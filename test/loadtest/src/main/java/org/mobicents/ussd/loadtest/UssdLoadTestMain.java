package org.mobicents.ussd.loadtest;

import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Logger;
import org.restcomm.protocols.ss7.map.MAPStackImpl;
import org.restcomm.protocols.ss7.map.api.MAPProvider;
import org.restcomm.protocols.ss7.sccp.SccpProvider;
import org.restcomm.protocols.ss7.sccp.impl.SccpStackImpl;
import org.restcomm.protocols.ss7.sccp.impl.parameter.SccpAddressImpl;
import org.restcomm.protocols.ss7.sccp.parameter.SccpAddress;
import org.restcomm.protocols.ss7.indicator.RoutingIndicator;
import org.restcomm.protocols.ss7.sccp.parameter.GlobalTitle;
import org.restcomm.protocols.ss7.sccp.impl.parameter.ParameterFactoryImpl;
import org.restcomm.protocols.ss7.indicator.NumberingPlan;
import org.restcomm.protocols.ss7.indicator.NatureOfAddress;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * USSD Load Test Main Entry Point - Target 10k TPS.
 * 
 * This tool can run in two modes:
 * 1. SERVER mode: Receives USSD requests and auto-responds (optimized for high throughput)
 * 2. CLIENT mode: Generates USSD load with multi-threaded generator
 * 
 * For real SS7 testing, this tool should be integrated with test/bootstrap
 * which provides proper SCTP/M3UA/SCCP stack configuration.
 * 
 * For local benchmark testing, it can create a minimal loopback SCCP stack.
 * 
 * Usage examples:
 *   Server mode: java -jar loadtest.jar --server --ssn 8
 *   Client mode: java -jar loadtest.jar --client --tps 10000 --threads 20 --remote-ip 127.0.0.1 --remote-port 2905
 */
public class UssdLoadTestMain {

    private static final Logger logger = Logger.getLogger(UssdLoadTestMain.class);

    private MAPStackImpl mapStack;
    private SccpStackImpl sccpStack;
    private LoadTestMetrics metrics;
    private UssdLoadGenerator generator;
    private ScheduledExecutorService reporter;

    public static void main(String[] args) throws Exception {
        BasicConfigurator.configure();
        
        // Parse arguments
        boolean serverMode = false;
        int targetTps = 10000;
        int workerThreads = 20;
        int ssn = 8;
        int localSpc = 1;
        int remoteSpc = 2;
        String localIp = "127.0.0.1";
        String remoteIp = "127.0.0.1";
        int localPort = 2905;
        int remotePort = 2906;
        String ussdMessage = "*100#";
        int maxConcurrentDialogs = 50000;
        boolean loopback = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--server".equals(arg)) serverMode = true;
            else if ("--client".equals(arg)) serverMode = false;
            else if ("--tps".equals(arg)) targetTps = Integer.parseInt(args[++i]);
            else if ("--threads".equals(arg)) workerThreads = Integer.parseInt(args[++i]);
            else if ("--ssn".equals(arg)) ssn = Integer.parseInt(args[++i]);
            else if ("--local-spc".equals(arg)) localSpc = Integer.parseInt(args[++i]);
            else if ("--remote-spc".equals(arg)) remoteSpc = Integer.parseInt(args[++i]);
            else if ("--local-ip".equals(arg)) localIp = args[++i];
            else if ("--remote-ip".equals(arg)) remoteIp = args[++i];
            else if ("--local-port".equals(arg)) localPort = Integer.parseInt(args[++i]);
            else if ("--remote-port".equals(arg)) remotePort = Integer.parseInt(args[++i]);
            else if ("--message".equals(arg)) ussdMessage = args[++i];
            else if ("--max-dialogs".equals(arg)) maxConcurrentDialogs = Integer.parseInt(args[++i]);
            else if ("--loopback".equals(arg)) loopback = true;
            else if ("--help".equals(arg)) {
                printUsage();
                return;
            }
        }

        UssdLoadTestMain main = new UssdLoadTestMain();

        if (loopback) {
            // Loopback mode: minimal SCCP stack without real SCTP/M3UA
            // Suitable for local benchmark of dialog creation throughput
            System.out.println("=== USSD Load Test - LOOPBACK MODE ===");
            System.out.println("WARNING: Loopback mode does not send real SS7 messages.");
            System.out.println("Use test/bootstrap with real SCTP/M3UA config for end-to-end testing.\n");
            main.initLoopback(ssn);
        } else {
            System.out.println("=== USSD Load Test - REAL SS7 MODE ===");
            System.out.println("Please ensure SCTP/M3UA/SCCP stacks are configured via test/bootstrap.");
            System.out.println("This mode requires external SS7 stack initialization.\n");
            printUsage();
            return;
        }

        MAPProvider mapProvider = main.mapStack.getMAPProvider();
        mapProvider.getMAPServiceSupplementary().activate();

        main.metrics = new LoadTestMetrics();
        main.reporter = Executors.newSingleThreadScheduledExecutor();
        main.reporter.scheduleAtFixedRate(() -> {
            System.out.println(main.metrics.report());
        }, 1, 1, TimeUnit.SECONDS);

        if (serverMode) {
            System.out.println("Starting USSD Load Server [SSN=" + ssn + "]...");
            UssdLoadServer server = new UssdLoadServer(main.metrics, mapProvider.getMAPErrorMessageFactory());
            mapProvider.addMAPDialogListener(server);
            mapProvider.getMAPServiceSupplementary().addMAPServiceListener(server);
            System.out.println("Server ready. Waiting for USSD requests...\n");
        } else {
            System.out.println("Starting USSD Load Generator [targetTps=" + targetTps + ", threads=" + workerThreads + "]...");
            
            // Create SCCP addresses
            ParameterFactoryImpl sccpFactory = new ParameterFactoryImpl();
            org.restcomm.protocols.ss7.sccp.parameter.EncodingScheme ec = new org.restcomm.protocols.ss7.sccp.impl.parameter.BCDEvenEncodingScheme();
            GlobalTitle origGt = sccpFactory.createGlobalTitle("0000000000", 0, NumberingPlan.ISDN_TELEPHONY, ec, NatureOfAddress.INTERNATIONAL);
            GlobalTitle destGt = sccpFactory.createGlobalTitle("0000000001", 0, NumberingPlan.ISDN_TELEPHONY, ec, NatureOfAddress.INTERNATIONAL);
            SccpAddress origAddress = new SccpAddressImpl(RoutingIndicator.ROUTING_BASED_ON_GLOBAL_TITLE, origGt, localSpc, ssn);
            SccpAddress destAddress = new SccpAddressImpl(RoutingIndicator.ROUTING_BASED_ON_GLOBAL_TITLE, destGt, remoteSpc, ssn);

            main.generator = new UssdLoadGenerator(
                mapProvider, origAddress, destAddress,
                null, null, main.metrics,
                targetTps, workerThreads, ussdMessage, 15, maxConcurrentDialogs
            );
            main.generator.start();
            System.out.println("Generator started. Sending USSD requests...\n");
        }

        System.out.println("Press Enter to stop...");
        System.in.read();

        System.out.println("\nStopping load test...");
        if (main.generator != null) {
            main.generator.stop();
        }
        main.reporter.shutdown();
        main.mapStack.stop();
        if (main.sccpStack != null) {
            main.sccpStack.stop();
        }
        System.out.println("Load test stopped.");
        System.out.println("Final stats: " + main.metrics.report());
    }

    private void initLoopback(int ssn) throws Exception {
        // Create SCCP stack without Mtp3UserPart for local loopback testing
        // Note: This only tests dialog creation, not real message transport
        sccpStack = new SccpStackImpl("LoadTestSCCP", null);
        sccpStack.start();
        sccpStack.removeAllResources();

        mapStack = new MAPStackImpl("LoadTestMAP", sccpStack.getSccpProvider(), ssn);
        mapStack.start();
        mapStack.getTCAPStack().setMaxDialogs(1000000);
    }

    private static void printUsage() {
        System.out.println("Usage: java -jar loadtest.jar [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --server                Run as USSD server (auto-responder)");
        System.out.println("  --client                Run as USSD load generator (default)");
        System.out.println("  --tps <n>               Target TPS (default: 10000)");
        System.out.println("  --threads <n>           Number of worker threads (default: 20)");
        System.out.println("  --ssn <n>               Local SSN (default: 8)");
        System.out.println("  --local-spc <n>         Local Signaling Point Code (default: 1)");
        System.out.println("  --remote-spc <n>        Remote Signaling Point Code (default: 2)");
        System.out.println("  --local-ip <ip>         Local IP address (default: 127.0.0.1)");
        System.out.println("  --remote-ip <ip>        Remote IP address (default: 127.0.0.1)");
        System.out.println("  --local-port <n>        Local SCTP port (default: 2905)");
        System.out.println("  --remote-port <n>       Remote SCTP port (default: 2906)");
        System.out.println("  --message <text>        USSD message to send (default: *100#)");
        System.out.println("  --max-dialogs <n>       Max concurrent dialogs (default: 50000)");
        System.out.println("  --loopback              Use loopback mode (no real SS7 transport)");
        System.out.println("  --help                  Show this help");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  # Server mode (loopback):");
        System.out.println("  java -jar loadtest.jar --server --loopback --ssn 8");
        System.out.println();
        System.out.println("  # Client mode (loopback), target 10k TPS:");
        System.out.println("  java -jar loadtest.jar --client --loopback --tps 10000 --threads 20");
        System.out.println();
        System.out.println("  # Real SS7 mode (requires test/bootstrap stack):");
        System.out.println("  # 1. Start ussdgateway/test/bootstrap with SS7 configuration");
        System.out.println("  # 2. Integrate UssdLoadGenerator/UssdLoadServer via JMX or direct injection");
    }
}
