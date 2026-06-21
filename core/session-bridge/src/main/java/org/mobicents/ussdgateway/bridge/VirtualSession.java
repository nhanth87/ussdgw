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

package org.mobicents.ussdgateway.bridge;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Application-layer representation of a USSD business session, independent of any single
 * network (MAP) dialogue. Persisted in {@link VirtualSessionStore} and reconciled across the
 * MO dialogue (S1) and the NI push dialogue (S2) via {@link #correlationId}.
 */
public final class VirtualSession implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String virtualSessionId;
    private final String correlationId;
    private String msisdn;
    private String imsi;
    private String serviceCode;
    private String lastMenu;
    private String lastInput;
    private String pendingRequestId;
    private FsmState fsmState;
    private SessionPriority priority;
    private final List<Long> networkDialogIds;
    private int attempt;
    private int inputGeneration;
    private final long createdAtMillis;
    private long expireAtMillis;

    public VirtualSession(String virtualSessionId, String correlationId, String msisdn,
            long ttlMillis) {
        this.virtualSessionId = virtualSessionId;
        this.correlationId = correlationId;
        this.msisdn = msisdn;
        this.fsmState = FsmState.CREATED;
        this.priority = SessionPriority.ACTIVE_PULL;
        this.networkDialogIds = new ArrayList<Long>(2);
        this.attempt = 0;
        this.inputGeneration = 0;
        this.createdAtMillis = System.currentTimeMillis();
        this.expireAtMillis = this.createdAtMillis + ttlMillis;
    }

    /**
     * Deep-ish copy used by stores that perform copy-on-write compare-and-swap (e.g. Infinispan):
     * a fresh instance is produced for the new state so the previously stored reference stays
     * immutable for the CAS comparison.
     */
    public VirtualSession(VirtualSession other) {
        this.virtualSessionId = other.virtualSessionId;
        this.correlationId = other.correlationId;
        this.msisdn = other.msisdn;
        this.imsi = other.imsi;
        this.serviceCode = other.serviceCode;
        this.lastMenu = other.lastMenu;
        this.lastInput = other.lastInput;
        this.pendingRequestId = other.pendingRequestId;
        this.fsmState = other.fsmState;
        this.priority = other.priority;
        this.networkDialogIds = new ArrayList<Long>(other.networkDialogIds);
        this.attempt = other.attempt;
        this.inputGeneration = other.inputGeneration;
        this.createdAtMillis = other.createdAtMillis;
        this.expireAtMillis = other.expireAtMillis;
    }

    public String getVirtualSessionId() {
        return virtualSessionId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getMsisdn() {
        return msisdn;
    }

    public void setMsisdn(String msisdn) {
        this.msisdn = msisdn;
    }

    public String getImsi() {
        return imsi;
    }

    public void setImsi(String imsi) {
        this.imsi = imsi;
    }

    public String getServiceCode() {
        return serviceCode;
    }

    public void setServiceCode(String serviceCode) {
        this.serviceCode = serviceCode;
    }

    public String getLastMenu() {
        return lastMenu;
    }

    public void setLastMenu(String lastMenu) {
        this.lastMenu = lastMenu;
    }

    public String getLastInput() {
        return lastInput;
    }

    public void setLastInput(String lastInput) {
        this.lastInput = lastInput;
    }

    public String getPendingRequestId() {
        return pendingRequestId;
    }

    public void setPendingRequestId(String pendingRequestId) {
        this.pendingRequestId = pendingRequestId;
    }

    public FsmState getFsmState() {
        return fsmState;
    }

    public SessionPriority getPriority() {
        return priority;
    }

    public void setPriority(SessionPriority priority) {
        if (priority != null) {
            this.priority = priority;
        }
    }

    public List<Long> getNetworkDialogIds() {
        return networkDialogIds;
    }

    public void addNetworkDialogId(Long dialogId) {
        if (dialogId != null && !networkDialogIds.contains(dialogId)) {
            networkDialogIds.add(dialogId);
        }
    }

    public int getAttempt() {
        return attempt;
    }

    public int incrementAttempt() {
        return ++attempt;
    }

    /**
     * Monotonic counter of user inputs in this business session. Each new MO input bumps it and
     * stamps the {@code requestId}; a late response carrying an older generation is stale and must
     * be dropped to preserve menu ordering (RFC §13.3).
     */
    public int getInputGeneration() {
        return inputGeneration;
    }

    public int incrementInputGeneration() {
        return ++inputGeneration;
    }

    public void setInputGeneration(int inputGeneration) {
        this.inputGeneration = inputGeneration;
    }

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    public long getExpireAtMillis() {
        return expireAtMillis;
    }

    public void setExpireAtMillis(long expireAtMillis) {
        this.expireAtMillis = expireAtMillis;
    }

    public boolean isExpired(long nowMillis) {
        return nowMillis >= expireAtMillis;
    }

    /**
     * Attempts an FSM transition.
     *
     * @return {@code true} if the transition was legal and applied.
     */
    public boolean transitionTo(FsmState next) {
        if (fsmState.canTransitionTo(next)) {
            this.fsmState = next;
            return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return "VirtualSession[vsid=" + virtualSessionId + ", correlationId=" + correlationId
                + ", msisdn=" + msisdn + ", state=" + fsmState + ", priority=" + priority
                + ", pendingRequestId=" + pendingRequestId + ", attempt=" + attempt
                + ", inputGen=" + inputGeneration + "]";
    }
}
