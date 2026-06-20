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

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Resolves the process-wide {@link VirtualSessionStore}. Prefers an Infinispan-backed store when
 * the WildFly cache container is reachable; otherwise falls back to {@link InMemoryVirtualSessionStore}.
 */
public final class VirtualSessionStoreFactory {

    private static final Logger LOGGER = Logger.getLogger(VirtualSessionStoreFactory.class.getName());

    /** JNDI container name configured by the Infinispan subsystem in standalone.xml. */
    public static final String DEFAULT_CONTAINER_JNDI = "java:jboss/infinispan/container/ussd";
    public static final String DEFAULT_CACHE_NAME = "ussd-virtual-sessions";

    private static volatile VirtualSessionStore instance;

    private VirtualSessionStoreFactory() {
    }

    public static VirtualSessionStore getStore() {
        VirtualSessionStore local = instance;
        if (local == null) {
            synchronized (VirtualSessionStoreFactory.class) {
                local = instance;
                if (local == null) {
                    local = create();
                    instance = local;
                }
            }
        }
        return local;
    }

    private static VirtualSessionStore create() {
        try {
            VirtualSessionStore ispn = new InfinispanVirtualSessionStore(DEFAULT_CONTAINER_JNDI, DEFAULT_CACHE_NAME);
            LOGGER.info("Using Infinispan-backed VirtualSessionStore");
            return ispn;
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING,
                    "Infinispan cache unavailable, falling back to in-memory VirtualSessionStore: " + t.getMessage());
            return new InMemoryVirtualSessionStore();
        }
    }

    /** Test/seam hook to inject a specific store implementation. */
    public static void setStore(VirtualSessionStore store) {
        instance = store;
    }
}
