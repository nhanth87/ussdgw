/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2017, Telestax Inc and individual contributors
 * by the @authors tag.
 *
 * This program is free software: you can redistribute it and/or modify
 * under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package org.mobicents.ussdgateway.slee.grpc;

import javax.slee.ActivityContextInterface;
import javax.slee.SbbContext;

import org.mobicents.ussdgateway.FastList;
import org.restcomm.protocols.ss7.map.api.MAPMessage;
import org.restcomm.protocols.ss7.map.api.dialog.MAPUserAbortChoice;
import org.restcomm.protocols.ss7.map.api.service.supplementary.MAPDialogSupplementary;

import org.mobicents.ussd.grpc.GrpcAsResourceAdaptorSbbInterface;
import org.mobicents.ussd.grpc.GrpcRequest;
import org.mobicents.ussd.grpc.GrpcResponse;
import org.mobicents.ussd.grpc.GrpcResponseRegistry;
import org.mobicents.ussdgateway.EventsSerializeFactory;
import org.mobicents.ussdgateway.XmlMAPDialog;
import org.mobicents.ussdgateway.bridge.CorrelationIdGenerator;
import org.mobicents.ussdgateway.rules.ScRoutingRule;
import org.mobicents.ussdgateway.slee.ChildSbb;
import org.mobicents.ussdgateway.slee.SessionBridgeSupport;
import org.mobicents.ussdgateway.slee.cdr.RecordStatus;
import org.mobicents.ussdgateway.slee.cdr.USSDCDRState;

/**
 * gRPC counterpart of {@code HttpClientSbb}: the gateway pushes the serialized USSD dialogue to the
 * Application Server over gRPC and collects the reply. Because the gRPC RA is non-blocking and
 * publishes the reply to {@link GrpcResponseRegistry}, this SBB polls the registry on a short SLEE
 * timer (a portable, container-managed pattern) and processes the reply in a valid event context.
 *
 * @author Jenny
 */
public abstract class GrpcClientSbb extends ChildSbb {

    /**
     * First poll delay (ms). AS tester uses max-delay=50ms; arm just above that so the
     * first fire usually hits (miss≈0 → no rearm JTA). Flat 250ms made CDR min≈760ms
     * (3 hops). FIRST=10 cut min to ~48ms but raised misses and did not lift the ~100/s
     * high-inflight ceiling.
     */
    private static final long FIRST_POLL_MS = 60;
    /** Rearm delay after a miss (ms). */
    private static final long POLL_INTERVAL_MS = 50;

    /** Wall-clock submit time per correlationId — feeds AdaptiveTimeout (pollCount*interval is 0 on first-hit). */
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> GRPC_SUBMIT_AT_MS =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();

    protected GrpcAsResourceAdaptorSbbInterface grpcProvider;

    public GrpcClientSbb() {
        super("GrpcClientSbb");
    }

    // -------------------------------------------------------------
    // Sending: serialize and submit to the AS over gRPC
    // -------------------------------------------------------------

    @Override
    protected void sendUssdData(XmlMAPDialog xmlMAPDialog) throws Exception {
        String userData = this.getUserObject();
        if (userData != null) {
            xmlMAPDialog.setUserObject(userData);
        }

        byte[] payload = this.getEventsSerializeFactory().serialize(xmlMAPDialog);

        USSDCDRState state = getOrCreateLocalRaCdrState();
        // Unify the AS-facing session id with the bridge correlation id when present; otherwise
        // mint a fresh id. One id is enough across MO/NI (see design doc, section "sessionId vs
        // correlationId").
        String correlationId = state.getCorrelationId();
        if (correlationId == null) {
            correlationId = CorrelationIdGenerator.newCorrelationId();
            state.setCorrelationId(correlationId);
        }
        this.setGrpcCorrelationId(correlationId);
        this.setGrpcPollCount(0);
        GRPC_SUBMIT_AT_MS.put(correlationId, Long.valueOf(System.currentTimeMillis()));

        ScRoutingRule call = this.getCall();
        String target = call.getRuleUrl();
        int networkId = xmlMAPDialog.getNetworkId();

        // Propagate the bridge request id so the AS can echo it on a late response (Channel A).
        SessionBridgeSupport bridge = SessionBridgeSupport.getInstance();
        String requestId = bridge.requestIdFor(correlationId);
        // #region agent log
        if (AGENT_POLL_HIT.get() == 0L && AGENT_POLL_MISS.get() == 0L) {
            if (this.logger != null) {
                this.logger.warning("AGENT_DBG_NDJSON {\"sessionId\":\"6dfc1e\",\"runId\":\"adaptive-gate\",\"hypothesisId\":\"H15\","
                        + "\"location\":\"GrpcClientSbb.sendUssdData\",\"message\":\"bridge+gate snapshot\","
                        + "\"data\":{\"bridgeEnabled\":" + bridge.isEnabled()
                        + ",\"grpcBridge\":" + bridge.isGrpcClientEnabled()
                        + ",\"gateMs\":" + bridge.gateTimeoutMs(networkId)
                        + ",\"dialogTimeoutMs\":" + this.getUssdPropertiesManagement().getDialogTimeout()
                        + ",\"asyncGateCeilingMs\":" + this.getUssdPropertiesManagement().getAsyncGateTimeoutMs()
                        + ",\"firstPollMs\":" + FIRST_POLL_MS + "},"
                        + "\"timestamp\":" + System.currentTimeMillis() + "}");
            }
        }
        // #endregion
        GrpcRequest request = new GrpcRequest(target, correlationId, correlationId, requestId, false,
                networkId, payload);
        if (grpcProvider != null) {
            grpcProvider.submit(request);
        } else {
            logger.severe("gRPC AS RA provider not available");
            throw new IllegalStateException("gRPC provider unavailable");
        }
    }

    // -------------------------------------------------------------
    // Polling: collect the gRPC reply on the SLEE timer
    // -------------------------------------------------------------

    @Override
    protected long getTimerDelayMs() {
        // First arm after submit: short. After a miss, pollCount>=1 → POLL_INTERVAL_MS.
        return getGrpcPollCount() <= 0 ? FIRST_POLL_MS : POLL_INTERVAL_MS;
    }

    // #region agent log
    private static final java.util.concurrent.atomic.AtomicLong AGENT_POLL_HIT = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong AGENT_POLL_MISS = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong AGENT_POLL_GIVEUP = new java.util.concurrent.atomic.AtomicLong();
    // #endregion

    @Override
    protected boolean onProtocolTimer(ActivityContextInterface aci) {
        String correlationId = this.getGrpcCorrelationId();
        if (correlationId == null) {
            return false;
        }

        GrpcResponse response = GrpcResponseRegistry.getInstance().poll(correlationId);
        if (response != null) {
            // #region agent log
            long hits = AGENT_POLL_HIT.incrementAndGet();
            // #endregion
            // Feed wall-clock AS RTT into AdaptiveTimeout (pollCount*interval was 0 on first-hit
            // so EWMA never seeded and gate stayed stuck at asyncgatetimeoutms ceiling).
            try {
                XmlMAPDialog dialog = this.getXmlMAPDialog();
                int networkId = dialog != null ? dialog.getNetworkId() : 0;
                Long t0 = GRPC_SUBMIT_AT_MS.remove(correlationId);
                long latency = (t0 != null) ? (System.currentTimeMillis() - t0.longValue())
                        : Math.max(FIRST_POLL_MS, (long) this.getGrpcPollCount() * POLL_INTERVAL_MS);
                SessionBridgeSupport bridge = SessionBridgeSupport.getInstance();
                bridge.recordAsLatency(networkId, latency);
                // #region agent log
                if (hits == 1L || hits % 100L == 0L) {
                    if (this.logger != null) {
                        this.logger.warning("AGENT_DBG_NDJSON {\"sessionId\":\"6dfc1e\",\"runId\":\"adaptive-gate\",\"hypothesisId\":\"H15\","
                                + "\"location\":\"GrpcClientSbb.onProtocolTimer\",\"message\":\"poll hit + adaptive gate\","
                                + "\"data\":{\"hits\":" + hits + ",\"misses\":" + AGENT_POLL_MISS.get()
                                + ",\"giveups\":" + AGENT_POLL_GIVEUP.get()
                                + ",\"pollCount\":" + this.getGrpcPollCount()
                                + ",\"latencyMs\":" + latency
                                + ",\"gateMs\":" + bridge.gateTimeoutMs(networkId)
                                + ",\"ewmaMs\":" + org.mobicents.ussdgateway.bridge.AdaptiveTimeout.getInstance().observedLatencyMs(networkId)
                                + ",\"bridgeEnabled\":" + bridge.isEnabled()
                                + ",\"firstPollMs\":" + FIRST_POLL_MS + "},"
                                + "\"timestamp\":" + System.currentTimeMillis() + "}");
                    }
                }
                // #endregion
            } catch (Exception ignore) {
                // metrics best-effort
            }
            processGrpcResponse(response);
            return true;
        }

        int attempts = this.getGrpcPollCount();
        if (attempts >= maxPollAttempts()) {
            // #region agent log
            AGENT_POLL_GIVEUP.incrementAndGet();
            GRPC_SUBMIT_AT_MS.remove(correlationId);
            // #endregion
            // give up polling; fall through to standard timeout / bridge handling
            return false;
        }
        // #region agent log
        long misses = AGENT_POLL_MISS.incrementAndGet();
        if (misses == 1L || misses % 500L == 0L) {
            if (this.logger != null) {
                this.logger.warning("AGENT_DBG_NDJSON {\"sessionId\":\"6dfc1e\",\"runId\":\"post-fix\",\"hypothesisId\":\"H2\","
                        + "\"location\":\"GrpcClientSbb.onProtocolTimer\",\"message\":\"poll miss rearm sample\","
                        + "\"data\":{\"misses\":" + misses + ",\"hits\":" + AGENT_POLL_HIT.get()
                        + ",\"giveups\":" + AGENT_POLL_GIVEUP.get()
                        + ",\"attempts\":" + attempts + ",\"maxAttempts\":" + maxPollAttempts() + "},"
                        + "\"timestamp\":" + System.currentTimeMillis() + "}");
            }
        }
        // #endregion
        this.setGrpcPollCount(attempts + 1);
        this.rearmTimer(aci);
        return true;
    }

    private int maxPollAttempts() {
        SessionBridgeSupport bridge = SessionBridgeSupport.getInstance();
        int networkId = 0;
        try {
            XmlMAPDialog dialog = this.getXmlMAPDialog();
            if (dialog != null) {
                networkId = dialog.getNetworkId();
            }
        } catch (Exception ignore) {
            // default 0
        }
        // Adaptive gate (EWMA) when gRPC bridge on — keep poll budget << dialog/TCAP timeouts.
        long budget = bridge.isGrpcClientEnabled() ? bridge.gateTimeoutMs(networkId)
                : this.getUssdPropertiesManagement().getDialogTimeout();
        long attempts = budget / POLL_INTERVAL_MS;
        if (attempts < 1) {
            attempts = 1;
        }
        return (int) Math.min(attempts, Integer.MAX_VALUE);
    }

    private void processGrpcResponse(GrpcResponse response) {
        MAPDialogSupplementary mapDialogSupplementary = this.getMAPDialog();
        if (mapDialogSupplementary == null) {
            // Virtual Session Bridge Channel A: MO dialogue gone (gate fired). Try to reconcile this
            // late sync gRPC response into an NI push instead of dropping it.
            if (response != null && response.isSuccess() && tryBridgeSyncReconcile(response)) {
                return;
            }
            logger.warning("gRPC response received but MAP dialog is gone; dropping. correlationId="
                    + response.getCorrelationId());
            return;
        }

        if (!response.isSuccess()) {
            logger.severe("gRPC AS returned error: " + response.getErrorMessage());
            this.sendServerErrorMessage();
            this.terminateProtocolConnection();
            this.updateDialogFailureStat();
            this.createCDRRecord(RecordStatus.FAILED_TRANSPORT_FAILURE);
            return;
        }

        try {
            byte[] xmlContent = response.getPayload();
            if (xmlContent == null || xmlContent.length == 0) {
                throw new Exception("Received empty gRPC payload from AS");
            }

            EventsSerializeFactory factory = this.getEventsSerializeFactory();
            XmlMAPDialog dialog = factory.deserialize(xmlContent);
            if (dialog == null) {
                throw new Exception("Could not deserialize gRPC payload to dialog");
            }

            Object userObject = dialog.getUserObject();
            if (userObject != null) {
                this.setUserObject(userObject.toString());
            }

            MAPUserAbortChoice mapUserAbortChoice = dialog.getMAPUserAbortChoice();
            if (mapUserAbortChoice != null) {
                mapDialogSupplementary.abort(mapUserAbortChoice);
                this.updateDialogFailureStat();
                this.createCDRRecord(RecordStatus.ABORT_APP);
                return;
            }

            Boolean prearrangedEnd = dialog.getPrearrangedEnd();
            FastList<MAPMessage> mapMessages = dialog.getMAPMessages();

            if (mapMessages != null && mapMessages.size() > 0) {
                this.processXmlMAPDialog(dialog, mapDialogSupplementary);
                if (prearrangedEnd != null) {
                    mapDialogSupplementary.close(prearrangedEnd);
                    this.createCDRRecord(RecordStatus.SUCCESS);
                } else {
                    mapDialogSupplementary.send();
                }
            } else if (dialog.getMAPUserAbortChoice() != null || dialog.getDialogTimedOut() != null) {
                this.updateDialogFailureStat();
                this.createCDRRecord(RecordStatus.FAILED_TRANSPORT_FAILURE);
            } else {
                throw new Exception("gRPC payload had no MAP messages");
            }
        } catch (Throwable e) {
            logger.severe("Error while processing gRPC response", e);
            this.sendServerErrorMessage();
            this.terminateProtocolConnection();
            this.updateDialogFailureStat();
            this.createCDRRecord(RecordStatus.FAILED_CORRUPTED_MESSAGE);
        }
    }

    /**
     * Virtual Session Bridge Channel A for gRPC: reconcile a late sync response (MO dialogue gone)
     * into an NI push. Returns {@code true} when the bridge consumed it.
     */
    private boolean tryBridgeSyncReconcile(GrpcResponse response) {
        try {
            SessionBridgeSupport bridge = SessionBridgeSupport.getInstance();
            if (!bridge.isSyncReconcileEnabled()) {
                return false;
            }
            String correlationId = this.getGrpcCorrelationId();
            if (correlationId == null) {
                USSDCDRState state = getOrCreateLocalRaCdrState();
                correlationId = state != null ? state.getCorrelationId() : null;
            }
            if (correlationId == null) {
                return false;
            }
            String requestId = response.getRequestId();
            if (requestId == null) {
                requestId = bridge.requestIdFor(correlationId);
            }
            if (requestId == null) {
                return false;
            }
            int gen = bridge.currentInputGeneration(correlationId);
            org.mobicents.ussdgateway.bridge.ReconcileResult rr = bridge.reconcileLateResponse(
                    requestId, response.getPayload(),
                    org.mobicents.ussdgateway.bridge.ReconcileChannel.SYNC_GRPC, gen);
            if (logger.isFineEnabled()) {
                logger.fine("Bridge sync reconcile (gRPC) requestId=" + requestId + " -> " + rr);
            }
            return rr.isHandled();
        } catch (Throwable t) {
            logger.severe("Bridge sync reconcile (gRPC) error", t);
            return false;
        }
    }

    // -------------------------------------------------------------
    // ChildSbb abstract methods
    // -------------------------------------------------------------

    @Override
    protected boolean isSip() {
        return false;
    }

    @Override
    protected boolean checkProtocolConnection() {
        return true;
    }

    @Override
    protected void terminateProtocolConnection() {
        // gRPC is request/response and stateless at the SBB; nothing to tear down here.
    }

    @Override
    protected void updateDialogFailureStat() {
        super.ussdStatAggregator.updateDialogsAllFailed();
        super.ussdStatAggregator.updateDialogsPullFailed();
        super.ussdStatAggregator.updateDialogsHttpFailed();
    }

    // -------------------------------------------------------------
    // CMP for gRPC correlation/poll state
    // -------------------------------------------------------------

    public abstract void setGrpcCorrelationId(String correlationId);

    public abstract String getGrpcCorrelationId();

    public abstract void setGrpcPollCount(int count);

    public abstract int getGrpcPollCount();

    // -------------------------------------------------------------
    // SLEE
    // -------------------------------------------------------------

    @Override
    public void setSbbContext(SbbContext sbbContext) {
        super.setSbbContext(sbbContext);
        try {
            this.grpcProvider = (GrpcAsResourceAdaptorSbbInterface) super.sbbContext
                    .getResourceAdaptorInterface(GrpcAsResourceAdaptorSbbInterface.RATYPE_ID, "GrpcAsRA");
        } catch (Exception ne) {
            super.logger.severe("Could not obtain gRPC AS RA provider:", ne);
        }
    }
}
