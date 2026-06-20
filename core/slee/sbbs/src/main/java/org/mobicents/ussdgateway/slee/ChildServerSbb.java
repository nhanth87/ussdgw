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

package org.mobicents.ussdgateway.slee;

import javax.slee.ActivityContextInterface;
import javax.slee.SLEEException;
import javax.slee.facilities.TimerEvent;
import javax.slee.facilities.TimerFacility;
import javax.slee.facilities.TimerID;
import javax.slee.facilities.TimerOptions;

import org.restcomm.protocols.ss7.map.api.datacoding.CBSDataCodingScheme;
import org.restcomm.protocols.ss7.map.api.primitives.USSDString;
import org.restcomm.protocols.ss7.map.api.service.supplementary.MAPDialogSupplementary;
import org.restcomm.protocols.ss7.map.datacoding.CBSDataCodingSchemeImpl;
import org.mobicents.ussdgateway.UssdPropertiesManagement;
import org.mobicents.ussdgateway.UssdPropertiesManagementMBean;
import org.mobicents.ussdgateway.slee.cdr.ChargeInterface;
import org.mobicents.ussdgateway.slee.cdr.RecordStatus;

/**
 * @author Amit Bhayani
 * 
 */
public abstract class ChildServerSbb extends USSDBaseSbb {

    protected UssdPropertiesManagementMBean ussdPropertiesManagement = null;
    protected TimerFacility timerFacility = null;

	public ChildServerSbb(String loggerName) {
		super(loggerName);
	}

    /**
     * Timer event
     */
    public void onTimerEvent(TimerEvent event, ActivityContextInterface aci) {

        if (super.logger.isWarningEnabled()) {
            super.logger.warning(String.format(
                    "Application didn't revert in %d milliseconds for PUSH case. Sending back dialogtimeouterrmssg for MAPDialog %s",
                    ussdPropertiesManagement.getDialogTimeout(), this.getMAPDialog()));
        }

        String errorMssg = ussdPropertiesManagement.getDialogTimeoutErrorMessage();
        this.sendErrorMessage(errorMssg);

        if (isSip()) { // sending error message only in SIP case
            abortSipDialog();
        }

        this.terminateProtocolConnection();

        this.ussdStatAggregator.updateAppTimeouts();
        this.updateDialogFailureStat();

        this.createCDRRecord(RecordStatus.FAILED_APP_TIMEOUT);
    }

    protected abstract boolean isSip();

    protected abstract void abortSipDialog();

    protected void sendErrorMessage(String errorMssg) {
        MAPDialogSupplementary mapDialogSupplementary = (MAPDialogSupplementary) this.getMAPDialog();
        this.sendErrorMessage(mapDialogSupplementary, errorMssg);
    }

    protected void sendErrorMessage(MAPDialogSupplementary mapDialogSupplementary, String errorMssg) {
        if (errorMssg != null) {
            if (errorMssg.length() > 160)
                errorMssg = errorMssg.substring(0, 160);
        } else {
            errorMssg = "sendError";
        }

        try {
            USSDString ussdString = mapParameterFactory.createUSSDString(errorMssg);

            // TODO this is in-correct. The CBSDataCodingScheme must be
            // configurable or from original request?
            CBSDataCodingScheme cbsDataCodingScheme = new CBSDataCodingSchemeImpl(0x0f);
            mapDialogSupplementary.addUnstructuredSSNotifyRequest(cbsDataCodingScheme, ussdString, null, null);
            mapDialogSupplementary.send();
            this.setFinalMessageSent(true);
        } catch (Exception e) {
            logger.severe("Exception while trying to send MAP ErrorMessage", e);
        }

    }

    protected abstract void terminateProtocolConnection();
    protected abstract void updateDialogFailureStat();

    protected void cancelTimer() {
        try {
            TimerID timerID = this.getTimerID();
            if (timerID != null) {
                this.timerFacility.cancelTimer(timerID);
            }
        } catch (Exception e) {
            logger.severe("Could not cancel Timer", e);
        }
    }

    protected void setTimer(ActivityContextInterface ac) {
        TimerOptions options = new TimerOptions();
        long waitingTime = ussdPropertiesManagement.getDialogTimeout();
        // Set the timer on ACI
        TimerID timerID = this.timerFacility.setTimer(ac, null, System.currentTimeMillis() + waitingTime, options);
        this.setTimerID(timerID);
    }

    public abstract void setTimerID(TimerID value);
    public abstract TimerID getTimerID();
    public abstract void setFinalMessageSent(boolean value);
    public abstract boolean getFinalMessageSent();

	public ChargeInterface getCDRChargeInterface() {
		return getLocalRaChargeInterface();
	}
	
	protected void createCDRRecord(RecordStatus recordStatus) {
		try {
			submitLocalRaCdr(recordStatus);
		} catch (Exception e) {
			logger.severe("Error while trying to create CDR Record", e);
		}
	}

}
