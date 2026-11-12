package wherevercode.agent.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTypesTest {

    @Test
    fun `limits - session name over 80 and message over 4000 are refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            validateSessionName("x".repeat(81))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateMessageContent("x".repeat(4001))
        }
        // Boundaries pass.
        assertEquals("x".repeat(80), validateSessionName("x".repeat(80)))
        assertEquals("x".repeat(4000), validateMessageContent("x".repeat(4000)))
    }

    @Test
    fun `secret-shaped content is redacted with SecretRedactor, not refused`() {
        val raw = "我的 key 是 sk-abcdefghijklmnop123456"
        assertTrue(messageContainsSecret(raw))
        val redacted = redactMessageContent(raw)
        assertFalse(redacted.contains("sk-abcdefghijklmnop123456"))
        assertTrue(redacted.contains("[REDACTED]"))
        // Plain text passes through unchanged.
        assertEquals("hello", redactMessageContent("hello"))
    }

    @Test
    fun `classification - six verbs plus taskId plus reason plus authorized sender become CONTROL`() {
        val cases = mapOf(
            "暂停 t1 原因：先停一下" to ControlVerb.PAUSE,
            "恢复 t1 原因：继续做" to ControlVerb.RESUME,
            "停止 t1 原因：不做了" to ControlVerb.STOP,
            "改目标 t1 原因：改成新目标" to ControlVerb.CHANGE_GOAL,
            "撤权 t1 原因：收回授权" to ControlVerb.REVOKE,
            "接管 t1 原因：我来接手" to ControlVerb.TAKEOVER,
            "pause t1 reason: hold on" to ControlVerb.PAUSE,
        )
        for ((text, verb) in cases) {
            val result = classify(text, senderAuthorized = true)
            assertTrue("expected CONTROL for: $text", result is Classification.Control)
            assertEquals(verb, (result as Classification.Control).instruction.verb)
            assertEquals("t1", result.instruction.taskId)
            assertTrue(result.instruction.reason.isNotBlank())
        }
    }

    @Test
    fun `classification - missing reason or taskId stays CHAT, free text never becomes CONTROL`() {
        assertTrue(classify("暂停 t1", senderAuthorized = true) is Classification.Chat)
        assertTrue(classify("暂停一下想想", senderAuthorized = true) is Classification.Chat)
        assertTrue(classify("帮我暂停一下想想", senderAuthorized = true) is Classification.Chat)
        assertTrue(classify("暂停 t1 原因：", senderAuthorized = true) is Classification.Chat)
        assertTrue(classify("今天把 t1 做完", senderAuthorized = true) is Classification.Chat)
        assertTrue(classify("你好，在吗？", senderAuthorized = true) is Classification.Chat)
    }

    @Test
    fun `classification - verbs outside the whitelist are rejected, person swap is refused`() {
        val unknown = classify("删除 t1 原因：清理掉", senderAuthorized = true)
        assertTrue(unknown is Classification.Rejected)
        assertEquals("unknown_control_verb", (unknown as Classification.Rejected).errorCode)
        val swap = classify("换人 t1 原因：换个人做", senderAuthorized = true)
        assertTrue(swap is Classification.Rejected)
        assertEquals("person_swap_not_supported", (swap as Classification.Rejected).errorCode)
        // An unauthorized sender with a valid shape is also rejected, not chat.
        val unauthorized = classify("暂停 t1 原因：先停一下", senderAuthorized = false)
        assertTrue(unauthorized is Classification.Rejected)
        assertEquals("sender_not_authorized", (unauthorized as Classification.Rejected).errorCode)
    }

    @Test
    fun `reply_to across sessions or missing is refused, non-members and reserved owner id are refused`() {
        val parent = newSessionMessage(
            conversationId = "conv-a",
            senderId = "user",
            content = "parent",
        )
        assertThrows(IllegalArgumentException::class.java) {
            validateReplyTo("missing-id", null, "conv-a")
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateReplyTo(parent.messageId, parent, "conv-b")
        }
        // Same-conversation reply passes.
        validateReplyTo(parent.messageId, parent, "conv-a")
        validateReplyTo(null, null, "conv-a")
        // Sender rules.
        val members = listOf("user", "muse")
        assertThrows(IllegalArgumentException::class.java) { validateSender("owner", members) }
        assertThrows(IllegalArgumentException::class.java) { validateSender("stranger", members) }
        assertThrows(IllegalArgumentException::class.java) { validateSender("", members) }
        validateSender("user", members)
    }

    @Test
    fun `WACF fields - messageId is a UUID, five message types, causationId traceable, expired TTL refused`() {
        val id = SessionMessage.newId()
        assertTrue(SessionMessage.isValidMessageId(id))
        assertFalse(SessionMessage.isValidMessageId("not-a-uuid"))
        assertEquals(
            setOf("CHAT", "CONTROL", "TASK", "EVENT", "DELIVERY"),
            MessageKind.values().map { it.name }.toSet(),
        )
        assertEquals(
            setOf("PERSISTED", "DELIVERED", "CLAIMED", "READ"),
            DeliveryState.values().map { it.name }.toSet(),
        )
        val parent = newSessionMessage(conversationId = "c1", senderId = "user", content = "p")
        val child = newSessionMessage(
            conversationId = "c1",
            senderId = "user",
            content = "reply",
            causationId = parent.messageId,
            taskId = "t1",
            attemptId = "a1",
            authVersion = 7,
            goalVersion = 3,
            ttlSeconds = 10,
            createdAtEpochSeconds = 1_000,
        )
        assertEquals(parent.messageId, child.causationId)
        assertEquals("t1", child.taskId)
        assertEquals("a1", child.attemptId)
        assertEquals(7L, child.authVersion)
        assertEquals(3L, child.goalVersion)
        assertFalse(child.isExpired(1_005))
        assertTrue(child.isExpired(1_011))
        // No-ttl messages never expire.
        assertFalse(child.copy(ttlSeconds = null).isExpired(9_999_999))
    }
}
