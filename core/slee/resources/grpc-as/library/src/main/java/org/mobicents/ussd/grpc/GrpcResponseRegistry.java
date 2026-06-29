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

package org.mobicents.ussd.grpc;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide hand-off point between the gRPC RA worker threads (producers) and the SBB poll
 * timer (consumer). Entries are removed on {@link #poll(String)} and swept after a TTL to avoid
 * leaks when an SBB never collects (e.g. the MO dialogue was already released).
 *
 * <p>Sweep runs on a dedicated daemon thread every 30 seconds so that producer threads (gRPC
 * callbacks) are never blocked by cleanup work.
 *
 * <p>Maximum capacity is bounded at 1_000_000 entries to prevent runaway memory growth.
 * Above that threshold, the oldest entries are evicted first (FIFO).
 *
 * @author USSD Gateway Team
 */
public final class GrpcResponseRegistry {

    private static final long DEFAULT_TTL_MILLIS = 120000;   // 2 minutes
    private static final long SWEEP_INTERVAL_MILLIS = 30000; // 30 seconds
    private static final int MAX_CAPACITY = 1_000_000;       // ~= 128 MB heap at 128 bytes/entry

    private static final GrpcResponseRegistry INSTANCE = new GrpcResponseRegistry();

    public static GrpcResponseRegistry getInstance() {
        return INSTANCE;
    }

    private static final class Entry {
        final GrpcResponse response;
        final long expireAtMillis;
        final long sequence; // insertion order for bounded eviction

        Entry(GrpcResponse response, long expireAtMillis, long sequence) {
            this.response = response;
            this.expireAtMillis = expireAtMillis;
            this.sequence = sequence;
        }
    }

    private final ConcurrentHashMap<String, Entry> responses = new ConcurrentHashMap<String, Entry>();
    private final AtomicLong sequenceCounter = new AtomicLong();
    private final ScheduledExecutorService sweeper;
    private volatile long oldestSequence = 0;

    private GrpcResponseRegistry() {
        this.sweeper = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            private final AtomicLong n = new AtomicLong();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "grpc-registry-sweep-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
        this.sweeper.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                sweep();
            }
        }, SWEEP_INTERVAL_MILLIS, SWEEP_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }

    /**
     * Stores a response keyed by its correlation id.
     * Silently ignores null responses and null correlation ids.
     */
    public void put(GrpcResponse response) {
        if (response == null || response.getCorrelationId() == null) {
            return;
        }
        long seq = sequenceCounter.incrementAndGet();
        long expireAt = System.currentTimeMillis() + DEFAULT_TTL_MILLIS;
        responses.put(response.getCorrelationId(), new Entry(response, expireAt, seq));
        // Bounded eviction: if we exceed capacity, schedule an immediate sweep
        if (responses.size() > MAX_CAPACITY) {
            sweeper.submit(new Runnable() {
                @Override
                public void run() {
                    sweepBounded();
                }
            });
        }
    }

    /**
     * Atomically removes and returns the response for the given correlation id,
     * or {@code null} if not yet present.
     */
    public GrpcResponse poll(String correlationId) {
        if (correlationId == null) {
            return null;
        }
        Entry e = responses.remove(correlationId);
        return e == null ? null : e.response;
    }

    /**
     * Removes the entry for the given correlation id if present.
     */
    public void remove(String correlationId) {
        if (correlationId != null) {
            responses.remove(correlationId);
        }
    }

    /**
     * @return the approximate number of entries currently stored.
     */
    public int size() {
        return responses.size();
    }

    // -------------------------------------------------------------
    // Background sweep — runs on the dedicated sweeper thread
    // -------------------------------------------------------------

    /**
     * Periodic TTL-based sweep. Iterates the map and removes expired entries.
     * Runs on the background sweeper thread every 30 seconds.
     */
    private void sweep() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Entry>> it = responses.entrySet().iterator();
        long minSeq = Long.MAX_VALUE;
        while (it.hasNext()) {
            Entry e = it.next().getValue();
            if (now >= e.expireAtMillis) {
                it.remove();
            } else if (e.sequence < minSeq) {
                minSeq = e.sequence;
            }
        }
        oldestSequence = minSeq == Long.MAX_VALUE ? 0 : minSeq;
    }

    /**
     * Capacity-based eviction (FIFO). Removes the oldest 10% of entries when the map exceeds
     * {@link #MAX_CAPACITY}. This prevents unbounded memory growth.
     */
    private void sweepBounded() {
        int over = responses.size() - MAX_CAPACITY;
        if (over <= 0) {
            return;
        }
        // Evict oldest entries: remove up to 10% of max capacity
        long cutoff = oldestSequence + Math.min(over + MAX_CAPACITY / 10, MAX_CAPACITY / 4);
        Iterator<Map.Entry<String, Entry>> it = responses.entrySet().iterator();
        long minSeq = Long.MAX_VALUE;
        while (it.hasNext()) {
            Entry e = it.next().getValue();
            if (e.sequence < cutoff) {
                it.remove();
            } else if (e.sequence < minSeq) {
                minSeq = e.sequence;
            }
        }
        oldestSequence = minSeq == Long.MAX_VALUE ? 0 : minSeq;
    }
}
