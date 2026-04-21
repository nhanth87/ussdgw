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
 * REPLACED: javolution.util.FastMap with HashMap and javolution.xml with Jackson
 * 100% API Compatible with original
 */
package org.mobicents.ussdgateway;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.restcomm.protocols.ss7.tcap.asn.comp.Problem;

import java.util.Map;
import java.util.HashMap;
import java.util.Collections;

/**
 * @author sergey vetyutnev
 * @author Matrix Agent
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class RejectComponentMap {

    @JsonProperty("rejectComponents")
    private HashMap<Long, Problem> data = new HashMap<>();

    public void put(Long invokeId, Problem problem) {
        this.data.put(invokeId, problem);
    }

    public void clear() {
        this.data.clear();
    }

    public int size() {
        return this.data.size();
    }

    @JsonIgnore
    public Map<Long, Problem> getRejectComponents() {
        return Collections.unmodifiableMap(data);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("RejectComponentMap=[");

        int i1 = 0;
        for (Map.Entry<Long, Problem> entry : data.entrySet()) {
            Long id = entry.getKey();
            Problem problem = entry.getValue();

            if (i1 == 0)
                i1 = 1;
            else
                sb.append(", ");
            sb.append("invokeId=");
            sb.append(id);
            sb.append(", problem=");
            sb.append(problem);
        }

        sb.append("]");
        return sb.toString();
    }
}
