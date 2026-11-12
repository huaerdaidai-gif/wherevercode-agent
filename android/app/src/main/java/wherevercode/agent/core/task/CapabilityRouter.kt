package wherevercode.agent.core.task

import java.util.UUID
import wherevercode.agent.core.agent.AgentKind
import wherevercode.agent.core.agent.AgentLoginState

/**
 * P1 确定性自动派活——打分路由（2026-11-11）。
 *
 * 路由 = 确定性打分查找（纯代码，不用 LLM 路由器）：
 * 门禁先硬过滤（不过的连分都不打），过了的按整数分加总，
 * 最高分胜出，同分按 agentId 字典序（可复现、可回看）。
 *
 * 门禁（老项目匹配四层的 V5 版，顺序即检查顺序；fail-closed，
 * 每条不过都带原因，路由决策进日志供 eval 回看）：
 * 1. 不可派单（dispatchable=false）——只对话不接任务。
 * 2. 明确未登录（loginState=NOT_LOGGED_IN）。UNKNOWN 不拦：
 *    远端 Agent 的登录态经常未知（如 agy、codex），拦了等于
 *    把能干活的 Agent 全拦掉；真没登录会在执行时如实失败。
 * 3. DEVICE 的设备从未在线（lastSeen=0）。
 * 4. DEVICE 的设备未配对确认。
 * 5. 仓库硬过滤（只拦 DEVICE）：任务的项目在某台设备上有家，
 *    候选设备必须托管该项目；项目无任何设备家（GitHub-only 或
 *    无家），DEVICE 一律拦下并给提示（可先克隆到一台设备）。
 *    CLOUD/LOCAL_MODEL 不拦——它们不在设备文件夹里执行，
 *    仓库位置与它们无关。
 * 6. 授权项目列表非空且不含该项目。
 * 7. 计费未允许（CLOUD 按计费处理，须 allowMetered）。
 * 8. 失败冷却：eval 日志里连续 3 次失败且最近一次在 30 分钟内。
 * 9. 用户指定（pinnedAgentId）：只留被指定的一个，其余不参选。
 *
 * 打分（只给过门禁的候选）：
 * - 在线新近：DEVICE 按最后在线 ≤5 分钟 +30、≤30 分钟 +20、
 *   ≤2 小时 +10、更久 +0；CLOUD/LOCAL_MODEL 恒 +30（无需在线等待）。
 * - 负载：0 个在途任务 +20、1 个 +10、≥2 个 +0。
 * - 能力匹配（自报 capabilities；空 = 未知，只不加分，不扣分）：
 *   代码/修复任务含 dev/code +20；审查含 review +20；
 *   调研含 research/dev +10。
 * - 审查分工：审查任务且已知写代码的 Agent 时，非写作者 +15。
 * - 成本：FREE +10、METERED +0。
 *
 * 历史成功率绝不进打分（老项目“记分不参政”；回归测试锁定）。
 * 用户指定（pinned）过门禁即胜出——用户自己选 Agent 仍是功能，
 * 不是缺口；指定者过不了门禁时如实报原因，不静默换人。
 */
enum class TaskType {
    CODE,
    FIX,
    REVIEW,
    RESEARCH,
}

/**
 * 任务类型启发式 v1：只看标题 + 描述的确定性关键词匹配，不调模型。
 * 分错了只影响“建议”和 eval 归类，不影响执行（路由的硬门禁与
 * 任务类型无关）。关键词命中顺序：审查 > 修复 > 调研 > 代码。
 */
fun classifyTaskType(title: String, description: String?): TaskType {
    val text = ((title + " " + (description ?: "")).lowercase())
    val reviewKeywords = listOf("审查", "评审", "审阅", "检查代码", "review", "code review", "cr ")
    if (reviewKeywords.any { text.contains(it) }) return TaskType.REVIEW
    val fixKeywords = listOf("修复", "修 ", "修一", "fix", "bug", "报错", "异常", "出问题", "不工作", "失败", "崩溃", "闪退")
    if (fixKeywords.any { text.contains(it) }) return TaskType.FIX
    val researchKeywords = listOf("调研", "研究", "对比", "research", "查一下", "看看", "怎么实现", "方案", "选型")
    if (researchKeywords.any { text.contains(it) }) return TaskType.RESEARCH
    return TaskType.CODE
}

data class RoutingRequirement(
    val projectId: String?,
    val taskType: TaskType,
    /** 用户明确指定的 Agent（点选行）；指定即只评它一个。 */
    val pinnedAgentId: String? = null,
    /** 审查任务时写代码的 Agent（审查分工加分用；未知为 null）。 */
    val reviewCoderAgentId: String? = null,
    /** 是否允许派给计费 Agent（CLOUD）。默认不允许。 */
    val allowMetered: Boolean = false,
    val nowEpochSeconds: Long,
)

data class CandidateScore(
    val agentId: String,
    val score: Int,
    /** 分项：factor（中文）→ 分数；被门禁排除时为空。 */
    val breakdown: Map<String, Int> = emptyMap(),
    /** 非空 = 被门禁排除的原因（中文）；为空 = 过门禁参与打分。 */
    val excludedReason: String? = null,
)

data class RoutingDecision(
    val decisionId: String,
    val createdAtEpochSeconds: Long,
    val projectId: String?,
    val taskType: TaskType,
    val pinnedAgentId: String?,
    val candidates: List<CandidateScore>,
    val chosenAgentId: String?,
    /** 给人看的一句话（首选的理由 / 无可用时的原因）。 */
    val reason: String,
    /** 仓库硬过滤把 DEVICE 全拦下时的提示（可先克隆到一台设备）。 */
    val repoHint: String? = null,
    /** 派单后由 attachTask 回填。 */
    val assignedTaskId: String? = null,
    /**
     * 分流小模型建议（端侧小模型路由 v1）：成功分流时才有；
     * 回落时为 null（不假装分流过）。分流只是建议，不改派单。
     */
    val triage: TriageRecord? = null,
) {
    /** 选 Agent 弹窗里的建议行文案（不含 Agent 名）。 */
    val shortReason: String
        get() {
            if (chosenAgentId == null) return reason
            val chosen = candidates.firstOrNull { it.agentId == chosenAgentId } ?: return reason
            val parts = mutableListOf<String>()
            if (projectId != null) parts += "仓库已检出"
            if ((chosen.breakdown["在线"] ?: 0) >= 30) parts += "在线"
            if ((chosen.breakdown["负载"] ?: 0) >= 20) parts += "空闲"
            if ((chosen.breakdown["能力"] ?: 0) > 0) parts += "能力匹配"
            if ((chosen.breakdown["成本"] ?: 0) > 0) parts += "免费"
            return if (parts.isEmpty()) "综合评分最高" else parts.take(3).joinToString(" · ")
        }
}

data class ConsecutiveFailures(
    val count: Int,
    val lastFailureAtEpochSeconds: Long,
)

object CapabilityRouter {
    /** 失败冷却：连续失败 ≥3 次进冷却（老项目 120 秒是服务端快循环口径；V5 编码任务更长，改 30 分钟）。 */
    const val COOLING_FAILURES: Int = 3
    const val COOLING_SECONDS: Long = 30 * 60

    const val SCORE_ONLINE_FRESH: Int = 30
    const val SCORE_ONLINE_RECENT: Int = 20
    const val SCORE_ONLINE_STALE: Int = 10
    const val SCORE_LOAD_IDLE: Int = 20
    const val SCORE_LOAD_ONE: Int = 10
    const val SCORE_CAPABILITY_MATCH: Int = 20
    const val SCORE_RESEARCH_MATCH: Int = 10
    const val SCORE_REVIEW_DIFFERENT_AGENT: Int = 15
    const val SCORE_COST_FREE: Int = 10

    private const val ONLINE_FRESH_SECONDS: Long = 5 * 60
    private const val ONLINE_RECENT_SECONDS: Long = 30 * 60
    private const val ONLINE_STALE_SECONDS: Long = 2 * 3600

    fun route(
        cards: List<CapabilityCard>,
        requirement: RoutingRequirement,
        cooldowns: Map<String, ConsecutiveFailures> = emptyMap(),
        /** 该项目在任何设备上有没有 DEVICE 家（决定 repoHint 的措辞）。 */
        projectHasDeviceLocation: Boolean = false,
    ): RoutingDecision {
        val now = requirement.nowEpochSeconds
        val projectHostsIt = cards.any { card ->
            requirement.projectId != null && card.hostedProjectIds.contains(requirement.projectId)
        }
        val scored = cards.map { card ->
            scoreOne(card, requirement, cooldowns, projectHostsIt, now)
        }
        val eligible = scored.filter { it.excludedReason == null }
        val chosen = eligible.maxWithOrNull(
            // 最高分胜出；同分按 agentId 字典序最小（确定性，不依赖输入顺序）：
            // comparator 是“分升序 + agentId 降序”，max 即“最高分 + 最小 agentId”。
            compareBy<CandidateScore> { it.score }.thenByDescending { it.agentId },
        )
        val reason: String
        var repoHint: String? = null
        if (chosen != null) {
            reason = if (requirement.pinnedAgentId != null) {
                "用户指定 ${chosen.agentId}，门禁全过"
            } else {
                "首选 ${chosen.agentId}（${chosen.score} 分）"
            }
        } else {
            val repoBlocked = scored.any { it.excludedReason == "仓库不在该设备上" } ||
                scored.any { it.excludedReason == "仓库未检出到任何设备" }
            if (repoBlocked && requirement.projectId != null) {
                repoHint = if (projectHasDeviceLocation) {
                    "仓库在某台设备上有家，但那台设备没有可用 Agent"
                } else {
                    "仓库未检出到任何设备（可先克隆到一台设备）"
                }
            }
            val reasons = scored.mapNotNull { it.excludedReason }
                .groupingBy { it }.eachCount()
                .entries.joinToString("；") { (r, n) -> "$r（$n 个）" }
            reason = if (reasons.isEmpty()) "无已添加 Agent" else "无可用 Agent：$reasons"
        }
        return RoutingDecision(
            decisionId = UUID.randomUUID().toString(),
            createdAtEpochSeconds = now,
            projectId = requirement.projectId,
            taskType = requirement.taskType,
            pinnedAgentId = requirement.pinnedAgentId,
            candidates = scored,
            chosenAgentId = chosen?.agentId,
            reason = reason,
            repoHint = repoHint,
        )
    }

    private fun scoreOne(
        card: CapabilityCard,
        requirement: RoutingRequirement,
        cooldowns: Map<String, ConsecutiveFailures>,
        projectHostsIt: Boolean,
        now: Long,
    ): CandidateScore {
        val gateReason = gateReason(card, requirement, cooldowns, projectHostsIt, now)
        if (gateReason != null) {
            return CandidateScore(agentId = card.agentId, score = 0, excludedReason = gateReason)
        }
        val breakdown = mutableMapOf<String, Int>()
        // 在线新近。
        if (card.kind == AgentKind.CLOUD || card.kind == AgentKind.LOCAL_MODEL) {
            breakdown["在线"] = SCORE_ONLINE_FRESH
        } else {
            val age = now - card.lastSeenEpochSeconds
            breakdown["在线"] = when {
                age <= ONLINE_FRESH_SECONDS -> SCORE_ONLINE_FRESH
                age <= ONLINE_RECENT_SECONDS -> SCORE_ONLINE_RECENT
                age <= ONLINE_STALE_SECONDS -> SCORE_ONLINE_STALE
                else -> 0
            }
        }
        // 负载。
        breakdown["负载"] = when {
            card.activeTaskCount <= 0 -> SCORE_LOAD_IDLE
            card.activeTaskCount == 1 -> SCORE_LOAD_ONE
            else -> 0
        }
        // 能力匹配（自报；空 = 未知，不加分不扣分）。
        val caps = card.tools.map { it.lowercase() }.toSet()
        val capabilityPoints = when (requirement.taskType) {
            TaskType.CODE, TaskType.FIX ->
                if (caps.any { it == "dev" || it == "code" || it == "开发" }) SCORE_CAPABILITY_MATCH else 0
            TaskType.REVIEW ->
                if (caps.any { it == "review" || it == "审查" }) SCORE_CAPABILITY_MATCH else 0
            TaskType.RESEARCH ->
                if (caps.any { it == "research" || it == "调研" || it == "dev" }) SCORE_RESEARCH_MATCH else 0
        }
        breakdown["能力"] = capabilityPoints
        // 审查分工：审查者与写作者不同加分（Cognition 干净上下文审查思路）。
        if (requirement.taskType == TaskType.REVIEW &&
            requirement.reviewCoderAgentId != null &&
            card.agentId != requirement.reviewCoderAgentId
        ) {
            breakdown["审查分工"] = SCORE_REVIEW_DIFFERENT_AGENT
        }
        // 成本。
        breakdown["成本"] = if (card.costClass == CostClass.FREE) SCORE_COST_FREE else 0
        return CandidateScore(
            agentId = card.agentId,
            score = breakdown.values.sum(),
            breakdown = breakdown,
        )
    }

    private fun gateReason(
        card: CapabilityCard,
        requirement: RoutingRequirement,
        cooldowns: Map<String, ConsecutiveFailures>,
        projectHostsIt: Boolean,
        now: Long,
    ): String? {
        // 用户指定：只留被指定的一个。
        if (requirement.pinnedAgentId != null && card.agentId != requirement.pinnedAgentId) {
            return "用户已指定其他 Agent"
        }
        if (!card.dispatchable) return "不可派单（仅对话）"
        if (card.loginState == AgentLoginState.NOT_LOGGED_IN) return "未登录"
        if (card.kind == AgentKind.DEVICE) {
            if (card.lastSeenEpochSeconds <= 0) return "设备从未在线"
            if (!card.pairingConfirmed) return "设备未配对确认"
            if (requirement.projectId != null) {
                if (!projectHostsIt) return "仓库未检出到任何设备"
                if (!card.hostedProjectIds.contains(requirement.projectId)) return "仓库不在该设备上"
            }
        }
        if (card.authorizedProjectIds.isNotEmpty() && requirement.projectId != null &&
            !card.authorizedProjectIds.contains(requirement.projectId)
        ) {
            return "未授权该项目"
        }
        if (card.costClass == CostClass.METERED && !requirement.allowMetered) return "计费 Agent，需明确允许"
        val cooling = cooldowns[card.agentId]
        if (cooling != null && cooling.count >= COOLING_FAILURES &&
            now - cooling.lastFailureAtEpochSeconds <= COOLING_SECONDS
        ) {
            return "连续失败 ${cooling.count} 次，冷却中"
        }
        return null
    }
}
