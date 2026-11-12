package wherevercode.agent.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandLimitsTest {

    @Test
    fun `allowlisted commands pass`() {
        val limits = CommandLimits()
        assertTrue(validateCommand(listOf("python", "test.py")).first)
        assertTrue(validateCommand(listOf("pytest", "-q")).first)
        assertTrue(validateCommand(listOf("git", "status")).first)
        assertTrue(validateCommand(listOf("git", "diff")).first)
        assertTrue(validateCommand(listOf("git", "log")).first)
        assertTrue(validateCommand(listOf("git", "show")).first)
        assertTrue(validateCommand(listOf("ls", "-la")).first)
        assertTrue(validateCommand(listOf("/usr/bin/python", "test.py")).first)
        limits.checkCommand(listOf("npm", "test"))
    }

    @Test
    fun `shells are refused`() {
        assertFalse(validateCommand(listOf("sh", "-c", "ls")).first)
        assertFalse(validateCommand(listOf("bash", "-c", "ls")).first)
        assertThrows(LimitError::class.java) { CommandLimits().checkCommand(listOf("bash", "-c", "ls")) }
    }

    @Test
    fun `downloaders and destructive tools are refused`() {
        assertFalse(validateCommand(listOf("curl", "https://example.com")).first)
        assertFalse(validateCommand(listOf("wget", "https://example.com")).first)
        assertFalse(validateCommand(listOf("rm", "-rf", "/")).first)
        assertFalse(validateCommand(listOf("mv", "a", "b")).first)
        assertFalse(validateCommand(listOf("chmod", "+x", "a")).first)
    }

    @Test
    fun `privilege escalation is refused`() {
        assertFalse(validateCommand(listOf("sudo", "ls")).first)
        assertFalse(validateCommand(listOf("su", "-c", "ls")).first)
    }

    @Test
    fun `inline code flags are refused`() {
        assertFalse(validateCommand(listOf("python", "-c", "print(1)")).first)
        assertFalse(validateCommand(listOf("python3", "-c", "print(1)")).first)
        assertFalse(validateCommand(listOf("node", "-e", "console.log(1)")).first)
        assertFalse(validateCommand(listOf("node", "--eval", "1")).first)
        // The same interpreters running a file are fine.
        assertTrue(validateCommand(listOf("python", "script.py")).first)
        assertTrue(validateCommand(listOf("node", "index.js")).first)
    }

    @Test
    fun `git subcommands are restricted to read-only`() {
        assertFalse(validateCommand(listOf("git", "push")).first)
        assertFalse(validateCommand(listOf("git", "commit", "-m", "x")).first)
        assertFalse(validateCommand(listOf("git")).first)
    }

    @Test
    fun `timeout over the cap is refused not clamped`() {
        val error = assertThrows(LimitError::class.java) {
            CommandLimits().checkTimeout(301.0)
        }
        assertEquals("hand_timeout_exceeded", error.code)
        assertEquals(30.0, CommandLimits().checkTimeout(null), 0.0)
    }

    @Test
    fun `run slot guard allows two concurrent runs only`() {
        val guard = RunSlotGuard(2)
        assertTrue(guard.tryAcquire())
        assertTrue(guard.tryAcquire())
        assertFalse(guard.tryAcquire())
        guard.release()
        assertTrue(guard.tryAcquire())
        guard.release()
        guard.release()
    }

    @Test
    fun `default allowlist is the verbatim old list`() {
        assertEquals(
            listOf(
                "python", "python3", "pytest", "npm", "node", "npx",
                "go", "cargo", "make", "git",
                "ls", "cat", "head", "tail", "grep", "find", "wc", "echo", "pwd",
                "sleep",
            ),
            DEFAULT_ALLOWED_COMMANDS,
        )
        assertEquals(listOf("status", "diff", "log", "show"), GIT_ALLOWED_SUBCOMMANDS)
    }
}
