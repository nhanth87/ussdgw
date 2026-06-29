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

import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

/**
 * Codec for the gRPC request/response envelope. Uses Gson for safe, standards-compliant JSON
 * encoding/decoding replacing the previous hand-rolled parser.
 *
 * <p>The gRPC method transports an opaque {@code bytes} body whose content is a compact JSON
 * envelope:
 *
 * <pre>
 * {"sessionId":"...","correlationId":"...","push":false,"networkId":0,"payloadB64":"..."}
 * </pre>
 *
 * <p>Refactored 2026-06-28: replaced hand-rolled JSON parser with Gson for correctness and safety.
 *
 * @author USSD Gateway Team
 */
public final class GrpcEnvelopeCodec {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Type STRING_MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    public static final String F_SESSION_ID = "sessionId";
    public static final String F_CORRELATION_ID = "correlationId";
    public static final String F_REQUEST_ID = "requestId";
    public static final String F_PUSH = "push";
    public static final String F_NETWORK_ID = "networkId";
    public static final String F_PAYLOAD = "payloadB64";
    public static final String F_SUCCESS = "success";
    public static final String F_ERROR = "error";

    private GrpcEnvelopeCodec() {
    }

    /**
     * Encodes a {@link GrpcRequest} into a UTF-8 JSON byte array using Gson.
     */
    public static byte[] encodeRequest(GrpcRequest request) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<String, Object>();
        map.put(F_SESSION_ID, request.getSessionId());
        map.put(F_CORRELATION_ID, request.getCorrelationId());
        map.put(F_REQUEST_ID, request.getRequestId());
        map.put(F_PUSH, request.isPush());
        map.put(F_NETWORK_ID, request.getNetworkId());
        map.put(F_PAYLOAD, encodePayload(request.getPayload()));
        return GSON.toJson(map).getBytes(UTF8);
    }

    /**
     * Encodes a response (short form, no requestId) into a UTF-8 JSON byte array.
     */
    public static byte[] encodeResponse(boolean success, String correlationId, byte[] payload,
            String error) {
        return encodeResponse(success, correlationId, null, payload, error);
    }

    /**
     * Encodes a response (full form, with requestId) into a UTF-8 JSON byte array.
     */
    public static byte[] encodeResponse(boolean success, String correlationId, String requestId,
            byte[] payload, String error) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<String, Object>();
        map.put(F_SUCCESS, success);
        map.put(F_CORRELATION_ID, correlationId);
        map.put(F_REQUEST_ID, requestId);
        map.put(F_PAYLOAD, encodePayload(payload));
        map.put(F_ERROR, error);
        return GSON.toJson(map).getBytes(UTF8);
    }

    /**
     * Decodes a UTF-8 JSON envelope byte array into a field-to-value map using Gson.
     *
     * @param bytes the raw JSON bytes
     * @return a map of field name to string value; never null
     * @throws IllegalArgumentException if the data is not valid JSON
     */
    public static Map<String, String> decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new LinkedHashMap<String, String>();
        }
        String json = new String(bytes, UTF8);
        try {
            Map<String, String> result = GSON.fromJson(json, STRING_MAP_TYPE);
            return result != null ? result : new LinkedHashMap<String, String>();
        } catch (com.google.gson.JsonSyntaxException e) {
            throw new IllegalArgumentException("Malformed gRPC envelope JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Decodes a Base64-encoded payload string into raw bytes.
     */
    public static byte[] decodePayload(String b64) {
        if (b64 == null || b64.isEmpty()) {
            return new byte[0];
        }
        return Base64.getDecoder().decode(b64);
    }

    /**
     * Base64-encodes a byte array for transport in the JSON envelope.
     */
    private static String encodePayload(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return "";
        }
        return Base64.getEncoder().encodeToString(payload);
    }
}
