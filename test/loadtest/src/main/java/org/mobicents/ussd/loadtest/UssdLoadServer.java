package org.mobicents.ussd.loadtest;

import org.apache.log4j.Logger;
import org.restcomm.protocols.ss7.map.api.MAPDialog;
import org.restcomm.protocols.ss7.map.api.MAPDialogListener;
import org.restcomm.protocols.ss7.map.api.MAPException;
import org.restcomm.protocols.ss7.map.api.MAPMessage;
import org.restcomm.protocols.ss7.map.api.datacoding.CBSDataCodingScheme;
import org.restcomm.protocols.ss7.map.api.dialog.MAPAbortProviderReason;
import org.restcomm.protocols.ss7.map.api.dialog.MAPAbortSource;
import org.restcomm.protocols.ss7.map.api.dialog.MAPNoticeProblemDiagnostic;
import org.restcomm.protocols.ss7.map.api.dialog.MAPRefuseReason;
import org.restcomm.protocols.ss7.map.api.dialog.MAPUserAbortChoice;
import org.restcomm.protocols.ss7.map.api.errors.MAPErrorMessage;
import org.restcomm.protocols.ss7.map.api.errors.MAPErrorMessageFactory;
import org.restcomm.protocols.ss7.map.api.primitives.AddressString;
import org.restcomm.protocols.ss7.map.api.primitives.MAPExtensionContainer;
import org.restcomm.protocols.ss7.map.api.primitives.USSDString;
import org.restcomm.protocols.ss7.map.api.service.sms.AlertServiceCentreRequest;
import org.restcomm.protocols.ss7.map.api.service.sms.AlertServiceCentreResponse;
import org.restcomm.protocols.ss7.map.api.service.sms.ForwardShortMessageRequest;
import org.restcomm.protocols.ss7.map.api.service.sms.ForwardShortMessageResponse;
import org.restcomm.protocols.ss7.map.api.service.sms.InformServiceCentreRequest;
import org.restcomm.protocols.ss7.map.api.service.sms.MoForwardShortMessageRequest;
import org.restcomm.protocols.ss7.map.api.service.sms.MoForwardShortMessageResponse;
import org.restcomm.protocols.ss7.map.api.service.sms.MtForwardShortMessageRequest;
import org.restcomm.protocols.ss7.map.api.service.sms.MtForwardShortMessageResponse;
import org.restcomm.protocols.ss7.map.api.service.sms.NoteSubscriberPresentRequest;
import org.restcomm.protocols.ss7.map.api.service.sms.ReadyForSMRequest;
import org.restcomm.protocols.ss7.map.api.service.sms.ReadyForSMResponse;
import org.restcomm.protocols.ss7.map.api.service.sms.ReportSMDeliveryStatusRequest;
import org.restcomm.protocols.ss7.map.api.service.sms.ReportSMDeliveryStatusResponse;
import org.restcomm.protocols.ss7.map.api.service.sms.SendRoutingInfoForSMRequest;
import org.restcomm.protocols.ss7.map.api.service.sms.SendRoutingInfoForSMResponse;
import org.restcomm.protocols.ss7.map.api.service.supplementary.ActivateSSRequest;
import org.restcomm.protocols.ss7.map.api.service.supplementary.ActivateSSResponse;
import org.restcomm.protocols.ss7.map.api.service.supplementary.DeactivateSSRequest;
import org.restcomm.protocols.ss7.map.api.service.supplementary.DeactivateSSResponse;
import org.restcomm.protocols.ss7.map.api.service.supplementary.EraseSSRequest;
import org.restcomm.protocols.ss7.map.api.service.supplementary.EraseSSResponse;
import org.restcomm.protocols.ss7.map.api.service.supplementary.GetPasswordRequest;
import org.restcomm.protocols.ss7.map.api.service.supplementary.GetPasswordResponse;
import org.restcomm.protocols.ss7.map.api.service.supplementary.InterrogateSSRequest;
import org.restcomm.protocols.ss7.map.api.service.supplementary.InterrogateSSResponse;
import org.restcomm.protocols.ss7.map.api.service.supplementary.MAPDialogSupplementary;
import org.restcomm.protocols.ss7.map.api.service.supplementary.MAPServiceSupplementaryListener;
import org.restcomm.protocols.ss7.map.api.service.supplementary.ProcessUnstructuredSSRequest;
import org.restcomm.protocols.ss7.map.api.service.supplementary.ProcessUnstructuredSSResponse;
import org.restcomm.protocols.ss7.map.api.service.supplementary.RegisterPasswordRequest;
import org.restcomm.protocols.ss7.map.api.service.supplementary.RegisterPasswordResponse;
import org.restcomm.protocols.ss7.map.api.service.supplementary.RegisterSSRequest;
import org.restcomm.protocols.ss7.map.api.service.supplementary.RegisterSSResponse;
import org.restcomm.protocols.ss7.map.api.service.supplementary.UnstructuredSSNotifyRequest;
import org.restcomm.protocols.ss7.map.api.service.supplementary.UnstructuredSSNotifyResponse;
import org.restcomm.protocols.ss7.map.api.service.supplementary.UnstructuredSSRequest;
import org.restcomm.protocols.ss7.map.api.service.supplementary.UnstructuredSSResponse;
import org.restcomm.protocols.ss7.map.datacoding.CBSDataCodingSchemeImpl;
import org.restcomm.protocols.ss7.map.primitives.USSDStringImpl;
import org.restcomm.protocols.ss7.tcap.asn.ApplicationContextName;
import org.restcomm.protocols.ss7.tcap.asn.comp.Problem;

/**
 * High-performance USSD load test server optimized for 10k+ TPS.
 * 
 * Key optimizations:
 * - No heavy logging in hot path
 * - Immediate auto-response
 * - Minimal object allocation
 * - Lock-free counters via LoadTestMetrics
 */
public class UssdLoadServer implements MAPDialogListener, MAPServiceSupplementaryListener {

    private static final Logger logger = Logger.getLogger(UssdLoadServer.class);
    private static final boolean DEBUG = logger.isDebugEnabled();

    private final LoadTestMetrics metrics;
    private final MAPErrorMessageFactory errorMessageFactory;
    private final CBSDataCodingScheme defaultDcs = new CBSDataCodingSchemeImpl(15);

    // Pre-created response string to avoid allocation
    private static final String AUTO_RESPONSE = "OK";
    private final USSDString autoResponseUssd;

    public UssdLoadServer(LoadTestMetrics metrics, MAPErrorMessageFactory errorMessageFactory) throws MAPException {
        this.metrics = metrics;
        this.errorMessageFactory = errorMessageFactory;
        this.autoResponseUssd = new USSDStringImpl(AUTO_RESPONSE, defaultDcs, null);
    }

    @Override
    public void onProcessUnstructuredSSRequest(ProcessUnstructuredSSRequest request) {
        if (DEBUG) {
            logger.debug("onProcessUnstructuredSSRequest: " + request.getInvokeId());
        }

        MAPDialogSupplementary dialog = request.getMAPDialog();
        try {
            // Fast auto-response: ProcessUnstructuredSSResponse + close
            dialog.addProcessUnstructuredSSResponse(
                request.getInvokeId(), 
                defaultDcs, 
                autoResponseUssd
            );
            dialog.close(false);
            metrics.recordResponse(0); // server-side, latency not tracked here
        } catch (MAPException e) {
            metrics.recordError();
            if (DEBUG) {
                logger.debug("Error sending response", e);
            }
            try {
                dialog.close(false);
            } catch (MAPException ex) {
                // ignore
            }
        }
    }

    @Override
    public void onProcessUnstructuredSSResponse(ProcessUnstructuredSSResponse response) {
        // Client-side event, not expected on server
    }

    @Override
    public void onUnstructuredSSRequest(UnstructuredSSRequest request) {
        if (DEBUG) {
            logger.debug("onUnstructuredSSRequest: " + request.getInvokeId());
        }
        MAPDialogSupplementary dialog = request.getMAPDialog();
        try {
            USSDString resp = new USSDStringImpl("1", request.getDataCodingScheme(), null);
            dialog.addUnstructuredSSResponse(request.getInvokeId(), request.getDataCodingScheme(), resp);
            dialog.send();
            metrics.recordResponse(0);
        } catch (MAPException e) {
            metrics.recordError();
        }
    }

    @Override
    public void onUnstructuredSSResponse(UnstructuredSSResponse response) {
        // Not handling in this scenario
    }

    @Override
    public void onUnstructuredSSNotifyRequest(UnstructuredSSNotifyRequest request) {
        if (DEBUG) {
            logger.debug("onUnstructuredSSNotifyRequest: " + request.getInvokeId());
        }
        MAPDialogSupplementary dialog = request.getMAPDialog();
        try {
            dialog.addUnstructuredSSNotifyResponse(request.getInvokeId());
            dialog.send();
            metrics.recordResponse(0);
        } catch (MAPException e) {
            metrics.recordError();
        }
    }

    @Override
    public void onUnstructuredSSNotifyResponse(UnstructuredSSNotifyResponse response) {
        // Not handling
    }

    // MAPDialogListener methods - minimal implementations
    @Override
    public void onDialogRequest(MAPDialog mapDialog, AddressString destReference, AddressString origReference, MAPExtensionContainer extensionContainer) {
    }

    @Override
    public void onDialogRequestEricsson(MAPDialog mapDialog, AddressString destReference, AddressString origReference, AddressString ericssonMsisdn, AddressString ericssonVlrNo) {
    }

    @Override
    public void onDialogAccept(MAPDialog mapDialog, MAPExtensionContainer extensionContainer) {
    }

    @Override
    public void onDialogReject(MAPDialog mapDialog, MAPRefuseReason refuseReason, ApplicationContextName alternativeApplicationContext, MAPExtensionContainer extensionContainer) {
        metrics.recordError();
    }

    @Override
    public void onDialogUserAbort(MAPDialog mapDialog, MAPUserAbortChoice userAbortChoice, MAPExtensionContainer extensionContainer) {
        metrics.recordError();
    }

    @Override
    public void onDialogProviderAbort(MAPDialog mapDialog, MAPAbortProviderReason abortProviderReason, MAPAbortSource abortSource, MAPExtensionContainer extensionContainer) {
        metrics.recordError();
    }

    @Override
    public void onDialogClose(MAPDialog mapDialog) {
    }

    @Override
    public void onDialogNotice(MAPDialog mapDialog, MAPNoticeProblemDiagnostic noticeProblemDiagnostic) {
    }

    @Override
    public void onDialogRelease(MAPDialog mapDialog) {
    }

    @Override
    public void onDialogTimeout(MAPDialog mapDialog) {
        metrics.recordError();
    }

    @Override
    public void onDialogDelimiter(MAPDialog mapDialog) {
    }

    @Override
    public void onErrorComponent(MAPDialog mapDialog, Long invokeId, MAPErrorMessage mapErrorMessage) {
        metrics.recordError();
    }

    @Override
    public void onInvokeTimeout(MAPDialog mapDialog, Long invokeId) {
        metrics.recordError();
    }

    @Override
    public void onMAPMessage(MAPMessage mapMessage) {
    }

    @Override
    public void onRejectComponent(MAPDialog mapDialog, Long invokeId, Problem problem, boolean isLocalOriginated) {
        metrics.recordError();
    }

    // SS methods - not used
    @Override public void onRegisterSSRequest(RegisterSSRequest request) {}
    @Override public void onRegisterSSResponse(RegisterSSResponse response) {}
    @Override public void onEraseSSRequest(EraseSSRequest request) {}
    @Override public void onEraseSSResponse(EraseSSResponse response) {}
    @Override public void onActivateSSRequest(ActivateSSRequest request) {}
    @Override public void onActivateSSResponse(ActivateSSResponse response) {}
    @Override public void onDeactivateSSRequest(DeactivateSSRequest request) {}
    @Override public void onDeactivateSSResponse(DeactivateSSResponse response) {}
    @Override public void onInterrogateSSRequest(InterrogateSSRequest request) {}
    @Override public void onInterrogateSSResponse(InterrogateSSResponse response) {}
    @Override public void onGetPasswordRequest(GetPasswordRequest request) {}
    @Override public void onGetPasswordResponse(GetPasswordResponse response) {}
    @Override public void onRegisterPasswordRequest(RegisterPasswordRequest request) {}
    @Override public void onRegisterPasswordResponse(RegisterPasswordResponse response) {}
}
