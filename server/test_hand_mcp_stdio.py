"""Tests for the Mac Codex stdio MCP adapter against the real Hand protocol."""

from __future__ import annotations

import base64
import io
import json
import os
import socket
import sys
import tempfile
import threading
import time
import unittest
import uuid
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import hand_server as hs
from hand_mcp_stdio import AdapterError, HandMcpAdapter, init_identity, load_config, load_identity, serve_stdio


class HandMcpStdioTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.work = os.path.join(self.tmp.name, "authorized")
        os.makedirs(self.work)
        self.server = hs.HandServer(os.path.join(self.tmp.name, "state"), "xiaomi-12", "Xiaomi 12")
        self.server.state.save_json("auth.json", {"codex": [self.work]})
        self.server.state.save_json("agents.json", {"codex": {"folder": self.work, "command": []}})
        self.network = hs.ThreadedHandServer(("127.0.0.1", 0), hs.HandConnectionHandler, self.server)
        self.thread = threading.Thread(target=self.network.serve_forever, daemon=True)
        self.thread.start()
        self.port = self.network.server_address[1]
        identity_data = init_identity(os.path.join(self.tmp.name, "identity.json"), "mac-codex-test")
        self.identity = load_identity(identity_data["identity_file"])
        self.server.note_client_hello(
            self.identity["device_id"], identity_data["public_key_b64"],
        )
        clients = self.server.state.load_json("paired_clients.json", {})
        clients[self.identity["device_id"]]["status"] = "APPROVED"
        self.server.state.save_json("paired_clients.json", clients)
        self.config = {
            "identity_file": identity_data["identity_file"],
            "timeout_seconds": 3,
            "devices": [{
                "device_id": self.server.device_id, "host": "127.0.0.1", "port": self.port,
                "target_public_key_b64": base64.b64encode(self.server.public_key).decode("ascii"),
                "authorizations": [{"authorization_id": "solo-folder", "agent_id": "codex", "folder": self.work}],
            }],
        }
        self.adapter = HandMcpAdapter(self.config, self.identity, read_timeout=3)
        self.route = {"device_id": self.server.device_id, "authorization_id": "solo-folder"}

    def tearDown(self):
        self.network.shutdown()
        self.network.server_close()
        self.thread.join(timeout=2)
        self.tmp.cleanup()

    def test_mcp_tools_round_trip_over_paired_encrypted_hand(self):
        written = self.adapter.call_tool("device_write", self.route | {
            "path": "solo-test.txt", "content": "MCP → Hand\n"})
        self.assertIn("已写入 solo-test.txt", written)
        self.assertEqual(self.adapter.call_tool("device_read", self.route | {"path": "solo-test.txt"}), "MCP → Hand\n")
        listing = json.loads(self.adapter.call_tool("device_list", self.route | {"path": ""}))
        self.assertEqual([entry["name"] for entry in listing], ["solo-test.txt"])
        self.assertEqual(len(self.server.state.load_json("paired_clients.json", {})), 1)

    def test_task_scoped_config_correlates_app_task_without_granting_authority(self):
        # The App/Host binds the MCP process to its persisted task ID. The
        # bridge still signs a fresh v2 credential and Hand independently
        # checks pairing, target, folder, expiry, replay, and the task fence.
        self.config.update({"task_id": "app-task-7c23", "epoch": 3, "goal_version": 2})
        adapter = HandMcpAdapter(self.config, self.identity, read_timeout=3)
        adapter.call_tool("device_write", self.route | {
            "path": "task-bound.txt", "content": "same task"})
        self.assertIn("app-task-7c23", self.server.task_meta)
        self.assertEqual(self.server.task_meta["app-task-7c23"]["epoch"], 3)
        self.assertEqual(self.server.task_meta["app-task-7c23"]["goal_version"], 2)
        self.assertEqual(Path(self.work, "task-bound.txt").read_text(encoding="utf-8"), "same task")
        self.assertEqual(adapter.call_tool("device_read", self.route | {
            "path": "task-bound.txt", "task_id": "attacker-selected-task"}), "same task")
        self.assertNotIn("attacker-selected-task", self.server.task_meta)

        # A task ID is not an authorization. Selecting an ungranted folder
        # remains denied even when the task ID is valid and already bound.
        with self.assertRaises(AdapterError) as error:
            adapter.call_tool("device_write", {
                "device_id": self.server.device_id, "authorization_id": "not-granted",
                "path": "denied.txt", "content": "no"})
        self.assertEqual(error.exception.code, "authorization_required")
        self.assertFalse(Path(self.work, "denied.txt").exists())

        adapter.call_tool("device_run", self.route | {
            "operation": "change_goal", "operation_id": "app-change-goal",
            "new_goal": "updated task"})
        restarted_adapter = HandMcpAdapter(self.config, self.identity, read_timeout=3)
        with self.assertRaises(AdapterError) as error:
            restarted_adapter.call_tool("device_write", self.route | {
                "path": "stale-write.txt", "content": "must be fenced"})
        self.assertEqual(error.exception.code, hs.ERR_STALE_GOAL_VERSION)
        self.assertFalse(Path(self.work, "stale-write.txt").exists())

    def test_task_scoped_config_rejects_invalid_fence_values(self):
        for field, value in (("epoch", 0), ("goal_version", True), ("task_id", "")):
            with self.subTest(field=field, value=value):
                config = dict(self.config)
                config[field] = value
                path = Path(self.tmp.name, "invalid-config.json")
                path.write_text(json.dumps(config), encoding="utf-8")
                with self.assertRaises(AdapterError) as error:
                    load_config(str(path))
                self.assertEqual(error.exception.code, "configuration_error")

        config = dict(self.config, task_id="task-must-have-single-route")
        config["devices"] = [dict(config["devices"][0], authorizations=[
            *config["devices"][0]["authorizations"],
            {"authorization_id": "second", "agent_id": "codex", "folder": self.work},
        ])]
        path = Path(self.tmp.name, "multi-route-task-config.json")
        path.write_text(json.dumps(config), encoding="utf-8")
        with self.assertRaises(AdapterError) as error:
            load_config(str(path))
        self.assertEqual(error.exception.code, "configuration_error")

    def test_wrong_device_route_is_rejected_before_network_call(self):
        with self.assertRaisesRegex(AdapterError, "configured Hand device") as error:
            self.adapter.call_tool("device_write", {
                "device_id": "unconfigured-device", "authorization_id": "solo-folder",
                "path": "wrong.txt", "content": "no"})
        self.assertEqual(error.exception.code, "wrong_target")
        self.assertFalse(os.path.exists(os.path.join(self.work, "wrong.txt")))

    def test_route_selects_device_and_authorization_from_registry(self):
        second_work = os.path.join(self.tmp.name, "second-authorized")
        os.makedirs(second_work)
        second = hs.HandServer(os.path.join(self.tmp.name, "second-state"), "second-hand", "Second Hand")
        second.state.save_json("auth.json", {"review": [second_work]})
        second_network = hs.ThreadedHandServer(("127.0.0.1", 0), hs.HandConnectionHandler, second)
        second_thread = threading.Thread(target=second_network.serve_forever, daemon=True)
        second_thread.start()
        try:
            second.note_client_hello(self.identity["device_id"], base64.b64encode(self.identity["public_key"]).decode("ascii"))
            clients = second.state.load_json("paired_clients.json", {})
            clients[self.identity["device_id"]]["status"] = "APPROVED"
            second.state.save_json("paired_clients.json", clients)
            self.config["devices"].append({
                "device_id": second.device_id, "host": "127.0.0.1", "port": second_network.server_address[1],
                "target_public_key_b64": base64.b64encode(second.public_key).decode("ascii"),
                "authorizations": [{"authorization_id": "review-folder", "agent_id": "review", "folder": second_work}],
            })
            adapter = HandMcpAdapter(self.config, self.identity, read_timeout=3)
            route = {"device_id": second.device_id, "authorization_id": "review-folder"}
            adapter.call_tool("device_write", route | {"path": "routed.txt", "content": "second"})
            self.assertEqual(Path(second_work, "routed.txt").read_text(encoding="utf-8"), "second")
            self.assertFalse(os.path.exists(os.path.join(self.work, "routed.txt")))
            discovered = json.loads(adapter.call_tool("hand_devices", {}))
            self.assertEqual({d["device_id"] for d in discovered}, {"xiaomi-12", "second-hand"})
        finally:
            second_network.shutdown()
            second_network.server_close()
            second_thread.join(timeout=2)

    def test_unconfigured_authorization_is_rejected(self):
        with self.assertRaises(AdapterError) as error:
            self.adapter.call_tool("device_write", {
                "device_id": self.server.device_id, "authorization_id": "other-folder",
                "path": "outside.txt", "content": "no"})
        self.assertEqual(error.exception.code, "authorization_required")
        self.assertFalse(os.path.exists(os.path.join(self.work, "outside.txt")))

    def test_hello_device_key_pin_mismatch_is_rejected(self):
        self.config["devices"][0]["target_public_key_b64"] = base64.b64encode(os.urandom(32)).decode("ascii")
        adapter = HandMcpAdapter(self.config, self.identity, read_timeout=3)
        with self.assertRaises(AdapterError) as error:
            adapter.call_tool("device_list", self.route)
        self.assertEqual(error.exception.code, "wrong_target")

    def test_offline_device_fails_closed_without_local_fallback(self):
        def offline(_address, _timeout):
            raise OSError("network is unreachable")

        adapter = HandMcpAdapter(self.config, self.identity, socket_factory=offline)
        with self.assertRaises(AdapterError) as error:
            adapter.call_tool("device_write", self.route | {"path": "offline.txt", "content": "no"})
        self.assertEqual(error.exception.code, "device_offline")
        self.assertFalse(os.path.exists(os.path.join(self.work, "offline.txt")))

    def test_timeout_and_subsequent_recovery(self):
        listener = socket.socket()
        listener.bind(("127.0.0.1", 0))
        listener.listen(1)
        port = listener.getsockname()[1]

        def stall_hello():
            conn, _ = listener.accept()
            time.sleep(0.25)
            conn.close()
            listener.close()

        stalled = threading.Thread(target=stall_hello, daemon=True)
        stalled.start()
        slow_device = dict(self.config["devices"][0], port=port, timeout_seconds=0.05)
        slow_config = {"devices": [slow_device], "identity_file": self.config["identity_file"], "timeout_seconds": 0.05}
        slow_adapter = HandMcpAdapter(slow_config, self.identity, read_timeout=0.05)
        with self.assertRaises(AdapterError) as error:
            slow_adapter.call_tool("device_write", self.route | {"path": "timeout.txt", "content": "no"})
        self.assertEqual(error.exception.code, "device_timeout")
        # A transport error does not poison the adapter; the next fresh call works.
        self.assertEqual(self.adapter.call_tool("device_write", self.route | {"path": "recovered.txt", "content": "ok"}), "已写入 recovered.txt")
        stalled.join(timeout=1)

    def test_stop_blocks_write_until_change_goal(self):
        stopped = json.loads(self.adapter.call_tool("device_run", {
            **self.route, "operation": "stop", "operation_id": "stop-1"}))
        self.assertTrue(stopped["confirmed"])
        with self.assertRaises(AdapterError) as error:
            self.adapter.call_tool("device_write", self.route | {"path": "stopped.txt", "content": "no"})
        self.assertEqual(error.exception.code, "task_stopped")
        self.adapter.call_tool("device_run", {
            **self.route, "operation": "change_goal", "operation_id": "goal-2", "new_goal": "write test file"})
        self.adapter.call_tool("device_write", self.route | {"path": "stopped.txt", "content": "recovered"})
        self.assertEqual(Path(self.work, "stopped.txt").read_text(encoding="utf-8"), "recovered")

    def test_device_run_never_launches_agent(self):
        with self.assertRaises(AdapterError) as error:
            self.adapter.call_tool("device_run", self.route | {"operation": "agent", "operation_id": "x"})
        self.assertEqual(error.exception.code, "command_not_allowed")
        self.assertEqual(self.server.processes, {})

    def test_stdio_json_rpc_exposes_only_device_tools(self):
        request = b'{"jsonrpc":"2.0","id":1,"method":"tools/list"}\n'
        out = io.BytesIO()
        serve_stdio(self.adapter, io.BytesIO(request), out)
        response = json.loads(out.getvalue().decode("utf-8"))
        self.assertEqual([tool["name"] for tool in response["result"]["tools"]],
                         ["hand_devices", "device_list", "device_read", "device_write", "device_run"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
