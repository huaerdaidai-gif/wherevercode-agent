"""Platform adapter: AWM P0 guarantees on the platform dispatch path.

This module adapts the five AWM modules (store / keys / statemachine /
evidence / relay) to the task dispatch path of the platform service
(``v5_platform.py``). It does NOT modify the platform; it only calls
the platform's existing dispatch operations, keyed by the AWM
effect_key:

    owner start_task -> Store.create_task(idempotency_key=effect_key)
    agent claim_task(attempt_id=effect_key)
    agent report_progress / heartbeat(attempt_id=effect_key)
    agent submit_result -> Store.submit_work(attempt_id=effect_key)

Why an adapter at all -- the platform path on its own lacks three
AWM guarantees:

- A platform claim is NOT a consumption point. ``expire_leases``
  returns a claimed task to the queue and another agent may claim and
  execute it: the same side effect can run twice. Under the adapter,
  the AWM claim (arbitration-domain commit of claim + consumed mark)
  happens FIRST and is forever; a platform lease expiring afterwards
  can resurrect the platform task, but never the effect's
  executability -- a second claimant is refused by the arbiter and
  only the notifier role (no injection capability) remains.
- Platform idempotency is per-(task, action, attempt) result replay.
  It dedupes a retried call, but a fresh attempt after lease expiry
  re-executes. The adapter's identity is the effect_key, minted once
  at the command entry point (keys.KeyMint) and carried end-to-end.
- The platform has no evidence grading and no indeterminate freeze:
  a submit that may or may not have landed can be retried blindly.
  The adapter freezes such effects as indeterminate; the only exits
  are an authoritative platform API idempotent receipt bound to the
  same effect_key (re-issuing submit_work with the same attempt_id
  returns the platform's stored result), or a human denial followed
  by a NEW mint through the resend entry -- never a revival.

Deliberately NOT adapted: platform ``release_task``. Releasing a
platform task does not un-consume an effect; exposing it here would
invite exactly the re-execution the arbiter forbids.

Delivery modes (Funk 2026-11-20 10:17, corrected 10:19):

- PRIMARY mode is PUSH. Dispatch envelopes and wake signals are
  pushed to the agent runtime (the platform's task WebSocket stream,
  a webhook callback, or a vendor push -- represented here by the
  injected ``push_sender`` callable). Push is the only realtime mode.
- Polling exists ONLY as a fallback: ``deliver_via_poll_fallback``
  and the ``poll_fallback`` wake-matrix cell are the fallback cell,
  labelled non-realtime everywhere they surface. Nothing in this
  module ever presents a poll result as realtime delivery.
- Timed functions are exempt from the fallback restriction by the
  same ruling: ``run_scheduled_maintenance`` (relay sweeper) is
  timer-driven housekeeping, not a delivery path.

Wake matrix: each agent's delivery capability is graded by push
capability -- ``push`` (realtime), ``poll_fallback`` (fallback cell,
non-realtime), ``unknown``. An unregistered agent's cell stays
``unknown``; it is never defaulted to a usable grade.

Zero-token signalling: this module contains no model/AI client at
all. Delivery, presence, read receipts and wake pushes are pure
program signalling and consume no model tokens. A wake delivers an
envelope to the agent *runtime's door*; whether the runtime then
spends its own tokens producing real output is the runtime's
business -- wake is not a model call.
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass
from typing import Callable, Optional, Protocol

from .evidence import (
    KIND_AUTHORITATIVE_RECEIPT,
    KIND_ENQUEUE,
    KIND_EXECUTION_RECEIPT,
    KIND_TRANSPORT,
    EnvFingerprint,
    EvidenceLog,
)
from .keys import EffectKey, KeyMint
from .relay import Relay
from .statemachine import (
    ACTION_ATTEMPTED,
    EFFECT_CONFIRMED,
    HUMAN_REQUIRED,
    INDETERMINATE,
    QUEUED,
    VIA_AUTHORITATIVE_RECEIPT,
    StateMachine,
)
from .store import (
    ROLE_NOTIFIER,
    ArbitrationStore,
    Claim,
    ClaimDenied,
    UnknownEffect,
)

# Delivery / wake-matrix modes. DELIVERY_POLL_FALLBACK is the fallback
# cell ONLY: non-realtime, always labelled as such. DELIVERY_UNKNOWN
# is an untested cell and stays unknown (SPEC-P0 T3 ruling).
DELIVERY_PUSH = "push"
DELIVERY_POLL_FALLBACK = "poll_fallback"
DELIVERY_UNKNOWN = "unknown"
_DELIVERY_MODES = {DELIVERY_PUSH, DELIVERY_POLL_FALLBACK, DELIVERY_UNKNOWN}


class AdapterError(Exception):
    """Base class for platform-adapter errors."""


class DispatchConflict(AdapterError):
    """The platform rejected an operation after the AWM claim had
    already consumed the effect. The effect is frozen indeterminate;
    it can never be claimed again."""


class OutcomeUnknown(AdapterError):
    """A platform call's outcome is ambiguous (it may or may not have
    landed). The effect is frozen indeterminate; resolve it via
    confirm_from_indeterminate or the human/resend path, never by
    blind replay."""


class PlatformDispatch(Protocol):
    """The slice of the platform Store the adapter drives.

    Signatures mirror v5_platform.Store. Any object with these
    methods (the real Store, or a test double) satisfies the
    interface; the adapter never imports the platform module.
    """

    def create_task(self, project_id, goal, assignee_id, assignee_name,
                    reviewer_id=None, reviewer_name=None, title=None,
                    created_by="owner", idempotency_key=None) -> dict:
        ...

    def claim_task(self, task_id, agent_id, agent_name,
                   expected_version=None, attempt_id=None) -> dict:
        ...

    def report_progress(self, task_id, agent_id, agent_name, text,
                        expected_version=None, attempt_id=None) -> dict:
        ...

    def heartbeat(self, task_id, agent_id, expected_version=None,
                  attempt_id=None) -> dict:
        ...

    def submit_work(self, task_id, agent_id, agent_name, summary,
                    branch=None, commit=None, pr_url=None,
                    expected_version=None, attempt_id=None) -> dict:
        ...

    def get_task(self, task_id) -> Optional[dict]:
        ...


@dataclass(frozen=True)
class DispatchTicket:
    effect_key: str
    message_id: str
    task_id: str
    target: str
    assignee_id: str


class PlatformAdapter:
    """AWM arbitration in front of the platform dispatch path.

    One adapter instance owns one AWM directory (arbiter store,
    evidence log, relay outbox) and drives one platform dispatch
    interface. Dispatch tickets are tracked in memory; dispatch()
    is the entry point and is idempotent per command.
    """

    def __init__(self, awm_dir: str, platform: PlatformDispatch,
                 push_sender: Optional[Callable[[str, dict], None]] = None,
                 claim_lease_seconds: float = 300.0,
                 ack_timeout_seconds: float = 120.0):
        os.makedirs(awm_dir, exist_ok=True)
        self._store = ArbitrationStore(os.path.join(awm_dir, "arbiter.db"))
        self._sm = StateMachine(self._store)
        self._evidence = EvidenceLog(os.path.join(awm_dir, "evidence.db"))
        self._relay = Relay(
            os.path.join(awm_dir, "relay.db"),
            ack_timeout_seconds=ack_timeout_seconds,
        )
        self._mint = KeyMint()
        self._platform = platform
        self._push_sender = push_sender
        self._claim_lease = claim_lease_seconds
        self._tickets: dict[str, DispatchTicket] = {}
        self._by_message: dict[str, str] = {}
        self._envs: dict[str, EnvFingerprint] = {}
        self._wake_modes: dict[str, str] = {}
        self._read_messages: set[str] = set()

    # -- entry point: dispatch -------------------------------------------

    def dispatch(self, *, command_identity: str, target: str,
                 project_id: str, goal: str, assignee_id: str,
                 assignee_name: str, env: EnvFingerprint,
                 reviewer_id: Optional[str] = None,
                 reviewer_name: Optional[str] = None,
                 title: Optional[str] = None,
                 injection_subject: Optional[str] = None,
                 now: Optional[float] = None) -> DispatchTicket:
        """Mint (once) and dispatch a command to one target.

        Retrying dispatch with the same command_identity reuses the
        same effect_key, the same platform task (the platform keys
        create_task idempotency by it) and the same outbox envelope.
        """
        key = self._mint.mint_for_command(command_identity, [target])[target]
        return self._dispatch_with_key(
            key, project_id=project_id, goal=goal,
            assignee_id=assignee_id, assignee_name=assignee_name, env=env,
            reviewer_id=reviewer_id, reviewer_name=reviewer_name,
            title=title, injection_subject=injection_subject, now=now,
        )

    def resend_after_human_denial(self, *, command_identity: str,
                                  target: str, project_id: str, goal: str,
                                  assignee_id: str, assignee_name: str,
                                  env: EnvFingerprint,
                                  reviewer_id: Optional[str] = None,
                                  reviewer_name: Optional[str] = None,
                                  title: Optional[str] = None,
                                  injection_subject: Optional[str] = None,
                                  now: Optional[float] = None) -> DispatchTicket:
        """Deliberate resend after a human recorded that the original
        effect did NOT happen. Mints a NEW identity (keys.resend);
        the old key stays frozen wherever it was -- it is never
        revived, and the new dispatch is a new platform task."""
        key = self._mint.resend(command_identity, target)
        return self._dispatch_with_key(
            key, project_id=project_id, goal=goal,
            assignee_id=assignee_id, assignee_name=assignee_name, env=env,
            reviewer_id=reviewer_id, reviewer_name=reviewer_name,
            title=title, injection_subject=injection_subject, now=now,
        )

    def _dispatch_with_key(self, key: EffectKey, *, project_id, goal,
                           assignee_id, assignee_name, env,
                           reviewer_id, reviewer_name, title,
                           injection_subject, now) -> DispatchTicket:
        existing = self._tickets.get(key.value)
        if existing is not None:
            return existing
        try:
            self._store.get_effect(key.value)
            is_new = False
        except UnknownEffect:
            is_new = True
        self._sm.enqueue(key)  # E0; idempotent for the same identity
        self._evidence.bind_effect(
            key.value, env, injection_subject=injection_subject)
        self._envs[key.value] = env
        if is_new:
            self._evidence.record(
                key.value, KIND_ENQUEUE, source="platform_adapter.dispatch",
                env=env, detail=f"target={key.target_resource}", now=now)
        task = self._platform.create_task(
            project_id, goal, assignee_id, assignee_name,
            reviewer_id=reviewer_id, reviewer_name=reviewer_name,
            title=title, created_by="owner", idempotency_key=key.value)
        message_id = f"msg_{key.value[3:]}"
        envelope = {
            "kind": "awm_dispatch_signal",
            "effect_key": key.value,
            "message_id": message_id,
            "task_id": task["task_id"],
            "assignee_id": assignee_id,
            "target": key.target_resource,
        }
        self._relay.enqueue(
            message_id, json.dumps(envelope, ensure_ascii=False),
            effect_key=key.value, now=now)
        ticket = DispatchTicket(
            effect_key=key.value, message_id=message_id,
            task_id=task["task_id"], target=key.target_resource,
            assignee_id=assignee_id)
        self._tickets[key.value] = ticket
        self._by_message[message_id] = key.value
        return ticket

    def minted_key(self, effect_key: str) -> Optional[EffectKey]:
        """The mint record for an effect_key (identity, minted_via,
        supersedes), or None if this adapter's mint never issued it."""
        return self._mint.lookup(effect_key)

    # -- delivery: push primary, poll fallback ------------------------------

    def deliver_via_push(self, now: Optional[float] = None) -> int:
        """PRIMARY delivery path (realtime): push every pending
        dispatch envelope to its assignee runtime via the configured
        push sender. Returns the number pushed. Without a push sender
        this raises -- push is the primary mode, and its absence is a
        configuration error, never a silent downgrade to polling."""
        if self._push_sender is None:
            raise AdapterError(
                "no push sender configured: push is the primary "
                "delivery mode; configure push_sender or call "
                "deliver_via_poll_fallback explicitly as the fallback")
        sent = 0
        for row in self._relay.next_pending():
            envelope = json.loads(row["payload"])
            self._push_sender(envelope["assignee_id"], envelope)
            self._relay.mark_sent(row["message_id"], now=now)
            sent += 1
        return sent

    def deliver_via_poll_fallback(self, now: Optional[float] = None) -> list:
        """FALLBACK ONLY -- non-realtime. Offer pending envelopes to
        an agent that can only poll (the platform's list_my_tasks /
        record_poll world). Being listed here is NOT realtime
        delivery and must never be presented as such; the effect is
        transported only when the agent actually picks the envelope
        up (on_fallback_pickup)."""
        out = []
        for row in self._relay.next_pending():
            self._relay.mark_sent(row["message_id"], now=now)
            out.append(json.loads(row["payload"]))
        return out

    def on_transport_ack(self, message_id: str,
                         now: Optional[float] = None) -> None:
        """The push channel confirmed transport (E1)."""
        self._transport_confirmed(message_id, via=DELIVERY_PUSH, now=now)

    def on_fallback_pickup(self, message_id: str,
                           now: Optional[float] = None) -> None:
        """A polling agent actually picked the envelope up (E1, via
        the fallback cell -- recorded as such in the evidence trail)."""
        self._transport_confirmed(
            message_id, via=DELIVERY_POLL_FALLBACK, now=now)

    def _transport_confirmed(self, message_id: str, via: str,
                             now: Optional[float]) -> None:
        effect_key = self._by_message.get(message_id)
        if effect_key is None:
            raise AdapterError(f"unknown message_id: {message_id}")
        self._relay.ack(message_id)
        if self._sm.state(effect_key) == QUEUED:
            self._sm.mark_transported(effect_key, now=now)
            self._evidence.record(
                effect_key, KIND_TRANSPORT,
                source="platform_adapter.transport", env=self._envs.get(effect_key),
                detail=f"via={via}", now=now)

    # -- claim: the consumption point ---------------------------------------

    def claim(self, effect_key: str, actor: str, agent_name: str,
              now: Optional[float] = None) -> Claim:
        """Executable claim. The AWM claim commits FIRST (claim +
        consumed mark, one arbitration transaction -- the consumption
        point); only then is the platform task claimed, carrying the
        effect_key as its attempt_id. If the platform rejects after
        consumption, the effect freezes indeterminate: it can never
        be claimed again, by anyone."""
        ticket = self._ticket(effect_key)
        claim = self._sm.claim(
            effect_key, actor, lease_seconds=self._claim_lease, now=now)
        try:
            self._platform.claim_task(
                ticket.task_id, actor, agent_name, attempt_id=effect_key)
        except Exception as exc:
            self._sm.mark_indeterminate(effect_key, now=now)
            raise DispatchConflict(
                f"platform claim for task {ticket.task_id} failed after "
                f"effect {effect_key} was consumed; effect frozen "
                f"indeterminate: {exc}") from exc
        return claim

    def claim_notifier(self, effect_key: str, actor: str,
                       now: Optional[float] = None) -> Claim:
        """Notifier claim ('tell a human'). Grantable in any state,
        including after consumption; carries no injection capability."""
        return self._sm.claim_notifier(
            effect_key, actor, lease_seconds=self._claim_lease, now=now)

    def assert_can_inject(self, claim: Claim) -> None:
        """Capability-layer check (SPEC-P0 T16): a notifier claim
        cannot inject, full stop."""
        self._sm.assert_can_inject(claim)

    # -- action gate -----------------------------------------------------------

    def begin_action(self, effect_key: str, actor: str,
                     now: Optional[float] = None) -> None:
        """The injection gate, run immediately before the agent acts
        on the platform task: revalidates the executor claim with the
        arbiter (zombie-window rule) and records the E2 execution
        receipt. No valid claim, no action."""
        self._sm.mark_attempted(effect_key, actor, now=now)
        self._evidence.record(
            effect_key, KIND_EXECUTION_RECEIPT,
            source=f"agent:{actor}", detail="action_attempted", now=now)

    def report_progress(self, effect_key: str, actor: str, agent_name: str,
                        text: str) -> dict:
        ticket = self._ticket(effect_key)
        return self._platform.report_progress(
            ticket.task_id, actor, agent_name, text, attempt_id=effect_key)

    def heartbeat(self, effect_key: str, actor: str) -> dict:
        ticket = self._ticket(effect_key)
        return self._platform.heartbeat(
            ticket.task_id, actor, attempt_id=effect_key)

    # -- outcome: submit, confirm, freeze, resolve ------------------------------

    def submit_result(self, effect_key: str, actor: str, agent_name: str,
                      summary: str, branch: Optional[str] = None,
                      commit: Optional[str] = None,
                      pr_url: Optional[str] = None,
                      receipt_env: Optional[EnvFingerprint] = None,
                      now: Optional[float] = None) -> dict:
        """Submit the work result on the platform and confirm the
        effect (E3) from the platform's authoritative idempotent
        receipt (its stored result for this attempt_id).

        Safe to retry: once the effect is confirmed, a retry returns
        the platform's stored result without re-applying anything.
        An ambiguous platform failure freezes the effect
        indeterminate instead of replaying blindly."""
        ticket = self._ticket(effect_key)
        state = self._sm.state(effect_key)
        if state == EFFECT_CONFIRMED:
            return self._platform.submit_work(
                ticket.task_id, actor, agent_name, summary, branch=branch,
                commit=commit, pr_url=pr_url, attempt_id=effect_key)
        if state != ACTION_ATTEMPTED:
            raise AdapterError(
                f"effect {effect_key} is {state}; submit requires "
                "action_attempted (pass begin_action first)")
        try:
            result = self._platform.submit_work(
                ticket.task_id, actor, agent_name, summary, branch=branch,
                commit=commit, pr_url=pr_url, attempt_id=effect_key)
        except Exception as exc:
            self._sm.mark_indeterminate(effect_key, now=now)
            raise OutcomeUnknown(
                f"platform submit for effect {effect_key} may or may "
                f"not have landed; effect frozen indeterminate: {exc}"
            ) from exc
        self._record_receipt_and_confirm(
            effect_key, receipt_env, via=None, now=now)
        return result

    def confirm_from_indeterminate(self, effect_key: str, actor: str,
                                   agent_name: str, summary: str,
                                   branch: Optional[str] = None,
                                   commit: Optional[str] = None,
                                   pr_url: Optional[str] = None,
                                   receipt_env: Optional[EnvFingerprint] = None,
                                   now: Optional[float] = None) -> dict:
        """Resolve an indeterminate effect via the ONLY automatic
        exit: re-issue the platform submit with the SAME attempt_id.
        If it landed, the platform returns its stored result -- an
        authoritative API idempotent receipt bound to this effect_key
        -- and the effect confirms. If the call fails again, the
        effect stays indeterminate."""
        ticket = self._ticket(effect_key)
        state = self._sm.state(effect_key)
        if state not in (INDETERMINATE, HUMAN_REQUIRED):
            raise AdapterError(
                f"effect {effect_key} is {state}; only indeterminate / "
                "human_required effects resolve via this path")
        try:
            result = self._platform.submit_work(
                ticket.task_id, actor, agent_name, summary, branch=branch,
                commit=commit, pr_url=pr_url, attempt_id=effect_key)
        except Exception as exc:
            raise OutcomeUnknown(
                f"resolution attempt for effect {effect_key} failed; "
                f"effect remains {state}: {exc}") from exc
        self._record_receipt_and_confirm(
            effect_key, receipt_env, via=VIA_AUTHORITATIVE_RECEIPT, now=now)
        return result

    def _record_receipt_and_confirm(self, effect_key: str,
                                    receipt_env: Optional[EnvFingerprint],
                                    via: Optional[str],
                                    now: Optional[float]) -> None:
        env = receipt_env if receipt_env is not None else self._envs.get(effect_key)
        self._evidence.record(
            effect_key, KIND_AUTHORITATIVE_RECEIPT,
            source="platform.submit_result", env=env,
            detail="platform idempotent result for attempt_id=effect_key",
            now=now)
        if not self._evidence.effect_confirmed(effect_key):
            # The receipt does not bind to this effect's environment
            # fingerprint: it establishes nothing (no cross-device
            # extrapolation). Freeze rather than confirm.
            if self._sm.state(effect_key) not in (INDETERMINATE, HUMAN_REQUIRED):
                self._sm.mark_indeterminate(effect_key, now=now)
            raise AdapterError(
                f"authoritative receipt for effect {effect_key} does "
                "not bind to the effect's environment fingerprint; "
                "effect frozen indeterminate")
        self._sm.confirm_effect(effect_key, via=via, now=now)

    def mark_outcome_unknown(self, effect_key: str,
                             now: Optional[float] = None) -> None:
        """Freeze an effect whose outcome is in doubt (E2 happened,
        E3 unproven). Automation stops; see confirm_from_indeterminate
        and resend_after_human_denial for the exits."""
        self._sm.mark_indeterminate(effect_key, now=now)

    def escalate_human(self, effect_key: str,
                       now: Optional[float] = None) -> None:
        self._sm.escalate_human(effect_key, now=now)

    # -- wake matrix & zero-token signalling --------------------------------------

    def register_wake_mode(self, agent_id: str, mode: str) -> None:
        """Grade an agent's wake cell by its push capability. Only
        register a mode that was actually measured; an unregistered
        agent stays DELIVERY_UNKNOWN."""
        if mode not in _DELIVERY_MODES:
            raise AdapterError(f"unknown wake mode: {mode!r}")
        self._wake_modes[agent_id] = mode

    def wake_mode(self, agent_id: str) -> str:
        return self._wake_modes.get(agent_id, DELIVERY_UNKNOWN)

    def wake(self, agent_id: str, effect_key: str) -> dict:
        """Wake an agent runtime. Pure signalling, zero model tokens:
        for a push-capable agent the wake envelope is pushed to the
        runtime's door; for the fallback cell / unknown cell nothing
        is pushed and nothing is claimed realtime -- the dispatch
        envelope simply waits in the durable outbox. This method
        never invokes a model."""
        mode = self.wake_mode(agent_id)
        envelope = {
            "kind": "awm_wake_signal",
            "effect_key": effect_key,
            "agent_id": agent_id,
            "mode": mode,
        }
        if mode == DELIVERY_PUSH:
            if self._push_sender is None:
                raise AdapterError(
                    "agent graded push but no push sender configured")
            self._push_sender(agent_id, envelope)
            return {"agent_id": agent_id, "mode": mode,
                    "delivered": "push", "realtime": True}
        if mode == DELIVERY_POLL_FALLBACK:
            return {"agent_id": agent_id, "mode": mode,
                    "delivered": "queued_for_poll", "realtime": False}
        return {"agent_id": agent_id, "mode": mode,
                "delivered": "queued_unknown", "realtime": False}

    def note_peer_seen(self, now: Optional[float] = None) -> None:
        """Presence signalling: record an actual sign of life from
        the peer runtime. Pure bookkeeping, zero tokens."""
        self._relay.note_peer_seen(now=now)

    def peer_status(self, now: Optional[float] = None) -> str:
        """'online' only on a fresh recorded heartbeat, else
        'unknown' -- silence is never reported as 'offline'."""
        return self._relay.peer_status(now=now)

    def note_read(self, message_id: str) -> None:
        """Read receipt for a dispatch envelope. Pure signalling
        bookkeeping; changes no state and calls nothing external."""
        if message_id not in self._by_message:
            raise AdapterError(f"unknown message_id: {message_id}")
        self._read_messages.add(message_id)

    def is_read(self, message_id: str) -> bool:
        return message_id in self._read_messages

    # -- timed housekeeping ---------------------------------------------------------

    def run_scheduled_maintenance(self, now: Optional[float] = None) -> dict:
        """Timed housekeeping, driven by a timer -- a timed function,
        NOT a realtime delivery path. The relay sweeper returns
        sent-but-unacked envelopes to pending for re-offer; nothing
        is ever marked permanently failed, and no claim or execution
        state is touched."""
        return {"requeued": self._relay.sweep(now=now)}

    # -- status ------------------------------------------------------------------------

    def status(self, effect_key: str) -> dict:
        ticket = self._tickets.get(effect_key)
        out = {
            "effect_key": effect_key,
            "state": self._sm.state(effect_key),
            "consumed": self._store.is_consumed(effect_key),
            "evidence_grade": self._evidence.highest_grade(effect_key),
            "effect_confirmed": self._evidence.effect_confirmed(effect_key),
        }
        if ticket is not None:
            out["message_id"] = ticket.message_id
            out["task_id"] = ticket.task_id
            out["relay_state"] = self._relay.outbox_state(ticket.message_id)
            get_task = getattr(self._platform, "get_task", None)
            if get_task is not None:
                row = get_task(ticket.task_id)
                out["platform_state"] = row["state"] if row else None
        return out

    # -- internals ------------------------------------------------------------------------

    def _ticket(self, effect_key: str) -> DispatchTicket:
        ticket = self._tickets.get(effect_key)
        if ticket is None:
            raise AdapterError(
                f"no dispatch ticket for effect {effect_key}; "
                "dispatch() is the entry point")
        return ticket

    def close(self) -> None:
        self._store.close()
        self._evidence.close()
        self._relay.close()

    def __enter__(self) -> "PlatformAdapter":
        return self

    def __exit__(self, *exc) -> None:
        self.close()
