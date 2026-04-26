package org.mobicents.ussd.loadtest.stub;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.sctp.SctpMessage;
import io.netty.channel.sctp.nio.NioSctpServerChannel;

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
 * Standalone SCTP test stub for USSD Gateway load testing.
 *
 * <p>Runs directly on SCTP transport (no HTTP, no WildFly required).
 * Receives serialized XmlMAPDialog over SCTP, processes USSD logic,
 * and returns the response over the same SCTP association.
 *
 * <p>This is closer to production SS7/SCTP behavior than HTTP mode.
 *
 * <p>Usage:
 * <pre>
 *   java -cp ussd-loadtest-10k.jar org.mobicents.ussd.loadtest.stub.UssdSctpTestStub [port]
 * </pre>
 */
public class UssdSctpTestStub {

    private static final org.apache.log4j.Logger logger =
            org.apache.log4j.Logger.getLogger(UssdSctpTestStub.class);

    private final int port;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel channel;

    public UssdSctpTestStub(int port) {
        this.port = port;
    }

    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(NioSctpServerChannel.class)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast(new UssdSctpHandler());
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true);

        channel = b.bind(port).sync().channel();
        logger.info("USSD SCTP Test Stub started on port " + port);
        System.out.println("[UssdSctpTestStub] Started on SCTP port " + port);
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
        logger.info("USSD SCTP Test Stub stopped.");
    }

    /**
     * SCTP channel handler for USSD stub logic.
     */
    private static class UssdSctpHandler extends SimpleChannelInboundHandler<SctpMessage> {

        private final EventsSerializeFactory factory = new EventsSerializeFactory();

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, SctpMessage msg) {
            try {
                ByteBuf content = msg.content();
                byte[] body = new byte[content.readableBytes()];
                content.readBytes(body);

                if (body.length == 0) {
                    logger.warn("Received empty SCTP message");
                    return;
                }

                XmlMAPDialog dialog = factory.deserialize(new ByteArrayInputStream(body));

                if (logger.isDebugEnabled()) {
                    logger.debug("Received dialog over SCTP: " + dialog);
                }

                List<MAPMessage> messages = dialog.getMAPMessages();
                MessageType tcapType = dialog.getTCAPMessageType();

                if (messages == null || messages.isEmpty()) {
                    logger.warn("No MAP messages in received dialog");
                    return;
                }

                MAPMessage mapMsg = messages.get(0);
                MAPMessageType msgType = mapMsg.getMessageType();

                byte[] responseData;

                if (tcapType == MessageType.Begin && msgType == MAPMessageType.processUnstructuredSSRequest_Request) {
                    // Initial USSD request → respond with UnstructuredSSRequest (Continue)
                    ProcessUnstructuredSSRequest request = (ProcessUnstructuredSSRequest) mapMsg;
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
                    // User response → respond with ProcessUnstructuredSSResponse (End)
                    UnstructuredSSResponse response = (UnstructuredSSResponse) mapMsg;

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
                    logger.warn("Unexpected TCAP=" + tcapType + " MAP=" + msgType);
                    return;
                }

                ByteBuf respBuf = Unpooled.wrappedBuffer(responseData);
                SctpMessage resp = new SctpMessage(
                        msg.protocolIdentifier(), msg.streamIdentifier(), respBuf);
                ctx.writeAndFlush(resp);

                if (logger.isDebugEnabled()) {
                    logger.debug("Sent SCTP response, bytes=" + responseData.length);
                }

            } catch (Exception e) {
                logger.error("Error processing SCTP message", e);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logger.error("Exception in SCTP handler", cause);
            ctx.close();
        }
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8081;

        UssdSctpTestStub stub = new UssdSctpTestStub(port);
        stub.start();

        System.out.println("Press Enter to stop...");
        System.in.read();

        stub.stop();
    }
}
