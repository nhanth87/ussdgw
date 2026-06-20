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

/**
 * Performs the actual Network-Initiated push. Supplied by the SLEE layer (which owns the MAP
 * provider) so the dependency-light {@link PushRetryQueue} can drive retries without depending
 * on jSS7.
 */
public interface PushExecutor {

    /**
     * Attempt to deliver a push.
     *
     * @return {@code true} if the push was accepted by the network; {@code false} to schedule a retry.
     */
    boolean deliver(PushRetryTask task);
}
