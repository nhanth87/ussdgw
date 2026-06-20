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
 * Identifies which network dialogue a CDR record belongs to within a bridged transaction.
 * Recorded in CDR alongside the shared {@code correlationId}.
 */
public enum BridgePhase {

    /** No bridge applied; a single ordinary CDR record. */
    NONE,
    /** The MO dialogue (S1) released early while waiting for the AS. */
    S1_RELEASED,
    /** The NI push dialogue (S2) delivering the late AS result. */
    S2_PUSH,
    /** A reconciled/merged view across S1 and S2. */
    MERGED;
}
