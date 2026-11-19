"""Tests for awm.keys (SPEC-P0 section 1). Stdlib unittest only."""

import os
import sys
import unittest

sys.path.insert(
    0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
)

from awm.keys import KeyMint, MINT_ENTRY, MINT_RESEND, NotMinted  # noqa: E402


class TestMinting(unittest.TestCase):
    def test_fanout_one_key_per_target(self):
        # T17: one command, N targets -> N independent keys.
        mint = KeyMint()
        keys = mint.mint_for_command("cmd-1", ["dev-a", "dev-b", "dev-c"])
        self.assertEqual(set(keys), {"dev-a", "dev-b", "dev-c"})
        values = [k.value for k in keys.values()]
        self.assertEqual(len(set(values)), 3)
        for target, key in keys.items():
            self.assertEqual(key.target_resource, target)
            self.assertEqual(key.command_identity, "cmd-1")
            self.assertEqual(key.minted_via, MINT_ENTRY)

    def test_retry_reuses_same_key(self):
        mint = KeyMint()
        first = mint.mint_for_command("cmd-1", ["dev-a"])["dev-a"]
        again = mint.mint_for_command("cmd-1", ["dev-a"])["dev-a"]
        self.assertEqual(first.value, again.value)
        self.assertEqual(mint.key_for("cmd-1", "dev-a").value, first.value)

    def test_key_for_unminted_raises(self):
        mint = KeyMint()
        with self.assertRaises(NotMinted):
            mint.key_for("cmd-never", "dev-a")

    def test_deliberate_resend_mints_new_identity(self):
        mint = KeyMint()
        old = mint.mint_for_command("cmd-1", ["dev-a"])["dev-a"]
        new = mint.resend("cmd-1", "dev-a")
        self.assertNotEqual(old.value, new.value)
        self.assertEqual(new.minted_via, MINT_RESEND)
        self.assertEqual(new.supersedes, old.value)
        # After the resend, retries follow the new identity.
        self.assertEqual(mint.key_for("cmd-1", "dev-a").value, new.value)
        # The old key object is still resolvable by value.
        self.assertEqual(mint.lookup(old.value).value, old.value)

    def test_same_text_different_command_gets_different_key(self):
        # Identity is the caller-supplied command identity, never a
        # hash of message text: two distinct commands never collide.
        mint = KeyMint()
        a = mint.mint_for_command("cmd-A", ["dev-a"])["dev-a"]
        b = mint.mint_for_command("cmd-B", ["dev-a"])["dev-a"]
        self.assertNotEqual(a.value, b.value)


if __name__ == "__main__":
    unittest.main()
