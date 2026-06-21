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
 * Response returned by the Application Server to the gateway over gRPC. Delivered to the SBB as a
 * {@code GrpcResponseEvent}.
 */
public final class GrpcResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private final boolean success;
    private final String correlationId;
    private final String requestId;
    private final byte[] payload;
    private final String errorMessage;

    private GrpcResponse(boolean success, String correlationId, String requestId, byte[] payload,
            String errorMessage) {
        this.success = success;
        this.correlationId = correlationId;
        this.requestId = requestId;
        this.payload = payload;
        this.errorMessage = errorMessage;
    }

    public static GrpcResponse ok(String correlationId, byte[] payload) {
        return new GrpcResponse(true, correlationId, null, payload, null);
    }

    public static GrpcResponse ok(String correlationId, String requestId, byte[] payload) {
        return new GrpcResponse(true, correlationId, requestId, payload, null);
    }

    public static GrpcResponse error(String correlationId, String errorMessage) {
        return new GrpcResponse(false, correlationId, null, null, errorMessage);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    /** The request id echoed by the AS (Channel A sync reconcile key); may be {@code null}. */
    public String getRequestId() {
        return requestId;
    }

    public byte[] getPayload() {
        return payload;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
