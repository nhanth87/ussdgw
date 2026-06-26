/*
 * TeleStax, Open Source Cloud Communications
 */

package org.mobicents.ussd.grpc.ra.push;

import java.util.concurrent.atomic.AtomicBoolean;

import org.mobicents.ussd.grpc.GrpcEnvelopeCodec;
import org.mobicents.ussd.grpc.GrpcPushActivity;

import io.grpc.stub.StreamObserver;

/**
 * Activity for a single inbound gRPC push unary RPC. Completes the RPC asynchronously after
 * the SBB processes the NI push (mirrors HTTP servlet suspend/resume without blocking Netty).
 */
public final class GrpcPushActivityImpl implements GrpcPushActivity {

    private final String id;
    private final StreamObserver<byte[]> responseObserver;
    private final Runnable onEnd;
    private final AtomicBoolean completed = new AtomicBoolean();

    public GrpcPushActivityImpl(String id, StreamObserver<byte[]> responseObserver, Runnable onEnd) {
        this.id = id;
        this.responseObserver = responseObserver;
        this.onEnd = onEnd;
    }

    public String getId() {
        return id;
    }

    @Override
    public void ackSuccess(byte[] responseEnvelope) {
        if (!completed.compareAndSet(false, true)) {
            return;
        }
        try {
            responseObserver.onNext(responseEnvelope);
            responseObserver.onCompleted();
        } catch (Throwable t) {
            responseObserver.onError(t);
        }
    }

    @Override
    public void ackEmptyOk() {
        ackSuccess(GrpcEnvelopeCodec.encodeResponse(true, id, null, null, null));
    }

    @Override
    public void ackError(String message) {
        if (!completed.compareAndSet(false, true)) {
            return;
        }
        byte[] body = GrpcEnvelopeCodec.encodeResponse(false, id, null, null, message);
        try {
            responseObserver.onNext(body);
            responseObserver.onCompleted();
        } catch (Throwable t) {
            responseObserver.onError(t);
        }
    }

    @Override
    public void endActivity() {
        if (onEnd != null) {
            onEnd.run();
        }
    }
}
