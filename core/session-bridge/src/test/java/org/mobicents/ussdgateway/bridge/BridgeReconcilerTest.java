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
import static org.testng.Assert.assertTrue;

import java.nio.charset.Charset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.mobicents.ussdgateway.bridge.ReconcileResult.Outcome;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Unified late-response reconciliation (RFC §5/§13) covering every outcome and the
 * concurrent sync+push race (the billing-safety guarantee).
 */
public class BridgeReconcilerTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final byte[] MENU = "<menu/>".getBytes(UTF8);

    private InMemoryVirtualSessionStore store;
    private BridgeReconciler reconciler;

    @BeforeMethod
    public void setUp() {
        store = new InMemoryVirtualSessionStore();
        reconciler = new BridgeReconciler(store, BridgeMetrics.getInstance());
    }

    private VirtualSession bridged(String id, String requestId) {
        VirtualSession s = new VirtualSession(id, id, "849" + id, 180000);
        s.transitionTo(FsmState.WAIT_USER);
        s.transitionTo(FsmState.WAIT_AS);
        s.setPendingRequestId(requestId);
        s.transitionTo(FsmState.BRIDGED);
        store.save(s);
        return s;
    }

    @Test
    public void deliveredOnFirstLateResponse() {
        bridged("1", "r1");
        ReconcileResult r = reconciler.reconcileLateResponse("r1", MENU, ReconcileChannel.SYNC_HTTP,
                BridgeReconciler.NO_GENERATION);
        assertEquals(r.getOutcome(), Outcome.DELIVERED);
        assertTrue(r.shouldPush());
        assertNotNull(r.getSession());
        assertEquals(r.getSession().getFsmState(), FsmState.PUSH_PENDING);
        // menu is cached for retry
        assertEquals(r.getSession().getLastMenu(), new String(MENU, UTF8));
    }

    @Test
    public void duplicateSecondResponseDropped() {
        bridged("2", "r2");
        reconciler.reconcileLateResponse("r2", MENU, ReconcileChannel.SYNC_HTTP, BridgeReconciler.NO_GENERATION);
        ReconcileResult dup = reconciler.reconcileLateResponse("r2", MENU, ReconcileChannel.PUSH_HTTP,
                BridgeReconciler.NO_GENERATION);
        assertEquals(dup.getOutcome(), Outcome.DUPLICATE);
        assertTrue(dup.isHandled());
        assertTrue(!dup.shouldPush());
    }

    @Test
    public void unknownRequestIdYieldsExpired() {
        ReconcileResult r = reconciler.reconcileLateResponse("ghost", MENU, ReconcileChannel.PUSH_HTTP,
                BridgeReconciler.NO_GENERATION);
        assertEquals(r.getOutcome(), Outcome.EXPIRED);
    }

    @Test
    public void nullRequestIdYieldsUnknown() {
        ReconcileResult r = reconciler.reconcileLateResponse(null, MENU, ReconcileChannel.PUSH_HTTP,
                BridgeReconciler.NO_GENERATION);
        assertEquals(r.getOutcome(), Outcome.UNKNOWN);
    }

    @Test
    public void abortedSessionDropsLateResponse() {
        VirtualSession s = new VirtualSession("3", "3", "8493", 180000);
        s.transitionTo(FsmState.WAIT_USER);
        s.transitionTo(FsmState.WAIT_AS);
        s.setPendingRequestId("r3");
        store.save(s);
        // network abort before bridging
        assertTrue(reconciler.markAborted("3"));
        ReconcileResult r = reconciler.reconcileLateResponse("r3", MENU, ReconcileChannel.SYNC_HTTP,
                BridgeReconciler.NO_GENERATION);
        assertEquals(r.getOutcome(), Outcome.ABORTED);
        assertTrue(!r.shouldPush());
    }

    @Test
    public void markAbortedDoesNotTouchBridgedSession() {
        bridged("4", "r4");
        // A bridged session must not be aborted by teardown events (we committed to push).
        assertTrue(!reconciler.markAborted("4"));
        ReconcileResult r = reconciler.reconcileLateResponse("r4", MENU, ReconcileChannel.SYNC_GRPC,
                BridgeReconciler.NO_GENERATION);
        assertEquals(r.getOutcome(), Outcome.DELIVERED);
    }

    @Test
    public void staleResponseFromOlderGenerationDropped() {
        VirtualSession s = bridged("5", "r5");
        s.setInputGeneration(2);
        store.save(s);
        // a response stamped with an older generation (1) is superseded by input gen 2
        ReconcileResult r = reconciler.reconcileLateResponse("r5", MENU, ReconcileChannel.SYNC_HTTP, 1);
        assertEquals(r.getOutcome(), Outcome.STALE);
    }

    @Test
    public void currentGenerationIsDelivered() {
        VirtualSession s = bridged("6", "r6");
        s.setInputGeneration(2);
        store.save(s);
        ReconcileResult r = reconciler.reconcileLateResponse("r6", MENU, ReconcileChannel.SYNC_HTTP, 2);
        assertEquals(r.getOutcome(), Outcome.DELIVERED);
    }

    @Test
    public void queuedWhenHigherPriorityMoActive() {
        VirtualSession s = bridged("7", "r7");
        s.setPriority(SessionPriority.PUSH_DEFAULT);
        store.save(s);
        // a higher-priority active MO session exists for the same msisdn
        store.markActive(s.getMsisdn(), "other-active", 180000);
        ReconcileResult r = reconciler.reconcileLateResponse("r7", MENU, ReconcileChannel.PUSH_HTTP,
                BridgeReconciler.NO_GENERATION);
        assertEquals(r.getOutcome(), Outcome.QUEUED);
        // CAS already moved it to PUSH_PENDING; it is awaiting retry, not re-deliverable
        assertEquals(store.getByCorrelationId("7").getFsmState(), FsmState.PUSH_PENDING);
    }

    @Test
    public void concurrentSyncAndPushDeliverExactlyOnce() throws Exception {
        bridged("race", "rR");
        final int threads = 16;
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicInteger delivered = new AtomicInteger();
        final AtomicInteger duplicate = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                final ReconcileChannel ch = (i % 2 == 0) ? ReconcileChannel.SYNC_HTTP
                        : ReconcileChannel.PUSH_HTTP;
                pool.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            start.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        ReconcileResult r = reconciler.reconcileLateResponse("rR", MENU, ch,
                                BridgeReconciler.NO_GENERATION);
                        if (r.getOutcome() == Outcome.DELIVERED) {
                            delivered.incrementAndGet();
                        } else if (r.getOutcome() == Outcome.DUPLICATE) {
                            duplicate.incrementAndGet();
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
        assertEquals(delivered.get(), 1, "exactly one channel may deliver (no double charge)");
        assertEquals(duplicate.get(), threads - 1);
    }

    @Test
    public void deliveredOnFirstLateResponseViaPushGrpc() {
        bridged("grpc1", "rg1");
        long before = BridgeMetrics.getInstance().getLatePushGrpc();
        ReconcileResult r = reconciler.reconcileLateResponse("rg1", MENU, ReconcileChannel.PUSH_GRPC,
                BridgeReconciler.NO_GENERATION);
        assertEquals(r.getOutcome(), Outcome.DELIVERED);
        assertTrue(r.shouldPush());
        assertEquals(BridgeMetrics.getInstance().getLatePushGrpc(), before + 1);
    }

    @Test
    public void duplicatePushGrpcDropped() {
        bridged("grpc2", "rg2");
        reconciler.reconcileLateResponse("rg2", MENU, ReconcileChannel.PUSH_GRPC,
                BridgeReconciler.NO_GENERATION);
        ReconcileResult dup = reconciler.reconcileLateResponse("rg2", MENU, ReconcileChannel.PUSH_GRPC,
                BridgeReconciler.NO_GENERATION);
        assertEquals(dup.getOutcome(), Outcome.DUPLICATE);
        assertTrue(!dup.shouldPush());
    }

    @Test
    public void unknownRequestIdViaPushGrpcYieldsExpired() {
        ReconcileResult r = reconciler.reconcileLateResponse("ghost-grpc", MENU, ReconcileChannel.PUSH_GRPC,
                BridgeReconciler.NO_GENERATION);
        assertEquals(r.getOutcome(), Outcome.EXPIRED);
    }

    @Test
    public void concurrentSyncGrpcAndPushGrpcDeliverExactlyOnce() throws Exception {
        bridged("grpcRace", "rGR");
        final int threads = 16;
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicInteger delivered = new AtomicInteger();
        final AtomicInteger duplicate = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                final ReconcileChannel ch = (i % 2 == 0) ? ReconcileChannel.SYNC_GRPC
                        : ReconcileChannel.PUSH_GRPC;
                pool.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            start.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        ReconcileResult r = reconciler.reconcileLateResponse("rGR", MENU, ch,
                                BridgeReconciler.NO_GENERATION);
                        if (r.getOutcome() == Outcome.DELIVERED) {
                            delivered.incrementAndGet();
                        } else if (r.getOutcome() == Outcome.DUPLICATE) {
                            duplicate.incrementAndGet();
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
        assertEquals(delivered.get(), 1);
        assertEquals(duplicate.get(), threads - 1);
        assertTrue(BridgeMetrics.getInstance().getLatePushGrpc()
                + BridgeMetrics.getInstance().getLateSyncGrpc() >= 1);
    }
}
