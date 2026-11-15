"""S3c: both policies reject pending tools before any execution side effect."""
from contextlib import ExitStack
from copy import deepcopy
import json
from pathlib import Path
import socket
import sys
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch

from cryptography.exceptions import InvalidTag
import hand_server as hs
from test_hand_server import load_bundled_hand_server, make_credential


class PendingPolicyTests(unittest.TestCase):
    def modules(self):
        return (hs, load_bundled_hand_server())

    def exchange(self, module, hand, build_envelope, verify):
        """Use the real hello, key derivation, sealed frames and connection handler."""
        private = module.X25519PrivateKey.generate()
        public = private.public_key().public_bytes_raw()
        client, server = socket.socketpair()
        errors = []
        def serve():
            try:
                with server:
                    hand.handle_connection(server, ("test-local", 0))
            except BaseException as error:
                errors.append(error)
        thread = threading.Thread(target=serve)
        thread.start()
        try:
            module.write_frame(client, json.dumps({
                "type": "wherevercode_hello", "version": 1,
                "device_id": "pending-phone", "public_key_b64": module.b64e(public),
            }).encode())
            hello = json.loads(module.read_frame(client, 5))
            self.assertEqual("PENDING", hello["client_status"])
            session = module.derive_session_key(private.private_bytes_raw(), public,
                                                module.b64d(hello["public_key_b64"]))
            envelope = build_envelope(session)
            module.write_frame(client, module.seal(session, json.dumps(envelope).encode()))
            verify(client, session, envelope)
        finally:
            client.close()
            thread.join(timeout=5)
            self.assertFalse(thread.is_alive(), "pending connection must finish, not time out silently")
            self.assertEqual([], errors)

    def state_files(self, state):
        return {str(path.relative_to(state)): path.read_bytes()
                for path in Path(state).rglob("*")
                if path.is_file() and path.name != "audit.jsonl"}

    def reject_matrix(self, policy):
        for module in self.modules():
            for action in ("run", "write", "read", "list", "authorize_project_folder", "get_artifact"):
                with self.subTest(module=module.__name__, policy=policy, action=action), tempfile.TemporaryDirectory() as temp:
                    root = Path(temp)
                    work = root / "work"
                    work.mkdir()
                    project = work / "project"
                    project.mkdir()
                    marker = work / "must-not-exist"
                    (work / "existing.txt").write_text("unchanged", encoding="utf-8")
                    hand = module.HandServer(str(root / "state"), "hand", "test",
                                             config_policy=policy)
                    hand.state.save_json("agents.json", {"probe": {
                        "folder": str(work),
                        "command": [sys.executable, "-c",
                                    "from pathlib import Path; Path('must-not-exist').write_text('ran')"],
                    }})
                    hand.state.save_json("auth.json", {"probe": [str(work)]})
                    hand._dispatch = Mock(wraps=hand._dispatch)
                    tool_spies = {}
                    for name in ("_do_run", "_do_control", "_do_write", "_do_read",
                                 "_do_list", "_do_authorize_project_folder", "_get_artifact"):
                        spy = Mock(wraps=getattr(hand, name))
                        setattr(hand, name, spy)
                        tool_spies[name] = spy
                    before_work = self.state_files(work)
                    # Pairing may create its own record, but no execution state may appear.
                    hand.state.save_json("tasks.json", {"sentinel": {"epoch": 1}})
                    before_tasks = deepcopy(hand.state.load_json("tasks.json", {}))
                    before_meta = deepcopy(hand.task_meta)
                    before_processes = dict(hand.processes)
                    before_attempts = dict(hand.process_attempts)
                    before_starting = set(hand.starting_tasks)
                    def envelope(session):
                        credential = make_credential(module.derive_signing_key(session), "denied-task",
                                                     "hand", str(work), "probe", source="pending-phone")
                        return {
                            "type": "cross_device_tool_call", "version": 1, "seq": 17,
                            "source_device_id": "pending-phone", "target_device_id": "hand",
                            "task_id": "denied-task", "agent_id": "probe", "folder": str(work),
                            "action": action, "args": {
                                "credential": credential, "path": "must-not-exist",
                                "content": "must not be written",
                                "folder": str(project), "argv": json.dumps(["agent", "probe", "run"]),
                                "control": json.dumps({"epoch": 1, "goal_version": 1}),
                                "attempt": "denied-attempt",
                            },
                        }
                    def verify(client, session, request):
                        if policy == "standalone":
                            # EOF is required; a socket timeout is a test failure.
                            self.assertEqual(b"", client.recv(1))
                            return
                        sealed = module.read_frame(client, 5)
                        with self.assertRaises(InvalidTag):
                            module.open_sealed(b"wrong-session-key".ljust(32, b"!"), sealed)
                        response = json.loads(module.open_sealed(session, sealed))
                        self.assertEqual({
                            "type": "cross_device_error", "version": 1, "seq": 1,
                            "task_id": "denied-task", "agent_id": "probe",
                            "source_device_id": "hand", "target_device_id": "pending-phone",
                            "folder": str(work), "action": action,
                            "args": {
                                "code": module.ERR_NOT_AUTHORIZED,
                                "message": "远端尚未批准此手机：请先在 App 里完成配对（核对指纹 + 配对码），或在服务端跑 hand_server.py pair-approve 批准",
                            },
                        }, response)
                        self.assertEqual(b"", client.recv(1))
                    with ExitStack() as stack:
                        spawn = stack.enter_context(patch.object(module, "spawn_agent", side_effect=AssertionError("runner started")))
                        runner = (stack.enter_context(patch.object(module, "run_agent_entry", side_effect=AssertionError("runner dispatched")))
                                  if hasattr(module, "run_agent_entry") else None)
                        self.exchange(module, hand, envelope, verify)
                        spawn.assert_not_called()
                        if runner is not None:
                            runner.assert_not_called()
                    hand._dispatch.assert_not_called()
                    for spy in tool_spies.values():
                        spy.assert_not_called()
                    self.assertEqual(before_meta, hand.task_meta)
                    self.assertEqual(before_processes, hand.processes)
                    self.assertEqual(before_attempts, hand.process_attempts)
                    self.assertEqual(before_starting, hand.starting_tasks)
                    self.assertEqual(before_tasks, hand.state.load_json("tasks.json", {}))
                    self.assertFalse((Path(hand.state.dir) / "artifacts").exists())
                    self.assertFalse((Path(hand.state.dir) / "project_grants.json").exists())
                    self.assertEqual(before_work, self.state_files(work))
                    self.assertFalse(marker.exists())
                    self.assertEqual("PENDING", hand.client_status("pending-phone"))
                    events = [json.loads(line) for line in (Path(hand.state.dir) / "audit.jsonl").read_text().splitlines()]
                    self.assertFalse(any(event.get("event") in ("call", "folder_authorized", "change_goal")
                                         for event in events))

    def test_standalone_pending_tools_disconnect_without_dispatch_or_side_effects(self):
        self.reject_matrix("standalone")

    def test_bundled_pending_tools_return_sealed_error_without_dispatch_or_side_effects(self):
        self.reject_matrix("bundled")

    def test_valid_sealed_pair_confirmation_still_works_under_both_policies(self):
        for module in self.modules():
            for policy in ("standalone", "bundled"):
                with self.subTest(module=module.__name__, policy=policy), tempfile.TemporaryDirectory() as temp:
                    hand = module.HandServer(temp, "hand", "test", config_policy=policy)
                    code = hand.get_or_create_pair_code()
                    hand._dispatch = Mock(wraps=hand._dispatch)
                    def envelope(session):
                        return {"type": "cross_device_pair_confirm", "version": 1,
                                "code": code, "source_device_id": "pending-phone",
                                "target_device_id": "hand"}
                    def verify(client, session, request):
                        response = json.loads(module.open_sealed(session, module.read_frame(client, 5)))
                        self.assertEqual("cross_device_pair_approved", response["type"])
                        self.assertEqual({"device_id": "hand"}, response["args"])
                    with patch.object(module, "spawn_agent", side_effect=AssertionError("pairing started runner")) as spawn:
                        self.exchange(module, hand, envelope, verify)
                        spawn.assert_not_called()
                    hand._dispatch.assert_not_called()
                    self.assertEqual("APPROVED", hand.client_status("pending-phone"))
                    self.assertEqual({}, hand.task_meta)
                    self.assertEqual({}, hand.processes)
                    self.assertEqual(set(), hand.starting_tasks)


if __name__ == "__main__":
    unittest.main()
