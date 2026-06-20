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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.mobicents.ussdgateway;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.testng.annotations.Test;

/**
 * Functional + contention tests for {@link MpscMessageList}.
 *
 * <p>Covers the MPSC contract that {@link UssdSctpLoadGenerator} and any
 * future multi-producer inbox in the SBB hot path rely on.</p>
 */
public class MpscMessageListTest {

    @Test
    public void basicOfferPoll() {
        MpscMessageList<String> q = new MpscMessageList<>(8);
        assertTrue(q.isEmpty());
        assertEquals(q.size(), 0);
        assertEquals(q.capacity(), 8);

        assertTrue(q.offer("a"));
        assertTrue(q.offer("b"));
        assertTrue(q.offer("c"));
        assertEquals(q.size(), 3);
        assertFalse(q.isEmpty());

        assertEquals(q.poll(), "a");
        assertEquals(q.poll(), "b");
        assertEquals(q.poll(), "c");
        assertNull(q.poll());
        assertTrue(q.isEmpty());
    }

    @Test
    public void fifoOrder() {
        MpscMessageList<Integer> q = new MpscMessageList<>(64);
        int n = 50;
        for (int i = 0; i < n; i++) {
            assertTrue(q.offer(i));
        }
        for (int i = 0; i < n; i++) {
            assertEquals(q.poll().intValue(), i);
        }
        assertNull(q.poll());
    }

    @Test
    public void offerRejectedWhenFull() {
        // capacity is rounded up to next power of two
        MpscMessageList<Integer> q = new MpscMessageList<>(2);  // -> 2
        assertEquals(q.capacity(), 2);

        assertTrue(q.offer(1));
        assertTrue(q.offer(2));
        assertFalse(q.offer(3), "third offer must fail when capacity=2");
        assertEquals(q.droppedCount(), 1L);
        assertEquals(q.offeredCount(), 2L);
        assertEquals(q.size(), 2);
    }

    @Test
    public void roundUpToPowerOfTwo() {
        assertEquals(new MpscMessageList<>(1).capacity(), 1);
        assertEquals(new MpscMessageList<>(3).capacity(), 4);
        assertEquals(new MpscMessageList<>(5).capacity(), 8);
        assertEquals(new MpscMessageList<>(1025).capacity(), 2048);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void zeroCapacityRejected() {
        new MpscMessageList<>(0);
    }

    @Test(expectedExceptions = NullPointerException.class)
    public void nullOfferRejected() {
        new MpscMessageList<String>().offer(null);
    }

    @Test
    public void addAliasForListCompatibility() {
        MpscMessageList<Integer> q = new MpscMessageList<>(8);
        assertTrue(q.add(1));
        assertTrue(q.add(2));
        assertEquals(q.size(), 2);
        assertEquals(q.poll().intValue(), 1);
    }

    @Test
    public void snapshotReturnsCopy() {
        MpscMessageList<String> q = new MpscMessageList<>(8);
        q.offer("x");
        q.offer("y");
        ArrayList<String> snap = q.snapshot();
        ArrayList<String> expected = new ArrayList<>();
        expected.add("x");
        expected.add("y");
        assertEquals(snap, expected);
        // MpscArrayQueue has no non-destructive enumeration, so snapshot
        // necessarily drains the queue. The returned ArrayList is the copy.
        assertTrue(q.isEmpty());
    }

    @Test
    public void drainTo() {
        MpscMessageList<Integer> q = new MpscMessageList<>(8);
        for (int i = 0; i < 5; i++) {
            q.offer(i);
        }
        ArrayList<Integer> sink = new ArrayList<>();
        int n = q.drainTo(sink);
        assertEquals(n, 5);
        assertEquals(sink.size(), 5);
        assertTrue(q.isEmpty());
    }

    @Test
    public void drainToBounded() {
        MpscMessageList<Integer> q = new MpscMessageList<>(8);
        for (int i = 0; i < 5; i++) {
            q.offer(i);
        }
        ArrayList<Integer> sink = new ArrayList<>();
        int n = q.drainTo(sink, 3);
        assertEquals(n, 3);
        assertEquals(sink.size(), 3);
        assertEquals(q.size(), 2);
    }

    @Test
    public void iteratorDrains() {
        MpscMessageList<Integer> q = new MpscMessageList<>(8);
        for (int i = 0; i < 4; i++) {
            q.offer(i);
        }
        int sum = 0;
        int count = 0;
        java.util.Iterator<Integer> it = q.iterator();
        while (it.hasNext()) {
            Integer e = it.next();
            assertNotNull(e);
            sum += e;
            count++;
        }
        assertEquals(count, 4);
        assertEquals(sum, 0 + 1 + 2 + 3);
        assertTrue(q.isEmpty());
    }

    @Test
    public void forEachDrains() {
        MpscMessageList<String> q = new MpscMessageList<>(8);
        q.offer("a");
        q.offer("b");
        StringBuilder sb = new StringBuilder();
        q.forEach(sb::append);
        assertEquals(sb.toString(), "ab");
        assertTrue(q.isEmpty());
    }

    @Test
    public void clearDrains() {
        MpscMessageList<Integer> q = new MpscMessageList<>(8);
        for (int i = 0; i < 5; i++) {
            q.offer(i);
        }
        int discarded = q.clear();
        assertEquals(discarded, 5);
        assertTrue(q.isEmpty());
    }

    @Test
    public void toStringIncludesStats() {
        MpscMessageList<Integer> q = new MpscMessageList<>(4);
        q.offer(1);
        q.offer(2);
        q.poll();
        String s = q.toString();
        assertTrue(s.contains("MpscMessageList"), s);
        assertTrue(s.contains("offered=2"), s);
        assertTrue(s.contains("polled=1"), s);
    }

    /**
     * The headline guarantee: many producer threads can call offer() and the
     * single consumer drains every element exactly once.
     *
     * <p>This is the exact pattern the load generator relies on
     * (one Netty IO thread polls, many SctpWorker threads offer).</p>
     *
     * <p>To avoid races, the test uses a single consumer thread (MPSC is
     * single-consumer by contract) and {@code done.await()} as the
     * happens-before edge between the producers' relaxed offers and the
     * consumer's drain. Without that edge the consumer could observe
     * {@code done.getCount() == 0} before the producer's final offer was
     * published to the ring buffer's slot.</p>
     */
    @Test
    public void concurrentMpscNoLoss() throws Exception {
        final int producers = 8;
        final int perProducer = 5_000;
        final int total = producers * perProducer;
        final MpscMessageList<Integer> q = new MpscMessageList<>(4096);
        final ExecutorService pool = Executors.newFixedThreadPool(producers);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(producers);
        final AtomicInteger offered = new AtomicInteger();

        for (int p = 0; p < producers; p++) {
            final int producerId = p;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perProducer; i++) {
                        // Bounded queue may drop on overflow - retry until accepted.
                        Integer v = producerId * perProducer + i;
                        while (!q.offer(v)) {
                            Thread.yield();
                        }
                        offered.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();

        // The bounded queue (4096) is much smaller than the total (40000),
        // so a concurrent drainer must run to keep producers from spinning.
        // That drainer is the SOLE consumer of the MPSC queue.
        final AtomicInteger concurrentPolled = new AtomicInteger();
        final java.util.concurrent.atomic.AtomicReference<Throwable> drainerError = new java.util.concurrent.atomic.AtomicReference<>();
        Thread drainer = new Thread(() -> {
            try {
                // Phase 1: drain while producers are still working.
                while (done.getCount() > 0) {
                    Integer v = q.poll();
                    if (v != null) {
                        concurrentPolled.incrementAndGet();
                    } else {
                        Thread.yield();
                    }
                }
                // done.getCount() is now 0. Use await() to establish the
                // happens-before edge against the producers' countDown()
                // calls, then drain any straggler elements.
                done.await();
                Integer v;
                while ((v = q.poll()) != null) {
                    concurrentPolled.incrementAndGet();
                }
            } catch (Throwable t) {
                drainerError.set(t);
            }
        }, "MPSC-TestDrainer");
        drainer.start();

        drainer.join(30_000);
        assertFalse(drainer.isAlive(), "drainer should have finished in 30s");
        if (drainerError.get() != null) {
            throw new AssertionError("drainer failed", drainerError.get());
        }

        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(concurrentPolled.get(), total,
            "MPSC must deliver every offered element exactly once");
        assertEquals(q.offeredCount(), (long) total);
        assertEquals(offered.get(), total);
        assertTrue(q.isEmpty());
    }

    /**
     * If the consumer never drains, a producer hitting a full queue must see
     * a rejected offer (returns false) and the droppedCount must tick. This
     * is what {@code !inbox.offer(event)} checks in the SBB hot path.
     */
    @Test
    public void overflowDetected() {
        MpscMessageList<Integer> q = new MpscMessageList<>(2);
        assertTrue(q.offer(1));
        assertTrue(q.offer(2));
        assertFalse(q.offer(3));
        assertFalse(q.offer(4));
        assertEquals(q.droppedCount(), 2L);
    }

    @Test
    public void peekDoesNotConsume() {
        MpscMessageList<String> q = new MpscMessageList<>(4);
        q.offer("x");
        assertEquals(q.peek(), "x");
        assertEquals(q.peek(), "x");
        assertEquals(q.size(), 1);
        assertEquals(q.poll(), "x");
    }
}
