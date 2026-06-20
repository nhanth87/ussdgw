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

    private BridgeMetrics() {
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
                + ", completed=" + sessionsCompleted + ", expired=" + sessionsExpired + "]";
    }
}
