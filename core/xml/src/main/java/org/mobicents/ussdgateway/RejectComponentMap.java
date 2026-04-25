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
public class RejectComponentMap extends HashMap<String, Problem> {

    public void put(Long invokeId, Problem problem) {
        this.put("id" + invokeId, problem);
    }

    @JsonIgnore
    public Map<Long, Problem> getRejectComponents() {
        Map<Long, Problem> result = new HashMap<>();
        for (Map.Entry<String, Problem> entry : entrySet()) {
            result.put(Long.valueOf(entry.getKey().substring(2)), entry.getValue());
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("RejectComponentMap=[");

        int i1 = 0;
        for (Map.Entry<String, Problem> entry : entrySet()) {
            Long id = Long.valueOf(entry.getKey().substring(2));
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
