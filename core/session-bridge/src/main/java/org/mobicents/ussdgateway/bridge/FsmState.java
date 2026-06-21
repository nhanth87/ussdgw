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
 * Lifecycle states of a {@link VirtualSession}.
 *
 * <pre>
 * CREATED -> WAIT_USER -> WAIT_AS -> COMPLETED
 *                            |-> BRIDGED -> PUSH_PENDING -> COMPLETED | FAILED
 *                            |-> EXPIRED
 *                            |-> ABORTED   (network/MSC tore down the dialogue)
 * </pre>
 *
 * {@code ABORTED} is terminal and distinct from {@code EXPIRED}: {@code EXPIRED} means our TTL
 * elapsed with no AS response, whereas {@code ABORTED} means the <em>network</em> ended the
 * transaction (MAP/TCAP/Provider/User Abort). A late AS response landing on an {@code ABORTED}
 * session must be dropped — pushing it would reopen a dialogue the MSC already considers closed
 * (RFC §13.2).
 */
public enum FsmState {

    CREATED,
    WAIT_USER,
    WAIT_AS,
    BRIDGED,
    PUSH_PENDING,
    COMPLETED,
    FAILED,
    EXPIRED,
    ABORTED;

    /**
     * @return {@code true} if no further transitions are allowed from this state.
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == EXPIRED || this == ABORTED;
    }

    /**
     * Validates an FSM transition. Terminal states accept no further transitions.
     *
     * @return {@code true} if {@code this -> next} is a legal transition.
     */
    public boolean canTransitionTo(FsmState next) {
        if (next == null || isTerminal()) {
            return false;
        }
        switch (this) {
            case CREATED:
                return next == WAIT_USER || next == WAIT_AS || next == EXPIRED || next == FAILED
                        || next == ABORTED;
            case WAIT_USER:
                return next == WAIT_AS || next == EXPIRED || next == FAILED || next == COMPLETED
                        || next == ABORTED;
            case WAIT_AS:
                return next == COMPLETED || next == BRIDGED || next == EXPIRED || next == FAILED
                        || next == ABORTED;
            case BRIDGED:
                return next == PUSH_PENDING || next == EXPIRED || next == FAILED || next == ABORTED;
            case PUSH_PENDING:
                return next == COMPLETED || next == FAILED || next == EXPIRED || next == ABORTED;
            default:
                return false;
        }
    }
}
