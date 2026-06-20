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
 * REPLACED: javolution.util.FastMap with ConcurrentHashMap and javolution.xml with Jackson
 * 100% API Compatible with original
 */
package org.mobicents.ussdgateway;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import org.restcomm.protocols.ss7.map.api.errors.MAPErrorMessage;
import org.restcomm.protocols.ss7.map.api.errors.MAPErrorMessageAbsentSubscriber;
import org.restcomm.protocols.ss7.map.api.errors.MAPErrorMessageAbsentSubscriberSM;
import org.restcomm.protocols.ss7.map.api.errors.MAPErrorMessageBusySubscriber;
import org.restcomm.protocols.ss7.map.api.errors.MAPErrorMessageCUGReject;
import org.restcomm.protocols.ss7.map.api.errors.MAPErrorMessageCallBarred;
import org.restcomm.protocols.ss7.map.api.errors.MAPErrorMessageExtensionContainer;
import org.restcomm.protocols.ss7.map.api.errors.MAPErrorMessageFacilityNotSup;
import org.restcomm.protocols.ss7.map.api.errors.MAPErrorMessageParameterless;
import org.restcomm.protocols.ss7.map.api.errors.MAPErrorMessagePositionMethodFailure;
import org.restcomm.protocols.ss7.map.api.errors.MAPErrorMessagePwRegistrationFailure;
import org.restcomm.protocols.ss7.map.api.errors.MAPErrorMessageRoamingNotAllowed;
import org.restcomm.protocols.ss7.map.api.errors.MAPErrorMessageSMDeliveryFailure;
import org.restcomm.protocols.ss7.map.api.errors.MAPErrorMessageSsErrorStatus;
import org.restcomm.protocols.ss7.map.api.errors.MAPErrorMessageSsIncompatibility;
import org.restcomm.protocols.ss7.map.api.errors.MAPErrorMessageSubscriberBusyForMtSms;
import org.restcomm.protocols.ss7.map.api.errors.MAPErrorMessageSystemFailure;
import org.restcomm.protocols.ss7.map.api.errors.MAPErrorMessageUnauthorizedLCSClient;
import org.restcomm.protocols.ss7.map.api.errors.MAPErrorMessageUnknownSubscriber;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe MAP error component map.
 *
 * <p>Originally backed by {@code javolution.util.FastMap} (not thread-safe). The migration
 * to {@link ConcurrentHashMap} provides wait-free reads and lock-striped writes so the
 * SBB event handlers can safely update the map while the dialog serializer iterates
 * over its contents.</p>
 *
 * @author Amit Bhayani
 * @author sergey vetyutnev
 * @author Matrix Agent
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ErrorComponentMap extends ConcurrentHashMap<String, MAPErrorMessage> {

    public static final String MAP_ERROR_EXT_CONTAINER = MAPErrorMessageExtensionContainer.class.getSimpleName();
    public static final String MAP_ERROR_SM_DEL_FAILURE = MAPErrorMessageSMDeliveryFailure.class.getSimpleName();
    public static final String MAP_ERROR_ABSENT_SUBS_SM = MAPErrorMessageAbsentSubscriberSM.class.getSimpleName();
    public static final String MAP_ERROR_SYSTEM_FAILURE = MAPErrorMessageSystemFailure.class.getSimpleName();
    public static final String MAP_ERROR_CALL_BARRED = MAPErrorMessageCallBarred.class.getSimpleName();
    public static final String MAP_ERROR_FACILITY_NOT_SUPPORTED = MAPErrorMessageFacilityNotSup.class.getSimpleName();
    public static final String MAP_ERROR_UNKNOWN_SUBS = MAPErrorMessageUnknownSubscriber.class.getSimpleName();
    public static final String MAP_ERROR_SUBS_BUSY_FOR_MT_SMS = MAPErrorMessageSubscriberBusyForMtSms.class.getSimpleName();
    public static final String MAP_ERROR_ABSENT_SUBS = MAPErrorMessageAbsentSubscriber.class.getSimpleName();
    public static final String MAP_ERROR_UNAUTHORIZED_LCS_CLIENT = MAPErrorMessageUnauthorizedLCSClient.class.getSimpleName();
    public static final String MAP_ERROR_POSITION_METHOD_FAIL = MAPErrorMessagePositionMethodFailure.class.getSimpleName();
    public static final String MAP_ERROR_BUSY_SUBS = MAPErrorMessageBusySubscriber.class.getSimpleName();
    public static final String MAP_ERROR_CUG_REJECT = MAPErrorMessageCUGReject.class.getSimpleName();
    public static final String MAP_ERROR_ROAMING_NOT_ALLOWED = MAPErrorMessageRoamingNotAllowed.class.getSimpleName();
    public static final String MAP_ERROR_SS_ERROR_STATUS = MAPErrorMessageSsErrorStatus.class.getSimpleName();
    public static final String MAP_ERROR_SS_INCOMPATIBILITY = MAPErrorMessageSsIncompatibility.class.getSimpleName();
    public static final String MAP_ERROR_PW_REGS_FAIL = MAPErrorMessagePwRegistrationFailure.class.getSimpleName();
    public static final String MAP_ERROR_PARAM_LESS = MAPErrorMessageParameterless.class.getSimpleName();

    public void put(Long invokeId, MAPErrorMessage mapErrorMessage) {
        this.put("id" + invokeId, mapErrorMessage);
    }

    @JsonIgnore
    public Map<Long, MAPErrorMessage> getErrorComponents() {
        // entrySet() view of ConcurrentHashMap is weakly consistent and safe to iterate
        // even if the map is mutated concurrently. We use a plain HashMap to assemble
        // the typed view because it is published via Collections.unmodifiableMap.
        Map<Long, MAPErrorMessage> result = new java.util.HashMap<>(size());
        for (Map.Entry<String, MAPErrorMessage> entry : entrySet()) {
            result.put(Long.valueOf(entry.getKey().substring(2)), entry.getValue());
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("ErrorComponentMap=[");

        int i1 = 0;
        for (Map.Entry<String, MAPErrorMessage> entry : entrySet()) {
            Long id = Long.valueOf(entry.getKey().substring(2));
            MAPErrorMessage mapErrorMessage = entry.getValue();

            if (i1 == 0)
                i1 = 1;
            else
                sb.append(", ");
            sb.append("invokeId=");
            sb.append(id);
            sb.append(", mapErrorMessage=");
            sb.append(mapErrorMessage);
        }

        sb.append("]");
        return sb.toString();
    }
}
