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

package org.mobicents.ussd.grpc;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.nio.charset.Charset;
import java.util.Map;

import org.testng.annotations.Test;

public class GrpcEnvelopeCodecTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    @Test
    public void requestRoundTrip() {
        byte[] payload = "<dialog>hello</dialog>".getBytes(UTF8);
        GrpcRequest req = new GrpcRequest("localhost:8443", "sess-1", "corr-1", false, 3, payload);
        byte[] encoded = GrpcEnvelopeCodec.encodeRequest(req);

        Map<String, String> env = GrpcEnvelopeCodec.decode(encoded);
        assertEquals(env.get(GrpcEnvelopeCodec.F_SESSION_ID), "sess-1");
        assertEquals(env.get(GrpcEnvelopeCodec.F_CORRELATION_ID), "corr-1");
        assertEquals(env.get(GrpcEnvelopeCodec.F_PUSH), "false");
        assertEquals(env.get(GrpcEnvelopeCodec.F_NETWORK_ID), "3");
        byte[] decodedPayload = GrpcEnvelopeCodec.decodePayload(env.get(GrpcEnvelopeCodec.F_PAYLOAD));
        assertEquals(new String(decodedPayload, UTF8), "<dialog>hello</dialog>");
    }

    @Test
    public void responseRoundTripSuccess() {
        byte[] payload = "<dialog>reply</dialog>".getBytes(UTF8);
        byte[] encoded = GrpcEnvelopeCodec.encodeResponse(true, "corr-2", payload, null);
        Map<String, String> env = GrpcEnvelopeCodec.decode(encoded);
        assertEquals(env.get(GrpcEnvelopeCodec.F_SUCCESS), "true");
        assertEquals(env.get(GrpcEnvelopeCodec.F_CORRELATION_ID), "corr-2");
        assertEquals(new String(GrpcEnvelopeCodec.decodePayload(env.get(GrpcEnvelopeCodec.F_PAYLOAD)), UTF8),
                "<dialog>reply</dialog>");
    }

    @Test
    public void responseError() {
        byte[] encoded = GrpcEnvelopeCodec.encodeResponse(false, "corr-3", null, "boom");
        Map<String, String> env = GrpcEnvelopeCodec.decode(encoded);
        assertEquals(env.get(GrpcEnvelopeCodec.F_SUCCESS), "false");
        assertEquals(env.get(GrpcEnvelopeCodec.F_ERROR), "boom");
    }

    @Test
    public void emptyPayloadIsSafe() {
        GrpcRequest req = new GrpcRequest("t", "s", "c", true, 0, new byte[0]);
        byte[] encoded = GrpcEnvelopeCodec.encodeRequest(req);
        Map<String, String> env = GrpcEnvelopeCodec.decode(encoded);
        assertEquals(env.get(GrpcEnvelopeCodec.F_PUSH), "true");
        assertEquals(GrpcEnvelopeCodec.decodePayload(env.get(GrpcEnvelopeCodec.F_PAYLOAD)).length, 0);
    }

    @Test
    public void escapesSpecialCharsInIds() {
        GrpcRequest req = new GrpcRequest("t", "a\"b\\c", "c", false, 0, new byte[0]);
        byte[] encoded = GrpcEnvelopeCodec.encodeRequest(req);
        Map<String, String> env = GrpcEnvelopeCodec.decode(encoded);
        assertEquals(env.get(GrpcEnvelopeCodec.F_SESSION_ID), "a\"b\\c");
    }

    @Test
    public void registryHandoffRoundTrip() {
        GrpcResponseRegistry reg = GrpcResponseRegistry.getInstance();
        reg.put(GrpcResponse.ok("rk-1", "x".getBytes(UTF8)));
        GrpcResponse got = reg.poll("rk-1");
        assertTrue(got != null && got.isSuccess());
        // second poll must be empty (consumed)
        assertFalse(reg.poll("rk-1") != null);
    }
}
