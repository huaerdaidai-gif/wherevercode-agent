package wherevercode.agent.core.agent

/**
 * 云端 Agent（MCP 连接器，V5，2026-11-11）。
 *
 * 老项目“添加云端 Agent”对话框的机制（照抄机制，不抄凭证值）：
 * 云端 Agent 不是平台去调它，而是它把平台加为自定义 MCP 连接器、
 * 自己来领单——方向与平台 Agent（webhook 唤醒）正好相反：
 * 平台 Agent 是平台去叫醒 Agent；云端 Agent 是 Agent 来连 App
 * 领单。
 *
 * - 在哪干活：云端（只是一个标注：归到设备下就和那台设备的 Agent
 *   分一组，不改变权限）。[cloudHomeDeviceId] 只做分组，永不参与
 *   路由判定（见 [AgentChatRouter]：它只看 `deviceId`，不看本字段）。
 * - 角色（可多选）：开发、审查、递代码（把交付的改动递给读不到
 *   GitHub 的审查方）。开发 + 审查默认勾上。
 * - 权限：云端 Agent 和设备上的 Agent 权限平等：能领活写代码、能
 *   审查别人的交付；“允许它派活”开着时也能给其他 Agent 派活，
 *   关掉就只能领活、读、交结果。它要改哪个文件就会读到那个文件
 *   的内容。
 * - 各 App 设置说明（老项目口径，见 [CloudAgentSetupSheet]）：
 *   身份验证一律选“无”；连接地址填进对方 App 的自定义连接器，
 *   钥匙放进 Authorization: Bearer 请求头（Manus 另加
 *   User-Agent: WhereverCode-CloudAgent/1.0；ChatGPT 只支持每天
 *   定时，建多个每天定时任务覆盖想让它来查的时间，例如每 2 小时
 *   一个）。
 *
 * 钥匙：每 Agent 一个随机 Bearer 钥匙，保存时现生成、只存本机
 * Keystore（见 [CloudAgentCredentialStore]），设置说明里展示出来
 * 让用户去对方 App 里填。记录里只存引用名（[AgentRecord.authRef]），
 * 绝不存钥匙值。
 *
 * 缺口（诚实写在 [CloudAgentMcpEndpoint] 与 DELIVERY 里，不伪装）：
 * 手机 App 没有公网地址，MCP 服务端尚未暴露——注册、角色与设置
 * 说明先行，端点接线另算。
 */
object CloudAgent {
    /** 适配器 id：云端 Agent 记录的唯一标识（见 [isCloudAgentRecord]）。 */
    const val ADAPTER_ID: String = "cloud-agent"

    /** 角色：开发（领活写代码）。 */
    const val ROLE_DEVELOP: String = "dev"

    /** 角色：审查（审查别人的交付）。 */
    const val ROLE_REVIEW: String = "review"

    /** 角色：递代码（把交付的改动递给读不到 GitHub 的审查方）。 */
    const val ROLE_DELIVER: String = "deliver"

    /** 全部角色（表单多选的候选集；顺序即表单顺序）。 */
    val ALL_ROLES: List<String> = listOf(ROLE_DEVELOP, ROLE_REVIEW, ROLE_DELIVER)

    /** 默认角色：开发 + 审查（老项目默认值）。 */
    val DEFAULT_ROLES: List<String> = listOf(ROLE_DEVELOP, ROLE_REVIEW)

    /** 是否云端 Agent 记录（不认 brainKind，只认 adapterId，与 Manus 同款做法）。 */
    fun isCloudAgentRecord(record: AgentRecord): Boolean =
        record.adapterId.equals(ADAPTER_ID, ignoreCase = true)

    /** 角色中文名（只做展示；存记录的永远是英文稳定值）。 */
    fun roleLabel(role: String): String = when (role) {
        ROLE_DEVELOP -> "开发"
        ROLE_REVIEW -> "审查"
        ROLE_DELIVER -> "递代码"
        else -> role
    }

    /** 角色列表中文名（展示用，如“开发、审查”）。 */
    fun rolesLabel(roles: List<String>): String =
        roles.joinToString("、") { roleLabel(it) }
}
