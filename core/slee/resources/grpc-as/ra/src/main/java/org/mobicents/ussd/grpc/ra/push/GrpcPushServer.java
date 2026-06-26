/*
 * TeleStax, Open Source Cloud Communications
 *
 * High-throughput gRPC push ingress server (gateway acts as gRPC server, like HTTP servlet).
 * Tuned for 10k+ TPS: Netty shaded transport, dedicated executor, async SLEE fire (no lock.wait).
 */

package org.mobicents.ussd.grpc.ra.push;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.slee.facilities.Tracer;
import javax.slee.resource.ActivityAlreadyExistsException;
import javax.slee.resource.EventFlags;
import javax.slee.resource.FireableEventType;
import javax.slee.resource.SleeEndpoint;

import org.mobicents.ussd.grpc.GrpcEnvelopeCodec;
import org.mobicents.ussd.grpc.GrpcPushActivity;
import org.mobicents.ussd.grpc.events.GrpcPushReceivedEvent;
import org.mobicents.ussd.grpc.ra.GrpcAsResourceAdaptor;

import io.grpc.BindableService;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;

public final class GrpcPushServer {

    private static final int NO_INPUT_GENERATION = -1;

    public static final String EVENT_TYPE_NAME = "ussd.grpc.events.PUSH_RECEIVED";
    public static final String EVENT_VENDOR = "org.mobicents.ussd";
    public static final String EVENT_VERSION = "1.0";

    private final GrpcAsResourceAdaptor ra;
    private final Tracer tracer;
    private final SleeEndpoint sleeEndpoint;
    private final FireableEventType pushEventType;
    private final Executor executor;
    private final AtomicLong requestSeq = new AtomicLong();

    private volatile Server server;
    private volatile int port;
    private volatile int maxConcurrentCalls = 10000;

    public GrpcPushServer(GrpcAsResourceAdaptor ra, Tracer tracer, SleeEndpoint sleeEndpoint,
            FireableEventType pushEventType, int workerThreads) {
        this.ra = ra;
        this.tracer = tracer;
        this.sleeEndpoint = sleeEndpoint;
        this.pushEventType = pushEventType;
        int threads = workerThreads > 0 ? workerThreads : Math.max(8, Runtime.getRuntime().availableProcessors() * 4);
        this.executor = Executors.newFixedThreadPool(threads, new ThreadFactory() {
            private final AtomicLong n = new AtomicLong();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "ussd-grpc-push-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
    }

    public synchronized void start(int listenPort, int maxConcurrent) throws IOException {
        stop();
        this.port = listenPort;
        this.maxConcurrentCalls = maxConcurrent > 0 ? maxConcurrent : 10000;

        BindableService service = new BindableService() {
            @Override
            public ServerServiceDefinition bindService() {
                return ServerServiceDefinition.builder("ussd.UssdApplicationService")
                        .addMethod(GrpcAsResourceAdaptor.PROCESS_METHOD,
                                ServerCalls.asyncUnaryCall(GrpcPushServer.this::onProcess))
                        .build();
            }
        };

        server = NettyServerBuilder.forPort(listenPort)
                .executor(executor)
                .maxInboundMessageSize(4 * 1024 * 1024)
                .maxConcurrentCallsPerConnection(maxConcurrentCalls)
                .permitKeepAliveWithoutCalls(true)
                .permitKeepAliveTime(30, TimeUnit.SECONDS)
                .addService(service)
                .build()
                .start();

        if (tracer != null) {
            tracer.info("gRPC push server listening on port " + listenPort + ", maxConcurrent="
                    + maxConcurrentCalls + ", workers=" + ((java.util.concurrent.ThreadPoolExecutor) executor).getCorePoolSize());
        }
    }

    public synchronized void stop() {
        if (server != null) {
            try {
                server.shutdown();
                server.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                server.shutdownNow();
            } catch (Throwable t) {
                if (tracer != null) {
                    tracer.warning("gRPC push server shutdown", t);
                }
            }
            server = null;
        }
    }

    public int getPort() {
        return port;
    }

  private void onProcess(byte[] requestBytes, StreamObserver<byte[]> responseObserver) {
        try {
            Map<String, String> env = GrpcEnvelopeCodec.decode(requestBytes);
            boolean push = Boolean.parseBoolean(env.get(GrpcEnvelopeCodec.F_PUSH));
            if (!push) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("push server expects envelope push=true").asRuntimeException());
                return;
            }
            byte[] payload = GrpcEnvelopeCodec.decodePayload(env.get(GrpcEnvelopeCodec.F_PAYLOAD));
            String sessionId = env.get(GrpcEnvelopeCodec.F_SESSION_ID);
            String correlationId = env.get(GrpcEnvelopeCodec.F_CORRELATION_ID);
            if (correlationId == null) {
                correlationId = sessionId;
            }
            String requestId = env.get(GrpcEnvelopeCodec.F_REQUEST_ID);
            int networkId = 0;
            try {
                String nw = env.get(GrpcEnvelopeCodec.F_NETWORK_ID);
                if (nw != null) {
                    networkId = Integer.parseInt(nw);
                }
            } catch (NumberFormatException ignore) {
                // default 0
            }
            int inputGen = parseInputGen(env.get("inputGeneration"));

            final String activityId = "grpc-push-" + requestSeq.incrementAndGet();
            final GrpcPushActivityImpl[] activityHolder = new GrpcPushActivityImpl[1];
            activityHolder[0] = new GrpcPushActivityImpl(activityId, responseObserver,
                    new Runnable() {
                        @Override
                        public void run() {
                            endActivityQuiet(activityHolder[0]);
                        }
                    });
            final GrpcPushActivityImpl activity = activityHolder[0];

            ra.registerPushActivity(activity);
            try {
                sleeEndpoint.startActivity(activity, activity);
            } catch (ActivityAlreadyExistsException e) {
                // proceed
            }

            GrpcPushReceivedEvent event = new GrpcPushReceivedEvent(activity, sessionId, correlationId,
                    requestId, inputGen, networkId, payload);

            sleeEndpoint.fireEvent(activity, pushEventType, event, null, null,
                    EventFlags.REQUEST_EVENT_UNREFERENCED_CALLBACK);

        } catch (Throwable t) {
            if (tracer != null) {
                tracer.severe("gRPC push ingress failed", t);
            }
            responseObserver.onError(Status.INTERNAL.withDescription(t.getMessage()).asRuntimeException());
        }
    }

    private void endActivityQuiet(GrpcPushActivity activity) {
        try {
            sleeEndpoint.endActivity(activity);
        } catch (Throwable t) {
            if (tracer != null) {
                tracer.warning("Failed to end gRPC push activity", t);
            }
        }
    }

    private static int parseInputGen(String raw) {
        if (raw == null) {
            return NO_INPUT_GENERATION;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return NO_INPUT_GENERATION;
        }
    }
}
