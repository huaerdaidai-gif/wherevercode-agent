"""Tests for awm.relay (SPEC-P0 section 7, T11). Stdlib unittest."""

import os
import sys
import tempfile
import unittest

sys.path.insert(
    0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
)

from awm.relay import (  # noqa: E402
    PEER_ONLINE,
    PEER_UNKNOWN,
    STATE_ACKED,
    STATE_PENDING,
    STATE_SENT,
    Relay,
)


class RelayTestBase(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.path = os.path.join(self._tmp.name, "relay.db")

    def tearDown(self):
        self._tmp.cleanup()

    def open_relay(self, **kw):
        return Relay(self.path, **kw)


class TestOutbox(RelayTestBase):
    def test_enqueue_idempotent_and_persistent(self):
        with self.open_relay() as r:
            self.assertTrue(r.enqueue("m1", "hello", now=1000.0))
            self.assertFalse(r.enqueue("m1", "hello", now=1001.0))
        with self.open_relay() as r:  # survives restart
            pending = r.next_pending()
            self.assertEqual([m["message_id"] for m in pending], ["m1"])
            self.assertEqual(r.pending_count(), 1)

    def test_send_ack_flow(self):
        with self.open_relay() as r:
            r.enqueue("m1", "x", now=1000.0)
            r.mark_sent("m1", now=1001.0)
            self.assertEqual(r.outbox_state("m1"), STATE_SENT)
            r.ack("m1")
            self.assertEqual(r.outbox_state("m1"), STATE_ACKED)
            self.assertEqual(r.pending_count(), 0)

    def test_sweep_requeues_unacked_never_fails(self):
        # T11 core: no ack is not failure; the sweeper re-offers and
        # the message is never dead-lettered, however often this runs.
        with self.open_relay(ack_timeout_seconds=60) as r:
            r.enqueue("m1", "x", now=1000.0)
            r.mark_sent("m1", now=1001.0)
            self.assertEqual(r.sweep(now=1030.0), 0)   # within window
            self.assertEqual(r.sweep(now=2000.0), 1)   # stale -> requeue
            self.assertEqual(r.outbox_state("m1"), STATE_PENDING)
            r.mark_sent("m1", now=2001.0)
            self.assertEqual(r.sweep(now=99999.0), 1)  # again, still alive
            self.assertEqual(r.outbox_state("m1"), STATE_PENDING)
            self.assertEqual(r.pending_count(), 1)

    def test_delivery_attempts_are_transport_only(self):
        with self.open_relay(ack_timeout_seconds=10) as r:
            r.enqueue("m1", "x", effect_key="ek1", now=1000.0)
            r.mark_sent("m1", now=1001.0)
            r.sweep(now=2000.0)
            r.mark_sent("m1", now=2001.0)
            row = r.next_pending(limit=0) or None
            rows = r._conn.execute(
                "SELECT delivery_attempts FROM outbox WHERE message_id='m1'"
            ).fetchone()
            self.assertEqual(rows["delivery_attempts"], 2)


class TestInbox(RelayTestBase):
    def test_receive_dedupes_by_message_id(self):
        with self.open_relay() as r:
            self.assertTrue(r.receive("m1", "payload", now=1000.0))
            self.assertFalse(r.receive("m1", "payload", now=1002.0))
            self.assertEqual(len(r.inbox_messages()), 1)


class TestPeerLiveness(RelayTestBase):
    def test_silence_is_unknown_never_dead(self):
        with self.open_relay(heartbeat_fresh_seconds=90) as r:
            self.assertEqual(r.peer_status(now=1000.0), PEER_UNKNOWN)
            r.note_peer_seen(now=1000.0)
            self.assertEqual(r.peer_status(now=1050.0), PEER_ONLINE)
            # Long silence degrades to 'unknown', never to a death fact.
            self.assertEqual(r.peer_status(now=99999.0), PEER_UNKNOWN)


if __name__ == "__main__":
    unittest.main()
