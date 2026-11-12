package wherevercode.agent.brain

import wherevercode.agent.brain.generic.GenericOpenAiBrainAdapter
import wherevercode.agent.brain.siwc.BrainBootstrap
import wherevercode.agent.core.agent.AgentKind
import wherevercode.agent.core.agent.AgentRecord
import wherevercode.agent.core.agent.ManusTaskAdapter

/**
 * 阶段 A（V5 紧急开发，2026-11-10）：聊天按 Agent 路由的纯决策。
 *
 * 背景：选了云端 Agent 再发“在吗”，消息根本没发给那个 Agent——
 * 它发给了全局大脑 ChatGPT（SIWC），其订阅额度耗尽，OpenAI 原样
 * 返回 "The ChatGPT user has reached their Subscription Sharing usage
 * limit"。根因是架构性缺失：选 Agent 只换会话标签，发送路径永远
 * 只认全局大脑（见审计报告 §一）。
 *
 * 本对象只做决策，不碰 Android、不读 Keystore、不发网络：
 * 调用方传入已有的 [BrainConfig] 列表与 [isBrainConfigured] 判定，
 * 拿到的要么是明确的大脑 id（经 [BrainEngine.chat] 的
 * `brainIdOverride` 接缝调用），要么是 [NotRoutable]——此时消息
 * 绝不发出，也绝不静默回落到任何大脑（尤其不回落 ChatGPT）。
 */
sealed class AgentChatRoute {
    /** 未选 Agent：走现有全局大脑路径，原样不变。 */
    object UseGlobalBrain : AgentChatRoute()

    /**
     * 本轮改走 [brainId] 对应大脑（[brainLabel] 是该大脑的配置名，
     * 仅作文案/归属用）。调用方必须同时关掉本轮的跨大脑 failover，
     * 否则一次可重试失败仍会悄悄切到另一个大脑。
     */
    data class UseBrain(val brainId: String, val brainLabel: String) : AgentChatRoute()

    /** 远程设备 Agent：消息经 Hand 直连发往该设备上的 Agent（阶段 B）。 */
    data class UseRemoteAgent(val agent: AgentRecord) : AgentChatRoute()

    /**
     * Manus 云端任务型 Agent：消息走 Manus 聊天桥接
     * （[wherevercode.agent.core.agent.ManusChatBridge]：聊天消息 →
     * 建 Manus 任务 → 轮询 → 结果回填，阶段 C）。
     * 仍走同一套通用路由/会话/历史，不另做 Manus 专属流程或 UI。
     */
    data class UseManus(val agent: AgentRecord) : AgentChatRoute()

    /**
     * 平台 Agent：消息经平台频道发往该 Agent（
     * [wherevercode.agent.core.agent.PlatformAgentChatBridge]：正文
     * 带 `@<平台 Agent ID> ` 收件方前缀 → 平台频道 → 轮询收回复）。
     * 只发任务消息，不传代码、不推代码。
     */
    data class UsePlatformAgent(val agent: AgentRecord) : AgentChatRoute()

    /**
     * 不要发送。[message] 是可直接展示给用户的中文原因；
     * 没有任何消息被发出，也没有任何大脑被调用。
     */
    data class NotRoutable(val message: String) : AgentChatRoute()
}

object AgentChatRouter {

    private const val SUBSCRIPTION_ADAPTER_ID = "subscription"

    /** 本轮只支持的已验证 CLI 二进制（与 AgentRunner 的已验证调用一致）。 */
    private val VERIFIED_CLI_BINARIES = setOf("codebuddy", "codex")

    /**
     * 决定一条已选 Agent 的消息该走哪里。
     *
     * @param agent null = 未选 Agent，走全局大脑。
     * @param configs [BrainConfigStore.listConfigs] 的全部大脑配置。
     * @param isBrainConfigured 与 [BrainBootstrap.BrainComponents.isBrainConfigured]
     *   同口径的判定（Key/登录/模型/地址是否齐全）。
     */
    fun resolve(
        agent: AgentRecord?,
        configs: List<BrainConfig>,
        isBrainConfigured: (String) -> Boolean,
        /**
         * 阶段 B：该 Agent 是否已接入远端（设备已配对且在线可达）。
         * 调用方（MainActivity/ChatViewModel）按 PairingManager 的
         * CONFIRMED 配对与实际连通判定；默认 false（未接入）。
         */
        isRemoteAgent: (AgentRecord) -> Boolean = { false },
        /**
         * 阶段 C：该 Manus Agent 是否已配置 API Key（调用方按
         * BrainKeyStore 里 authRef 有无值判定；默认 false，
         * 未配置时 NotRoutable，绝不建任务、绝不计费）。
         */
        isManusConfigured: (AgentRecord) -> Boolean = { false },
        /**
         * 平台 Agent：App 的平台凭证是否已配置（调用方按 Keystore
         * 里平台凭证有无值判定；默认 false，未配置时 NotRoutable，
         * 绝不发消息、绝不回落大脑）。
         */
        isPlatformAgentConfigured: (AgentRecord) -> Boolean = { false },
    ): AgentChatRoute {
        if (agent == null) return AgentChatRoute.UseGlobalBrain
        // 平台 Agent（V5，2026-11-11）：走平台频道，不走任何大脑、
        // 不走 Hand、不走模型 API。未配平台凭证时诚实拒绝。
        if (wherevercode.agent.core.agent.PlatformAgent.isPlatformAgentRecord(agent)) {
            return if (isPlatformAgentConfigured(agent)) {
                AgentChatRoute.UsePlatformAgent(agent)
            } else {
                AgentChatRoute.NotRoutable(
                    "平台 Agent 凭证未配置：请先在你的平台服务中创建 Agent，" +
                        "再在平台 Agent 表单的平台凭证栏填入它的凭证（只存本机 Keystore），再发送。本次消息未发出。",
                )
            }
        }
        // 云端 Agent（V5，2026-11-11）：对方把 App 加为自定义 MCP
        // 连接器、自己来领单，不走 App 内聊天；MCP 服务端尚未开通，
        // 现在既不能聊天也不能领单，诚实拒绝、不回落大脑。
        if (wherevercode.agent.core.agent.CloudAgent.isCloudAgentRecord(agent)) {
            return AgentChatRoute.NotRoutable(
                "云端 Agent 不走 App 内聊天：它要把 App 加为自定义 MCP 连接器、" +
                    "自己来领单；且 App 的 MCP 端点尚未开通（手机没有公网地址），" +
                    "现在还领不了单。请先按 Agent 页的设置说明准备，端点开通后再用。" +
                    "本次消息未发出。",
            )
        }
        // 阶段 C：Manus 走聊天桥接（任务型 API 转聊天消息），不再是
        // 阶段 A 的 NotRoutable。旧记录靠主机识别（adapterId 仍是
        // deepseek、model 仍是 "Manus 2.0 Lite" 的也算 Manus，
        // 见 ManusTaskAdapter.isManusRecord），绝不能把它当 DeepSeek
        // 大脑调。未配 Key 时仍诚实拒绝，不建任务、不计费。
        if (ManusTaskAdapter.isManusRecord(agent)) {
            return if (isManusConfigured(agent)) {
                AgentChatRoute.UseManus(agent)
            } else {
                AgentChatRoute.NotRoutable(
                    "Manus API Key 未配置：请先在 Agent 页填写 Manus API Key" +
                        "（只存本机 Keystore），再发送。本次不建任务、不计费。",
                )
            }
        }
        // 阶段 B：归属远端设备的 Agent 优先走 Hand 直连，绝不落到
        // 本机 CLI 或模型 API（否则消息会发给错误的执行方）。
        if (agent.deviceId != null) {
            return if (isRemoteAgent(agent)) {
                AgentChatRoute.UseRemoteAgent(agent)
            } else {
                AgentChatRoute.NotRoutable(
                    "该远程设备上的 Agent 未接入：设备未配对或未连接，消息未发出。",
                )
            }
        }
        if (isRemoteAgent(agent)) {
            return AgentChatRoute.NotRoutable(
                "该远程设备上的 Agent 未接入：未填写设备信息，请先在设备管理里添加远程设备。",
            )
        }
        // 远程设备 Agent：通道是阶段 B 的活，本阶段没有可发的路。
        if (agent.kind == AgentKind.DEVICE && agent.brainKind != BrainKind.AGENT_RUNTIME) {
            return AgentChatRoute.NotRoutable(
                "该设备上的 Agent 未接入：远程通道在阶段 B 接线，当前无法发送，" +
                    "消息未发出。",
            )
        }
        if (agent.brainKind == BrainKind.AGENT_RUNTIME) return resolveCli(agent, configs, isBrainConfigured)
        return resolveModelApi(agent, configs, isBrainConfigured)
    }

    private fun resolveCli(
        agent: AgentRecord,
        configs: List<BrainConfig>,
        isBrainConfigured: (String) -> Boolean,
    ): AgentChatRoute {
        val binary = agent.binary?.trim()?.takeIf { it.isNotBlank() }
            ?: agent.adapterId.trim().takeIf { it.isNotBlank() }
            ?: return AgentChatRoute.NotRoutable(
                "该 Agent 未填 CLI 二进制名：请先在 Agent 页补填（第一阶段只支持 codebuddy / codex）。",
            )
        if (binary !in VERIFIED_CLI_BINARIES) {
            return AgentChatRoute.NotRoutable(
                "该 CLI（$binary）暂无已验证的调用方式：第一阶段只支持 codebuddy / codex " +
                    "的已验证调用，不发明其他 CLI 的调用。",
            )
        }
        val cliConfig = configs.firstOrNull { it.brainId == BrainBootstrap.CLI_BRAIN_ID }
            ?: return AgentChatRoute.NotRoutable(
                "CLI 会员大脑未配置：请先在设置里添加 CLI 会员大脑，再选它对话。",
            )
        // CLI 会员大脑全局只接一个二进制：接的不是选的那个时绝不代跑另一个。
        val configuredBinary = cliConfig.cliBinary?.trim()?.takeIf { it.isNotBlank() }
        if (configuredBinary != null && configuredBinary != binary) {
            return AgentChatRoute.NotRoutable(
                "CLI 会员大脑当前接的是 $configuredBinary，不是 $binary：请先在设置里" +
                    "切换 CLI 并点“验证一次”，再选它对话。",
            )
        }
        if (!isBrainConfigured(BrainBootstrap.CLI_BRAIN_ID)) {
            return AgentChatRoute.NotRoutable(
                "该 CLI Agent 未验证登录：请先在设置里点“验证一次”（会消耗一次会员调用），" +
                    "验证通过后再发送。",
            )
        }
        return AgentChatRoute.UseBrain(BrainBootstrap.CLI_BRAIN_ID, cliConfig.label)
    }

    private fun resolveModelApi(
        agent: AgentRecord,
        configs: List<BrainConfig>,
        isBrainConfigured: (String) -> Boolean,
    ): AgentChatRoute {
        val candidate = findBrainConfig(agent, configs)
            ?: return AgentChatRoute.NotRoutable(
                when {
                    agent.adapterId.equals(SUBSCRIPTION_ADAPTER_ID, ignoreCase = true) ||
                        agent.adapterId.equals(BrainBootstrap.SIWC_BRAIN_ID, ignoreCase = true) ||
                        agent.adapterId.equals(BrainBootstrap.DEEPSEEK_BRAIN_ID, ignoreCase = true) ||
                        agent.adapterId.equals(BrainBootstrap.CLOUDFLARE_BRAIN_ID, ignoreCase = true) ->
                        "该 Agent 对应的大脑尚未配置：请先在设置里完成配置（服务地址/模型/Key 或登录），再选它对话。"
                    else ->
                        "该 Agent 的适配器（${agent.adapterId}）未接入：没有对应的大脑配置，" +
                            "消息未发出；请先在设置里添加对应的大脑，或改选已接入的 Agent。"
                },
            )
        if (!isBrainConfigured(candidate.brainId)) {
            return AgentChatRoute.NotRoutable(
                "该 Agent 对应的大脑（${candidate.label}）未配置或未登录：" +
                    "请先在设置里完成配置再发送。",
            )
        }
        // 地址/模型必须与大脑配置一致：不一致时绝不悄悄调另一个地址/模型。
        val agentBaseUrl = agent.baseUrl?.trim()?.takeIf { it.isNotBlank() }
        val configBaseUrl = candidate.baseUrl?.trim()?.takeIf { it.isNotBlank() }
        if (agentBaseUrl != null && configBaseUrl != null &&
            normalizeBaseUrl(agentBaseUrl) != normalizeBaseUrl(configBaseUrl)
        ) {
            return AgentChatRoute.NotRoutable(
                "该 Agent 的服务地址（$agentBaseUrl）与已配置的大脑（${candidate.label}，" +
                    "$configBaseUrl）不一致：请先在设置里核对，确认一致后再发送。",
            )
        }
        val agentModel = agent.model?.trim()?.takeIf { it.isNotBlank() }
        val configModel = candidate.model.trim().takeIf { it.isNotBlank() }
        if (agentModel != null && configModel != null && agentModel != configModel) {
            return AgentChatRoute.NotRoutable(
                "该 Agent 的模型（$agentModel）与已配置的大脑（${candidate.label}，" +
                    "$configModel）不一致：请先在设置里核对，确认一致后再发送。",
            )
        }
        return AgentChatRoute.UseBrain(candidate.brainId, candidate.label)
    }

    /**
     * Agent 记录 → 大脑配置。
     *
     * 1. adapterId 直配（deepseek / siwc-chatgpt / cloudflare-workers-ai /
     *    cli-membership 等各有唯一配置；通用适配器 id "model-api-generic"
     *    是多条配置共用的，按名字、再按地址+模型消歧）。
     * 2. adapterId "subscription" 是“订阅授权”的占位写法，指 SIWC ChatGPT。
     * 3. 未知 adapterId 时再试通用大脑：名字相同，或地址+模型相同，才算
     *    同一个（只剩一条通用配置也不默认认领，防 Grok 走到 OpenRouter）。
     */
    private fun findBrainConfig(agent: AgentRecord, configs: List<BrainConfig>): BrainConfig? {
        val direct = configs.filter { it.adapterId.equals(agent.adapterId, ignoreCase = true) }
        if (direct.size == 1) return direct.single()
        if (direct.size > 1) {
            // 端点（地址+模型）先于名字：名字会重（中文名 slug 后也常重），
            // 端点才是服务身份；名字相同的两个大脑只按名字分不出来。
            direct.firstOrNull { matchesEndpoint(it, agent) }?.let { return it }
            direct.firstOrNull { it.label == agent.displayName }?.let { return it }
            return null
        }
        if (agent.adapterId.equals(SUBSCRIPTION_ADAPTER_ID, ignoreCase = true)) {
            configs.firstOrNull { it.brainId == BrainBootstrap.SIWC_BRAIN_ID }?.let { return it }
        }
        if (agent.brainKind == BrainKind.MODEL_API) {
            val generic = configs.filter { it.adapterId == GenericOpenAiBrainAdapter.ADAPTER_ID }
            generic.firstOrNull { matchesEndpoint(it, agent) }?.let { return it }
            generic.firstOrNull { it.label == agent.displayName }?.let { return it }
        }
        return null
    }

    private fun matchesEndpoint(config: BrainConfig, agent: AgentRecord): Boolean {
        val agentBaseUrl = agent.baseUrl?.trim()?.takeIf { it.isNotBlank() } ?: return false
        val configBaseUrl = config.baseUrl?.trim()?.takeIf { it.isNotBlank() } ?: return false
        val agentModel = agent.model?.trim()?.takeIf { it.isNotBlank() } ?: return false
        val configModel = config.model.trim().takeIf { it.isNotBlank() } ?: return false
        return normalizeBaseUrl(agentBaseUrl) == normalizeBaseUrl(configBaseUrl) &&
            agentModel == configModel
    }

    /** 地址只做无害归一化（去空白、去尾部斜杠），不改 host 与 path。 */
    private fun normalizeBaseUrl(url: String): String = url.trim().trimEnd('/')

    /**
     * 聊天顶部的“当前设备”：只说已核实的位置，不编在线态。
     * 云端 Agent 不在手机上；本机 CLI 在手机上；远程设备未接入。
     */
    fun deviceLabel(agent: AgentRecord): String = when (agent.kind) {
        AgentKind.CLOUD -> "云端"
        AgentKind.LOCAL_MODEL -> "本机（本地模型）"
        AgentKind.DEVICE ->
            if (agent.deviceId != null) "远程设备"
            else if (agent.brainKind == BrainKind.AGENT_RUNTIME) "本机（CLI）"
            else "远程设备（未接入）"
    }

    /** 打开 Agent 会话时的提示：必须与实际路由一致，不写“消息走大脑”。 */
    fun describeRoute(agent: AgentRecord, route: AgentChatRoute): String = when (route) {
        is AgentChatRoute.UseGlobalBrain ->
            "与 ${agent.displayName} 的独立会话：消息走全局大脑。"
        is AgentChatRoute.UseRemoteAgent ->
            "与 ${agent.displayName} 的独立会话：消息经 Hand 直连发往远程设备上的 Agent（${deviceLabel(agent)}）。"
        is AgentChatRoute.UseManus ->
            "与 ${agent.displayName} 的独立会话：消息走 Manus 任务通道（${deviceLabel(agent)}），" +
                "每次发送创建一个 Manus 任务或跟进同一任务。"
        is AgentChatRoute.UsePlatformAgent ->
            "与 ${agent.displayName} 的独立会话：消息经平台频道发往平台上的 Agent（${deviceLabel(agent)}），" +
                "对方按自己的节奏回复；只发任务消息，不传代码。"
        is AgentChatRoute.UseBrain ->
            "与 ${agent.displayName} 的独立会话：消息走${route.brainLabel}（${deviceLabel(agent)}）。"
        is AgentChatRoute.NotRoutable ->
            "与 ${agent.displayName} 的独立会话：${route.message}"
    }
}
