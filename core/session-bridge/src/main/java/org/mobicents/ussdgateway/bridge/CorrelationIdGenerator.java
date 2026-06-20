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

import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates short, monotonically-increasing, node-unique correlation and request ids.
 * <p>
 * Format: {@code <nodePrefix>-<base36 epochMillis>-<base36 counter>}. Compact enough to ride
 * inside an HTTP header and a CDR line while staying collision-free within a node.
 */
public final class CorrelationIdGenerator {

    private static final AtomicLong COUNTER = new AtomicLong();

    private static final String NODE_PREFIX = resolveNodePrefix();

    private CorrelationIdGenerator() {
    }

    public static String newCorrelationId() {
        return NODE_PREFIX + "-" + Long.toString(System.currentTimeMillis(), 36) + "-"
                + Long.toString(COUNTER.incrementAndGet(), 36);
    }

    public static String newRequestId() {
        return "r" + newCorrelationId();
    }

    private static String resolveNodePrefix() {
        String node = System.getProperty("jboss.node.name");
        if (node == null || node.trim().isEmpty()) {
            node = System.getProperty("jboss.server.name");
        }
        if (node == null || node.trim().isEmpty()) {
            node = "gw";
        }
        // keep it short and id-safe
        String trimmed = node.trim().replaceAll("[^a-zA-Z0-9]", "");
        if (trimmed.length() > 6) {
            trimmed = trimmed.substring(0, 6);
        }
        return trimmed.isEmpty() ? "gw" : trimmed;
    }
}
