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
 * Standalone HTTP test stub for USSD Gateway load testing.
 *
 * <p>Replaces the need for WildFly + ussdhttpdemo.war during HTTP load tests.
 * This lightweight Netty-based server directly handles XmlMAPDialog serialization
 * and responds like the original TestServlet, but without any JEE container overhead.
 *
 * <p>Usage:
 * <pre>
 *   java -cp ussd-loadtest-10k.jar org.mobicents.ussd.loadtest.stub.UssdHttpTestStub [port]
 * </pre>
 *
 * <p>Endpoints:
 * <ul>
 *   <li>POST /test - Accepts XmlMAPDialog, returns appropriate USSD response</li>
 *   <li>GET  /health - Returns "OK"</li>
 * </ul>
 */
public class UssdHttpTestStub {

    private static final org.apache.log4j.Logger logger =
            org.apache.log4j.Logger.getLogger(UssdHttpTestStub.class);

    private final int port;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel channel;

    public UssdHttpTestStub(int port) {
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
                        p.addLast(new UssdStubHandler());
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true);

        channel = b.bind(port).sync().channel();
        logger.info("USSD HTTP Test Stub started on port " + port);
        System.out.println("[UssdHttpTestStub] Started on http://localhost:" + port + "/test");
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
        logger.info("USSD HTTP Test Stub stopped.");
    }

    /**
     * Netty channel handler for USSD stub logic.
     */
    private static class UssdStubHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

        private final EventsSerializeFactory factory = new EventsSerializeFactory();

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
            String uri = req.uri();
            HttpMethod method = req.method();

            if (uri.equals("/health") && method == HttpMethod.GET) {
                sendTextResponse(ctx, HttpResponseStatus.OK, "OK");
                return;
            }

            if (uri.equals("/test") && method == HttpMethod.POST) {
                handleUssdRequest(ctx, req);
                return;
            }

            sendTextResponse(ctx, HttpResponseStatus.NOT_FOUND, "Not Found: " + uri);
        }

        private void handleUssdRequest(ChannelHandlerContext ctx, FullHttpRequest req) {
            try {
                ByteBuf content = req.content();
                byte[] body = new byte[content.readableBytes()];
                content.readBytes(body);

                XmlMAPDialog dialog = factory.deserialize(new ByteArrayInputStream(body));

                if (logger.isDebugEnabled()) {
                    logger.debug("Received dialog: " + dialog);
                }

                List<MAPMessage> messages = dialog.getMAPMessages();
                MessageType tcapType = dialog.getTCAPMessageType();

                if (messages == null || messages.isEmpty()) {
                    sendTextResponse(ctx, HttpResponseStatus.BAD_REQUEST, "No MAP messages");
                    return;
                }

                MAPMessage msg = messages.get(0);
                MAPMessageType msgType = msg.getMessageType();

                byte[] responseData;

                if (tcapType == MessageType.Begin && msgType == MAPMessageType.processUnstructuredSSRequest_Request) {
                    // Initial request → respond with UnstructuredSSRequest (Continue dialog)
                    ProcessUnstructuredSSRequest request = (ProcessUnstructuredSSRequest) msg;
                    CBSDataCodingScheme dcs = request.getDataCodingScheme();

                    USSDString ussdStr = new USSDStringImpl(
                            "USSD String : Hello World\n 1. Balance\n 2. Texts Remaining", dcs, null);
                    UnstructuredSSRequest ussdRequest = new UnstructuredSSRequestImpl(dcs, ussdStr, null, null);

                    dialog.reset();
                    dialog.setTCAPMessageType(MessageType.Continue);
                    dialog.setCustomInvokeTimeOut(25000);
                    dialog.addMAPMessage(ussdRequest);

                    responseData = factory.serialize(dialog);

                } else if (tcapType == MessageType.Continue && msgType == MAPMessageType.unstructuredSSRequest_Response) {
                    // User response → respond with ProcessUnstructuredSSResponse (End dialog)
                    UnstructuredSSResponse response = (UnstructuredSSResponse) msg;

                    CBSDataCodingScheme dcs = new CBSDataCodingSchemeImpl(0x0f);
                    USSDString ussdStr = new USSDStringImpl("Thank You!", null, null);
                    ProcessUnstructuredSSResponse procResp = new ProcessUnstructuredSSResponseImpl(dcs, ussdStr);
                    procResp.setInvokeId(response.getInvokeId());

                    dialog.reset();
                    dialog.setTCAPMessageType(MessageType.End);
                    dialog.addMAPMessage(procResp);
                    dialog.close(false);

                    responseData = factory.serialize(dialog);

                } else {
                    sendTextResponse(ctx, HttpResponseStatus.BAD_REQUEST,
                            "Unexpected TCAP=" + tcapType + " MAP=" + msgType);
                    return;
                }

                FullHttpResponse resp = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.OK,
                        Unpooled.wrappedBuffer(responseData)
                );
                resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/xml; charset=utf-8");
                resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, responseData.length);
                ctx.writeAndFlush(resp);

            } catch (Exception e) {
                logger.error("Error processing USSD request", e);
                sendTextResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Error: " + e.getMessage());
            }
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
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;

        UssdHttpTestStub stub = new UssdHttpTestStub(port);
        stub.start();

        System.out.println("Press Enter to stop...");
        System.in.read();

        stub.stop();
    }
}
