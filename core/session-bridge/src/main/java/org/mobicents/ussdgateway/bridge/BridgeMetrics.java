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

import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide counters for the Virtual Session Bridge. Exposed to the management/metrics layer
 * to compute success-rate KPIs.
 */
public final class BridgeMetrics {

    private static final BridgeMetrics INSTANCE = new BridgeMetrics();

    public static BridgeMetrics getInstance() {
        return INSTANCE;
    }

    private final AtomicLong bridgesStarted = new AtomicLong();
    private final AtomicLong bridgeRecovered = new AtomicLong();
    private final AtomicLong lateCallbacks = new AtomicLong();
    private final AtomicLong duplicateCallbacks = new AtomicLong();
    private final AtomicLong pushRetries = new AtomicLong();
    private final AtomicLong pushRejected = new AtomicLong();
    private final AtomicLong fallbackNotifications = new AtomicLong();
    private final AtomicLong doubleSubmitBlocked = new AtomicLong();
    private final AtomicLong sessionsCompleted = new AtomicLong();
    private final AtomicLong sessionsExpired = new AtomicLong();

    // Unified late-response reconciliation (RFC §9) — per channel + outcomes.
    private final AtomicLong lateSyncHttp = new AtomicLong();
    private final AtomicLong lateSyncGrpc = new AtomicLong();
    private final AtomicLong lateSyncSip = new AtomicLong();
    private final AtomicLong latePushHttp = new AtomicLong();
    private final AtomicLong latePushSip = new AtomicLong();
    private final AtomicLong lateDuplicate = new AtomicLong();
    private final AtomicLong lateAborted = new AtomicLong();
    private final AtomicLong lateExpired = new AtomicLong();
    private final AtomicLong lateStale = new AtomicLong();
    private final AtomicLong sessionsAborted = new AtomicLong();

    private BridgeMetrics() {
    }

    /** Count a successful reconcile on the given channel. */
    public void incLateReconciled(ReconcileChannel channel) {
        if (channel == null) {
            return;
        }
        switch (channel) {
            case SYNC_HTTP: lateSyncHttp.incrementAndGet(); break;
            case SYNC_GRPC: lateSyncGrpc.incrementAndGet(); break;
            case SYNC_SIP:  lateSyncSip.incrementAndGet(); break;
            case PUSH_HTTP: latePushHttp.incrementAndGet(); break;
            case PUSH_SIP:  latePushSip.incrementAndGet(); break;
            default: break;
        }
    }

    public void incLateDuplicate() {
        lateDuplicate.incrementAndGet();
    }

    public void incLateAborted() {
        lateAborted.incrementAndGet();
    }

    public void incLateExpired() {
        lateExpired.incrementAndGet();
    }

    public void incLateStale() {
        lateStale.incrementAndGet();
    }

    public void incSessionsAborted() {
        sessionsAborted.incrementAndGet();
    }

    public long getLateSyncHttp() {
        return lateSyncHttp.get();
    }

    public long getLateSyncGrpc() {
        return lateSyncGrpc.get();
    }

    public long getLateSyncSip() {
        return lateSyncSip.get();
    }

    public long getLatePushHttp() {
        return latePushHttp.get();
    }

    public long getLatePushSip() {
        return latePushSip.get();
    }

    public long getLateDuplicate() {
        return lateDuplicate.get();
    }

    public long getLateAborted() {
        return lateAborted.get();
    }

    public long getLateExpired() {
        return lateExpired.get();
    }

    public long getLateStale() {
        return lateStale.get();
    }

    public long getSessionsAborted() {
        return sessionsAborted.get();
    }

    public void incBridgesStarted() {
        bridgesStarted.incrementAndGet();
    }

    public void incBridgeRecovered() {
        bridgeRecovered.incrementAndGet();
    }

    public void incLateCallbacks() {
        lateCallbacks.incrementAndGet();
    }

    public void incDuplicateCallbacks() {
        duplicateCallbacks.incrementAndGet();
    }

    public void incPushRetries() {
        pushRetries.incrementAndGet();
    }

    public void incPushRejected() {
        pushRejected.incrementAndGet();
    }

    public void incFallbackNotifications() {
        fallbackNotifications.incrementAndGet();
    }

    public void incDoubleSubmitBlocked() {
        doubleSubmitBlocked.incrementAndGet();
    }

    public void incSessionsCompleted() {
        sessionsCompleted.incrementAndGet();
    }

    public void incSessionsExpired() {
        sessionsExpired.incrementAndGet();
    }

    public long getBridgesStarted() {
        return bridgesStarted.get();
    }

    public long getBridgeRecovered() {
        return bridgeRecovered.get();
    }

    public long getLateCallbacks() {
        return lateCallbacks.get();
    }

    public long getDuplicateCallbacks() {
        return duplicateCallbacks.get();
    }

    public long getPushRetries() {
        return pushRetries.get();
    }

    public long getPushRejected() {
        return pushRejected.get();
    }

    public long getFallbackNotifications() {
        return fallbackNotifications.get();
    }

    public long getDoubleSubmitBlocked() {
        return doubleSubmitBlocked.get();
    }

    public long getSessionsCompleted() {
        return sessionsCompleted.get();
    }

    public long getSessionsExpired() {
        return sessionsExpired.get();
    }

    /** @return recovered / started, or 0 when no bridges have started. */
    public double bridgeRecoveryRate() {
        long started = bridgesStarted.get();
        return started == 0 ? 0d : (double) bridgeRecovered.get() / started;
    }

    @Override
    public String toString() {
        return "BridgeMetrics[started=" + bridgesStarted + ", recovered=" + bridgeRecovered
                + ", lateCallbacks=" + lateCallbacks + ", duplicateCallbacks=" + duplicateCallbacks
                + ", pushRetries=" + pushRetries + ", pushRejected=" + pushRejected
                + ", fallback=" + fallbackNotifications + ", doubleSubmitBlocked=" + doubleSubmitBlocked
                + ", completed=" + sessionsCompleted + ", expired=" + sessionsExpired
                + ", aborted=" + sessionsAborted
                + ", lateSync(http/grpc/sip)=" + lateSyncHttp + "/" + lateSyncGrpc + "/" + lateSyncSip
                + ", latePush(http/sip)=" + latePushHttp + "/" + latePushSip
                + ", lateDup=" + lateDuplicate + ", lateAborted=" + lateAborted
                + ", lateExpired=" + lateExpired + ", lateStale=" + lateStale + "]";
    }
}
