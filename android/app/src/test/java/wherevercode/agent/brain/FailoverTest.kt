package wherevercode.agent.brain

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FailoverTest {

    @Test
    fun `retryable error retries once on the same brain then follows fallback order`() = runTest {
        val policy = FailoverPolicy(maxSameBrainRetries = 1, fallbackBrainIds = listOf("brain-2", "brain-3"))
        val runner = FailoverRunner(policy)
        var calls = 0
        val outcome = runner.run("brain-1") { brainId ->
            calls++
            if (brainId == "brain-1") {
                throw FailoverException("timeout", "调用超时", retryable = true)
            }
            "ok-from-$brainId"
        }
        // brain-1 tried twice (initial + 1 retry), then brain-2 succeeded.
        assertEquals(3, calls)
        assertTrue(outcome is FailoverOutcome.Success)
        val success = outcome as FailoverOutcome.Success
        assertEquals("brain-2", success.brainId)
        assertEquals("ok-from-brain-2", success.value)
    }

    @Test
    fun `retryable=false reports the reason immediately without retry`() = runTest {
        val policy = FailoverPolicy(fallbackBrainIds = listOf("brain-2"))
        val runner = FailoverRunner(policy)
        var calls = 0
        val outcome = runner.run("brain-1") { _ ->
            calls++
            throw FailoverException("unauthorized", "密钥无效，请检查配置", retryable = false)
        }
        assertEquals(1, calls)
        assertTrue(outcome is FailoverOutcome.Failure)
        val failure = outcome as FailoverOutcome.Failure
        assertEquals("unauthorized", failure.error.code)
        assertEquals(listOf("brain-1"), failure.triedBrains)
    }

    @Test
    fun `empty fallback list never auto-switches`() = runTest {
        val policy = FailoverPolicy(fallbackBrainIds = emptyList())
        val runner = FailoverRunner(policy)
        var calls = 0
        val outcome = runner.run("brain-1") { _ ->
            calls++
            throw FailoverException("timeout", "调用超时", retryable = true)
        }
        // Initial + 1 same-brain retry, then stop: no fallback to switch to.
        assertEquals(2, calls)
        assertTrue(outcome is FailoverOutcome.Failure)
        assertEquals(listOf("brain-1"), (outcome as FailoverOutcome.Failure).triedBrains)
    }

    @Test
    fun `nextFallback skips already tried brains`() {
        val policy = FailoverPolicy(fallbackBrainIds = listOf("brain-2", "brain-3"))
        assertEquals("brain-2", Failover.nextFallback("brain-1", policy, emptySet()))
        assertEquals("brain-3", Failover.nextFallback("brain-2", policy, setOf("brain-1", "brain-2")))
        assertNull(Failover.nextFallback("brain-3", policy, setOf("brain-1", "brain-2", "brain-3")))
        assertNull(Failover.nextFallback("brain-1", FailoverPolicy(), emptySet()))
    }

    @Test
    fun `capability degradation is labelled, never faked`() {
        val notes = Failover.describeDegradation(
            BrainCapabilities(streaming = false, toolCalling = false),
        )
        assertEquals(2, notes.size)
        assertTrue(notes.any { it.contains("不支持原生工具调用") })
        assertTrue(notes.any { it.contains("打字机") })
        assertTrue(Failover.describeDegradation(BrainCapabilities(streaming = true, toolCalling = true)).isEmpty())
    }

    @Test
    fun `text fallback extracts a strict JSON tool call only`() {
        val tools = setOf("read_file", "write_file")
        val actions = Failover.extractToolCallsFromText(
            "我来读一下文件：{\"tool\":\"read_file\",\"args\":{\"path\":\"README.md\"}}",
            tools,
            "task-1",
        )
        assertEquals(1, actions?.size)
        assertEquals("read_file", actions?.first()?.tool)
        assertEquals("task-1:0", actions?.first()?.idempotencyKey)
        // Unknown tool: no guessing, null.
        assertNull(
            Failover.extractToolCallsFromText(
                "{\"tool\":\"delete_everything\",\"args\":{}}",
                tools,
                "task-1",
            ),
        )
        // No JSON at all: null.
        assertNull(Failover.extractToolCallsFromText("就是聊聊天", tools, "task-1"))
    }
}
