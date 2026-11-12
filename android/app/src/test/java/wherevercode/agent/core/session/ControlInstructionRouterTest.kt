package wherevercode.agent.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.core.task.StaleTaskUpdate

class ControlInstructionRouterTest {

    /** Persist a CONTROL message in the fake DAO (the ledger receipt),
     * the way S3 does it before calling the router. */
    private fun persistedControl(
        h: LedgerHarness,
        session: SessionRecord,
        senderId: String,
        content: String,
        recipientId: String? = null,
    ): SessionMessage {
        val message = newSessionMessage(
            conversationId = session.sessionId,
            senderId = senderId,
            content = content,
            recipientId = recipientId,
            messageType = MessageKind.CONTROL,
        )
        h.messageDao.insert(message.toEntity())
        return message
    }

    @Test
    fun `an unauthorized sender is refused and the task ledger does not move`() {
        val h = LedgerHarness()
        h.addTask("t1")
        val session = h.privateSession()
        val message = persistedControl(h, session, "muse", "暂停 t1 原因：需要先停一下")
        val result = h.router.route(message, session)
        assertFalse(result.ok)
        assertEquals("sender_not_authorized", result.errorCode)
        assertEquals(1L, h.taskStore.getTask("t1")!!.version)
        assertFalse(h.taskStore.getTask("t1")!!.needsAttention)
        assertTrue(h.port.calls.isEmpty())
    }

    @Test
    fun `the delegation switch is off by default - proxy control is refused even with a device grant`() {
        val h = LedgerHarness()
        h.addTask("t1")
        h.authStore.grantDevice()
        val session = h.privateSession()
        val message = persistedControl(h, session, "muse", "暂停 t1 原因：代用户暂停")
        val refused = h.router.route(message, session)
        assertFalse(refused.ok)
        assertEquals("sender_not_authorized", refused.errorCode)
        assertEquals(1L, h.taskStore.getTask("t1")!!.version)
        // With the switch on (and the device grant present) the same
        // sender is allowed — proving the switch is the gate.
        val enabled = LedgerHarness(delegatedControlEnabled = true)
        enabled.addTask("t1")
        enabled.authStore.grantDevice()
        val session2 = enabled.privateSession()
        val message2 = persistedControl(enabled, session2, "muse", "暂停 t1 原因：代用户暂停")
        val allowed = enabled.router.route(message2, session2)
        assertTrue(allowed.ok)
        assertEquals(listOf("pause:t1"), enabled.port.calls)
    }

    @Test
    fun `an optimistic-lock mismatch throws StaleTaskUpdate and writes nothing`() {
        val h = LedgerHarness()
        h.addTask("t1")
        h.taskStore.failUpdateWith = StaleTaskUpdate("t1", 1, 2)
        val session = h.privateSession()
        val message = persistedControl(h, session, "user", "暂停 t1 原因：需要先停一下")
        assertThrows(StaleTaskUpdate::class.java) { h.router.route(message, session) }
        assertEquals(1L, h.taskStore.getTask("t1")!!.version)
        assertTrue(h.port.calls.isEmpty())
    }

    @Test
    fun `reason is required`() {
        val h = LedgerHarness()
        h.addTask("t1")
        val session = h.privateSession()
        val message = persistedControl(h, session, "user", "暂停 t1")
        val result = h.router.route(message, session)
        assertFalse(result.ok)
        assertEquals("control_reason_required", result.errorCode)
        assertEquals(1L, h.taskStore.getTask("t1")!!.version)
        assertTrue(h.port.calls.isEmpty())
    }

    @Test
    fun `the router only records intent and hands over - it never executes the action itself`() {
        val h = LedgerHarness()
        h.addTask("t1", state = "working")
        val session = h.privateSession()
        val message = persistedControl(h, session, "user", "暂停 t1 原因：需要先停一下")
        val result = h.router.route(message, session)
        assertTrue(result.ok)
        assertEquals(listOf("pause:t1"), h.port.calls)
        // Intent recorded (version +1, flagged)…
        assertEquals(2L, result.task!!.version)
        assertTrue(result.task!!.needsAttention)
        // …but the task state itself did not move: pausing is the
        // ControlPlane's job, not the router's.
        assertEquals("working", result.task!!.state)
        assertEquals("working", h.taskStore.getTask("t1")!!.state)
    }

    @Test
    fun `direct addressing - sender to recipient, straight past the brain`() {
        val h = LedgerHarness()
        h.addTask("t1")
        val session = h.groupSession()
        val message = persistedControl(
            h, session, "user", "停止 t1 原因：到此为止", recipientId = "target-agent",
        )
        val result = h.router.route(message, session)
        assertTrue(result.ok)
        assertEquals("target-agent", result.recipientId)
        assertEquals(listOf("stop:t1"), h.port.calls)
        // The router holds no adapter and never calls one: the only
        // call made is the control handover above.
        assertEquals(1, h.port.calls.size)
    }
}
