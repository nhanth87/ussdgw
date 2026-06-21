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
 * Performs the actual Network-Initiated (S2) push of a reconciled late AS response. Supplied by the
 * SLEE layer (which owns the MAP provider and CDR), so the dependency-light bridge core can drive
 * delivery without depending on jSS7. Shared by {@link BridgeReconciler} and the
 * {@link PushRetryQueue} so there is a single push code path (RFC §7.6).
 */
public interface NiPushDispatcher {

    /**
     * Deliver the menu payload carried by the reconciled session as an NI push.
     *
     * @param session the reconciled {@link VirtualSession} (state {@code PUSH_PENDING}).
     * @param payload the serialized {@code XmlMAPDialog} menu bytes from the AS late response.
     * @return {@code true} if accepted by the network; {@code false} to schedule a retry.
     */
    boolean deliverNiPush(VirtualSession session, byte[] payload);
}
