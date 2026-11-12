package wherevercode.agent.brain.cli

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import wherevercode.agent.brain.BrainAdapter
import wherevercode.agent.brain.BrainCapabilities
import wherevercode.agent.brain.BrainConfigStore
import wherevercode.agent.brain.BrainEvent
import wherevercode.agent.brain.BrainKind
import wherevercode.agent.brain.ChatRequest
import wherevercode.agent.brain.ModelInfo
import wherevercode.agent.core.agent.AgentLoginState

/**
 * CLI 会员额度大脑适配器 (C3-D): a logged-in device CLI's membership
 * quota as a brain.
 *
 * Identity: adapterId `cli-membership`, kind SUBSCRIPTION (it borrows a
 * login's quota — it is NOT an API call: no key, no baseUrl, no `/models`
 * catalogue). The engine only ever sees this [BrainAdapter] contract;
 * the CLI itself is reached through [CliRunner].
 *
 * Login state (audit section 4.2 — honest probe, no invented commands):
 * - `--version` proves nothing and is never consulted here.
 * - The only trusted signal is one real CLI call. [validateCredentials]
 *   runs exactly that: [CliRunner.PROBE_PROMPT] ("ok", one fixed
 *   read-only word) through the configured [BrainConfig.cliBinary]. It
 *   consumes one membership call by design, so it must ONLY be called
 *   when the user explicitly taps "验证一次" (C's settings UI owns that
 *   button; this adapter never polls, never probes at startup, and
 *   never retries on its own).
 * - The probe result is written back three-way via
 *   [BrainConfigStore.saveConfig]: loginState / lastTestedAtEpochSeconds /
 *   lastTestSummary. Success (exit 0) -> LOGGED_IN; an explicit
 *   not-logged-in error from the CLI -> NOT_LOGGED_IN; quota wording
 *   (which proves a login exists, only the quota is gone) -> LOGGED_IN
 *   with a quota summary, while the Result itself still fails (the brain
 *   cannot run); anything else (not installed, timeout, transport)
 *   -> UNKNOWN. A config without a binary cannot be probed at all and
 *   fails without a writeback (there is nothing to write back to).
 *
 * Tools: toolCalling = false, first stage. The engine already withholds
 * tools from such adapters (BrainEngine only offers tools when
 * capabilities.toolCalling is true), but this adapter also defends
 * itself: a request that carries tools is rejected with `unsupported`
 * before any CLI call, and a CLI tool-use event mid-stream is rejected
 * the same way instead of being emitted as a BrainEvent.ToolCall (the
 * engine would execute that). Nothing is silently dropped.
 *
 * Models: SUBSCRIPTION has no model catalogue ([listModels] returns an
 * empty list, per the [BrainAdapter] contract). The config's `model`
 * field carries the binary name as the seed value (see BrainBootstrap:
 * the validator requires a non-SIWC SUBSCRIPTION model to be non-empty,
 * the CLI brain has no model to name, and inventing one would be fake —
 * so `model` names the provider binary instead). This adapter never
 * sends `model` to the CLI: the CLI uses its own default model.
 *
 * Streaming: stage 1 emits the CLI's output lines as TextDelta events
 * only after the CLI process finishes ([CliRunner] is synchronous), so
 * this is buffered per-line emission, not live streaming. The
 * capabilities note says so; do not present it as live in UI copy.
 *
 * Real-CLI verification is still pending on the K70 (audit section 7):
 * the codebuddy/codex stream-json shapes are mapped only through the
 * already-known [wherevercode.agent.brain.StreamParser] shapes, and
 * unknown lines fall back to the raw tail. If the real CLI emits a shape
 * the parser does not know, the answer still surfaces (raw tail), but
 * per-line fidelity on-device is unverified until maintainer runs it.
 */
class CliMembershipBrainAdapter(
    private val configStore: BrainConfigStore,
    private val cliRunner: CliRunner,
    private val brainId: String = BRAIN_ID,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) : BrainAdapter {

    override val adapterId: String = ADAPTER_ID
    override val kind: BrainKind = BrainKind.SUBSCRIPTION
    override val label: String = "CLI 会员额度"

    override fun capabilities(): BrainCapabilities = BrainCapabilities(
        streaming = true,
        // Stage 1: no tool calling. Requests with tools and CLI tool-use
        // events are both rejected as unsupported (see chatStream).
        toolCalling = false,
        vision = false,
        maxContextTokens = null,
        notes = "借已登录 CLI 的会员额度作大脑；无 key、无 baseUrl、无模型目录。" +
            "第一阶段不支持工具调用；每次调用消耗一次会员额度。" +
            "文本增量按 CLI 输出行发出（首阶段在 CLI 进程结束后再按行发出，非逐行实时）。",
    )

    override suspend fun validateCredentials(): Result<Unit> {
        val config = configStore.getConfig(brainId)
            ?: return Result.failure(
                IllegalStateException("未配置 CLI 大脑：请先在设置里添加 CLI 大脑。"),
            )
        val binary = config.cliBinary?.takeIf { it.isNotBlank() }
            ?: return Result.failure(
                IllegalStateException("未填写 CLI 二进制名：请先选择提供大脑的已登录 CLI。"),
            )
        val outcome = withContext(Dispatchers.IO) {
            cliRunner.run(CliRunner.PROBE_PROMPT, binary, CliRunner.PROBE_TIMEOUT_SECONDS)
        }
        val (state, summary) = classifyProbe(binary, outcome)
        val updated = config.copy(
            loginState = state,
            lastTestedAtEpochSeconds = clock(),
            lastTestSummary = summary,
        )
        configStore.saveConfig(updated)
        return if (outcome is CliCallResult.Success) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(summary))
        }
    }

    /** SUBSCRIPTION has no model catalogue: empty list is honest, not a failure. */
    override suspend fun listModels(): Result<List<ModelInfo>> = Result.success(emptyList())

    override fun chatStream(request: ChatRequest): Flow<BrainEvent> = flow {
        if (request.tools.isNotEmpty()) {
            emit(
                BrainEvent.Error(
                    code = "unsupported",
                    message = "CLI 大脑第一阶段不支持工具调用：本次请求要求调用工具 " +
                        request.tools.joinToString("、") { it.name } + "，已拒绝，未执行任何工具。",
                    retryable = false,
                ),
            )
            emit(BrainEvent.Done("error"))
            return@flow
        }
        val binary = configStore.getConfig(brainId)?.cliBinary?.takeIf { it.isNotBlank() }
        if (binary == null) {
            emit(
                BrainEvent.Error(
                    code = "not_configured",
                    message = "未配置 CLI 大脑：请先在设置里选择提供大脑的已登录 CLI。",
                    retryable = false,
                ),
            )
            emit(BrainEvent.Done("error"))
            return@flow
        }
        val prompt = buildPrompt(request)
        val outcome = withContext(Dispatchers.IO) {
            cliRunner.run(prompt, binary, CliRunner.CHAT_TIMEOUT_SECONDS)
        }
        when (outcome) {
            is CliCallResult.Success -> {
                if (outcome.events.isEmpty()) {
                    // No line parsed into an event but output exists
                    // (framing-only lines): surface the raw tail as text
                    // rather than returning an empty turn.
                    if (outcome.text.isNotBlank()) emit(BrainEvent.TextDelta(outcome.text))
                } else {
                    for (event in outcome.events) {
                        when (event) {
                            is BrainEvent.ToolCall -> {
                                emit(
                                    BrainEvent.Error(
                                        code = "unsupported",
                                        message = "CLI 大脑第一阶段不支持工具调用：CLI 要求调用工具 " +
                                            event.name + "，已拒绝，未执行。",
                                        retryable = false,
                                    ),
                                )
                                emit(BrainEvent.Done("error"))
                                return@flow
                            }
                            is BrainEvent.Error -> {
                                emit(event)
                                emit(BrainEvent.Done("error"))
                                return@flow
                            }
                            // Per-line Done is CLI framing; the adapter
                            // emits its own Done at the end.
                            is BrainEvent.Done -> Unit
                            is BrainEvent.ToolResult -> Unit
                            else -> emit(event)
                        }
                    }
                }
                emit(BrainEvent.Done("stop"))
            }
            is CliCallResult.Failure -> {
                emit(
                    BrainEvent.Error(
                        code = mapFailureCode(outcome.kind),
                        message = outcome.summary,
                        retryable = outcome.kind == CliFailureKind.TRANSPORT ||
                            outcome.kind == CliFailureKind.TIMEOUT,
                    ),
                )
                emit(BrainEvent.Done("error"))
            }
        }
    }

    /**
     * Probe classification (audit section 4.2, three-way writeback).
     * Quota wording proves a login exists — the state is LOGGED_IN with
     * a quota summary — while validateCredentials still fails, because
     * the brain cannot run on an exhausted quota.
     */
    private fun classifyProbe(binary: String, outcome: CliCallResult): Pair<AgentLoginState, String> =
        when (outcome) {
            is CliCallResult.Success -> AgentLoginState.LOGGED_IN to
                "验证成功：$binary 一次真实调用完成（exit=0），登录已确认。"
            is CliCallResult.Failure -> when (outcome.kind) {
                CliFailureKind.AUTH -> AgentLoginState.NOT_LOGGED_IN to
                    "未登录：$binary 明确报告未登录，请先在该 CLI 里完成登录，再点“验证一次”。"
                CliFailureKind.QUOTA -> AgentLoginState.LOGGED_IN to
                    "已登录，但会员额度不足/受限，本次验证未完成推理：${outcome.summary}"
                CliFailureKind.TIMEOUT -> AgentLoginState.UNKNOWN to
                    "验证超时（>${CliRunner.PROBE_TIMEOUT_SECONDS}s）：登录状态仍未验证，请稍后重试。"
                CliFailureKind.NOT_INSTALLED -> AgentLoginState.UNKNOWN to
                    "${outcome.summary}登录状态仍未验证。"
                CliFailureKind.UNSUPPORTED_BINARY -> AgentLoginState.UNKNOWN to
                    "${outcome.summary}登录状态仍未验证。"
                CliFailureKind.TRANSPORT -> AgentLoginState.UNKNOWN to
                    "验证未完成：${outcome.summary}"
            }
        }

    private fun mapFailureCode(kind: CliFailureKind): String = when (kind) {
        CliFailureKind.NOT_INSTALLED -> "not_configured"
        CliFailureKind.AUTH -> "auth_failed"
        CliFailureKind.QUOTA -> "quota_exceeded"
        CliFailureKind.TRANSPORT -> "transport_error"
        CliFailureKind.TIMEOUT -> "transport_error"
        CliFailureKind.UNSUPPORTED_BINARY -> "unsupported"
    }

    /**
     * One prompt for the CLI: the conversation as plain lines (user lines
     * as-is, assistant history prefixed, system guidance as plain lines —
     * the CLI has no system role in stage 1). The config model is never
     * sent: it names the provider binary (see the class doc), not a model
     * the CLI accepts as a parameter.
     */
    private fun buildPrompt(request: ChatRequest): String {
        val lines = StringBuilder()
        for (message in request.messages) {
            when (message.role) {
                "system" -> lines.append(message.content).append("\n")
                "assistant" -> lines.append("助手：").append(message.content).append("\n")
                "tool" -> lines.append("工具${message.name ?: ""}执行结果：")
                    .append(message.content).append("\n")
                else -> lines.append(message.content).append("\n")
            }
        }
        return lines.toString().trim().ifBlank { CliRunner.PROBE_PROMPT }
    }

    companion object {
        /** Stable M2 adapter id and brain id for the CLI membership brain. */
        const val ADAPTER_ID: String = "cli-membership"

        /** BrainConfig.brainId for the CLI membership brain. */
        const val BRAIN_ID: String = "cli-membership"
    }
}
