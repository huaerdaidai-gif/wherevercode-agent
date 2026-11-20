"""Tests for the channel dispatch bridge (phase 4).

Run: python3 -m pytest server/awm/tests/test_channel_bridge.py
or:  python3 -m unittest server.awm.tests.test_channel_bridge
"""

import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", ".."))

from awm.channel_bridge import ChannelDispatchArbiter  # noqa: E402
from awm.statemachine import VIA_AUTHORITATIVE_RECEIPT  # noqa: E402
from awm.store import ClaimDenied  # noqa: E402


class ChannelBridgeTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.arbiter = ChannelDispatchArbiter(
            os.path.join(self._tmp.name, "arb.db"),
            os.path.join(self._tmp.name, "evidence.jsonl"),
        )

    def tearDown(self):
        self._tmp.cleanup()

    def test_double_dispatch_reuses_one_key_and_one_effect(self):
        first = self.arbiter.mint_dispatch_key("chan-1", "msg-1", "agent-a")
        second = self.arbiter.mint_dispatch_key("chan-1", "msg-1", "agent-a")
        self.assertEqual(first, second)
        self.assertEqual(
            first,
            self.arbiter.key_for_retry("chan-1", "msg-1", "agent-a"),
        )
        self.assertEqual("queued", self.arbiter.state(first))
        self.assertEqual("E0", self.arbiter.evidence_grade(first))

    def test_distinct_targets_get_distinct_keys(self):
        a = self.arbiter.mint_dispatch_key("chan-1", "msg-1", "agent-a")
        b = self.arbiter.mint_dispatch_key("chan-1", "msg-1", "agent-b")
        self.assertNotEqual(a, b)

    def test_full_lifecycle_grades(self):
        key = self.arbiter.mint_dispatch_key("chan-1", "msg-2", "agent-a")
        self.arbiter.mark_transported(key)
        self.assertEqual("E1", self.arbiter.evidence_grade(key))
        claim = self.arbiter.claim(key, "agent-a")
        self.assertTrue(claim.can_inject)
        # A second executor cannot claim the same effect.
        with self.assertRaises(ClaimDenied):
            self.arbiter.claim(key, "agent-b")
        self.arbiter.mark_attempted(key, "agent-a")
        self.assertEqual("E2", self.arbiter.evidence_grade(key))
        self.arbiter.confirm_effect(key)
        self.assertEqual("effect_confirmed", self.arbiter.state(key))
        self.assertEqual("E3", self.arbiter.evidence_grade(key))

    def test_indeterminate_freezes_and_needs_receipt(self):
        key = self.arbiter.mint_dispatch_key("chan-1", "msg-3", "agent-a")
        self.arbiter.mark_transported(key)
        self.arbiter.mark_indeterminate(key)
        self.assertEqual("indeterminate", self.arbiter.state(key))
        # Confirming out of indeterminate without the authoritative
        # receipt route is refused by the state machine.
        with self.assertRaises(Exception):
            self.arbiter.confirm_effect(key)
        self.arbiter.confirm_effect(key, via=VIA_AUTHORITATIVE_RECEIPT)
        self.assertEqual("effect_confirmed", self.arbiter.state(key))


if __name__ == "__main__":
    unittest.main()
