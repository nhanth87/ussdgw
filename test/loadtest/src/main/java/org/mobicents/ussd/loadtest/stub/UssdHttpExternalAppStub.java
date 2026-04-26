package org.mobicents.ussd.loadtest.stub;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;

import org.mobicents.ussdgateway.EventsSerializeFactory;
import org.mobicents.ussdgateway.XmlMAPDialog;
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
import org.restcomm.protocols.ss7.map.service.supplementary.UnstructuredSSRequestImpl;
import org.restcomm.protocols.ss7.map.service.supplementary.ProcessUnstructuredSSResponseImpl;
import org.restcomm.protocols.ss7.tcap.api.MessageType;

import java.io.ByteArrayInputStream;
import java.util.List;

/**
 * Standalone External HTTP Application Stub for USSD Gateway integration testing.
 *
 * <p>This stub simulates an external HTTP application that USSD Gateway calls via
 * HTTP Client RA. It receives serialized XmlMAPDialog over HTTP POST, processes
 * USSD business logic, and returns the response XmlMAPDialog.
 *
 * <p>Usage:
 * <pre>
 *   java -cp ussd-loadtest-10k.jar org.mobicents.ussd.loadtest.stub.UssdHttpExternalAppStub [port]
 * </pre>
 *
 * <p>In USSD Gateway routing rule, configure URL pointing to this stub:
 * <pre>
 *   &lt;ruleurl&gt;http://localhost:8082/ussd&lt;/ruleurl&gt;
 * </pre>
 *
 * <p>Endpoints:
 * <ul>
 *   <li>POST /ussd - Accepts XmlMAPDialog, returns USSD response</li>
 *   <li>GET  /health - Returns "OK"</li>
 * </ul>
 */
public class UssdHttpExternalAppStub {

    private static final org.apache.log4j.Logger logger =
            org.apache.log4j.Logger.getLogger(UssdHttpExternalAppStub.class);

    private static final String[] SUPPORTED_CONTENT_TYPES = {
            "text/xml", "application/xml"
    };

    private final int port;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel channel;

    public UssdHttpExternalAppStub(int port) {
        this.port = port;
    }

    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast(new HttpServerCodec());
                        p.addLast(new HttpObjectAggregator(65536));
                        p.addLast(new ExternalAppHandler());
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true);

        channel = b.bind(port).sync().channel();
        logger.info("USSD External HTTP App Stub started on port " + port);
        System.out.println("[UssdHttpExternalAppStub] Started on http://localhost:" + port + "/ussd");
        System.out.println("[INFO] Configure USSD Gateway routing rule URL to: http://localhost:" + port + "/ussd");
    }

    public void stop() {
        if (channel != null) {
            channel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        logger.info("USSD External HTTP App Stub stopped.");
    }

    /**
     * Netty handler for external USSD app logic.
     */
    private static class ExternalAppHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

        private final EventsSerializeFactory factory = new EventsSerializeFactory();

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
            String uri = req.uri();
            HttpMethod method = req.method();

            if (uri.equals("/health") && method == HttpMethod.GET) {
                sendTextResponse(ctx, HttpResponseStatus.OK, "OK");
                return;
            }

            if (uri.equals("/ussd") && method == HttpMethod.POST) {
                handleUssdRequest(ctx, req);
                return;
            }

            sendTextResponse(ctx, HttpResponseStatus.NOT_FOUND, "Not Found: " + uri);
        }

        private void handleUssdRequest(ChannelHandlerContext ctx, FullHttpRequest req) {
            try {
                // Validate content type
                String contentType = req.headers().get(HttpHeaderNames.CONTENT_TYPE);
                if (contentType == null || !isSupportedContentType(contentType)) {
                    sendTextResponse(ctx, HttpResponseStatus.UNSUPPORTED_MEDIA_TYPE,
                            "Expected Content-Type: text/xml or application/xml");
                    return;
                }

                ByteBuf content = req.content();
                byte[] body = new byte[content.readableBytes()];
                content.readBytes(body);

                if (body.length == 0) {
                    sendTextResponse(ctx, HttpResponseStatus.BAD_REQUEST, "Empty request body");
                    return;
                }

                XmlMAPDialog dialog = factory.deserialize(new ByteArrayInputStream(body));

                if (logger.isDebugEnabled()) {
                    logger.debug("Received dialog from USSD Gateway: " + dialog);
                }

                List<MAPMessage> messages = dialog.getMAPMessages();
                MessageType tcapType = dialog.getTCAPMessageType();

                if (messages == null || messages.isEmpty()) {
                    sendTextResponse(ctx, HttpResponseStatus.BAD_REQUEST, "No MAP messages in dialog");
                    return;
                }

                MAPMessage msg = messages.get(0);
                MAPMessageType msgType = msg.getMessageType();

                byte[] responseData;

                if (tcapType == MessageType.Begin && msgType == MAPMessageType.processUnstructuredSSRequest_Request) {
                    // Initial USSD request from subscriber → respond with menu (Continue dialog)
                    ProcessUnstructuredSSRequest request = (ProcessUnstructuredSSRequest) msg;
                    CBSDataCodingScheme dcs = request.getDataCodingScheme();

                    // Simulate business logic: show menu
                    USSDString ussdStr = new USSDStringImpl(
                            "USSD Menu:\n1. Check Balance\n2. Data Usage\n3. Exit", dcs, null);
                    UnstructuredSSRequest menuRequest = new UnstructuredSSRequestImpl(dcs, ussdStr, null, null);

                    dialog.reset();
                    dialog.setTCAPMessageType(MessageType.Continue);
                    dialog.setCustomInvokeTimeOut(25000);
                    dialog.addMAPMessage(menuRequest);

                    responseData = factory.serialize(dialog);

                    if (logger.isInfoEnabled()) {
                        logger.info("Sent USSD menu response for: " + request.getUSSDString().getString(null));
                    }

                } else if (tcapType == MessageType.Continue && msgType == MAPMessageType.unstructuredSSRequest_Response) {
                    // User selected an option → respond with final message (End dialog)
                    UnstructuredSSResponse response = (UnstructuredSSResponse) msg;

                    USSDString userChoice = response.getUSSDString();
                    String choice = userChoice != null ? userChoice.getString(null) : "";

                    String responseText;
                    if (choice.contains("1")) {
                        responseText = "Your balance is $10.50";
                    } else if (choice.contains("2")) {
                        responseText = "Data used: 2.3GB / 5GB";
                    } else {
                        responseText = "Thank you for using our service!";
                    }

                    CBSDataCodingScheme dcs = new CBSDataCodingSchemeImpl(0x0f);
                    USSDString ussdStr = new USSDStringImpl(responseText, null, null);
                    ProcessUnstructuredSSResponse procResp = new ProcessUnstructuredSSResponseImpl(dcs, ussdStr);
                    procResp.setInvokeId(response.getInvokeId());

                    dialog.reset();
                    dialog.setTCAPMessageType(MessageType.End);
                    dialog.addMAPMessage(procResp);
                    dialog.close(false);

                    responseData = factory.serialize(dialog);

                    if (logger.isInfoEnabled()) {
                        logger.info("Sent final USSD response for choice: " + choice);
                    }

                } else {
                    logger.warn("Unexpected TCAP=" + tcapType + " MAP=" + msgType);
                    sendTextResponse(ctx, HttpResponseStatus.BAD_REQUEST,
                            "Unexpected message: TCAP=" + tcapType + " MAP=" + msgType);
                    return;
                }

                FullHttpResponse resp = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.OK,
                        Unpooled.wrappedBuffer(responseData)
                );
                resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/xml; charset=utf-8");
                resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, responseData.length);
                ctx.writeAndFlush(resp);

            } catch (Exception e) {
                logger.error("Error processing USSD request from gateway", e);
                sendTextResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                        "Error: " + e.getMessage());
            }
        }

        private boolean isSupportedContentType(String contentType) {
            String lower = contentType.toLowerCase();
            for (String supported : SUPPORTED_CONTENT_TYPES) {
                if (lower.contains(supported)) {
                    return true;
                }
            }
            return false;
        }

        private void sendTextResponse(ChannelHandlerContext ctx, HttpResponseStatus status, String text) {
            byte[] data = text.getBytes(CharsetUtil.UTF_8);
            FullHttpResponse resp = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(data)
            );
            resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
            resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, data.length);
            ctx.writeAndFlush(resp);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logger.error("Exception in handler", cause);
            ctx.close();
        }
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8082;

        UssdHttpExternalAppStub stub = new UssdHttpExternalAppStub(port);
        stub.start();

        System.out.println("Press Enter to stop...");
        System.in.read();

        stub.stop();
    }
}
