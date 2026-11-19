"""Tests for awm.statemachine (SPEC-P0 section 2). Stdlib unittest."""

import os
import sys
import tempfile
import unittest

sys.path.insert(
    0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
)

from awm.keys import KeyMint  # noqa: E402
from awm.statemachine import (  # noqa: E402
    ACTION_ATTEMPTED,
    CLAIMED,
    EFFECT_CONFIRMED,
    HUMAN_REQUIRED,
    INDETERMINATE,
    QUEUED,
    TRANSPORTED,
    VIA_AUTHORITATIVE_RECEIPT,
    CapabilityDenied,
    IllegalTransition,
    InjectionRefused,
    StateMachine,
)
from awm.store import ArbitrationStore, ClaimDenied  # noqa: E402


class SMTestBase(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.store = ArbitrationStore(os.path.join(self._tmp.name, "arb.db"))
        self.sm = StateMachine(self.store)
        self.mint = KeyMint()

    def tearDown(self):
        self.store.close()
        self._tmp.cleanup()

    def key(self, cmd="cmd-1", target="dev-a"):
        k = self.mint.mint_for_command(cmd, [target])[target]
        self.sm.enqueue(k)
        return k.value

    def transported(self, **kw):
        v = self.key(**kw)
        self.sm.mark_transported(v)
        return v


class TestHappyPath(SMTestBase):
    def test_full_lifecycle(self):
        v = self.key()
        self.assertEqual(self.sm.state(v), QUEUED)
        self.sm.mark_transported(v)
        self.assertEqual(self.sm.state(v), TRANSPORTED)
        claim = self.sm.claim(v, "actor-1")
        self.assertTrue(claim.can_inject)
        self.assertEqual(self.sm.state(v), CLAIMED)
        self.sm.mark_attempted(v, "actor-1")
        self.assertEqual(self.sm.state(v), ACTION_ATTEMPTED)
        self.sm.confirm_effect(v)
        self.assertEqual(self.sm.state(v), EFFECT_CONFIRMED)

    def test_illegal_jumps_raise(self):
        v = self.key()
        with self.assertRaises(IllegalTransition):
            self.sm.confirm_effect(v)  # queued -> confirmed
        self.sm.mark_transported(v)
        with self.assertRaises(InjectionRefused):
            self.sm.mark_attempted(v, "actor-1")  # not claimed: gate refuses
        self.sm.claim(v, "actor-1")
        self.sm.mark_attempted(v, "actor-1")
        self.sm.confirm_effect(v)
        with self.assertRaises(IllegalTransition):
            self.sm.mark_indeterminate(v)  # terminal


class TestClaimRules(SMTestBase):
    def test_claim_requires_transported(self):
        v = self.key()
        with self.assertRaises(ClaimDenied):
            self.sm.claim(v, "actor-1")

    def test_double_claim_denied(self):
        v = self.transported()
        self.sm.claim(v, "actor-1")
        with self.assertRaises(ClaimDenied):
            self.sm.claim(v, "actor-2")

    def test_notifier_has_no_injection_capability(self):
        # T16: refusal happens at the capability layer.
        v = self.transported()
        note = self.sm.claim_notifier(v, "watcher")
        self.assertFalse(note.can_inject)
        with self.assertRaises(CapabilityDenied):
            self.sm.assert_can_inject(note)

    def test_zombie_executor_refused_after_lease_expiry(self):
        # T14 core: executor frozen past its lease, resumes, tries to
        # inject -- before_inject revalidation must refuse.
        v = self.transported()
        self.sm.claim(v, "actor-1", lease_seconds=10, now=1000.0)
        with self.assertRaises(InjectionRefused):
            self.sm.before_inject(v, "actor-1", now=2000.0)
        with self.assertRaises(InjectionRefused):
            self.sm.mark_attempted(v, "actor-1", now=2000.0)

    def test_wrong_actor_cannot_attempt(self):
        v = self.transported()
        self.sm.claim(v, "actor-1")
        with self.assertRaises(InjectionRefused):
            self.sm.mark_attempted(v, "actor-2")


class TestIndeterminate(SMTestBase):
    def stuck(self):
        v = self.transported()
        self.sm.claim(v, "actor-1")
        self.sm.mark_attempted(v, "actor-1")
        self.sm.mark_indeterminate(v)
        return v

    def test_no_reexecution_after_indeterminate(self):
        # T6 core: no replay path exists once undecided.
        v = self.stuck()
        with self.assertRaises(ClaimDenied):
            self.sm.claim(v, "actor-2")
        note = self.sm.claim_notifier(v, "watcher")
        self.assertFalse(note.can_inject)
        with self.assertRaises(InjectionRefused):
            self.sm.mark_attempted(v, "actor-1")  # gate refuses: not claimed

    def test_receipt_is_the_only_exit(self):
        v = self.stuck()
        with self.assertRaises(IllegalTransition):
            self.sm.confirm_effect(v)  # no via -> refused
        self.sm.confirm_effect(v, via=VIA_AUTHORITATIVE_RECEIPT)
        self.assertEqual(self.sm.state(v), EFFECT_CONFIRMED)

    def test_human_required_one_way(self):
        v = self.stuck()
        self.sm.escalate_human(v)
        self.assertEqual(self.sm.state(v), HUMAN_REQUIRED)
        with self.assertRaises(IllegalTransition):
            self.sm.mark_indeterminate(v)  # cannot go back
        with self.assertRaises(IllegalTransition):
            self.sm.confirm_effect(v)  # still needs the receipt
        self.sm.confirm_effect(v, via=VIA_AUTHORITATIVE_RECEIPT)
        self.assertEqual(self.sm.state(v), EFFECT_CONFIRMED)


if __name__ == "__main__":
    unittest.main()
