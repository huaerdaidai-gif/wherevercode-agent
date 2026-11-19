"""Tests for awm.store (SPEC-P0 section 3). Stdlib unittest only."""

import os
import sys
import tempfile
import threading
import unittest

sys.path.insert(
    0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
)

from awm.store import (  # noqa: E402
    ArbitrationStore,
    ClaimDenied,
    ROLE_EXECUTOR,
    ROLE_NOTIFIER,
    StaleTermError,
    StoreError,
)


class StoreTestBase(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.path = os.path.join(self._tmp.name, "arb.db")

    def tearDown(self):
        self._tmp.cleanup()

    def open_store(self):
        return ArbitrationStore(self.path)


class TestDurability(StoreTestBase):
    def test_pragmas_are_wal_and_full(self):
        with self.open_store() as s:
            mode = s._conn.execute("PRAGMA journal_mode").fetchone()[0]
            sync = s._conn.execute("PRAGMA synchronous").fetchone()[0]
        self.assertEqual(mode.lower(), "wal")
        self.assertEqual(sync, 2)  # FULL

    def test_consumed_survives_reopen(self):
        with self.open_store() as s:
            s.register_effect("ek1", "cmd-1", "dev-a", state="transported")
            s.claim("ek1", "actor-1")
            self.assertTrue(s.is_consumed("ek1"))
        # Reopen: consumed set must be replayed before serving.
        with self.open_store() as s:
            self.assertTrue(s.is_consumed("ek1"))
            self.assertIn("ek1", s.consumed_keys())
            with self.assertRaises(ClaimDenied):
                s.claim("ek1", "actor-2")

    def test_register_conflicting_identity_raises(self):
        with self.open_store() as s:
            s.register_effect("ek1", "cmd-1", "dev-a")
            with self.assertRaises(StoreError):
                s.register_effect("ek1", "cmd-OTHER", "dev-a")
            # Same identity re-register is idempotent.
            s.register_effect("ek1", "cmd-1", "dev-a")


class TestClaimRules(StoreTestBase):
    def test_executor_claim_consumes_exactly_once(self):
        with self.open_store() as s:
            s.register_effect("ek1", "cmd-1", "dev-a", state="transported")
            c = s.claim("ek1", "actor-1")
            self.assertTrue(c.can_inject)
            with self.assertRaises(ClaimDenied):
                s.claim("ek1", "actor-1")  # even the same actor
            with self.assertRaises(ClaimDenied):
                s.claim("ek1", "actor-2")

    def test_concurrent_claims_single_winner(self):
        with self.open_store() as s:
            s.register_effect("ek1", "cmd-1", "dev-a", state="transported")
            wins, losses = [], []

            def worker(i):
                try:
                    wins.append(s.claim("ek1", f"actor-{i}"))
                except ClaimDenied:
                    losses.append(i)

            threads = [threading.Thread(target=worker, args=(i,))
                       for i in range(16)]
            for t in threads:
                t.start()
            for t in threads:
                t.join()
        self.assertEqual(len(wins), 1)
        self.assertEqual(len(losses), 15)

    def test_lease_expiry_never_resurrects_executor(self):
        # T9 core: lease expires, B tries to claim the same key.
        with self.open_store() as s:
            s.register_effect("ek1", "cmd-1", "dev-a", state="transported")
            s.claim("ek1", "actor-A", lease_seconds=10, now=1000.0)
            with self.assertRaises(ClaimDenied):
                s.claim("ek1", "actor-B", now=9999.0)  # long past expiry
            # B may only receive the notifier role: no injection rights.
            nb = s.claim("ek1", "actor-B", role=ROLE_NOTIFIER, now=9999.0)
            self.assertFalse(nb.can_inject)

    def test_notifier_claim_does_not_consume(self):
        with self.open_store() as s:
            s.register_effect("ek1", "cmd-1", "dev-a", state="transported")
            s.claim("ek1", "watcher", role=ROLE_NOTIFIER)
            self.assertFalse(s.is_consumed("ek1"))
            c = s.claim("ek1", "actor-1")  # executor still possible
            self.assertTrue(c.can_inject)

    def test_revalidate_claim_zombie_window(self):
        with self.open_store() as s:
            s.register_effect("ek1", "cmd-1", "dev-a", state="transported")
            s.claim("ek1", "actor-1", lease_seconds=30, now=1000.0)
            self.assertTrue(s.revalidate_claim("ek1", "actor-1", now=1010.0))
            self.assertFalse(s.revalidate_claim("ek1", "actor-2", now=1010.0))
            # After lease expiry the resumed executor must not proceed.
            self.assertFalse(s.revalidate_claim("ek1", "actor-1", now=2000.0))

    def test_abnormal_states_never_grant_executor_claim(self):
        # R7 B2 regression: an effect parked in the abnormal path
        # (indeterminate / human_required, consumed still 0) must never
        # be re-granted an executor claim, and claim() must not flip its
        # state back to claimed. SPEC-P0 section 2: the abnormal path is
        # one-way to human resolution or authoritative confirmation.
        for abnormal in ("indeterminate", "human_required"):
            with self.open_store() as s:
                s.register_effect("ek1", "cmd-1", "dev-a", state="transported")
                s.set_state("ek1", abnormal)
                with self.assertRaises(ClaimDenied):
                    s.claim("ek1", "actor-1")
                self.assertEqual(s.get_effect("ek1")["state"], abnormal)
                self.assertFalse(s.is_consumed("ek1"))
                # The notifier role stays available: a human can still
                # be notified about the parked effect.
                nb = s.claim("ek1", "watcher", role=ROLE_NOTIFIER)
                self.assertFalse(nb.can_inject)


class TestMultiProcessClaim(StoreTestBase):
    """T1 regression (independent test failure, 2026-11-19): with a
    deferred transaction, 8 processes could all read consumed = 0 and
    all win an executor claim. The claim path now runs under
    BEGIN IMMEDIATE, so exactly one process may win."""

    def test_multiprocess_claims_single_winner(self):
        import multiprocessing as mp

        with self.open_store() as s:
            s.register_effect("ek1", "cmd-1", "dev-a", state="transported")

        n = 8
        barrier = mp.Barrier(n)
        results: mp.Queue = mp.Queue()

        def worker(i):
            store = ArbitrationStore(self.path)
            try:
                barrier.wait(timeout=30)
                try:
                    store.claim("ek1", f"proc-{i}")
                    results.put("win")
                except ClaimDenied:
                    results.put("denied")
                except Exception as exc:  # noqa: BLE001 - report anything
                    results.put(f"error:{type(exc).__name__}")
            finally:
                store.close()

        procs = [mp.Process(target=worker, args=(i,)) for i in range(n)]
        for p in procs:
            p.start()
        for p in procs:
            p.join(timeout=60)
        outcomes = [results.get(timeout=10) for _ in range(n)]
        self.assertEqual(outcomes.count("win"), 1, outcomes)
        self.assertEqual(outcomes.count("denied"), n - 1, outcomes)
        # And the store agrees: exactly one executor claim row exists.
        with self.open_store() as s:
            rows = s._conn.execute(
                "SELECT COUNT(*) AS n FROM claims WHERE role = 'executor'"
            ).fetchone()
            self.assertEqual(rows["n"], 1)


class TestTermGuard(StoreTestBase):
    def test_restored_old_term_refuses_claims(self):
        with self.open_store() as s:
            s.advance_term(5)
            s.note_seen_term(5)
            s.register_effect("ek1", "cmd-1", "dev-a", state="transported")
        # Simulate a restore from an older backup: term rolled back to 2
        # while max_seen_term stays 5 (written here directly, as a restore
        # of an older meta snapshot would produce).
        with self.open_store() as s:
            s._conn.execute("UPDATE meta SET v = '2' WHERE k = 'term'")
            s._conn.commit()
        with self.open_store() as s:
            self.assertTrue(s.stale)
            with self.assertRaises(StaleTermError):
                s.claim("ek1", "actor-1")

    def test_advance_term_must_be_monotone(self):
        with self.open_store() as s:
            s.advance_term(3)
            with self.assertRaises(StoreError):
                s.advance_term(3)
            with self.assertRaises(StoreError):
                s.advance_term(2)
            s.advance_term(4)
            self.assertEqual(s.term, 4)

    def test_advance_term_below_max_seen_rejected(self):
        # R7 B1 regression: while stale (a higher term has been seen),
        # advancing to a term still at or below max_seen must be
        # rejected and must not clear the stale flag; only advancing
        # past every seen term makes the arbiter fresh. SPEC-P0
        # sections 2/3: a lagging term never grants executable claims.
        with self.open_store() as s:
            s.register_effect("ek1", "cmd-1", "dev-a", state="transported")
            s.note_seen_term(5)
            self.assertTrue(s.stale)
            with self.assertRaises(StoreError):
                s.advance_term(3)
            self.assertTrue(s.stale)
            with self.assertRaises(StaleTermError):
                s.claim("ek1", "actor-1")
            s.advance_term(6)
            self.assertFalse(s.stale)
            c = s.claim("ek1", "actor-1")
            self.assertTrue(c.can_inject)


if __name__ == "__main__":
    unittest.main()
