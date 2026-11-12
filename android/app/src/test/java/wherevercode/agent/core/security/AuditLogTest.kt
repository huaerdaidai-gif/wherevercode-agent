package wherevercode.agent.core.security

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditLogTest {
    private fun entry(result: Map<String, Any?>? = null) = AuditEntry(
        ts = "2026-11-08T05:00:00Z",
        taskId = "task-1",
        agentId = "muse",
        action = "read",
        path = "README.md",
        command = null,
        ok = true,
        errorCode = null,
        result = result,
    )

    @Test
    fun `record keeps metadata only, extra result keys are dropped`() {
        val log = InMemoryAuditLog()
        val stored = log.record(
            entry(
                mapOf(
                    "bytes" to 128,
                    "content" to "SECRET FILE CONTENT MUST NOT BE LOGGED",
                    "exit_code" to 0,
                ),
            ),
        )
        assertEquals(128, stored.result?.get("bytes"))
        assertEquals(0, stored.result?.get("exit_code"))
        assertFalse(stored.result?.containsKey("content") ?: false)
        assertEquals(1, log.readEntries().size)
    }

    @Test
    fun `file log is append-only JSONL and round-trips`() {
        val dir = Files.createTempDirectory("audit-test").toFile()
        val file = File(dir, "audit.jsonl")
        val log = FileAuditLog(file)
        log.record(entry(mapOf("bytes" to 10)))
        log.record(entry().copy(action = "write", ok = false, errorCode = "hand_not_found"))
        assertEquals(2, log.readEntries().size)
        assertTrue(file.readText().lines().filter { it.isNotBlank() }.size == 2)
        assertEquals("write", log.readEntries()[1].action)
        assertEquals("hand_not_found", log.readEntries()[1].errorCode)
    }

    @Test
    fun `file contents never enter the log`() {
        val log = InMemoryAuditLog()
        val stored = log.record(entry(mapOf("bytes" to 5, "content" to "top secret content")))
        assertTrue(!stored.toString().contains("top secret content"))
    }

    @Test
    fun `M2 M10 - sessionId round-trips through memory and file logs`() {
        val log = InMemoryAuditLog()
        val stored = log.record(entry().copy(sessionId = "sess-1"))
        assertEquals("sess-1", stored.sessionId)
        assertEquals("sess-1", log.readEntries().single().sessionId)
        // No sessionId stays null after a round-trip.
        val plain = log.record(entry())
        assertEquals(null, plain.sessionId)

        val dir = Files.createTempDirectory("audit-session-test").toFile()
        val fileLog = FileAuditLog(File(dir, "audit.jsonl"))
        fileLog.record(entry().copy(sessionId = "sess-2"))
        fileLog.record(entry())
        val back = fileLog.readEntries()
        assertEquals("sess-2", back[0].sessionId)
        assertEquals(null, back[1].sessionId)
    }

    @Test
    fun `M2 M10 - result keeps message_id and delivery counts, drops body keys`() {
        val log = InMemoryAuditLog()
        val stored = log.record(
            entry(
                mapOf(
                    "message_id" to "m1",
                    "delivered_count" to 2,
                    "pending_count" to 1,
                    "content" to "secret body must not be logged",
                ),
            ).copy(sessionId = "sess-1"),
        )
        assertEquals("m1", stored.result?.get("message_id"))
        assertEquals(2, stored.result?.get("delivered_count"))
        assertEquals(1, stored.result?.get("pending_count"))
        assertFalse(stored.result?.containsKey("content") ?: false)
        assertTrue(!stored.toString().contains("secret body must not be logged"))
    }
}
