package wherevercode.agent.core.agent

import wherevercode.agent.brain.BrainConfigValidator
import wherevercode.agent.brain.BrainKind
import wherevercode.agent.core.team.MemberConnectionState
import wherevercode.agent.core.team.MemberRole

/**
 * Agent 记录（阶段 3，A1 P1）。
 *
 * 数据形状照抄旧项目 `agent_directory.py:28-130`（main `fd6b43d2`）的
 * AGENT_DIRECTORY 条目（审计 O1，映射表最重要的一行）：`kind` 区分
 * 设备/云端、`access` 区分 CLI/应用/连接器/Webhook/API/人工、
 * `capabilities` + `quota_rule` + `dispatchable` + `add_hint` 四字段
 * 正是卡片缺的能力/成本/可派单/添加指引；旧 `runner`（服务器端
 * Runner 类名）改为新 App 的适配器 id；旧 `binary_env` 不再单列，
 * 由 [binary] + 环境变量覆盖约定代替。
 *
 * 只移植字段形状与文案语义（旧 `quota_rule` 的两句原文见
 * [defaultQuotaRule]），不移植旧项目的任何执行代码；旧项目未达
 * 商用水平（maintainer 2026-11-09 12:53），本记录的校验与存储均为新写，
 * 见 [AgentStoreTest] 回归覆盖。
 *
 * 密钥规则与 BrainConfig 相同：配置里只存 [authRef]（引用名），
 * 密钥值一律走 [wherevercode.agent.brain.BrainKeyStore]，绝不进本记录。
 */
enum class AgentKind {
    /** 云端 Agent/服务（模型 API、云端 Agent 服务）。 */
    CLOUD,

    /** 另一台设备上的 Agent（需配对后才可用，见 A1 P2）。 */
    DEVICE,

    /** 本机模型（http://127.0.0.1 / http://localhost 的本地模型服务）。 */
    LOCAL_MODEL,
}

enum class AgentAccess {
    CLI,
    APP,
    CONNECTOR,
    WEBHOOK,
    API,
    MANUAL,
}

enum class AgentLoginState {
    UNKNOWN,
    LOGGED_IN,
    NOT_LOGGED_IN,
}

data class AgentRecord(
    val agentId: String,
    val displayName: String,
    /** 位置：云端 / 设备 / 本机模型。 */
    val kind: AgentKind,
    /** 接入方式：CLI / 应用 / 连接器 / Webhook / API / 人工。 */
    val access: AgentAccess,
    /** 授权形态：三类合一表单的选项（见 [brainKind] 说明）。 */
    val brainKind: BrainKind,
    /** 适配器 id（旧目录 `runner` 字段的新 App 对应物）。 */
    val adapterId: String,
    /** CLI 二进制名（access == CLI 时必填，如 "codebuddy"）。 */
    val binary: String? = null,
    /** 服务地址（access == API 时必填；订阅/人工接入必须为空）。 */
    val baseUrl: String? = null,
    /** 模型 id（模型 API 时必填）。 */
    val model: String? = null,
    /** 密钥引用名（不是密钥本身；值在 BrainKeyStore 里）。 */
    val authRef: String? = null,
    /** 自报能力，如 ["dev", "review"]；未知时为空，不编造。 */
    val capabilities: List<String> = emptyList(),
    /** 成本/额度一行字（见 [defaultQuotaRule] 的旧目录原文）。 */
    val quotaRule: String? = null,
    /** 是否可派单（旧目录 `dispatchable`；不可派单的 Agent 只对话不接任务）。 */
    val dispatchable: Boolean = true,
    /**
     * 开关（maintainer 2026-11-12 定：每个 Agent 都要有开/关，支持热插拔）。
     * 关掉只是停用，不是删除：停用的 Agent 不在选 Agent 里出现、
     * 不进频道、不接派活；列表里仍看得到（标已停用），随时可再打开。
     * 默认 true——老记录里没有该字段时按启用读，不因升级被误关。
     */
    val enabled: Boolean = true,
    /** 已知限制（旧目录 `known_limits`，如“只读工作区，不能写代码”）。 */
    val knownLimits: String? = null,
    /** 添加指引（旧目录 `add_hint`，如“去系统里安装后回 App 扫描”）。 */
    val addHint: String? = null,
    val loginState: AgentLoginState = AgentLoginState.UNKNOWN,
    val lastTestedAtEpochSeconds: Long? = null,
    /** 最近一次连接测试的结果摘要（已脱敏，不含密钥与正文）。 */
    val lastTestSummary: String? = null,
    /**
     * 成员镜像字段（V5 Phase B C3 第二阶段，成员框架）：只供目录/UI
     * 显示。权威在 TeamMemberStore（成员表 + 绑定表）；派发与授权
     * 永远不读这里。见 [wherevercode.agent.core.team.TeamMember.syncToAgentRecord]。
     */
    val memberRole: MemberRole? = null,
    val memberConnectionState: MemberConnectionState = MemberConnectionState.NOT_CONNECTED,
    /** 已授权项目 id 列表（沿用现有 projectId，不新造项目模型）。 */
    val authorizedProjectIds: List<String> = emptyList(),
    /** 成员最后一次状态更新时间。 */
    val lastMemberStateUpdatedAtEpochSeconds: Long? = null,
    /**
     * 阶段 B（V5 紧急开发）：远程设备 Agent 的归属设备 id（配对设备
     * 的 deviceId）与该设备上的工作文件夹；仅 kind == DEVICE 的
     * 远程 Agent 有值（本机 CLI 的 DEVICE 记录为 null）。deviceId
     * 非空 ⇒ 该 Agent 归属一台远端设备，聊天/分派走 Hand 直连，
     * 不再走本机 CLI 或模型 API。
     */
    val deviceId: String? = null,
    val deviceFolder: String? = null,
    /**
     * 平台 Agent 字段（V5，2026-11-11；只在 brainKind == PLATFORM_AGENT
     * 时有值，其他类型一律为 null/默认值）。
     * - [platformAgentId]：平台上的 Agent 标识，如 example-agent（用户填，
     *   收件方前缀与回复匹配都以它为准，见 [PlatformAgent]）。
     * - [platformHost]：平台地址主机名，如 example.invalid（默认）；
     *   端点由它拼出 `https://<host>/api/open/v1/mcp`。
     * - [platformChannelId]：收发消息的专用频道 id；为空即仍走共享
     *   “第一批”群（平台上目前没有 V5 专用频道，见 [PlatformAgent]）。
     * - [webhookUrl]：可选的 webhook 地址（只存地址，唤醒配置本身在
     *   平台上）；[webhookSecretRef] 是签名密钥的引用名，值在 Keystore。
     * - [directGithubRead]：该 Agent 能直接读 GitHub 审查完整代码。
     */
    val platformAgentId: String? = null,
    val platformHost: String? = null,
    val platformChannelId: String? = null,
    val webhookUrl: String? = null,
    val webhookSecretRef: String? = null,
    val directGithubRead: Boolean = false,
    /**
     * 云端 Agent 字段（V5，2026-11-11；只在 brainKind == CLOUD_AGENT
     * 时有值，其他类型一律为空/默认值）。
     * - [cloudRoles]：角色，dev/review/deliver 的子集（见 [CloudAgent]）。
     * - [canDispatch]：“允许它派活”开关：开着能给其他 Agent 派活，
     *   关掉只能领活、读、交结果。
     * - [cloudHomeDeviceId]：在哪干活的分组标注（归到某设备下时填该
     *   设备 id）；只做分组，永不参与路由判定（路由只看 [deviceId]）。
     */
    val cloudRoles: List<String> = emptyList(),
    val canDispatch: Boolean = true,
    val cloudHomeDeviceId: String? = null,
    val createdAtEpochSeconds: Long = 0,
    val updatedAtEpochSeconds: Long = 0,
) {
    companion object {
        /** id 形状：小写字母/数字开头，只含小写字母、数字、-、_。 */
        private val ID_REGEX = Regex("^[a-z0-9][a-z0-9_-]*$")

        /**
         * 远程设备 id 的形状：与 RemoteDeviceValidation 的规则同口径。
         * 服务端报的 device-id 原样采用（如小米 12 Hand 服务端的
         * `Android-x`，含大写），App 不能因为含大写或点就拒绝——
         * 否则这台设备上的 Agent 存不进去（saveAgent 保存时再验一次，
         * 会直接抛错）。两处规则改动时须同步改。
         */
        private val DEVICE_ID_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")

        /**
         * 旧目录 `quota_rule` 的原文（agent_directory.py:28-130）：
         * 云端按厂商额度，设备/本地不占云端额度。未知时调用方应显示
         * “未知”，不得留空或编数字；本函数只给新建记录的默认值。
         */
        fun defaultQuotaRule(kind: AgentKind): String = when (kind) {
            AgentKind.CLOUD -> "按厂商额度"
            AgentKind.DEVICE, AgentKind.LOCAL_MODEL -> "设备本地，不占云端额度"
        }

        /** 本机模型地址判定：委托给 [BrainConfigValidator]，只留一份规则。 */
        fun isLocalModelUrl(url: String): Boolean = BrainConfigValidator.isLocalModelUrl(url)
    }

    /** 校验。返回可读错误列表；空 = 合法。 */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (agentId.isBlank()) errors += "Agent id 不能为空"
        else if (!ID_REGEX.matches(agentId)) errors += "Agent id 只能用小写字母、数字、-、_，且以字母或数字开头"
        if (displayName.isBlank()) errors += "Agent 名字不能为空"
        if (adapterId.isBlank()) errors += "适配器 id 不能为空"
        when (brainKind) {
            BrainKind.MODEL_API -> {
                val url = baseUrl
                if (url.isNullOrBlank()) {
                    errors += "模型 API 需要填写服务地址 baseUrl"
                } else if (!BrainConfigValidator.isAllowedBaseUrl(url)) {
                    errors += "服务地址只允许 https（本地模型允许 http://127.0.0.1 或 http://localhost）：$url"
                }
                if (model.isNullOrBlank()) errors += "模型 API 需要填写模型"
            }
            BrainKind.SUBSCRIPTION -> {
                if (!baseUrl.isNullOrBlank()) errors += "订阅授权没有服务地址：请去掉 baseUrl，改选已登录的 CLI"
                if (authRef != null) errors += "订阅授权没有密钥输入框：请去掉 authRef，改用已登录 CLI 的会员额度"
            }
            BrainKind.AGENT_RUNTIME -> {
                if (binary.isNullOrBlank()) errors += "本机 Agent 需要填写 CLI 二进制名"
            }
            BrainKind.PLATFORM_AGENT -> {
                // 平台 Agent：既不是模型 API 也不是 CLI；只校验寻址字段。
                // authRef 必须为空（平台凭证是 App 级的，走 Keystore 固定
                // 引用名，不记在记录里）；binary/deviceId 必须为空。
                if (platformAgentId.isNullOrBlank()) errors += "平台 Agent 需要填写平台 Agent ID，如 example-agent"
                val host = platformHost
                PlatformAgent.platformHostIssue(host)?.let(errors::add)
                if (authRef != null) errors += "平台 Agent 没有密钥输入框：平台凭证是 App 级的，填在表单的平台凭证栏（只存本机 Keystore）"
                if (binary != null) errors += "平台 Agent 不是 CLI：请去掉 CLI 二进制名"
                if (deviceId != null) errors += "平台 Agent 不在任何设备上：请去掉设备归属"
                if (!platformChannelId.isNullOrBlank() && platformChannelId.any { it.isWhitespace() }) {
                    errors += "专用频道 ID 不能含空格"
                }
                val hook = webhookUrl
                if (hook != null && !hook.startsWith("https://") && !hook.startsWith("http://")) {
                    errors += "webhook 地址须以 https:// 或 http:// 开头"
                }
                val secretRef = webhookSecretRef
                if (secretRef != null && BrainConfigValidator.looksLikeSecretValue(secretRef)) {
                    errors += "webhook 签名密钥引用名不能是密钥本身"
                }
            }
            BrainKind.CLOUD_AGENT -> {
                // 云端 Agent：对方来连 App 领单；只校验角色与钥匙引用名。
                if (cloudRoles.isEmpty()) errors += "云端 Agent 至少要勾一个角色：开发、审查、递代码"
                val unknownRole = cloudRoles.firstOrNull { it !in CloudAgent.ALL_ROLES }
                if (unknownRole != null) errors += "未知角色：$unknownRole"
                if (authRef.isNullOrBlank()) errors += "云端 Agent 需要一个钥匙引用名（保存时自动生成并只存本机 Keystore）"
                if (binary != null) errors += "云端 Agent 不是 CLI：请去掉 CLI 二进制名"
                if (deviceId != null) errors += "云端 Agent 不在任何设备上：归到设备下只做分组，请用分组字段"
            }
        }
        if (access == AgentAccess.CLI && binary.isNullOrBlank()) {
            errors += "CLI 接入需要填写二进制名"
        }
        if (access == AgentAccess.API && baseUrl.isNullOrBlank()) {
            errors += "API 接入需要填写服务地址 baseUrl"
        }
        val ref = authRef
        if (ref != null) {
            if (ref.isBlank()) errors += "密钥引用名不能为空字符串"
            if (BrainConfigValidator.looksLikeSecretValue(ref)) {
                errors += "authRef 只能是引用名，不能是密钥本身"
            }
        }
        for (projectId in authorizedProjectIds) {
            if (projectId.isBlank()) errors += "授权项目 id 不能为空字符串"
        }
        val remoteDeviceId = deviceId
        if (remoteDeviceId != null) {
            if (remoteDeviceId.isBlank()) errors += "远程设备 id 不能为空字符串"
            else if (!DEVICE_ID_REGEX.matches(remoteDeviceId)) errors += "远程设备 id 只能用字母、数字、-、_、.，且以字母或数字开头"
        }
        val remoteFolder = deviceFolder
        if (remoteFolder != null && remoteFolder.isBlank()) errors += "远程设备文件夹不能为空字符串"
        return errors
    }
}
