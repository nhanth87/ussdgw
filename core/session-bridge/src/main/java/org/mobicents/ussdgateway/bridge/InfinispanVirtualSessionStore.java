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

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.naming.InitialContext;

/**
 * {@link VirtualSessionStore} backed by a WildFly-managed Infinispan cache resolved via JNDI.
 * <p>
 * No compile-time dependency on the Infinispan API: the cache is used through the
 * {@link ConcurrentMap} contract (Infinispan's {@code Cache} implements it), and per-entry TTL
 * is applied reflectively via {@code put(K,V,long,TimeUnit)} when available, falling back to a
 * plain put governed by the cache-level expiration configured in {@code standalone.xml}.
 * <p>
 * If the cache cannot be resolved, construction throws and callers should fall back to
 * {@link InMemoryVirtualSessionStore} (see {@link VirtualSessionStoreFactory}).
 */
public final class InfinispanVirtualSessionStore implements VirtualSessionStore {

    private static final Logger LOGGER = Logger.getLogger(InfinispanVirtualSessionStore.class.getName());

    private static final String KEY_SESSION = "vs:";
    private static final String KEY_REQUEST = "req:";
    private static final String KEY_LOCK = "lock:";
    private static final String KEY_ACTIVE = "active:";

    private final ConcurrentMap<String, Object> cache;
    private final Method ttlPut;

    @SuppressWarnings("unchecked")
    public InfinispanVirtualSessionStore(String containerJndiName, String cacheName) throws Exception {
        InitialContext ctx = new InitialContext();
        Object container = ctx.lookup(containerJndiName);
        if (container == null) {
            throw new IllegalStateException("Infinispan container not found at " + containerJndiName);
        }
        Method getCache = container.getClass().getMethod("getCache", String.class);
        Object resolved = getCache.invoke(container, cacheName);
        if (!(resolved instanceof ConcurrentMap)) {
            throw new IllegalStateException("Resolved cache is not a ConcurrentMap: " + resolved);
        }
        this.cache = (ConcurrentMap<String, Object>) resolved;
        this.ttlPut = resolveTtlPut(resolved);
        LOGGER.info("InfinispanVirtualSessionStore bound to " + containerJndiName + "/" + cacheName
                + ", nativeTtl=" + (ttlPut != null));
    }

    private static Method resolveTtlPut(Object cache) {
        try {
            return cache.getClass().getMethod("put", Object.class, Object.class, long.class, TimeUnit.class);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private void put(String key, Object value, long ttlMillis) {
        if (ttlPut != null) {
            try {
                ttlPut.invoke(cache, key, value, ttlMillis, TimeUnit.MILLISECONDS);
                return;
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "TTL put failed, falling back to plain put", e);
            }
        }
        cache.put(key, value);
    }

    @Override
    public void save(VirtualSession session) {
        if (session == null) {
            return;
        }
        long ttl = Math.max(1L, session.getExpireAtMillis() - System.currentTimeMillis());
        put(KEY_SESSION + session.getCorrelationId(), session, ttl);
        if (session.getPendingRequestId() != null) {
            put(KEY_REQUEST + session.getPendingRequestId(), session.getCorrelationId(), ttl);
        }
    }

    @Override
    public VirtualSession getByCorrelationId(String correlationId) {
        if (correlationId == null) {
            return null;
        }
        Object v = cache.get(KEY_SESSION + correlationId);
        if (!(v instanceof VirtualSession)) {
            return null;
        }
        VirtualSession session = (VirtualSession) v;
        if (session.isExpired(System.currentTimeMillis())) {
            cache.remove(KEY_SESSION + correlationId);
            return null;
        }
        return session;
    }

    @Override
    public VirtualSession getByRequestId(String requestId) {
        if (requestId == null) {
            return null;
        }
        Object v = cache.get(KEY_REQUEST + requestId);
        if (!(v instanceof String)) {
            return null;
        }
        return getByCorrelationId((String) v);
    }

    @Override
    public void remove(String correlationId) {
        if (correlationId == null) {
            return;
        }
        Object v = cache.remove(KEY_SESSION + correlationId);
        if (v instanceof VirtualSession) {
            String requestId = ((VirtualSession) v).getPendingRequestId();
            if (requestId != null) {
                cache.remove(KEY_REQUEST + requestId);
            }
        }
    }

    @Override
    public VirtualSession compareAndTransition(String correlationId, FsmState expected, FsmState next) {
        if (correlationId == null || expected == null || next == null) {
            return null;
        }
        final String key = KEY_SESSION + correlationId;
        // Copy-on-write CAS loop using the atomic ConcurrentMap.replace(key, old, new) contract.
        // VirtualSession uses identity equals, so the stored reference we just read is the exact
        // value replace() compares against; a concurrent writer swaps the reference and we retry.
        for (int spins = 0; spins < 64; spins++) {
            Object current = cache.get(key);
            if (!(current instanceof VirtualSession)) {
                return null;
            }
            VirtualSession old = (VirtualSession) current;
            if (old.isExpired(System.currentTimeMillis())) {
                cache.remove(key);
                return null;
            }
            if (old.getFsmState() != expected) {
                return null;
            }
            VirtualSession updated = new VirtualSession(old);
            if (!updated.transitionTo(next)) {
                return null;
            }
            if (cache.replace(key, old, updated)) {
                return updated;
            }
            // lost the race; another writer changed the entry — re-read and re-evaluate
        }
        return null;
    }

    @Override
    public boolean tryLock(String msisdn, String correlationId, long ttlMillis) {
        if (msisdn == null) {
            return true;
        }
        final String key = KEY_LOCK + msisdn;
        // Retry loop: Infinispan lazily evicts expired entries (on-access or via reaper).
        // If putIfAbsent returns non-null, the entry may still be alive, or may be an
        // expired zombie that the reaper hasn't purged yet. A subsequent get() triggers
        // access-time eviction — if it returns null the slot is free and we retry.
        for (int spins = 0; spins < 4; spins++) {
            Object prev = cache.putIfAbsent(key, correlationId);
            if (prev == null) {
                put(key, correlationId, ttlMillis);
                return true;
            }
            // Force Infinispan access-time expiry check on the existing entry.
            if (cache.get(key) == null) {
                continue; // entry was expired and purged — retry putIfAbsent
            }
            return false; // entry is still alive — lock held by another session
        }
        return false;
    }

    @Override
    public void unlock(String msisdn) {
        if (msisdn != null) {
            cache.remove(KEY_LOCK + msisdn);
        }
    }

    @Override
    public void markActive(String msisdn, String correlationId, long ttlMillis) {
        if (msisdn != null) {
            put(KEY_ACTIVE + msisdn, correlationId, ttlMillis);
        }
    }

    @Override
    public void clearActive(String msisdn) {
        if (msisdn != null) {
            cache.remove(KEY_ACTIVE + msisdn);
        }
    }

    @Override
    public String activeCorrelationId(String msisdn) {
        if (msisdn == null) {
            return null;
        }
        Object v = cache.get(KEY_ACTIVE + msisdn);
        return (v instanceof String) ? (String) v : null;
    }
}
