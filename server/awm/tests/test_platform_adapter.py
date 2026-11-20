"""Tests for awm.platform_adapter (P4-AWM-ADAPT). Stdlib unittest.

The platform is replaced by FakePlatformDispatch, an in-memory double
that mirrors the real platform Store's semantics -- including its
hazards: create_task idempotent by idempotency_key, submit_work
idempotent by attempt_id with an evidence gate, and lease expiry
returning a claimed task to the queue (the double-execution hazard
the adapter exists to neutralise). No real service is contacted.
"""

import os
import sys
import tempfile
import unittest

sys.path.insert(
    0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
)

from awm.evidence import EnvFingerprint  # noqa: E402
from awm.platform_adapter import (  # noqa: E402
    DELIVERY_POLL_FALLBACK,
    DELIVERY_PUSH,
    DELIVERY_UNKNOWN,
    AdapterError,
    OutcomeUnknown,
    PlatformAdapter,
)
from awm.statemachine import CapabilityDenied, InjectionRefused  # noqa: E402
from awm.store import ClaimDenied  # noqa: E402

ENV_A = EnvFingerprint("Xiaomi", 34, "agent-a")
ENV_B = EnvFingerprint("Pixel", 35, "agent-a")


class FakePlatformError(Exception):
    pass


class FakePlatformDispatch:
    """Platform Store double with the platform's real semantics."""

    def __init__(self, lease_seconds=100.0):
        self.lease_seconds = lease_seconds
        self.now = 10_000.0
        self.tasks = {}
        self.calls = []
        self.claim_applies = 0
        self.submit_applies = 0
        self.fail_next_submit_after_apply = False
        self._by_idem = {}
        self._attempts = {}
        self._seq = 0

    def advance(self, seconds):
        self.now += seconds

    def create_task(self, project_id, goal, assignee_id, assignee_name,
                    reviewer_id=None, reviewer_name=None, title=None,
                    created_by="owner", idempotency_key=None):
        self.calls.append(("create_task", idempotency_key))
        if idempotency_key and idempotency_key in self._by_idem:
            return dict(self.tasks[self._by_idem[idempotency_key]])
        self._seq += 1
        task_id = f"task-{self._seq:04d}"
        self.tasks[task_id] = {
            "task_id": task_id, "project_id": project_id, "goal": goal,
            "assignee_id": assignee_id, "assignee_name": assignee_name,
            "state": "queued", "claimed_by_id": None, "lease_until": None,
            "summary": None, "branch": None,
        }
        if idempotency_key:
            self._by_idem[idempotency_key] = task_id
        return dict(self.tasks[task_id])

    def expire_leases(self):
        """The platform hazard: an expired claim returns to the queue
        and may be claimed -- and executed -- by someone else."""
        for row in self.tasks.values():
            if (row["state"] == "claimed" and row["lease_until"] is not None
                    and row["lease_until"] < self.now):
                row["state"] = "queued"
                row["claimed_by_id"] = None
                row["lease_until"] = None

    def claim_task(self, task_id, agent_id, agent_name,
                   expected_version=None, attempt_id=None):
        self.calls.append(("claim_task", task_id))
        if attempt_id and (task_id, "claim_task", attempt_id) in self._attempts:
            return self._attempts[(task_id, "claim_task", attempt_id)]
        row = self.tasks.get(task_id)
        if row is None:
            raise FakePlatformError("task_not_found")
        self.expire_leases()
        row = self.tasks[task_id]
        if row["state"] == "claimed":
            if row["claimed_by_id"] == agent_id:
                row["lease_until"] = self.now + self.lease_seconds
                return {"task": dict(row), "lease_seconds": self.lease_seconds}
            raise FakePlatformError("task_state_claimed")
        if row["state"] != "queued" or row["assignee_id"] != agent_id:
            raise FakePlatformError("task_not_found")
        row["state"] = "claimed"
        row["claimed_by_id"] = agent_id
        row["lease_until"] = self.now + self.lease_seconds
        self.claim_applies += 1
        result = {"task": dict(row), "lease_seconds": self.lease_seconds}
        if attempt_id:
            self._attempts[(task_id, "claim_task", attempt_id)] = result
        return result

    def report_progress(self, task_id, agent_id, agent_name, text,
                        expected_version=None, attempt_id=None):
        self.calls.append(("report_progress", task_id))
        return {"ok": True}

    def heartbeat(self, task_id, agent_id, expected_version=None,
                  attempt_id=None):
        self.calls.append(("heartbeat", task_id))
        row = self.tasks[task_id]
        if row["state"] == "claimed" and row["claimed_by_id"] == agent_id:
            row["lease_until"] = self.now + self.lease_seconds
        return {"ok": True, "lease_seconds": self.lease_seconds}

    def submit_work(self, task_id, agent_id, agent_name, summary,
                    branch=None, commit=None, pr_url=None,
                    expected_version=None, attempt_id=None):
        self.calls.append(("submit_work", task_id))
        if attempt_id and (task_id, "submit_result", attempt_id) in self._attempts:
            return self._attempts[(task_id, "submit_result", attempt_id)]
        if not branch and not commit and not pr_url:
            raise FakePlatformError("evidence_required")
        row = self.tasks.get(task_id)
        if row is None:
            raise FakePlatformError("task_not_found")
        if row["state"] != "claimed" or row["claimed_by_id"] != agent_id:
            raise FakePlatformError("task_state")
        row["state"] = "reviewing"
        row["summary"] = summary
        row["branch"] = branch
        self.submit_applies += 1
        result = {"ok": True, "state": "reviewing"}
        if attempt_id:
            self._attempts[(task_id, "submit_result", attempt_id)] = result
        if self.fail_next_submit_after_apply:
            self.fail_next_submit_after_apply = False
            raise TimeoutError("applied, but the response was lost")
        return result

    def get_task(self, task_id):
        row = self.tasks.get(task_id)
        return dict(row) if row else None


class AdapterTestBase(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.platform = FakePlatformDispatch()
        self.pushed = []
        self.adapter = PlatformAdapter(
            os.path.join(self._tmp.name, "awm"), self.platform,
            push_sender=lambda agent_id, env: self.pushed.append((agent_id, env)),
        )

    def tearDown(self):
        self.adapter.close()
        self._tmp.cleanup()

    def dispatch(self, cmd="cmd-1", target="dev-a", assignee="agent-a"):
        return self.adapter.dispatch(
            command_identity=cmd, target=target, project_id="proj-1",
            goal="do the thing", assignee_id=assignee,
            assignee_name="Agent A", env=ENV_A)

    def to_claimed(self, ticket, actor="agent-a"):
        self.adapter.deliver_via_push()
        self.adapter.on_transport_ack(ticket.message_id)
        return self.adapter.claim(ticket.effect_key, actor, "Agent A")

    def to_attempted(self, ticket, actor="agent-a"):
        self.to_claimed(ticket, actor)
        self.adapter.begin_action(ticket.effect_key, actor)


class TestSimulatedDispatchRound(AdapterTestBase):
    def test_full_round_push_delivery(self):
        ticket = self.dispatch()
        st = self.adapter.status(ticket.effect_key)
        self.assertEqual(st["state"], "queued")
        self.assertEqual(st["evidence_grade"], "E0")
        self.assertFalse(st["consumed"])

        self.assertEqual(self.adapter.deliver_via_push(), 1)
        agent_id, envelope = self.pushed[0]
        self.assertEqual(agent_id, "agent-a")
        self.assertEqual(envelope["kind"], "awm_dispatch_signal")
        self.assertEqual(envelope["effect_key"], ticket.effect_key)

        self.adapter.on_transport_ack(ticket.message_id)
        st = self.adapter.status(ticket.effect_key)
        self.assertEqual(st["state"], "transported")
        self.assertEqual(st["evidence_grade"], "E1")
        self.assertEqual(st["relay_state"], "acked")

        claim = self.adapter.claim(ticket.effect_key, "agent-a", "Agent A")
        self.assertTrue(claim.can_inject)
        st = self.adapter.status(ticket.effect_key)
        self.assertEqual(st["state"], "claimed")
        self.assertTrue(st["consumed"])

        self.adapter.begin_action(ticket.effect_key, "agent-a")
        st = self.adapter.status(ticket.effect_key)
        self.assertEqual(st["state"], "action_attempted")
        self.assertEqual(st["evidence_grade"], "E2")

        result = self.adapter.submit_result(
            ticket.effect_key, "agent-a", "Agent A", "done",
            branch="feat/thing")
        self.assertTrue(result["ok"])
        st = self.adapter.status(ticket.effect_key)
        self.assertEqual(st["state"], "effect_confirmed")
        self.assertEqual(st["evidence_grade"], "E3")
        self.assertTrue(st["effect_confirmed"])
        self.assertEqual(st["platform_state"], "reviewing")
        self.assertEqual(self.platform.submit_applies, 1)

    def test_submit_retry_no_replay(self):
        ticket = self.dispatch()
        self.to_attempted(ticket)
        self.adapter.submit_result(
            ticket.effect_key, "agent-a", "Agent A", "done", branch="feat/x")
        again = self.adapter.submit_result(
            ticket.effect_key, "agent-a", "Agent A", "done", branch="feat/x")
        self.assertTrue(again["ok"])
        self.assertEqual(self.platform.submit_applies, 1)
        self.assertEqual(
            self.adapter.status(ticket.effect_key)["state"], "effect_confirmed")
        # Transport retry is likewise a no-op once acked.
        self.assertEqual(self.adapter.deliver_via_push(), 0)

    def test_dispatch_retry_reuses_identity(self):
        first = self.dispatch()
        second = self.dispatch()
        self.assertEqual(first.effect_key, second.effect_key)
        self.assertEqual(first.task_id, second.task_id)
        self.assertEqual(len(self.platform.tasks), 1)
        self.assertEqual(
            self.adapter.status(first.effect_key)["relay_state"], "pending")


class TestNoDuplicateExecution(AdapterTestBase):
    def test_no_duplicate_claim_after_platform_lease_expiry(self):
        ticket = self.dispatch()
        self.to_claimed(ticket)
        # The platform lease expires and the platform re-offers the
        # task -- on the bare platform, a second execution could now
        # happen. The arbiter refuses: the effect is consumed forever.
        self.platform.advance(10_000)
        self.platform.expire_leases()
        self.assertEqual(
            self.platform.get_task(ticket.task_id)["state"], "queued")
        with self.assertRaises(ClaimDenied):
            self.adapter.claim(ticket.effect_key, "agent-b", "Agent B")
        with self.assertRaises(ClaimDenied):
            self.adapter.claim(ticket.effect_key, "agent-a", "Agent A")
        self.assertEqual(self.platform.claim_applies, 1)
        claim_calls = [c for c in self.platform.calls if c[0] == "claim_task"]
        self.assertEqual(len(claim_calls), 1)

    def test_ambiguous_submit_freezes_and_resolves_by_receipt(self):
        ticket = self.dispatch()
        self.to_attempted(ticket)
        self.platform.fail_next_submit_after_apply = True
        with self.assertRaises(OutcomeUnknown):
            self.adapter.submit_result(
                ticket.effect_key, "agent-a", "Agent A", "done",
                branch="feat/x")
        st = self.adapter.status(ticket.effect_key)
        self.assertEqual(st["state"], "indeterminate")
        self.assertFalse(st["effect_confirmed"])
        # Frozen: no re-claim, no action, no blind replay.
        with self.assertRaises(ClaimDenied):
            self.adapter.claim(ticket.effect_key, "agent-b", "Agent B")
        with self.assertRaises(InjectionRefused):
            self.adapter.begin_action(ticket.effect_key, "agent-a")
        # The only exit: the platform's authoritative idempotent
        # receipt for the same attempt (the stored submit result).
        result = self.adapter.confirm_from_indeterminate(
            ticket.effect_key, "agent-a", "Agent A", "done", branch="feat/x")
        self.assertTrue(result["ok"])
        self.assertEqual(self.platform.submit_applies, 1)
        st = self.adapter.status(ticket.effect_key)
        self.assertEqual(st["state"], "effect_confirmed")
        self.assertTrue(st["effect_confirmed"])

    def test_receipt_fingerprint_mismatch_does_not_confirm(self):
        ticket = self.dispatch()
        self.to_attempted(ticket)
        with self.assertRaises(AdapterError):
            self.adapter.submit_result(
                ticket.effect_key, "agent-a", "Agent A", "done",
                branch="feat/x", receipt_env=ENV_B)
        st = self.adapter.status(ticket.effect_key)
        self.assertEqual(st["state"], "indeterminate")
        self.assertFalse(st["effect_confirmed"])
        # A receipt bound to the right fingerprint resolves it.
        self.adapter.confirm_from_indeterminate(
            ticket.effect_key, "agent-a", "Agent A", "done",
            branch="feat/x", receipt_env=ENV_A)
        self.assertEqual(
            self.adapter.status(ticket.effect_key)["state"], "effect_confirmed")
        self.assertEqual(self.platform.submit_applies, 1)


class TestDeliveryModes(AdapterTestBase):
    def test_poll_fallback_labelled_non_realtime(self):
        adapter = PlatformAdapter(
            os.path.join(self._tmp.name, "awm-fallback"), self.platform)
        try:
            ticket = adapter.dispatch(
                command_identity="cmd-fb", target="dev-a", project_id="p",
                goal="g", assignee_id="agent-a", assignee_name="Agent A",
                env=ENV_A)
            # Push is the primary mode: its absence is a config error,
            # never a silent downgrade to polling.
            with self.assertRaises(AdapterError):
                adapter.deliver_via_push()
            adapter.register_wake_mode("agent-a", DELIVERY_POLL_FALLBACK)
            receipt = adapter.wake("agent-a", ticket.effect_key)
            self.assertEqual(receipt["mode"], DELIVERY_POLL_FALLBACK)
            self.assertEqual(receipt["delivered"], "queued_for_poll")
            self.assertFalse(receipt["realtime"])
            offered = adapter.deliver_via_poll_fallback()
            self.assertEqual(len(offered), 1)
            self.assertEqual(offered[0]["effect_key"], ticket.effect_key)
            # Offered is not transported until actually picked up.
            self.assertEqual(
                adapter.status(ticket.effect_key)["state"], "queued")
            adapter.on_fallback_pickup(ticket.message_id)
            self.assertEqual(
                adapter.status(ticket.effect_key)["state"], "transported")
        finally:
            adapter.close()

    def test_wake_matrix_unknown_stays_unknown_and_push_wakes(self):
        ticket = self.dispatch()
        self.assertEqual(self.adapter.wake_mode("agent-a"), DELIVERY_UNKNOWN)
        receipt = self.adapter.wake("agent-a", ticket.effect_key)
        self.assertEqual(receipt["mode"], DELIVERY_UNKNOWN)
        self.assertEqual(receipt["delivered"], "queued_unknown")
        self.assertFalse(receipt["realtime"])
        self.assertEqual(self.pushed, [])  # nothing pushed for unknown
        self.adapter.register_wake_mode("agent-a", DELIVERY_PUSH)
        receipt = self.adapter.wake("agent-a", ticket.effect_key)
        self.assertEqual(receipt["delivered"], "push")
        self.assertTrue(receipt["realtime"])
        agent_id, envelope = self.pushed[-1]
        self.assertEqual(agent_id, "agent-a")
        self.assertEqual(envelope["kind"], "awm_wake_signal")
        # A wake is a signal envelope only: no model, no prompt.
        self.assertNotIn("model", envelope)
        self.assertNotIn("prompt", envelope)

    def test_scheduled_maintenance_sweeps_unacked(self):
        ticket = self.adapter.dispatch(
            command_identity="cmd-sweep", target="dev-a", project_id="p",
            goal="g", assignee_id="agent-a", assignee_name="Agent A",
            env=ENV_A, now=1000.0)
        self.assertEqual(self.adapter.deliver_via_push(now=1000.0), 1)
        out = self.adapter.run_scheduled_maintenance(now=1000.0 + 121.0)
        self.assertEqual(out["requeued"], 1)
        st = self.adapter.status(ticket.effect_key)
        self.assertEqual(st["relay_state"], "pending")
        # Sweeping is housekeeping: no transport, no claim, no consume.
        self.assertEqual(st["state"], "queued")
        self.assertFalse(st["consumed"])
        self.assertEqual(self.adapter.deliver_via_push(now=1122.0), 1)
        self.adapter.on_transport_ack(ticket.message_id, now=1123.0)
        self.assertEqual(
            self.adapter.status(ticket.effect_key)["state"], "transported")


class TestSignallingAndRoles(AdapterTestBase):
    def test_signalling_is_local_only(self):
        ticket = self.dispatch()
        calls_before = list(self.platform.calls)
        self.adapter.note_peer_seen()
        self.assertEqual(self.adapter.peer_status(), "online")
        self.adapter.note_read(ticket.message_id)
        self.assertTrue(self.adapter.is_read(ticket.message_id))
        self.adapter.wake("agent-a", ticket.effect_key)  # unknown cell
        # Presence / read / wake bookkeeping touched nothing external.
        self.assertEqual(self.platform.calls, calls_before)

    def test_notifier_cannot_inject(self):
        ticket = self.dispatch()
        self.to_claimed(ticket)
        notifier = self.adapter.claim_notifier(ticket.effect_key, "ops-1")
        self.assertFalse(notifier.can_inject)
        with self.assertRaises(CapabilityDenied):
            self.adapter.assert_can_inject(notifier)
        with self.assertRaises(InjectionRefused):
            self.adapter.begin_action(ticket.effect_key, "ops-1")

    def test_begin_action_requires_claim(self):
        ticket = self.dispatch()
        self.adapter.deliver_via_push()
        self.adapter.on_transport_ack(ticket.message_id)
        with self.assertRaises(InjectionRefused):
            self.adapter.begin_action(ticket.effect_key, "agent-a")

    def test_resend_after_human_denial_mints_new_identity(self):
        ticket = self.dispatch()
        self.to_attempted(ticket)
        self.platform.fail_next_submit_after_apply = True
        with self.assertRaises(OutcomeUnknown):
            self.adapter.submit_result(
                ticket.effect_key, "agent-a", "Agent A", "done",
                branch="feat/x")
        self.adapter.escalate_human(ticket.effect_key)
        self.assertEqual(
            self.adapter.status(ticket.effect_key)["state"], "human_required")

        new_ticket = self.adapter.resend_after_human_denial(
            command_identity="cmd-1", target="dev-a", project_id="proj-1",
            goal="do the thing", assignee_id="agent-a",
            assignee_name="Agent A", env=ENV_A)
        self.assertNotEqual(new_ticket.effect_key, ticket.effect_key)
        self.assertNotEqual(new_ticket.task_id, ticket.task_id)
        minted = self.adapter.minted_key(new_ticket.effect_key)
        self.assertEqual(minted.minted_via, "resend")
        self.assertEqual(minted.supersedes, ticket.effect_key)
        # The old key stays frozen; the new identity runs its own round.
        self.assertEqual(
            self.adapter.status(ticket.effect_key)["state"], "human_required")
        self.to_attempted(new_ticket)
        self.adapter.submit_result(
            new_ticket.effect_key, "agent-a", "Agent A", "done",
            branch="feat/x")
        self.assertEqual(
            self.adapter.status(new_ticket.effect_key)["state"],
            "effect_confirmed")


if __name__ == "__main__":
    unittest.main()
