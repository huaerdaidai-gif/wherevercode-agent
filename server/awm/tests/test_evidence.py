"""Tests for awm.evidence (SPEC-P0 section 4). Stdlib unittest."""

import os
import sys
import tempfile
import unittest

sys.path.insert(
    0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
)

from awm.evidence import (  # noqa: E402
    GRADE_E2,
    GRADE_E3,
    KIND_AUTHORITATIVE_RECEIPT,
    KIND_EXECUTION_RECEIPT,
    KIND_HUMAN_CONFIRMATION,
    KIND_OBSERVATION,
    EnvFingerprint,
    EvidenceLog,
    UnboundEvidence,
)

ENV = EnvFingerprint(oem="Xiaomi", os_api_level=34, actor_id="actor-1")
OTHER_ENV = EnvFingerprint(oem="Samsung", os_api_level=33, actor_id="actor-9")


class EvidenceTestBase(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.log = EvidenceLog(os.path.join(self._tmp.name, "ev.db"))
        self.log.bind_effect("ek1", ENV, injection_subject="accessibility-svc")

    def tearDown(self):
        self.log.close()
        self._tmp.cleanup()


class TestPromotion(EvidenceTestBase):
    def test_observation_never_promotes_alone(self):
        # T10 core: read-back / observation cannot establish E3 and
        # cannot clear indeterminate.
        self.log.record("ek1", KIND_OBSERVATION, "reader",
                        env=ENV, observer_subject="other-reader")
        self.assertFalse(self.log.effect_confirmed("ek1"))
        self.assertFalse(self.log.can_clear_indeterminate("ek1"))
        self.assertEqual(self.log.highest_grade("ek1"), GRADE_E2)

    def test_same_subject_observation_disqualified(self):
        # Observer sharing the injection's permission subject is
        # permanently ineligible, even as supporting outcome evidence.
        self.log.record("ek1", KIND_OBSERVATION, "a11y-reader",
                        env=ENV, observer_subject="accessibility-svc")
        self.assertIsNone(self.log.resolve_outcome("ek1"))
        self.assertFalse(self.log.effect_confirmed("ek1"))

    def test_execution_receipt_is_not_authoritative(self):
        # Distinct terms, distinct records: an execution receipt alone
        # never establishes the external effect.
        self.log.record("ek1", KIND_EXECUTION_RECEIPT, "device", env=ENV)
        self.assertFalse(self.log.effect_confirmed("ek1"))
        self.assertEqual(self.log.highest_grade("ek1"), GRADE_E2)

    def test_authoritative_receipt_needs_matching_fingerprint(self):
        # Cross-device extrapolation is forbidden.
        self.log.record("ek1", KIND_AUTHORITATIVE_RECEIPT, "api", env=OTHER_ENV)
        self.assertFalse(self.log.effect_confirmed("ek1"))
        self.log.record("ek1", KIND_AUTHORITATIVE_RECEIPT, "api", env=ENV)
        self.assertTrue(self.log.effect_confirmed("ek1"))
        self.assertEqual(self.log.highest_grade("ek1"), GRADE_E3)

    def test_human_confirmation_second_rank(self):
        self.log.record("ek1", KIND_HUMAN_CONFIRMATION, "funk-record")
        self.assertTrue(self.log.can_clear_indeterminate("ek1"))
        # Total order: an authoritative receipt outranks the human one.
        self.log.record("ek1", KIND_AUTHORITATIVE_RECEIPT, "api", env=ENV)
        winner = self.log.resolve_outcome("ek1")
        self.assertEqual(winner["kind"], KIND_AUTHORITATIVE_RECEIPT)

    def test_unbound_record_rejected(self):
        with self.assertRaises(UnboundEvidence):
            self.log.record("", KIND_OBSERVATION, "reader")

    def test_records_are_append_only_history(self):
        self.log.record("ek1", KIND_OBSERVATION, "r1")
        self.log.record("ek1", KIND_EXECUTION_RECEIPT, "device")
        recs = self.log.records("ek1")
        self.assertEqual([r["source"] for r in recs], ["r1", "device"])


if __name__ == "__main__":
    unittest.main()
