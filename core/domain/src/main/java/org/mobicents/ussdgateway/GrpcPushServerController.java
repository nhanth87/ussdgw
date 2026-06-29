/*
 * TeleStax, Open Source Cloud Communications
 */

package org.mobicents.ussdgateway;

/**
 * Hot-reload hook for the gRPC push ingress server (implemented by {@code GrpcAsResourceAdaptor}).
 * <p>
 * The {@code sslCertChain} and {@code sslPrivateKey} parameters are PEM file paths used to enable
 * server-side TLS on the gRPC push server (GRPC-3). Pass {@code null} for both to keep plaintext.
 */
public interface GrpcPushServerController {

    void applyGrpcPushConfig(boolean enabled, int port, int workerThreads, int maxConcurrentCalls,
            String sslCertChain, String sslPrivateKey);
}
