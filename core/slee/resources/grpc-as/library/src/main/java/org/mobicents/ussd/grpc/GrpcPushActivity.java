/*
 * TeleStax, Open Source Cloud Communications
 */

package org.mobicents.ussd.grpc;

import javax.slee.resource.ActivityHandle;

/**
 * One inbound gRPC push unary call. Holds the async response observer for non-blocking
 * 10k+ TPS push ingress (no servlet-thread blocking).
 */
public interface GrpcPushActivity extends ActivityHandle {

    void ackSuccess(byte[] responseEnvelope);

    /** Empty OK ack for bridge duplicate / queued paths. */
    void ackEmptyOk();

    void ackError(String message);

    void endActivity();
}
