package wherevercode.agent.core.task

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.brain.BrainAdapter
import wherevercode.agent.brain.BrainCapabilities
import wherevercode.agent.brain.BrainEvent
import wherevercode.agent.brain.BrainKind
import wherevercode.agent.brain.ChatRequest
import wherevercode.agent.brain.ModelInfo

/**
 * 端侧小模型路由 v1——提示词 / 解析 / 设置 / 回落单测（2026-11-11）。
 * 纯 JVM 单测：分流执行器用假适配器，不碰网络、不碰 SharedPreferences。
 */
class TriageRouterTest {

    private class FakeAdapter(
        private val script: suspend FlowCollector<BrainEvent>.() -> Unit,
    ) : BrainAdapter {
        var seenRequest: ChatRequest? = null
        override val adapterId: String = "fake"
        override val kind: BrainKind = BrainKind.MODEL_API
        override val label: String = "fake"
        override fun capabilities(): BrainCapabilities =
            BrainCapabilities(streaming = true, toolCalling = false)

        override suspend fun validateCredentials(): Result<Unit> = Result.success(Unit)
        override suspend fun listModels(): Result<List<ModelInfo>> = Result.success(emptyList())
        override fun chatStream(request: ChatRequest): Flow<BrainEvent> {
            seenRequest = request
            return flow(script)
        }
    }

    @Test
    fun `prompt contains task text and both modes, bounds long text`() {
        val prompt = TriageRouter.buildPrompt("修复登录 bug")
        assertTrue(prompt.contains("修复登录 bug"))
        assertTrue(prompt.contains("SIMPLE"))
        assertTrue(prompt.contains("COMPLEX"))
        val longPrompt = TriageRouter.buildPrompt("x".repeat(5000))
        // 任务文本截断到 2000 字符，提示词总长有界（不把聊天记录喂进去）。
        assertTrue(longPrompt.length < 3000)
        assertTrue(longPrompt.count { it == 'x' } <= TriageRouter.MAX_TRIAGE_TEXT_CHARS)
    }

    @Test
    fun `parse simple and complex with reason`() {
        val simple = TriageRouter.parseResponse("SIMPLE：单文件小改")
        assertEquals(TriageMode.SINGLE, simple?.first)
        assertEquals("单文件小改", simple?.second)
        val complex = TriageRouter.parseResponse("COMPLEX：涉及三模块重构")
        assertEquals(TriageMode.PIPELINE, complex?.first)
        assertEquals("涉及三模块重构", complex?.second)
        // 大小写不敏感、半角冒号也认。
        assertEquals(TriageMode.SINGLE, TriageRouter.parseResponse("simple: trivial")?.first)
        // 只有分类没有理由：补默认理由，不算失败。
        assertEquals(TriageMode.PIPELINE, TriageRouter.parseResponse("COMPLEX")?.first)
        assertTrue(TriageRouter.parseResponse("COMPLEX")?.second?.isNotBlank() == true)
    }

    @Test
    fun `parse rejects garbage and empty`() {
        assertNull(TriageRouter.parseResponse(""))
        assertNull(TriageRouter.parseResponse("   \n  "))
        assertNull(TriageRouter.parseResponse("今天天气不错"))
        assertNull(TriageRouter.parseResponse("理由但没分类"))
        // 前导空行后才是有效分类：取第一个非空行，仍认。
        assertEquals(
            TriageMode.SINGLE,
            TriageRouter.parseResponse("\n\nSIMPLE：可以")?.first,
        )
    }

    @Test
    fun `in-memory settings default off and save round trip`() {
        val settings = InMemoryRouterSettings()
        assertFalse(settings.triageEnabled)
        assertNull(settings.triageBrainId)
        settings.save(true, "model-api-zhipu")
        assertTrue(settings.triageEnabled)
        assertEquals("model-api-zhipu", settings.triageBrainId)
        settings.save(false, "model-api-zhipu")
        assertFalse(settings.triageEnabled)
        assertNull(settings.triageBrainId)
    }

    @Test
    fun `runner returns record on clean reply and sends a short classification request`() = runBlocking {
        val adapter = FakeAdapter {
            emit(BrainEvent.TextDelta("COMPLEX："))
            emit(BrainEvent.TextDelta("多模块重构"))
            emit(BrainEvent.Done("stop"))
        }
        val record = TriageRunner.run(adapter, "zhipu · glm-4.7-flash", "重构登录模块", 8000)
        assertEquals(TriageMode.PIPELINE, record?.suggestedMode)
        assertEquals("多模块重构", record?.reason)
        assertEquals("zhipu · glm-4.7-flash", record?.modelLabel)
        assertTrue((record?.latencyMs ?: -1) >= 0)
        // 只发一次短分类请求：不带工具、model 为空（走分流大脑自己的模型）。
        val request = adapter.seenRequest
        assertEquals("", request?.model)
        assertEquals(128, request?.maxTokens)
        assertEquals(0.0, request?.temperature)
        assertTrue(request?.tools.isNullOrEmpty())
    }

    @Test
    fun `runner falls back to null on error event`() = runBlocking {
        val adapter = FakeAdapter {
            emit(BrainEvent.TextDelta("COMPLEX：多模块"))
            emit(BrainEvent.Error("http_500", "server error", true))
        }
        assertNull(TriageRunner.run(adapter, "fake", "重构登录模块", 8000))
    }

    @Test
    fun `runner falls back to null on empty reply`() = runBlocking {
        val adapter = FakeAdapter {
            emit(BrainEvent.Done("stop"))
        }
        assertNull(TriageRunner.run(adapter, "fake", "修个小 bug", 8000))
    }

    @Test
    fun `runner falls back to null on unparseable reply`() = runBlocking {
        val adapter = FakeAdapter {
            emit(BrainEvent.TextDelta("今天天气不错"))
        }
        assertNull(TriageRunner.run(adapter, "fake", "修个小 bug", 8000))
    }

    @Test
    fun `runner falls back to null when adapter throws and never throws itself`() = runBlocking {
        val adapter = FakeAdapter {
            throw RuntimeException("network down")
        }
        assertNull(TriageRunner.run(adapter, "fake", "修个小 bug", 8000))
    }

    @Test
    fun `runner falls back to null on timeout`() = runBlocking {
        val adapter = FakeAdapter {
            delay(2000)
            emit(BrainEvent.TextDelta("SIMPLE：小改"))
        }
        assertNull(TriageRunner.run(adapter, "fake", "修个小 bug", 50))
    }
}
