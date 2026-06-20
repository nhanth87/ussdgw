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

/**
 * Process-wide hand-off point between the gRPC RA worker threads (producers) and the SBB poll
 * timer (consumer). Entries are removed on {@link #poll(String)} and swept after a TTL to avoid
 * leaks when an SBB never collects (e.g. the MO dialogue was already released).
 */
public final class GrpcResponseRegistry {

    private static final long DEFAULT_TTL_MILLIS = 120000;
    private static final long SWEEP_INTERVAL_MILLIS = 30000;

    private static final GrpcResponseRegistry INSTANCE = new GrpcResponseRegistry();

    public static GrpcResponseRegistry getInstance() {
        return INSTANCE;
    }

    private static final class Entry {
        final GrpcResponse response;
        final long expireAtMillis;

        Entry(GrpcResponse response, long expireAtMillis) {
            this.response = response;
            this.expireAtMillis = expireAtMillis;
        }
    }

    private final ConcurrentHashMap<String, Entry> responses = new ConcurrentHashMap<String, Entry>();
    private volatile long lastSweep = System.currentTimeMillis();

    private GrpcResponseRegistry() {
    }

    public void put(GrpcResponse response) {
        if (response == null || response.getCorrelationId() == null) {
            return;
        }
        responses.put(response.getCorrelationId(),
                new Entry(response, System.currentTimeMillis() + DEFAULT_TTL_MILLIS));
        maybeSweep();
    }

    /**
     * @return the response for the correlation id, removing it, or {@code null} if not yet present.
     */
    public GrpcResponse poll(String correlationId) {
        if (correlationId == null) {
            return null;
        }
        Entry e = responses.remove(correlationId);
        return e == null ? null : e.response;
    }

    public void remove(String correlationId) {
        if (correlationId != null) {
            responses.remove(correlationId);
        }
    }

    public int size() {
        return responses.size();
    }

    private void maybeSweep() {
        long now = System.currentTimeMillis();
        if (now - lastSweep < SWEEP_INTERVAL_MILLIS) {
            return;
        }
        lastSweep = now;
        Iterator<Map.Entry<String, Entry>> it = responses.entrySet().iterator();
        while (it.hasNext()) {
            if (now >= it.next().getValue().expireAtMillis) {
                it.remove();
            }
        }
    }
}
