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


if __name__ == "__main__":
    unittest.main()
