package wherevercode.agent.brain.cloudflare

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject
import wherevercode.agent.brain.BrainAdapter
import wherevercode.agent.brain.BrainCapabilities
import wherevercode.agent.brain.BrainEvent
import wherevercode.agent.brain.BrainKind
import wherevercode.agent.brain.ChatRequest
import wherevercode.agent.brain.ModelInfo
import wherevercode.agent.brain.StreamParser
import wherevercode.agent.brain.siwc.BrainError
import wherevercode.agent.brain.siwc.BrainMessage
import wherevercode.agent.brain.siwc.BrainProvider
import wherevercode.agent.brain.siwc.BrainRequest
import wherevercode.agent.brain.siwc.BrainRole
import wherevercode.agent.brain.siwc.BrainStreamEvent
import wherevercode.agent.brain.siwc.BrainTool

/**
* CloudflareM2BrainAdapter: the bridge between the Cloudflare BrainProvider
* and the M2 BrainAdapter contract the BrainEngine trusts.
*
* Mirrors [wherevercode.agent.brain.siwc.SiwcM2BrainAdapter]:
* the two contracts are deliberately NOT merged — [BrainProvider] stays the
* provider-facing layer, [BrainAdapter] stays the engine-facing layer, and
* the engine never sees a vendor branch.
*
* Mapping rules (all explicit, nothing silently dropped):
* - Role mapping: system messages become [BrainRequest.instructions]
* (the provider sends them as the first system message, which Workers AI
* supports); tool messages become [BrainRole.TOOL] messages with the
* content passed through unchanged and the call id carried in
* [BrainMessage.toolCallId] (OpenAI 兼容端点的配对字段，P1-2)；
* assistant messages map 1:1, so the echo the engine wrote passes
* through verbatim, and their structured [BrainMessage.toolCalls] pass
* through with ids; user messages map 1:1. No "工具…执行结果：" prefix
* is added here any more (the old prefix rendered an empty tool name
* when the engine did not fill ChatMessage.name, and duplicated the
* engine-side prefix).
* - temperature / maxTokens: Cloudflare supports both (temperature 0–5,
* max_tokens ≥ 1). Out-of-range values are rejected loudly by the
* provider, never silently clamped or ignored.
* - model: blank means "not chosen yet" ([BrainRequest.model] = null);
* the provider resolves it from the configured model and errors clearly
* when the configuration has none. No hardcoded fallback.
* - Errors: AuthFailed / Unsupported / NotConfigured are non-retryable;
* Transport is retryable.
* - QuotaExceeded (429 / 3036，兼容第三方报告的 4006）是 non-retryable
* （P1 修复，2026-11-08）：额度耗尽明确提示后停止——引擎不再同大脑
* 重试（额度不会在几秒内恢复，那次重试必然再报 429），也不再按
* fallback 顺序切到 SIWC（SIWC 池已知耗尽，静默回落只会让用户
* 在另一个耗尽的池子里再失败一次）。超额提示仍先以 TextDelta 发出，
* 再发 Error；调用方不得高频重试、不自动付费。
* - Providers must emit [BrainStreamEvent.Failed], not throw. If a
* provider throws, the engine treats it as a retryable transport
* failure (brain_call_failed); an auth failure thrown as an exception
* would be retried by mistake, so the Failed-event contract matters.
*/
class CloudflareM2BrainAdapter(
private val provider: BrainProvider,
/** Currently configured model (for listModels); null = not configured. */
private val configuredModel: () -> String? = { null},
): BrainAdapter {

override val adapterId: String = ADAPTER_ID
override val kind: BrainKind = BrainKind.MODEL_API
override val label: String = provider.displayName

override fun capabilities(): BrainCapabilities = BrainCapabilities(
streaming = provider.capabilities.supportsStreaming,
toolCalling = provider.capabilities.supportsTools,
vision = false,
// Context window varies by model (8B 32k? 未核实）；未知 = null，
// UI 须显示“未知”，不要编一个数字。
maxContextTokens = null,
notes = provider.capabilities.limitsNote,
)

override suspend fun validateCredentials(): Result<Unit> =
if (provider.isConfigured()) {
Result.success(Unit)
} else {
Result.failure(
IllegalStateException(
"未配置 Cloudflare：请在控制台的 Cloudflare 设置里填写 Account ID、" +
"API Token 与模型。",
),
)
}

override suspend fun listModels(): Result<List<ModelInfo>> =
// MODEL_API normally probes /models; Workers AI has no verified
// model-catalogue probe wired here, so the honest answer is the
// single user-configured model (or an empty list when none is set),
// never an invented catalogue.
Result.success(
configuredModel()?.takeIf { it.isNotBlank()}?.let { model ->
listOf(ModelInfo(id = model, displayName = model))
}?: emptyList(),
)

override fun chatStream(request: ChatRequest): Flow<BrainEvent> = flow {
val brainRequest = mapRequest(request)
provider.streamInfer(brainRequest).collect { event ->
when (event) {
is BrainStreamEvent.Delta ->
emit(BrainEvent.TextDelta(event.text))
is BrainStreamEvent.ToolCall -> {
val args = parseToolArgs(event.argumentsJson)
if (args == null) {
// 与 DeepSeekBrainAdapter 完全一致：非法 JSON 不猜参数、不发空对象
// 调用，而是报 tool_arguments_invalid 并停轮（P1-3）。空对象 {} 是
// 合法参数，见 parseToolArgs。
emit(
BrainEvent.Error(
code = "tool_arguments_invalid",
message = "模型返回的工具参数不是合法 JSON（${event.name}），已停止本轮。",
retryable = false,
),
)
emit(BrainEvent.Done("error"))
} else {
emit(
BrainEvent.ToolCall(
id = event.id,
name = event.name,
args = args,
),
)
}
}
is BrainStreamEvent.Completed -> {
event.response.usage?.let { usage ->
emit(
BrainEvent.Usage(
inputTokens = usage.inputTokens?.toInt(),
outputTokens = usage.outputTokens?.toInt(),
),
)
}
emit(BrainEvent.Done("stop"))
}
is BrainStreamEvent.Failed -> {
// 超额时先把原因以文本发给用户（Error 本身也带同样的文案）。
if (event.error is BrainError.QuotaExceeded) {
emit(BrainEvent.TextDelta(QUOTA_NOTICE))
}
emit(mapError(event.error))
emit(BrainEvent.Done("error"))
}
}
}
}

private fun mapRequest(request: ChatRequest): BrainRequest {
val instructions = StringBuilder()
val messages = mutableListOf<BrainMessage>()
for (message in request.messages) {
when (message.role) {
"system" -> {
if (instructions.isNotEmpty()) instructions.append("\n")
instructions.append(message.content)
}
"assistant" ->
messages += BrainMessage(
BrainRole.ASSISTANT,
message.content,
toolCalls = message.toolCalls.map { call ->
wherevercode.agent.brain.siwc.BrainToolCall(
id = call.id,
name = call.name,
argumentsJson = call.argumentsJson,
)
},
)
"tool" ->
// 回填范式（tool_call_repeated 修复，2026-11-08；P1-2 配对，
// 2026-11-09）：tool 结果原样发为 TOOL 角色消息，不加“工具…
// 执行结果：”前缀、不补 TOOL_RESULT_GUIDANCE 指引；同时带上
// 本次调用的 toolCallId，OpenAI 兼容端点据此配对。引擎已按
// 范式回填（assistant 回显 JSON 在前，role="tool" 结果 JSON
// 在后，name 已填）；本层只负责原样透传。
messages += BrainMessage(
BrainRole.TOOL,
message.content,
toolCallId = message.toolCallId,
)
else ->
messages += BrainMessage(BrainRole.USER, message.content)
}
}
return BrainRequest(
messages = messages,
instructions = instructions.toString().takeIf { it.isNotBlank()},
model = request.model.takeIf { it.isNotBlank()},
tools = request.tools.map { tool ->
BrainTool(
name = tool.name,
description = tool.description,
parametersJson = JSONObject(tool.inputSchema as Map<*, *>).toString(),
)
},
temperature = request.temperature,
maxOutputTokens = request.maxTokens,
)
}

/**
* Parse a tool-call arguments JSON string into a Map.
*
* 与 DeepSeekBrainAdapter.parseToolArgs 完全一致（P1-3，2026-11-09）：
* null = 非法 JSON；空串 / 空白 = 空对象（合法）；"{}" = 空对象
* （合法）。调用方见 null 即发 tool_arguments_invalid 并停轮，不再
* 把非法 JSON 静默当成空对象执行——否则日志里的 invalid_action
* 无法区分“模型发了 {}”与“模型发了非法 JSON 字符串”。
* 原始参数字符串可能含敏感内容，一律不记日志、不放进错误消息。
*/
private fun parseToolArgs(argumentsJson: String): Map<String, Any?>? {
if (argumentsJson.isBlank()) return emptyMap()
return try {
StreamParser.jsonObjectToMap(JSONObject(argumentsJson))
} catch (_: Exception) {
null
}
}

private fun mapError(error: BrainError): BrainEvent.Error = when (error) {
is BrainError.NotConfigured -> BrainEvent.Error(
code = "not_configured",
message = error.message?: "未配置 Cloudflare。",
retryable = false,
)
is BrainError.AuthFailed -> BrainEvent.Error(
code = "auth_failed",
message = error.message?: "Cloudflare 授权失败，请检查 API Token。",
retryable = false,
)
is BrainError.QuotaExceeded -> BrainEvent.Error(
// P1: retryable = false。额度耗尽不再同大脑重试（必然再报 429），
// 也不再回落 SIWC（SIWC 池已知耗尽）。引擎见 non-retryable 即
// 直接报出超额提示并停止，不自动付费、不静默换池。
code = "quota_exceeded",
message = error.message?: "Cloudflare 今日免费额度已用完。",
retryable = false,
)
is BrainError.Unsupported -> BrainEvent.Error(
code = "unsupported",
message = error.message?: "该大脑不支持本次请求。",
retryable = false,
)
is BrainError.Transport -> BrainEvent.Error(
code = "transport_error",
message = error.message?: "网络或服务器错误，请稍后重试。",
retryable = true,
)
is BrainError.NotImplemented -> BrainEvent.Error(
code = "not_implemented",
message = error.message?: "该大脑尚未实现。",
retryable = false,
)
}

companion object {
/** Stable M2 adapter id and brain id for the Cloudflare brain. */
const val ADAPTER_ID: String = "cloudflare-workers-ai"

/** BrainConfig.brainId for the Cloudflare brain. */
const val BRAIN_ID: String = "cloudflare-workers-ai"

/** BrainKeyStore authRef holding the Cloudflare API Token. */
const val AUTH_REF: String = "cloudflare:token"

/**
* 超额提示（Error 之前先以文本发出，Error 本身也带同样的文案）。
* 重置时间按核查口径写：官方称每日 00:00 UTC，个别情况可能更久，
* 绝不写“00:00 UTC 一定恢复”。
*/
const val QUOTA_NOTICE: String =
"Cloudflare 今日免费额度已用完（每天 10,000 Neurons，全账户共享）。" +
"按官方说法每日 00:00 UTC 重置，但个别情况下可能持续更久" +
"（最长到首次使用后 24 小时）。"
}
}
