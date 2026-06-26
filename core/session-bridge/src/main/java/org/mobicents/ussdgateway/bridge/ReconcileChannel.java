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
 * The entry point through which a late AS response reached the gateway (RFC §3.1). Channel A is the
 * original synchronous transport still being alive after the gate fired; Channel B is the dedicated
 * USSD push interface used when the synchronous transport is already gone.
 */
public enum ReconcileChannel {

    /** Late HTTP 200 on the original MO request connection. */
    SYNC_HTTP,
    /** Late gRPC unary response collected after the gate. */
    SYNC_GRPC,
    /** Late SIP INFO on the original MO dialogue. */
    SYNC_SIP,
    /** AS posted to the existing USSD push servlet carrying the request id. */
    PUSH_HTTP,
    /** AS invoked the gateway gRPC push server carrying the request id. */
    PUSH_GRPC,
    /** AS sent a SIP INVITE push carrying the request id. */
    PUSH_SIP;

    public boolean isSync() {
        return this == SYNC_HTTP || this == SYNC_GRPC || this == SYNC_SIP;
    }

    public boolean isPush() {
        return this == PUSH_HTTP || this == PUSH_GRPC || this == PUSH_SIP;
    }
}
