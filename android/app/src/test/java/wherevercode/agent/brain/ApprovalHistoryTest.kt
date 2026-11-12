package wherevercode.agent.brain

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段 3（U4）：ApprovalBroker 审批历史与待审批倒计时。
 *
 * 覆盖：批准 / 主动拒绝 / 超时拒绝 / 无界面拒绝分开记录；去重命中记
 * “已批准（缓存）”；弹窗展示期间 pendingApproval 可读且含未来截止
 * 时间；历史超过 50 条丢最旧的。超时与主动拒绝必须可区分。
 */
class ApprovalHistoryTest {

    private fun writeAction(id: String = "call-1", idem: String = "idem-1") = Action(
        actionId = id,
        tool = "write_file",
        args = mapOf("path" to "agent-test/a.txt", "content" to "hello"),
        idempotencyKey = idem,
    )

    @Test
    fun `approved request is recorded`() = runTest {
        val broker = ApprovalBroker(60_000) { true }
        assertTrue(broker.request(writeAction()))
        val history = broker.history()
        assertEquals(1, history.size)
        assertEquals(ApprovalOutcome.APPROVED, history[0].outcome)
        assertEquals("write_file", history[0].tool)
        assertEquals("agent-test/a.txt", history[0].target)
        assertFalse(history[0].cached)
        assertNull(broker.pendingApproval())
    }

    @Test
    fun `user denial and timeout denial are recorded differently`() = runTest {
        val denied = ApprovalBroker(60_000) { false }
        assertFalse(denied.request(writeAction()))
        assertEquals(ApprovalOutcome.DENIED_USER, denied.history()[0].outcome)

        val timedOut = ApprovalBroker(200) { awaitCancellation() }
        assertFalse(timedOut.request(writeAction()))
        assertEquals(ApprovalOutcome.DENIED_TIMEOUT, timedOut.history()[0].outcome)
    }

    @Test
    fun `dedup hit is recorded as cached approval`() = runTest {
        var presented = 0
        val broker = ApprovalBroker(60_000) { presented++; true }
        val action = writeAction()
        assertTrue(broker.request(action))
        assertTrue(broker.request(action))
        assertEquals("同一逻辑调用只应弹一次审批", 1, presented)
        val history = broker.history()
        assertEquals(2, history.size)
        assertEquals(ApprovalOutcome.APPROVED, history[1].outcome)
        assertTrue("第二次应记为缓存命中", history[1].cached)
    }

    @Test
    fun `pending approval is visible while presenter waits`() = runTest {
        var brokerRef: ApprovalBroker? = null
        val broker = ApprovalBroker(60_000) {
            val pending = brokerRef?.pendingApproval()
            assertNotNull("弹窗展示期间待审批必须可读", pending)
            assertTrue("截止时间必须在未来", pending!!.deadlineEpochMillis > System.currentTimeMillis())
            assertEquals("write_file", pending.tool)
            delay(10)
            true
        }
        brokerRef = broker
        assertTrue(broker.request(writeAction()))
        assertNull("决定后待审批必须清除", broker.pendingApproval())
    }

    @Test
    fun `closed broker records unavailable denial`() = runTest {
        val broker = ApprovalBroker(60_000) { true }
        broker.close()
        assertFalse(broker.request(writeAction()))
        assertEquals(ApprovalOutcome.DENIED_UNAVAILABLE, broker.history()[0].outcome)
    }

    @Test
    fun `presenter exception records unavailable denial`() = runTest {
        val broker = ApprovalBroker(60_000) { throw IllegalStateException("activity gone") }
        assertFalse(broker.request(writeAction()))
        assertEquals(ApprovalOutcome.DENIED_UNAVAILABLE, broker.history()[0].outcome)
    }

    @Test
    fun `history keeps at most 50 records`() = runTest {
        val broker = ApprovalBroker(60_000) { true }
        repeat(55) { i ->
            assertTrue(broker.request(writeAction(id = "call-$i", idem = "idem-$i")))
        }
        val history = broker.history()
        assertEquals(ApprovalBroker.MAX_HISTORY, history.size)
        assertEquals("最旧的 5 条应被丢弃", "call-5", history[0].actionId)
        assertEquals("call-54", history.last().actionId)
    }

    @Test
    fun `target summary never contains file content`() = runTest {
        val broker = ApprovalBroker(60_000) { true }
        val action = writeAction().copy(
            args = mapOf("path" to "a.txt", "content" to "SECRET-CONTENT-must-not-appear"),
        )
        assertTrue(broker.request(action))
        val record = broker.history()[0]
        assertEquals("a.txt", record.target)
        assertFalse(record.target?.contains("SECRET-CONTENT") ?: false)
    }
}
