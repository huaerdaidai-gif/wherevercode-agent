package wherevercode.agent.core.team

import wherevercode.agent.brain.BrainConfigValidator

/**
 * 团队成员模型（V5 Phase B C3 第二阶段，成员框架 W-codex-framework）。
 *
 * 这是跨设备连接的数据模型与控制面，不是执行器：成员本身不跑
 * 任务，跑任务的是成员背后的 Agent（设备上的 Codex/CodeBuddy CLI、
 * 云端 API、桌面 App 内置能力）。本文件只定义“成员是谁、在哪、
 * 怎么连、能碰哪些项目”，真实连接与派任务随最小闭环推进
 * （maintainer 07:44 解除 07:14“还是等你安排”；真实 Codex 连接是
 * 下一阶段，须 W-cli 运行器钩子 + Hand 桥接），框架内本身没有
 * 任何真实连接、真实派发、真实进程调用。
 *
 * 背景（maintainer 07:15–07:20，已核实的部分）：
 * - Codex CLI 在多个设备上已经有了：K70 的 Termux 里有 Codex
 *   （和 CodeBuddy），Mac 上有 Codex；小米 12 上有没有未核实。
 *   框架里没有任何安装、下载、部署 Codex/CodeBuddy 的逻辑与文案。
 *   没解决的是跨设备连接本身——配对、Hand、派发、回传、受控。
 * - 第一条真实跨设备闭环的目标环境是 Mac 或远程设备（remote-device）
 *   上的 Codex CLI，二者任一先通即可；K70 自身 Termux 里的 Codex
 *   是已知的本地执行端，但不是第一条闭环的目标。
 * - 约束（设计时必须体现，不要假设）：远程设备 经 Tailscale 的
 *   SSH 自 2026-11-06 晚起连不上，VPS 一侧的操作只能从 maintainer 自己
 *   的终端做，远程 Hand 走不走得通要先验证连通性；Mac 的局域网
 *   IP、Hand 形态、授权文件夹未知。成员记录按传输形态分别记连接
 *   状态，并留“先验证哪条通”的连通性核查位（设计 + 状态位，
 *   不真的去连）。
 * - 云端成员（Manus、LeChat 等）走云端 API，不走 Hand、不走 CLI
 *   发现；Manus 是任务型 API，不是聊天补全 API，不能塞进通用
 *   OpenAI 兼容适配器，以后须单独做云端适配器（本批不做）。
 *   免费额度优先（每周/每天重置的说法与具体数额待逐家按官方文档
 *   核实），额度耗尽明确报错、不静默切付费。
 * - Mac 端 Workbuddy 是桌面 App（macOS 12+），没有命令行、没有
 *   公开 API；它对外暴露什么接口（本地 API/服务、MCP、命令行伴侣、
 *   自动化接口等）待核实，不要假设、不要编造。框架里绝不把它写死
 *   成独立 CLI，也不建“WorkBuddy API”集成。
 *
 * 形态（[MemberKind]，三种成员 + 一种桌面 App，不只按设备 CLI 设计）：
 * - DEVICE_CLI：设备上的 CLI 成员（Codex / CodeBuddy 等），经
 *   Hand 连接；CodeBuddy 在 CliRunner 白名单里（codebuddy/codex），
 *   远程设备 的 CodeBuddy 与远程设备 的 Codex CLI 走同一路远程 Hand。
 * - CLOUD_CHAT：云端聊天型成员（LeChat），走 Chat Completions
 *   （OpenAI 兼容）经通用 OpenAI 兼容适配器（model-api-generic，
 *   W-adapter 已交付）；Le Chat 订阅与 La Plateforme API 是两个
 *   独立计费系统，不要混为一谈。Agents API（Beta）官方正用
 *   Agents SDK 取代、Durable agents 即将被废弃，本批不做适配器。
 * - CLOUD_TASK：云端任务型成员（Manus），任务型 API，不是聊天
 *   补全 API，不能塞进通用 OpenAI 兼容适配器，以后须单独做云端
 *   适配器（本批不做）。
 * - DESKTOP_APP：桌面 App 成员（Mac 端 Workbuddy）。已核实：没
 *   有官方可编程接口（无公开 API、无本地 API/HTTP 服务、无 MCP
 *   服务器暴露、无 AppleScript/快捷指令）；唯一受支持的驱动路径
 *   是内置 CodeBuddy CLI headless（-p），故记为“经内置 CodeBuddy
 *   CLI 的设备成员”，[driverAgentId] 为 "codebuddy-cli"，绝不写
 *   死成独立 CLI。社区逆向方案（workbuddy-mcp、WorkBuddy2API
 *   代理、WorkDaddy loopback CDP 注入）非官方、有账号/ToS 与脆弱
 *   性风险，未经 maintainer 明确决定不得采用（本框架无任何引用）。
 *   桌面 App 的办公技能（deck/文档/表格）不走 CLI——若 maintainer 要
 *   驱动办公技能，没有受支持的路径，等他给输入。
 * - Grok：会员路是社区实现（SuperGrok/X Premium+ 经 OAuth
 *   device-code 登录，走 cli-chat-proxy），非官方文档路线；xAI
 *   后端有 tier allowlist，已有登录成功后推理返 HTTP 403 的实测
 *   报告（登得上 ≠ 能推理），恢复后须在 maintainer 的真机/账号上实测
 *   才能定，实测前不列为已支持、不写进 UI。API key 路是官方的
 *   （api.x.ai），但是付费路，须 maintainer 明确许可才能用。Example Agent
 *   CLI（`grok` 命令，官方开源，headless -p）是候选 CLI 成员，
 *   但它在任何设备上是否已装均未核实，不要假设已装、不要装。
 * - GPT Bot：指什么尚未确认（群里的 GPT？自定义 GPT 机器人？），
 *   不要按任何一种猜测去设计；成员模型里不给它建占位成员。
 * - 小米 12 的 agy：设备上的 agent 身份待核实（是不是 Termux
 *   里的那个 agent 待 maintainer 回），见 [TeamMember.unverifiedDeviceMember]；
 *   不要假设 agy 就是老项目 agent_directory 里的 `agy` CLI。
 *
 * 老项目执行路径映射（复用清单 §17，三档，不编造）：
 * - A 档已核实（分派/回传设计的准绳）：Codex CLI（agent_directory
 *   `codex-cli` → CodexRunner :319 command / :367 parse_line，
 *   prompt 走 stdin）→ execute_task :311 结算 → artifact → _result
 *   回投；CodeBuddy CLI（CodeBuddyRunner :1219，prompt 在 argv
 *   里——移植时一律走 stdin/Hand 信封）；Agy（AgyRunner :1839，
 *   quota_fallback :1766、denial_retry :1779）；本地模型
 *   （LocalModelRunner :2164，只读）。四方法协议（command/
 *   environment/finalize/parse_line）与“退出码 + 有无 result 事件”
 *   双条件结算可作分派/回传的参照。
 * - B 档（Manus、Mistral/LeChat）：目录项存在，但消费该目录项的
 *   派发适配器未在本轮隔离出来——不宣称“老项目已跑通”。
 * - C 档（Workbuddy、Grok、GPT Bot）：本轮审查未找到对应目录项——
 *   不宣称老项目路径已找到。
 *
 * 独立性标注（maintainer 07:23–07:24 硬原则）：本框架无对旧平台服务
 * 器/账号/数据库的运行时依赖；移植内容是思路/协议（认领租约
 * 600 秒、attempt 绑定、交付记录 8 键、执行层 5 态、暂停独立布尔
 * 字段、四方法协议与双条件结算），代码全部新写；传输层（旧服务
 * 器中继）不可留，密码原语已换（V5 AES-256-GCM），prompt 不进
 * argv，事件失败不得静默丢。路线已定（maintainer 07:44）：V5 自主架构
 * 是主干，老项目是成熟技术参考，不把旧平台架构当作新 App 的基
 * 础；继续不直接复用第三方源码。本框架保持模块化（模型/存储/
 * 分派/回传/控制分离），以便先打通最小真实链路、再逐步扩展。
 * 连接诚实规则：
 * - 新成员一律 [MemberConnectionState.NOT_CONNECTED]，界面显示
 *   “未连接（等待安排）”；只有 Hand 配对层（后续批次）才能把状态
 *   置为 CONNECTED，且置 CONNECTED 要求 [shapeStatus] 已是 VERIFIED
 *   （见 [MemberManager.setConnectionState]）。
 * - 连通性核查（[connectivity]）只是“哪条路通”的记录位：
 *   NOT_CHECKED / LAN_* / REMOTE_* / CLOUD_*，由以后的 Hand 探测
 *   写回；一次核查通过不等于已连接，连接另须配对。
 * - 派发只认 [connectionState]（见 MemberDispatcher），与
 *   [connectivity]、发现结果、静态能力表一律无关。
 */

/** 成员在团队里的角色：开发成员 / 审查成员（通用，不写死厂商角色）。 */
enum class MemberRole {
    DEVELOPER,
    REVIEWER,
}

/** 成员形态：设备 CLI / 云端聊天型 / 云端任务型 / 桌面 App。 */
enum class MemberKind {
    /** 设备上的 CLI（如 Codex / CodeBuddy），经 Hand 连接。 */
    DEVICE_CLI,

    /**
     * 云端聊天型成员（如 LeChat）：走 Chat Completions（OpenAI
     * 兼容）经通用 OpenAI 兼容适配器；API-key 认证，不走 Hand。
     */
    CLOUD_CHAT,

    /**
     * 云端任务型成员（如 Manus）：任务型 API，不是聊天补全 API，
     * 不能塞进通用 OpenAI 兼容适配器；API-key 认证，不走 Hand。
     */
    CLOUD_TASK,

    /**
     * 桌面 App 成员（如 Mac 端 Workbuddy）。已核实无官方可编程接
     * 口；唯一受支持的驱动路径是内置 CodeBuddy CLI headless（-p），
     * 故 [TeamMember.driverAgentId] 为 "codebuddy-cli"；[MemberShapeStatus]
     * 在 Mac 上实测前恒为 UNVERIFIED。
     */
    DESKTOP_APP,
}

/** 传输形态：本机沙箱 / LAN Hand / 远程 Hand / 云端 API。 */
enum class MemberTransport {
    /** 本机 App 沙箱内的 CLI（AgentDiscovery 看得见的范围）。 */
    LOCAL_SANDBOX,

    /** 同一局域网内的 Hand（Mac 在同一 LAN 时的最短路径）。 */
    LAN,

    /** 远程 Hand（远程设备；走不走得通先验证连通性，不假设 SSH 可用）。 */
    REMOTE,

    /** 云端 API 直连（Manus、LeChat 等），不走 Hand。 */
    CLOUD_API,
}

/** 连接状态：默认未连接；CONNECTED 只能由以后的 Hand 配对层写入。 */
enum class MemberConnectionState {
    NOT_CONNECTED,
    CONNECTED,
    UNAVAILABLE,
}

/**
 * 连通性核查位：记录“先验证哪条通”的结果，不等于连接状态。
 * NOT_CHECKED = 还没查过；PENDING = 正在查（以后 Hand 探测用）；
 * 其余按形态分别记可达/不可达。框架本身不发任何探测包。
 */
enum class MemberConnectivity {
    NOT_CHECKED,
    PENDING,
    LAN_REACHABLE,
    LAN_UNREACHABLE,
    REMOTE_REACHABLE,
    REMOTE_UNREACHABLE,
    CLOUD_REACHABLE,
    CLOUD_UNREACHABLE,
}

/** 形态核实状态：UNVERIFIED = 形态/接法待核实，不可据此派发。 */
enum class MemberShapeStatus {
    UNVERIFIED,
    VERIFIED,
}

/**
 * 免费额度状态位（maintainer 07:20 规则）：优先走免费额度；额度耗尽时
 * 明确报错，不静默切付费。UNKNOWN = 未核实（默认）；FREE_AVAILABLE
 * = 免费额度可用；FREE_EXHAUSTED = 免费额度已耗尽（分派拒绝，
 * 见 MemberDispatcher）；PAID_ONLY = 只有付费路（须 maintainer 明确
 * 许可才能用，如 Grok API key 路）。数额绝不在代码里写死——
 * Manus 免费数额须 maintainer 在控制台核对，Mistral 免费数额未公开、
 * 唯一可靠数字是 maintainer 自己账号的 Limits 页。
 */
enum class MemberQuotaState {
    UNKNOWN,
    FREE_AVAILABLE,
    FREE_EXHAUSTED,
    PAID_ONLY,
}

/** 成员认证形态：CLI 内会员登录 / API-key 引用 / 配对设备 / 无。 */
enum class MemberAuthKind {
    /** CLI 自己的会员登录（如已登录的 Codex/CodeBuddy CLI）。 */
    CLI_LOGIN,

    /** API-key 认证：本记录只存 Keystore 引用名，Key 值绝不进记录。 */
    API_KEY_REF,

    /** 已配对设备（Hand 配对记录为准），无密钥。 */
    PAIRED_DEVICE,

    /** 无认证（桌面 App 等接口待核实形态在核实前只能是 NONE）。 */
    NONE,
}

/**
 * 一个团队成员。
 *
 * [agentId] 关联 [wherevercode.agent.core.agent.AgentRecord.agentId]
 *（如 "codex-cli"）；[endpointDeviceId] 是配对设备 id（如配对记录
 * 的 deviceId），[endpointLabel] 是给人看的端点名（如 "Mac"、
 * "远程设备"、"小米 12"），两者都只是标识，不含 IP、不含密钥、
 * 不含路径——Mac 的局域网 IP、Hand 形态、授权文件夹未知，在核实
 * 前一律留空，不编造。
 *
 * [endpointNote] 只接受在设备上由发现确定的备注（如 Workbuddy 在
 * Mac 上实际暴露的接口形态），调用方传入前须来自实测；框架不写
 * 死任何路径、地址或接口名。
 *
 * [quotaNote] 只存免费额度的文字备注（如“免费额度待核实”），绝
 * 不存未经核实的数字；额度耗尽时以后的云端适配器须明确报错、
 * 不静默切付费。
 */
data class TeamMember(
    val memberId: String,
    val agentId: String,
    val displayName: String,
    val role: MemberRole = MemberRole.DEVELOPER,
    val kind: MemberKind,
    val transport: MemberTransport,
    /** 配对设备 id（Hand 配对记录的 deviceId），未配对时为空。 */
    val endpointDeviceId: String? = null,
    /** 给人看的端点名（如 "Mac" / "远程设备"），不含 IP 与路径。 */
    val endpointLabel: String? = null,
    /** 仅由设备上发现确定的备注；框架不写死任何路径、地址、接口名。 */
    val endpointNote: String? = null,
    /** 云端成员的服务地址：调用方在核实官方文档后传入，框架不内置厂商地址。 */
    val baseUrl: String? = null,
    val authKind: MemberAuthKind,
    /** Keystore 引用名（不是 Key 本身；只在 [MemberAuthKind.API_KEY_REF] 时有值）。 */
    val authRef: String? = null,
    /** 免费额度文字备注，不存未经核实的数字。 */
    val quotaNote: String? = null,
    /** 免费额度状态位（优先走免费额度；耗尽明确报错、不静默切付费）。 */
    val quotaState: MemberQuotaState = MemberQuotaState.UNKNOWN,
    /**
     * 实际执行驱动的 agentId（如 Workbuddy 的 "codebuddy-cli"：内置
     * CodeBuddy CLI headless 是唯一受支持的驱动路径）。桌面 App 成员
     * 必填；其余形态为空（成员自己就是执行端）。
     */
    val driverAgentId: String? = null,
    val shapeStatus: MemberShapeStatus = MemberShapeStatus.UNVERIFIED,
    val connectionState: MemberConnectionState = MemberConnectionState.NOT_CONNECTED,
    val connectivity: MemberConnectivity = MemberConnectivity.NOT_CHECKED,
    /** 已授权项目 id 列表（沿用现有 projectId，不新造项目模型）。 */
    val authorizedProjectIds: List<String> = emptyList(),
    /** 最后一次状态更新时间（连接/连通性/授权变化时由 MemberManager 刷新）。 */
    val lastStateUpdatedAtEpochSeconds: Long? = null,
    val createdAtEpochSeconds: Long = 0,
    val updatedAtEpochSeconds: Long = 0,
) {
    companion object {
        /** id 形状：与 AgentRecord 相同（小写字母/数字开头）。 */
        private val ID_REGEX = Regex("^[a-z0-9][a-z0-9_-]*$")

        /**
         * 远端 CLI 成员（Mac / 远程设备 上的 Codex / CodeBuddy CLI）。
         * 不安装、不部署 CLI：CLI 在目标设备上已经有了，本成员只是
         * “连过去的路”的记录。[shapeStatus] 默认 UNVERIFIED——调用
         * 方须在核实该 CLI 的调用方式已验证（CliConfigs VERIFIED 行）
         * 后才传 VERIFIED。
         */
        fun remoteCliMember(
            memberId: String,
            agentId: String,
            displayName: String,
            transport: MemberTransport,
            endpointDeviceId: String? = null,
            endpointLabel: String? = null,
            role: MemberRole = MemberRole.DEVELOPER,
            shapeStatus: MemberShapeStatus = MemberShapeStatus.UNVERIFIED,
        ) = TeamMember(
            memberId = memberId,
            agentId = agentId,
            displayName = displayName,
            role = role,
            kind = MemberKind.DEVICE_CLI,
            transport = transport,
            endpointDeviceId = endpointDeviceId,
            endpointLabel = endpointLabel,
            authKind = MemberAuthKind.CLI_LOGIN,
            shapeStatus = shapeStatus,
        )

        /**
         * 云端聊天型成员（LeChat）。走 Chat Completions（OpenAI 兼容）
         * 经通用 OpenAI 兼容适配器（model-api-generic）。已核实路线：
         * POST /v1/chat/completions，base https://api.mistral.ai，
         * Bearer $MISTRAL_API_KEY；[baseUrl] 由调用方在核实官方文档
         * 后传入，框架不内置。Le Chat 订阅与 La Plateforme API 是
         * 两个独立计费系统。Agents API（Beta）本批不做。
         */
        fun cloudChatMember(
            memberId: String,
            agentId: String,
            displayName: String,
            authRef: String,
            baseUrl: String? = null,
            role: MemberRole = MemberRole.REVIEWER,
            quotaNote: String? = null,
        ) = TeamMember(
            memberId = memberId,
            agentId = agentId,
            displayName = displayName,
            role = role,
            kind = MemberKind.CLOUD_CHAT,
            transport = MemberTransport.CLOUD_API,
            baseUrl = baseUrl,
            authKind = MemberAuthKind.API_KEY_REF,
            authRef = authRef,
            quotaNote = quotaNote,
            shapeStatus = MemberShapeStatus.UNVERIFIED,
        )

        /**
         * 云端任务型成员（Manus）。任务型 API，不是聊天补全 API，
         * 不能塞进通用 OpenAI 兼容适配器，以后须单独做云端适配器
         * （本批不做）；[authRef] 是 Keystore 引用名（Key 由 maintainer
         * 自己在手机 App 里填）；[baseUrl] 由调用方在核实官方文档
         * 后传入，框架不内置。免费数额须 maintainer 在控制台核对，代码
         * 里不写死任何数字。
         */
        fun cloudTaskMember(
            memberId: String,
            agentId: String,
            displayName: String,
            authRef: String,
            baseUrl: String? = null,
            role: MemberRole = MemberRole.DEVELOPER,
            quotaNote: String? = null,
        ) = TeamMember(
            memberId = memberId,
            agentId = agentId,
            displayName = displayName,
            role = role,
            kind = MemberKind.CLOUD_TASK,
            transport = MemberTransport.CLOUD_API,
            baseUrl = baseUrl,
            authKind = MemberAuthKind.API_KEY_REF,
            authRef = authRef,
            quotaNote = quotaNote,
            shapeStatus = MemberShapeStatus.UNVERIFIED,
        )

        /**
         * 桌面 App 成员（Mac 端 Workbuddy）。已核实无官方可编程接口；
         * 唯一受支持的驱动路径是内置 CodeBuddy CLI headless（-p），故
         * [driverAgentId] 为 "codebuddy-cli"；本工厂不产生任何 CLI
         * 二进制路径、不建任何“WorkBuddy API”集成、不采用任何社区逆
         * 向方案。[endpointNote] 只能由以后在 Mac 上的发现填入。
         * 办公技能（deck/文档/表格）不走 CLI，没有受支持的路径，
         * 等 maintainer 给输入。
         */
        fun desktopAppMember(
            memberId: String,
            agentId: String,
            displayName: String,
            endpointDeviceId: String? = null,
            endpointLabel: String? = null,
            role: MemberRole = MemberRole.DEVELOPER,
        ) = TeamMember(
            memberId = memberId,
            agentId = agentId,
            displayName = displayName,
            role = role,
            kind = MemberKind.DESKTOP_APP,
            transport = MemberTransport.LAN,
            endpointDeviceId = endpointDeviceId,
            endpointLabel = endpointLabel,
            authKind = MemberAuthKind.NONE,
            shapeStatus = MemberShapeStatus.UNVERIFIED,
            driverAgentId = "codebuddy-cli",
        )

        /**
         * 身份待核实的设备成员（如小米 12 上的 agy：是不是 Termux 里
         * 的那个 agent 待 maintainer 回）。[agentId] 在此只是本地占位，不
         * 是“该 agent 就是某 CLI”的断言；[shapeStatus] 恒为
         * UNVERIFIED，在设备上核实真实身份后须重建成对应 CLI 成员，
         * 不要假设它就是老项目 agent_directory 里的 `agy` CLI。
         */
        fun unverifiedDeviceMember(
            memberId: String,
            displayName: String,
            transport: MemberTransport,
            endpointDeviceId: String? = null,
            endpointLabel: String? = null,
            role: MemberRole = MemberRole.DEVELOPER,
        ) = TeamMember(
            memberId = memberId,
            agentId = memberId,
            displayName = displayName,
            role = role,
            kind = MemberKind.DEVICE_CLI,
            transport = transport,
            endpointDeviceId = endpointDeviceId,
            endpointLabel = endpointLabel,
            authKind = MemberAuthKind.NONE,
            shapeStatus = MemberShapeStatus.UNVERIFIED,
        )
    }

    /** 界面上的连接状态文案：未连接一律诚实显示“未连接（等待安排）”。 */
    fun displayConnection(): String = when (connectionState) {
        MemberConnectionState.NOT_CONNECTED -> "未连接（等待安排）"
        MemberConnectionState.CONNECTED -> "已连接"
        MemberConnectionState.UNAVAILABLE -> "不可用（连接已断开）"
    }

    /** 是否可派发：只认连接状态；发现结果、连通性、静态能力表一律不算数。 */
    fun canDispatch(): Boolean = connectionState == MemberConnectionState.CONNECTED

    fun isAuthorizedFor(projectId: String): Boolean = projectId in authorizedProjectIds

    /** 校验。返回可读错误列表；空 = 合法。 */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (memberId.isBlank()) errors += "成员 id 不能为空"
        else if (!ID_REGEX.matches(memberId)) errors += "成员 id 只能用小写字母、数字、-、_，且以字母或数字开头"
        if (agentId.isBlank()) errors += "Agent id 不能为空"
        if (displayName.isBlank()) errors += "成员名字不能为空"
        // 形态与传输必须一致：云端成员只走云端 API，桌面 App 只走 LAN，
        // 设备 CLI 绝不走云端 API。
        when (kind) {
            MemberKind.CLOUD_CHAT, MemberKind.CLOUD_TASK -> {
                if (transport != MemberTransport.CLOUD_API) errors += "云端成员只走云端 API 传输"
                if (baseUrl != null && baseUrl.isBlank()) errors += "云端服务地址不能为空字符串"
            }
            MemberKind.DESKTOP_APP -> {
                if (transport != MemberTransport.LAN) errors += "桌面 App 成员只走 LAN 传输（接口待核实）"
                if (authKind != MemberAuthKind.NONE) errors += "桌面 App 成员在接口核实前没有认证形态"
                if (driverAgentId.isNullOrBlank()) {
                    errors += "桌面 App 成员须注明实际执行驱动（Workbuddy 为内置 CodeBuddy CLI）"
                }
            }
            MemberKind.DEVICE_CLI -> {
                if (transport == MemberTransport.CLOUD_API) errors += "设备 CLI 成员不能走云端 API 传输"
                if (driverAgentId != null) errors += "只有桌面 App 成员能带 driverAgentId"
            }
        }
        if (kind != MemberKind.CLOUD_CHAT && kind != MemberKind.CLOUD_TASK && baseUrl != null) {
            errors += "服务地址只属于云端成员"
        }
        // 认证：API-key 只存引用名；引用名只属于 API-key 形态。
        when (authKind) {
            MemberAuthKind.API_KEY_REF -> {
                if (authRef.isNullOrBlank()) errors += "API-key 成员需要填写 Keystore 引用名"
                else if (BrainConfigValidator.looksLikeSecretValue(authRef)) {
                    errors += "authRef 只能是引用名，不能是密钥本身"
                }
            }
            else -> {
                if (authRef != null) errors += "只有 API-key 成员能带 authRef"
            }
        }
        if (endpointDeviceId != null && endpointDeviceId.isBlank()) {
            errors += "端点设备 id 不能为空字符串"
        }
        if (endpointLabel != null && endpointLabel.isBlank()) {
            errors += "端点名不能为空字符串"
        }
        if (endpointNote != null && BrainConfigValidator.looksLikeSecretValue(endpointNote)) {
            errors += "端点备注不能是密钥"
        }
        for (projectId in authorizedProjectIds) {
            if (projectId.isBlank()) errors += "授权项目 id 不能为空字符串"
        }
        return errors
    }
}
