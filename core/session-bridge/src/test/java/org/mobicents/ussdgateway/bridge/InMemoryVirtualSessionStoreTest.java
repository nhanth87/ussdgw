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
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

public class InMemoryVirtualSessionStoreTest {

    private VirtualSession session(String correlationId, String requestId, String msisdn) {
        VirtualSession s = new VirtualSession(correlationId, correlationId, msisdn, 180000);
        s.setPendingRequestId(requestId);
        return s;
    }

    @Test
    public void saveAndLookupByCorrelationAndRequest() {
        InMemoryVirtualSessionStore store = new InMemoryVirtualSessionStore();
        store.save(session("c1", "r1", "8491"));

        assertNotNull(store.getByCorrelationId("c1"));
        VirtualSession byReq = store.getByRequestId("r1");
        assertNotNull(byReq);
        assertEquals(byReq.getCorrelationId(), "c1");
    }

    @Test
    public void removeClearsBothIndexes() {
        InMemoryVirtualSessionStore store = new InMemoryVirtualSessionStore();
        store.save(session("c2", "r2", "8492"));
        store.remove("c2");
        assertNull(store.getByCorrelationId("c2"));
        assertNull(store.getByRequestId("r2"));
    }

    @Test
    public void lockIsExclusivePerMsisdn() {
        InMemoryVirtualSessionStore store = new InMemoryVirtualSessionStore();
        assertTrue(store.tryLock("8493", "c3", 5000));
        assertFalse(store.tryLock("8493", "c3b", 5000));
        store.unlock("8493");
        assertTrue(store.tryLock("8493", "c3c", 5000));
    }

    @Test
    public void expiredLockCanBeReacquired() throws InterruptedException {
        InMemoryVirtualSessionStore store = new InMemoryVirtualSessionStore();
        assertTrue(store.tryLock("8494", "c4", 30));
        Thread.sleep(40);
        assertTrue(store.tryLock("8494", "c4b", 5000));
    }

    @Test
    public void activeMarkerRoundTrips() {
        InMemoryVirtualSessionStore store = new InMemoryVirtualSessionStore();
        store.markActive("8495", "c5", 5000);
        assertEquals(store.activeCorrelationId("8495"), "c5");
        store.clearActive("8495");
        assertNull(store.activeCorrelationId("8495"));
    }

    @Test
    public void expiredSessionReturnsNull() throws InterruptedException {
        InMemoryVirtualSessionStore store = new InMemoryVirtualSessionStore();
        VirtualSession s = new VirtualSession("c6", "c6", "8496", 30);
        s.setPendingRequestId("r6");
        store.save(s);
        Thread.sleep(40);
        assertNull(store.getByCorrelationId("c6"));
    }
}
