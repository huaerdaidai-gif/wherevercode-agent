"""Exercise B-origin pairing and DNS against both actual Hand modules."""
import json
import os
from pathlib import Path
import socket
import stat
import struct
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch

import hand_server as hs
from test_hand_server import load_bundled_hand_server


class HandPairingMdnsTests(unittest.TestCase):
    def modules(self):
        return (hs, load_bundled_hand_server())

    def pair(self, module, hand, code, client_id="phone", key=None):
        key = key or module.X25519PrivateKey.generate()
        public = key.public_key().public_bytes_raw()
        client, server = socket.socketpair()
        def handle():
            with server:
                hand.handle_connection(server, ("local", 0))
        thread = threading.Thread(target=handle)
        thread.start()
        try:
            module.write_frame(client, json.dumps({"type": "wherevercode_hello", "version": 1,
                "device_id": client_id, "public_key_b64": module.b64e(public)}).encode())
            hello = json.loads(module.read_frame(client, 5))
            session = module.derive_session_key(key.private_bytes_raw(), public, module.b64d(hello["public_key_b64"]))
            module.write_frame(client, module.seal(session, json.dumps({
                "type": "cross_device_pair_confirm", "version": 1, "code": code, "seq": 1,
            }).encode()))
            try:
                reply = json.loads(module.open_sealed(session, module.read_frame(client, 5)))
            except ConnectionError:
                reply = None
            return hello, reply
        finally:
            client.close()
            thread.join(timeout=5)
            self.assertFalse(thread.is_alive())

    def test_encrypted_pair_confirm_approves_without_executing_task(self):
        for module in self.modules():
            with self.subTest(module=module.__name__), tempfile.TemporaryDirectory() as temp:
                hand = module.HandServer(temp, "hand", "test")
                hand._dispatch = Mock(side_effect=AssertionError("pairing must not dispatch tools"))
                hello, reply = self.pair(module, hand, hand.get_or_create_pair_code())
                self.assertEqual("PENDING", hello["client_status"])
                self.assertEqual("cross_device_pair_approved", reply["type"])
                self.assertEqual("APPROVED", hand.client_status("phone"))
                hand._dispatch.assert_not_called()
                self.assertEqual(stat.S_IMODE(os.stat(Path(temp) / "pair_code.json").st_mode), 0o600)

    def test_wrong_pair_code_is_sealed_error_and_stays_pending(self):
        for module in self.modules():
            with self.subTest(module=module.__name__), tempfile.TemporaryDirectory() as temp:
                hand = module.HandServer(temp, "hand", "test")
                right = hand.get_or_create_pair_code()
                wrong = "000000" if right != "000000" else "111111"
                _, reply = self.pair(module, hand, wrong)
                self.assertEqual("cross_device_error", reply["type"])
                self.assertEqual(module.ERR_PAIR_CODE_WRONG, reply["args"]["code"])
                self.assertEqual("PENDING", hand.client_status("phone"))

    def test_revoked_client_cannot_repair_using_correct_code(self):
        for module in self.modules():
            with self.subTest(module=module.__name__), tempfile.TemporaryDirectory() as temp:
                hand = module.HandServer(temp, "hand", "test")
                key = module.X25519PrivateKey.generate()
                hand.state.save_json("paired_clients.json", {"phone": {
                    "status": "REVOKED", "public_key_b64": module.b64e(key.public_key().public_bytes_raw())}})
                hello, reply = self.pair(module, hand, hand.get_or_create_pair_code(), key=key)
                self.assertEqual("REVOKED", hello["client_status"])
                self.assertIsNone(reply)
                self.assertEqual("REVOKED", hand.client_status("phone"))

    def test_pair_code_rotation_and_lockout(self):
        for module in self.modules():
            with self.subTest(module=module.__name__), tempfile.TemporaryDirectory() as temp:
                hand = module.HandServer(temp, "hand", "test")
                with patch.object(module.secrets, "choice", return_value="1"):
                    self.assertEqual("111111", hand.get_or_create_pair_code())
                with patch.object(module.secrets, "choice", return_value="2"):
                    self.assertEqual("222222", hand.rotate_pair_code())
                self.assertFalse(hand.check_pair_code("111111", "old-code")[0])
                for _ in range(module.PAIR_CODE_MAX_FAILS):
                    self.assertFalse(hand.check_pair_code("bad", "locked")[0])
                self.assertFalse(hand.check_pair_code("222222", "locked")[0])
                self.assertTrue(hand.check_pair_code("222222", "other")[0])
                self.assertEqual("222222", module.HandServer(temp, "hand", "test").get_or_create_pair_code())

    def test_mdns_names_compression_and_response_records(self):
        for module in self.modules():
            with self.subTest(module=module.__name__):
                name = module.MDNS_SERVICE_TYPE
                encoded = module._dns_encode_name(name)
                self.assertEqual((name, len(encoded)), module._dns_decode_name(encoded, 0))
                self.assertEqual((name, len(encoded) + 2), module._dns_decode_name(encoded + b"\xc0\x00", len(encoded)))
                packet = module._mdns_build_response("instance", "host", "192.0.2.5", 23456, "hand-id", "fp")
                self.assertEqual((0, 0x8400, 0, 4, 0, 0), struct.unpack(">HHHHHH", packet[:12]))
                self.assertIn(struct.pack(">HHH", 0, 0, 23456), packet)
                self.assertIn(b"id=hand-id", packet)
                self.assertIn(b"fp=fp", packet)
                self.assertIn(socket.inet_aton("192.0.2.5"), packet)
                no_ip = module._mdns_build_response("instance", "host", None, 23456, "hand-id", "fp")
                self.assertEqual(3, struct.unpack(">HHHHHH", no_ip[:12])[3])

    def test_mdns_answers_service_query_and_ignores_unrelated_and_response_packets(self):
        for module in self.modules():
            with self.subTest(module=module.__name__), tempfile.TemporaryDirectory() as temp:
                hand = module.HandServer(temp, "hand", "test", port=23456)
                with patch.object(module, "_mdns_lan_ip", return_value="192.0.2.5"):
                    advertiser = module.MdnsAdvertiser(hand)
                advertiser.sock = Mock()
                def query(name):
                    return struct.pack(">HHHHHH", 0, 0, 1, 0, 0, 0) + module._dns_encode_name(name) + struct.pack(">HH", 12, 1)
                advertiser._answer(query("unrelated.local"), ("local", 5353))
                advertiser.sock.sendto.assert_not_called()
                advertiser._answer(query(module.MDNS_SERVICE_TYPE), ("local", 5353))
                packet, destination = advertiser.sock.sendto.call_args.args
                self.assertEqual((module.MDNS_GROUP, module.MDNS_PORT), destination)
                advertiser.sock.sendto.reset_mock()
                advertiser._answer(packet, ("local", 5353))
                advertiser.sock.sendto.assert_not_called()


if __name__ == "__main__":
    unittest.main()
