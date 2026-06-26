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

package org.mobicents.ussd.grpc.ra;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import javax.slee.Address;
import javax.slee.EventTypeID;
import javax.slee.facilities.EventLookupFacility;
import javax.slee.facilities.Tracer;
import javax.slee.resource.ActivityHandle;
import javax.slee.resource.ConfigProperties;
import javax.slee.resource.FailureReason;
import javax.slee.resource.FireableEventType;
import javax.slee.resource.InvalidConfigurationException;
import javax.slee.resource.Marshaler;
import javax.slee.resource.ReceivableService;
import javax.slee.resource.ResourceAdaptor;
import javax.slee.resource.ResourceAdaptorContext;
import javax.slee.resource.SleeEndpoint;

import org.mobicents.ussd.grpc.GrpcEnvelopeCodec;
import org.mobicents.ussd.grpc.GrpcRequest;
import org.mobicents.ussd.grpc.GrpcResponse;
import org.mobicents.ussd.grpc.GrpcResponseRegistry;
import org.mobicents.ussd.grpc.ra.push.GrpcPushActivityImpl;
import org.mobicents.ussd.grpc.ra.push.GrpcPushServer;
import org.mobicents.ussdgateway.GrpcPushServerController;
import org.mobicents.ussdgateway.UssdPropertiesManagement;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.StreamObserver;

/**
 * JAIN SLEE Resource Adaptor: the USSD Gateway as a gRPC client toward the Application Server.
 * <p>
 * Non-blocking, like the CDR Local RA: {@code submit()} returns immediately and the gRPC unary call
 * runs on the gRPC executor. The reply is deposited in {@link GrpcResponseRegistry} keyed by
 * correlation id, where the SBB collects it via a short SLEE poll timer.
 */
public class GrpcAsResourceAdaptor implements ResourceAdaptor, GrpcPushServerController {

    private static final String GRPC_DEADLINE_MS_PROPERTY = "GRPC_DEADLINE_MS";

    public static final MethodDescriptor<byte[], byte[]> PROCESS_METHOD = MethodDescriptor.<byte[], byte[]>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName(MethodDescriptor.generateFullMethodName("ussd.UssdApplicationService", "Process"))
            .setRequestMarshaller(ByteArrayMarshaller.INSTANCE)
            .setResponseMarshaller(ByteArrayMarshaller.INSTANCE)
            .build();

    private ResourceAdaptorContext context;
    private Tracer tracer;
    private SleeEndpoint sleeEndpoint;
    private FireableEventType pushEventType;
    private long deadlineMs = 30000;

    private final GrpcAsResourceAdaptorSbbInterfaceImpl sbbInterface = new GrpcAsResourceAdaptorSbbInterfaceImpl(this);
    private final GrpcResponseRegistry registry = GrpcResponseRegistry.getInstance();
    private final ConcurrentHashMap<String, ManagedChannel> channels = new ConcurrentHashMap<String, ManagedChannel>();
    private final ConcurrentHashMap<String, GrpcPushActivityImpl> pushActivities = new ConcurrentHashMap<String, GrpcPushActivityImpl>();

    private volatile GrpcPushServer pushServer;

    /**
     * Non-blocking gRPC unary call. Result is published to {@link GrpcResponseRegistry}.
     */
    void submit(final GrpcRequest request) {
        if (request == null) {
            return;
        }
        final String correlationId = request.getCorrelationId();
        final ManagedChannel channel;
        try {
            channel = channel(request.getTarget());
        } catch (Throwable t) {
            tracer.severe("Could not obtain gRPC channel for " + request.getTarget(), t);
            registry.put(GrpcResponse.error(correlationId, "channel-error: " + t.getMessage()));
            return;
        }

        ClientCall<byte[], byte[]> call = channel.newCall(PROCESS_METHOD,
                CallOptions.DEFAULT.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS));
        byte[] body = GrpcEnvelopeCodec.encodeRequest(request);

        ClientCalls.asyncUnaryCall(call, body, new StreamObserver<byte[]>() {
            private byte[] last;

            @Override
            public void onNext(byte[] value) {
                this.last = value;
            }

            @Override
            public void onError(Throwable t) {
                if (tracer != null) {
                    tracer.warning("gRPC call failed for correlationId=" + correlationId, t);
                }
                registry.put(GrpcResponse.error(correlationId,
                        t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
            }

            @Override
            public void onCompleted() {
                Map<String, String> env = GrpcEnvelopeCodec.decode(last);
                byte[] payload = GrpcEnvelopeCodec.decodePayload(env.get(GrpcEnvelopeCodec.F_PAYLOAD));
                String echoedRequestId = env.get(GrpcEnvelopeCodec.F_REQUEST_ID);
                registry.put(GrpcResponse.ok(correlationId, echoedRequestId, payload));
            }
        });
    }

    private ManagedChannel channel(String target) {
        ManagedChannel existing = channels.get(target);
        if (existing != null && !existing.isShutdown()) {
            return existing;
        }
        synchronized (channels) {
            existing = channels.get(target);
            if (existing != null && !existing.isShutdown()) {
                return existing;
            }
            ManagedChannel created = ManagedChannelBuilder.forTarget(target).usePlaintext().build();
            channels.put(target, created);
            return created;
        }
    }

    public void registerPushActivity(GrpcPushActivityImpl activity) {
        if (activity != null && activity.getId() != null) {
            pushActivities.put(activity.getId(), activity);
        }
    }

    void unregisterPushActivity(GrpcPushActivityImpl activity) {
        if (activity != null && activity.getId() != null) {
            pushActivities.remove(activity.getId());
        }
    }

    @Override
    public void applyGrpcPushConfig(boolean enabled, int port, int workerThreads, int maxConcurrentCalls) {
        if (context == null || sleeEndpoint == null || pushEventType == null) {
            return;
        }
        try {
            if (enabled) {
                if (pushServer == null) {
                    pushServer = new GrpcPushServer(this, tracer, sleeEndpoint, pushEventType, workerThreads);
                }
                pushServer.start(port, maxConcurrentCalls);
            } else {
                stopPushServer();
            }
        } catch (Throwable t) {
            if (tracer != null) {
                tracer.severe("Failed to apply gRPC push server config", t);
            }
        }
    }

    private void stopPushServer() {
        if (pushServer != null) {
            pushServer.stop();
        }
    }

    private void startPushServerFromProperties() {
        UssdPropertiesManagement props = UssdPropertiesManagement.getInstance();
        if (props == null) {
            return;
        }
        applyGrpcPushConfig(props.isGrpcPushServerEnabled(), props.getGrpcPushServerPort(),
                props.getGrpcPushWorkerThreads(), props.getGrpcPushMaxConcurrentCalls());
    }

    // -------------------------------------------------------------
    // ResourceAdaptor SPI
    // -------------------------------------------------------------

    @Override
    public Object getResourceAdaptorInterface(String className) {
        return sbbInterface;
    }

    @Override
    public Marshaler getMarshaler() {
        return null;
    }

    @Override
    public void setResourceAdaptorContext(ResourceAdaptorContext context) {
        this.context = context;
        this.tracer = context.getTracer(GrpcAsResourceAdaptor.class.getSimpleName());
        this.sleeEndpoint = context.getSleeEndpoint();
        try {
            EventLookupFacility lookup = context.getEventLookupFacility();
            this.pushEventType = lookup.getFireableEventType(new EventTypeID(GrpcPushServer.EVENT_TYPE_NAME,
                    GrpcPushServer.EVENT_VENDOR, GrpcPushServer.EVENT_VERSION));
        } catch (Throwable t) {
            throw new RuntimeException("Could not resolve gRPC push event type", t);
        }
    }

    @Override
    public void unsetResourceAdaptorContext() {
        this.context = null;
        this.tracer = null;
        this.sleeEndpoint = null;
        this.pushEventType = null;
    }

    @Override
    public void raConfigure(ConfigProperties properties) {
        Object v = value(properties, GRPC_DEADLINE_MS_PROPERTY);
        if (v instanceof Number) {
            this.deadlineMs = ((Number) v).longValue();
        }
    }

    @Override
    public void raActive() {
        tracer.info("gRPC AS RA active, deadlineMs=" + deadlineMs);
        UssdPropertiesManagement.setGrpcPushServerController(this);
        startPushServerFromProperties();
    }

    @Override
    public void raInactive() {
        UssdPropertiesManagement.setGrpcPushServerController(null);
        stopPushServer();
        pushServer = null;
        pushActivities.clear();
        for (ManagedChannel ch : channels.values()) {
            try {
                ch.shutdownNow();
            } catch (Throwable ignore) {
                // best effort
            }
        }
        channels.clear();
    }

    @Override
    public void raStopping() {
    }

    @Override
    public void raConfigurationUpdate(ConfigProperties properties) {
        raConfigure(properties);
    }

    @Override
    public void raUnconfigure() {
        deadlineMs = 30000;
    }

    @Override
    public void raVerifyConfiguration(ConfigProperties properties) throws InvalidConfigurationException {
        Object v = value(properties, GRPC_DEADLINE_MS_PROPERTY);
        if (v instanceof Number && ((Number) v).longValue() < 100) {
            throw new InvalidConfigurationException("GRPC_DEADLINE_MS must be >= 100");
        }
    }

    private static Object value(ConfigProperties properties, String name) {
        if (properties == null) {
            return null;
        }
        ConfigProperties.Property p = properties.getProperty(name);
        return p == null ? null : p.getValue();
    }

    @Override
    public Object getActivity(ActivityHandle handle) {
        if (handle instanceof GrpcPushActivityImpl) {
            return handle;
        }
        if (handle != null) {
            return pushActivities.get(handle.toString());
        }
        return null;
    }

    @Override
    public ActivityHandle getActivityHandle(Object activityObject) {
        if (activityObject instanceof GrpcPushActivityImpl) {
            return (ActivityHandle) activityObject;
        }
        return null;
    }

    @Override
    public void administrativeRemove(ActivityHandle handle) {
        if (handle instanceof GrpcPushActivityImpl) {
            unregisterPushActivity((GrpcPushActivityImpl) handle);
        }
    }

    @Override
    public void activityEnded(ActivityHandle handle) {
        if (handle instanceof GrpcPushActivityImpl) {
            unregisterPushActivity((GrpcPushActivityImpl) handle);
        }
    }

    @Override
    public void activityUnreferenced(ActivityHandle handle) {
    }

    @Override
    public void eventProcessingFailed(ActivityHandle handle, FireableEventType eventType, Object event,
            Address address, ReceivableService service, int flags, FailureReason reason) {
    }

    @Override
    public void eventProcessingSuccessful(ActivityHandle handle, FireableEventType eventType, Object event,
            Address address, ReceivableService service, int flags) {
    }

    @Override
    public void eventUnreferenced(ActivityHandle handle, FireableEventType eventType, Object event,
            Address address, ReceivableService service, int flags) {
    }

    @Override
    public void queryLiveness(ActivityHandle handle) {
    }

    @Override
    public void serviceActive(ReceivableService service) {
    }

    @Override
    public void serviceInactive(ReceivableService service) {
    }

    @Override
    public void serviceStopping(ReceivableService service) {
    }
}
