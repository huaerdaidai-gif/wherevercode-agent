"""Effect state machine and claim rules (SPEC-P0 section 2).

The only legal lifecycle of an effect_key:

    queued (E0) -> transported (E1) -> claimed -> action_attempted (E2)
        -> effect_confirmed (E3)

Abnormal path: indeterminate -> human_required, one-way. Neither state
can return to action_attempted, and neither can be re-executed.

Rules wired in here (consensus amendments included):

- Consumption = the commit of an executable claim inside the
  arbitration domain (store.claim), before any injection. A local
  intent write that the arbiter never committed consumes nothing.
- Claims only prevent concurrent double-grants; they do NOT promise
  external exactly-once. Lease expiry, executor change, or a missing
  attempt record never make a consumed/undecided key claimable again;
  afterwards only the notifier role (no injection capability) exists.
- Arbiter unreachable = fail-closed: store exceptions propagate.
- The only automatic exits from indeterminate / human_required are an
  authoritative API idempotent receipt bound to the same effect_key.
  A human saying "it didn't happen" authorises a NEW mint (keys.resend);
  it never revives the old key, so there is no transition for it here.
- Zombie window: an executor resuming after a freeze must revalidate
  its claim with the arbiter before injecting (before_inject), and an
  optional about_to_inject checkpoint is the same call.
- UI-injection channels (Accessibility, notification taps) are closed
  UI channels (section 5): nothing in this module offers them the API
  recovery path; confirm_effect from a stuck state requires the
  authoritative-receipt route regardless of channel claims.
"""

from __future__ import annotations

from typing import Optional

from .keys import EffectKey
from .store import (
    ArbitrationStore,
    Claim,
    ClaimDenied,
    ROLE_EXECUTOR,
    ROLE_NOTIFIER,
)

QUEUED = "queued"
TRANSPORTED = "transported"
CLAIMED = "claimed"
ACTION_ATTEMPTED = "action_attempted"
EFFECT_CONFIRMED = "effect_confirmed"
INDETERMINATE = "indeterminate"
HUMAN_REQUIRED = "human_required"

# Evidence grade a state corresponds to (SPEC-P0 section 4); states
# without a grade produce no evidence by themselves.
STATE_EVIDENCE = {
    QUEUED: "E0",
    TRANSPORTED: "E1",
    ACTION_ATTEMPTED: "E2",
    EFFECT_CONFIRMED: "E3",
}

VIA_AUTHORITATIVE_RECEIPT = "authoritative_receipt"

_LEGAL = {
    QUEUED: {TRANSPORTED, INDETERMINATE},
    TRANSPORTED: {CLAIMED, INDETERMINATE},
    CLAIMED: {ACTION_ATTEMPTED, INDETERMINATE},
    ACTION_ATTEMPTED: {EFFECT_CONFIRMED, INDETERMINATE},
    EFFECT_CONFIRMED: set(),
    INDETERMINATE: {HUMAN_REQUIRED, EFFECT_CONFIRMED},
    HUMAN_REQUIRED: {EFFECT_CONFIRMED},
}

# Transitions that are legal only on the authoritative-receipt route.
_RECEIPT_ONLY = {
    (INDETERMINATE, EFFECT_CONFIRMED),
    (HUMAN_REQUIRED, EFFECT_CONFIRMED),
}


class StateMachineError(Exception):
    """Base class for state-machine errors."""


class IllegalTransition(StateMachineError):
    """The requested transition is not in the legal set."""


class CapabilityDenied(StateMachineError):
    """The claim's role carries no such capability (e.g. notifier
    attempting to inject). Raised at the capability layer, before any
    state check (SPEC-P0 T16)."""


class InjectionRefused(StateMachineError):
    """before_inject revalidation failed (zombie window / stale claim)."""


class StateMachine:
    def __init__(self, store: ArbitrationStore):
        self._store = store

    # -- helpers -----------------------------------------------------------

    def _state(self, effect_key: str) -> str:
        return self._store.get_effect(effect_key)["state"]

    def _advance(self, effect_key: str, to_state: str,
                 via: Optional[str] = None,
                 now: Optional[float] = None) -> None:
        current = self._state(effect_key)
        if to_state not in _LEGAL.get(current, set()):
            raise IllegalTransition(f"{current} -> {to_state} is not legal")
        if (current, to_state) in _RECEIPT_ONLY and via != VIA_AUTHORITATIVE_RECEIPT:
            raise IllegalTransition(
                f"{current} -> {to_state} requires an authoritative "
                "API idempotent receipt for the same effect_key"
            )
        self._store.set_state(effect_key, to_state, now=now)

    # -- lifecycle -----------------------------------------------------------

    def enqueue(self, key: EffectKey) -> None:
        """Register a freshly minted key at E0."""
        self._store.register_effect(
            key.value, key.command_identity, key.target_resource, state=QUEUED
        )

    def mark_transported(self, effect_key: str,
                         now: Optional[float] = None) -> None:
        self._advance(effect_key, TRANSPORTED, now=now)

    def claim(self, effect_key: str, actor: str, lease_seconds: float = 60.0,
              now: Optional[float] = None) -> Claim:
        """Executable claim: the consumption point. Only from transported;
        the store commits claim + consumed mark in one transaction and
        moves the state to claimed."""
        if self._state(effect_key) != TRANSPORTED:
            raise ClaimDenied(
                f"effect {effect_key} is not transported; cannot claim"
            )
        return self._store.claim(
            effect_key, actor, role=ROLE_EXECUTOR,
            lease_seconds=lease_seconds, now=now,
        )

    def claim_notifier(self, effect_key: str, actor: str,
                       lease_seconds: float = 60.0,
                       now: Optional[float] = None) -> Claim:
        """Notifier claim ('tell a human' role). Grantable in any state,
        including after consumption; carries no injection capability
        and changes no state."""
        return self._store.claim(
            effect_key, actor, role=ROLE_NOTIFIER,
            lease_seconds=lease_seconds, now=now,
        )

    # -- injection gate ------------------------------------------------------

    def assert_can_inject(self, claim: Claim) -> None:
        """Capability-layer check: a notifier claim cannot inject, full
        stop -- this is not a state check and cannot be configured away."""
        if not claim.can_inject:
            raise CapabilityDenied(
                f"role {claim.role!r} has no injection capability"
            )

    def before_inject(self, effect_key: str, actor: str,
                      now: Optional[float] = None) -> None:
        """Mandatory checkpoint immediately before any injection.

        Revalidates with the arbiter that the actor still holds the
        active executor claim (zombie-window rule) and that the effect
        is in claimed state. Any doubt = refuse."""
        if self._state(effect_key) != CLAIMED:
            raise InjectionRefused(
                f"effect {effect_key} is not in claimed state"
            )
        if not self._store.revalidate_claim(effect_key, actor, now=now):
            raise InjectionRefused(
                f"claim of {actor} for {effect_key} no longer valid; "
                "refusing to inject"
            )

    def mark_attempted(self, effect_key: str, actor: str,
                       now: Optional[float] = None) -> None:
        """Record that the action was attempted (E2). Runs the injection
        gate first: no valid claim, no attempt record."""
        self.before_inject(effect_key, actor, now=now)
        self._advance(effect_key, ACTION_ATTEMPTED, now=now)

    # -- outcomes --------------------------------------------------------------

    def confirm_effect(self, effect_key: str, via: Optional[str] = None,
                       now: Optional[float] = None) -> None:
        """E3. From action_attempted this is the normal completion; from
        indeterminate / human_required only via an authoritative API
        idempotent receipt bound to this same key."""
        self._advance(effect_key, EFFECT_CONFIRMED, via=via, now=now)

    def mark_indeterminate(self, effect_key: str,
                           now: Optional[float] = None) -> None:
        """The effect may or may not have happened; freeze automation."""
        self._advance(effect_key, INDETERMINATE, now=now)

    def escalate_human(self, effect_key: str,
                       now: Optional[float] = None) -> None:
        self._advance(effect_key, HUMAN_REQUIRED, now=now)

    def state(self, effect_key: str) -> str:
        return self._state(effect_key)
