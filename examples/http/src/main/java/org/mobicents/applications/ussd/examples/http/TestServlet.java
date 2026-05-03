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

package org.mobicents.applications.ussd.examples.http;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.log4j.Logger;
import org.mobicents.ussdgateway.EventsSerializeFactory;
import org.mobicents.ussdgateway.FastList;
import org.mobicents.ussdgateway.XmlMAPDialog;
import org.restcomm.protocols.ss7.map.api.MAPApplicationContext;
import org.restcomm.protocols.ss7.map.api.MAPApplicationContextName;
import org.restcomm.protocols.ss7.map.api.MAPApplicationContextVersion;
import org.restcomm.protocols.ss7.map.api.MAPException;
import org.restcomm.protocols.ss7.map.api.MAPMessage;
import org.restcomm.protocols.ss7.map.api.MAPMessageType;
import org.restcomm.protocols.ss7.map.api.datacoding.CBSDataCodingScheme;
import org.restcomm.protocols.ss7.map.api.primitives.AddressNature;
import org.restcomm.protocols.ss7.map.api.primitives.NumberingPlan;
import org.restcomm.protocols.ss7.map.api.primitives.USSDString;
import org.restcomm.protocols.ss7.map.api.service.supplementary.ProcessUnstructuredSSRequest;
import org.restcomm.protocols.ss7.map.api.service.supplementary.ProcessUnstructuredSSResponse;
import org.restcomm.protocols.ss7.map.api.service.supplementary.UnstructuredSSRequest;
import org.restcomm.protocols.ss7.map.api.service.supplementary.UnstructuredSSResponse;
import org.restcomm.protocols.ss7.map.datacoding.CBSDataCodingSchemeImpl;
import org.restcomm.protocols.ss7.map.primitives.AddressStringImpl;
import org.restcomm.protocols.ss7.map.primitives.ISDNAddressStringImpl;
import org.restcomm.protocols.ss7.map.primitives.USSDStringImpl;
import org.restcomm.protocols.ss7.map.service.supplementary.ProcessUnstructuredSSResponseImpl;
import org.restcomm.protocols.ss7.map.service.supplementary.UnstructuredSSRequestImpl;
import org.restcomm.protocols.ss7.sccp.impl.parameter.SccpAddressImpl;
import org.restcomm.protocols.ss7.sccp.parameter.SccpAddress;
import org.restcomm.protocols.ss7.tcap.api.MessageType;

/**
 * 
 * @author amit bhayani
 * 
 */
public class TestServlet extends HttpServlet {

	private static final Logger logger = Logger.getLogger(TestServlet.class);

	private EventsSerializeFactory factory = null;

	// HTTP Client RA does not send cookies, so each POST gets a new session.
	// Store per-dialog state (invokeId and menuLevel) keyed by dialogId.
	private static final ConcurrentHashMap<Long, Long> dialogInvokeIds = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<Long, Integer> dialogMenuLevels = new ConcurrentHashMap<>();

	@Override
	public void init() {
		factory = new EventsSerializeFactory();
	}

	@Override
	public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
		PrintWriter out = response.getWriter();
		out.println("<html>");
		out.println("<body>");
		out.println("<h1>Hello USSD Demo Get</h1>");
		out.println("</body>");
		out.println("</html>");
	}

	@Override
	public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
		ServletInputStream is = request.getInputStream();
		try {
			// Read raw XML for logging
			byte[] rawBytes = new byte[request.getContentLength()];
			int totalRead = 0;
			while (totalRead < rawBytes.length) {
				int read = is.read(rawBytes, totalRead, rawBytes.length - totalRead);
				if (read < 0) break;
				totalRead += read;
			}
			String rawXml = new String(rawBytes, "UTF-8");
			logger.info("JENNY-TESTSERVLET-RAW-XML: " + rawXml);
			
			XmlMAPDialog original = factory.deserialize(new java.io.ByteArrayInputStream(rawBytes));
			Long dialogId = original != null ? original.getLocalDialogId() : null;
			if (logger.isInfoEnabled()) {
				logger.info("doPost. DialogId=" + dialogId + " Dialog = " + original);
			}

			if (original == null) {
				logger.error("TestServlet: deserialized dialog is null");
				response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Could not deserialize XML dialog");
				return;
			}

			final FastList<MAPMessage> capMessages = original.getMAPMessages();
			MessageType messageType = original.getTCAPMessageType();

			if (capMessages == null || capMessages.size() == 0) {
				logger.warn("TestServlet: No MAP messages in dialog");
				response.sendError(HttpServletResponse.SC_BAD_REQUEST, "No MAP messages in dialog");
				return;
			}

			for (int i = 0; i < capMessages.size(); i++) {
				final MAPMessage rawMessage = capMessages.get(i);
				if (rawMessage == null) {
					logger.warn("TestServlet: NULL message at index " + i);
					continue;
				}
				final MAPMessageType type = rawMessage.getMessageType();

				switch (messageType) {
				case Begin:
					switch (type) {
					case processUnstructuredSSRequest_Request:
						ProcessUnstructuredSSRequest processUnstructuredSSRequest = (ProcessUnstructuredSSRequest) rawMessage;
						CBSDataCodingScheme cbsDataCodingScheme = processUnstructuredSSRequest.getDataCodingScheme();
						if (logger.isInfoEnabled()) {
							logger.info("Received ProcessUnstructuredSSRequestIndication USSD String="
									+ processUnstructuredSSRequest.getUSSDString().getString(null));
						}
						if (dialogId != null) {
							long reqInvokeId = processUnstructuredSSRequest.getInvokeId();
							logger.info("TestServlet: dialogId=" + dialogId + ", request invokeId=" + reqInvokeId);
							dialogInvokeIds.put(dialogId, reqInvokeId);
							dialogMenuLevels.put(dialogId, 1);
						}

						// Load test mode: return ProcessUnstructuredSSResponse immediately to close dialog
						// instead of UnstructuredSSRequest which requires multi-turn interaction
						ussdStr = new USSDStringImpl("USSD String : Hello World",
								cbsDataCodingScheme, null);
						ProcessUnstructuredSSResponse processUnstructuredSSResponse = new ProcessUnstructuredSSResponseImpl(
								cbsDataCodingScheme, ussdStr);
						long respInvokeId = processUnstructuredSSRequest.getInvokeId();
						logger.info("TestServlet: setting response invokeId=" + respInvokeId);
						processUnstructuredSSResponse.setInvokeId(respInvokeId);

						original.reset();
						original.setUserObject("DialogId=" + dialogId + ", LoadTest=1");
						original.setTCAPMessageType(MessageType.End);
						original.addMAPMessage(processUnstructuredSSResponse);
						original.close(false);

						byte[] data = serializeResponse(original);

						response.setContentType("text/xml;charset=UTF-8");
						response.getOutputStream().write(data);
						response.flushBuffer();

						break;
					default:
						logger.error("Received Dialog BEGIN but message is not ProcessUnstructuredSSRequestIndication. Message="
								+ rawMessage);
						response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unexpected message type in BEGIN dialog");
						break;
					}

					break;
				case Continue:
					switch (type) {
					case unstructuredSSRequest_Response:
						UnstructuredSSResponse unstructuredSSResponse = (UnstructuredSSResponse) rawMessage;

						CBSDataCodingScheme cbsDataCodingScheme = unstructuredSSResponse.getDataCodingScheme();

						Long invokeIdObj = (dialogId != null) ? dialogInvokeIds.get(dialogId) : null;
						long invokeId = invokeIdObj != null ? invokeIdObj : 0;

						USSDString ussdStringObj = unstructuredSSResponse.getUSSDString();
						String ussdString = null;
						if (ussdStringObj != null) {
							ussdString = ussdStringObj.getString(null);
						}

						// Per-dialog state for multi-level menu (HttpSession is not usable because
						// HTTP Client RA is stateless and doesn't send cookies).
						Integer menuLevel = (dialogId != null) ? dialogMenuLevels.get(dialogId) : null;
						if (menuLevel == null) menuLevel = 1;

						logger.info("Received UnstructuredSSResponse DialogId=" + dialogId
								+ " menuLevel=" + menuLevel + " ussdString=" + ussdString + " invokeId=" + invokeId);

						String responseText;
						boolean endDialog = false;

						if (menuLevel == 1) {
							if ("1".equals(ussdString)) {
								responseText = "Balance: $100.50\n1. Back to main menu";
							} else if ("2".equals(ussdString)) {
								responseText = "Texts: 50 remaining\n1. Back to main menu";
							} else {
								responseText = "Invalid choice. Try again.\n1. Balance\n2. Texts Remaining";
							}
							if ("1".equals(ussdString) || "2".equals(ussdString)) {
								if (dialogId != null) dialogMenuLevels.put(dialogId, 2);
							}
						} else if (menuLevel == 2) {
							if ("1".equals(ussdString)) {
								responseText = "Thank You!";
								endDialog = true;
							} else {
								responseText = "Invalid choice.\n1. Back to main menu";
							}
						} else {
							responseText = "Thank You!";
							endDialog = true;
						}

						cbsDataCodingScheme = new CBSDataCodingSchemeImpl(0x0f);
						ussdStr = new USSDStringImpl(responseText, null, null);

						original.reset();
						original.setUserObject("DialogId=" + dialogId + " | MenuLevel=" + menuLevel + " | Choice=" + ussdString);

						if (endDialog) {
							ProcessUnstructuredSSResponse processUnstructuredSSResponse = new ProcessUnstructuredSSResponseImpl(
									cbsDataCodingScheme, ussdStr);
							processUnstructuredSSResponse.setInvokeId(invokeId);
							original.setTCAPMessageType(MessageType.End);
							original.addMAPMessage(processUnstructuredSSResponse);
							original.close(false);
						} else {
							UnstructuredSSRequest nextRequest = new UnstructuredSSRequestImpl(
									cbsDataCodingScheme, ussdStr, null, null);
							original.setTCAPMessageType(MessageType.Continue);
							original.setCustomInvokeTimeOut(25000);
							original.addMAPMessage(nextRequest);
						}

						byte[] data = serializeResponse(original);

						response.setContentType("text/xml;charset=UTF-8");
						response.getOutputStream().write(data);
						response.flushBuffer();

						if (endDialog && dialogId != null) {
							dialogInvokeIds.remove(dialogId);
							dialogMenuLevels.remove(dialogId);
						}
						break;
					default:
						logger.error("Received Dialog CONTINUE but message is not UnstructuredSSResponseIndication. Message="
								+ rawMessage);
						response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unexpected message type in CONTINUE dialog");
						break;
					}

					break;

				case Abort:
					if (dialogId != null) {
						dialogInvokeIds.remove(dialogId);
						dialogMenuLevels.remove(dialogId);
					}
					break;
				default:
					logger.error("Unknown message type: " + messageType);
					response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unknown message type: " + messageType);
					break;
				}

			}

		} catch (MAPException e) {
			logger.error("MAPException while processing received XML", e);
			response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "MAP Error: " + e.getMessage());
		} catch (Exception e) {
			logger.error("Error while processing received XML", e);
			response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error: " + e.getMessage());
		}
	}

	/**
	 * Serialize XmlMAPDialog response. First tries Jackson XML serialization.
	 * If that fails (e.g. StackOverflowError due to complex object graphs),
	 * falls back to manual XML generation compatible with EventsSerializeFactory.
	 */
	private byte[] serializeResponse(XmlMAPDialog dialog) throws Exception {
		try {
			return factory.serialize(dialog);
		} catch (Exception | StackOverflowError e) {
			logger.warn("Jackson XML serialization failed, using manual XML fallback. Error: " + e.getMessage());
			return buildManualXmlResponse(dialog);
		}
	}

	/**
	 * Build XML response manually to avoid Jackson serialization issues.
	 * Format is compatible with EventsSerializeFactory.deserialize().
	 */
	private byte[] buildManualXmlResponse(XmlMAPDialog dialog) {
		StringBuilder sb = new StringBuilder();
		sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		sb.append("<dialog");

		// Message type
		if (dialog.getTCAPMessageType() != null) {
			sb.append(" type=\"").append(escapeXml(dialog.getTCAPMessageType().name())).append("\"");
		}

		// Application context
		if (dialog.getApplicationContext() != null) {
			MAPApplicationContext appCtx = dialog.getApplicationContext();
			sb.append(" appCntx=\"").append(escapeXml(appCtx.getApplicationContextName().name()))
			  .append("_").append(escapeXml(appCtx.getApplicationContextVersion().name())).append("\"");
		}

		// Local and remote IDs
		if (dialog.getLocalDialogId() != null) {
			sb.append(" localId=\"").append(dialog.getLocalDialogId()).append("\"");
		}
		if (dialog.getRemoteDialogId() != null) {
			sb.append(" remoteId=\"").append(dialog.getRemoteDialogId()).append("\"");
		}

		// Network ID
		sb.append(" networkId=\"").append(dialog.getNetworkId()).append("\"");

		// Map messages size
		FastList<MAPMessage> messages = dialog.getMAPMessages();
		int msgSize = messages != null ? messages.size() : 0;
		sb.append(" mapMessagesSize=\"").append(msgSize).append("\"");

		// Return message on error
		sb.append(" returnMessageOnError=\"").append(dialog.getReturnMessageOnError()).append("\"");

		// User object
		if (dialog.getUserObject() != null) {
			sb.append(" userObject=\"").append(escapeXml(dialog.getUserObject().toString())).append("\"");
		}

		// Custom invoke timeout
		if (dialog.getCustomInvokeTimeOut() != null) {
			sb.append(" customInvokeTimeout=\"").append(dialog.getCustomInvokeTimeOut()).append("\"");
		}

		// Prearranged end
		if (dialog.getPrearrangedEnd() != null) {
			sb.append(" prearrangedEnd=\"").append(dialog.getPrearrangedEnd()).append("\"");
		}

		sb.append(">\n");

		// SCCP addresses
		if (dialog.getLocalAddress() != null) {
			sb.append("  ").append(sccpAddressToXml("localAddress", dialog.getLocalAddress())).append("\n");
		}
		if (dialog.getRemoteAddress() != null) {
			sb.append("  ").append(sccpAddressToXml("remoteAddress", dialog.getRemoteAddress())).append("\n");
		}

		// Destination and origination references
		if (dialog.getReceivedDestReference() != null) {
			sb.append("  ").append(addressStringToXml("destinationReference", dialog.getReceivedDestReference())).append("\n");
		}
		if (dialog.getReceivedOrigReference() != null) {
			sb.append("  ").append(addressStringToXml("originationReference", dialog.getReceivedOrigReference())).append("\n");
		}

		// MAP messages
		if (messages != null) {
			for (int i = 0; i < messages.size(); i++) {
				MAPMessage msg = messages.get(i);
				if (msg != null) {
					sb.append("  ").append(mapMessageToXml(msg)).append("\n");
				}
			}
		}

		sb.append("</dialog>");

		return sb.toString().getBytes(StandardCharsets.UTF_8);
	}

	private String sccpAddressToXml(String elementName, SccpAddress addr) {
		StringBuilder sb = new StringBuilder();
		sb.append("<").append(elementName);
		if (addr.getSignalingPointCode() != 0) {
			sb.append(" pc=\"").append(addr.getSignalingPointCode()).append("\"");
		}
		if (addr.getSubsystemNumber() != -1) {
			sb.append(" ssn=\"").append(addr.getSubsystemNumber()).append("\"");
		}
		sb.append(">");
		if (addr.getAddressIndicator() != null) {
			sb.append("<ai value=\"").append(addr.getAddressIndicator().getValue()).append("\"/>");
		}
		if (addr.getGlobalTitle() != null) {
			sb.append("<gt class=\"").append(escapeXml(addr.getGlobalTitle().getClass().getSimpleName()))
			  .append("\"/>");
		}
		sb.append("</").append(elementName).append(">");
		return sb.toString();
	}

	private String addressStringToXml(String elementName, org.restcomm.protocols.ss7.map.api.primitives.AddressString addr) {
		StringBuilder sb = new StringBuilder();
		sb.append("<").append(elementName);
		if (addr.getAddressNature() != null) {
			sb.append(" nai=\"").append(escapeXml(addr.getAddressNature().name())).append("\"");
		}
		if (addr.getNumberingPlan() != null) {
			sb.append(" npi=\"").append(escapeXml(addr.getNumberingPlan().name())).append("\"");
		}
		sb.append(" number=\"").append(escapeXml(addr.getAddress())).append("\"/>");
		return sb.toString();
	}

	private String mapMessageToXml(MAPMessage msg) {
		StringBuilder sb = new StringBuilder();
		switch (msg.getMessageType()) {
		case unstructuredSSRequest_Request:
			UnstructuredSSRequest req = (UnstructuredSSRequest) msg;
			sb.append("<unstructuredSSRequest_Request");
			if (msg.getInvokeId() != 0) {
				sb.append(" invokeId=\"").append(msg.getInvokeId()).append("\"");
			}
			if (req.getDataCodingScheme() != null) {
				sb.append(" dataCodingScheme=\"").append(((CBSDataCodingSchemeImpl)req.getDataCodingScheme()).getCode()).append("\"");
			}
			if (req.getUSSDString() != null) {
				try {
					sb.append(" string=\"").append(escapeXml(req.getUSSDString().getString(null))).append("\"");
				} catch (MAPException e) {
					sb.append(" string=\"\"");
				}
			}
			sb.append("/>");
			break;
		case processUnstructuredSSRequest_Response:
			ProcessUnstructuredSSResponse resp = (ProcessUnstructuredSSResponse) msg;
			sb.append("<processUnstructuredSSRequest_Response");
			if (msg.getInvokeId() != 0) {
				sb.append(" invokeId=\"").append(msg.getInvokeId()).append("\"");
			}
			if (resp.getDataCodingScheme() != null) {
				sb.append(" dataCodingScheme=\"").append(((CBSDataCodingSchemeImpl)resp.getDataCodingScheme()).getCode()).append("\"");
			}
			if (resp.getUSSDString() != null) {
				try {
					sb.append(" string=\"").append(escapeXml(resp.getUSSDString().getString(null))).append("\"");
				} catch (MAPException e) {
					sb.append(" string=\"\"");
				}
			}
			sb.append("/>");
			break;
		default:
			sb.append("<!-- Unsupported message type: ").append(msg.getMessageType()).append(" -->");
			break;
		}
		return sb.toString();
	}

	private String escapeXml(String input) {
		if (input == null) return "";
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < input.length(); i++) {
			char c = input.charAt(i);
			switch (c) {
			case '<': sb.append("&lt;"); break;
			case '>': sb.append("&gt;"); break;
			case '&': sb.append("&amp;"); break;
			case '"': sb.append("&quot;"); break;
			case '\'': sb.append("&apos;"); break;
			case '\n': sb.append("&#10;"); break;
			case '\r': sb.append("&#13;"); break;
			default:
				if (c < 0x20 && c != '\t') {
					sb.append("&#").append((int)c).append(";");
				} else {
					sb.append(c);
				}
			}
		}
		return sb.toString();
	}

	private USSDString ussdStr = null;
}
