package org.mobicents.ussd.loadtest;

import org.apache.log4j.Logger;
import org.restcomm.protocols.ss7.map.api.MAPApplicationContext;
import org.restcomm.protocols.ss7.map.api.MAPApplicationContextName;
import org.restcomm.protocols.ss7.map.api.MAPApplicationContextVersion;
import org.restcomm.protocols.ss7.map.api.MAPDialog;
import org.restcomm.protocols.ss7.map.api.MAPDialogListener;
import org.restcomm.protocols.ss7.map.api.MAPException;
import org.restcomm.protocols.ss7.map.api.MAPMessage;
import org.restcomm.protocols.ss7.map.api.MAPProvider;
import org.restcomm.protocols.ss7.map.api.datacoding.CBSDataCodingScheme;
import org.restcomm.protocols.ss7.map.api.dialog.MAPAbortProviderReason;
import org.restcomm.protocols.ss7.map.api.dialog.MAPAbortSource;
import org.restcomm.protocols.ss7.map.api.dialog.MAPNoticeProblemDiagnostic;
import org.restcomm.protocols.ss7.map.api.dialog.MAPRefuseReason;
import org.restcomm.protocols.ss7.map.api.dialog.MAPUserAbortChoice;
import org.restcomm.protocols.ss7.map.api.errors.MAPErrorMessage;
import org.restcomm.protocols.ss7.map.api.primitives.AddressString;
import org.restcomm.protocols.ss7.map.api.primitives.AddressNature;
import org.restcomm.protocols.ss7.map.api.primitives.ISDNAddressString;
import org.restcomm.protocols.ss7.map.api.primitives.MAPExtensionContainer;
import org.restcomm.protocols.ss7.map.api.primitives.NumberingPlan;
import org.restcomm.protocols.ss7.map.api.primitives.USSDString;
import org.restcomm.protocols.ss7.map.api.service.supplementary.MAPDialogSupplementary;
import org.restcomm.protocols.ss7.map.api.service.supplementary.MAPServiceSupplementaryListener;
import org.restcomm.protocols.ss7.map.api.service.supplementary.ProcessUnstructuredSSRequest;
import org.restcomm.protocols.ss7.map.api.service.supplementary.ProcessUnstructuredSSResponse;
import org.restcomm.protocols.ss7.map.api.service.supplementary.UnstructuredSSNotifyRequest;
import org.restcomm.protocols.ss7.map.api.service.supplementary.UnstructuredSSNotifyResponse;
import org.restcomm.protocols.ss7.map.api.service.supplementary.UnstructuredSSRequest;
import org.restcomm.protocols.ss7.map.api.service.supplementary.UnstructuredSSResponse;
import org.restcomm.protocols.ss7.map.datacoding.CBSDataCodingSchemeImpl;
import org.restcomm.protocols.ss7.map.primitives.ISDNAddressStringImpl;
import org.restcomm.protocols.ss7.map.primitives.USSDStringImpl;
import org.restcomm.protocols.ss7.sccp.parameter.SccpAddress;
import org.restcomm.protocols.ss7.tcap.asn.ApplicationContextName;
import org.restcomm.protocols.ss7.tcap.asn.comp.Problem;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;

/**
 * Multi-threaded USSD load generator targeting 10k+ TPS.
 * 
 * Design:
 * - N worker threads create MAP dialogs and send ProcessUnstructuredSSRequest
 * - Rate-limited to target TPS
 * - Measures end-to-end latency (request sent -> response received)
 * - Uses concurrent map to track pending dialogs with timestamps
 */
public class UssdLoadGenerator implements MAPDialogListener, MAPServiceSupplementaryListener {

    private static final Logger logger = Logger.getLogger(UssdLoadGenerator.class);
    private static final boolean DEBUG = logger.isDebugEnabled();

    private final MAPProvider mapProvider;
    private final SccpAddress origAddress;
    private final SccpAddress destAddress;
    private final AddressString origReference;
    private final AddressString destReference;
    private final LoadTestMetrics metrics;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executor;

    // Target TPS and thread configuration
    private final int targetTps;
    private final int workerThreads;
    private final String ussdMessage;
    private final int dataCodingScheme;

    // Track pending dialogs for latency measurement
    private final ConcurrentHashMap<Long, Long> pendingDialogs = new ConcurrentHashMap<>();
    private final AtomicInteger concurrentDialogs = new AtomicInteger(0);
    private final int maxConcurrentDialogs;

    private final MAPApplicationContext ussdAppContext;
    private final CBSDataCodingScheme dcs;
    private final USSDString ussdString;
    private final ISDNAddressString msisdn;

    public UssdLoadGenerator(MAPProvider mapProvider, SccpAddress origAddress, SccpAddress destAddress,
                             AddressString origReference, AddressString destReference,
                             LoadTestMetrics metrics, int targetTps, int workerThreads,
                             String ussdMessage, int dataCodingScheme, int maxConcurrentDialogs) throws MAPException {
        this.mapProvider = mapProvider;
        this.origAddress = origAddress;
        this.destAddress = destAddress;
        this.origReference = origReference;
        this.destReference = destReference;
        this.metrics = metrics;
        this.targetTps = targetTps;
        this.workerThreads = workerThreads;
        this.ussdMessage = ussdMessage;
        this.dataCodingScheme = dataCodingScheme;
        this.maxConcurrentDialogs = maxConcurrentDialogs;

        this.ussdAppContext = MAPApplicationContext.getInstance(
                MAPApplicationContextName.networkUnstructuredSsContext,
                MAPApplicationContextVersion.version2);
        this.dcs = new CBSDataCodingSchemeImpl(dataCodingScheme);
        this.ussdString = mapProvider.getMAPParameterFactory().createUSSDString(ussdMessage, dcs, null);
        this.msisdn = new ISDNAddressStringImpl(AddressNature.international_number, NumberingPlan.ISDN, "0000000000");
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            mapProvider.addMAPDialogListener(this);
            mapProvider.getMAPServiceSupplementary().addMAPServiceListener(this);

            executor = Executors.newFixedThreadPool(workerThreads);
            double tpsPerThread = (double) targetTps / workerThreads;
            long intervalNanos = (long) (1_000_000_000.0 / tpsPerThread);

            for (int i = 0; i < workerThreads; i++) {
                executor.submit(new Worker(intervalNanos));
            }

            logger.info("LoadGenerator started: threads=" + workerThreads + ", targetTps=" + targetTps
                    + ", intervalNanos=" + intervalNanos);
        }
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (executor != null) {
                executor.shutdownNow();
                try {
                    executor.awaitTermination(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            mapProvider.getMAPServiceSupplementary().removeMAPServiceListener(this);
            mapProvider.removeMAPDialogListener(this);
            logger.info("LoadGenerator stopped. Pending dialogs: " + pendingDialogs.size());
        }
    }

    /**
     * Send a single ProcessUnstructuredSSRequest.
     */
    private void sendRequest() {
        // Check max concurrent dialogs to avoid overwhelming the stack
        if (concurrentDialogs.get() >= maxConcurrentDialogs) {
            metrics.recordError();
            if (DEBUG) {
                logger.debug("Max concurrent dialogs reached (" + maxConcurrentDialogs + "), request dropped");
            }
            return;
        }

        try {
            MAPDialogSupplementary dialog = mapProvider.getMAPServiceSupplementary().createNewDialog(
                    ussdAppContext, origAddress, origReference, destAddress, destReference);

            dialog.addProcessUnstructuredSSRequest(dcs, ussdString, null, msisdn);
            
            long dialogId = dialog.getLocalDialogId();
            long startTime = System.nanoTime();
            pendingDialogs.put(dialogId, startTime);
            concurrentDialogs.incrementAndGet();
            
            dialog.send();
            metrics.recordRequest();

            if (DEBUG) {
                logger.debug("Sent ProcessUnstructuredSSRequest, dialogId=" + dialogId);
            }
        } catch (MAPException e) {
            metrics.recordError();
            if (DEBUG) {
                logger.debug("Error sending request", e);
            }
        }
    }

    private void recordResponse(long dialogId) {
        Long startTime = pendingDialogs.remove(dialogId);
        if (startTime != null) {
            concurrentDialogs.decrementAndGet();
            long latency = System.nanoTime() - startTime;
            metrics.recordResponse(latency);
        }
    }

    @Override
    public void onProcessUnstructuredSSResponse(ProcessUnstructuredSSResponse response) {
        recordResponse(response.getMAPDialog().getLocalDialogId());
    }

    @Override
    public void onDialogRelease(MAPDialog mapDialog) {
        // If dialog released without response, clean up
        Long startTime = pendingDialogs.remove(mapDialog.getLocalDialogId());
        if (startTime != null) {
            concurrentDialogs.decrementAndGet();
        }
    }

    @Override
    public void onDialogTimeout(MAPDialog mapDialog) {
        Long startTime = pendingDialogs.remove(mapDialog.getLocalDialogId());
        if (startTime != null) {
            concurrentDialogs.decrementAndGet();
            metrics.recordError();
        }
    }

    @Override
    public void onDialogReject(MAPDialog mapDialog, MAPRefuseReason refuseReason,
                               ApplicationContextName alternativeApplicationContext,
                               MAPExtensionContainer extensionContainer) {
        Long startTime = pendingDialogs.remove(mapDialog.getLocalDialogId());
        if (startTime != null) {
            concurrentDialogs.decrementAndGet();
            metrics.recordError();
        }
    }

    @Override
    public void onErrorComponent(MAPDialog mapDialog, Long invokeId, MAPErrorMessage mapErrorMessage) {
        Long startTime = pendingDialogs.remove(mapDialog.getLocalDialogId());
        if (startTime != null) {
            concurrentDialogs.decrementAndGet();
            metrics.recordError();
        }
    }

    @Override
    public void onRejectComponent(MAPDialog mapDialog, Long invokeId, Problem problem, boolean isLocalOriginated) {
        Long startTime = pendingDialogs.remove(mapDialog.getLocalDialogId());
        if (startTime != null) {
            concurrentDialogs.decrementAndGet();
            metrics.recordError();
        }
    }

    @Override
    public void onDialogUserAbort(MAPDialog mapDialog, MAPUserAbortChoice userAbortChoice, MAPExtensionContainer extensionContainer) {
        Long startTime = pendingDialogs.remove(mapDialog.getLocalDialogId());
        if (startTime != null) {
            concurrentDialogs.decrementAndGet();
            metrics.recordError();
        }
    }

    @Override
    public void onDialogProviderAbort(MAPDialog mapDialog, MAPAbortProviderReason abortProviderReason,
                                      MAPAbortSource abortSource, MAPExtensionContainer extensionContainer) {
        Long startTime = pendingDialogs.remove(mapDialog.getLocalDialogId());
        if (startTime != null) {
            concurrentDialogs.decrementAndGet();
            metrics.recordError();
        }
    }

    private class Worker implements Runnable {
        private final long intervalNanos;

        Worker(long intervalNanos) {
            this.intervalNanos = intervalNanos;
        }

        @Override
        public void run() {
            while (running.get()) {
                long start = System.nanoTime();
                sendRequest();
                long elapsed = System.nanoTime() - start;
                long sleepTime = intervalNanos - elapsed;
                if (sleepTime > 0) {
                    busySleep(sleepTime);
                }
            }
        }

        private void busySleep(long nanos) {
            LockSupport.parkNanos(nanos);
        }
    }

    // Unused MAPDialogListener methods
    @Override public void onDialogRequest(MAPDialog mapDialog, AddressString destReference, AddressString origReference, MAPExtensionContainer extensionContainer) {}
    @Override public void onDialogRequestEricsson(MAPDialog mapDialog, AddressString destReference, AddressString origReference, AddressString ericssonMsisdn, AddressString ericssonVlrNo) {}
    @Override public void onDialogAccept(MAPDialog mapDialog, MAPExtensionContainer extensionContainer) {}
    @Override public void onDialogClose(MAPDialog mapDialog) {}
    @Override public void onDialogNotice(MAPDialog mapDialog, MAPNoticeProblemDiagnostic noticeProblemDiagnostic) {}
    @Override public void onDialogDelimiter(MAPDialog mapDialog) {}
    @Override public void onMAPMessage(MAPMessage mapMessage) {}
    @Override public void onInvokeTimeout(MAPDialog mapDialog, Long invokeId) {}

    // Unused MAPServiceSupplementaryListener methods
    @Override public void onProcessUnstructuredSSRequest(ProcessUnstructuredSSRequest request) {}
    @Override public void onUnstructuredSSRequest(UnstructuredSSRequest request) {}
    @Override public void onUnstructuredSSResponse(UnstructuredSSResponse response) {}
    @Override public void onUnstructuredSSNotifyRequest(UnstructuredSSNotifyRequest request) {}
    @Override public void onUnstructuredSSNotifyResponse(UnstructuredSSNotifyResponse response) {}
    @Override public void onRegisterSSRequest(org.restcomm.protocols.ss7.map.api.service.supplementary.RegisterSSRequest request) {}
    @Override public void onRegisterSSResponse(org.restcomm.protocols.ss7.map.api.service.supplementary.RegisterSSResponse response) {}
    @Override public void onEraseSSRequest(org.restcomm.protocols.ss7.map.api.service.supplementary.EraseSSRequest request) {}
    @Override public void onEraseSSResponse(org.restcomm.protocols.ss7.map.api.service.supplementary.EraseSSResponse response) {}
    @Override public void onActivateSSRequest(org.restcomm.protocols.ss7.map.api.service.supplementary.ActivateSSRequest request) {}
    @Override public void onActivateSSResponse(org.restcomm.protocols.ss7.map.api.service.supplementary.ActivateSSResponse response) {}
    @Override public void onDeactivateSSRequest(org.restcomm.protocols.ss7.map.api.service.supplementary.DeactivateSSRequest request) {}
    @Override public void onDeactivateSSResponse(org.restcomm.protocols.ss7.map.api.service.supplementary.DeactivateSSResponse response) {}
    @Override public void onInterrogateSSRequest(org.restcomm.protocols.ss7.map.api.service.supplementary.InterrogateSSRequest request) {}
    @Override public void onInterrogateSSResponse(org.restcomm.protocols.ss7.map.api.service.supplementary.InterrogateSSResponse response) {}
    @Override public void onGetPasswordRequest(org.restcomm.protocols.ss7.map.api.service.supplementary.GetPasswordRequest request) {}
    @Override public void onGetPasswordResponse(org.restcomm.protocols.ss7.map.api.service.supplementary.GetPasswordResponse response) {}
    @Override public void onRegisterPasswordRequest(org.restcomm.protocols.ss7.map.api.service.supplementary.RegisterPasswordRequest request) {}
    @Override public void onRegisterPasswordResponse(org.restcomm.protocols.ss7.map.api.service.supplementary.RegisterPasswordResponse response) {}
}
