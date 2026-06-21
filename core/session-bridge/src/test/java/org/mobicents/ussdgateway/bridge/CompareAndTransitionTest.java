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
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.testng.annotations.Test;

/**
 * Atomic compare-and-swap state transition (RFC §6.2) — the single idempotency point that makes
 * late-response delivery exactly-once across channels and guards against double charge.
 */
public class CompareAndTransitionTest {

    private VirtualSession bridged(String id) {
        VirtualSession s = new VirtualSession(id, id, "8490", 180000);
        s.transitionTo(FsmState.WAIT_USER);
        s.transitionTo(FsmState.WAIT_AS);
        s.setPendingRequestId("r-" + id);
        s.transitionTo(FsmState.BRIDGED);
        return s;
    }

    @Test
    public void casSucceedsOnceFromExpectedState() {
        InMemoryVirtualSessionStore store = new InMemoryVirtualSessionStore();
        store.save(bridged("c1"));

        VirtualSession first = store.compareAndTransition("c1", FsmState.BRIDGED, FsmState.PUSH_PENDING);
        assertNotNull(first);
        assertEquals(first.getFsmState(), FsmState.PUSH_PENDING);

        // Second attempt from the same expected state must fail (already moved on).
        VirtualSession second = store.compareAndTransition("c1", FsmState.BRIDGED, FsmState.PUSH_PENDING);
        assertNull(second);
    }

    @Test
    public void casFailsWhenStateMismatch() {
        InMemoryVirtualSessionStore store = new InMemoryVirtualSessionStore();
        store.save(bridged("c2"));
        // Expect WAIT_AS but it is BRIDGED → no transition.
        assertNull(store.compareAndTransition("c2", FsmState.WAIT_AS, FsmState.COMPLETED));
        assertEquals(store.getByCorrelationId("c2").getFsmState(), FsmState.BRIDGED);
    }

    @Test
    public void casReturnsNullForUnknownSession() {
        InMemoryVirtualSessionStore store = new InMemoryVirtualSessionStore();
        assertNull(store.compareAndTransition("nope", FsmState.BRIDGED, FsmState.PUSH_PENDING));
    }

    @Test
    public void concurrentCasYieldsExactlyOneWinner() throws Exception {
        final InMemoryVirtualSessionStore store = new InMemoryVirtualSessionStore();
        store.save(bridged("race"));

        final int threads = 32;
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicInteger winners = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            start.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        VirtualSession won = store.compareAndTransition("race", FsmState.BRIDGED,
                                FsmState.PUSH_PENDING);
                        if (won != null) {
                            winners.incrementAndGet();
                        }
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(winners.get(), 1, "exactly one thread may win the BRIDGED->PUSH_PENDING CAS");
        assertEquals(store.getByCorrelationId("race").getFsmState(), FsmState.PUSH_PENDING);
    }
}
