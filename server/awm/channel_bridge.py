"""Channel dispatch bridge (channel completion phase 4).

Connects channel-originated *explicit* task dispatch — the user
tapping "convert to formal task" on a channel message — to the AWM
arbitration stack (keys / statemachine / evidence) that already
lives in this package:

- One effect_key per (channel, message, target): minted once at the
  dispatch entry point via KeyMint, so a double-tap or a retried
  dispatch of the same message to the same target reuses the SAME
  key and can never create a second effect (the store also treats
  re-registration of the same key+identity as idempotent).
- The dispatch then walks the state machine: queued (E0) ->
  transported (E1) -> claimed (single executor, atomic) ->
  action_attempted (E2) -> effect_confirmed (E3, authoritative
  receipt only where the state machine requires it). Ambiguous
  outcomes go to indeterminate and freeze automation, per SPEC-P0.
- The grade surfaced to the channel UI is STATE_EVIDENCE for the
  current state; the channel only *displays* it — execution never
  happens inside the channel itself.

This module is the integration seam: the platform server (which is
not part of this repo) calls ChannelDispatchArbiter when a channel
message is converted to a task. Nothing here talks to the network.
"""

from __future__ import annotations

from typing import Optional

from .evidence import EvidenceLog
from .keys import KeyMint
from .statemachine import STATE_EVIDENCE, StateMachine
from .store import ArbitrationStore, UnknownEffect

__all__ = ["ChannelDispatchArbiter", "dispatch_command_identity"]


def dispatch_command_identity(channel_id: str, message_id: str) -> str:
    """Stable command identity for one channel dispatch intent."""
    return f"channel-dispatch:{channel_id}:{message_id}"


class ChannelDispatchArbiter:
    """Arbitration for tasks dispatched explicitly from a channel."""

    def __init__(self, db_path: str, evidence_path: str):
        self._mint = KeyMint()
        self._store = ArbitrationStore(db_path)
        self._sm = StateMachine(self._store)
        self._evidence = EvidenceLog(evidence_path)

    # -- entry point ---------------------------------------------------

    def mint_dispatch_key(self, channel_id: str, message_id: str,
                          target: str) -> str:
        """Mint (once) and enqueue the effect_key for a dispatch.

        Calling this again for the same channel message and target —
        a double-tap, a retried request — returns the same key and
        does not register a second effect.
        """
        identity = dispatch_command_identity(channel_id, message_id)
        key = self._mint.mint_for_command(identity, [target])[target]
        try:
            self._store.get_effect(key.value)
        except UnknownEffect:
            self._sm.enqueue(key)
        return key.value

    def key_for_retry(self, channel_id: str, message_id: str,
                      target: str) -> str:
        """The already-minted key for a retry path (never re-mints)."""
        identity = dispatch_command_identity(channel_id, message_id)
        return self._mint.key_for(identity, target).value

    # -- lifecycle -------------------------------------------------------

    def mark_transported(self, effect_key: str) -> None:
        self._sm.mark_transported(effect_key)

    def claim(self, effect_key: str, actor: str, lease_seconds: float = 60.0):
        """Single-executor claim; a second claimant is denied."""
        return self._sm.claim(effect_key, actor, lease_seconds=lease_seconds)

    def mark_attempted(self, effect_key: str, actor: str) -> None:
        self._sm.mark_attempted(effect_key, actor)

    def confirm_effect(self, effect_key: str,
                       via: Optional[str] = None) -> None:
        self._sm.confirm_effect(effect_key, via=via)

    def mark_indeterminate(self, effect_key: str) -> None:
        self._sm.mark_indeterminate(effect_key)

    def escalate_human(self, effect_key: str) -> None:
        self._sm.escalate_human(effect_key)

    # -- read side (what the channel UI shows) ---------------------------

    def state(self, effect_key: str) -> str:
        return self._sm.state(effect_key)

    def evidence_grade(self, effect_key: str) -> Optional[str]:
        """E0-E3 grade for the current state (None when the state
        itself carries no grade, e.g. claimed/indeterminate). An
        evidence-log grade, when one exists, wins over the state
        mapping (an authoritative receipt is stronger than a state)."""
        logged = self._evidence.highest_grade(effect_key)
        if logged is not None:
            return logged
        return STATE_EVIDENCE.get(self._sm.state(effect_key))
