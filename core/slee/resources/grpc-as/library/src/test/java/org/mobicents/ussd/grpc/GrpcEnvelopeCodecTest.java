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
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.nio.charset.Charset;
import java.util.Map;

import org.testng.annotations.Test;

/**
 * Unit tests for {@link GrpcEnvelopeCodec} and {@link GrpcResponseRegistry}.
 */
public class GrpcEnvelopeCodecTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    // =================== Codec Tests ===================

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
        byte[] decoded = GrpcEnvelopeCodec.decodePayload(env.get(GrpcEnvelopeCodec.F_PAYLOAD));
        assertEquals(new String(decoded, UTF8), "<dialog>hello</dialog>");
    }

    @Test
    public void requestWithRequestId() {
        byte[] payload = "<dialog>data</dialog>".getBytes(UTF8);
        GrpcRequest req = new GrpcRequest("host:port", "sess-2", "corr-2", "req-abc", true, 0, payload);
        byte[] encoded = GrpcEnvelopeCodec.encodeRequest(req);

        Map<String, String> env = GrpcEnvelopeCodec.decode(encoded);
        assertEquals(env.get(GrpcEnvelopeCodec.F_REQUEST_ID), "req-abc");
        assertEquals(env.get(GrpcEnvelopeCodec.F_PUSH), "true");
    }

    @Test
    public void responseRoundTripSuccess() {
        byte[] payload = "<dialog>reply</dialog>".getBytes(UTF8);
        byte[] encoded = GrpcEnvelopeCodec.encodeResponse(true, "corr-2", payload, null);
        Map<String, String> env = GrpcEnvelopeCodec.decode(encoded);
        assertEquals(env.get(GrpcEnvelopeCodec.F_SUCCESS), "true");
        assertEquals(env.get(GrpcEnvelopeCodec.F_CORRELATION_ID), "corr-2");
        String decoded = new String(GrpcEnvelopeCodec.decodePayload(env.get(GrpcEnvelopeCodec.F_PAYLOAD)), UTF8);
        assertEquals(decoded, "<dialog>reply</dialog>");
    }

    @Test
    public void responseWithRequestId() {
        byte[] encoded = GrpcEnvelopeCodec.encodeResponse(true, "corr-3", "req-xyz", new byte[0], null);
        Map<String, String> env = GrpcEnvelopeCodec.decode(encoded);
        assertEquals(env.get(GrpcEnvelopeCodec.F_REQUEST_ID), "req-xyz");
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
        Map<String, String> env = GrpcEnvelopeCodec.decode(GrpcEnvelopeCodec.encodeRequest(req));
        assertEquals(env.get(GrpcEnvelopeCodec.F_PUSH), "true");
        assertEquals(GrpcEnvelopeCodec.decodePayload(env.get(GrpcEnvelopeCodec.F_PAYLOAD)).length, 0);
    }

    @Test
    public void nullPayloadIsSafe() {
        GrpcRequest req = new GrpcRequest("t", "s", "c", false, 1, null);
        Map<String, String> env = GrpcEnvelopeCodec.decode(GrpcEnvelopeCodec.encodeRequest(req));
        assertEquals(env.get(GrpcEnvelopeCodec.F_NETWORK_ID), "1");
        assertEquals(GrpcEnvelopeCodec.decodePayload(env.get(GrpcEnvelopeCodec.F_PAYLOAD)).length, 0);
    }

    @Test
    public void escapesSpecialChars() {
        GrpcRequest req = new GrpcRequest("t", "a\"b\\c", "c", false, 0, new byte[0]);
        Map<String, String> env = GrpcEnvelopeCodec.decode(GrpcEnvelopeCodec.encodeRequest(req));
        assertEquals(env.get(GrpcEnvelopeCodec.F_SESSION_ID), "a\"b\\c");
    }

    @Test
    public void unicodeInSessionId() {
        String unicode = "user-\u00e9\u00e0\u1ea1";
        GrpcRequest req = new GrpcRequest("t", unicode, "c", false, 0, new byte[0]);
        Map<String, String> env = GrpcEnvelopeCodec.decode(GrpcEnvelopeCodec.encodeRequest(req));
        assertEquals(env.get(GrpcEnvelopeCodec.F_SESSION_ID), unicode);
    }

    @Test
    public void unicodeInPayload() {
        String xml = "<msg>\u00e9\u00e0\u1ea1</msg>";
        GrpcRequest req = new GrpcRequest("t", "s", "c", false, 0, xml.getBytes(UTF8));
        byte[] decoded = GrpcEnvelopeCodec.decodePayload(
                GrpcEnvelopeCodec.decode(GrpcEnvelopeCodec.encodeRequest(req)).get(GrpcEnvelopeCodec.F_PAYLOAD));
        assertEquals(new String(decoded, UTF8), xml);
    }

    @Test
    public void nullFields() {
        Map<String, String> env = GrpcEnvelopeCodec.decode(
                GrpcEnvelopeCodec.encodeResponse(true, null, (String) null, null, null));
        assertEquals(env.get(GrpcEnvelopeCodec.F_SUCCESS), "true");
    }

    @Test
    public void decodeNullBytes() {
        assertTrue(GrpcEnvelopeCodec.decode(null).isEmpty());
    }

    @Test
    public void decodeEmptyBytes() {
        assertTrue(GrpcEnvelopeCodec.decode(new byte[0]).isEmpty());
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void decodeMalformedJson() {
        GrpcEnvelopeCodec.decode("not-json".getBytes(UTF8));
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void decodeTruncatedJson() {
        GrpcEnvelopeCodec.decode("{\"key\":\"value".getBytes(UTF8));
    }

    @Test
    public void decodePayloadNull() {
        assertEquals(GrpcEnvelopeCodec.decodePayload(null).length, 0);
    }

    @Test
    public void decodePayloadEmpty() {
        assertEquals(GrpcEnvelopeCodec.decodePayload("").length, 0);
    }

    @Test
    public void largePayloadRoundTrip() {
        byte[] largePayload = new byte[65536];
        for (int i = 0; i < largePayload.length; i++) {
            largePayload[i] = (byte) (i % 256);
        }
        GrpcRequest req = new GrpcRequest("h:p", "s", "c", false, 0, largePayload);
        Map<String, String> env = GrpcEnvelopeCodec.decode(GrpcEnvelopeCodec.encodeRequest(req));
        byte[] decoded = GrpcEnvelopeCodec.decodePayload(env.get(GrpcEnvelopeCodec.F_PAYLOAD));
        assertEquals(decoded.length, largePayload.length);
        for (int i = 0; i < largePayload.length; i++) {
            assertEquals(decoded[i], largePayload[i]);
        }
    }

    @Test
    public void booleanValuesAreStrings() {
        Map<String, String> env = GrpcEnvelopeCodec.decode(
                GrpcEnvelopeCodec.encodeResponse(true, "c1", null, null));
        assertEquals(env.get(GrpcEnvelopeCodec.F_SUCCESS), "true");
    }

    @Test
    public void invalidBase64Throws() {
        try {
            GrpcEnvelopeCodec.decodePayload("!!!invalid!!!");
            fail("Expected exception");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    // =================== Registry Tests ===================

    @Test
    public void registryHandoffRoundTrip() {
        GrpcResponseRegistry reg = GrpcResponseRegistry.getInstance();
        reg.put(GrpcResponse.ok("rk-1", "x".getBytes(UTF8)));
        GrpcResponse got = reg.poll("rk-1");
        assertTrue(got != null && got.isSuccess());
        assertFalse(reg.poll("rk-1") != null);
    }

    @Test
    public void registryPollNonExistent() {
        assertNull(GrpcResponseRegistry.getInstance().poll("no-such-key"));
    }

    @Test
    public void registryPutAndRemove() {
        GrpcResponseRegistry reg = GrpcResponseRegistry.getInstance();
        reg.put(GrpcResponse.ok("rk-2", "y".getBytes(UTF8)));
        reg.remove("rk-2");
        assertNull(reg.poll("rk-2"));
    }

    @Test
    public void registryPutNullResponse() {
        GrpcResponseRegistry.getInstance().put(null);
        // should not throw
    }

    @Test
    public void registryPutNullCorrelationId() {
        GrpcResponseRegistry reg = GrpcResponseRegistry.getInstance();
        reg.put(GrpcResponse.ok(null, "z".getBytes(UTF8)));
        assertEquals(reg.size(), 0);
    }

    @Test
    public void registryMultiplePuts() {
        GrpcResponseRegistry reg = GrpcResponseRegistry.getInstance();
        int before = reg.size();
        reg.put(GrpcResponse.ok("m1", "a".getBytes(UTF8)));
        reg.put(GrpcResponse.ok("m2", "b".getBytes(UTF8)));
        reg.put(GrpcResponse.ok("m3", "c".getBytes(UTF8)));
        assertEquals(reg.size(), before + 3);
        assertNotNull(reg.poll("m1"));
        assertNotNull(reg.poll("m2"));
        assertNotNull(reg.poll("m3"));
    }

    @Test
    public void registryConcurrentPutPoll() throws Exception {
        final GrpcResponseRegistry reg = GrpcResponseRegistry.getInstance();
        final String key = "concurrent-" + System.nanoTime();
        Thread producer = new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException e) { }
            reg.put(GrpcResponse.ok(key, "hello".getBytes(UTF8)));
        });
        producer.start();
        GrpcResponse result = null;
        for (int i = 0; i < 200 && result == null; i++) {
            result = reg.poll(key);
            if (result == null) Thread.sleep(10);
        }
        producer.join(2000);
        assertNotNull(result, "Concurrent put/poll failed");
        assertTrue(result.isSuccess());
    }
}
