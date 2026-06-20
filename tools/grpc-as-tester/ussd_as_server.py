#!/usr/bin/env python3
"""
USSD Application Server over gRPC - test server for the USSD Gateway gRPC AS Resource Adaptor.

The gateway acts as the gRPC client (like the HTTP client RA) and calls the unary method
``ussd.UssdApplicationService/Process`` with a JSON envelope carrying the serialized USSD dialogue.
This server:

  * implements a configurable multi-menu USSD flow (pull / interactive push of menus),
  * applies an adaptive/random processing delay (default 1-100 ms) to emulate a real AS so the
    gateway's Virtual Session Bridge and adaptive timeout can be exercised,
  * is interoperable without protobuf code generation (raw-bytes gRPC method + JSON envelope),
  * keeps per-session menu state keyed by the session id (which the gateway unifies with the
    bridge correlation id - one id is enough across MO/NI; see the design doc).

Usage:
    pip install -r requirements.txt
    python3 ussd_as_server.py --port 8443 --min-delay 1 --max-delay 100 --menu-config menu_config.json

Run with --help for all options.
"""

import argparse
import json
import logging
import random
import threading
import time
from concurrent import futures

import grpc

import ussd_envelope as env
import ussd_xml as uxml

LOG = logging.getLogger("ussd-as")


class MenuEngine:
    """Drives the configurable USSD menu tree and keeps per-session state."""

    def __init__(self, config: dict):
        self.root = config["root"]
        self.nodes = config["nodes"]
        self._sessions = {}
        self._lock = threading.Lock()

    def handle(self, session_id: str, parsed: dict):
        """
        Returns (text, end) for the given inbound parsed dialogue.
        On the initial request we show the root menu; on each reply we advance the tree.
        """
        msg_type = parsed["message_type"]
        if msg_type == "process_request":
            node_name = self.root
            with self._lock:
                self._sessions[session_id] = node_name
            node = self.nodes[node_name]
            return node["text"], bool(node.get("final"))

        if msg_type == "ussd_response":
            with self._lock:
                node_name = self._sessions.get(session_id, self.root)
            node = self.nodes.get(node_name, self.nodes[self.root])
            choice = (parsed.get("ussd_string") or "").strip()
            options = node.get("options", {})
            next_name = options.get(choice) or options.get("*")
            if next_name is None:
                # invalid choice: re-show the current menu
                return "Invalid choice.\n" + node["text"], False
            if next_name == "__end__":
                with self._lock:
                    self._sessions.pop(session_id, None)
                return "Thank you for using Jenny USSD. Goodbye!", True
            nxt = self.nodes[next_name]
            with self._lock:
                self._sessions[session_id] = next_name
            is_final = bool(nxt.get("final"))
            if is_final:
                with self._lock:
                    self._sessions.pop(session_id, None)
            return nxt["text"], is_final

        # Unknown inbound: end politely
        return "Session ended.", True


class UssdProcessor:
    """gRPC handler implementing the raw-bytes Process method."""

    def __init__(self, engine: MenuEngine, min_delay_ms: int, max_delay_ms: int):
        self.engine = engine
        self.min_delay_ms = min_delay_ms
        self.max_delay_ms = max_delay_ms

    def process(self, request_bytes: bytes, context) -> bytes:
        try:
            envelope = env.decode_request(request_bytes)
        except Exception as e:  # noqa: BLE001 - any malformed body must yield a graceful error
            LOG.warning("Bad request envelope: %s", e)
            return env.encode_response(None, b"", success=False, error="bad-envelope")

        session_id = envelope.get("sessionId") or envelope.get("correlationId") or "unknown"
        correlation_id = envelope.get("correlationId") or session_id

        # adaptive / random AS processing latency
        if self.max_delay_ms > 0:
            lo = max(0, self.min_delay_ms)
            hi = max(lo, self.max_delay_ms)
            time.sleep(random.randint(lo, hi) / 1000.0)

        parsed = uxml.parse_request(envelope.get("payload", b""))
        text, end = self.engine.handle(session_id, parsed)

        response_xml = uxml.build_response(
            message_type=parsed["message_type"],
            text=text,
            invoke_id=parsed.get("invoke_id", 0),
            end=end,
            user_object="sessionId=%s" % session_id,
            network_id=parsed.get("network_id", 0),
        )
        return env.encode_response(correlation_id, response_xml, success=True)


def build_server(processor: UssdProcessor, port: int, workers: int) -> grpc.Server:
    handler = grpc.unary_unary_rpc_method_handler(
        processor.process,
        request_deserializer=None,  # pass raw bytes through
        response_serializer=None,
    )
    generic = grpc.method_handlers_generic_handler(env.SERVICE_NAME, {env.METHOD_NAME: handler})
    server = grpc.server(
        futures.ThreadPoolExecutor(max_workers=workers),
        options=[
            ("grpc.max_concurrent_streams", 100000),
            ("grpc.so_reuseport", 1),
        ],
    )
    server.add_generic_rpc_handlers((generic,))
    server.add_insecure_port("[::]:%d" % port)
    return server


def main():
    ap = argparse.ArgumentParser(description="USSD gRPC Application Server (test)")
    ap.add_argument("--port", type=int, default=8443)
    ap.add_argument("--workers", type=int, default=64, help="gRPC server thread pool size")
    ap.add_argument("--min-delay", type=int, default=1, help="min adaptive delay (ms)")
    ap.add_argument("--max-delay", type=int, default=100, help="max adaptive delay (ms)")
    ap.add_argument("--menu-config", default="menu_config.json")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO,
                        format="%(asctime)s %(levelname)s %(name)s %(message)s")

    with open(args.menu_config, "r", encoding="utf-8") as f:
        config = json.load(f)

    engine = MenuEngine(config)
    processor = UssdProcessor(engine, args.min_delay, args.max_delay)
    server = build_server(processor, args.port, args.workers)
    server.start()
    LOG.info("USSD gRPC AS listening on :%d (workers=%d, delay=%d-%d ms, menu=%s)",
             args.port, args.workers, args.min_delay, args.max_delay, args.menu_config)
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        LOG.info("Shutting down...")
        server.stop(grace=2).wait()


if __name__ == "__main__":
    main()
