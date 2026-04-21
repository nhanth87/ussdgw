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
 * REPLACED: javolution.text.CharArray with String and javolution.xml with Jackson
 * 100% API Compatible with original
 */
package org.mobicents.ussdgateway;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;

import org.restcomm.protocols.ss7.map.api.MAPMessageType;

/**
 * @author Amit Bhayani
 * @author Matrix Agent
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class MessageType {

    public static final MessageType Unknown = new MessageType();

    private MAPMessageType mapMessageType;

    /**
     *
     */
    public MessageType() {
    }

    public MessageType(MAPMessageType mapMessageType) {
        this.mapMessageType = mapMessageType;
    }

    @JsonValue
    public MAPMessageType getType() {
        return mapMessageType;
    }

    public void setType(MAPMessageType mapMessageType) {
        this.mapMessageType = mapMessageType;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((mapMessageType == null) ? 0 : mapMessageType.hashCode());
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
        MessageType other = (MessageType) obj;
        if (mapMessageType != other.mapMessageType)
            return false;
        return true;
    }
}
