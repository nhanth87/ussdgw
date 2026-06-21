"""
gRPC request/response envelope codec for the USSD Gateway gRPC AS RA.

The gateway's gRPC method ``ussd.UssdApplicationService/Process`` transports an opaque ``bytes``
body whose content is a compact JSON envelope. This module mirrors the Java
``GrpcEnvelopeCodec`` so the Python Application Server interoperates without protobuf code
generation.

Request envelope:
    {"sessionId": "...", "correlationId": "...", "requestId": "...", "push": false,
     "networkId": 0, "payloadB64": "..."}

Response envelope:
    {"success": true, "correlationId": "...", "requestId": "...", "payloadB64": "...", "error": null}

``payloadB64`` is the Base64 of the serialized ``XmlMAPDialog`` exchanged with the gateway.
``requestId`` is the per-request id the gateway generates; the AS MUST echo it on its response so
the Virtual Session Bridge can reconcile a late (post-gate) response exactly-once (RFC §4/§13).
"""

import base64
import json


def decode_request(raw: bytes) -> dict:
    """Decode an inbound request envelope into a dict with a decoded ``payload`` (bytes)."""
    env = json.loads(raw.decode("utf-8")) if raw else {}
    payload_b64 = env.get("payloadB64") or ""
    env["payload"] = base64.b64decode(payload_b64) if payload_b64 else b""
    return env


def encode_response(correlation_id: str, payload: bytes, success: bool = True,
                    error: str = None, request_id: str = None) -> bytes:
    """Encode a response envelope to bytes (echoing ``request_id`` for bridge reconciliation)."""
    out = {
        "success": success,
        "correlationId": correlation_id,
        "requestId": request_id,
        "payloadB64": base64.b64encode(payload).decode("ascii") if payload else "",
        "error": error,
    }
    return json.dumps(out).encode("utf-8")


def encode_request(session_id: str, correlation_id: str, payload: bytes,
                   push: bool = False, network_id: int = 0, request_id: str = None) -> bytes:
    """Encode a request envelope (used by the load-test client)."""
    out = {
        "sessionId": session_id,
        "correlationId": correlation_id,
        "requestId": request_id,
        "push": push,
        "networkId": network_id,
        "payloadB64": base64.b64encode(payload).decode("ascii") if payload else "",
    }
    return json.dumps(out).encode("utf-8")


def decode_response(raw: bytes) -> dict:
    env = json.loads(raw.decode("utf-8")) if raw else {}
    payload_b64 = env.get("payloadB64") or ""
    env["payload"] = base64.b64decode(payload_b64) if payload_b64 else b""
    return env


FULL_METHOD = "ussd.UssdApplicationService/Process"
SERVICE_NAME = "ussd.UssdApplicationService"
METHOD_NAME = "Process"
