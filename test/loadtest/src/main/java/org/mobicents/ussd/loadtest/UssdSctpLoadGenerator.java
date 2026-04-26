package org.mobicents.ussd.loadtest;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.sctp.SctpMessage;
import io.netty.channel.sctp.nio.NioSctpChannel;
import io.netty.util.AttributeKey;

import org.mobicents.ussdgateway.EventsSerializeFactory;
import org.mobicents.ussdgateway.XmlMAPDialog;
import org.restcomm.protocols.ss7.indicator.RoutingIndicator;
import org.restcomm.protocols.ss7.map.api.MAPApplicationContext;
import org.restcomm.protocols.ss7.map.api.MAPApplicationContextName;
import org.restcomm.protocols.ss7.map.api.MAPApplicationContextVersion;
import org.restcomm.protocols.ss7.map.api.datacoding.CBSDataCodingScheme;
import org.restcomm.protocols.ss7.map.api.primitives.AddressNature;
import org.restcomm.protocols.ss7.map.api.primitives.AddressString;
import org.restcomm.protocols.ss7.map.api.primitives.NumberingPlan;
import org.restcomm.protocols.ss7.map.api.primitives.USSDString;
import org.restcomm.protocols.ss7.map.datacoding.CBSDataCodingSchemeImpl;
import org.restcomm.protocols.ss7.map.primitives.AddressStringImpl;
import org.restcomm.protocols.ss7.map.primitives.ISDNAddressStringImpl;
import org.restcomm.protocols.ss7.map.primitives.USSDStringImpl;
import org.restcomm.protocols.ss7.map.service.supplementary.ProcessUnstructuredSSRequestImpl;
import org.restcomm.protocols.ss7.sccp.impl.parameter.SccpAddressImpl;
import org.restcomm.protocols.ss7.sccp.parameter.SccpAddress;
import org.restcomm.protocols.ss7.tcap.api.MessageType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * High-performance SCTP load generator for USSD Gateway.
 *
 * <p>Sends serialized XmlMAPDialog over SCTP to {@link org.mobicents.ussd.loadtest.stub.UssdSctpTestStub}
 * (or a real USSD Gateway SCTP endpoint), measures end-to-end latency.
 *
 * <p>Design:
 * <ul>
 *   <li>Netty SCTP client with multiple associations (one per worker)</li>
 *   <li>Async request/response with per-channel pending-time queues</li>
 *   <li>Nanosecond pacing via {@code LockSupport.parkNanos()}</li>
 *   <li>Lock-free metrics via {@link LoadTestMetrics}</li>
 * </ul>
 */
public class UssdSctpLoadGenerator {

    private static final org.apache.log4j.Logger logger =
            org.apache.log4j.Logger.getLogger(UssdSctpLoadGenerator.class);

    private static final AttributeKey<ChannelState> STATE_KEY =
            AttributeKey.valueOf("sctp.state");

    private final String host;
    private final int port;
    private final int targetTps;
    private final int workerThreads;
    private final int maxConcurrent;
    private final LoadTestMetrics metrics;
    private final EventsSerializeFactory serializeFactory;

    private final byte[] templateDialogBytes;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger concurrent = new AtomicInteger(0);

    private EventLoopGroup group;
    private List<ChannelState> channelStates;
    private ExecutorService executor;
    private ScheduledExecutorService reporter;

    public UssdSctpLoadGenerator(String host, int port, int targetTps,
                                  int workerThreads, int maxConcurrent,
                                  LoadTestMetrics metrics) throws Exception {
        this.host = host;
        this.port = port;
        this.targetTps = targetTps;
        this.workerThreads = workerThreads;
        this.maxConcurrent = maxConcurrent;
        this.metrics = metrics;
        this.serializeFactory = new EventsSerializeFactory();
        this.templateDialogBytes = buildTemplateDialog();
    }

    private byte[] buildTemplateDialog() throws Exception {
        CBSDataCodingScheme dcs = new CBSDataCodingSchemeImpl(0x0f);
        USSDString ussdStr = new USSDStringImpl("*100#", dcs, null);
        ProcessUnstructuredSSRequestImpl request = new ProcessUnstructuredSSRequestImpl(dcs, ussdStr, null, null);

        SccpAddress orgAddress = new SccpAddressImpl(RoutingIndicator.ROUTING_BASED_ON_DPC_AND_SSN, null, 1, 8);
        SccpAddress dstAddress = new SccpAddressImpl(RoutingIndicator.ROUTING_BASED_ON_DPC_AND_SSN, null, 2, 8);
        AddressString destRef = new AddressStringImpl(AddressNature.international_number, NumberingPlan.ISDN, "204208300008002");
        AddressString origRef = new AddressStringImpl(AddressNature.international_number, NumberingPlan.ISDN, "31628968300");

        MAPApplicationContext appCtx = MAPApplicationContext.getInstance(
                MAPApplicationContextName.networkUnstructuredSsContext,
                MAPApplicationContextVersion.version2);

        XmlMAPDialog dialog = new XmlMAPDialog(appCtx, orgAddress, dstAddress, 0L, 0L, destRef, origRef);
        dialog.addMAPMessage(request);
        dialog.setTCAPMessageType(MessageType.Begin);

        return serializeFactory.serialize(dialog);
    }

    public void connect() throws InterruptedException {
        group = new NioEventLoopGroup(workerThreads);
        channelStates = new ArrayList<>(workerThreads);

        Bootstrap b = new Bootstrap();
        b.group(group)
                .channel(NioSctpChannel.class)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast(new SctpClientHandler());
                    }
                });

        for (int i = 0; i < workerThreads; i++) {
            ChannelFuture future = b.connect(host, port).sync();
            Channel ch = future.channel();
            ChannelState state = new ChannelState(ch);
            ch.attr(STATE_KEY).set(state);
            channelStates.add(state);
            logger.info("SCTP association " + i + " connected to " + host + ":" + port);
        }
        System.out.println("[UssdSctpLoadGenerator] " + workerThreads + " SCTP associations connected.");
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            double tpsPerThread = (double) targetTps / workerThreads;
            long intervalNanos = (long) (1_000_000_000.0 / tpsPerThread);

            executor = Executors.newFixedThreadPool(workerThreads);
            for (int i = 0; i < workerThreads; i++) {
                executor.submit(new SctpWorker(i, intervalNanos));
            }

            reporter = Executors.newSingleThreadScheduledExecutor();
            reporter.scheduleAtFixedRate(() -> {
                System.out.println(metrics.report() + " | Concurrent=" + concurrent.get());
            }, 1, 1, TimeUnit.SECONDS);

            logger.info("SCTP LoadGenerator started: threads=" + workerThreads +
                    ", targetTps=" + targetTps + ", target=" + host + ":" + port);
        }
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (executor != null) {
                executor.shutdownNow();
                try { executor.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            }
            if (reporter != null) {
                reporter.shutdown();
                try { reporter.awaitTermination(1, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            }
            if (channelStates != null) {
                for (ChannelState state : channelStates) {
                    state.channel.close();
                }
            }
            if (group != null) {
                group.shutdownGracefully();
            }
            logger.info("SCTP LoadGenerator stopped.");
        }
    }

    /**
     * Per-channel state to track pending requests for latency measurement.
     */
    private static class ChannelState {
        final Channel channel;
        final ConcurrentLinkedQueue<Long> pendingTimes = new ConcurrentLinkedQueue<>();

        ChannelState(Channel channel) {
            this.channel = channel;
        }
    }

    /**
     * Netty handler that receives SCTP responses and records latency.
     */
    private class SctpClientHandler extends SimpleChannelInboundHandler<SctpMessage> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, SctpMessage msg) {
            ChannelState state = ctx.channel().attr(STATE_KEY).get();
            if (state == null) {
                concurrent.decrementAndGet();
                metrics.recordError();
                return;
            }

            Long startTime = state.pendingTimes.poll();
            if (startTime != null) {
                long latency = System.nanoTime() - startTime;
                metrics.recordResponse(latency);
                concurrent.decrementAndGet();
            } else {
                // Response without matching request (should not happen)
                concurrent.decrementAndGet();
                metrics.recordError();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logger.error("SCTP client exception", cause);
            concurrent.decrementAndGet();
            metrics.recordError();
            ctx.close();
        }
    }

    /**
     * Worker thread that paces requests on its dedicated SCTP association.
     */
    private class SctpWorker implements Runnable {
        private final int workerIndex;
        private final long intervalNanos;

        SctpWorker(int workerIndex, long intervalNanos) {
            this.workerIndex = workerIndex;
            this.intervalNanos = intervalNanos;
        }

        @Override
        public void run() {
            ChannelState state = channelStates.get(workerIndex);
            while (running.get()) {
                long loopStart = System.nanoTime();

                if (concurrent.get() >= maxConcurrent) {
                    metrics.recordError();
                } else {
                    sendRequest(state);
                }

                long elapsed = System.nanoTime() - loopStart;
                long sleep = intervalNanos - elapsed;
                if (sleep > 0) {
                    LockSupport.parkNanos(sleep);
                }
            }
        }

        private void sendRequest(ChannelState state) {
            concurrent.incrementAndGet();
            state.pendingTimes.offer(System.nanoTime());

            ByteBuf buf = Unpooled.wrappedBuffer(templateDialogBytes.clone());
            SctpMessage msg = new SctpMessage(0, 0, buf);
            state.channel.writeAndFlush(msg);

            metrics.recordRequest();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Usage: UssdSctpLoadGenerator <host> <port> <targetTps> [threads] [maxConcurrent]");
            System.out.println("  host          - SCTP server host (e.g. localhost)");
            System.out.println("  port          - SCTP server port (e.g. 8081)");
            System.out.println("  targetTps     - Target transactions per second");
            System.out.println("  threads       - SCTP associations / worker threads (default: targetTps/500)");
            System.out.println("  maxConcurrent - Max in-flight requests (default: targetTps*5)");
            System.exit(1);
        }

        String host = args[0];
        int port = Integer.parseInt(args[1]);
        int tps = Integer.parseInt(args[2]);
        int threads = args.length > 3 ? Integer.parseInt(args[3]) : Math.max(4, tps / 500);
        int maxConcurrent = args.length > 4 ? Integer.parseInt(args[4]) : tps * 5;

        LoadTestMetrics metrics = new LoadTestMetrics();
        UssdSctpLoadGenerator generator = new UssdSctpLoadGenerator(
                host, port, tps, threads, maxConcurrent, metrics);

        System.out.println("=== USSD SCTP Load Generator ===");
        System.out.println("Target: " + host + ":" + port);
        System.out.println("Target TPS: " + tps);
        System.out.println("Associations: " + threads);
        System.out.println("Max Concurrent: " + maxConcurrent);
        System.out.println("Press Enter to connect...");
        System.in.read();

        generator.connect();

        System.out.println("Press Enter to start...");
        System.in.read();

        generator.start();

        System.out.println("Running... Press Enter to stop.");
        System.in.read();

        generator.stop();
        System.out.println("\nFinal Report:");
        System.out.println(metrics.report());
    }
}
