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

package org.mobicents.ussdgateway.bridge;

import java.nio.charset.Charset;

/**
 * Channel-agnostic late-response reconciliation (RFC §5). A single, atomic decision point for every
 * AS late response regardless of whether it arrived on the synchronous transport (Channel A) or the
 * dedicated push interface (Channel B):
 *
 * <ol>
 *   <li>resolve the {@link VirtualSession} by {@code requestId};</li>
 *   <li>reject network-aborted, expired, duplicate and out-of-order (stale) responses;</li>
 *   <li>atomically transition {@code BRIDGED -> PUSH_PENDING} via
 *       {@link VirtualSessionStore#compareAndTransition} so exactly one channel wins (billing
 *       safety, RFC §13.1);</li>
 *   <li>apply the active-session priority rule and signal {@code QUEUED} when delivery must wait.</li>
 * </ol>
 *
 * The class is dependency-light (no SLEE / jSS7); the SLEE layer performs the actual NI push through
 * a {@link NiPushDispatcher}.
 */
public final class BridgeReconciler {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Sentinel meaning "no ordering check" for callers that do not track an input generation. */
    public static final int NO_GENERATION = -1;

    private final VirtualSessionStore store;
    private final BridgeMetrics metrics;

    public BridgeReconciler(VirtualSessionStore store, BridgeMetrics metrics) {
        this.store = store;
        this.metrics = metrics;
    }

    /**
     * Reconcile a late AS response.
     *
     * @param requestId       echoed {@code X-Ussd-Request-Id} (required).
     * @param payload         serialized {@code XmlMAPDialog} menu bytes (may be cached for retry).
     * @param channel         which entry point received it.
     * @param inputGeneration generation stamped on the request, or {@link #NO_GENERATION}.
     * @return the {@link ReconcileResult} describing how the caller must proceed.
     */
    public ReconcileResult reconcileLateResponse(String requestId, byte[] payload,
            ReconcileChannel channel, int inputGeneration) {
        if (requestId == null) {
            return ReconcileResult.of(ReconcileResult.Outcome.UNKNOWN);
        }

        VirtualSession session = store.getByRequestId(requestId);
        if (session == null) {
            // No live session: either TTL elapsed (P12) or an unknown/forged request id.
            metrics.incLateExpired();
            return ReconcileResult.of(ReconcileResult.Outcome.EXPIRED);
        }

        final String correlationId = session.getCorrelationId();
        FsmState state = session.getFsmState();

        if (state == FsmState.ABORTED) {
            metrics.incLateAborted();
            return ReconcileResult.of(ReconcileResult.Outcome.ABORTED, session);
        }
        if (state == FsmState.PUSH_PENDING || state.isTerminal()) {
            // Already delivered (or being delivered) / finished: idempotent drop.
            metrics.incLateDuplicate();
            return ReconcileResult.of(ReconcileResult.Outcome.DUPLICATE, session);
        }

        // Out-of-order: a newer user input has superseded this response (RFC §13.3).
        if (inputGeneration != NO_GENERATION && inputGeneration < session.getInputGeneration()) {
            metrics.incLateStale();
            return ReconcileResult.of(ReconcileResult.Outcome.STALE, session);
        }

        // Atomic, exactly-once transition. Only the winner proceeds to push.
        VirtualSession updated = store.compareAndTransition(correlationId, FsmState.BRIDGED,
                FsmState.PUSH_PENDING);
        if (updated == null) {
            return classifyCasLoser(correlationId);
        }

        // Cache the menu so a queued/failed push can be retried with the real payload.
        if (payload != null && payload.length > 0) {
            updated.setLastMenu(new String(payload, UTF8));
            store.save(updated);
        }

        // Active-session priority: defer behind a higher-priority in-flight MO session (RFC S5).
        if (!shouldDeliverNow(updated)) {
            metrics.incPushRetries();
            return ReconcileResult.of(ReconcileResult.Outcome.QUEUED, updated);
        }

        metrics.incLateReconciled(channel);
        metrics.incBridgeRecovered();
        return ReconcileResult.of(ReconcileResult.Outcome.DELIVERED, updated);
    }

    private ReconcileResult classifyCasLoser(String correlationId) {
        VirtualSession cur = store.getByCorrelationId(correlationId);
        if (cur == null) {
            metrics.incLateExpired();
            return ReconcileResult.of(ReconcileResult.Outcome.EXPIRED);
        }
        if (cur.getFsmState() == FsmState.ABORTED) {
            metrics.incLateAborted();
            return ReconcileResult.of(ReconcileResult.Outcome.ABORTED, cur);
        }
        metrics.incLateDuplicate();
        return ReconcileResult.of(ReconcileResult.Outcome.DUPLICATE, cur);
    }

    /**
     * Decide whether to deliver a push now or queue it back behind a higher-priority active MO
     * session for the same MSISDN.
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

    /**
     * Mark a session aborted by the network (MAP/TCAP/Provider/User abort) <em>before</em> the
     * gateway bridged it. Idempotent. A subsequent late response will be dropped (RFC §13.2).
     * <p>
     * Deliberately only transitions the pre-bridge states {@code WAIT_AS} / {@code WAIT_USER}: once
     * the gateway has itself released S1 ({@code BRIDGED}), the teardown events that follow our own
     * release must not undo the committed NI push. An explicit administrative abort of a
     * {@code BRIDGED} session can use {@link VirtualSessionStore#compareAndTransition} directly.
     *
     * @return {@code true} if the session transitioned to {@code ABORTED}.
     */
    public boolean markAborted(String correlationId) {
        if (correlationId == null) {
            return false;
        }
        VirtualSession waitAs = store.compareAndTransition(correlationId, FsmState.WAIT_AS, FsmState.ABORTED);
        if (waitAs != null) {
            metrics.incSessionsAborted();
            return true;
        }
        VirtualSession waitUser = store.compareAndTransition(correlationId, FsmState.WAIT_USER, FsmState.ABORTED);
        if (waitUser != null) {
            metrics.incSessionsAborted();
            return true;
        }
        return false;
    }
}
