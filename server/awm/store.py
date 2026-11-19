"""Arbitration store for AWM P0 (SPEC-P0 section 3).

Single-writer SQLite store (WAL + synchronous=FULL). The store is the
arbitration domain: the *consumption point* of an effect_key is the
commit of an executable claim inside this store's transaction -- never
a local file write outside it (consensus amendment to A-v4).

Invariants enforced here, at the storage layer:

- Durability: a write counts as persisted only after the transaction
  commits AND fsync completes (WAL + FULL synchronous, plus an explicit
  fsync of the database files on every commit).
- consumed is append-only: once an effect_key is consumed it is never
  un-consumed, by any code path, including recovery. On open, the
  consumed set is replayed before the store serves anything.
- Single-active arbiter with monotone term/epoch: if this store's term
  is behind the maximum term ever seen, it refuses to grant claims and
  raises StaleTermError (split-brain guard; the single-active arbiter
  is a known SPOF, mitigated by durable marks + term, not fake HA).
- Fail-closed: if the store cannot be opened or a transaction fails,
  callers get an exception, never a silent degradation.
- Lease expiry never resurrects a consumed key: after consumption the
  only grantable role is the notifier role (no injection rights).
"""

from __future__ import annotations

import os
import sqlite3
import threading
import time
from dataclasses import dataclass
from typing import Optional

ROLE_EXECUTOR = "executor"   # may inject (subject to state machine checks)
ROLE_NOTIFIER = "notifier"   # may only notify a human; never injects


class StoreError(Exception):
    """Base class for arbitration-store errors."""


class StoreUnavailable(StoreError):
    """The arbitration domain is unreachable; callers must fail closed."""


class StaleTermError(StoreError):
    """This arbiter's term is behind the max seen term; refuse claims."""


class UnknownEffect(StoreError):
    """The effect_key was never registered in this store."""


class ClaimDenied(StoreError):
    """A claim was refused (already consumed / wrong state / stale term)."""


@dataclass(frozen=True)
class Claim:
    effect_key: str
    actor: str
    role: str
    granted_at: float
    lease_expires_at: float

    @property
    def can_inject(self) -> bool:
        # Capability-level fact about the role itself (SPEC-P0 T16):
        # a notifier claim carries no injection capability at all.
        return self.role == ROLE_EXECUTOR


_SCHEMA = """
CREATE TABLE IF NOT EXISTS meta (
    k TEXT PRIMARY KEY,
    v TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS effects (
    effect_key       TEXT PRIMARY KEY,
    command_identity TEXT NOT NULL,
    target_resource  TEXT NOT NULL,
    state            TEXT NOT NULL,
    consumed         INTEGER NOT NULL DEFAULT 0,
    created_at       REAL NOT NULL,
    updated_at       REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS claims (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    effect_key       TEXT NOT NULL,
    actor            TEXT NOT NULL,
    role             TEXT NOT NULL,
    granted_at       REAL NOT NULL,
    lease_expires_at REAL NOT NULL,
    active           INTEGER NOT NULL DEFAULT 1
);
CREATE INDEX IF NOT EXISTS idx_claims_effect ON claims(effect_key, active);
"""


class ArbitrationStore:
    """Durable arbitration store. One process, one writer."""

    def __init__(self, path: str):
        self._path = path
        self._lock = threading.RLock()
        self._closed = False
        try:
            self._conn = sqlite3.connect(path, check_same_thread=False)
            self._conn.row_factory = sqlite3.Row
            self._conn.execute("PRAGMA journal_mode=WAL")
            self._conn.execute("PRAGMA synchronous=FULL")
            self._conn.executescript(_SCHEMA)
            self._conn.commit()
            self._fsync_files()
        except sqlite3.Error as exc:
            raise StoreUnavailable(f"cannot open arbitration store: {exc}") from exc
        # Replay consumed marks before serving anything (SPEC-P0 section 3).
        self._consumed: set[str] = {
            row["effect_key"]
            for row in self._conn.execute(
                "SELECT effect_key FROM effects WHERE consumed = 1"
            )
        }
        self._term = int(self._meta_get("term", "1"))
        self._max_seen_term = int(self._meta_get("max_seen_term", str(self._term)))
        # A store restored from an older backup has term < max_seen_term:
        # it must refuse claims rather than resurrect consumed keys.
        self._stale = self._term < self._max_seen_term

    # -- internals ------------------------------------------------------

    def _meta_get(self, key: str, default: str) -> str:
        row = self._conn.execute(
            "SELECT v FROM meta WHERE k = ?", (key,)
        ).fetchone()
        return row["v"] if row else default

    def _meta_set(self, key: str, value: str) -> None:
        self._conn.execute(
            "INSERT INTO meta(k, v) VALUES(?, ?) "
            "ON CONFLICT(k) DO UPDATE SET v = excluded.v",
            (key, value),
        )

    def _fsync_files(self) -> None:
        # synchronous=FULL already fsyncs on commit; fsync the files
        # explicitly as well so "persisted" never rests on page cache.
        for suffix in ("", "-wal"):
            try:
                fd = os.open(self._path + suffix, os.O_RDWR)
            except OSError:
                continue
            try:
                os.fsync(fd)
            finally:
                os.close(fd)

    def _commit_durable(self) -> None:
        try:
            self._conn.commit()
        except sqlite3.Error as exc:
            self._conn.rollback()
            raise StoreUnavailable(f"commit failed: {exc}") from exc
        self._fsync_files()

    def _check_open(self) -> None:
        if self._closed:
            raise StoreUnavailable("arbitration store is closed")

    def _assert_fresh(self) -> None:
        if self._stale:
            raise StaleTermError(
                f"arbiter term {self._term} is behind max seen term "
                f"{self._max_seen_term}; refusing to grant claims"
            )

    # -- term / epoch ----------------------------------------------------

    @property
    def term(self) -> int:
        return self._term

    @property
    def stale(self) -> bool:
        return self._stale

    def note_seen_term(self, term: int) -> None:
        """Record a term observed from a peer incarnation of the arbiter."""
        with self._lock:
            self._check_open()
            if term > self._max_seen_term:
                self._max_seen_term = term
                self._meta_set("max_seen_term", str(term))
                self._commit_durable()
            if self._term < self._max_seen_term:
                self._stale = True

    def advance_term(self, new_term: int) -> None:
        """Move this arbiter to a newer term (explicit failover step)."""
        with self._lock:
            self._check_open()
            if new_term <= self._max_seen_term and new_term <= self._term:
                raise StoreError(
                    f"term must increase monotonically: current {self._term}, "
                    f"max seen {self._max_seen_term}, requested {new_term}"
                )
            self._term = new_term
            self._max_seen_term = max(self._max_seen_term, new_term)
            self._meta_set("term", str(new_term))
            self._meta_set("max_seen_term", str(self._max_seen_term))
            self._stale = False
            self._commit_durable()

    # -- effects ----------------------------------------------------------

    def register_effect(
        self,
        effect_key: str,
        command_identity: str,
        target_resource: str,
        state: str = "queued",
        now: Optional[float] = None,
    ) -> dict:
        """Register an effect_key once. Re-registering the same key with
        the same identity is idempotent; a conflicting identity raises."""
        with self._lock:
            self._check_open()
            ts = time.time() if now is None else now
            row = self._conn.execute(
                "SELECT * FROM effects WHERE effect_key = ?", (effect_key,)
            ).fetchone()
            if row is not None:
                if (
                    row["command_identity"] != command_identity
                    or row["target_resource"] != target_resource
                ):
                    raise StoreError(
                        f"effect_key {effect_key} already bound to a "
                        "different identity"
                    )
                return dict(row)
            self._conn.execute(
                "INSERT INTO effects(effect_key, command_identity, "
                "target_resource, state, consumed, created_at, updated_at) "
                "VALUES(?, ?, ?, ?, 0, ?, ?)",
                (effect_key, command_identity, target_resource, state, ts, ts),
            )
            self._commit_durable()
            return self.get_effect(effect_key)

    def get_effect(self, effect_key: str) -> dict:
        with self._lock:
            self._check_open()
            row = self._conn.execute(
                "SELECT * FROM effects WHERE effect_key = ?", (effect_key,)
            ).fetchone()
            if row is None:
                raise UnknownEffect(effect_key)
            return dict(row)

    def set_state(self, effect_key: str, state: str,
                  now: Optional[float] = None) -> None:
        """Write a state value. Transition *legality* is the state
        machine's job; the store only guarantees durable writes and
        never touches the consumed flag here."""
        with self._lock:
            self._check_open()
            ts = time.time() if now is None else now
            cur = self._conn.execute(
                "UPDATE effects SET state = ?, updated_at = ? "
                "WHERE effect_key = ?",
                (state, ts, effect_key),
            )
            if cur.rowcount == 0:
                raise UnknownEffect(effect_key)
            self._commit_durable()

    def is_consumed(self, effect_key: str) -> bool:
        with self._lock:
            self._check_open()
            return effect_key in self._consumed

    def consumed_keys(self) -> frozenset:
        with self._lock:
            self._check_open()
            return frozenset(self._consumed)

    # -- claims: the consumption point -------------------------------------

    def claim(
        self,
        effect_key: str,
        actor: str,
        role: str = ROLE_EXECUTOR,
        lease_seconds: float = 60.0,
        now: Optional[float] = None,
    ) -> Claim:
        """Grant a claim. This is the linearization point of consumption.

        For ROLE_EXECUTOR: succeeds exactly once per effect_key, inside
        one transaction that writes the claim AND the consumed mark
        together (intent in the arbitration domain, same transaction --
        consensus amendment). Any later executor claim for the same key
        is denied forever, whatever the lease did.

        For ROLE_NOTIFIER: grantable also after consumption; carries no
        injection capability and does not consume.
        """
        with self._lock:
            self._check_open()
            self._assert_fresh()
            ts = time.time() if now is None else now
            row = self._conn.execute(
                "SELECT * FROM effects WHERE effect_key = ?", (effect_key,)
            ).fetchone()
            if row is None:
                raise UnknownEffect(effect_key)
            if role == ROLE_EXECUTOR:
                if row["consumed"]:
                    raise ClaimDenied(
                        f"effect_key {effect_key} already consumed; "
                        "executor claim can never be re-granted"
                    )
                # One transaction: claim row + consumed mark + state.
                self._conn.execute(
                    "INSERT INTO claims(effect_key, actor, role, granted_at,"
                    " lease_expires_at, active) VALUES(?, ?, ?, ?, ?, 1)",
                    (effect_key, actor, role, ts, ts + lease_seconds),
                )
                self._conn.execute(
                    "UPDATE effects SET consumed = 1, state = 'claimed',"
                    " updated_at = ? WHERE effect_key = ?",
                    (ts, effect_key),
                )
                self._commit_durable()
                self._consumed.add(effect_key)
            elif role == ROLE_NOTIFIER:
                self._conn.execute(
                    "INSERT INTO claims(effect_key, actor, role, granted_at,"
                    " lease_expires_at, active) VALUES(?, ?, ?, ?, ?, 1)",
                    (effect_key, actor, role, ts, ts + lease_seconds),
                )
                self._commit_durable()
            else:
                raise StoreError(f"unknown claim role: {role!r}")
            return Claim(
                effect_key=effect_key,
                actor=actor,
                role=role,
                granted_at=ts,
                lease_expires_at=ts + lease_seconds,
            )

    def active_claim(self, effect_key: str) -> Optional[dict]:
        with self._lock:
            self._check_open()
            row = self._conn.execute(
                "SELECT * FROM claims WHERE effect_key = ? AND active = 1 "
                "ORDER BY id DESC LIMIT 1",
                (effect_key,),
            ).fetchone()
            return dict(row) if row else None

    def revalidate_claim(self, effect_key: str, actor: str,
                         now: Optional[float] = None) -> bool:
        """Zombie-executor check (SPEC-P0 section 2): after a freeze /
        resume, an executor must confirm its claim is still the active
        executor claim, unexpired, and the key still consumed by it,
        before any further injection."""
        with self._lock:
            self._check_open()
            if self._stale:
                return False
            ts = time.time() if now is None else now
            row = self._conn.execute(
                "SELECT * FROM claims WHERE effect_key = ? AND active = 1 "
                "AND role = ? ORDER BY id DESC LIMIT 1",
                (effect_key, ROLE_EXECUTOR),
            ).fetchone()
            if row is None:
                return False
            return (
                row["actor"] == actor
                and row["lease_expires_at"] > ts
                and effect_key in self._consumed
            )

    def release_claim(self, effect_key: str, actor: str) -> None:
        """Deactivate an actor's claims. Never un-consumes the key."""
        with self._lock:
            self._check_open()
            self._conn.execute(
                "UPDATE claims SET active = 0 WHERE effect_key = ? AND actor = ?",
                (effect_key, actor),
            )
            self._commit_durable()

    # -- lifecycle -----------------------------------------------------------

    def close(self) -> None:
        with self._lock:
            if not self._closed:
                self._closed = True
                try:
                    self._conn.commit()
                    self._fsync_files()
                finally:
                    self._conn.close()

    def __enter__(self) -> "ArbitrationStore":
        return self

    def __exit__(self, *exc) -> None:
        self.close()
