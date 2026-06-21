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

/**
 * Outcome of {@link BridgeReconciler#reconcileLateResponse}. Tells the calling SBB how to respond
 * to the AS (ack with no further action vs. proceed to NI push) and is the single place the
 * idempotency / abort / staleness decisions are surfaced.
 */
public final class ReconcileResult {

    public enum Outcome {
        /** This call won the CAS; the caller must dispatch the NI push (S2). */
        DELIVERED,
        /** Delivery deferred behind an active higher-priority MO session; queued for retry. */
        QUEUED,
        /** Another channel already delivered this requestId; ack only, no push. */
        DUPLICATE,
        /** No session for this requestId (never bridged, or already removed); ack and drop. */
        UNKNOWN,
        /** Session TTL elapsed before this response; ack and drop (optionally SMS fallback). */
        EXPIRED,
        /** Network tore down the dialogue (MAP/TCAP abort); ack and drop, never reopen. */
        ABORTED,
        /** A newer user input superseded this response (out-of-order); ack and drop. */
        STALE,
        /** Bridge feature disabled; caller uses legacy behaviour. */
        DISABLED
    }

    private final Outcome outcome;
    private final VirtualSession session;

    private ReconcileResult(Outcome outcome, VirtualSession session) {
        this.outcome = outcome;
        this.session = session;
    }

    public static ReconcileResult of(Outcome outcome) {
        return new ReconcileResult(outcome, null);
    }

    public static ReconcileResult of(Outcome outcome, VirtualSession session) {
        return new ReconcileResult(outcome, session);
    }

    public Outcome getOutcome() {
        return outcome;
    }

    /** The session to push (only meaningful for {@link Outcome#DELIVERED}). */
    public VirtualSession getSession() {
        return session;
    }

    /** @return {@code true} if the caller must dispatch an NI push now. */
    public boolean shouldPush() {
        return outcome == Outcome.DELIVERED;
    }

    /**
     * @return {@code true} if the late response was recognised and consumed by the bridge, so the
     *         caller must <em>not</em> fall back to legacy / cold-push handling. Only
     *         {@link Outcome#DISABLED} lets the caller proceed with legacy behaviour.
     */
    public boolean isHandled() {
        return outcome != Outcome.DISABLED;
    }

    @Override
    public String toString() {
        return "ReconcileResult[" + outcome
                + (session != null ? ", correlationId=" + session.getCorrelationId() : "") + "]";
    }
}
