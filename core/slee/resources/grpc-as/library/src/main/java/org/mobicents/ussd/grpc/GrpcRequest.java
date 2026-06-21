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

import java.io.Serializable;

/**
 * Request sent from the USSD Gateway (gRPC client) to the Application Server (gRPC server).
 * <p>
 * Mirrors the HTTP client RA: {@link #payload} carries the serialized {@code XmlMAPDialog}, while
 * {@link #sessionId} provides the per-session identifier the AS uses to keep menu state. When the
 * Virtual Session Bridge is active the session id equals the bridge correlation id so the AS sees
 * a single stable identifier across MO (pull) and NI (push) interactions.
 */
public final class GrpcRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String target;
    private final String sessionId;
    private final String correlationId;
    private final String requestId;
    private final boolean push;
    private final int networkId;
    private final byte[] payload;

    public GrpcRequest(String target, String sessionId, String correlationId, boolean push,
            int networkId, byte[] payload) {
        this(target, sessionId, correlationId, null, push, networkId, payload);
    }

    public GrpcRequest(String target, String sessionId, String correlationId, String requestId,
            boolean push, int networkId, byte[] payload) {
        this.target = target;
        this.sessionId = sessionId;
        this.correlationId = correlationId;
        this.requestId = requestId;
        this.push = push;
        this.networkId = networkId;
        this.payload = payload;
    }

    /** gRPC endpoint of the AS, e.g. {@code host:port} or {@code dns:///host:port}. */
    public String getTarget() {
        return target;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    /** The per-request id the AS must echo on a late response so the bridge can reconcile it. */
    public String getRequestId() {
        return requestId;
    }

    public boolean isPush() {
        return push;
    }

    public int getNetworkId() {
        return networkId;
    }

    public byte[] getPayload() {
        return payload;
    }
}
