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

/**
 * Maps a service code to a {@link SessionPriority}. Phase 1 uses a simple keyword heuristic;
 * later phases can replace this with a configurable per-service table.
 */
public final class SessionPriorityResolver {

    private SessionPriorityResolver() {
    }

    public static SessionPriority forServiceCode(String serviceCode) {
        if (serviceCode == null) {
            return SessionPriority.PUSH_DEFAULT;
        }
        String code = serviceCode.toLowerCase();
        if (code.contains("pay") || code.contains("bank") || code.contains("otp")
                || code.contains("transfer")) {
            return SessionPriority.PUSH_PAYMENT;
        }
        return SessionPriority.PUSH_DEFAULT;
    }

    /**
     * Decide whether a push for {@code pushPriority} should be delivered immediately while an
     * MO session of {@code activePriority} is in progress.
     *
     * @return {@code true} to deliver now (preempt), {@code false} to queue-back.
     */
    public static boolean shouldDeliverNow(SessionPriority pushPriority, SessionPriority activePriority) {
        if (activePriority == null) {
            return true;
        }
        return pushPriority != null && pushPriority.preempts(activePriority);
    }
}
