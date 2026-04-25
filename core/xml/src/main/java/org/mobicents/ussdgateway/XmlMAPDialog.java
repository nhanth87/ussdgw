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

package org.mobicents.ussdgateway;

import java.util.Map;

import javax.naming.OperationNotSupportedException;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

import org.restcomm.protocols.ss7.map.api.MAPApplicationContext;
import org.restcomm.protocols.ss7.map.api.MAPApplicationContextName;
import org.restcomm.protocols.ss7.map.api.MAPApplicationContextVersion;
import org.restcomm.protocols.ss7.map.api.MAPDialog;
import org.restcomm.protocols.ss7.map.api.MAPException;
import org.restcomm.protocols.ss7.map.api.MAPMessage;
import org.restcomm.protocols.ss7.map.api.MAPMessageType;
import org.restcomm.protocols.ss7.map.api.MAPServiceBase;
import org.restcomm.protocols.ss7.map.api.dialog.MAPAbortProviderReason;
import org.restcomm.protocols.ss7.map.api.dialog.MAPDialogState;
import org.restcomm.protocols.ss7.map.api.dialog.MAPRefuseReason;
import org.restcomm.protocols.ss7.map.api.dialog.MAPUserAbortChoice;
import org.restcomm.protocols.ss7.map.api.dialog.ProcedureCancellationReason;
import org.restcomm.protocols.ss7.map.api.dialog.Reason;
import org.restcomm.protocols.ss7.map.api.dialog.ResourceUnavailableReason;
import org.restcomm.protocols.ss7.map.api.errors.MAPErrorMessage;
import org.restcomm.protocols.ss7.map.api.primitives.AddressString;
import org.restcomm.protocols.ss7.map.api.primitives.MAPExtensionContainer;
import org.restcomm.protocols.ss7.map.api.service.supplementary.MAPDialogSupplementary;
import org.restcomm.protocols.ss7.map.dialog.MAPUserAbortChoiceImpl;
import org.restcomm.protocols.ss7.map.primitives.AddressStringImpl;
import org.restcomm.protocols.ss7.map.service.supplementary.ProcessUnstructuredSSRequestImpl;
import org.restcomm.protocols.ss7.map.service.supplementary.ProcessUnstructuredSSResponseImpl;
import org.restcomm.protocols.ss7.map.service.supplementary.UnstructuredSSNotifyRequestImpl;
import org.restcomm.protocols.ss7.map.service.supplementary.UnstructuredSSNotifyResponseImpl;
import org.restcomm.protocols.ss7.map.service.supplementary.UnstructuredSSRequestImpl;
import org.restcomm.protocols.ss7.map.service.supplementary.UnstructuredSSResponseImpl;
import org.restcomm.protocols.ss7.sccp.impl.parameter.SccpAddressImpl;
import org.restcomm.protocols.ss7.sccp.parameter.SccpAddress;
import org.restcomm.protocols.ss7.tcap.api.MessageType;
import org.restcomm.protocols.ss7.tcap.asn.comp.Invoke;
import org.restcomm.protocols.ss7.tcap.asn.comp.Problem;
import org.restcomm.protocols.ss7.tcap.asn.comp.ReturnResult;
import org.restcomm.protocols.ss7.tcap.asn.comp.ReturnResultLast;

/**
 * <p>
 * Represents the underlying {@link MAPDialogSupplementary}. Application can use
 * this instance of this class, set the supplementary {@link MAPMessage} and
 * pass it {@link EventsSerializeFactory} to serialize this Dialog to send
 * across the wire.
 * </p>
 * <p>
 * Application may also pass byte[] to {@link EventsSerializeFactory} and get
 * deserialized Dailog back
 * </p>
 * 
 * @author amit bhayani
 * 
 */
@JacksonXmlRootElement(localName = "dialog")
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class XmlMAPDialog implements MAPDialog {

	private static final String MAP_APPLN_CONTEXT = "appCntx";
	
	private static final String NETWORK_ID = "networkId";

	private static final String SCCP_LOCAL_ADD = "localAddress";
	private static final String SCCP_REMOTE_ADD = "remoteAddress";

	private static final String MAP_USER_ABORT_CHOICE = "mapUserAbortChoice";
	private static final String MAP_PROVIDER_ABORT_REASON = "mapAbortProviderReason";
	private static final String MAP_REFUSE_REASON = "mapRefuseReason";
    private static final String MAP_DIALOG_TIMEDOUT = "dialogTimedOut";
    private static final String MAP_SRI_PART = "sriPart";
	private static final String EMPTY_DIALOG_HANDSHAKE = "emptyDialogHandshake";
	private static final String MAP_INVOKE_TIMEDOUT = "invokeTimedOut";

	private static final String PRE_ARRANGED_END = "prearrangedEnd";

	private static final String RETURN_MSG_ON_ERR = "returnMessageOnError";

//	private static final String REDIRECT_REQUEST = "redirectRequest";

	private static final String MAP_MSGS_SIZE = "mapMessagesSize";

	private static final String LOCAL_ID = "localId";
	private static final String REMOTE_ID = "remoteId";

	private static final String INVOKE_WITHOUT_ANSWERS_ID = "invokeWithoutAnswerIds";
	private static final String CUSTOM_INVOKE_TIMEOUT = "customInvokeTimeout";

    private static final String ERROR_COMPONENTS = "errComponents";
    private static final String REJECT_COMPONENTS = "rejectComponents";

	private static final String COMMA_SEPARATOR = ",";
	private static final String UNDERSCORE_SEPARATOR = "_";

	private static final String DESTINATION_REFERENCE = "destinationReference";
	private static final String ORIGINATION_REFERENCE = "originationReference";

	private static final String MAPUSERABORTCHOICE_PROCEDCANC = "isProcedureCancellationReason";
	private static final String MAPUSERABORTCHOICE_RESORCUNAV = "isResourceUnavailableReason";
	private static final String MAPUSERABORTCHOICE_USERRESLMT = "isUserResourceLimitation";
	private static final String MAPUSERABORTCHOICE_USERSPECREA = "isUserSpecificReason";

	private static final String DIALOG_TYPE = "type";

	private static final String USER_OBJECT = "userObject";

	@JsonProperty("appCntx")
	// Application Context of this Dialog
	protected MAPApplicationContext appCntx;

	@JsonDeserialize(as = SccpAddressImpl.class)
	protected SccpAddress localAddress;
	@JsonDeserialize(as = SccpAddressImpl.class)
	protected SccpAddress remoteAddress;

	@JsonProperty("mapUserAbortChoice")
	private MAPUserAbortChoice mapUserAbortChoice = null;
	private MAPAbortProviderReason mapAbortProviderReason = null;
	private MAPRefuseReason mapRefuseReason = null;
	@JsonProperty("refuseReason")
	private Reason refuseReason = null;
	@JsonProperty("prearrangedEnd")
	private Boolean prearrangedEnd = null;
	private Boolean dialogTimedOut = null;
	private Boolean emptyDialogHandshake = null;
    private Boolean sriPart = null;

	@JsonProperty("localId")
	private Long localId;
	@JsonProperty("remoteId")
	private Long remoteId;
	
	private int networkId;

	private boolean returnMessageOnError = true;

//	private boolean redirectRequest = false;

	@JsonProperty("processInvokeWithoutAnswerIds")
	@JacksonXmlElementWrapper(useWrapping = false)
	private FastList<Long> processInvokeWithoutAnswerIds = new FastList<>();

	@JsonProperty("mapMessages")
	@JacksonXmlElementWrapper(useWrapping = false)
	private FastList<MAPMessage> mapMessages = new FastList<>();

    @JsonProperty("errorComponents")
    private ErrorComponentMap errorComponents = new ErrorComponentMap();
    @JsonProperty("rejectComponents")
    private RejectComponentMap rejectComponents = new RejectComponentMap();

	@JsonProperty("state")
	private MAPDialogState state = MAPDialogState.IDLE;

	@JsonProperty("destReference")
	private AddressString destReference;
	@JsonProperty("origReference")
	private AddressString origReference;

	@JsonProperty("messageType")
	private MessageType messageType = MessageType.Unknown;

	private Long invokeTimedOut = null;
	private Integer customInvokeTimeOut = null;

	private String userObject = null;

	public XmlMAPDialog() {
		super();
	}

	/**
	 * 
	 */
	public XmlMAPDialog(MAPApplicationContext appCntx, SccpAddress localAddress, SccpAddress remoteAddress,
			Long localId, Long remoteId, AddressString destReference, AddressString origReference) {
		this.appCntx = appCntx;
		this.localAddress = localAddress;
		this.remoteAddress = remoteAddress;
		this.localId = localId;
		this.remoteId = remoteId;

		this.destReference = destReference;
		this.origReference = origReference;
	}

	@Override
	public void abort(MAPUserAbortChoice mapUserAbortChoice) throws MAPException {
		this.mapUserAbortChoice = mapUserAbortChoice;
	}

	@Override
	public void addEricssonData(AddressString arg0, AddressString arg1) {
		// TODO Auto-generated method stub

	}

	@Override
	public boolean cancelInvocation(Long arg0) throws MAPException {
		throw new MAPException(new OperationNotSupportedException());
	}

	@Override
	public void close(boolean prearrangedEnd) throws MAPException {
		this.prearrangedEnd = prearrangedEnd;
	}

	@Override
	public void closeDelayed(boolean arg0) throws MAPException {
		throw new MAPException(new OperationNotSupportedException());
	}

	@Override
	public MAPApplicationContext getApplicationContext() {
		return this.appCntx;
	}

	@Override
	public SccpAddress getLocalAddress() {
		return this.localAddress;
	}

	@Override
	public Long getLocalDialogId() {
		return this.localId;
	}

	@Override
	public int getMaxUserDataLength() {
		return 0;
	}

	@Override
	public int getMessageUserDataLengthOnClose(boolean arg0) throws MAPException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getMessageUserDataLengthOnSend() throws MAPException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public AddressString getReceivedDestReference() {
		return this.destReference;
	}

	@Override
	public MAPExtensionContainer getReceivedExtensionContainer() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public AddressString getReceivedOrigReference() {
		return this.origReference;
	}

	@Override
	public SccpAddress getRemoteAddress() {
		return this.remoteAddress;
	}

	@Override
	public Long getRemoteDialogId() {
		return this.remoteId;
	}

	@Override
	public boolean getReturnMessageOnError() {
		return this.returnMessageOnError;
	}

	@Override
	public MAPServiceBase getService() {
		return null;
	}

	@Override
	public MAPDialogState getState() {
		return this.state;
	}

	@Override
	public MessageType getTCAPMessageType() {
		return this.messageType;
	}

	@Override
	public Object getUserObject() {
		return this.userObject;
	}

	@Override
	public void keepAlive() {
		// TODO Auto-generated method stub

	}

	@Override
	public void processInvokeWithoutAnswer(Long invokeId) {
		this.processInvokeWithoutAnswerIds.add(invokeId);
	}

	@Override
	public void refuse(Reason refuseReason) throws MAPException {
		this.refuseReason = refuseReason;
	}

	@Override
	public void release() {
		// TODO Auto-generated method stub

	}

	@Override
	public void resetInvokeTimer(Long arg0) throws MAPException {
		throw new MAPException(new OperationNotSupportedException());
	}

	@Override
	public void send() throws MAPException {
		throw new MAPException(new OperationNotSupportedException());
	}

	@Override
	public void sendDelayed() throws MAPException {
		throw new MAPException(new OperationNotSupportedException());
	}

	@Override
	public void sendErrorComponent(Long invokeId, MAPErrorMessage mapErrorMessage) throws MAPException {
		this.errorComponents.put(invokeId, mapErrorMessage);
	}

	@Override
	public void sendInvokeComponent(Invoke arg0) throws MAPException {
		throw new MAPException(new OperationNotSupportedException());
	}

    @Override
    public void sendRejectComponent(Long invokeId, Problem problem) throws MAPException {
        this.rejectComponents.put(invokeId, problem);
    }

	@Override
	public void sendReturnResultComponent(ReturnResult arg0) throws MAPException {
		// TODO Auto-generated method stub

	}

	@Override
	public void sendReturnResultLastComponent(ReturnResultLast arg0) throws MAPException {
		throw new MAPException(new OperationNotSupportedException());

	}

	@Override
	public void setExtensionContainer(MAPExtensionContainer arg0) {
		// TODO Auto-generated method stub

	}

	@Override
	public void setLocalAddress(SccpAddress origAddress) {
		this.localAddress = origAddress;
	}

	@Override
	public void setRemoteAddress(SccpAddress destAddress) {
		this.remoteAddress = destAddress;
	}

	@Override
	public void setReturnMessageOnError(boolean returnMessageOnError) {
		this.returnMessageOnError = returnMessageOnError;

	}

	@Override
	public void setUserObject(Object obj) {
		this.userObject = obj.toString();
	}
	
	@Override
	public int getNetworkId() {
		return this.networkId;
	}

	@Override
	public void setNetworkId(int networkId) {
        this.networkId = networkId;
	}
	
	@Override
	public long getStartTimeDialog() {
		//method stub
		return -1;
	}
	
	@Override
	public long getIdleTaskTimeout() {
		//method stub
		return -1;
	}

	@Override
	public void setIdleTaskTimeout(long idleTaskTimeoutMs) {
		//method stub
	}
	
	@Override
	public void setDoNotSendProtocolVersion(Boolean doNotSendProtocolVersion) {
		//method stub 
	}
	
	@Override
	public Boolean isDoNotSendProtocolVersion() {
		//method stub
		return null;
	}

	/**
	 * Non MAPDialog methods
	 */

	public void addMAPMessage(MAPMessage mapMessage) {
		this.mapMessages.add(mapMessage);
	}

	public boolean removeMAPMessage(MAPMessage mapMessage) {
		return this.mapMessages.remove(mapMessage);
	}

	@JsonIgnore
	public FastList<MAPMessage> getMAPMessages() {
		if (this.mapMessages == null) {
			this.mapMessages = new FastList<>();
		}
		return this.mapMessages;
	}

	@JsonIgnore
	public FastList<Long> getProcessInvokeWithoutAnswerIds() {
		if (this.processInvokeWithoutAnswerIds == null) {
			this.processInvokeWithoutAnswerIds = new FastList<>();
		}
		return this.processInvokeWithoutAnswerIds;
	}

    @JsonIgnore
    public Map<Long, MAPErrorMessage> getErrorComponents() {
        return errorComponents.getErrorComponents();
    }

    @JsonIgnore
    public Map<Long, Problem> getRejectComponents() {
        return rejectComponents.getRejectComponents();
    }

	public MAPUserAbortChoice getMAPUserAbortChoice() {
		return this.mapUserAbortChoice;
	}

	public MAPAbortProviderReason getMapAbortProviderReason() {
		return mapAbortProviderReason;
	}

	public void setMapAbortProviderReason(MAPAbortProviderReason mapAbortProviderReason) {
		this.mapAbortProviderReason = mapAbortProviderReason;
	}

	public MAPRefuseReason getMapRefuseReason() {
		return mapRefuseReason;
	}

	public void setMapRefuseReason(MAPRefuseReason mapRefuseReason) {
		this.mapRefuseReason = mapRefuseReason;
	}

	public Boolean getDialogTimedOut() {
		return dialogTimedOut;
	}

	public void setDialogTimedOut(Boolean dialogTimedOut) {
		this.dialogTimedOut = dialogTimedOut;
	}

    public Boolean getSriPart() {
        return sriPart;
    }

    public void setSriPart(Boolean sriPart) {
        this.sriPart = sriPart;
    }

	public Boolean getPrearrangedEnd() {
		return this.prearrangedEnd;
	}

	public void setTCAPMessageType(MessageType messageType) {
		this.messageType = messageType;
	}

//	public boolean isRedirectRequest() {
//		return redirectRequest;
//	}

	public Long getInvokeTimedOut() {
		return invokeTimedOut;
	}

	public void setInvokeTimedOut(Long invokeTimedOut) {
		this.invokeTimedOut = invokeTimedOut;
	}

//	public void setRedirectRequest(boolean redirectRequest) {
//		this.redirectRequest = redirectRequest;
//	}

	public Integer getCustomInvokeTimeOut() {
		return customInvokeTimeOut;
	}

	/**
	 * Set custom invoke time out for added MapMessages. If not set the default
	 * values will be used
	 * 
	 * @param customInvokeTimeOut
	 */
	public void setCustomInvokeTimeOut(Integer customInvokeTimeOut) {
		this.customInvokeTimeOut = customInvokeTimeOut;
	}

	public Boolean getEmptyDialogHandshake() {
		return emptyDialogHandshake;
	}

	/**
	 * A special parameter used only when Dialog is initiated by USSD Gw (Push
	 * or Proxy). USSD Gateway will first create empty Dialog and send Begin to
	 * remote end and only on Dialog accept from remote side it, will send
	 * payload.
	 * 
	 * @param emptyDialogHandshake
	 */
	public void setEmptyDialogHandshake(Boolean emptyDialogHandshake) {
		this.emptyDialogHandshake = emptyDialogHandshake;
	}

	public void reset() {
		this.mapMessages.clear();
		this.processInvokeWithoutAnswerIds.clear();
		this.errorComponents.clear();
		this.rejectComponents.clear();
	}

	@Override
	public String toString() {
		return "XmlMAPDialog [appCntx=" + appCntx + ", localAddress="
				+ localAddress + ", remoteAddress=" + remoteAddress
				+ ", mapUserAbortChoice=" + mapUserAbortChoice
				+ ", mapAbortProviderReason=" + mapAbortProviderReason
				+ ", mapRefuseReason=" + mapRefuseReason + ", refuseReason="
				+ refuseReason + ", prearrangedEnd=" + prearrangedEnd
				+ ", dialogTimedOut=" + dialogTimedOut
				+ ", emptyDialogHandshake=" + emptyDialogHandshake
				+ ", sriPart=" + sriPart + ", localId=" + localId
				+ ", remoteId=" + remoteId + ", networkId=" + networkId
				+ ", returnMessageOnError=" + returnMessageOnError
				+ ", processInvokeWithoutAnswerIds="
				+ processInvokeWithoutAnswerIds + ", mapMessages="
				+ mapMessages + ", errorComponents=" + errorComponents
				+ ", rejectComponents=" + rejectComponents + ", state=" + state
				+ ", destReference=" + destReference + ", origReference="
				+ origReference + ", messageType=" + messageType
				+ ", invokeTimedOut=" + invokeTimedOut
				+ ", customInvokeTimeOut=" + customInvokeTimeOut
				+ ", userObject=" + userObject + "]";
	}

	protected static String serializeMAPUserAbortChoice(MAPUserAbortChoice abort) {
		StringBuilder sb = new StringBuilder();

		if (abort.isUserSpecificReason()) {
			sb.append(MAPUSERABORTCHOICE_USERSPECREA);
			return sb.toString();
		}

		if (abort.isProcedureCancellationReason()) {
			sb.append(MAPUSERABORTCHOICE_PROCEDCANC).append(UNDERSCORE_SEPARATOR)
					.append(abort.getProcedureCancellationReason().name());
			return sb.toString();
		}

		if (abort.isResourceUnavailableReason()) {
			sb.append(MAPUSERABORTCHOICE_RESORCUNAV).append(UNDERSCORE_SEPARATOR)
					.append(abort.getResourceUnavailableReason().name());
			return sb.toString();
		}

		if (abort.isUserResourceLimitation()) {
			sb.append(MAPUSERABORTCHOICE_USERRESLMT);
			return sb.toString();
		}

		return null;
	}

	protected static MAPUserAbortChoice deserializeMAPUserAbortChoice(String str) {
		String[] appCtxBody = str.split(UNDERSCORE_SEPARATOR);

		MAPUserAbortChoiceImpl abort = new MAPUserAbortChoiceImpl();

		if (appCtxBody[0].equals(MAPUSERABORTCHOICE_USERSPECREA)) {
			abort.setUserSpecificReason();
			return abort;
		}

		if (appCtxBody[0].equals(MAPUSERABORTCHOICE_USERRESLMT)) {
			abort.setUserResourceLimitation();
			return abort;
		}

		if (appCtxBody[0].equals(MAPUSERABORTCHOICE_PROCEDCANC)) {
			ProcedureCancellationReason procCanReasn = ProcedureCancellationReason.valueOf(appCtxBody[1]);
			abort.setProcedureCancellationReason(procCanReasn);
			return abort;
		}

		if (appCtxBody[0].equals(MAPUSERABORTCHOICE_RESORCUNAV)) {
			ResourceUnavailableReason resUnaReas = ResourceUnavailableReason.valueOf(appCtxBody[1]);
			abort.setResourceUnavailableReason(resUnaReas);
			return abort;
		}

		return null;
	}

	protected static String serializeMAPApplicationContext(MAPApplicationContext mapApplicationContext) {
		StringBuilder sb = new StringBuilder();
		sb.append(mapApplicationContext.getApplicationContextName().name()).append(UNDERSCORE_SEPARATOR)
				.append(mapApplicationContext.getApplicationContextVersion().name());
		return sb.toString();
	}

	protected static MAPApplicationContext deserializeMAPApplicationContext(String str) {
		String[] appCtxBody = str.split(UNDERSCORE_SEPARATOR);

		MAPApplicationContextName appCtxname = MAPApplicationContextName.valueOf(appCtxBody[0]);
		MAPApplicationContextVersion appCtxVer = MAPApplicationContextVersion.valueOf(appCtxBody[1]);

		return MAPApplicationContext.getInstance(appCtxname, appCtxVer);

	}


	public int getLongTimer() {
		// TODO Auto-generated method stub
		return 0;
	}

	public int getMediumTimer() {
		// TODO Auto-generated method stub
		return 0;
	}

	public int getShortTimer() {
		// TODO Auto-generated method stub
		return 0;
	}

}
