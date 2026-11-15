"""Update in isolated files; webhook only to loopback test HTTP server."""
import base64
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
from pathlib import Path
import shlex
import socket
import sys
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch

import hand_server as hs
from test_hand_server import load_bundled_hand_server


class HandAdminWebhookTests(unittest.TestCase):
    def modules(self):
        return (hs, load_bundled_hand_server())

    def hand(self, module, temp):
        hand = module.HandServer(str(Path(temp) / "state"), "device", "test")
        live = Path(temp) / "hand.py"
        live.write_text("print('old')\n")
        hand._live_hand_path = lambda: str(live)
        events = []
        hand._send = lambda conn, key, envelope, kind, args, seq: events.append((kind, args))
        return hand, live, events

    def stage_args(self, content):
        digest = hashlib.sha256(content).hexdigest()
        return {"phase": "stage", "content_b64": base64.urlsafe_b64encode(content).decode(),
                "sha256": digest, "version": digest[:12]}

    def test_admin_confirmation_checksum_and_syntax_guards(self):
        for module in self.modules():
            with self.subTest(module=module.__name__), tempfile.TemporaryDirectory() as temp:
                hand, live, events = self.hand(module, temp)
                args = self.stage_args(b"print('new')\n")
                envelope = {"action": "update_hand", "args": args, "source_device_id": "phone"}
                hand._handle_device_admin(None, b"", "phone", envelope)
                self.assertEqual("cross_device_error", events[-1][0])
                self.assertFalse(Path(str(live) + ".new").exists())
                envelope["confirmed"] = True
                envelope["args"] = {**args, "sha256": "0" * 64}
                hand._handle_device_admin(None, b"", "phone", envelope)
                self.assertEqual("cross_device_error", events[-1][0])
                envelope["args"] = self.stage_args(b"this is not python !!!")
                hand._handle_device_admin(None, b"", "phone", envelope)
                self.assertEqual("cross_device_error", events[-1][0])
                self.assertFalse(Path(str(live) + ".new").exists())
                self.assertEqual("print('old')\n", live.read_text())

    def test_stage_apply_rollback_preserve_real_files_without_process_exit(self):
        for module in self.modules():
            with self.subTest(module=module.__name__), tempfile.TemporaryDirectory() as temp:
                hand, live, events = self.hand(module, temp)
                content = b"print('new')\n"
                hand._handle_device_admin(None, b"", "phone", {"action": "update_hand",
                    "confirmed": True, "args": self.stage_args(content)})
                self.assertTrue(events[-1][1]["staged"])
                self.assertEqual("print('old')\n", live.read_text())
                with patch.object(hand, "_write_update_watchdog") as watchdog, patch.object(module.threading, "Timer") as timer:
                    hand._handle_device_admin(None, b"", "phone", {"action": "update_hand",
                        "confirmed": True, "args": {"phase": "apply"}})
                    self.assertTrue(events[-1][1]["applied"])
                    self.assertEqual(content, live.read_bytes())
                    watchdog.assert_called_once()
                    timer.return_value.start.assert_called_once()
                    hand._handle_device_admin(None, b"", "phone", {"action": "update_hand",
                        "confirmed": True, "args": {"phase": "rollback"}})
                    self.assertTrue(events[-1][1]["rolled_back"])
                    self.assertEqual("print('old')\n", live.read_text())

    def test_watchdog_fallback_retains_exact_original_argv_and_host(self):
        for module in self.modules():
            with self.subTest(module=module.__name__), tempfile.TemporaryDirectory() as temp:
                original = ["--state-dir", temp, "--device-id", "original-id", "--display-name",
                            "name with spaces", "--host", "127.0.0.1", "--port", "23456",
                            "--config-policy", "bundled", "serve"]
                hand = module.HandServer(temp, "device", "test", config_policy="bundled",
                                         startup_argv=original)
                hand._live_hand_path = lambda: str(Path(temp) / "hand.py")
                with patch.object(module.subprocess, "Popen"):
                    path = hand._write_update_watchdog(str(Path(temp) / "backup"), "version")
                line = next(line for line in Path(path).read_text().splitlines() if line.startswith("RESTART="))
                command = shlex.split(line[len("RESTART="):])[0]
                self.assertEqual([sys.executable, hand._live_hand_path(), *original], shlex.split(command))

    def test_webhook_real_loopback_sender_body_signature_and_https_gate(self):
        for module in self.modules():
            with self.subTest(module=module.__name__):
                captured = []
                class Handler(BaseHTTPRequestHandler):
                    def do_POST(self):
                        captured.append((dict(self.headers), self.rfile.read(int(self.headers["Content-Length"]))))
                        self.send_response(204)
                        self.end_headers()
                    def log_message(self, *args):
                        pass
                with HTTPServer(("127.0.0.1", 0), Handler) as http:
                    thread = threading.Thread(target=http.serve_forever, daemon=True)
                    thread.start()
                    url = "http://127.0.0.1:" + str(http.server_port) + "/hook"
                    try:
                        rejected = module.webhook_deliver(url, "secret", "test", {}, "probe", max_attempts=1)
                        self.assertFalse(rejected["ok"])
                        self.assertEqual([], captured)
                        result = module.webhook_deliver(url, "secret", "task.assigned",
                            {"task_id": "task", "round": 1}, "test-only", allow_private=True, max_attempts=1)
                        self.assertTrue(result["ok"])
                        headers, body = captured[0]
                        headers = {key.lower(): value for key, value in headers.items()}
                        payload = json.loads(body)
                        self.assertEqual("task_task_1", payload["eventId"])
                        signature = hmac.new(b"secret", (headers["webhook-id"] + "." +
                            headers["webhook-timestamp"] + ".").encode() + body, hashlib.sha256).digest()
                        self.assertEqual("v1," + base64.b64encode(signature).decode(), headers["webhook-signature"])
                    finally:
                        http.shutdown()
                        thread.join(timeout=5)

    def test_healthcheck_observes_real_key_and_listening_port(self):
        for module in self.modules():
            with self.subTest(module=module.__name__), tempfile.TemporaryDirectory() as temp, socket.socket() as listener:
                listener.bind(("127.0.0.1", 0))
                listener.listen()
                hand = module.HandServer(temp, "device", "test", port=listener.getsockname()[1])
                result = module.run_healthcheck(hand)
                checks = {row["name"]: row["ok"] for row in result["checks"]}
                self.assertTrue(checks["server_key_present"])
                self.assertTrue(checks["port_listening"])


if __name__ == "__main__":
    unittest.main()
