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

import javax.slee.resource.ResourceAdaptorTypeID;

/**
 * SBB-facing interface for the USSD gRPC AS Resource Adaptor.
 * <p>
 * The call is non-blocking: {@link #submit(GrpcRequest)} returns immediately and the worker
 * performs the gRPC unary call. The result is deposited in {@link GrpcResponseRegistry} keyed by
 * the request correlation id; the SBB collects it via a short SLEE poll timer. This mirrors the
 * non-blocking spirit of the HTTP client RA while using only the verified, portable SLEE SPI.
 */
public interface GrpcAsResourceAdaptorSbbInterface {

    ResourceAdaptorTypeID RATYPE_ID = new ResourceAdaptorTypeID(
            "GrpcAsResourceAdaptorType", "org.mobicents.ussd", "1.0");

    /**
     * Submit a gRPC request to the AS. Non-blocking. The {@link GrpcResponse} will be available
     * from {@link GrpcResponseRegistry#poll(String)} using {@link GrpcRequest#getCorrelationId()}.
     */
    void submit(GrpcRequest request);
}
