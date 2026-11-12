package wherevercode.agent.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayGuardTest {

    @Test
    fun `fresh increasing seqs are accepted`() {
        val guard = ReplayGuard(windowSeconds = 300)
        assertTrue(guard.checkAndRecord(1, 1000))
        assertTrue(guard.checkAndRecord(2, 1001))
        assertTrue(guard.checkAndRecord(3, 1002))
    }

    @Test
    fun `duplicate seq is refused`() {
        val guard = ReplayGuard(windowSeconds = 300)
        assertTrue(guard.checkAndRecord(5, 1000))
        assertFalse(guard.checkAndRecord(5, 1001))
    }

    @Test
    fun `seq far ahead of the watermark is refused`() {
        val guard = ReplayGuard(windowSeconds = 300)
        assertTrue(guard.checkAndRecord(10, 1000))
        assertFalse(guard.checkAndRecord(10 + 301, 1001))
    }

    @Test
    fun `replay after the window expires is refused`() {
        val guard = ReplayGuard(windowSeconds = 300)
        assertTrue(guard.checkAndRecord(7, 1000))
        // Advance past the window: the entry is pruned, but the watermark
        // still refuses the replayed old seq.
        assertFalse(guard.checkAndRecord(7, 1000 + 301))
    }

    @Test
    fun `seq at the window boundary is still a duplicate, not expired`() {
        val guard = ReplayGuard(windowSeconds = 300)
        assertTrue(guard.checkAndRecord(9, 1000))
        assertFalse(guard.checkAndRecord(9, 1000 + 300))
    }

    @Test
    fun `a newer seq is accepted after an old one expires`() {
        val guard = ReplayGuard(windowSeconds = 300)
        assertTrue(guard.checkAndRecord(1, 1000))
        assertTrue(guard.checkAndRecord(2, 1000 + 301))
    }
}
