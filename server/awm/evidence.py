"""Evidence grading and promotion rules (SPEC-P0 section 4).

Grades, strictly separate: E0 enqueued / E1 transported / E2 device
action / E3 external effect / H human relay. A receipt is not a
reception, a reception is not an execution, an execution is not an
effect confirmation -- the record kinds below keep them apart, and an
execution receipt is a different term and record type from an
authoritative API receipt (Grok Build condition).

Total order for resolving an effect's outcome:
    authoritative API idempotent receipt
        > independently recorded human confirmation
        > read-only observation (diagnostic only, NEVER promotes alone)

Promotion rules enforced here:

- An observation whose observer shares the injection's permission
  subject (e.g. the same AccessibilityService) is permanently
  ineligible to establish E3 for that effect.
- Read-only reconciliation is diagnostic only; it cannot by itself
  clear an indeterminate effect.
- A record not bound to the effect_key is not evidence for that key;
  similarity of text never establishes success.
- E3 evidence must be bound to the target device's environment
  fingerprint (OEM, OS API level, actor id). A receipt recorded
  against a different fingerprint establishes nothing for this effect
  (no cross-device extrapolation).
"""

from __future__ import annotations

import os
import sqlite3
import threading
import time
from dataclasses import dataclass
from typing import Optional

GRADE_E0 = "E0"
GRADE_E1 = "E1"
GRADE_E2 = "E2"
GRADE_E3 = "E3"
GRADE_H = "H"

KIND_AUTHORITATIVE_RECEIPT = "authoritative_receipt"
KIND_HUMAN_CONFIRMATION = "human_confirmation"
KIND_OBSERVATION = "observation"
KIND_EXECUTION_RECEIPT = "execution_receipt"
KIND_TRANSPORT = "transport"
KIND_ENQUEUE = "enqueue"

# Outcome-resolution rank (higher wins). Observations rank last and,
# per the total order, can never alone establish an outcome.
_RANK = {
    KIND_AUTHORITATIVE_RECEIPT: 3,
    KIND_HUMAN_CONFIRMATION: 2,
    KIND_OBSERVATION: 1,
    KIND_EXECUTION_RECEIPT: 1,
    KIND_TRANSPORT: 0,
    KIND_ENQUEUE: 0,
}

_GRADE_BY_KIND = {
    KIND_ENQUEUE: GRADE_E0,
    KIND_TRANSPORT: GRADE_E1,
    KIND_EXECUTION_RECEIPT: GRADE_E2,
    KIND_OBSERVATION: GRADE_E2,
    KIND_AUTHORITATIVE_RECEIPT: GRADE_E3,
    KIND_HUMAN_CONFIRMATION: GRADE_H,
}


@dataclass(frozen=True)
class EnvFingerprint:
    """Target device environment an effect's evidence must bind to."""
    oem: str
    os_api_level: int
    actor_id: str


class EvidenceError(Exception):
    """Base class for evidence errors."""


class UnboundEvidence(EvidenceError):
    """A record without an effect_key binding is not evidence."""


_SCHEMA = """
CREATE TABLE IF NOT EXISTS effect_env (
    effect_key TEXT PRIMARY KEY,
    oem TEXT NOT NULL,
    os_api_level INTEGER NOT NULL,
    actor_id TEXT NOT NULL,
    injection_subject TEXT
);
CREATE TABLE IF NOT EXISTS evidence (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    effect_key TEXT NOT NULL,
    grade TEXT NOT NULL,
    kind TEXT NOT NULL,
    source TEXT NOT NULL,
    oem TEXT,
    os_api_level INTEGER,
    actor_id TEXT,
    observer_subject TEXT,
    detail TEXT NOT NULL DEFAULT '',
    recorded_at REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_evidence_effect ON evidence(effect_key);
"""


class EvidenceLog:
    """Append-only evidence record. There is deliberately no update
    or delete API: evidence history is never rewritten."""

    def __init__(self, path: str):
        self._path = path
        self._lock = threading.RLock()
        self._conn = sqlite3.connect(path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._conn.execute("PRAGMA synchronous=FULL")
        self._conn.executescript(_SCHEMA)
        self._conn.commit()
        self._fsync()

    def _fsync(self) -> None:
        for suffix in ("", "-wal"):
            try:
                fd = os.open(self._path + suffix, os.O_RDWR)
            except OSError:
                continue
            try:
                os.fsync(fd)
            finally:
                os.close(fd)

    def bind_effect(self, effect_key: str, env: EnvFingerprint,
                    injection_subject: Optional[str] = None) -> None:
        """Bind an effect to its target environment fingerprint and to
        the permission subject that will perform the injection (used
        for the same-subject observation disqualification)."""
        with self._lock:
            self._conn.execute(
                "INSERT INTO effect_env(effect_key, oem, os_api_level,"
                " actor_id, injection_subject) VALUES(?, ?, ?, ?, ?) "
                "ON CONFLICT(effect_key) DO UPDATE SET oem = excluded.oem,"
                " os_api_level = excluded.os_api_level,"
                " actor_id = excluded.actor_id,"
                " injection_subject = excluded.injection_subject",
                (effect_key, env.oem, env.os_api_level, env.actor_id,
                 injection_subject),
            )
            self._conn.commit()
            self._fsync()

    def record(self, effect_key: str, kind: str, source: str,
               env: Optional[EnvFingerprint] = None,
               observer_subject: Optional[str] = None,
               detail: str = "",
               now: Optional[float] = None) -> int:
        """Append one evidence record. Every record must be bound to
        an effect_key; unbound 'similar' observations are rejected."""
        if not effect_key:
            raise UnboundEvidence("evidence must bind an effect_key")
        if kind not in _GRADE_BY_KIND:
            raise EvidenceError(f"unknown evidence kind: {kind!r}")
        with self._lock:
            ts = time.time() if now is None else now
            cur = self._conn.execute(
                "INSERT INTO evidence(effect_key, grade, kind, source,"
                " oem, os_api_level, actor_id, observer_subject, detail,"
                " recorded_at) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (effect_key, _GRADE_BY_KIND[kind], kind, source,
                 env.oem if env else None,
                 env.os_api_level if env else None,
                 env.actor_id if env else None,
                 observer_subject, detail, ts),
            )
            self._conn.commit()
            self._fsync()
            return int(cur.lastrowid)

    def records(self, effect_key: str) -> list[dict]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT * FROM evidence WHERE effect_key = ? ORDER BY id",
                (effect_key,),
            ).fetchall()
            return [dict(r) for r in rows]

    # -- evaluation ------------------------------------------------------

    def _bound_env(self, effect_key: str) -> Optional[dict]:
        row = self._conn.execute(
            "SELECT * FROM effect_env WHERE effect_key = ?", (effect_key,)
        ).fetchone()
        return dict(row) if row else None

    def _fingerprint_matches(self, effect_key: str, rec: dict) -> bool:
        bound = self._bound_env(effect_key)
        if bound is None:
            return False
        return (
            rec["oem"] == bound["oem"]
            and rec["os_api_level"] == bound["os_api_level"]
            and rec["actor_id"] == bound["actor_id"]
        )

    def _observation_disqualified(self, effect_key: str, rec: dict) -> bool:
        bound = self._bound_env(effect_key)
        if bound is None or not bound["injection_subject"]:
            return False
        return rec["observer_subject"] == bound["injection_subject"]

    def effect_confirmed(self, effect_key: str) -> bool:
        """E3 established? Only an authoritative receipt bound to the
        same key AND the bound fingerprint, or an independently
        recorded human confirmation, establish E3. Observations and
        execution receipts never do."""
        with self._lock:
            for rec in self.records(effect_key):
                if rec["kind"] == KIND_AUTHORITATIVE_RECEIPT and \
                        self._fingerprint_matches(effect_key, rec):
                    return True
                if rec["kind"] == KIND_HUMAN_CONFIRMATION:
                    return True
            return False

    def can_clear_indeterminate(self, effect_key: str) -> bool:
        """The only records that may clear an indeterminate effect are
        the top two ranks of the total order. Read-only reconciliation
        (observations) is diagnostic only and is excluded here."""
        return self.effect_confirmed(effect_key)

    def resolve_outcome(self, effect_key: str) -> Optional[dict]:
        """Pick the winning outcome record by the total order, ignoring
        disqualified observations and fingerprint mismatches. Returns
        None when nothing qualifies."""
        with self._lock:
            best: Optional[dict] = None
            for rec in self.records(effect_key):
                if rec["kind"] == KIND_OBSERVATION and \
                        self._observation_disqualified(effect_key, rec):
                    continue
                if rec["kind"] == KIND_AUTHORITATIVE_RECEIPT and \
                        not self._fingerprint_matches(effect_key, rec):
                    continue
                if rec["kind"] in (KIND_OBSERVATION, KIND_EXECUTION_RECEIPT):
                    continue  # never an outcome by themselves
                if best is None or _RANK[rec["kind"]] > _RANK[best["kind"]]:
                    best = rec
            return best

    def highest_grade(self, effect_key: str) -> Optional[str]:
        """Highest grade with a qualifying record. E3 appears only via
        effect_confirmed(); observation records cap at E2-diagnostic."""
        with self._lock:
            grades = set()
            for rec in self.records(effect_key):
                grades.add(rec["grade"])
            if self.effect_confirmed(effect_key):
                return GRADE_E3
            for grade in (GRADE_H, GRADE_E2, GRADE_E1, GRADE_E0):
                if grade in grades:
                    return grade
            return None

    def close(self) -> None:
        with self._lock:
            try:
                self._conn.commit()
                self._fsync()
            finally:
                self._conn.close()

    def __enter__(self) -> "EvidenceLog":
        return self

    def __exit__(self, *exc) -> None:
        self.close()
