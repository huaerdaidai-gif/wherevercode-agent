"""S3d: real subprocesses and secure optional log copies, both entry files."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import socket
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

import hand_server as hs
from test_hand_server import Client, load_bundled_hand_server


class RunnerLogTests(unittest.TestCase):
    def modules(self):
        return (hs, load_bundled_hand_server())

    def path(self, folder, task="task"):
        return Path(folder) / ("log-" + hashlib.sha256(task.encode()).hexdigest() + ".txt")

    def run_agent(self, module, folder, task="task", path=None, warnings=None, script=None):
        return module.run_cli_agent(sys.executable,
            [sys.executable, "-c", script or "print('first'); print('完整输出')"], "", task,
            str(folder), log_path=str(path) if path is not None else None,
            on_log_warning=warnings.append if warnings is not None else None)

    def test_unconfigured_log_writes_nothing(self):
        for module in self.modules():
            with self.subTest(module=module.__name__), tempfile.TemporaryDirectory() as temp:
                result = self.run_agent(module, temp)
                self.assertEqual((0, "first\n完整输出\n", False), result)
                self.assertEqual([], list(Path(temp).iterdir()))

    def test_configured_copy_equals_full_returned_output_and_is_private(self):
        for module in self.modules():
            with self.subTest(module=module.__name__), tempfile.TemporaryDirectory() as temp:
                warnings = []
                target = self.path(temp)
                result = self.run_agent(module, temp, path=target, warnings=warnings,
                                        script="print('x' * 70000); print('END')")
                self.assertEqual(0, result[0])
                self.assertFalse(result[2])
                self.assertGreater(len(result[1]), module.MAX_OUTPUT_CHARS)
                self.assertEqual(result[1].encode(), target.read_bytes())
                self.assertEqual(0o600, target.stat().st_mode & 0o777)
                self.assertEqual([], warnings)

    def test_missing_parent_warns_but_execution_success_is_unchanged(self):
        for module in self.modules():
            with self.subTest(module=module.__name__), tempfile.TemporaryDirectory() as temp:
                warnings = []
                target = self.path(Path(temp) / "missing")
                with self.assertLogs(level="WARNING") as logs:
                    result = self.run_agent(module, temp, path=target, warnings=warnings)
                self.assertEqual((0, "first\n完整输出\n", False), result)
                self.assertEqual("hand_log_copy_failed", warnings[0]["event"])
                self.assertEqual("FileNotFoundError", warnings[0]["error_type"])
                self.assertIn("hand_log_copy_failed", "\n".join(logs.output))
                self.assertFalse(target.exists())
                self.assertNotIn(str(target), "\n".join(logs.output))

    def test_permission_failure_is_nonfatal_and_observable(self):
        for module in self.modules():
            with self.subTest(module=module.__name__), tempfile.TemporaryDirectory() as temp:
                warnings = []
                with patch.object(module, "_write_log_copy", side_effect=PermissionError("private path")), self.assertLogs(level="WARNING"):
                    result = self.run_agent(module, temp, path=self.path(temp), warnings=warnings)
                self.assertEqual(0, result[0])
                self.assertEqual("PermissionError", warnings[0]["error_type"])
                self.assertFalse(result[2])

    def test_existing_file_or_symlink_is_never_overwritten(self):
        for module in self.modules():
            for link in (False, True):
                with self.subTest(module=module.__name__, link=link), tempfile.TemporaryDirectory() as temp:
                    outside = Path(temp) / "sensitive"
                    outside.write_text("keep-secret")
                    folder = Path(temp) / "work"
                    folder.mkdir()
                    target = self.path(folder)
                    if link:
                        target.symlink_to(outside)
                    else:
                        target.write_text("keep-existing")
                    warnings = []
                    with self.assertLogs(level="WARNING"):
                        result = self.run_agent(module, folder, path=target, warnings=warnings)
                    self.assertEqual(0, result[0])
                    self.assertTrue(warnings)
                    self.assertEqual("keep-secret", outside.read_text())
                    self.assertEqual("keep-secret" if link else "keep-existing", target.read_text())

    def test_directory_symlink_and_traversal_and_wrong_task_are_rejected(self):
        for module in self.modules():
            with self.subTest(module=module.__name__), tempfile.TemporaryDirectory() as temp:
                work = Path(temp) / "work"
                work.mkdir()
                outside = Path(temp) / "outside"
                outside.mkdir()
                (work / "link").symlink_to(outside, target_is_directory=True)
                for target in (self.path(outside), self.path(work / "link"),
                               self.path(work / ".." / "outside"), work / "server_key.json",
                               self.path(work, "another-task")):
                    warnings = []
                    with self.assertLogs(level="WARNING"):
                        code, output, timed_out = self.run_agent(module, work, path=target, warnings=warnings)
                    self.assertEqual(0, code)
                    self.assertFalse(timed_out)
                    self.assertTrue(warnings)
                    self.assertFalse(target.exists())
                self.assertEqual([], list(outside.iterdir()))

    def test_concurrent_tasks_and_duplicate_writes_cannot_clobber_logs(self):
        for module in self.modules():
            with self.subTest(module=module.__name__), tempfile.TemporaryDirectory() as temp:
                def one(task):
                    return self.run_agent(module, temp, task=task, path=self.path(temp, task),
                                          script="print(" + repr(task) + ")")
                with ThreadPoolExecutor(max_workers=2) as pool:
                    results = list(pool.map(one, ("one", "two")))
                self.assertEqual([0, 0], [r[0] for r in results])
                self.assertEqual("one\n", self.path(temp, "one").read_text())
                self.assertEqual("two\n", self.path(temp, "two").read_text())
                warnings = []
                with self.assertLogs(level="WARNING"):
                    result = self.run_agent(module, temp, task="one", path=self.path(temp, "one"),
                                            warnings=warnings, script="print('replacement')")
                self.assertEqual(0, result[0])
                self.assertEqual("one\n", self.path(temp, "one").read_text())
                self.assertEqual("FileExistsError", warnings[0]["error_type"])

    def test_timeout_and_nonzero_exit_are_preserved(self):
        for module in self.modules():
            with self.subTest(module=module.__name__), tempfile.TemporaryDirectory() as temp:
                self.assertEqual((7, "failed\n", False), self.run_agent(
                    module, temp, script="print('failed'); raise SystemExit(7)"))
                code, output, timed_out = module.run_cli_agent(sys.executable,
                    [sys.executable, "-c", "import time; print('started', flush=True); time.sleep(20)"],
                    "", "timeout", temp, timeout_s=0.2)
                self.assertTrue(timed_out)
                self.assertNotEqual(0, code)
                self.assertIn("started", output)

    def configure(self, module, hand, work, log_parent=None):
        module.registry_register(hand.state.dir, "adapter", "probe", {
            "binary": sys.executable, "binary_candidates": [sys.executable],
            "argv": [sys.executable, "-c", "print('real execution')"],
            "folders": [str(work)],
            "log_template": {"device": str(log_parent or work) + "/log-{task_id}.txt"},
        })
        hand.state.save_json("auth.json", {"probe": [str(work)]})

    def test_mcp_real_execution_and_copy_failure_preserve_success(self):
        for module in self.modules():
            for failure in (False, True):
                with self.subTest(module=module.__name__, failure=failure), tempfile.TemporaryDirectory() as temp:
                    work = Path(temp) / "work"
                    work.mkdir()
                    hand = module.HandServer(str(Path(temp) / "state"), "device", "test")
                    self.configure(module, hand, work, work / "missing" if failure else None)
                    result = module._mcp_call_tool(hand, "run", {"agent_id": "probe", "folder": str(work), "prompt": "go"})
                    self.assertFalse(result["isError"])
                    self.assertEqual("real execution\n", result["content"][0]["text"])
                    logs = list(work.glob("log-*.txt"))
                    self.assertEqual(0 if failure else 1, len(logs))
                    if logs:
                        self.assertEqual(result["content"][0]["text"], logs[0].read_text())
                    if failure:
                        audit = (Path(hand.state.dir) / "audit.jsonl").read_text()
                        self.assertIn("hand_log_copy_failed", audit)

    def test_remote_real_execution_keeps_result_and_writes_configured_copy(self):
        for module in self.modules():
            with self.subTest(module=module.__name__), tempfile.TemporaryDirectory() as temp:
                work = Path(temp) / "work"
                work.mkdir()
                hand = module.HandServer(str(Path(temp) / "state"), "device", "test")
                self.configure(module, hand, work)
                with module.ThreadedHandServer(("127.0.0.1", 0), module.HandConnectionHandler, hand) as tcp:
                    thread = threading.Thread(target=tcp.serve_forever, daemon=True)
                    thread.start()
                    try:
                        client = Client(*tcp.server_address, "device")
                        client.hello()
                        clients = hand.state.load_json("paired_clients.json", {})
                        clients[client.device_id]["status"] = "APPROVED"
                        hand.state.save_json("paired_clients.json", clients)
                        events = client.tool_call("remote-log", "probe", str(work), "run",
                            {"argv": json.dumps(["agent", "probe", "go"])})
                        self.assertEqual("cross_device_result", events[-1]["type"])
                        self.assertEqual("real execution\n", events[-1]["args"]["output"])
                        self.assertEqual("real execution\n", self.path(work, "remote-log").read_text())
                    finally:
                        tcp.shutdown()
                        thread.join(timeout=5)


if __name__ == "__main__":
    unittest.main()
