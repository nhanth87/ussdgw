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

import static org.testng.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.testng.annotations.Test;

public class PushRetryQueueTest {

    @Test
    public void deliversOnFirstAttempt() throws InterruptedException {
        final CountDownLatch delivered = new CountDownLatch(1);
        PushExecutor executor = new PushExecutor() {
            @Override
            public boolean deliver(PushRetryTask task) {
                delivered.countDown();
                return true;
            }
        };
        PushRetryQueue queue = new PushRetryQueue(1024, new long[] { 10L, 10L }, executor, null);
        queue.start();
        try {
            queue.enqueue(new PushRetryTask("c1", "8491", "payload", 0));
            assertTrue(delivered.await(2, TimeUnit.SECONDS), "task should be delivered");
        } finally {
            queue.stop();
        }
    }

    @Test
    public void retriesThenFallsBack() throws InterruptedException {
        final AtomicInteger attempts = new AtomicInteger();
        final CountDownLatch fellBack = new CountDownLatch(1);
        PushExecutor alwaysFail = new PushExecutor() {
            @Override
            public boolean deliver(PushRetryTask task) {
                attempts.incrementAndGet();
                return false;
            }
        };
        NotificationFallback fallback = new NotificationFallback() {
            @Override
            public void onPushUndeliverable(String msisdn, String correlationId, String payload, String reason) {
                fellBack.countDown();
            }
        };
        PushRetryQueue queue = new PushRetryQueue(1024, new long[] { 10L, 10L }, alwaysFail, fallback);
        queue.start();
        try {
            queue.enqueue(new PushRetryTask("c2", "8492", "payload", 0));
            assertTrue(fellBack.await(3, TimeUnit.SECONDS), "fallback should fire after retries");
            assertTrue(attempts.get() >= 2, "executor should be retried, attempts=" + attempts.get());
        } finally {
            queue.stop();
        }
    }
}
