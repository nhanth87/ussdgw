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

import java.nio.charset.Charset;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Codec for the gRPC request/response envelope. The gRPC method transports an opaque {@code bytes}
 * body whose content is a compact JSON envelope:
 *
 * <pre>
 * {"sessionId":"...","correlationId":"...","push":false,"networkId":0,"payloadB64":"..."}
 * </pre>
 *
 * Using a JSON-over-bytes envelope keeps the Java RA free of protobuf code generation while staying
 * trivially interoperable with the Python AS (which uses the same shape). Teams that prefer
 * protobuf can swap the marshaller without touching the SBB layer.
 */
public final class GrpcEnvelopeCodec {

    private static final Charset UTF8 = Charset.forName("UTF-8");

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

    public static byte[] encodeRequest(GrpcRequest request) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        appendString(sb, F_SESSION_ID, request.getSessionId()).append(',');
        appendString(sb, F_CORRELATION_ID, request.getCorrelationId()).append(',');
        appendString(sb, F_REQUEST_ID, request.getRequestId()).append(',');
        appendRaw(sb, F_PUSH, Boolean.toString(request.isPush())).append(',');
        appendRaw(sb, F_NETWORK_ID, Integer.toString(request.getNetworkId())).append(',');
        appendString(sb, F_PAYLOAD, encodePayload(request.getPayload()));
        sb.append('}');
        return sb.toString().getBytes(UTF8);
    }

    public static byte[] encodeResponse(boolean success, String correlationId, byte[] payload,
            String error) {
        return encodeResponse(success, correlationId, null, payload, error);
    }

    public static byte[] encodeResponse(boolean success, String correlationId, String requestId,
            byte[] payload, String error) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        appendRaw(sb, F_SUCCESS, Boolean.toString(success)).append(',');
        appendString(sb, F_CORRELATION_ID, correlationId).append(',');
        appendString(sb, F_REQUEST_ID, requestId).append(',');
        appendString(sb, F_PAYLOAD, encodePayload(payload)).append(',');
        appendString(sb, F_ERROR, error);
        sb.append('}');
        return sb.toString().getBytes(UTF8);
    }

    public static Map<String, String> decode(byte[] bytes) {
        Map<String, String> out = new HashMap<String, String>();
        if (bytes == null || bytes.length == 0) {
            return out;
        }
        String json = new String(bytes, UTF8).trim();
        if (json.startsWith("{")) {
            json = json.substring(1);
        }
        if (json.endsWith("}")) {
            json = json.substring(0, json.length() - 1);
        }
        int i = 0;
        int n = json.length();
        while (i < n) {
            int keyStart = json.indexOf('"', i);
            if (keyStart < 0) {
                break;
            }
            int keyEnd = json.indexOf('"', keyStart + 1);
            if (keyEnd < 0) {
                break;
            }
            String key = json.substring(keyStart + 1, keyEnd);
            int colon = json.indexOf(':', keyEnd + 1);
            if (colon < 0) {
                break;
            }
            int valStart = colon + 1;
            while (valStart < n && Character.isWhitespace(json.charAt(valStart))) {
                valStart++;
            }
            String value;
            if (valStart < n && json.charAt(valStart) == '"') {
                int valEnd = valStart + 1;
                StringBuilder vb = new StringBuilder();
                while (valEnd < n) {
                    char c = json.charAt(valEnd);
                    if (c == '\\' && valEnd + 1 < n) {
                        vb.append(json.charAt(valEnd + 1));
                        valEnd += 2;
                        continue;
                    }
                    if (c == '"') {
                        break;
                    }
                    vb.append(c);
                    valEnd++;
                }
                value = vb.toString();
                i = valEnd + 1;
            } else {
                int valEnd = valStart;
                while (valEnd < n && json.charAt(valEnd) != ',') {
                    valEnd++;
                }
                value = json.substring(valStart, valEnd).trim();
                if ("null".equals(value)) {
                    value = null;
                }
                i = valEnd;
            }
            out.put(key, value);
            int comma = json.indexOf(',', i);
            if (comma < 0) {
                break;
            }
            i = comma + 1;
        }
        return out;
    }

    public static byte[] decodePayload(String b64) {
        if (b64 == null || b64.isEmpty()) {
            return new byte[0];
        }
        return Base64.getDecoder().decode(b64);
    }

    private static String encodePayload(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return "";
        }
        return Base64.getEncoder().encodeToString(payload);
    }

    private static StringBuilder appendString(StringBuilder sb, String key, String value) {
        sb.append('"').append(key).append('"').append(':');
        if (value == null) {
            sb.append("null");
        } else {
            sb.append('"').append(escape(value)).append('"');
        }
        return sb;
    }

    private static StringBuilder appendRaw(StringBuilder sb, String key, String rawValue) {
        sb.append('"').append(key).append('"').append(':').append(rawValue);
        return sb;
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }
}
