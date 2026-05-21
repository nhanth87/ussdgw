/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2017, Telestax Inc and individual contributors
 * by the @authors tag.
 */
package org.mobicents.applications.ussd.examples.http;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.AsyncContext;
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
import org.restcomm.protocols.ss7.map.api.MAPException;
import org.restcomm.protocols.ss7.map.api.MAPMessage;
import org.restcomm.protocols.ss7.map.api.MAPMessageType;
import org.restcomm.protocols.ss7.map.api.datacoding.CBSDataCodingScheme;
import org.restcomm.protocols.ss7.map.api.primitives.USSDString;
import org.restcomm.protocols.ss7.map.api.service.supplementary.ProcessUnstructuredSSRequest;
import org.restcomm.protocols.ss7.map.api.service.supplementary.ProcessUnstructuredSSResponse;
import org.restcomm.protocols.ss7.map.api.service.supplementary.UnstructuredSSRequest;
import org.restcomm.protocols.ss7.map.api.service.supplementary.UnstructuredSSResponse;
import org.restcomm.protocols.ss7.map.datacoding.CBSDataCodingSchemeImpl;
import org.restcomm.protocols.ss7.map.primitives.USSDStringImpl;
import org.restcomm.protocols.ss7.map.service.supplementary.ProcessUnstructuredSSResponseImpl;
import org.restcomm.protocols.ss7.map.service.supplementary.UnstructuredSSRequestImpl;
import org.restcomm.protocols.ss7.tcap.api.MessageType;

/**
 * USSD HTTP TestServlet - Optimized for high TPS load testing.
 * 
 * OPTIMIZATIONS:
 * - CachedThreadPool: auto-scales threads, no queue buildup
 * - Debug-guarded logging: zero-cost when disabled
 * - No raw XML string allocation in hot path
 * - Fast path for load-test Begin dialogs
 * - Minimal object allocation per request
 */
public class TestServlet extends HttpServlet {

    private static final Logger logger = Logger.getLogger(TestServlet.class);

    private EventsSerializeFactory factory = null;

    // Async processing executor - cached thread pool auto-scales for burst load
    private ExecutorService asyncExecutor = null;
    private static final int ASYNC_TIMEOUT_SECONDS = 30;

    // Track pending async requests for monitoring
    private final AtomicInteger pendingAsyncRequests = new AtomicInteger(0);

    // Per-dialog state for multi-turn menu (not needed for single-turn load test)
    private static final ConcurrentHashMap<Long, Long> dialogInvokeIds = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Integer> dialogMenuLevels = new ConcurrentHashMap<>();

    // Reusable StringBuilder per thread to reduce allocation
    private static final ThreadLocal<StringBuilder> tlStringBuilder = ThreadLocal.withInitial(() -> new StringBuilder(4096));

    @Override
    public void init() {
        factory = new EventsSerializeFactory();
        // CachedThreadPool: creates threads on demand, reuses idle threads
        asyncExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "TestServlet-Async-" + System.currentTimeMillis());
            t.setDaemon(true);
            return t;
        });
        logger.info("TestServlet initialized with CachedThreadPool (auto-scaling)");
    }

    @Override
    public void destroy() {
        if (asyncExecutor != null) {
            logger.info("Shutting down TestServlet async executor...");
            asyncExecutor.shutdown();
            try {
                if (!asyncExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    asyncExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                asyncExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            logger.info("TestServlet async executor shutdown complete");
        }
    }

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        PrintWriter out = response.getWriter();
        out.println("<html><body><h1>Hello USSD Demo Get</h1></body></html>");
    }

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        final AsyncContext asyncContext = request.startAsync();
        asyncContext.setTimeout(ASYNC_TIMEOUT_SECONDS * 1000L);
        
        final int pendingCount = pendingAsyncRequests.incrementAndGet();
        if (logger.isDebugEnabled()) {
            logger.debug("JENNY-TESTSERVLET-ASYNC-START: pendingRequests=" + pendingCount);
        }
        
        asyncExecutor.submit(() -> {
            try {
                processRequest(asyncContext);
            } finally {
                int remaining = pendingAsyncRequests.decrementAndGet();
                if (logger.isDebugEnabled()) {
                    logger.debug("JENNY-TESTSERVLET-ASYNC-COMPLETE: remainingPending=" + remaining);
                }
            }
        });
    }
    
    private void processRequest(AsyncContext asyncContext) {
        HttpServletRequest request = (HttpServletRequest) asyncContext.getRequest();
        HttpServletResponse response = (HttpServletResponse) asyncContext.getResponse();
        
        try {
            if (!asyncContext.getRequest().isAsyncStarted()) {
                if (logger.isDebugEnabled()) {
                    logger.debug("JENNY-TESTSERVLET-ASYNC-ABORT: async context already completed");
                }
                return;
            }
            
            // Read request body efficiently
            int contentLength = request.getContentLength();
            if (contentLength <= 0) {
                sendErrorResponse(asyncContext, HttpServletResponse.SC_BAD_REQUEST, "Empty request body");
                return;
            }
            
            byte[] rawBytes = readRequestBody(request.getInputStream(), contentLength);
            
            // Only log raw XML in debug mode (expensive)
            if (logger.isDebugEnabled()) {
                logger.debug("JENNY-TESTSERVLET-RAW-XML: " + new String(rawBytes, StandardCharsets.UTF_8));
            }
            
            XmlMAPDialog original = factory.deserialize(new java.io.ByteArrayInputStream(rawBytes));
            Long dialogId = original != null ? original.getLocalDialogId() : null;
            if (logger.isDebugEnabled()) {
                logger.debug("JENNY-TESTSERVLET-DIALOG: dialogId=" + dialogId);
            }

            if (original == null) {
                logger.error("JENNY-TESTSERVLET-ERROR: deserialized dialog is null");
                sendErrorResponse(asyncContext, HttpServletResponse.SC_BAD_REQUEST, 
                    "Could not deserialize XML dialog");
                return;
            }

            final FastList<MAPMessage> capMessages = original.getMAPMessages();
            MessageType messageType = original.getTCAPMessageType();

            if (capMessages == null || capMessages.size() == 0) {
                if (logger.isDebugEnabled()) {
                    logger.debug("JENNY-TESTSERVLET-WARN: No MAP messages in dialog");
                }
                sendErrorResponse(asyncContext, HttpServletResponse.SC_BAD_REQUEST, 
                    "No MAP messages in dialog");
                return;
            }

            for (int i = 0; i < capMessages.size(); i++) {
                final MAPMessage rawMessage = capMessages.get(i);
                if (rawMessage == null) {
                    continue;
                }
                final MAPMessageType type = rawMessage.getMessageType();

                switch (messageType) {
                case Begin:
                    handleBeginDialog(asyncContext, original, rawMessage, dialogId, type);
                    break;
                case Continue:
                    handleContinueDialog(asyncContext, original, rawMessage, dialogId, type);
                    break;
                case Abort:
                    if (dialogId != null) {
                        dialogInvokeIds.remove(dialogId);
                        dialogMenuLevels.remove(dialogId);
                    }
                    asyncContext.complete();
                    break;
                default:
                    logger.error("JENNY-TESTSERVLET-ERROR: Unknown message type: " + messageType);
                    sendErrorResponse(asyncContext, HttpServletResponse.SC_BAD_REQUEST, 
                        "Unknown message type: " + messageType);
                    break;
                }
            }
        } catch (MAPException e) {
            logger.error("JENNY-TESTSERVLET-ERROR: MAPException", e);
            sendErrorResponse(asyncContext, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, 
                "MAP Error: " + e.getMessage());
        } catch (Exception e) {
            logger.error("JENNY-TESTSERVLET-ERROR: Error processing XML", e);
            sendErrorResponse(asyncContext, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, 
                "Error: " + e.getMessage());
        }
    }
    
    /**
     * Efficiently read request body into byte array.
     */
    private byte[] readRequestBody(ServletInputStream is, int contentLength) throws IOException {
        byte[] buffer = new byte[contentLength];
        int totalRead = 0;
        while (totalRead < contentLength) {
            int read = is.read(buffer, totalRead, contentLength - totalRead);
            if (read < 0) break;
            totalRead += read;
        }
        return buffer;
    }
    
    private void handleBeginDialog(AsyncContext asyncContext, XmlMAPDialog original, 
            MAPMessage rawMessage, Long dialogId, MAPMessageType type) throws Exception {
        
        if (type == MAPMessageType.processUnstructuredSSRequest_Request) {
            ProcessUnstructuredSSRequest req = (ProcessUnstructuredSSRequest) rawMessage;
            CBSDataCodingScheme cbsDataCodingScheme = req.getDataCodingScheme();
            
            if (logger.isDebugEnabled()) {
                logger.debug("JENNY-TESTSERVLET-RECV: ProcessUnstructuredSSRequest dialogId=" + dialogId);
            }
            
            if (dialogId != null) {
                dialogInvokeIds.put(dialogId, req.getInvokeId());
                dialogMenuLevels.put(dialogId, 1);
            }

            // Load test mode: return ProcessUnstructuredSSResponse immediately
            USSDString ussdStr = new USSDStringImpl("USSD String : Hello World", cbsDataCodingScheme, null);
            ProcessUnstructuredSSResponse resp = new ProcessUnstructuredSSResponseImpl(
                    cbsDataCodingScheme, ussdStr);
            resp.setInvokeId(req.getInvokeId());

            original.reset();
            original.setUserObject("DialogId=" + dialogId + ", LoadTest=1");
            original.setTCAPMessageType(MessageType.End);
            original.addMAPMessage(resp);
            original.close(false);

            // Use optimized serialization
            byte[] data = serializeResponseFast(original);
            sendSuccessResponse(asyncContext, data, dialogId);
        } else {
            logger.error("JENNY-TESTSERVLET-ERROR: Unexpected message in BEGIN dialog: " + rawMessage);
            sendErrorResponse(asyncContext, HttpServletResponse.SC_BAD_REQUEST, 
                "Unexpected message type in BEGIN dialog");
        }
    }
    
    private void handleContinueDialog(AsyncContext asyncContext, XmlMAPDialog original,
            MAPMessage rawMessage, Long dialogId, MAPMessageType type) throws Exception {
        
        if (type == MAPMessageType.unstructuredSSRequest_Response) {
            UnstructuredSSResponse unstructuredSSResponse = (UnstructuredSSResponse) rawMessage;
            CBSDataCodingScheme cbsDataCodingScheme = unstructuredSSResponse.getDataCodingScheme();

            Long invokeIdObj = (dialogId != null) ? dialogInvokeIds.get(dialogId) : null;
            long invokeId = invokeIdObj != null ? invokeIdObj : 0;

            USSDString ussdStringObj = unstructuredSSResponse.getUSSDString();
            String ussdString = null;
            if (ussdStringObj != null) {
                ussdString = ussdStringObj.getString(null);
            }

            Integer menuLevel = (dialogId != null) ? dialogMenuLevels.get(dialogId) : null;
            if (menuLevel == null) menuLevel = 1;

            if (logger.isDebugEnabled()) {
                logger.debug("JENNY-TESTSERVLET-RECV: Continue dialogId=" + dialogId 
                    + " menuLevel=" + menuLevel + " ussdString=" + ussdString);
            }

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
            USSDString ussdStr = new USSDStringImpl(responseText, null, null);

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

            byte[] data = serializeResponseFast(original);
            sendSuccessResponse(asyncContext, data, dialogId);

            if (endDialog && dialogId != null) {
                dialogInvokeIds.remove(dialogId);
                dialogMenuLevels.remove(dialogId);
            }
        } else {
            logger.error("JENNY-TESTSERVLET-ERROR: Unexpected message in CONTINUE dialog");
            sendErrorResponse(asyncContext, HttpServletResponse.SC_BAD_REQUEST, 
                "Unexpected message type in CONTINUE dialog");
        }
    }
    
    private void sendSuccessResponse(AsyncContext asyncContext, byte[] data, Long dialogId) throws IOException {
        HttpServletResponse response = (HttpServletResponse) asyncContext.getResponse();
        
        try {
            response.setContentType("text/xml;charset=UTF-8");
            response.setContentLength(data.length);
            response.getOutputStream().write(data);
            response.getOutputStream().flush();
            response.flushBuffer();
            
            if (logger.isDebugEnabled()) {
                logger.debug("JENNY-TESTSERVLET-SEND-SUCCESS: dialogId=" + dialogId 
                    + " responseLength=" + data.length + " status=200");
            }
        } catch (IOException e) {
            logger.error("JENNY-TESTSERVLET-SEND-ERROR: dialogId=" + dialogId + " error=" + e.getMessage(), e);
            throw e;
        } finally {
            asyncContext.complete();
        }
    }
    
    private void sendErrorResponse(AsyncContext asyncContext, int statusCode, String message) {
        try {
            HttpServletResponse response = (HttpServletResponse) asyncContext.getResponse();
            response.setContentType("text/plain;charset=UTF-8");
            response.sendError(statusCode, message);
            response.flushBuffer();
            if (logger.isDebugEnabled()) {
                logger.debug("JENNY-TESTSERVLET-SEND-ERROR: status=" + statusCode + " message=" + message);
            }
        } catch (IOException e) {
            logger.error("JENNY-TESTSERVLET-SEND-ERROR: Failed to send error response", e);
        } finally {
            asyncContext.complete();
        }
    }

    /**
     * Fast serialization: try factory first, fallback to manual XML.
     * For high TPS, factory.serialize() should be fast enough if Jackson is tuned.
     */
    private byte[] serializeResponseFast(XmlMAPDialog dialog) throws Exception {
        try {
            return factory.serialize(dialog);
        } catch (Exception | StackOverflowError e) {
            logger.warn("Jackson XML serialization failed, using manual fallback: " + e.getMessage());
            return buildManualXmlResponse(dialog);
        }
    }

    /**
     * Build XML response manually to avoid Jackson serialization issues.
     * Uses ThreadLocal StringBuilder to reduce allocation.
     */
    private byte[] buildManualXmlResponse(XmlMAPDialog dialog) {
        StringBuilder sb = tlStringBuilder.get();
        sb.setLength(0); // Reuse without allocating new
        
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<dialog");

        if (dialog.getTCAPMessageType() != null) {
            sb.append(" type=\"").append(escapeXml(dialog.getTCAPMessageType().name())).append("\"");
        }

        if (dialog.getApplicationContext() != null) {
            MAPApplicationContext appCtx = dialog.getApplicationContext();
            sb.append(" appCntx=\"").append(escapeXml(appCtx.getApplicationContextName().name()))
              .append("_").append(escapeXml(appCtx.getApplicationContextVersion().name())).append("\"");
        }

        if (dialog.getLocalDialogId() != null) {
            sb.append(" localId=\"").append(dialog.getLocalDialogId()).append("\"");
        }
        if (dialog.getRemoteDialogId() != null) {
            sb.append(" remoteId=\"").append(dialog.getRemoteDialogId()).append("\"");
        }

        sb.append(" networkId=\"").append(dialog.getNetworkId()).append("\"");

        FastList<MAPMessage> messages = dialog.getMAPMessages();
        int msgSize = messages != null ? messages.size() : 0;
        sb.append(" mapMessagesSize=\"").append(msgSize).append("\"");
        sb.append(" returnMessageOnError=\"").append(dialog.getReturnMessageOnError()).append("\"");

        if (dialog.getUserObject() != null) {
            sb.append(" userObject=\"").append(escapeXml(dialog.getUserObject().toString())).append("\"");
        }

        if (dialog.getCustomInvokeTimeOut() != null) {
            sb.append(" customInvokeTimeout=\"").append(dialog.getCustomInvokeTimeOut()).append("\"");
        }

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

        if (dialog.getReceivedDestReference() != null) {
            sb.append("  ").append(addressStringToXml("destinationReference", dialog.getReceivedDestReference())).append("\n");
        }
        if (dialog.getReceivedOrigReference() != null) {
            sb.append("  ").append(addressStringToXml("originationReference", dialog.getReceivedOrigReference())).append("\n");
        }

        sb.append("  <errComponents/>\n");
        sb.append("  <rejectComponents/>\n");

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

    private String sccpAddressToXml(String elementName, org.restcomm.protocols.ss7.sccp.parameter.SccpAddress addr) {
        StringBuilder sb = tlStringBuilder.get();
        int savedLen = sb.length();
        
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
            sb.append("<gt class=\"").append(escapeXml(addr.getGlobalTitle().getClass().getSimpleName())).append("\"/>");
        }
        sb.append("</").append(elementName).append(">");
        
        String result = sb.substring(savedLen);
        sb.setLength(savedLen); // Restore for reuse
        return result;
    }

    private String addressStringToXml(String elementName, org.restcomm.protocols.ss7.map.api.primitives.AddressString addr) {
        StringBuilder sb = tlStringBuilder.get();
        int savedLen = sb.length();
        
        sb.append("<").append(elementName);
        if (addr.getAddressNature() != null) {
            sb.append(" nai=\"").append(escapeXml(addr.getAddressNature().name())).append("\"");
        }
        if (addr.getNumberingPlan() != null) {
            sb.append(" npi=\"").append(escapeXml(addr.getNumberingPlan().name())).append("\"");
        }
        sb.append(" number=\"").append(escapeXml(addr.getAddress())).append("\"/>");
        
        String result = sb.substring(savedLen);
        sb.setLength(savedLen);
        return result;
    }

    private String mapMessageToXml(MAPMessage msg) {
        StringBuilder sb = tlStringBuilder.get();
        int savedLen = sb.length();
        
        switch (msg.getMessageType()) {
        case unstructuredSSRequest_Request:
            org.restcomm.protocols.ss7.map.api.service.supplementary.UnstructuredSSRequest req = 
                (org.restcomm.protocols.ss7.map.api.service.supplementary.UnstructuredSSRequest) msg;
            sb.append("<unstructuredSSRequest_Request");
            if (msg.getInvokeId() != 0) {
                sb.append(" invokeId=\"").append(msg.getInvokeId()).append("\"");
            }
            if (req.getDataCodingScheme() != null) {
                sb.append(" dataCodingScheme=\"").append(((CBSDataCodingSchemeImpl)req.getDataCodingScheme()).getCode()).append("\"");
            }
            sb.append(">");
            if (req.getUSSDString() != null) {
                try {
                    sb.append("<string>").append(escapeXml(req.getUSSDString().getString(null))).append("</string>");
                } catch (MAPException e) {
                    sb.append("<string></string>");
                }
            }
            sb.append("</unstructuredSSRequest_Request>");
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
            sb.append(">");
            if (resp.getUSSDString() != null) {
                try {
                    sb.append("<string>").append(escapeXml(resp.getUSSDString().getString(null))).append("</string>");
                } catch (MAPException e) {
                    sb.append("<string></string>");
                }
            }
            sb.append("</processUnstructuredSSRequest_Response>");
            break;
        default:
            sb.append("<!-- Unsupported: ").append(msg.getMessageType()).append(" -->");
            break;
        }
        
        String result = sb.substring(savedLen);
        sb.setLength(savedLen);
        return result;
    }

    private String escapeXml(String input) {
        if (input == null) return "";
        StringBuilder sb = tlStringBuilder.get();
        int savedLen = sb.length();
        
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
        
        String result = sb.substring(savedLen);
        sb.setLength(savedLen);
        return result;
    }
}
