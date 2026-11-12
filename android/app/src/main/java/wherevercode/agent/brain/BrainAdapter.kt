package wherevercode.agent.brain

import kotlinx.coroutines.flow.Flow

/**
 * 工具结果回填（tool_call_repeated 修复，2026-11-08）：按 Cloudflare
 * 官方 Function calling 范式回填——assistant 回显工具调用 JSON +
 * role="tool" 的结果 JSON。P1 的 USER 形态回填 + TOOL_RESULT_GUIDANCE
 * 文本指引是官方未记载的变通做法，已废止：常量随本次修复删除，
 * 引擎与 CloudflareM2BrainAdapter 不再追加该指引。
 */

/**
 * Brain adapter interface + registry (Universal Brain Engine, stage 2).
 *
 * Design source: stage-1 audit section 4.3, modelled on the old project's
 * `CloudCoderProvider` registration pattern (`cloud_coders.py:286-353`):
 * adapters self-register, the engine only trusts the registry, and no
 * `if (vendor == "...")` hard-coding is allowed anywhere in the engine.
 *
 * The three [BrainKind] values are NOT interchangeable (this must also be
 * shown in UI copy, audit section 4.3):
 * - MODEL_API: an HTTP model API billed per call. A key is enough to call
 *   it; streaming and native tool calling are normal; a leaked key is a
 *   leaked bill.
 * - AGENT_RUNTIME: an already-logged-in CLI/App on the device (CodeBuddy,
 *   Codex, ...). There is no key concept; every call spawns a process and
 *   the output is the CLI's stream-json, not a native chat API. The login
 *   state lives inside the app sandbox and is invisible from Termux.
 * - SUBSCRIPTION: borrowing an AGENT_RUNTIME login's membership quota as
 *   the brain. One CLI call per decision, slow, quota-bound. It must never
 *   be presented as an API call: no key, no baseUrl, no `/models`.
 */
enum class BrainKind {
    MODEL_API,
    AGENT_RUNTIME,
    SUBSCRIPTION,
    /**
     * 平台 Agent（V5，2026-11-11）：平台上的 Agent（如 Example Agent），
     * 走平台通道收发消息、经 webhook 唤醒；不是模型 API 也不是 CLI。
     */
    PLATFORM_AGENT,
    /**
     * 云端 Agent（V5，2026-11-11）：对方把 App 加为自定义 MCP
     * 连接器、自己来领单；不是模型 API 也不是 CLI。
     */
    CLOUD_AGENT,
}

/**
 * Capabilities are self-reported by the adapter. The engine trusts only
 * self-report + `/models` probing, never guesses. Unknown = null, and the
 * UI must show "未知" (unknown) instead of inventing a value.
 * Ported from audit section 4.3 `BrainCapabilities` sketch.
 */
data class BrainCapabilities(
    val streaming: Boolean,
    val toolCalling: Boolean,
    val vision: Boolean = false,
    val maxContextTokens: Int? = null,
    val notes: String = "",
    /**
     * P1 §7.4：模型工具调用的实测分级，与 [toolCalling]（适配器声称支持）
     * 是两回事，不得合并。null = 未验证（现状 8B 与 120b 均属此级）；
     * true = K70 真机多步任务通过；false = K70 已验证不支持。
     * 由 K70 验证结果写入，不由代码写死。
     */
    val toolCallingVerified: Boolean? = null,
)

/**
 * A tool call carried by an assistant message on the wire.
 *
 * Why this exists (2026-11-09 root-cause fix): the engine used to feed tool
 * results back as an assistant *text* echo (`{"name":…,"arguments":…}`) plus a
 * `role="tool"` message, but it never carried the call id. Native
 * OpenAI-compatible APIs (DeepSeek, Cloudflare's OpenAI-compatible endpoint)
 * require the assistant turn to contain `tool_calls[].id` and every tool
 * message to contain the matching `tool_call_id`; without that pairing the
 * model does not see the result as the answer to its call, so it re-issues
 * the call (often with missing arguments) until the engine's guards fire
 * (`invalid_action` → `tool_repeated_failure` / `tool_no_progress`).
 *
 * [argumentsJson] is the arguments object serialised as a JSON string, the
 * exact shape the OpenAI wire format expects.
 */
data class ChatToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

/**
 * 一张随消息发给大脑的图片（V5 按主流改，maintainer 2026-11-11）。
 * 只在当轮有效：图片字节不进对话历史（不持久化进 Message 内容，
 * 不随历史每轮重发）；[dataUrl] 是下采样后的 JPEG data URL
 * （`data:image/jpeg;base64,…`），由发送方（ChatAttachment）做好。
 */
data class ChatImage(val dataUrl: String)

data class ChatMessage(
    val role: String, // user | assistant | tool | system
    val content: String,
    val toolCallId: String? = null,
    val name: String? = null,
    /**
     * Assistant tool calls, in wire order. Empty for every non-assistant
     * message and for assistant messages that only carry text. The legacy
     * [content] echo is kept so adapters without native tool support still
     * receive something readable.
     */
    val toolCalls: List<ChatToolCall> = emptyList(),
    /**
     * 本轮用户消息随附的图片（OpenAI / xAI 同款 `image_url` parts 格式）。
     * 只在当轮的 user 消息上出现；不支持 vision 的适配器收不到它
     * （引擎按 [BrainCapabilities.vision] 逐轮过滤，见 BrainEngine），
     * 所以这里的空与非空绝不能理解为“模型一定看得到”。
     */
    val images: List<ChatImage> = emptyList(),
)

data class ToolDef(
    val name: String,
    val description: String,
    /** JSON-Schema-style map, e.g. {"type":"object","properties":{...},"required":[...]} */
    val inputSchema: Map<String, Any?> = emptyMap(),
)

data class ModelInfo(
    val id: String,
    val displayName: String,
    val contextTokens: Int? = null,
)

data class ChatRequest(
    val messages: List<ChatMessage>,
    val model: String,
    val tools: List<ToolDef> = emptyList(),
    val maxTokens: Int? = null,
    val temperature: Double? = null,
)

/**
 * Normalised stream events, audit section 4.2.
 * Usage carries ONLY numbers the provider returned; never an estimate.
 */
sealed class BrainEvent {
    data class TextDelta(val text: String) : BrainEvent()
    data class ReasoningDelta(val text: String) : BrainEvent()
    data class ToolCall(val id: String, val name: String, val args: Map<String, Any?>) : BrainEvent()
    data class ToolResult(val id: String, val ok: Boolean, val summary: String) : BrainEvent()
    data class Usage(val inputTokens: Int?, val outputTokens: Int?) : BrainEvent()
    data class Error(val code: String, val message: String, val retryable: Boolean) : BrainEvent()
    data class Done(val finishReason: String) : BrainEvent()
}

interface BrainAdapter {
    val adapterId: String
    val kind: BrainKind
    val label: String
    fun capabilities(): BrainCapabilities

    /**
     * Read-only probe: must not produce a billed call beyond the probe
     * itself and must have no side effects. Failure returns a
     * human-actionable reason.
     */
    suspend fun validateCredentials(): Result<Unit>

    /**
     * MODEL_API probes `/models`; AGENT_RUNTIME / SUBSCRIPTION return a
     * fixed list or an empty list (they have no model catalogue).
     */
    suspend fun listModels(): Result<List<ModelInfo>>

    /** Streaming chat; tool calls come out as BrainEvent.ToolCall and are executed by the engine. */
    fun chatStream(request: ChatRequest): Flow<BrainEvent>
}

/**
 * Adapter registry. Mirrors `CloudCoderProvider.register_provider()` /
 * `load_provider_plugins()` (`cloud_coders.py`): the engine only looks
 * adapters up here. Android has no `*_plugin.py` scan, so registration is
 * explicit at app start via [registerAdapter] / [loadAdapters]; the
 * important invariant is the same — no hard-coded vendor branches in the
 * engine.
 */
object BrainAdapterRegistry {
    private val adapters = LinkedHashMap<String, BrainAdapter>()
    private val configs = LinkedHashMap<String, BrainConfig>()

    fun registerAdapter(adapter: BrainAdapter) {
        adapters[adapter.adapterId] = adapter
    }

    /** Register a batch of adapters (the Android equivalent of load_provider_plugins). */
    fun loadAdapters(vararg adapters: BrainAdapter) {
        adapters.forEach { registerAdapter(it) }
    }

    fun get(adapterId: String): BrainAdapter? = adapters[adapterId]

    fun all(): List<BrainAdapter> = adapters.values.toList()

    fun clearForTests() {
        adapters.clear()
        configs.clear()
    }

    /** Brain configs are registered here so the engine can resolve brainId -> adapter. */
    fun registerConfig(config: BrainConfig) {
        configs[config.brainId] = config
    }

    /**
     * 注销一个大脑配置（删除大脑时调用，与 [registerConfig] 配对）。
     * 引擎按 brainId 找配置走的就是这里（BrainEngine.chat 里
     * adapters.configFor）：光删 BrainConfigStore 不清这里，删掉的
     * 脑在本次启动内仍能被路由到。
     */
    fun unregisterConfig(brainId: String) {
        configs.remove(brainId)
    }

    fun configFor(brainId: String): BrainConfig? = configs[brainId]

    fun allConfigs(): List<BrainConfig> = configs.values.toList()
}
