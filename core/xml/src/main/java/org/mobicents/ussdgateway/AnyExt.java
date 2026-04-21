/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2017, Telestax Inc and individual contributors
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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * REPLACED: javolution.xml with Jackson XML annotations
 * 100% API Compatible with original
 */
package org.mobicents.ussdgateway;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.restcomm.protocols.ss7.map.api.MAPMessageType;

/**
 * @author Amit Bhayani
 * @author Matrix Agent
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnyExt {

    public static final String MESSAGE_TYPE = "message-type";

    @JsonProperty(MESSAGE_TYPE)
    private MessageType messageType;

    /**
     *
     */
    public AnyExt() {
    }

    public AnyExt(MAPMessageType mapMessageType) {
        this.messageType = new MessageType(mapMessageType);
    }

    public MAPMessageType getMapMessageType() {
        if (this.messageType != null) {
            return messageType.getType();
        }
        return null;
    }

    public void setMapMessageType(MAPMessageType mapMessageType) {
        if (this.messageType == null) {
            this.messageType = new MessageType();
        }
        this.messageType.setType(mapMessageType);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((messageType == null) ? 0 : messageType.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        AnyExt other = (AnyExt) obj;
        if (messageType == null) {
            if (other.messageType != null)
                return false;
        } else if (!messageType.equals(other.messageType))
            return false;
        return true;
    }
}
