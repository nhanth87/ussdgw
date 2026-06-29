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

package org.mobicents.ussdgateway;

import java.util.Map;

import org.mobicents.ussdgateway.UssdPropertiesManagement.CdrLoggedType;

/**
 * @author amit bhayani
 * @author baranowb
 */
public interface UssdPropertiesManagementMBean {

	public String getNoRoutingRuleConfiguredMessage();

	public void setNoRoutingRuleConfiguredMessage(String noRoutingRuleConfiguredMessage);

    public String getServerOverloadedMessage();

    public void setServerOverloadedMessage(String serverOverloadedMessage);

	public String getServerErrorMessage();

	public void setServerErrorMessage(String serverErrorMessage);

	public String getDialogTimeoutErrorMessage();

	public void setDialogTimeoutErrorMessage(String dialogTimeoutErrorMessage);

	public long getDialogTimeout();

	public void setDialogTimeout(long dialogTimeout);
	
	public String getUssdGt();

    public void setUssdGt(String serviceCenterGt);
    
    public String getUssdGt(int networkId);
    
    public void setUssdGt(int networkId, String serviceCenterGt);
    
    public Map<Integer, String> getNetworkIdVsUssdGwGt();

    public int getUssdSsn();

    public void setUssdSsn(int serviceCenterSsn);

    public int getHlrSsn();
    
    public void setHlrSsn(int ssn);

    public int getMaxMapVersion();

    public void setMaxMapVersion(int maxMapVersion);
    
    public int getMscSsn();

    public void setMscSsn(int mscSsn);

    public CdrLoggedType getCdrLoggingTo();

    public void setCdrLoggingTo(CdrLoggedType cdrLogging);

    public String getCdrLoggingToStr();

    public void setCdrLoggingToStr(String cdrLogging);

    public String getCdrSeparator();

    public void setCdrSeparator(String cdrSeparator);

    public void setHrHlrGt(String hrHlrGt);

    public String getHrHlrGt();

    public int getMaxActivityCount();

    public void setMaxActivityCount(int maxActivityCount);

    // ----- Virtual Session Bridge -----

    public boolean isSessionBridgeEnabled();

    public void setSessionBridgeEnabled(boolean sessionBridgeEnabled);

    public long getAsyncGateTimeoutMs();

    public void setAsyncGateTimeoutMs(long asyncGateTimeoutMs);

    public String getAsyncWaitUserMessage();

    public void setAsyncWaitUserMessage(String asyncWaitUserMessage);

    public String getAsyncHardFailMessage();

    public void setAsyncHardFailMessage(String asyncHardFailMessage);

    public int getBridgeStateTtlSec();

    public void setBridgeStateTtlSec(int bridgeStateTtlSec);

    public String getPushRetryDelaysMs();

    public void setPushRetryDelaysMs(String pushRetryDelaysMs);

    // ----- Per-protocol bridge enable/disable -----

    public boolean isHttpClientBridgeEnabled();

    public void setHttpClientBridgeEnabled(boolean httpClientBridgeEnabled);

    public boolean isGrpcClientBridgeEnabled();

    public void setGrpcClientBridgeEnabled(boolean grpcClientBridgeEnabled);

    public boolean isHttpServerBridgeEnabled();

    public void setHttpServerBridgeEnabled(boolean httpServerBridgeEnabled);

    public boolean isGrpcServerBridgeEnabled();

    public void setGrpcServerBridgeEnabled(boolean grpcServerBridgeEnabled);

    // ----- Channel-A sync reconcile kill-switch -----

    public boolean isBridgeSyncReconcileEnabled();

    public void setBridgeSyncReconcileEnabled(boolean bridgeSyncReconcileEnabled);

    // ----- gRPC push ingress server (gateway as gRPC server) -----

    public boolean isGrpcPushServerEnabled();

    public void setGrpcPushServerEnabled(boolean grpcPushServerEnabled);

    public int getGrpcPushServerPort();

    public void setGrpcPushServerPort(int grpcPushServerPort);

    public int getGrpcPushWorkerThreads();

    public void setGrpcPushWorkerThreads(int grpcPushWorkerThreads);

    public int getGrpcPushMaxConcurrentCalls();

    public void setGrpcPushMaxConcurrentCalls(int grpcPushMaxConcurrentCalls);

    // ----- GRPC-2 / GRPC-3: TLS configuration -----

    public boolean isGrpcUseSsl();

    public void setGrpcUseSsl(boolean grpcUseSsl);

    public String getGrpcSslTrustStore();

    public void setGrpcSslTrustStore(String grpcSslTrustStore);

    public String getGrpcPushSslCertChain();

    public void setGrpcPushSslCertChain(String grpcPushSslCertChain);

    public String getGrpcPushSslPrivateKey();

    public void setGrpcPushSslPrivateKey(String grpcPushSslPrivateKey);

    // ----- HYBRID session tracking (SESS-1) -----

    /**
     * Returns the currently configured HYBRID session tracking strategy:
     * {@code HEADER_FIRST} (default), {@code COOKIE_FIRST} or
     * {@code LOCALID_ONLY}.
     */
    public String getHttpSessionStrategy();

    /**
     * Sets the HYBRID session tracking strategy. Unknown values fall
     * back to {@code HEADER_FIRST}.
     */
    public void setHttpSessionStrategy(String httpSessionStrategy);

}
