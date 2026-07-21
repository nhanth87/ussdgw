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

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

public class AdaptiveTimeoutTest {

    @Test
    public void noObservationReturnsConfiguredGate() {
        AdaptiveTimeout at = AdaptiveTimeout.getInstance();
        // a network id that is never fed
        assertEquals(at.suggestGateMs(987654, 7000), 7000);
    }

    @Test
    public void suggestionStaysWithinCeiling() {
        AdaptiveTimeout at = AdaptiveTimeout.getInstance();
        int net = 555;
        for (int i = 0; i < 20; i++) {
            at.recordLatency(net, 50000); // very slow AS
        }
        long gate = at.suggestGateMs(net, 7000);
        assertTrue(gate <= 7000, "Adaptive Timeout must not exceed configured ceiling, was " + gate);
        assertTrue(gate >= 500, "Adaptive Timeout must respect floor, was " + gate);
    }

    @Test
    public void fastAsYieldsShorterTimeout() {
        AdaptiveTimeout at = AdaptiveTimeout.getInstance();
        int net = 556;
        for (int i = 0; i < 20; i++) {
            at.recordLatency(net, 1200);
        }
        long gate = at.suggestGateMs(net, 7000);
        assertTrue(gate < 7000, "fast AS should yield Adaptive Timeout below the ceiling, was " + gate);
        assertTrue(gate >= 500, "Adaptive Timeout must respect floor, was " + gate);
        // 1200ms EWMA * 1.5 headroom ≈ 1800ms
        assertTrue(gate >= 1500 && gate <= 2500, "expected ~1800ms Adaptive Timeout, was " + gate);
    }

    @Test
    public void ignoresNonPositiveLatency() {
        AdaptiveTimeout at = AdaptiveTimeout.getInstance();
        int net = 557;
        at.recordLatency(net, 0);
        at.recordLatency(net, -1);
        assertEquals(at.suggestGateMs(net, 7000), 7000);
        assertEquals(at.observedLatencyMs(net), 0d);
    }

    @Test
    public void veryFastAsHitsFloor() {
        AdaptiveTimeout at = AdaptiveTimeout.getInstance();
        int net = 558;
        for (int i = 0; i < 20; i++) {
            at.recordLatency(net, 100); // 100 * 1.5 = 150 → floor 500
        }
        assertEquals(at.suggestGateMs(net, 7000), 500);
    }
}
