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

import java.util.logging.Logger;

/**
 * Default {@link NotificationFallback}: records the undeliverable push and bumps the fallback
 * metric. A real SMSC integration replaces this in a later phase.
 */
public final class LoggingNotificationFallback implements NotificationFallback {

    private static final Logger LOGGER = Logger.getLogger(LoggingNotificationFallback.class.getName());

    @Override
    public void onPushUndeliverable(String msisdn, String correlationId, String payload, String reason) {
        BridgeMetrics.getInstance().incFallbackNotifications();
        LOGGER.warning("CDR-FALLBACK correlationId=" + correlationId + " msisdn=" + msisdn
                + " reason=" + reason + " (SMS fallback not configured) payload="
                + (payload == null ? "" : payload));
    }
}
