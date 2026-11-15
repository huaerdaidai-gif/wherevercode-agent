"""Compatibility is selected by deployment policy, never source filename."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import hand_server as hs
from test_hand_server import load_bundled_hand_server, make_credential


class HandCompatibilityTests(unittest.TestCase):
    def modules(self):
        return (hs, load_bundled_hand_server())

    def test_init_agents_preserves_each_default_and_existing_file(self):
        for module in self.modules():
            for policy in ("standalone", "bundled"):
                with self.subTest(module=module.__name__, policy=policy), tempfile.TemporaryDirectory() as temp:
                    argv = [sys.executable, module.__file__, "--state-dir", temp,
                            "--config-policy", policy, "init-agents"]
                    subprocess.run(argv, capture_output=True, text=True, check=True, timeout=15)
                    target = Path(temp) / "agents.json"
                    content = json.loads(target.read_text())
                    row = content["example-agent"]
                    self.assertEqual(["display_name", "kind", "capabilities", "folder", "command", "env"], list(row))
                    expected = (["/workspace/example-project/run-grok.sh", "{prompt}"] if policy == "standalone" else
                                ["/usr/local/bin/grok", "-p", "{prompt}", "--always-approve", "--sandbox",
                                 "workspace", "--output-format", "plain"])
                    self.assertEqual(expected, row["command"])
                    target.write_text('{"custom": {"command": ["keep"]}}')
                    before = target.read_bytes()
                    subprocess.run(argv, capture_output=True, text=True, check=True, timeout=15)
                    self.assertEqual(before, target.read_bytes())

    def test_receipt_original_byte_order_is_preserved_for_both_explicit_policies(self):
        for module in self.modules():
            for policy in ("standalone", "bundled"):
                with self.subTest(module=module.__name__, policy=policy), tempfile.TemporaryDirectory() as temp:
                    project = Path(temp) / "project"
                    project.mkdir()
                    hand = module.HandServer(str(Path(temp) / "state"), "device", "test",
                                             config_policy=policy)
                    events = []
                    hand._send = lambda conn, key, envelope, kind, args, seq: events.append((kind, args))
                    for already in (False, True):
                        hand._do_authorize_project_folder(None, b"", {"task_id": "task"}, 1,
                                                          "task", "agent", {"folder": str(project)}, "client")
                        if policy == "standalone":
                            expected = {"authorized": True, "requested_folder": str(project),
                                        "agent_id": "agent", "already": already, "folder": str(project)}
                        else:
                            expected = {"authorized": True, "agent_id": "agent",
                                        "requested_folder": str(project), "folder": str(project), "already": already}
                        self.assertEqual("cross_device_result", events[-1][0])
                        self.assertEqual(json.dumps(expected, ensure_ascii=False), events[-1][1]["output"])

    def test_credential_symlink_grant_equivalence_keeps_legacy_policy_rules(self):
        for module in self.modules():
            for policy in ("standalone", "bundled"):
                with self.subTest(module=module.__name__, policy=policy), tempfile.TemporaryDirectory() as temp:
                    real = Path(temp) / "real"
                    real.mkdir()
                    alias = Path(temp) / "alias"
                    alias.symlink_to(real, target_is_directory=True)
                    signing = b"s" * 32
                    credential = module.parse_wire_credential(make_credential(signing, "task", "device",
                                                                               str(alias), "agent"))
                    import time
                    error = module.verify_credential(credential, signing, "task", "device", str(alias),
                                                     "agent", [str(real)], set(), set(), int(time.time()),
                                                     config_policy=policy)
                    self.assertEqual(module.ERR_WRONG_FOLDER if policy == "standalone" else None, error)

    def test_both_physical_files_default_to_standalone_without_fixture_injection(self):
        for module in self.modules():
            with self.subTest(module=module.__name__), tempfile.TemporaryDirectory() as temp:
                script = ("import importlib.util; "
                          "s=importlib.util.spec_from_file_location('actual', " + repr(module.__file__) + "); "
                          "m=importlib.util.module_from_spec(s); s.loader.exec_module(m); "
                          "print(m.HandServer(" + repr(temp) + ", 'device', 'test').config_policy)")
                result = subprocess.run([sys.executable, "-c", script], capture_output=True, text=True,
                                        check=True, timeout=15)
                self.assertEqual("standalone", result.stdout.strip())


if __name__ == "__main__":
    unittest.main()
