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
 * SPI invoked when a Network-Initiated push cannot be delivered after the configured retries.
 * Phase 1 ships {@link LoggingNotificationFallback}; an SMSC-backed implementation can be
 * registered later to deliver the result over SMS.
 */
public interface NotificationFallback {

    /**
     * @param msisdn        the target subscriber
     * @param correlationId the bridged transaction id
     * @param payload       the USSD result that could not be pushed
     * @param reason        the last failure reason
     */
    void onPushUndeliverable(String msisdn, String correlationId, String payload, String reason);
}
