"""S6 host evidence: loopback discovery/crypto + actual process-group controls.

This is Linux CI with a deterministic Python worker, NOT Android/provider-CLI
or real multicast discovery acceptance. Evidence explicitly records that scope.
"""
import hashlib
import json
import os
from pathlib import Path
import signal
import socket
import struct
import sys
import tempfile
import threading
import time
import unittest

import hand_server as hs
from test_hand_server import Client, load_bundled_hand_server


WORKER = r"""
from pathlib import Path
import subprocess, sys, time
goal = sys.argv[1]
if goal.startswith("deliver"):
    Path(goal.rsplit("：", 1)[-1]).write_bytes(b"actual artifact bytes")
    print("DELIVERED", flush=True)
else:
    child = subprocess.Popen([sys.executable, "-c",
        "from pathlib import Path; import time\n"
        "while True:\n"
        " with open('child-heartbeat', 'ab') as f: f.write(b'x'); f.flush()\n"
        " time.sleep(0.04)\n"])
    Path("child-pid").write_text(str(child.pid))
    while True:
        with open("heartbeat", "ab") as f:
            f.write(b"x")
            f.flush()
        print("tick", flush=True)
        time.sleep(0.04)
"""


class HostChainTests(unittest.TestCase):
    def wait_for(self, predicate, timeout=2):
        start = time.monotonic()
        while time.monotonic() - start < timeout:
            if predicate():
                return
            time.sleep(0.02)
        self.fail("observable process condition exceeded " + str(timeout) + " seconds")

    def process_state(self, pid):
        try:
            return Path("/proc", str(pid), "stat").read_text().rsplit(")", 1)[1].split()[0]
        except FileNotFoundError:
            return None

    def snapshot(self, work):
        return tuple((work / name).stat().st_size for name in ("heartbeat", "child-heartbeat"))

    def discover(self, module, hand):
        packet = module._mdns_build_response("test-hand", "host", "127.0.0.1",
                                             hand.port, hand.device_id, hand.fingerprint())
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as receiver, socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sender:
            receiver.bind(("127.0.0.1", 0))
            receiver.settimeout(2)
            sender.sendto(packet, receiver.getsockname())
            received, _ = receiver.recvfrom(65536)
        header = struct.unpack(">HHHHHH", received[:12])
        self.assertEqual(4, header[3])
        offset, port, texts = 12, None, b""
        for _ in range(header[3]):
            name, offset = module._dns_decode_name(received, offset)
            kind, cls, ttl, size = struct.unpack(">HHIH", received[offset:offset + 10])
            offset += 10
            data = received[offset:offset + size]
            offset += size
            if kind == 33:
                port = struct.unpack(">HHH", data[:6])[2]
            if kind == 16:
                texts = data
        self.assertEqual(hand.port, port)
        self.assertIn(("fp=" + hand.fingerprint()).encode(), texts)
        return port

    def test_host_chain_both_policies_and_both_physical_entry_files(self):
        for module in (hs, load_bundled_hand_server()):
            for policy in ("standalone", "bundled"):
                with self.subTest(module=module.__name__, policy=policy), tempfile.TemporaryDirectory() as temp:
                    root = Path(temp)
                    work = root / "work"
                    work.mkdir()
                    script = work / "worker.py"
                    script.write_text(WORKER, encoding="utf-8")
                    hand = module.HandServer(str(root / "state"), "device", "test",
                                             config_policy=policy)
                    hand.state.save_json("agents.json", {"worker": {"folder": str(work),
                        "command": [sys.executable, str(script), "{prompt}"]}})
                    hand.state.save_json("auth.json", {"worker": [str(work)]})
                    evidence = {"scope": "Linux host/loopback DNS packet/Python fixture worker; Android and provider CLI unverified",
                                "module": module.__name__, "policy": policy,
                                "source_sha256": hashlib.sha256(Path(module.__file__).read_bytes()).hexdigest(),
                                "steps": [], "success": False}
                    run_events, run_errors = [], []
                    run_thread = None
                    with module.ThreadedHandServer(("127.0.0.1", 0), module.HandConnectionHandler, hand) as tcp:
                        service = threading.Thread(target=tcp.serve_forever, daemon=True)
                        service.start()
                        hand.port = tcp.server_address[1]
                        try:
                            port = self.discover(module, hand)
                            evidence["steps"].append({"step": "discover", "port": port, "transport": "real UDP loopback, not multicast"})
                            client = Client("127.0.0.1", port, "device")
                            hello = client.hello()
                            self.assertEqual(hand.fingerprint(), hashlib.sha256(client.server_pub).hexdigest())
                            replies = client.call({"type": "cross_device_pair_confirm", "version": 1, "seq": 1,
                                "source_device_id": client.device_id, "target_device_id": "device",
                                "code": hand.get_or_create_pair_code()})
                            self.assertEqual("cross_device_pair_approved", replies[-1]["type"])
                            evidence["steps"].append({"step": "pair", "status": hand.client_status(client.device_id)})
                            def run():
                                try:
                                    run_events.extend(client.tool_call("chain-run", "worker", str(work), "run",
                                        {"argv": json.dumps(["agent", "worker", "heartbeat"]), "attempt": "attempt-one",
                                         "control": json.dumps({"epoch": 1, "goal_version": 1})}, timeout=30))
                                except BaseException as error:
                                    run_errors.append(repr(error))
                            run_thread = threading.Thread(target=run, daemon=True)
                            run_thread.start()
                            self.wait_for(lambda: "chain-run" in hand.processes and (work / "child-heartbeat").exists(), 5)
                            parent = hand.processes["chain-run"]
                            child_pid = int((work / "child-pid").read_text())
                            evidence["steps"].append({"step": "dispatch", "pid": parent.pid, "child_pid": child_pid,
                                                      "heartbeat_bytes": self.snapshot(work)})
                            def control(action):
                                return client.tool_call("chain-run", "worker", str(work), "run",
                                    {"argv": json.dumps(["__control__", action, "operation-" + action]),
                                     "control": json.dumps({"epoch": 1, "goal_version": 1, "attempt": "attempt-one"})})
                            start = time.monotonic()
                            pause = control("pause")
                            self.assertTrue(json.loads(pause[-1]["args"]["output"])["confirmed"])
                            self.wait_for(lambda: self.process_state(parent.pid) in ("T", "t") and self.process_state(child_pid) in ("T", "t"))
                            duration = time.monotonic() - start
                            self.assertLessEqual(duration, 2)
                            paused = self.snapshot(work)
                            time.sleep(3)
                            self.assertEqual(paused, self.snapshot(work))
                            evidence["steps"].append({"step": "pause", "seconds": duration, "states": ["stopped", "stopped"],
                                                      "stable_observation_seconds": 3, "heartbeat_bytes": paused})
                            start = time.monotonic()
                            resume = control("resume")
                            self.assertTrue(json.loads(resume[-1]["args"]["output"])["confirmed"])
                            self.wait_for(lambda: all(a > b for a, b in zip(self.snapshot(work), paused)))
                            duration = time.monotonic() - start
                            self.assertLessEqual(duration, 2)
                            evidence["steps"].append({"step": "resume", "seconds": duration, "heartbeat_bytes": self.snapshot(work)})
                            start = time.monotonic()
                            stopped = control("stop")
                            self.assertTrue(json.loads(stopped[-1]["args"]["output"])["confirmed"])
                            self.wait_for(lambda: parent.poll() is not None and self.process_state(child_pid) in (None, "Z"), 5)
                            duration = time.monotonic() - start
                            self.assertLessEqual(duration, 5)
                            stable = self.snapshot(work)
                            time.sleep(5)
                            self.assertEqual(stable, self.snapshot(work))
                            run_thread.join(timeout=5)
                            self.assertFalse(run_thread.is_alive())
                            self.assertEqual([], run_errors)
                            self.assertEqual(module.ERR_REMOTE_CONTROLLED_STOP, run_events[-1]["args"]["code"])
                            evidence["steps"].append({"step": "stop", "seconds": duration,
                                "stable_observation_seconds": 5, "terminal": run_events[-1]["args"]["code"]})
                            rejected = client.tool_call("chain-run", "worker", str(work), "get_artifact",
                                                        {"attempt": "attempt-one"})
                            self.assertEqual(module.ERR_REVOKED_CREDENTIAL, rejected[-1]["args"]["code"])
                            delivered = client.tool_call("chain-delivery", "worker", str(work), "run",
                                {"argv": json.dumps(["agent", "worker", "deliver"]), "attempt": "delivery-one",
                                 "control": json.dumps({"epoch": 1, "goal_version": 1})})
                            self.assertEqual("cross_device_result", delivered[-1]["type"])
                            readback = client.tool_call("chain-delivery", "worker", str(work), "get_artifact",
                                                        {"attempt": "delivery-one"})
                            record = readback[-1]["args"]
                            content = module.b64d(record["artifact_b64"])
                            self.assertEqual(b"actual artifact bytes", content)
                            self.assertEqual(hashlib.sha256(content).hexdigest(), record["artifact_sha256"])
                            evidence["steps"].append({"step": "readback", "stopped_task": "revoked_credential",
                                "successful_task": "chain-delivery", "artifact_sha256": record["artifact_sha256"],
                                "artifact_size": len(content)})
                            evidence["success"] = True
                        finally:
                            for task in list(hand.processes):
                                hand._kill_process(task)
                            if run_thread is not None:
                                run_thread.join(timeout=5)
                            tcp.shutdown()
                            service.join(timeout=5)
                            folder = os.environ.get("T4_EVIDENCE_DIR")
                            if folder:
                                Path(folder).mkdir(parents=True, exist_ok=True)
                                (Path(folder) / (module.__name__ + "-" + policy + ".json")).write_text(
                                    json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
                            print("T4_HOST_CHAIN " + json.dumps(evidence, ensure_ascii=False))


if __name__ == "__main__":
    unittest.main()
