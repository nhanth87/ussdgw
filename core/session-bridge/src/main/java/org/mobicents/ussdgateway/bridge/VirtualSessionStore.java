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
 * Storage abstraction for {@link VirtualSession} plus the secondary indexes used by the bridge:
 * request-id lookup, per-MSISDN idempotency lock and active-session marker.
 * <p>
 * Implementations must be safe for concurrent access from SLEE event threads and the push
 * retry worker.
 */
public interface VirtualSessionStore {

    /** Persist or update a session, keyed by its correlation id, with the session TTL. */
    void save(VirtualSession session);

    VirtualSession getByCorrelationId(String correlationId);

    /** Resolve a session from a pending AS request id (secondary index). */
    VirtualSession getByRequestId(String requestId);

    void remove(String correlationId);

    /**
     * Atomically transition the session identified by {@code correlationId} from {@code expected}
     * to {@code next} and persist it. This is the single idempotency point that makes
     * late-response delivery exactly-once across the sync and push channels (RFC §6.2): only one
     * concurrent caller can win the {@code BRIDGED -> PUSH_PENDING} step.
     *
     * @return the updated session if the compare-and-swap succeeded; {@code null} if the session is
     *         absent, expired, or its current state was not {@code expected} (lost the race).
     */
    VirtualSession compareAndTransition(String correlationId, FsmState expected, FsmState next);

    /**
     * Idempotency mutex per MSISDN. Returns {@code true} if the lock was acquired for the given
     * correlation id, {@code false} if another in-flight request already holds it.
     */
    boolean tryLock(String msisdn, String correlationId, long ttlMillis);

    void unlock(String msisdn);

    /** Mark an MSISDN as having an active MO session (used for push priority decisions). */
    void markActive(String msisdn, String correlationId, long ttlMillis);

    void clearActive(String msisdn);

    /** @return the correlation id of the active MO session for the MSISDN, or {@code null}. */
    String activeCorrelationId(String msisdn);
}
