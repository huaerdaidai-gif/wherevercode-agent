"""T4 registry/config-policy tests against both real Hand entry files."""
import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import hand_server as hs
from test_hand_server import load_bundled_hand_server


class HandConfigPolicyTests(unittest.TestCase):
    def modules(self):
        return (hs, load_bundled_hand_server())

    def register(self, module, hand, folder):
        return module.registry_register(hand.state.dir, "adapter", "policy-test", {
            "display_name": "Policy test", "binary": sys.executable,
            "binary_candidates": [sys.executable],
            "argv": [sys.executable, "-c", "print('registry')"],
            "folders": [folder], "login_check": {"type": "none"},
        })

    def test_standalone_discovery_never_grants_folder_until_explicit_grant(self):
        for module in self.modules():
            with self.subTest(module=module.__name__), tempfile.TemporaryDirectory() as temp:
                folder = str(Path(temp) / "discovered")
                os.mkdir(folder)
                hand = module.HandServer(str(Path(temp) / "state"), "test-device", "test",
                                         config_policy="standalone")
                self.register(module, hand, folder)
                self.assertTrue(module.resolve_adapters(hand.state.dir, hand.device_id)["policy-test"]["discovered"])
                self.assertIn("policy-test", [row["agent_id"] for row in hand.agents()])
                self.assertNotIn(folder, hand.authorized_folders("policy-test"))
                self.assertFalse((Path(hand.state.dir) / "auth.json").exists())
                output = io.StringIO()
                with contextlib.redirect_stdout(output):
                    code = module.main(["--state-dir", hand.state.dir, "grant", "policy-test", folder])
                self.assertEqual(0, code)
                self.assertIn(folder, hand.authorized_folders("policy-test"))
                audit = Path(hand.state.dir) / "audit.jsonl"
                events = [json.loads(line) for line in audit.read_text().splitlines()] if audit.exists() else []
                self.assertFalse(any(row["event"] == "folder_auto_authorized" for row in events))

    def test_bundled_implicit_authorization_is_audited_with_source_and_client(self):
        for module in self.modules():
            with self.subTest(module=module.__name__), tempfile.TemporaryDirectory() as temp:
                folder = str(Path(temp) / "discovered")
                os.mkdir(folder)
                hand = module.HandServer(str(Path(temp) / "state"), "test-device", "test",
                                         config_policy="bundled")
                self.register(module, hand, folder)
                self.assertIn(folder, hand.authorized_folders("policy-test", "phone"))
                events = [json.loads(line) for line in (Path(hand.state.dir) / "audit.jsonl").read_text().splitlines()]
                implicit = [row for row in events if row["event"] == "folder_auto_authorized"]
                self.assertTrue(implicit)
                self.assertTrue(all(row["policy"] == "bundled" and row["client"] == "phone" for row in implicit))
                self.assertEqual({"registry", "agent_config"}, {row["source"] for row in implicit})
                self.assertTrue(all(row["folder"] == folder for row in implicit))
                self.assertFalse(any("credential" in row or "key" in row for row in implicit))

    def test_command_conflict_obeys_explicit_policy(self):
        for module in self.modules():
            for policy in ("standalone", "bundled"):
                with self.subTest(module=module.__name__, policy=policy), tempfile.TemporaryDirectory() as temp:
                    hand = module.HandServer(temp, "test-device", "test", config_policy=policy)
                    self.register(module, hand, temp)
                    legacy = [sys.executable, "-c", "print('legacy')"]
                    hand.state.save_json("agents.json", {"policy-test": {"command": legacy, "folder": temp}})
                    command = hand.agent_config("policy-test")["command"]
                    self.assertEqual(legacy if policy == "standalone" else
                                     [sys.executable, "-c", "print('registry')"], command)
                    audit = Path(temp) / "audit.jsonl"
                    events = [json.loads(line) for line in audit.read_text().splitlines()] if audit.exists() else []
                    self.assertEqual(policy == "bundled", any(row["event"] == "legacy_command_ignored" for row in events))

    def test_client_project_grants_never_become_global(self):
        for module in self.modules():
            for policy in ("standalone", "bundled"):
                with self.subTest(module=module.__name__, policy=policy), tempfile.TemporaryDirectory() as temp:
                    hand = module.HandServer(temp, "test-device", "test", config_policy=policy)
                    hand.state.save_json("auth.json", {"agent": {"folders": ["/explicit"]}})
                    hand.state.save_json("project_grants.json", {"one": {"agent": ["/private"]}})
                    self.assertIn("/explicit", hand.authorized_folders("agent"))
                    self.assertIn("/private", hand.authorized_folders("agent", "one"))
                    self.assertNotIn("/private", hand.authorized_folders("agent", "two"))
                    self.assertNotIn("/private", hand.authorized_folders("agent"))

    def test_registry_custom_lifecycle_and_missing_implementation_labels_survive(self):
        for module in self.modules():
            with self.subTest(module=module.__name__), tempfile.TemporaryDirectory() as temp:
                module.registry_register(temp, "adapter", "new-agent", {"binary": "missing-t4-command"})
                self.assertEqual("custom", module.registry_all(temp)["adapter"]["new-agent"]["source"])
                module.registry_set_enabled(temp, "adapter", "new-agent", False)
                self.assertNotIn("new-agent", module.resolve_adapters(temp, "test-device"))
                module.registry_set_enabled(temp, "adapter", "new-agent", True)
                self.assertIn("new-agent", module.resolve_adapters(temp, "test-device"))
                self.assertTrue(module.registry_remove(temp, "adapter", "new-agent"))
                self.assertNotIn("new-agent", module.registry_all(temp)["adapter"])
                rows = module.registry_all(temp)
                self.assertEqual("design-only", rows["protocol"]["wacf"]["status"])
                self.assertIn("未实现", rows["protocol"]["wacf"]["gap"])
                self.assertEqual("mechanism-live", rows["wakeup"]["webhook"]["status"])
                self.assertIn("端到端接线", rows["wakeup"]["webhook"]["gap"])

    def test_registry_cli_runs_from_each_standalone_file(self):
        for module in self.modules():
            with self.subTest(module=module.__name__), tempfile.TemporaryDirectory() as temp:
                result = subprocess.run([sys.executable, module.__file__, "--state-dir", temp,
                                         "--config-policy", "standalone", "extensions", "--kind", "protocol"],
                                        capture_output=True, text=True, timeout=15, check=True)
                self.assertEqual("design-only", json.loads(result.stdout)["protocol"]["wacf"]["status"])

    def test_invalid_policy_fails_before_creating_state(self):
        for module in self.modules():
            with self.subTest(module=module.__name__), tempfile.TemporaryDirectory() as temp:
                path = Path(temp) / "must-not-exist"
                with self.assertRaises(ValueError):
                    module.HandServer(str(path), "test-device", "test", config_policy="guess")
                self.assertFalse(path.exists())


if __name__ == "__main__":
    unittest.main()
