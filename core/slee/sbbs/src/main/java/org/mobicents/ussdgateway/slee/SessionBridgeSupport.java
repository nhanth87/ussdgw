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

package org.mobicents.ussdgateway.slee;

import org.mobicents.ussdgateway.UssdPropertiesManagement;
import org.mobicents.ussdgateway.bridge.BridgeMetrics;
import org.mobicents.ussdgateway.bridge.CorrelationIdGenerator;
import org.mobicents.ussdgateway.bridge.FsmState;
import org.mobicents.ussdgateway.bridge.LoggingNotificationFallback;
import org.mobicents.ussdgateway.bridge.NotificationFallback;
import org.mobicents.ussdgateway.bridge.PushExecutor;
import org.mobicents.ussdgateway.bridge.PushRetryQueue;
import org.mobicents.ussdgateway.bridge.PushRetryTask;
import org.mobicents.ussdgateway.bridge.SessionPriority;
import org.mobicents.ussdgateway.bridge.SessionPriorityResolver;
import org.mobicents.ussdgateway.bridge.VirtualSession;
import org.mobicents.ussdgateway.bridge.VirtualSessionStore;
import org.mobicents.ussdgateway.bridge.VirtualSessionStoreFactory;

/**
 * Thin façade used by the USSD SBBs to drive the Virtual Session Bridge without depending on
 * the bridge module internals directly. Holds the process-wide store, metrics and push retry
 * queue, and translates {@link UssdPropertiesManagement} config into bridge operations.
 * <p>
 * All operations are no-ops / inert when {@code sessionBridgeEnabled=false}, so the legacy
 * synchronous behaviour is fully preserved when the feature flag is off.
 */
public final class SessionBridgeSupport {

    private static final int PUSH_QUEUE_CAPACITY = 16384;
    private static final int LOCK_TTL_MILLIS = 5000;

    private static final SessionBridgeSupport INSTANCE = new SessionBridgeSupport();

    public static SessionBridgeSupport getInstance() {
        return INSTANCE;
    }

    private final VirtualSessionStore store = VirtualSessionStoreFactory.getStore();
    private final BridgeMetrics metrics = BridgeMetrics.getInstance();
    private final NotificationFallback fallback = new LoggingNotificationFallback();

    private volatile PushRetryQueue pushQueue;
    private volatile PushExecutor pushExecutor;

    private SessionBridgeSupport() {
    }

    private UssdPropertiesManagement props() {
        return UssdPropertiesManagement.getInstance();
    }

    public boolean isEnabled() {
        UssdPropertiesManagement p = props();
        return p != null && p.isSessionBridgeEnabled();
    }

    /**
     * Effective gate timeout. Falls back to the dialog timeout when the configured gate is
     * non-positive or not strictly smaller than the dialog timeout.
     */
    public long gateTimeoutMs() {
        return gateTimeoutMs(0);
    }

    /**
     * Effective gate timeout for a network, blending the configured ceiling with the adaptive
     * (latency-aware) suggestion. Falls back to the dialog timeout when the configured gate is
     * non-positive or not strictly smaller than the dialog timeout.
     */
    public long gateTimeoutMs(int networkId) {
        UssdPropertiesManagement p = props();
        if (p == null) {
            return 7000L;
        }
        long gate = p.getAsyncGateTimeoutMs();
        long dialog = p.getDialogTimeout();
        if (gate <= 0 || gate >= dialog) {
            return dialog;
        }
        return org.mobicents.ussdgateway.bridge.AdaptiveTimeout.getInstance().suggestGateMs(networkId, gate);
    }

    /** Feed an observed AS round-trip latency into the adaptive timeout model. */
    public void recordAsLatency(int networkId, long latencyMs) {
        org.mobicents.ussdgateway.bridge.AdaptiveTimeout.getInstance().recordLatency(networkId, latencyMs);
    }

    public String waitUserMessage() {
        UssdPropertiesManagement p = props();
        return p != null ? p.getAsyncWaitUserMessage() : null;
    }

    public String hardFailMessage() {
        UssdPropertiesManagement p = props();
        return p != null ? p.getAsyncHardFailMessage() : null;
    }

    public BridgeMetrics metrics() {
        return metrics;
    }

    public VirtualSessionStore store() {
        return store;
    }

    private long ttlMillis() {
        UssdPropertiesManagement p = props();
        int ttlSec = p != null ? p.getBridgeStateTtlSec() : 180;
        return ttlSec * 1000L;
    }

    /**
     * Idempotency mutex for an MO request. Returns {@code true} if this caller acquired the lock
     * for the MSISDN, {@code false} if another in-flight request already holds it.
     */
    public boolean tryAcquire(String msisdn, String correlationId) {
        return store.tryLock(msisdn, correlationId, LOCK_TTL_MILLIS);
    }

    public void release(String msisdn) {
        store.unlock(msisdn);
    }

    /**
     * Begin tracking an MO interaction that is awaiting the AS. Creates the correlation id and
     * persists a {@link VirtualSession} in {@code WAIT_AS}.
     *
     * @return the new correlation id, or {@code null} if the feature is disabled.
     */
    public String beginWaitAs(String msisdn, String serviceCode, Long localDialogId) {
        if (!isEnabled()) {
            return null;
        }
        String correlationId = CorrelationIdGenerator.newCorrelationId();
        String requestId = CorrelationIdGenerator.newRequestId();
        VirtualSession session = new VirtualSession(correlationId, correlationId, msisdn, ttlMillis());
        session.setServiceCode(serviceCode);
        session.setPendingRequestId(requestId);
        session.setPriority(SessionPriorityResolver.forServiceCode(serviceCode));
        session.addNetworkDialogId(localDialogId);
        session.transitionTo(FsmState.WAIT_USER);
        session.transitionTo(FsmState.WAIT_AS);
        store.save(session);
        store.markActive(msisdn, correlationId, ttlMillis());
        return correlationId;
    }

    public String requestIdFor(String correlationId) {
        VirtualSession s = store.getByCorrelationId(correlationId);
        return s != null ? s.getPendingRequestId() : null;
    }

    /**
     * Mark the MO dialogue as released early (bridged) while waiting for the AS.
     */
    public void markBridged(String correlationId) {
        VirtualSession s = store.getByCorrelationId(correlationId);
        if (s != null && s.transitionTo(FsmState.BRIDGED)) {
            store.save(s);
            metrics.incBridgesStarted();
        }
    }

    /**
     * Resolve and idempotently accept an async AS callback.
     *
     * @return the {@link VirtualSession} to push, or {@code null} if unknown, expired or a
     *         duplicate callback.
     */
    public VirtualSession onAsyncCallback(String requestId) {
        if (requestId == null) {
            return null;
        }
        VirtualSession s = store.getByRequestId(requestId);
        if (s == null) {
            metrics.incLateCallbacks();
            return null;
        }
        if (s.getFsmState() == FsmState.PUSH_PENDING || s.getFsmState().isTerminal()) {
            metrics.incDuplicateCallbacks();
            return null;
        }
        if (s.transitionTo(FsmState.PUSH_PENDING)) {
            store.save(s);
            metrics.incLateCallbacks();
            return s;
        }
        return null;
    }

    /**
     * Decide whether to deliver a push now or queue it back behind an active MO session.
     */
    public boolean shouldDeliverNow(VirtualSession push) {
        if (push == null) {
            return false;
        }
        String activeCorrelation = store.activeCorrelationId(push.getMsisdn());
        if (activeCorrelation == null || activeCorrelation.equals(push.getCorrelationId())) {
            return true;
        }
        VirtualSession active = store.getByCorrelationId(activeCorrelation);
        SessionPriority activePriority = active != null ? active.getPriority() : SessionPriority.ACTIVE_PULL;
        return SessionPriorityResolver.shouldDeliverNow(push.getPriority(), activePriority);
    }

    public void completeBridge(String correlationId) {
        if (correlationId == null) {
            return;
        }
        VirtualSession s = store.getByCorrelationId(correlationId);
        if (s != null) {
            s.transitionTo(FsmState.COMPLETED);
            metrics.incSessionsCompleted();
        }
        store.remove(correlationId);
    }

    public void clearActive(String msisdn) {
        store.clearActive(msisdn);
    }

    // -------------------------------------------------------------
    // Push retry queue
    // -------------------------------------------------------------

    /** Register the executor that performs the actual NI push retry (supplied by the SLEE layer). */
    public void setPushExecutor(PushExecutor executor) {
        this.pushExecutor = executor;
    }

    private PushRetryQueue pushQueue() {
        PushRetryQueue local = pushQueue;
        if (local == null) {
            synchronized (this) {
                local = pushQueue;
                if (local == null) {
                    UssdPropertiesManagement p = props();
                    long[] delays = p != null ? p.getPushRetryDelaysMsArray()
                            : new long[] { 3000L, 8000L, 15000L };
                    local = new PushRetryQueue(PUSH_QUEUE_CAPACITY, delays, new PushExecutor() {
                        @Override
                        public boolean deliver(PushRetryTask task) {
                            PushExecutor ex = pushExecutor;
                            return ex != null && ex.deliver(task);
                        }
                    }, fallback);
                    local.start();
                    pushQueue = local;
                }
            }
        }
        return local;
    }

    /**
     * Enqueue a failed push for back-off retry. If retries are exhausted the
     * {@link NotificationFallback} is invoked.
     */
    public void enqueuePushRetry(String correlationId, String msisdn, String payload) {
        UssdPropertiesManagement p = props();
        long[] delays = p != null ? p.getPushRetryDelaysMsArray() : new long[] { 3000L };
        long firstDelay = delays.length > 0 ? delays[0] : 3000L;
        pushQueue().enqueue(new PushRetryTask(correlationId, msisdn, payload, firstDelay));
    }

    /** Last-resort notification when a push cannot be delivered. */
    public void fallbackNotify(String msisdn, String correlationId, String payload, String reason) {
        fallback.onPushUndeliverable(msisdn, correlationId, payload, reason);
    }
}
