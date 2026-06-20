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

import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-network adaptive gate timeout. Tracks an exponentially-weighted moving average (EWMA) of the
 * observed AS response latency and proposes a gate that is comfortably above typical latency but
 * still safely below the network timeout. This lets a fast AS use a short gate (early bridge) while
 * a consistently slow AS gets a longer one, reducing needless bridging.
 * <p>
 * The proposal is always clamped to {@code [floor, configuredGate]} so it can never exceed the
 * operator-configured ceiling.
 */
public final class AdaptiveTimeout {

    private static final AdaptiveTimeout INSTANCE = new AdaptiveTimeout();

    public static AdaptiveTimeout getInstance() {
        return INSTANCE;
    }

    /** Smoothing factor for the EWMA (0..1); higher reacts faster. */
    private static final double ALPHA = 0.2;
    /** Headroom multiplier applied to the average latency to absorb jitter. */
    private static final double HEADROOM = 1.5;
    private static final long FLOOR_MS = 1000;

    private static final class Ewma {
        volatile double valueMs;
        volatile boolean seeded;
    }

    private final ConcurrentHashMap<Integer, Ewma> perNetwork = new ConcurrentHashMap<Integer, Ewma>();

    private AdaptiveTimeout() {
    }

    public void recordLatency(int networkId, long latencyMs) {
        if (latencyMs <= 0) {
            return;
        }
        Ewma e = perNetwork.get(networkId);
        if (e == null) {
            e = new Ewma();
            Ewma prev = perNetwork.putIfAbsent(networkId, e);
            if (prev != null) {
                e = prev;
            }
        }
        synchronized (e) {
            if (!e.seeded) {
                e.valueMs = latencyMs;
                e.seeded = true;
            } else {
                e.valueMs = ALPHA * latencyMs + (1 - ALPHA) * e.valueMs;
            }
        }
    }

    /**
     * @param networkId      the operator/subnetwork id
     * @param configuredGate the operator-configured gate ceiling (ms)
     * @return a gate timeout in {@code [floor, configuredGate]} adapted to observed latency.
     */
    public long suggestGateMs(int networkId, long configuredGate) {
        Ewma e = perNetwork.get(networkId);
        if (e == null || !e.seeded) {
            return configuredGate;
        }
        long proposed = (long) (e.valueMs * HEADROOM);
        if (proposed < FLOOR_MS) {
            proposed = FLOOR_MS;
        }
        if (proposed > configuredGate) {
            proposed = configuredGate;
        }
        return proposed;
    }

    public double observedLatencyMs(int networkId) {
        Ewma e = perNetwork.get(networkId);
        return (e == null || !e.seeded) ? 0d : e.valueMs;
    }
}
