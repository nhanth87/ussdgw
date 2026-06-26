/*
 * TeleStax, Open Source Cloud Communications
 */

package org.mobicents.ussdgateway;

/**
 * Hot-reload hook for the gRPC push ingress server (implemented by {@code GrpcAsResourceAdaptor}).
 */
public interface GrpcPushServerController {

    void applyGrpcPushConfig(boolean enabled, int port, int workerThreads, int maxConcurrentCalls);
}
