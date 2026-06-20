package org.mobicents.ussdgateway.slee.cdr;

import java.sql.Timestamp;

import org.joda.time.DateTime;
import org.restcomm.protocols.ss7.indicator.AddressIndicator;
import org.restcomm.protocols.ss7.map.api.primitives.AddressString;
import org.restcomm.protocols.ss7.map.api.primitives.IMSI;
import org.restcomm.protocols.ss7.map.api.primitives.ISDNAddressString;
import org.restcomm.protocols.ss7.sccp.parameter.GlobalTitle;
import org.restcomm.protocols.ss7.sccp.parameter.SccpAddress;
import org.mobicents.ussdgateway.UssdPropertiesManagement;

import javax.slee.facilities.Tracer;

/**
 * Single source of truth for CDR line formatting and record finalization.
 * Used by plain CDR SBB, JDBC CDR SBB state prep, and CDR Local RA mode.
 */
public final class CdrLineFormatter {

    private CdrLineFormatter() {
    }

    /**
     * Applies the same state mutations as {@code CDRGeneratorSbb.createRecord()} before formatting.
     *
     * @return {@code false} if a record was already generated for this dialog
     */
    public static boolean finalizeRecord(USSDCDRState state, RecordStatus outcome) {
        if (state.isGenerated()) {
            return false;
        }
        DateTime startTime = state.getDialogStartTime();
        if (startTime != null) {
            DateTime endTime = DateTime.now();
            state.setDialogEndTime(endTime);
            state.setDialogDuration(endTime.getMillis() - startTime.getMillis());
        }
        state.setRecordStatus(outcome);
        state.setGenerated(true);
        return true;
    }

    public static boolean prepareRecord(USSDCDRState state, RecordStatus outcome, Tracer logger) {
        if (!finalizeRecord(state, outcome)) {
            if (logger != null) {
                logger.severe("");
            }
            return false;
        }
        if (logger != null && logger.isFineEnabled()) {
            logger.fine("Generating record, status '" + outcome + "' for '" + state + "'");
        }
        return true;
    }

    public static String format(USSDCDRState state) {
        return format(state, new Timestamp(System.currentTimeMillis()), resolveSeparator());
    }

    static String format(USSDCDRState state, Timestamp tstamp) {
        return format(state, tstamp, resolveSeparator());
    }

    static String format(USSDCDRState state, Timestamp tstamp, String separator) {
        final StringBuilder sb = new StringBuilder();

        SccpAddress localAddress = state.getLocalAddress();
        if (localAddress != null) {
            AddressIndicator addressIndicator = localAddress.getAddressIndicator();
            if (addressIndicator.isPCPresent()) {
                sb.append(localAddress.getSignalingPointCode()).append(separator);
            } else {
                sb.append(separator);
            }
            if (addressIndicator.isSSNPresent()) {
                sb.append((byte) localAddress.getSubsystemNumber()).append(separator);
            } else {
                sb.append(separator);
            }
            if (addressIndicator.getRoutingIndicator() != null) {
                sb.append((byte) addressIndicator.getRoutingIndicator().getValue()).append(separator);
            } else {
                sb.append(separator);
            }
            GlobalTitle gt = localAddress.getGlobalTitle();
            if (gt != null && gt.getGlobalTitleIndicator() != null) {
                sb.append((byte) gt.getGlobalTitleIndicator().getValue()).append(separator);
            } else {
                sb.append(separator);
            }
            if (gt != null && gt.getDigits() != null) {
                sb.append(gt.getDigits()).append(separator);
            } else {
                sb.append(separator);
            }
        }

        SccpAddress remoteAddress = state.getRemoteAddress();
        if (remoteAddress != null) {
            AddressIndicator addressIndicator = remoteAddress.getAddressIndicator();
            if (addressIndicator.isPCPresent()) {
                sb.append(remoteAddress.getSignalingPointCode()).append(separator);
            } else {
                sb.append(separator);
            }
            if (addressIndicator.isSSNPresent()) {
                sb.append((byte) remoteAddress.getSubsystemNumber()).append(separator);
            } else {
                sb.append(separator);
            }
            if (addressIndicator.getRoutingIndicator() != null) {
                sb.append((byte) addressIndicator.getRoutingIndicator().getValue()).append(separator);
            } else {
                sb.append(separator);
            }
            GlobalTitle gt = remoteAddress.getGlobalTitle();
            if (gt != null && gt.getGlobalTitleIndicator() != null) {
                sb.append((byte) gt.getGlobalTitleIndicator().getValue()).append(separator);
            } else {
                sb.append(separator);
            }
            if (gt != null && gt.getDigits() != null) {
                sb.append(gt.getDigits()).append(separator);
            } else {
                sb.append(separator);
            }
        }

        sb.append(state.getServiceCode()).append(separator);

        AddressString addressString = state.getOrigReference();
        if (addressString != null) {
            sb.append((byte) addressString.getAddressNature().getIndicator()).append(separator);
            sb.append((byte) addressString.getNumberingPlan().getIndicator()).append(separator);
            sb.append(addressString.getAddress()).append(separator);
        } else {
            sb.append(separator);
            sb.append(separator);
            sb.append(separator);
        }

        addressString = state.getDestReference();
        if (addressString != null) {
            sb.append((byte) addressString.getAddressNature().getIndicator()).append(separator);
            sb.append((byte) addressString.getNumberingPlan().getIndicator()).append(separator);
            sb.append(addressString.getAddress()).append(separator);
        } else {
            sb.append(separator);
            sb.append(separator);
            sb.append(separator);
        }

        ISDNAddressString isdnAddressString = state.getISDNAddressString();
        if (isdnAddressString != null) {
            sb.append((byte) isdnAddressString.getAddressNature().getIndicator()).append(separator);
            sb.append((byte) isdnAddressString.getNumberingPlan().getIndicator()).append(separator);
            sb.append(isdnAddressString.getAddress()).append(separator);
        } else {
            sb.append(separator);
            sb.append(separator);
            sb.append(separator);
        }

        addressString = state.getEriVlrNo();
        if (addressString != null) {
            sb.append((byte) addressString.getAddressNature().getIndicator()).append(separator);
            sb.append((byte) addressString.getNumberingPlan().getIndicator()).append(separator);
            sb.append(addressString.getAddress()).append(separator);
        } else {
            sb.append(separator);
            sb.append(separator);
            sb.append(separator);
        }

        IMSI imsi = state.getEriImsi();
        if (imsi != null) {
            sb.append(imsi.getData()).append(separator);
        } else {
            sb.append(separator);
        }

        sb.append(state.getRecordStatus().toString()).append(separator);
        sb.append(state.getUssdType().toString()).append(separator);
        sb.append(tstamp).append(separator);
        sb.append(state.getLocalDialogId()).append(separator);
        sb.append(state.getRemoteDialogId()).append(separator);

        Long dialogDuration = state.getDialogDuration();
        if (dialogDuration != null) {
            sb.append(dialogDuration).append(separator);
        } else {
            sb.append(separator);
        }

        String ussdString = state.getUssdString();
        if (ussdString != null && !ussdString.isEmpty()) {
            sb.append(ussdString).append(separator);
        } else {
            sb.append(separator);
        }

        sb.append(state.getId());

        // Virtual Session Bridge fields (appended at the end to preserve the legacy layout).
        // For non-bridged records both are empty so existing parsers ignore the trailing fields.
        String correlationId = state.getCorrelationId();
        String bridgePhase = state.getBridgePhase();
        if (correlationId != null || bridgePhase != null) {
            sb.append(separator).append(correlationId == null ? "" : correlationId);
            sb.append(separator).append(bridgePhase == null ? "" : bridgePhase);
        }
        return sb.toString();
    }

    private static String resolveSeparator() {
        UssdPropertiesManagement props = UssdPropertiesManagement.getInstance();
        if (props != null) {
            String separator = props.getCdrSeparator();
            if (separator != null && separator.length() > 0) {
                return separator;
            }
        }
        return ":";
    }
}
