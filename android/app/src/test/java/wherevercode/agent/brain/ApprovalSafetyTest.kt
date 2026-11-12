package wherevercode.agent.brain

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-09 审批安全专项回归。
 *
 * 覆盖：串行化（不叠加弹窗）、去重（同一逻辑调用只批一次）、参数变化
 * 必须重新审批、超时即拒绝、授权可撤销、UI 异常默认拒绝，以及弹窗
 * 文案不泄露文件正文/密钥。全部为纯 Kotlin，不需要 Android 容器。
 */
class ApprovalSafetyTest {

    private fun writeAction(
        id: String = "call-1",
        idem: String = "idem-1",
        path: String = "agent-test/a.txt",
        content: String = "hello",
    ) = Action(
        actionId = id,
        tool = "write_file",
        args = mapOf("path" to path, "content" to content),
        idempotencyKey = idem,
    )

    @Test
    fun `duplicate identical request is presented once`() = runTest {
        var presented = 0
        val broker = ApprovalBroker(60_000) { presented++; delay(10); true }
        val action = writeAction()
        val results = listOf(
            async { broker.request(action) },
            async { broker.request(action) },
        ).awaitAll()
        assertEquals(listOf(true, true), results)
        assertEquals("同一逻辑调用只应弹一次审批", 1, presented)
    }

    @Test
    fun `changed arguments require a new approval`() = runTest {
        var presented = 0
        val broker = ApprovalBroker(60_000) { presented++; true }
        assertTrue(broker.request(writeAction(path = "agent-test/a.txt")))
        assertTrue(broker.request(writeAction(path = "agent-test/b.txt")))
        assertEquals("参数变化必须重新审批", 2, presented)
    }

    @Test
    fun `concurrent approvals are serialised and never overlap`() = runTest {
        var active = 0
        var maxActive = 0
        val broker = ApprovalBroker(60_000) {
            active += 1
            maxActive = maxOf(maxActive, active)
            delay(50)
            active -= 1
            true
        }
        val results = listOf(
            async { broker.request(writeAction(id = "call-a")) },
            async { broker.request(writeAction(id = "call-b")) },
        ).awaitAll()
        assertEquals(listOf(true, true), results)
        assertEquals("同一时刻只能有一个审批弹窗", 1, maxActive)
    }

    @Test
    fun `timeout denies instead of hanging or auto-approving`() = runTest {
        var presented = 0
        val broker = ApprovalBroker(1_000) { presented++; awaitCancellation() }
        assertFalse(broker.request(writeAction()))
        assertFalse(broker.request(writeAction()))
        assertEquals(2, presented)
        assertFalse("超时不得留下有效授权", broker.isApproved(writeAction()))
    }

    @Test
    fun `invalidate revokes an existing approval and forces a new prompt`() = runTest {
        var presented = 0
        val broker = ApprovalBroker(60_000) { presented++; true }
        val action = writeAction()
        assertTrue(broker.request(action))
        assertTrue(broker.isApproved(action))

        broker.invalidate()
        assertFalse("界面销毁/用户停止后旧授权必须失效", broker.isApproved(action))

        assertTrue(broker.request(action))
        assertEquals(2, presented)
    }

    @Test
    fun `close denies everything and isApproved stays false`() = runTest {
        val broker = ApprovalBroker(60_000) { true }
        val action = writeAction()
        assertTrue(broker.request(action))
        broker.close()
        assertFalse(broker.isApproved(action))
        assertFalse(broker.request(action))
    }

    @Test
    fun `presenter exception denies by default`() = runTest {
        val broker = ApprovalBroker(60_000) { throw IllegalStateException("activity gone") }
        assertFalse(broker.request(writeAction()))
        assertFalse(broker.isApproved(writeAction()))
    }

    @Test
    fun `write prompt shows target and size but never the body`() {
        val secretBody = "API_KEY=sk-supersecret1234567890\nprint('hi')"
        val action = writeAction(content = secretBody)
        val message = ApprovalPromptFormatter.message(action)
        assertTrue(message.contains("agent-test/a.txt"))
        assertTrue(message.contains("字节"))
        assertFalse("文件正文不得出现在弹窗", message.contains("print('hi')"))
        assertFalse(message.contains("sk-supersecret1234567890"))
    }

    @Test
    fun `run command prompt redacts secret-shaped arguments`() {
        val action = Action(
            actionId = "call-run",
            tool = "run_command",
            args = mapOf(
                "argv" to listOf("python3", "test.py", "--api-key", "test-fixture-value"),
            ),
            idempotencyKey = "idem-run",
        )
        val message = ApprovalPromptFormatter.message(action)
        assertTrue(message.contains("python3 test.py"))
        assertFalse("命令行里的密钥必须脱敏", message.contains("sk-abcdefghijklmnop123456"))
        assertTrue(message.contains("[REDACTED]"))
    }

    @Test
    fun `dispatch prompt redacts bearer tokens`() {
        val action = Action(
            actionId = "call-dispatch",
            tool = "dispatch_task",
            args = mapOf("goal" to "调用接口 Authorization: Bearer abcdefghijklmnop"),
            idempotencyKey = "idem-dispatch",
        )
        val message = ApprovalPromptFormatter.message(action)
        assertFalse(message.contains("abcdefghijklmnop"))
    }
}
