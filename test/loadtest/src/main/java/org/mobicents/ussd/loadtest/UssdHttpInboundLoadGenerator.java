package org.mobicents.ussd.loadtest;

import okhttp3.*;
import org.mobicents.ussdgateway.EventsSerializeFactory;
import org.mobicents.ussdgateway.XmlMAPDialog;
import org.restcomm.protocols.ss7.indicator.RoutingIndicator;
import org.restcomm.protocols.ss7.map.api.MAPApplicationContext;
import org.restcomm.protocols.ss7.map.api.MAPApplicationContextName;
import org.restcomm.protocols.ss7.map.api.MAPApplicationContextVersion;
import org.restcomm.protocols.ss7.map.api.datacoding.CBSDataCodingScheme;
import org.restcomm.protocols.ss7.map.api.primitives.AddressNature;
import org.restcomm.protocols.ss7.map.api.primitives.AddressString;
import org.restcomm.protocols.ss7.map.api.primitives.ISDNAddressString;
import org.restcomm.protocols.ss7.map.api.primitives.NumberingPlan;
import org.restcomm.protocols.ss7.map.api.primitives.USSDString;
import org.restcomm.protocols.ss7.map.datacoding.CBSDataCodingSchemeImpl;
import org.restcomm.protocols.ss7.map.primitives.AddressStringImpl;
import org.restcomm.protocols.ss7.map.primitives.ISDNAddressStringImpl;
import org.restcomm.protocols.ss7.map.primitives.USSDStringImpl;
import org.restcomm.protocols.ss7.map.service.supplementary.UnstructuredSSRequestImpl;
import org.restcomm.protocols.ss7.sccp.impl.parameter.SccpAddressImpl;
import org.restcomm.protocols.ss7.sccp.parameter.SccpAddress;
import org.restcomm.protocols.ss7.tcap.api.MessageType;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * High-performance HTTP inbound load generator for USSD Gateway.
 *
 * <p>Simulates an external HTTP application (VAS platform) sending
 * USSD Push (network-initiated) requests to the USSD Gateway HTTP interface.
 *
 * <p>Flow:
 * <pre>
 *   HTTP Inbound Load Generator (this class)
 *           ↓ POST UnstructuredSSRequest
 *   USSD Gateway (HTTP Servlet RA → HttpServerSbb → MAP RA)
 *           ↓ SS7 MAP
 *   SS7 Responder / MSC (UssdLoadServer or real network)
 *           ↓ SS7 MAP Response
 *   USSD Gateway
 *           ↓ HTTP Response
 *   Load Generator (records success)
 * </pre>
 *
 * <p>Only records <b>SUCCESS</b> when a valid HTTP response is received.
 *
 * <p>Usage:
 * <pre>
 *   java -cp ussd-loadtest-10k.jar org.mobicents.ussd.loadtest.UssdHttpInboundLoadGenerator \
 *     http://localhost:8080/restcomm/ 10000 20 50000
 * </pre>
 */
public class UssdHttpInboundLoadGenerator {

    private static final org.apache.log4j.Logger logger =
            org.apache.log4j.Logger.getLogger(UssdHttpInboundLoadGenerator.class);
    private static final MediaType XML_MEDIA_TYPE = MediaType.parse("text/xml; charset=utf-8");

    private final String baseUrl;
    private final int targetTps;
    private final int workerThreads;
    private final int maxConcurrent;
    private final LoadTestMetrics metrics;
    private final EventsSerializeFactory serializeFactory;

    private final OkHttpClient httpClient;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executor;
    private ScheduledExecutorService reporter;

    private final AtomicInteger concurrent = new AtomicInteger(0);
    private final Random random = new Random();

    // Pre-built template dialog to avoid object creation in hot path
    private final byte[] templateDialogBytes;

    public UssdHttpInboundLoadGenerator(String baseUrl, int targetTps, int workerThreads,
                                         int maxConcurrent, LoadTestMetrics metrics) throws Exception {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.targetTps = targetTps;
        this.workerThreads = workerThreads;
        this.maxConcurrent = maxConcurrent;
        this.metrics = metrics;
        this.serializeFactory = new EventsSerializeFactory();

        // High-performance OkHttp config
        ConnectionPool connectionPool = new ConnectionPool(
                workerThreads * 2,
                5,
                TimeUnit.MINUTES
        );

        Dispatcher dispatcher = new Dispatcher(
                Executors.newFixedThreadPool(workerThreads * 2)
        );
        dispatcher.setMaxRequests(workerThreads * 100);
        dispatcher.setMaxRequestsPerHost(workerThreads * 100);

        this.httpClient = new OkHttpClient.Builder()
                .connectionPool(connectionPool)
                .dispatcher(dispatcher)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .protocols(java.util.Collections.singletonList(Protocol.HTTP_1_1))
                .build();

        this.templateDialogBytes = buildTemplateDialog();
    }

    private byte[] buildTemplateDialog() throws Exception {
        CBSDataCodingScheme dcs = new CBSDataCodingSchemeImpl(0x0f);
        USSDString ussdStr = new USSDStringImpl("Welcome! Press 1 for Balance, 2 for Data", dcs, null);
        UnstructuredSSRequestImpl request = new UnstructuredSSRequestImpl(dcs, ussdStr, null, null);

        // MSISDN of target subscriber
        ISDNAddressString msisdn = new ISDNAddressStringImpl(
                AddressNature.international_number, NumberingPlan.ISDN, "31628968300");
        request.setMSISDNAddressString(msisdn);

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

    public void start() {
        if (running.compareAndSet(false, true)) {
            executor = Executors.newFixedThreadPool(workerThreads);
            double tpsPerThread = (double) targetTps / workerThreads;
            long intervalNanos = (long) (1_000_000_000.0 / tpsPerThread);

            for (int i = 0; i < workerThreads; i++) {
                executor.submit(new HttpWorker(intervalNanos));
            }

            reporter = Executors.newSingleThreadScheduledExecutor();
            reporter.scheduleAtFixedRate(() -> {
                System.out.println(metrics.report() + " | Concurrent=" + concurrent.get());
            }, 1, 1, TimeUnit.SECONDS);

            logger.info("HTTP Inbound LoadGenerator started: threads=" + workerThreads +
                    ", targetTps=" + targetTps + ", url=" + baseUrl);
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
            if (httpClient != null) {
                httpClient.dispatcher().executorService().shutdown();
                httpClient.connectionPool().evictAll();
            }
            logger.info("HTTP Inbound LoadGenerator stopped.");
        }
    }

    private void sendRequest() {
        if (concurrent.get() >= maxConcurrent) {
            metrics.recordError();
            return;
        }

        concurrent.incrementAndGet();
        long startTime = System.nanoTime();

        byte[] body = templateDialogBytes.clone();

        Request request = new Request.Builder()
                .url(baseUrl)
                .post(RequestBody.create(XML_MEDIA_TYPE, body))
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                concurrent.decrementAndGet();
                metrics.recordError();
            }
            @Override public void onResponse(Call call, Response response) {
                concurrent.decrementAndGet();
                try (ResponseBody rb = response.body()) {
                    if (response.isSuccessful()) {
                        long latency = System.nanoTime() - startTime;
                        metrics.recordResponse(latency);
                    } else {
                        metrics.recordError();
                    }
                }
            }
        });

        metrics.recordRequest();
    }

    private class HttpWorker implements Runnable {
        private final long intervalNanos;
        HttpWorker(long intervalNanos) { this.intervalNanos = intervalNanos; }

        @Override
        public void run() {
            while (running.get()) {
                long start = System.nanoTime();
                sendRequest();
                long elapsed = System.nanoTime() - start;
                long sleep = intervalNanos - elapsed;
                if (sleep > 0) {
                    LockSupport.parkNanos(sleep);
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: UssdHttpInboundLoadGenerator <url> <targetTps> [threads] [maxConcurrent]");
            System.out.println("  url           - USSD Gateway inbound HTTP endpoint (e.g. http://localhost:8080/restcomm/)");
            System.out.println("  targetTps     - Target transactions per second");
            System.out.println("  threads       - Worker threads (default: targetTps/500)");
            System.out.println("  maxConcurrent - Max concurrent requests (default: targetTps*5)");
            System.out.println();
            System.out.println("Example (USSD Push to Gateway):");
            System.out.println("  java -cp ussd-loadtest-10k.jar org.mobicents.ussd.loadtest.UssdHttpInboundLoadGenerator \\");
            System.out.println("    http://localhost:8080/restcomm/ 10000 20 50000");
            System.exit(1);
        }

        String url = args[0];
        int tps = Integer.parseInt(args[1]);
        int threads = args.length > 2 ? Integer.parseInt(args[2]) : Math.max(4, tps / 500);
        int maxConcurrent = args.length > 3 ? Integer.parseInt(args[3]) : tps * 5;

        LoadTestMetrics metrics = new LoadTestMetrics();
        UssdHttpInboundLoadGenerator generator = new UssdHttpInboundLoadGenerator(
                url, tps, threads, maxConcurrent, metrics);

        System.out.println("=== USSD HTTP Inbound Load Generator (USSD Push) ===");
        System.out.println("URL: " + url);
        System.out.println("Target TPS: " + tps);
        System.out.println("Threads: " + threads);
        System.out.println("Max Concurrent: " + maxConcurrent);
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
