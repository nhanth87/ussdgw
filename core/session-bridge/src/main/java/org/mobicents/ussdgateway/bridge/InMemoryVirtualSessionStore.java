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

import java.util.concurrent.ConcurrentHashMap;

/**
 * Single-node, dependency-free {@link VirtualSessionStore} backed by {@link ConcurrentHashMap}
 * with lazy per-entry expiry. Used as the default store and in unit tests; also the runtime
 * fallback when an Infinispan cache cannot be resolved.
 */
public final class InMemoryVirtualSessionStore implements VirtualSessionStore {

    private static final class Expiring<V> {
        final V value;
        final long expireAtMillis;

        Expiring(V value, long expireAtMillis) {
            this.value = value;
            this.expireAtMillis = expireAtMillis;
        }

        boolean isExpired(long now) {
            return now >= expireAtMillis;
        }
    }

    private final ConcurrentHashMap<String, Expiring<VirtualSession>> sessions = new ConcurrentHashMap<String, Expiring<VirtualSession>>();
    private final ConcurrentHashMap<String, Expiring<String>> requestIndex = new ConcurrentHashMap<String, Expiring<String>>();
    private final ConcurrentHashMap<String, Expiring<String>> locks = new ConcurrentHashMap<String, Expiring<String>>();
    private final ConcurrentHashMap<String, Expiring<String>> active = new ConcurrentHashMap<String, Expiring<String>>();

    @Override
    public void save(VirtualSession session) {
        if (session == null) {
            return;
        }
        long expireAt = session.getExpireAtMillis();
        sessions.put(session.getCorrelationId(), new Expiring<VirtualSession>(session, expireAt));
        String requestId = session.getPendingRequestId();
        if (requestId != null) {
            requestIndex.put(requestId, new Expiring<String>(session.getCorrelationId(), expireAt));
        }
    }

    @Override
    public VirtualSession getByCorrelationId(String correlationId) {
        if (correlationId == null) {
            return null;
        }
        Expiring<VirtualSession> e = sessions.get(correlationId);
        if (e == null) {
            return null;
        }
        if (e.isExpired(System.currentTimeMillis())) {
            sessions.remove(correlationId, e);
            return null;
        }
        return e.value;
    }

    @Override
    public VirtualSession getByRequestId(String requestId) {
        if (requestId == null) {
            return null;
        }
        Expiring<String> e = requestIndex.get(requestId);
        if (e == null) {
            return null;
        }
        if (e.isExpired(System.currentTimeMillis())) {
            requestIndex.remove(requestId, e);
            return null;
        }
        return getByCorrelationId(e.value);
    }

    @Override
    public void remove(String correlationId) {
        if (correlationId == null) {
            return;
        }
        Expiring<VirtualSession> e = sessions.remove(correlationId);
        if (e != null && e.value != null && e.value.getPendingRequestId() != null) {
            requestIndex.remove(e.value.getPendingRequestId());
        }
    }

    @Override
    public boolean tryLock(String msisdn, String correlationId, long ttlMillis) {
        if (msisdn == null) {
            return true;
        }
        long now = System.currentTimeMillis();
        Expiring<String> existing = locks.get(msisdn);
        if (existing != null && !existing.isExpired(now)) {
            return false;
        }
        Expiring<String> fresh = new Expiring<String>(correlationId, now + ttlMillis);
        if (existing == null) {
            return locks.putIfAbsent(msisdn, fresh) == null;
        }
        return locks.replace(msisdn, existing, fresh);
    }

    @Override
    public void unlock(String msisdn) {
        if (msisdn != null) {
            locks.remove(msisdn);
        }
    }

    @Override
    public void markActive(String msisdn, String correlationId, long ttlMillis) {
        if (msisdn != null) {
            active.put(msisdn, new Expiring<String>(correlationId, System.currentTimeMillis() + ttlMillis));
        }
    }

    @Override
    public void clearActive(String msisdn) {
        if (msisdn != null) {
            active.remove(msisdn);
        }
    }

    @Override
    public String activeCorrelationId(String msisdn) {
        if (msisdn == null) {
            return null;
        }
        Expiring<String> e = active.get(msisdn);
        if (e == null) {
            return null;
        }
        if (e.isExpired(System.currentTimeMillis())) {
            active.remove(msisdn, e);
            return null;
        }
        return e.value;
    }
}
