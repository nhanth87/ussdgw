"""Shared USSD menu state machine (same tree as grpc-as-tester / MAP load)."""

import json
import threading


class MenuEngine:
    PROFILES = {
        "BALANCE": ["1", "0"],
        "DATA": ["2", "1"],
        "SUBSCRIBE": ["3", "100"],
    }

    def __init__(self, config_path: str):
        with open(config_path, "r", encoding="utf-8") as f:
            cfg = json.load(f)
        self.root = cfg["root"]
        self.nodes = cfg["nodes"]
        self._sessions = {}
        self._lock = threading.Lock()

    def handle(self, session_id: str, parsed: dict):
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
                return "Invalid choice.\n" + node["text"], False
            if next_name == "__end__":
                with self._lock:
                    self._sessions.pop(session_id, None)
                return "Thank you. Goodbye!", True
            nxt = self.nodes[next_name]
            with self._lock:
                self._sessions[session_id] = next_name
            is_final = bool(nxt.get("final"))
            if is_final:
                with self._lock:
                    self._sessions.pop(session_id, None)
            return nxt["text"], is_final

        return "Session ended.", True

    def menu_text(self, node_name: str = None) -> str:
        name = node_name or self.root
        return self.nodes[name]["text"]

    def walk_profile(self, profile: str):
        """Yield digit choices for scripted multi-menu (push / load clients)."""
        script = self.PROFILES.get(profile.upper(), [])
        node_name = self.root
        for digit in script:
            node = self.nodes.get(node_name, self.nodes[self.root])
            opts = node.get("options", {})
            if digit not in opts and "*" not in opts:
                break
            yield digit
            next_name = opts.get(digit) or opts.get("*")
            if next_name is None or next_name == "__end__":
                return
            node_name = next_name
            if self.nodes.get(node_name, {}).get("final"):
                return
