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
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

public class VirtualSessionFsmTest {

    @Test
    public void happyPathPullCompletes() {
        VirtualSession s = new VirtualSession("c1", "c1", "8491", 180000);
        assertEquals(s.getFsmState(), FsmState.CREATED);
        assertTrue(s.transitionTo(FsmState.WAIT_USER));
        assertTrue(s.transitionTo(FsmState.WAIT_AS));
        assertTrue(s.transitionTo(FsmState.COMPLETED));
        assertTrue(s.getFsmState().isTerminal());
    }

    @Test
    public void bridgePathReachesPushPending() {
        VirtualSession s = new VirtualSession("c2", "c2", "8492", 180000);
        s.transitionTo(FsmState.WAIT_USER);
        s.transitionTo(FsmState.WAIT_AS);
        assertTrue(s.transitionTo(FsmState.BRIDGED));
        assertTrue(s.transitionTo(FsmState.PUSH_PENDING));
        assertTrue(s.transitionTo(FsmState.COMPLETED));
    }

    @Test
    public void illegalTransitionsRejected() {
        VirtualSession s = new VirtualSession("c3", "c3", "8493", 180000);
        // CREATED -> PUSH_PENDING is illegal
        assertFalse(s.transitionTo(FsmState.PUSH_PENDING));
        assertEquals(s.getFsmState(), FsmState.CREATED);
    }

    @Test
    public void terminalStateAcceptsNoTransitions() {
        VirtualSession s = new VirtualSession("c4", "c4", "8494", 180000);
        s.transitionTo(FsmState.WAIT_USER);
        s.transitionTo(FsmState.WAIT_AS);
        s.transitionTo(FsmState.COMPLETED);
        assertFalse(s.transitionTo(FsmState.PUSH_PENDING));
        assertFalse(s.transitionTo(FsmState.BRIDGED));
    }

    @Test
    public void expiryIsComputedFromTtl() {
        VirtualSession s = new VirtualSession("c5", "c5", "8495", 50);
        assertFalse(s.isExpired(s.getCreatedAtMillis()));
        assertTrue(s.isExpired(s.getCreatedAtMillis() + 51));
    }

    @Test
    public void networkAbortFromWaitAsIsTerminal() {
        VirtualSession s = new VirtualSession("c6", "c6", "8496", 180000);
        s.transitionTo(FsmState.WAIT_USER);
        s.transitionTo(FsmState.WAIT_AS);
        assertTrue(s.transitionTo(FsmState.ABORTED));
        assertTrue(s.getFsmState().isTerminal());
        // No transitions out of ABORTED (cannot be reopened for a push).
        assertFalse(s.transitionTo(FsmState.PUSH_PENDING));
        assertFalse(s.transitionTo(FsmState.BRIDGED));
    }

    @Test
    public void bridgedCanAbortButAbortedNeverPushes() {
        VirtualSession s = new VirtualSession("c7", "c7", "8497", 180000);
        s.transitionTo(FsmState.WAIT_USER);
        s.transitionTo(FsmState.WAIT_AS);
        s.transitionTo(FsmState.BRIDGED);
        assertTrue(s.transitionTo(FsmState.ABORTED));
        assertFalse(s.transitionTo(FsmState.PUSH_PENDING));
    }

    @Test
    public void inputGenerationIsMonotonic() {
        VirtualSession s = new VirtualSession("c8", "c8", "8498", 180000);
        assertEquals(s.getInputGeneration(), 0);
        assertEquals(s.incrementInputGeneration(), 1);
        assertEquals(s.incrementInputGeneration(), 2);
        assertEquals(s.getInputGeneration(), 2);
    }

    @Test
    public void copyConstructorPreservesState() {
        VirtualSession s = new VirtualSession("c9", "c9", "8499", 180000);
        s.transitionTo(FsmState.WAIT_USER);
        s.transitionTo(FsmState.WAIT_AS);
        s.transitionTo(FsmState.BRIDGED);
        s.setPendingRequestId("r9");
        s.setInputGeneration(3);
        s.setLastMenu("menu");
        VirtualSession copy = new VirtualSession(s);
        assertEquals(copy.getCorrelationId(), "c9");
        assertEquals(copy.getFsmState(), FsmState.BRIDGED);
        assertEquals(copy.getPendingRequestId(), "r9");
        assertEquals(copy.getInputGeneration(), 3);
        assertEquals(copy.getLastMenu(), "menu");
        // Mutating the copy must not affect the original (copy-on-write CAS safety).
        copy.transitionTo(FsmState.PUSH_PENDING);
        assertEquals(s.getFsmState(), FsmState.BRIDGED);
    }
}
