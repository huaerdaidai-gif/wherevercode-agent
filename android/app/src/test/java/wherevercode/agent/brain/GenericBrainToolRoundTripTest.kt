package wherevercode.agent.brain

import java.io.BufferedReader
import java.io.File
import java.io.StringReader
import java.nio.file.Files
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import wherevercode.agent.brain.generic.GenericOpenAiApiClient
import wherevercode.agent.brain.generic.GenericOpenAiBrainAdapter
import wherevercode.agent.brain.generic.GenericOpenAiConnection
import wherevercode.agent.brain.generic.GenericOpenAiCredentialStore
import wherevercode.agent.brain.generic.GenericOpenAiTransport
import wherevercode.agent.core.device.JvmDeviceAdapter
import wherevercode.agent.core.security.CommandLimits
import wherevercode.agent.core.security.InMemoryAuditLog
import wherevercode.agent.core.workspace.FileWorkspaceAdapter

/**
 * Generic-brain stage-2 tool round trip (maintainer 2026-11-11: "派人做").
 *
 * The REAL [GenericOpenAiBrainAdapter] talks to a scripted fake
 * OpenAI-compatible server ([ScriptedTransport] serves canned SSE, one
 * body per POST), through the REAL [BrainEngine] and the REAL
 * [ToolRegistry], against a real temp workspace. No test here touches
 * the network, spends money, or uses a real key (the fake key is the
 * literal string "test-key-generic-1").
 *
 * What this pins:
 * - a single read_file call is executed for real and its result is fed
 *   back as a role=tool message before the model answers in text;
 * - a denied write_file is fed back as a failed tool result and the
 *   file is never written (approval runs, denial is not swallowed);
 * - ever-changing calls stop at the 8-round limit with
 *   `tool_round_limit`, never looping forever;
 * - a text-only reply uses no tool and is never presented as executed
 *   (zero tool executions, the workspace untouched).
 *
 * This is the adapter-level round trip only: the provider is still a
 * script, not 智谱. Real-device verification (K70 + the real zhipu
 * key, which only maintainer holds) is still pending — see DELIVERY.md.
 */
class GenericBrainToolRoundTripTest {

    /** One canned SSE body per POST; an over-long turn re-serves the last one. */
    private class ScriptedTransport(
        private val bodies: List<String>,
    ) : GenericOpenAiTransport {
        val postBodies = mutableListOf<String>()
        var postCalls = 0
            private set

        override fun post(url: String, apiKey: String, requestBody: String): GenericOpenAiConnection {
            postCalls += 1
            postBodies += requestBody
            val body = bodies.getOrNull(postCalls - 1) ?: bodies.last()
            return RoundTripConnection(body)
        }

        override fun get(url: String, apiKey: String): GenericOpenAiConnection {
            throw UnsupportedOperationException("round-trip tests never GET")
        }
    }

    private class RoundTripConnection(
        private val body: String,
    ) : GenericOpenAiConnection {
        override val statusCode: Int = 200
        override fun openBodyReader(): BufferedReader = BufferedReader(StringReader(body))
        override fun readErrorBody(): String? = null
        override fun close() = Unit
    }

    private class RoundTripConfigStore(
        private val config: BrainConfig,
    ) : BrainConfigStore {
        private var currentId: String? = "model-api-generic"
        override fun listConfigs(): List<BrainConfig> = listOf(config)
        override fun getConfig(brainId: String): BrainConfig? =
            config.takeIf { it.brainId == brainId }
        override fun saveConfig(config: BrainConfig) = Unit
        override fun deleteConfig(brainId: String) = Unit
        override fun currentBrainId(): String? = currentId
        override fun setCurrentBrainId(brainId: String?) { currentId = brainId }
    }

    private class RoundTripCredentialStore : GenericOpenAiCredentialStore {
        override fun saveApiKey(authRef: String, apiKey: String) = Unit
        override fun loadApiKey(authRef: String): String? = "test-key-generic-1"
        override fun clearApiKey(authRef: String) = Unit
    }

    private lateinit var tmpDir: File
    private lateinit var workspaceRoot: File
    private lateinit var conversations: ConversationStore
    private lateinit var taskStore: FakeTaskStore
    private lateinit var auditLog: InMemoryAuditLog
    private lateinit var tools: ToolRegistry
    private lateinit var eventLog: EventLog
    private lateinit var configStore: RoundTripConfigStore
    private lateinit var credentialStore: RoundTripCredentialStore

    @Before
    fun setUp() {
        tmpDir = Files.createTempDirectory("generic-brain-roundtrip-test").toFile()
        workspaceRoot = File(tmpDir, "workspace").apply { mkdirs() }
        val taskRoot = File(tmpDir, "task-root").apply { mkdirs() }
        File(workspaceRoot, "README.md").writeText("hello from workspace", Charsets.UTF_8)
        val workspaceAdapter = FileWorkspaceAdapter(workspaceRoot, taskRoot)
        val deviceAdapter = JvmDeviceAdapter(listOf(workspaceRoot), workspaceAdapter)
        conversations = InMemoryConversationStore()
        taskStore = FakeTaskStore()
        auditLog = InMemoryAuditLog()
        eventLog = InMemoryEventLog()
        tools = ToolRegistry(deviceAdapter, workspaceAdapter, CommandLimits(), taskStore, auditLog)
        val config = BrainConfig(
            brainId = "model-api-generic",
            kind = BrainKind.MODEL_API,
            adapterId = "model-api-generic",
            label = "智谱（测试替身）",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            model = "glm-5.3",
            authRef = "model-api:model-api-generic",
            fallbackBrainIds = emptyList(),
        )
        configStore = RoundTripConfigStore(config)
        credentialStore = RoundTripCredentialStore()
        BrainAdapterRegistry.clearForTests()
        BrainAdapterRegistry.registerConfig(config)
    }

    @After
    fun tearDown() {
        BrainAdapterRegistry.clearForTests()
        tmpDir.deleteRecursively()
    }

    private fun engine(gate: ApprovalGate, transport: ScriptedTransport): BrainEngine {
        val adapter = GenericOpenAiBrainAdapter(
            configStore = configStore,
            credentialStore = credentialStore,
            apiClient = GenericOpenAiApiClient(transport),
        )
        BrainAdapterRegistry.registerAdapter(adapter)
        return BrainEngine(
            adapters = BrainAdapterRegistry,
            conversations = conversations,
            contextManager = ContextManager(),
            memory = InMemoryMemoryStore(),
            tools = tools,
            eventLog = eventLog,
            approvalGate = gate,
            currentBrainId = { "model-api-generic" },
            maxToolCallsPerTurn = 8,
            maxTurnMillis = 120_000,
            controlState = { TurnControlState.RUNNING },
            nowMillis = { System.currentTimeMillis() },
            failoverPolicy = FailoverPolicy(),
        )
    }

    /** SSE for one tool call; the arguments JSON is split across two chunks. */
    private fun sseToolCall(
        id: String,
        name: String,
        argsJson: String,
        preamble: String? = null,
    ): String {
        // Split the RAW arguments first, then escape each half: splitting
        // the escaped form can cut an escape pair (\" -> \ + ") and the
        // chunk would be invalid JSON, losing the fragment.
        val split = argsJson.length / 2
        fun esc(raw: String) = raw.replace("\\", "\\\\").replace("\"", "\\\"")
        val first = esc(argsJson.substring(0, split))
        val rest = esc(argsJson.substring(split))
        val lines = mutableListOf<String>()
        if (preamble != null) {
            lines += """data: {"choices":[{"delta":{"content":"$preamble"}}]}"""
        }
        lines += """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"$id","function":{"name":"$name","arguments":"$first"}}]}}]}"""
        lines += """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"$rest"}}]}}]}"""
        lines += """data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}"""
        lines += "data: [DONE]"
        return lines.joinToString("\n")
    }

    private fun sseText(text: String): String = """
        data: {"choices":[{"delta":{"content":"$text"}}]}
        data: {"choices":[{"delta":{},"finish_reason":"stop"}]}
        data: [DONE]
    """.trimIndent()

    /** role=tool messages in a recorded request body, parsed as JSON objects. */
    private fun toolMessagesIn(body: String): List<JSONObject> {
        val messages = JSONObject(body).getJSONArray("messages")
        return (0 until messages.length())
            .map { messages.getJSONObject(it) }
            .filter { it.optString("role") == "tool" }
    }

    @Test
    fun `single read round trip executes for real and feeds the result back`() = runTest {
        val transport = ScriptedTransport(
            listOf(
                sseToolCall(
                    id = "call-1",
                    name = "read_file",
                    argsJson = """{"path":"README.md"}""",
                    preamble = "我先看看。",
                ),
                sseText("已读取完毕：hello from workspace"),
            ),
        )
        val conversation = conversations.createConversation("model-api-generic", "读文件")
        val engine = engine(FakeApprovalGate(allow = true), transport)
        val events = engine.chat(conversation.id, "请读一下 README").toList()

        // Two adapter calls: round 1 calls the tool, round 2 answers.
        assertEquals(2, transport.postCalls)
        // Round 1 offered the tool to the model.
        assertTrue(transport.postBodies[0].contains("\"tools\""))
        assertTrue(transport.postBodies[0].contains("\"name\":\"read_file\""))
        // The read really executed.
        assertTrue(events.any { it is BrainEvent.ToolCall && it.name == "read_file" })
        val result = events.filterIsInstance<BrainEvent.ToolResult>().firstOrNull()
        assertTrue(result != null && result.ok)
        // The result was fed back as a role=tool message in round 2.
        val fedBack = toolMessagesIn(transport.postBodies[1])
        assertEquals(1, fedBack.size)
        assertTrue(fedBack[0].has("tool_call_id"))
        val fedBackJson = JSONObject(fedBack[0].getString("content"))
        assertTrue(fedBackJson.getBoolean("ok"))
        assertTrue(fedBackJson.toString().contains("hello from workspace"))
        // The final text answers from the fed-back result, and history agrees.
        assertTrue(events.any { it is BrainEvent.TextDelta && it.text.contains("已读取完毕") })
        assertTrue(events.last() is BrainEvent.Done)
        val messages = conversations.listMessages(conversation.id)
        assertTrue(messages.any { it.role == "tool" && it.content.contains("hello from workspace") })
        assertTrue(messages.any { it.role == "assistant" && it.source == "model-api-generic" })
    }

    @Test
    fun `denied write is fed back and the file is never written`() = runTest {
        val transport = ScriptedTransport(
            listOf(
                sseToolCall(
                    id = "call-write",
                    name = "write_file",
                    argsJson = """{"path":"NOTE.txt","content":"hi"}""",
                    preamble = "我来写。",
                ),
                sseText("写文件被拒绝了，我没有写入。"),
            ),
        )
        val gate = FakeApprovalGate(allow = false)
        val conversation = conversations.createConversation("model-api-generic", "写文件")
        val engine = engine(gate, transport)
        val events = engine.chat(conversation.id, "请写一个 NOTE.txt").toList()

        // Approval was actually asked, and denied.
        assertTrue(gate.asked.any { it.tool == "write_file" })
        val result = events.filterIsInstance<BrainEvent.ToolResult>().firstOrNull()
        assertTrue(result != null && !result.ok)
        // The denial was fed back (round 2 carries a role=tool message),
        // not swallowed — and nothing landed on disk.
        assertEquals(2, transport.postCalls)
        val fedBack = toolMessagesIn(transport.postBodies[1])
        assertEquals(1, fedBack.size)
        assertFalse(JSONObject(fedBack[0].getString("content")).getBoolean("ok"))
        assertFalse(File(workspaceRoot, "NOTE.txt").exists())
        assertTrue(events.any { it is BrainEvent.TextDelta && it.text.contains("被拒绝") })
        assertTrue(events.last() is BrainEvent.Done)
    }

    @Test
    fun `ever-changing missing reads stop at the eight round limit`() = runTest {
        val bodies = (1..8).map { round ->
            sseToolCall(
                id = "call-$round",
                name = "read_file",
                argsJson = """{"path":"missing-$round"}""",
                preamble = "正在处理第 $round 轮",
            )
        }
        val transport = ScriptedTransport(bodies)
        val conversation = conversations.createConversation("model-api-generic", "列文件")
        val engine = engine(FakeApprovalGate(allow = true), transport)
        val events = engine.chat(conversation.id, "请读一下缺失的文件").toList()

        val error = events.filterIsInstance<BrainEvent.Error>().firstOrNull()
        assertTrue(error != null && error.code == "tool_round_limit")
        assertEquals(8, events.filterIsInstance<BrainEvent.ToolResult>().size)
        assertEquals(8, transport.postCalls)
    }

    @Test
    fun `text-only reply uses no tool and is not presented as executed`() = runTest {
        val transport = ScriptedTransport(listOf(sseText("我直接回答，不调工具。")))
        val conversation = conversations.createConversation("model-api-generic", "读文件")
        val engine = engine(FakeApprovalGate(allow = true), transport)
        val events = engine.chat(conversation.id, "请读一下 README").toList()

        // The model was offered tools (first request carries them) and
        // chose not to use any — so nothing executed at all.
        assertEquals(1, transport.postCalls)
        assertTrue(transport.postBodies[0].contains("\"tools\""))
        assertTrue(events.none { it is BrainEvent.ToolCall })
        assertTrue(events.none { it is BrainEvent.ToolResult })
        assertTrue(events.none { it is BrainEvent.Error })
        assertEquals(
            0,
            eventLog.entries(conversation.id).count { it.kind == "tool_result" },
        )
        assertTrue(eventLog.entries(conversation.id).any { it.kind == "tools_unused" })
        assertTrue(events.any { it is BrainEvent.TextDelta && it.text.contains("直接回答") })
        assertTrue(events.last() is BrainEvent.Done)
        // The workspace is untouched: no file was created or faked into being.
        assertEquals(listOf("README.md"), workspaceRoot.listFiles()?.map { it.name }?.sorted())
    }
}
