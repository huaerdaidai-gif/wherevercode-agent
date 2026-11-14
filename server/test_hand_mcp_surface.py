"""S4 MCP stdio/file routes and explicit internal-executor boundary."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import hand_server as hs
from test_hand_server import load_bundled_hand_server


class McpSurfaceTests(unittest.TestCase):
    def modules(self):
        return (hs, load_bundled_hand_server())

    def test_stdio_actual_jsonrpc_initialize_notification_ping_tools_and_errors(self):
        for module in self.modules():
            for policy in ("standalone", "bundled"):
                with self.subTest(module=module.__name__, policy=policy), tempfile.TemporaryDirectory() as temp:
                    messages = [
                        {"jsonrpc": "2.0", "id": 1, "method": "initialize"},
                        {"jsonrpc": "2.0", "method": "notifications/initialized"},
                        {"jsonrpc": "2.0", "id": 2, "method": "ping"},
                        {"jsonrpc": "2.0", "id": 3, "method": "tools/list"},
                        {"jsonrpc": "2.0", "id": 4, "method": "unknown"},
                        [],
                    ]
                    wire = "\n".join(json.dumps(message) for message in messages) + "\n{broken\n"
                    result = subprocess.run([sys.executable, module.__file__, "--state-dir", temp,
                                             "--config-policy", policy, "mcp"], input=wire,
                                            capture_output=True, text=True, check=True, timeout=15)
                    replies = [json.loads(line) for line in result.stdout.splitlines()]
                    self.assertEqual(6, len(replies))
                    self.assertEqual("2024-11-05", replies[0]["result"]["protocolVersion"])
                    self.assertEqual({}, replies[1]["result"])
                    self.assertEqual({"list", "read", "write", "run"},
                                     {row["name"] for row in replies[2]["result"]["tools"]})
                    self.assertEqual(-32601, replies[3]["error"]["code"])
                    self.assertEqual(-32600, replies[4]["error"]["code"])
                    self.assertEqual(-32700, replies[5]["error"]["code"])

    def test_real_mcp_file_tools_and_scope_escape_denials(self):
        for module in self.modules():
            for policy in ("standalone", "bundled"):
                with self.subTest(module=module.__name__, policy=policy), tempfile.TemporaryDirectory() as temp:
                    folder = Path(temp) / "project"
                    folder.mkdir()
                    outside = Path(temp) / "secret"
                    outside.write_text("private")
                    (folder / "escape").symlink_to(outside)
                    hand = module.HandServer(str(Path(temp) / "state"), "device", "test", config_policy=policy)
                    hand.state.save_json("auth.json", {"agent": [str(folder)]})
                    write = module._mcp_call_tool(hand, "write", {"folder": str(folder), "path": "file.txt", "content": "实际文本"})
                    self.assertFalse(write["isError"])
                    read = module._mcp_call_tool(hand, "read", {"folder": str(folder), "path": "file.txt"})
                    self.assertEqual("实际文本", read["content"][0]["text"])
                    listing = module._mcp_call_tool(hand, "list", {"folder": str(folder)})
                    self.assertIn("file.txt", [row["name"] for row in json.loads(listing["content"][0]["text"])])
                    for name in ("read", "write"):
                        for relative in ("../secret", "escape"):
                            result = module._mcp_call_tool(hand, name, {"folder": str(folder), "path": relative, "content": "bad"})
                            self.assertTrue(result["isError"])
                    denied = module._mcp_call_tool(hand, "list", {"folder": temp})
                    self.assertTrue(denied["isError"])
                    self.assertEqual("private", outside.read_text())

    def test_internal_agent_is_explicitly_rejected_without_spawning(self):
        for module in self.modules():
            with self.subTest(module=module.__name__), tempfile.TemporaryDirectory() as temp:
                hand = module.HandServer(temp, "device", "test")
                with patch.object(module, "spawn_agent", side_effect=AssertionError("internal cannot spawn")) as spawn:
                    with self.assertRaises(module.InternalAgentExecution):
                        module.run_agent_entry({"execution": "internal"}, "go", "task", temp)
                    result = module._mcp_call_tool(hand, "run", {"agent_id": "wherevercode-agent", "folder": temp, "prompt": "go"})
                    self.assertTrue(result["isError"])
                    self.assertIn("App 内执行", result["content"][0]["text"])
                    spawn.assert_not_called()

    def test_controlled_success_exit_never_emits_false_ok_websocket_event(self):
        for module in self.modules():
            with self.subTest(module=module.__name__), tempfile.TemporaryDirectory() as temp:
                hand = module.HandServer(temp, "device", "test")
                module.registry_register(temp, "adapter", "probe", {
                    "binary": sys.executable, "binary_candidates": [sys.executable],
                    "argv": [sys.executable, "-c", "print('ok')"], "folders": [temp]})
                hand.state.save_json("auth.json", {"probe": [temp]})
                events, frames = [], []
                hand.publish_event = lambda client, event, data: events.append((event, data))
                hand._send = lambda conn, key, envelope, kind, args, seq: frames.append((kind, args))
                # Model a race in which the process has returned 0 while stop won
                # the task fence. Result/event must both remain controlled-stop.
                with patch.object(module, "run_agent_entry", return_value=(0, "ok", False)), \
                     patch.object(hand, "_was_control_stopped", return_value=True):
                    hand._do_run(None, b"", {"task_id": "task"}, 1, "task", "probe", temp,
                                 {"argv": json.dumps(["agent", "probe", "go"])}, "client")
                terminal = [data for event, data in events if event == "agent.run.finished"]
                self.assertEqual(["stopped"], [data["status"] for data in terminal])
                self.assertEqual(module.ERR_REMOTE_CONTROLLED_STOP, frames[-1][1]["code"])


if __name__ == "__main__":
    unittest.main()
