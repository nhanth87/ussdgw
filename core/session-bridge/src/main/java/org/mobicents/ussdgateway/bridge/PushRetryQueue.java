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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jctools.queues.MpscArrayQueue;

/**
 * Lock-free, single-consumer retry queue for Network-Initiated pushes. Producers (SLEE event
 * threads) enqueue via {@link #enqueue(PushRetryTask)}; one worker thread drains the JCTools
 * {@link MpscArrayQueue}, honours each task's back-off schedule, and on exhausting the configured
 * delays invokes the {@link NotificationFallback}.
 * <p>
 * Mirrors the CDR Local RA writer pattern: hot path is a non-blocking offer, disk/network work is
 * confined to the worker.
 */
public final class PushRetryQueue {

    private static final Logger LOGGER = Logger.getLogger(PushRetryQueue.class.getName());

    private final MpscArrayQueue<PushRetryTask> queue;
    private final long[] retryDelaysMillis;
    private final PushExecutor executor;
    private final NotificationFallback fallback;
    private final BridgeMetrics metrics = BridgeMetrics.getInstance();

    private volatile boolean running;
    private Thread worker;

    public PushRetryQueue(int capacity, long[] retryDelaysMillis, PushExecutor executor,
            NotificationFallback fallback) {
        this.queue = new MpscArrayQueue<PushRetryTask>(normalizeCapacity(capacity));
        this.retryDelaysMillis = (retryDelaysMillis == null || retryDelaysMillis.length == 0)
                ? new long[] { 3000L, 8000L, 15000L }
                : retryDelaysMillis.clone();
        this.executor = executor;
        this.fallback = fallback != null ? fallback : new LoggingNotificationFallback();
    }

    private static int normalizeCapacity(int capacity) {
        int c = Math.max(1024, capacity);
        int n = 1;
        while (n < c && n < (1 << 30)) {
            n <<= 1;
        }
        return n;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                drainLoop();
            }
        }, "ussd-push-retry");
        worker.setDaemon(true);
        worker.start();
    }

    public synchronized void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(TimeUnit.SECONDS.toMillis(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            worker = null;
        }
    }

    public boolean isRunning() {
        return running && worker != null && worker.isAlive();
    }

    /**
     * Enqueue a task for (retry) delivery.
     *
     * @return {@code false} if the queue is full (the caller should fall back immediately).
     */
    public boolean enqueue(PushRetryTask task) {
        if (task == null) {
            return true;
        }
        boolean offered = queue.relaxedOffer(task);
        if (offered) {
            metrics.incPushRetries();
        } else {
            LOGGER.warning("Push retry queue full, dropping to fallback for " + task.getCorrelationId());
            fallback.onPushUndeliverable(task.getMsisdn(), task.getCorrelationId(), task.getPayload(), "queue-full");
        }
        return offered;
    }

    private void drainLoop() {
        while (running || !queue.isEmpty()) {
            PushRetryTask task = queue.relaxedPoll();
            if (task == null) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
                continue;
            }
            process(task);
        }
    }

    private void process(PushRetryTask task) {
        long now = System.currentTimeMillis();
        if (!task.isDue(now)) {
            // not yet due: re-enqueue and yield to avoid a tight spin on a single delayed task
            if (!queue.relaxedOffer(task)) {
                // queue saturated; wait until due inline
                long wait = Math.max(0L, task.getNextAttemptAtMillis() - System.currentTimeMillis());
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(Math.min(wait, 50L)));
                deliverOrReschedule(task);
            }
            return;
        }
        deliverOrReschedule(task);
    }

    private void deliverOrReschedule(PushRetryTask task) {
        boolean delivered = false;
        try {
            delivered = executor != null && executor.deliver(task);
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Push executor threw for " + task.getCorrelationId(), t);
        }
        if (delivered) {
            metrics.incBridgeRecovered();
            return;
        }
        metrics.incPushRejected();
        int nextAttempt = task.getAttempt();
        if (nextAttempt < retryDelaysMillis.length) {
            task.scheduleNext(retryDelaysMillis[nextAttempt]);
            if (!queue.relaxedOffer(task)) {
                fallback.onPushUndeliverable(task.getMsisdn(), task.getCorrelationId(), task.getPayload(),
                        "queue-full-on-retry");
            }
        } else {
            fallback.onPushUndeliverable(task.getMsisdn(), task.getCorrelationId(), task.getPayload(),
                    "retries-exhausted");
        }
    }
}
