/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2017, Telestax Inc and individual contributors
 * by the @authors tag.
 */

package org.mobicents.ussd.grpc.events;

import java.io.Serializable;

import org.mobicents.ussd.grpc.GrpcPushActivity;

/**
 * Fired when an Application Server invokes the gateway gRPC push server
 * ({@code ussd.UssdApplicationService/Process} with {@code push=true}).
 */
public final class GrpcPushReceivedEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    private final GrpcPushActivity activity;
    private final String sessionId;
    private final String correlationId;
    private final String requestId;
    private final int inputGeneration;
    private final int networkId;
    private final byte[] payload;

    public GrpcPushReceivedEvent(GrpcPushActivity activity, String sessionId, String correlationId,
            String requestId, int inputGeneration, int networkId, byte[] payload) {
        this.activity = activity;
        this.sessionId = sessionId;
        this.correlationId = correlationId;
        this.requestId = requestId;
        this.inputGeneration = inputGeneration;
        this.networkId = networkId;
        this.payload = payload;
    }

    public GrpcPushActivity getActivity() {
        return activity;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getRequestId() {
        return requestId;
    }

    public int getInputGeneration() {
        return inputGeneration;
    }

    public int getNetworkId() {
        return networkId;
    }

    public byte[] getPayload() {
        return payload;
    }
}
