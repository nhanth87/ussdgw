/*
 * TeleStax, Open Source Cloud Communications
 *
 * gRPC push ingress SBB — gateway acts as gRPC server (mirrors HttpServerSbb).
 * CDR via CdrLocal RA ({@link org.mobicents.ussdgateway.slee.cdr.USSDType#PUSH}).
 */

package org.mobicents.ussdgateway.slee.grpc;

import javax.slee.ActivityContextInterface;
import javax.slee.SbbContext;

import org.joda.time.DateTime;
import org.mobicents.ussd.grpc.GrpcEnvelopeCodec;
import org.mobicents.ussd.grpc.GrpcPushActivity;
import org.mobicents.ussd.grpc.events.GrpcPushReceivedEvent;
import org.mobicents.ussdgateway.FastList;
import org.mobicents.ussdgateway.XmlMAPDialog;
import org.mobicents.ussdgateway.slee.SessionBridgeSupport;
import org.mobicents.ussdgateway.slee.cdr.ChargeInterface;
import org.mobicents.ussdgateway.slee.cdr.USSDCDRState;
import org.mobicents.ussdgateway.slee.cdr.USSDType;
import org.mobicents.ussdgateway.slee.http.HttpServerSbb;
import org.restcomm.protocols.ss7.map.api.MAPMessage;
import org.restcomm.protocols.ss7.map.api.MAPMessageType;
import org.restcomm.protocols.ss7.map.api.primitives.ISDNAddressString;
import org.restcomm.protocols.ss7.map.api.service.supplementary.ProcessUnstructuredSSRequest;
import org.restcomm.protocols.ss7.map.api.service.supplementary.UnstructuredSSNotifyRequest;
import org.restcomm.protocols.ss7.map.api.service.supplementary.UnstructuredSSRequest;

/**
 * Handles NI push received on the gateway gRPC push server.
 */
public abstract class GrpcServerSbb extends HttpServerSbb {

    private GrpcPushActivity grpcPushActivity;
    private String grpcCorrelationId;

    public GrpcServerSbb() {
        super();
    }

    public void onGrpcPushReceived(GrpcPushReceivedEvent event, ActivityContextInterface aci) {
        if (super.logger.isFineEnabled()) {
            super.logger.fine("Received gRPC push");
        }

        this.cancelTimer();
        this.grpcPushActivity = event.getActivity();
        this.grpcCorrelationId = event.getCorrelationId();
        aci.attach(super.sbbContext.getSbbLocalObject());

        boolean success = false;
        try {
            final XmlMAPDialog xmlMAPDialog = deserializePushPayload(event.getPayload());
            this.setXmlMAPDialog(xmlMAPDialog);

            final FastList<MAPMessage> mapMessages = xmlMAPDialog.getMAPMessages();
            ISDNAddressString msisdn = null;
            String serviceCode = null;

            if (mapMessages != null) {
                for (int i = 0; i < mapMessages.size(); i++) {
                    final MAPMessage rawMessage = mapMessages.get(i);
                    if (rawMessage == null) {
                        continue;
                    }
                    final MAPMessageType type = rawMessage.getMessageType();
                    switch (type) {
                    case unstructuredSSRequest_Request:
                        UnstructuredSSRequest ussRequest = (UnstructuredSSRequest) rawMessage;
                        msisdn = ussRequest.getMSISDNAddressString();
                        serviceCode = ussRequest.getUSSDString().getString(null);
                        super.ussdStatAggregator.updateUssdRequestOperations();
                        super.ussdStatAggregator.updateMessagesSent();
                        super.ussdStatAggregator.updateMessagesAll();
                        break;
                    case unstructuredSSNotify_Request:
                        UnstructuredSSNotifyRequest ntfyReq = (UnstructuredSSNotifyRequest) rawMessage;
                        msisdn = ntfyReq.getMSISDNAddressString();
                        serviceCode = ntfyReq.getUSSDString().getString(null);
                        super.ussdStatAggregator.updateUssdNotifyOperations();
                        super.ussdStatAggregator.updateMessagesSent();
                        super.ussdStatAggregator.updateMessagesAll();
                        break;
                    case processUnstructuredSSRequest_Request:
                        ProcessUnstructuredSSRequest processUnstrSSReq = (ProcessUnstructuredSSRequest) rawMessage;
                        msisdn = processUnstrSSReq.getMSISDNAddressString();
                        serviceCode = processUnstrSSReq.getUSSDString().getString(null);
                        super.ussdStatAggregator.updateProcessUssdRequestOperations();
                        break;
                    default:
                        break;
                    }
                }
            }

            if (msisdn == null) {
                throw new Exception("MSISDN in a received gRPC PUSH request is null");
            }
            this.setMsisdnCMP(msisdn);

            SessionBridgeSupport bridge = SessionBridgeSupport.getInstance();
            String bridgeCorrelationId = null;
            if (bridge.isEnabled()) {
                String requestId = event.getRequestId();
                if (requestId != null) {
                    org.mobicents.ussdgateway.bridge.ReconcileResult rr = bridge.reconcileLateResponse(
                            requestId, null, org.mobicents.ussdgateway.bridge.ReconcileChannel.PUSH_GRPC,
                            event.getInputGeneration());
                    if (rr.shouldPush()) {
                        bridgeCorrelationId = rr.getSession().getCorrelationId();
                    } else {
                        ackPushCallback();
                        success = true;
                        return;
                    }
                }
            }

            ChargeInterface cdrInterface = this.getCDRChargeInterface();
            USSDCDRState state = cdrInterface.getState();
            if (!state.isInitialized()) {
                state.init(null, serviceCode, null, null, msisdn, null, null);
                state.setDialogStartTime(DateTime.now());
                state.setUssdType(USSDType.PUSH);
                if (bridgeCorrelationId != null) {
                    state.setCorrelationId(bridgeCorrelationId);
                    state.setBridgePhase(org.mobicents.ussdgateway.bridge.BridgePhase.S2_PUSH.name());
                } else if (event.getCorrelationId() != null) {
                    state.setCorrelationId(event.getCorrelationId());
                }
                cdrInterface.setState(state);
                attachCdrToActivity(aci, cdrInterface);
            }

            getSRI().performSRIQuery(msisdn.getAddress(), xmlMAPDialog);
            super.ussdStatAggregator.addDialogsInProcess();
            success = true;

        } catch (Exception e) {
            super.logger.severe("Error while processing received gRPC push request", e);
            if (grpcPushActivity != null) {
                grpcPushActivity.ackError(e.getMessage());
                grpcPushActivity.endActivity();
            }
        } finally {
            if (!success && grpcPushActivity != null) {
                super.ussdStatAggregator.updateDialogsAllFailed();
                super.ussdStatAggregator.updateDialogsPushFailed();
            }
        }
    }

    @Override
    protected void ackPushCallback() {
        if (grpcPushActivity != null) {
            grpcPushActivity.ackEmptyOk();
            grpcPushActivity.endActivity();
        } else {
            super.ackPushCallback();
        }
    }

    @Override
    protected void signalPushIngressOk() {
        if (grpcPushActivity != null) {
            grpcPushActivity.ackEmptyOk();
        } else {
            super.signalPushIngressOk();
        }
    }

    @Override
    protected void deliverPushResponse(byte[] data) {
        if (grpcPushActivity != null) {
            byte[] envelope = GrpcEnvelopeCodec.encodeResponse(true, grpcCorrelationId, data, null);
            grpcPushActivity.ackSuccess(envelope);
            grpcPushActivity.endActivity();
            grpcPushActivity = null;
            return;
        }
        super.deliverPushResponse(data);
    }

    @Override
    protected void endPushIngress() {
        if (grpcPushActivity != null) {
            grpcPushActivity.endActivity();
            grpcPushActivity = null;
            return;
        }
        super.endPushIngress();
    }

    @Override
    public void setSbbContext(SbbContext sbbContext) {
        super.setSbbContext(sbbContext);
        super.logger = sbbContext.getTracer("gRPC-Server-" + getClass().getName());
    }

    @Override
    protected void updateDialogFailureStat() {
        super.ussdStatAggregator.updateDialogsAllFailed();
        super.ussdStatAggregator.updateDialogsPushFailed();
    }
}
