package wherevercode.agent.brain.cloudflare

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import wherevercode.agent.brain.siwc.BrainCapabilities
import wherevercode.agent.brain.siwc.BrainError
import wherevercode.agent.brain.siwc.BrainMessage
import wherevercode.agent.brain.siwc.BrainProvider
import wherevercode.agent.brain.siwc.BrainResponse
import wherevercode.agent.brain.siwc.BrainRequest
import wherevercode.agent.brain.siwc.BrainRole
import wherevercode.agent.brain.siwc.BrainStreamEvent
import wherevercode.agent.brain.siwc.BrainUsage

/**
 * Cloudflare Workers AI BrainAdapter — REAL implementation.
 *
 * 把 Cloudflare Workers AI（免费层）接成一个可切换的 [BrainProvider]，
 * 与 SIWC 平行：SIWC 仍在，两个大脑都能选，引擎不认厂商分支。
 *
 * Flow：
 * 1. 配置来自调用方（[BrainBootstrap] 从 BrainConfig 读）：Account ID、
 *    baseUrl、模型。Token 来自 [CloudflareCredentialStore]（用户本人在
 *    设备端输入，存 Keystore）。三者缺一不可，缺失即 [BrainError.NotConfigured]，
 *    绝不猜、绝不回退到写死值。
 * 2. [CloudflareApiClient.streamResponse] POST 到 Cloudflare 官方
 *    OpenAI 兼容端点 `{baseUrl}/{account_id}/ai/v1/chat/completions`
 *    （Bearer Token），`stream: true`。2026-11-09 查官方文档后切过
 *    来（P1-2）：原生 `/ai/run` 的输入消息是 RoleScopedChatInput，只有
 *    role/content/name，没有 tool_call_id / tool_calls 字段，响应
 *    tool_calls 也是无 id 的 `[{name, arguments}]`，协议层无法配对；
 *    OpenAI 兼容端点用标准 chat completions 格式，assistant 发
 *    `tool_calls[]`（含 id）、tool 消息发 `tool_call_id`，与 DeepSeek
 *    路径同形。旧原生端点代码已删除，不留双路。
 * 3. SSE 事件译为 [BrainStreamEvent]：TextDelta -> Delta；
 *    ToolCallDone -> ToolCall（id 现在是服务端真实发的 id，不再是
 *    客户端自造的 `cf-call-N`；只有服务端某 chunk 缺 id 时才回落
 *    自造）；Completed -> Completed（usage = 服务器返回的数字）；
 *    Failed -> Failed（partial text 由调用方丢弃）。
 *
 * 模型（核查报告 §2，官方定价页 2026-10-20 版）：
 * - 首次配置的建议值是 [SUGGESTED_DEFAULT_MODEL]
 *   `@cf/meta/llama-3.1-8b-instruct-fp8-fast`：输入最便宜一档
 *   （4,119 Neurons/M 输入，34,868/M 输出；短问答约 1,107 次/天）。
 *   **必须用 `-fp8-fast` 完整 ID**：同权重不同量化价差达 6 倍，
 *   plain 版 `@cf/meta/llama-3.1-8b-instruct` 输入贵 6 倍，别误选。
 * - 该建议值只在首次建配置时写进用户可改的 BrainConfig.model；
 *   适配器在请求时绝不拿它当回退：配置的模型为空即报错。
 * - 用户可在设置里换成其他免费层模型，例如推理用
 *   `@cf/openai/gpt-oss-120b`（31,818/M 输入，68,182/M 输出；
 *   复杂规划约 26 次/天）或 `@cf/meta/llama-3.3-70b-instruct-fp8-fast`
 *   （26,668/M 输入，204,805/M 输出；复杂规划仅约 16 次/天）。
 * - GLM-5 系列、Kimi K2.6/K2.7、DeepSeek V4 系列不列入免费默认：
 *   官方文档中找不到完整付费清单，但第三方交叉印证它们需 Workers Paid，
 *   且 kimi-k2.6、deepseek-v4-pro-0813 官方已标注计划下架 2026-11-20。
 *   若用户执意配这些模型，服务器会以 403 / 5035 拒绝，本层如实报出。
 *
 * 额度（核查报告 §1）：
 * - 每天 10,000 Neurons，全账户共享；Free 计划超额只失败、不产生账单。
 * - 超额（429 / 3036，兼容第三方报告的 4006）译为 [BrainError.QuotaExceeded]，
 *   提示写“按官方说法每日 00:00 UTC 重置，个别情况下可能持续更久”，
 *   绝不写“00:00 UTC 一定恢复”；调用方不得高频重试（见桥接层注释）。
 * - 输出 token 比输入贵约 8 倍：不传 max_tokens 时用服务器默认值
 *   （llama 模型页称默认 256），输出长度默认即受限。
 * - 本增量不做本地 Neuron 计数 90% 停：usage 字段形状未经实测证实
 *   （见 CloudflareStreamParser 的 UNVERIFIED 清单），没有可靠数字
 *   就不做假计数；待 K70 实测确认 usage 字段后再加。
 *
 * 合规（核查报告 §3）：
 * - 单用户 BYO：用户自己的账号 + 自己的 Token，只服务自己——已读条款
 *   中无禁止性条文（注意：是“无禁止”，不是“明文许可”）。
 * - 绝不把一个账户的 Token/额度共享给多个用户，绝不转售
 *   （自助协议 §2.2.1(a)）；Token 只存用户自己的手机。
 * - 隐私：标准推理零保留、不用于训练（官方数据使用页确认）；但服务
 *   条款第 9 条有“为提供和改进服务而使用”的表述，与数据页存在措辞张力，
 *   本文件如实记录，不作“绝对不用于改进”的承诺。
 * - 地区：Workers AI 不在中国网络（JD Cloud）可用产品清单中；大陆直连
 *   api.cloudflare.com 的可达性官方未说明，K70 实测时须验证连通性。
 */
class CloudflareBrainAdapter(
    private val credentialStore: CloudflareCredentialStore,
    /**
     * 缺参修复 §7 第 5 条：SSE 畸形行回调，默认空实现（旧行为）。
     * 生产接线（BrainBootstrap）传入记入 EventLog 的回调，设备端可查
     * “解析器是否见过畸形行 / 截断”。须在 apiClient 之前声明：
     * 默认 apiClient 用它构造。显式传入 apiClient 时以该 client 为准，
     * 本回调不生效（测试注入 fake transport 走此路）。
     */
    private val onMalformedLine: (String) -> Unit = {},
    private val apiClient: CloudflareApiClient = CloudflareApiClient(onMalformedLine = onMalformedLine),
    /**
     * 以下三项由调用方（BrainBootstrap）从 BrainConfig 读出；本适配器
     * 不直接碰 SharedPreferences。返回 null / 空白 = 未配置。
     */
    private val accountIdProvider: () -> String? = { null },
    private val baseUrlProvider: () -> String? = { null },
    private val configuredModelProvider: () -> String? = { null },
) : BrainProvider {

    override val providerId: String = PROVIDER_ID

    override val displayName: String = "Cloudflare Workers AI"

    override val capabilities: BrainCapabilities = BrainCapabilities(
        supportsStreaming = true,
        // Workers AI 文本模型支持传统函数调用（官方函数调用页）；
        // 默认 8B 模型在真机上是否实际返回 tool_calls 未经实测，
        // 服务器拒绝时本层如实报 Unsupported，不静默降级。
        // P1 §7.4：supportsTools=true 只是声称值 ≠ 实测值，实测分级见
        // BrainCapabilities.toolCallingVerified（默认 null = 未验证）。
        supportsTools = true,
        // Workers AI 支持 system 角色（官方模型页示例即 system + user）。
        supportsSystemMessages = true,
        // 无状态 REST：每次带全量历史。
        requiresFullHistory = true,
        // temperature 0–5（官方模型页），max_tokens ≥ 1。
        supportsTemperature = true,
        supportsMaxOutputTokens = true,
        // API Token 计免费 Neuron 额度，不是订阅套餐。
        billsUserPlan = false,
        limitsNote = "免费层每天 10,000 Neurons，全账户共享；超额只失败、不计费。" +
            "按官方说法每日 00:00 UTC 重置，个别情况下可能持续更久。" +
            "模型须在设置里配置；默认建议 " + SUGGESTED_DEFAULT_MODEL + "。",
    )

    override fun isConfigured(): Boolean {
        return credentialStore.loadToken() != null &&
            !accountIdProvider().isNullOrBlank() &&
            !configuredModelProvider().isNullOrBlank()
    }

    override suspend fun infer(request: BrainRequest): BrainResponse {
        // Non-streaming path: collect the stream. Tool calls cannot be
        // represented in a BrainResponse, so a turn that calls tools is an
        // explicit Unsupported — callers that need tools must use streamInfer.
        val text = StringBuilder()
        var model = ""
        var usage: BrainUsage? = null
        streamInfer(request).collect { event ->
            when (event) {
                is BrainStreamEvent.Delta -> text.append(event.text)
                is BrainStreamEvent.ToolCall -> throw BrainError.Unsupported(
                    "infer() 不支持工具调用（本次回复要求调用工具 ${event.name}）；请用 streamInfer()。",
                )
                is BrainStreamEvent.Completed -> {
                    model = event.response.model
                    usage = event.response.usage
                }
                is BrainStreamEvent.Failed -> throw event.error
            }
        }
        return BrainResponse(
            text = text.toString(),
            model = model,
            usage = usage,
            billedToUserPlan = false,
        )
    }

    override fun streamInfer(request: BrainRequest): Flow<BrainStreamEvent> = flow {
        // Capability enforcement first: never silently drop these.
        if (request.temperature != null && (request.temperature < 0.0 || request.temperature > 5.0)) {
            emit(
                BrainStreamEvent.Failed(
                    BrainError.Unsupported(
                        "Cloudflare Workers AI 的 temperature 范围是 0–5，" +
                            "调用方请求了 temperature=${request.temperature}，已拒绝，未静默改写。",
                    ),
                ),
            )
            return@flow
        }
        if (request.maxOutputTokens != null && request.maxOutputTokens < 1) {
            emit(
                BrainStreamEvent.Failed(
                    BrainError.Unsupported(
                        "Cloudflare Workers AI 的 max_tokens 必须 ≥ 1，" +
                            "调用方请求了 max_tokens=${request.maxOutputTokens}，已拒绝，未静默改写。",
                    ),
                ),
            )
            return@flow
        }
        val apiToken = credentialStore.loadToken()
        if (apiToken.isNullOrBlank()) {
            emit(
                BrainStreamEvent.Failed(
                    BrainError.NotConfigured(
                        "未配置 Cloudflare API Token：请在控制台的 Cloudflare 设置里" +
                            "填写你自己的 API Token（Dashboard → Workers AI → Use REST API 创建）。",
                    ),
                ),
            )
            return@flow
        }
        val accountId = accountIdProvider()?.takeIf { it.isNotBlank() }
        if (accountId == null) {
            emit(
                BrainStreamEvent.Failed(
                    BrainError.NotConfigured(
                        "未配置 Cloudflare Account ID：请在控制台的 Cloudflare 设置里填写 Account ID。",
                    ),
                ),
            )
            return@flow
        }
        val baseUrl = baseUrlProvider()?.takeIf { it.isNotBlank() }
        if (baseUrl == null) {
            emit(
                BrainStreamEvent.Failed(
                    BrainError.NotConfigured("Cloudflare 大脑配置缺少 baseUrl，请检查大脑设置。"),
                ),
            )
            return@flow
        }
        // 模型解析顺序：调用方显式指定 > 配置的模型。配置为空即报错，
        // 绝不回退到任何写死模型——SUGGESTED_DEFAULT_MODEL 只是首次建配置时
        // 写进用户可改字段的建议值，不是请求时的回退。
        val model = request.model?.takeIf { it.isNotBlank() }
            ?: configuredModelProvider()?.takeIf { it.isNotBlank() }
        if (model == null) {
            emit(
                BrainStreamEvent.Failed(
                    BrainError.NotConfigured(
                        "未配置 Cloudflare 模型：请在控制台的 Cloudflare 设置里填写模型 ID，" +
                            "例如 " + SUGGESTED_DEFAULT_MODEL + "。",
                    ),
                ),
            )
            return@flow
        }
        val apiRequest = CloudflareApiRequest(
            model = model,
            messages = request.messages.map { message ->
                CloudflareInputMessage(
                    role = when (message.role) {
                        BrainRole.USER -> "user"
                        BrainRole.ASSISTANT -> "assistant"
                        // DEVELOPER 是系统级指引；Workers AI 没有 developer 角色，
                        // 发为 system（客户端的请求体构造器同样处理）。
                        BrainRole.DEVELOPER -> "developer"
                        // TOOL 是工具结果角色（assistant 回显在前，role="tool"
                        // 结果在后）；OpenAI 兼容端点用 tool_call_id 配对，
                        // 请求体构造器原样发出（P1-2）。
                        BrainRole.TOOL -> "tool"
                    },
                    content = message.content,
                    // P1-2：工具调用配对 id 全程透传，构造器不再丢掉它们。
                    // assistant 消息带 toolCalls，tool 消息带 toolCallId；
                    // 旧持久化里没有 id 的 tool 消息（toolCallId=null）由
                    // 请求体构造器降级为用户可见的结果说明，绝不发无 id
                    // 的 role="tool" 消息（兼容端点会整单拒绝）。
                    toolCallId = message.toolCallId,
                    toolCalls = message.toolCalls.map { call ->
                        CloudflareToolCall(
                            id = call.id,
                            name = call.name,
                            argumentsJson = call.argumentsJson,
                        )
                    },
                )
            },
            instructions = request.instructions,
            tools = request.tools.map { tool ->
                CloudflareTool(
                    name = tool.name,
                    description = tool.description,
                    parametersJson = tool.parametersJson,
                )
            },
            temperature = request.temperature,
            maxOutputTokens = request.maxOutputTokens,
        )
        try {
            collectCloudflareStream(apiRequest, accountId, apiToken, baseUrl)
        } catch (e: BrainError) {
            emit(BrainStreamEvent.Failed(e))
        }
    }

    /**
     * One Cloudflare attempt: stream events through, translating them to
     * [BrainStreamEvent]. HTTP/auth/quota failures arrive as [BrainError]
     * (thrown by the client before any event) and become Failed events;
     * in-stream failures arrive as [CloudflareStreamEvent.Failed].
     */
    private suspend fun FlowCollector<BrainStreamEvent>.collectCloudflareStream(
        apiRequest: CloudflareApiRequest,
        accountId: String,
        apiToken: String,
        baseUrl: String,
    ) {
        val fullText = StringBuilder()
        var toolCallIndex = 0
        apiClient.streamResponse(apiRequest, accountId, apiToken, baseUrl).collect { event ->
            when (event) {
                is CloudflareStreamEvent.TextDelta -> {
                    fullText.append(event.text)
                    emit(BrainStreamEvent.Delta(event.text))
                }
                is CloudflareStreamEvent.ToolCallDone -> {
                    toolCallIndex += 1
                    emit(
                        BrainStreamEvent.ToolCall(
                            id = event.callId ?: "cf-call-$toolCallIndex",
                            name = event.name,
                            argumentsJson = event.arguments,
                        ),
                    )
                }
                is CloudflareStreamEvent.Completed -> {
                    emit(
                        BrainStreamEvent.Completed(
                            BrainResponse(
                                text = event.fullText ?: fullText.toString(),
                                model = apiRequest.model,
                                usage = event.usage?.let { usage ->
                                    BrainUsage(
                                        inputTokens = usage.inputTokens,
                                        outputTokens = usage.outputTokens,
                                    )
                                },
                                billedToUserPlan = false,
                            ),
                        ),
                    )
                }
                is CloudflareStreamEvent.Failed -> {
                    emit(BrainStreamEvent.Failed(mapStreamFailure(event)))
                }
            }
        }
    }

    /**
     * Map a stream-level failure to a BrainError. Workers AI 兼容端点流没有
     * 带错误码的失败事件形状（官方未明说）；解析器只在“流被截断”时生成
     * Failed，一律按可重试的 Transport 处理，不猜错误码。
     */
    private fun mapStreamFailure(event: CloudflareStreamEvent.Failed): BrainError =
        BrainError.Transport(event.message)

    override suspend fun revoke() {
        // 只清本地 Token。Cloudflare API Token 的彻底作废须用户在
        // Dashboard → My Profile → API Tokens 里删除该 Token（删除需要
        // Token ID，本 App 不存 Token ID，无法代删）；清本地副本后，
        // isConfigured() 即为 false，本大脑不再可用。
        credentialStore.clearToken()
    }

    companion object {
        /** Stable provider id, e.g. "cloudflare-workers-ai". Never user-visible. */
        const val PROVIDER_ID: String = "cloudflare-workers-ai"

        /** Stable M2 adapter id and brain id for the Cloudflare brain. */
        const val ADAPTER_ID: String = "cloudflare-workers-ai"

        /** BrainConfig.brainId for the Cloudflare brain. */
        const val BRAIN_ID: String = "cloudflare-workers-ai"

        /** BrainKeyStore authRef holding the Cloudflare API Token. */
        const val AUTH_REF: String = "cloudflare:token"

        /** BrainConfig.extra key holding the Cloudflare Account ID (not a secret). */
        const val EXTRA_ACCOUNT_ID: String = "account_id"

        /** 官方 OpenAI 兼容端点的账号级前缀（chat completions 路径拼在后面）。 */
        const val ACCOUNTS_BASE_URL: String = "https://api.cloudflare.com/client/v4/accounts"

        /**
         * 首次配置的建议模型（官方定价页 2026-10-20 版：输入最便宜一档）。
         *
         * 这只是首次建配置时写进用户可改的 BrainConfig.model 的建议值：
         * 用户可在设置里随时改成别的模型；配置为空时适配器报错，绝不拿
         * 这个常量当请求时的回退。**必须用 `-fp8-fast` 完整 ID**：
         * 同权重不同量化价差达 6 倍，plain 版输入贵 6 倍。
         */
        const val SUGGESTED_DEFAULT_MODEL: String = "@cf/meta/llama-3.1-8b-instruct-fp8-fast"

        /**
         * 推理任务的建议模型（用户在设置里自选，非默认）：
         * `@cf/openai/gpt-oss-120b`（官方定价页：$0.35/$0.75 每百万 token；
         * 复杂规划约 26 次/天）。同样只是建议值，不写死为唯一选择。
         */
        const val SUGGESTED_REASONING_MODEL: String = "@cf/openai/gpt-oss-120b"
    }
}
