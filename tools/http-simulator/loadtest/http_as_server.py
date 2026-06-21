#!/usr/bin/env python3
"""
HTTP Pull Application Server — replaces manual XML in the legacy HTTP Simulator GUI.

Gateway POSTs XmlMAPDialog to this server (routing rule HTTP → listen URL).
Responds with auto-generated menu XML from menu_config.json (same as gRPC AS).

Usage:
    python3 http_as_server.py --port 8049 --min-delay 1 --max-delay 100
    python3 http_as_server.py --bridge-delay 8000 --bridge-every 10
"""

import argparse
import logging
import random
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import menu_engine
import ussd_xml as uxml

LOG = logging.getLogger("http-as")
_SESSION_COOKIE = re.compile(r"JSESSIONID=([^;\s]+)")


class HttpAsHandler(BaseHTTPRequestHandler):
    engine = None
    min_delay_ms = 1
    max_delay_ms = 100
    bridge_delay_ms = 0
    bridge_every = 0
    _counter = 0
    _lock = threading.Lock()

    def log_message(self, fmt, *args):
        LOG.debug("%s - %s", self.address_string(), fmt % args)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length) if length else b""
        cookie = self.headers.get("Cookie", "")
        m = _SESSION_COOKIE.search(cookie)
        session_id = m.group(1) if m else None

        parsed = uxml.parse_request(body)
        if not session_id:
            session_id = parsed.get("local_id") or ("http-%d" % int(time.time() * 1000))

        if self._should_bridge():
            time.sleep(self.bridge_delay_ms / 1000.0)
        elif self.max_delay_ms > 0:
            lo = max(0, self.min_delay_ms)
            hi = max(lo, self.max_delay_ms)
            time.sleep(random.randint(lo, hi) / 1000.0)

        text, end = self.engine.handle(str(session_id), parsed)
        resp_xml = uxml.build_response(
            message_type=parsed["message_type"],
            text=text,
            invoke_id=parsed.get("invoke_id", 0),
            end=end,
            user_object="sessionId=%s" % session_id,
            network_id=parsed.get("network_id", 0),
        )
        self.send_response(200)
        self.send_header("Content-Type", "text/xml; charset=utf-8")
        self.send_header("Content-Length", str(len(resp_xml)))
        if not m:
            self.send_header("Set-Cookie", "JSESSIONID=%s; Path=/" % session_id)
        self.end_headers()
        self.wfile.write(resp_xml)

    @classmethod
    def _should_bridge(cls) -> bool:
        if cls.bridge_every <= 0:
            return False
        with cls._lock:
            cls._counter += 1
            n = cls._counter
        return (n % cls.bridge_every) == 0


def main():
    ap = argparse.ArgumentParser(description="USSD HTTP Pull AS (auto menu XML)")
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=8049)
    ap.add_argument("--min-delay", type=int, default=1)
    ap.add_argument("--max-delay", type=int, default=100)
    ap.add_argument("--bridge-delay", type=int, default=0)
    ap.add_argument("--bridge-every", type=int, default=0)
    ap.add_argument("--menu-config", default="menu_config.json")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO,
                        format="%(asctime)s %(levelname)s %(name)s %(message)s")

    HttpAsHandler.engine = menu_engine.MenuEngine(args.menu_config)
    HttpAsHandler.min_delay_ms = args.min_delay
    HttpAsHandler.max_delay_ms = args.max_delay
    HttpAsHandler.bridge_delay_ms = args.bridge_delay
    HttpAsHandler.bridge_every = args.bridge_every

    server = ThreadingHTTPServer((args.host, args.port), HttpAsHandler)
    LOG.info("HTTP Pull AS on %s:%d delay=%d-%dms bridge=%dms/1-in-%d",
             args.host, args.port, args.min_delay, args.max_delay,
             args.bridge_delay, args.bridge_every)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        LOG.info("Shutting down...")
        server.shutdown()


if __name__ == "__main__":
    main()
