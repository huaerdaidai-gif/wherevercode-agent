"""Persistent relay: outbox / inbox with sweeper (SPEC-P0 section 7).

The relay moves envelopes; it never executes effects. Its duties:

- Durable outbox/inbox (SQLite, WAL + synchronous=FULL): an accepted
  message survives restarts and is re-offered until acknowledged.
- Idempotent receive: the same message_id delivered twice is stored
  once (dedupe), so reconnect-resends are safe.
- Sweeper compensation: messages sent but not acked within the ack
  window return to pending and are offered again.
- Fake-online duty (T11): the relay must not declare a peer dead or a
  message permanently failed on missing evidence. There is NO
  permanent-failure state in this module and no code path deletes or
  dead-letters a message. Peer liveness is 'unknown' unless a fresh
  heartbeat was actually recorded.
- The relay never increments an *executable* attempt and never
  dispatches a second executor: delivery attempts are transport
  bookkeeping only; claims and execution belong to the state machine
  and the arbitration store.
"""

from __future__ import annotations

import os
import sqlite3
import threading
import time
from typing import Optional

STATE_PENDING = "pending"
STATE_SENT = "sent"
STATE_ACKED = "acked"

PEER_ONLINE = "online"
PEER_UNKNOWN = "unknown"


class RelayError(Exception):
    """Base class for relay errors."""


class UnknownMessage(RelayError):
    """The message_id is not in the outbox."""


_SCHEMA = """
CREATE TABLE IF NOT EXISTS outbox (
    message_id TEXT PRIMARY KEY,
    effect_key TEXT,
    payload TEXT NOT NULL,
    state TEXT NOT NULL DEFAULT 'pending',
    delivery_attempts INTEGER NOT NULL DEFAULT 0,
    enqueued_at REAL NOT NULL,
    last_sent_at REAL
);
CREATE TABLE IF NOT EXISTS inbox (
    message_id TEXT PRIMARY KEY,
    effect_key TEXT,
    payload TEXT NOT NULL,
    received_at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS relay_meta (
    k TEXT PRIMARY KEY,
    v TEXT NOT NULL
);
"""


class Relay:
    def __init__(self, path: str, ack_timeout_seconds: float = 120.0,
                 heartbeat_fresh_seconds: float = 90.0):
        self._path = path
        self._ack_timeout = ack_timeout_seconds
        self._hb_fresh = heartbeat_fresh_seconds
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

    def _commit(self) -> None:
        self._conn.commit()
        self._fsync()

    # -- outbox ------------------------------------------------------------

    def enqueue(self, message_id: str, payload: str,
                effect_key: Optional[str] = None,
                now: Optional[float] = None) -> bool:
        """Accept a message into the durable outbox. Idempotent: the
        same message_id enqueued again (retry at the sender) is a
        no-op returning False."""
        with self._lock:
            ts = time.time() if now is None else now
            cur = self._conn.execute(
                "INSERT OR IGNORE INTO outbox(message_id, effect_key,"
                " payload, state, enqueued_at) VALUES(?, ?, ?, ?, ?)",
                (message_id, effect_key, payload, STATE_PENDING, ts),
            )
            self._commit()
            return cur.rowcount == 1

    def next_pending(self, limit: int = 10) -> list[dict]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT * FROM outbox WHERE state = ? ORDER BY enqueued_at"
                " LIMIT ?",
                (STATE_PENDING, limit),
            ).fetchall()
            return [dict(r) for r in rows]

    def mark_sent(self, message_id: str,
                  now: Optional[float] = None) -> None:
        """Record a transport attempt. This increments ONLY the
        delivery counter -- never an executable attempt, and it does
        not create a second executor anywhere."""
        with self._lock:
            ts = time.time() if now is None else now
            cur = self._conn.execute(
                "UPDATE outbox SET state = ?, last_sent_at = ?,"
                " delivery_attempts = delivery_attempts + 1"
                " WHERE message_id = ? AND state = ?",
                (STATE_SENT, ts, message_id, STATE_PENDING),
            )
            if cur.rowcount == 0:
                row = self._conn.execute(
                    "SELECT state FROM outbox WHERE message_id = ?",
                    (message_id,),
                ).fetchone()
                if row is None:
                    raise UnknownMessage(message_id)
            self._commit()

    def ack(self, message_id: str) -> None:
        with self._lock:
            cur = self._conn.execute(
                "UPDATE outbox SET state = ? WHERE message_id = ?",
                (STATE_ACKED, message_id),
            )
            if cur.rowcount == 0:
                raise UnknownMessage(message_id)
            self._commit()

    def sweep(self, now: Optional[float] = None) -> int:
        """Compensation pass: sent-but-unacked messages older than the
        ack window return to pending for re-offer. Nothing is ever
        marked permanently failed -- absence of an ack is not proof
        of failure (fake-online duty). Returns the requeued count."""
        with self._lock:
            ts = time.time() if now is None else now
            cur = self._conn.execute(
                "UPDATE outbox SET state = ? WHERE state = ?"
                " AND last_sent_at IS NOT NULL AND last_sent_at < ?",
                (STATE_PENDING, STATE_SENT, ts - self._ack_timeout),
            )
            self._commit()
            return cur.rowcount

    def outbox_state(self, message_id: str) -> str:
        with self._lock:
            row = self._conn.execute(
                "SELECT state FROM outbox WHERE message_id = ?",
                (message_id,),
            ).fetchone()
            if row is None:
                raise UnknownMessage(message_id)
            return row["state"]

    def pending_count(self) -> int:
        with self._lock:
            row = self._conn.execute(
                "SELECT COUNT(*) AS n FROM outbox WHERE state != ?",
                (STATE_ACKED,),
            ).fetchone()
            return int(row["n"])

    # -- inbox ---------------------------------------------------------------

    def receive(self, message_id: str, payload: str,
                effect_key: Optional[str] = None,
                now: Optional[float] = None) -> bool:
        """Store an inbound message idempotently. Returns True when
        newly stored, False when this message_id was already received
        (a duplicate delivery, e.g. a reconnect resend)."""
        with self._lock:
            ts = time.time() if now is None else now
            cur = self._conn.execute(
                "INSERT OR IGNORE INTO inbox(message_id, effect_key,"
                " payload, received_at) VALUES(?, ?, ?, ?)",
                (message_id, effect_key, payload, ts),
            )
            self._commit()
            return cur.rowcount == 1

    def inbox_messages(self) -> list[dict]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT * FROM inbox ORDER BY received_at"
            ).fetchall()
            return [dict(r) for r in rows]

    # -- peer liveness ---------------------------------------------------------

    def note_peer_seen(self, now: Optional[float] = None) -> None:
        """Record an actual sign of life from the peer (a heartbeat or
        any authenticated inbound contact)."""
        with self._lock:
            ts = time.time() if now is None else now
            self._conn.execute(
                "INSERT INTO relay_meta(k, v) VALUES('peer_last_seen', ?)"
                " ON CONFLICT(k) DO UPDATE SET v = excluded.v",
                (str(ts),),
            )
            self._commit()

    def peer_status(self, now: Optional[float] = None) -> str:
        """'online' only on a fresh recorded heartbeat; otherwise
        'unknown'. The relay never reports 'dead'/'offline' as a fact:
        silence is not evidence (fake-online duty, T11)."""
        with self._lock:
            ts = time.time() if now is None else now
            row = self._conn.execute(
                "SELECT v FROM relay_meta WHERE k = 'peer_last_seen'"
            ).fetchone()
            if row is None:
                return PEER_UNKNOWN
            last = float(row["v"])
            return PEER_ONLINE if ts - last <= self._hb_fresh else PEER_UNKNOWN

    def close(self) -> None:
        with self._lock:
            try:
                self._conn.commit()
                self._fsync()
            finally:
                self._conn.close()

    def __enter__(self) -> "Relay":
        return self

    def __exit__(self, *exc) -> None:
        self.close()
