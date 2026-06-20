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

/**
 * A queued attempt to deliver a Network-Initiated push, carrying the back-off schedule and the
 * current attempt index.
 */
public final class PushRetryTask implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String correlationId;
    private final String msisdn;
    private final String payload;
    private int attempt;
    private long nextAttemptAtMillis;

    public PushRetryTask(String correlationId, String msisdn, String payload, long firstDelayMillis) {
        this.correlationId = correlationId;
        this.msisdn = msisdn;
        this.payload = payload;
        this.attempt = 0;
        this.nextAttemptAtMillis = System.currentTimeMillis() + Math.max(0L, firstDelayMillis);
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getMsisdn() {
        return msisdn;
    }

    public String getPayload() {
        return payload;
    }

    public int getAttempt() {
        return attempt;
    }

    public long getNextAttemptAtMillis() {
        return nextAttemptAtMillis;
    }

    public boolean isDue(long nowMillis) {
        return nowMillis >= nextAttemptAtMillis;
    }

    /**
     * Schedule the next attempt.
     *
     * @param delayMillis delay from now
     */
    public void scheduleNext(long delayMillis) {
        this.attempt++;
        this.nextAttemptAtMillis = System.currentTimeMillis() + Math.max(0L, delayMillis);
    }

    @Override
    public String toString() {
        return "PushRetryTask[correlationId=" + correlationId + ", msisdn=" + msisdn
                + ", attempt=" + attempt + ", nextAt=" + nextAttemptAtMillis + "]";
    }
}
