"""effect_key minting (SPEC-P0 section 1, consensus minting rules).

An effect_key is the identity of a side effect. Rules:

- Minted exactly once, at the user-command entry point, and carried
  end-to-end. Nothing downstream ever mints.
- Identity = (command_identity, target_resource). The target dimension
  is part of the identity: a command fanning out to N targets yields
  N distinct effect_keys, each with its own consumed/indeterminate
  lifecycle (T17).
- Automatic retries, double-taps and reconnect-resends REUSE the same
  effect_key. They never mint.
- A deliberate user resend goes through a separate entry point
  (``resend``) and mints a NEW identity; it never resurrects an old key.
- Identity is never derived from message text (no content hashing)
  and never from attempt_id (attempts are tracking only).

command_identity is an opaque, caller-supplied stable identifier for
the user command (e.g. a UUID assigned by the UI when the user issues
the command). This module deliberately does not invent one from
payload content.
"""

from __future__ import annotations

import hashlib
import threading
import uuid
from dataclasses import dataclass
from typing import Iterable, Optional

MINT_ENTRY = "entry"     # minted at the command entry point
MINT_RESEND = "resend"   # minted by the deliberate-resend entry point


class KeyError_(Exception):
    """Base class for key-minting errors."""


class NotMinted(KeyError_):
    """No key was minted for this (command_identity, target) pair."""


@dataclass(frozen=True)
class EffectKey:
    value: str
    command_identity: str
    target_resource: str
    minted_via: str
    supersedes: Optional[str] = None

    def __str__(self) -> str:
        return self.value


def _derive_value(command_identity: str, target_resource: str,
                  nonce: str) -> str:
    digest = hashlib.sha256(
        f"{command_identity}\x00{target_resource}\x00{nonce}".encode("utf-8")
    ).hexdigest()
    return f"ek_{digest[:40]}"


class KeyMint:
    """The single minting authority at the command entry point.

    One instance lives at the entry point. It remembers what it minted
    so retries reuse keys; persistence of the *consumed* state is the
    arbitration store's job, not the mint's.
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._by_pair: dict[tuple[str, str], EffectKey] = {}
        self._by_value: dict[str, EffectKey] = {}

    def mint_for_command(
        self, command_identity: str, targets: Iterable[str]
    ) -> dict[str, EffectKey]:
        """Mint (once) the keys for a user command across its targets.

        Calling this again with the same command_identity returns the
        SAME keys -- that is what makes double-taps and automatic
        retries safe. Fan-out: one key per target.
        """
        with self._lock:
            out: dict[str, EffectKey] = {}
            for target in targets:
                pair = (command_identity, target)
                existing = self._by_pair.get(pair)
                if existing is not None:
                    out[target] = existing
                    continue
                key = EffectKey(
                    value=_derive_value(command_identity, target, uuid.uuid4().hex),
                    command_identity=command_identity,
                    target_resource=target,
                    minted_via=MINT_ENTRY,
                )
                self._by_pair[pair] = key
                self._by_value[key.value] = key
                out[target] = key
            return out

    def key_for(self, command_identity: str, target_resource: str) -> EffectKey:
        """Return the already-minted key for a retry/resend path.

        Raises NotMinted if the entry point never minted one -- retry
        paths must never silently mint a fresh identity.
        """
        with self._lock:
            key = self._by_pair.get((command_identity, target_resource))
            if key is None:
                raise NotMinted(
                    f"no effect_key minted for command {command_identity!r} "
                    f"target {target_resource!r}"
                )
            return key

    def resend(self, command_identity: str, target_resource: str) -> EffectKey:
        """Deliberate user resend: mint a NEW identity for the target.

        This is the only way a 'same command again' gets a new key, and
        it is a separate entry point on purpose. The new key records
        which key it supersedes; the old key's lifecycle is untouched
        (if it was indeterminate, it stays indeterminate).
        """
        with self._lock:
            pair = (command_identity, target_resource)
            old = self._by_pair.get(pair)
            key = EffectKey(
                value=_derive_value(command_identity, target_resource,
                                    uuid.uuid4().hex),
                command_identity=command_identity,
                target_resource=target_resource,
                minted_via=MINT_RESEND,
                supersedes=old.value if old else None,
            )
            self._by_pair[pair] = key
            self._by_value[key.value] = key
            return key

    def lookup(self, value: str) -> Optional[EffectKey]:
        with self._lock:
            return self._by_value.get(value)
