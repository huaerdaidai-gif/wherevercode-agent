"""B-origin WebSocket/token behavior through both real Hand modules."""
import json
import socket
import tempfile
import unittest
from unittest.mock import patch

import hand_server as hs
from test_hand_server import load_bundled_hand_server


class HandWsTests(unittest.TestCase):
    def modules(self):
        return (hs, load_bundled_hand_server())

    def test_ticket_expiry_single_use_and_pairing_revocation(self):
        for module in self.modules():
            with self.subTest(module=module.__name__), tempfile.TemporaryDirectory() as temp:
                hand = module.HandServer(temp, "hand", "test")
                hand.state.save_json("paired_clients.json", {"owner": {"status": "APPROVED"}})
                with patch.object(module.time, "time", return_value=100):
                    token, ttl = hand.issue_ws_token("owner")
                self.assertEqual(120, ttl)
                with patch.object(module.time, "time", return_value=219):
                    self.assertEqual("owner", hand.consume_ws_token(token))
                    self.assertIsNone(hand.consume_ws_token(token))
                with patch.object(module.time, "time", return_value=100):
                    expired, _ = hand.issue_ws_token("owner")
                with patch.object(module.time, "time", return_value=221):
                    self.assertIsNone(hand.consume_ws_token(expired))
                revoked, _ = hand.issue_ws_token("owner")
                hand.state.save_json("paired_clients.json", {"owner": {"status": "REVOKED"}})
                self.assertIsNone(hand.consume_ws_token(revoked))
                self.assertIsNone(hand.consume_ws_token("invalid"))

    def test_slow_subscriber_queue_is_bounded_and_identity_isolated(self):
        for module in self.modules():
            with self.subTest(module=module.__name__), tempfile.TemporaryDirectory() as temp:
                hand = module.HandServer(temp, "hand", "test")
                owner = hand._ws_subscribe("owner")
                other = hand._ws_subscribe("other")
                for i in range(module.WS_QUEUE_MAX + 3):
                    hand.publish_event("owner", "agent.run.progress", {"i": i})
                self.assertEqual(module.WS_QUEUE_MAX, owner.queue.qsize())
                self.assertEqual(3, json.loads(owner.queue.get_nowait())["data"]["i"])
                self.assertTrue(other.queue.empty())
                hand._ws_unsubscribe(owner)
                self.assertNotIn(owner, hand.ws_subscribers)

    def test_masked_fragmentation_ping_and_close(self):
        for module in self.modules():
            with self.subTest(module=module.__name__):
                client, server = socket.socketpair()
                try:
                    def frame(payload, opcode=1, fin=True):
                        mask = b"abcd"
                        return bytes([(0x80 if fin else 0) | opcode, 0x80 | len(payload)]) + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
                    client.sendall(frame(b"p", 9) + frame(b"hel", 1, False) + frame(b"lo", 0))
                    self.assertEqual((module.WS_OP_TEXT, b"hello"), module.ws_read_message(server, 2))
                    client.settimeout(2)
                    self.assertEqual(module.ws_encode_frame(b"p", module.WS_OP_PONG), client.recv(20))
                    client.sendall(frame(b"", 8))
                    with self.assertRaises(module.WsClosed):
                        module.ws_read_message(server, 2)
                finally:
                    client.close()
                    server.close()

    def test_handshake_vector_and_frame_length_boundaries(self):
        for module in self.modules():
            with self.subTest(module=module.__name__):
                self.assertEqual("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", module.ws_compute_accept("dGhlIHNhbXBsZSBub25jZQ=="))
                for size in (0, 125, 126, 65535, 65536):
                    frame = module.ws_encode_frame(b"x" * size)
                    self.assertEqual(0x81, frame[0])
                    expected = 2 if size < 126 else 4 if size < 65536 else 10
                    self.assertEqual(size + expected, len(frame))


if __name__ == "__main__":
    unittest.main()
