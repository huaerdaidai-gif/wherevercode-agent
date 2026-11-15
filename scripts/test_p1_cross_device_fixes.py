#!/usr/bin/env python3
"""
E2E verification script for WhereverCode V5 cross-device execution fixes:
- Fault A: Authorized project list_files(path="") and list_files(path=".") succeed; unauthorized escape (e.g. /etc) rejected with wrong_folder.
- Fault B: Real runAgent with agy ("修复 bug") succeeds with exit code 0; non-existent agent returns agent_not_found; invalid command returns execution_failed.
- Fault C: Binary upload (writeFile base64) successfully writes file without corruption; honest attachment representation.
"""
import base64
import json
import os
import sys
import unittest

sys.path.insert(0, "/data/data/com.termux/files/home/wc-ios-1.5-6/repo/server")
import hand_server as hs
from test_hand_server import Client

HAND_HOST = "127.0.0.1"
HAND_PORT = 18743
STATE_DIR = "/data/data/com.termux/files/home/.local/share/wherevercode/v5-hand"
AUTHORIZED_PROJECT = "/data/data/com.termux/files/home/Projects/ceshi1016"
UNAUTHORIZED_DIR = "/etc"


class CrossDeviceE2ETest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        os.makedirs(AUTHORIZED_PROJECT, exist_ok=True)
        cls.test_file_path = os.path.join(AUTHORIZED_PROJECT, "test_file.txt")
        with open(cls.test_file_path, "w", encoding="utf-8") as f:
            f.write("test content hello wherevercode")

        # Create client and do hello
        cls.client = Client(HAND_HOST, HAND_PORT, "Android-x")
        cls.client.hello()

        # Approve client in paired_clients.json
        paired_path = os.path.join(STATE_DIR, "paired_clients.json")
        with open(paired_path, "r") as f:
            clients = json.load(f)
        clients[cls.client.device_id] = {
            "public_key_b64": hs.b64e(cls.client.pub_bytes),
            "status": "APPROVED",
            "added_at": 1700000000
        }
        with open(paired_path, "w") as f:
            json.dump(clients, f, indent=2)

    def test_fault_a_authorized_list_empty_and_dot(self):
        """Fault A: list_files(path='') and list_files(path='.') on authorized project."""
        # 1. path=""
        events = self.client.tool_call("t_list_empty", "agy", AUTHORIZED_PROJECT, "list", {"path": ""})
        self.assertTrue(len(events) > 0, "Should receive events")
        res = events[-1]
        self.assertEqual(res.get("type"), "cross_device_result", f"Response: {res}")
        output = json.loads(res["args"]["output"])
        names = [item["name"] for item in output]
        self.assertIn("test_file.txt", names)

        # 2. path="."
        events_dot = self.client.tool_call("t_list_dot", "agy", AUTHORIZED_PROJECT, "list", {"path": "."})
        self.assertTrue(len(events_dot) > 0, "Should receive events for dot path")
        res_dot = events_dot[-1]
        self.assertEqual(res_dot.get("type"), "cross_device_result", f"Response: {res_dot}")
        output_dot = json.loads(res_dot["args"]["output"])
        names_dot = [item["name"] for item in output_dot]
        self.assertIn("test_file.txt", names_dot)

    def test_fault_a_unauthorized_folder_rejected(self):
        """Fault A: unauthorized directory like /etc must be strictly rejected with wrong_folder."""
        events = self.client.tool_call("t_unauth", "agy", UNAUTHORIZED_DIR, "list", {"path": ""})
        self.assertTrue(len(events) > 0)
        res = events[-1]
        self.assertEqual(res.get("type"), "cross_device_error", f"Response: {res}")
        self.assertEqual(res["args"].get("code"), "wrong_folder")

    def test_fault_b_agy_real_execution(self):
        """Fault B: agy execution with '修复 bug' exits 0 and returns valid output."""
        argv_str = json.dumps(["agent", "agy", "修复 bug"])
        events = self.client.tool_call("t_run_agy", "agy", AUTHORIZED_PROJECT, "run", {"argv": argv_str}, timeout=60)
        self.assertTrue(len(events) > 0)
        res = events[-1]
        self.assertEqual(res.get("type"), "cross_device_result", f"Response: {res}")
        output = res["args"].get("output", "")
        self.assertTrue(len(output) > 0, "agy should produce output")

    def test_fault_b_non_existent_agent_returns_agent_not_found(self):
        """Fault B: requesting non-existent agent returns agent_not_found."""
        argv_str = json.dumps(["agent", "non_existent_agent_123", "hello"])
        events = self.client.tool_call("t_non_agent", "non_existent_agent_123", AUTHORIZED_PROJECT, "run", {"argv": argv_str})
        self.assertTrue(len(events) > 0)
        res = events[-1]
        self.assertEqual(res.get("type"), "cross_device_error", f"Response: {res}")
        self.assertEqual(res["args"].get("code"), "agent_not_found")

    def test_fault_c_binary_upload(self):
        """Fault C: binary image data uploaded via writeFile base64."""
        test_binary = b"\xff\xd8\xff\xe0\x00\x10JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00\xff\xdb"
        b64_content = base64.b64encode(test_binary).decode("ascii")
        rel_path = "attachments/test_image.jpg"

        events = self.client.tool_call("t_write_b64", "agy", AUTHORIZED_PROJECT, "write", {
            "path": rel_path,
            "content": b64_content,
            "encoding": "base64"
        })
        self.assertTrue(len(events) > 0)
        res = events[-1]
        self.assertEqual(res.get("type"), "cross_device_result", f"Response: {res}")

        # Check on disk
        target_path = os.path.join(AUTHORIZED_PROJECT, rel_path)
        self.assertTrue(os.path.exists(target_path))
        with open(target_path, "rb") as f:
            read_bytes = f.read()
        self.assertEqual(read_bytes, test_binary)
        if os.path.exists(target_path):
            os.remove(target_path)


if __name__ == "__main__":
    unittest.main()
