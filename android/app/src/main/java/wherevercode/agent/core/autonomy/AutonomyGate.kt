package wherevercode.agent.core.autonomy

import java.util.UUID
import wherevercode.agent.core.control.ControlAuditEntry
import wherevercode.agent.core.control.ControlAuditLog
import wherevercode.agent.core.control.ControlStore

/**
 * 自主度门（大脑总管的开关执行层）。
 *
 * 四道门，全是“模型提议、程序验证”的程序侧决定：
 * - plan 批准：[planDecision]
 * - 派发确认：[dispatchDecision]（成员分派）与 [writeDecision] 的
 *   dispatch_task（大脑调派发工具）
 * - 审查 verdict 采纳：[verdictDecision]
 * - 写操作审批：[writeDecision]（执行权限 L0–L4 的梯子）
 *
 * 关键语义（maintainer 2026-11-11 22:43）：
 * 1. 档位实时读：每次决策都重新读 [AutonomyStore]，模式切换立刻
 *    对进行中的任务生效（下一道门按新档位走）。
 * 2. 已批过的决定不动：门只做“这次要不要问用户”的决定，绝不调
 *    ApprovalBroker.invalidate()，不碰已批准缓存，不改写已落的决定。
 * 3. 全权模式的自动决定全记审计：凡是“监督模式会问用户、这一档
 *    自动放行”的决定，都写进 [ControlAuditLog]（Room
 *    control_audit_log，与任务库同库，重启仍在），带 actor /
 *    action / rule / 时间；用户事后在任务详情页“自动决定”一节可查，
 *    不同意可走停止 / 重试 / 改目标打回。只读操作从不问用户，
 *    也就不记（否则审计会被每一次读文件淹没）。
 * 4. 返工上限 2 轮是硬线：[verdictDecision] 在返工已达上限时一律
 *    升级给用户，全权模式也不例外。
 *
 * 档位未接线时（gate 为 null）调用方走原有规则——本门是可选件，
 *    不接不改变任何行为。
 */
sealed class AutonomyDecision {
    /** 自动放行（rule 写明按哪条规则，例如“主动性 4·高自主”）。 */
    data class AutoApproved(val rule: String) : AutonomyDecision()

    /** 需用户决定（reason 写明为什么等，例如“监督模式：等用户点”）。 */
    data class NeedsUser(val reason: String) : AutonomyDecision()
}

interface AutonomyGate {
    /** plan 批准：监督模式等用户点，全权模式自动批（记审计）。 */
    fun planDecision(taskId: String): AutonomyDecision

    /** 派发确认：监督模式等用户确认，全权模式自动派发（记审计）。 */
    fun dispatchDecision(taskId: String, memberId: String): AutonomyDecision

    /**
     * 审查 verdict 采纳：监督模式等用户采纳；全权模式自动采纳（记审计），
     * 但 reworkRounds 已达上限时一律等用户。
     */
    fun verdictDecision(taskId: String, verdict: String, reworkRounds: Int): AutonomyDecision

    /**
     * 写操作审批。返回 null = 门不管这把工具（未知 / 扩展工具），
     * 调用方回落到原有 needsApproval。
     */
    fun writeDecision(taskId: String, tool: String, isReadOnlyRun: Boolean): AutonomyDecision?

    /** 该任务的自动决定记录（actor = 大脑总管），按时间先后。 */
    fun autoDecisionsFor(taskId: String): List<ControlAuditEntry>
}

class DefaultAutonomyGate(
    private val store: AutonomyStore,
    /** 为空 = 只做决定、不落审计（测试与未接线调用点）。 */
    private val auditLog: ControlAuditLog? = null,
    /** 为空 = 审计行 goalVersion/epoch 记 1（控制面计数器未接线时）。 */
    private val controlStore: ControlStore? = null,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : AutonomyGate {

    companion object {
        /** 审计 actor：自动决定是大脑总管做的，不是用户做的。 */
        const val ACTOR: String = "autonomy:manager"
    }

    override fun planDecision(taskId: String): AutonomyDecision {
        val settings = store.loadAll()
        return if (AutonomyPolicy.planApprovalRequired(settings.initiative)) {
            AutonomyDecision.NeedsUser(
                "监督模式：plan 批准等用户点（主动性 ${settings.initiative.level}·${settings.initiative.label}）",
            )
        } else {
            val rule = "主动性 ${settings.initiative.level}·${settings.initiative.label}：plan 自动批准"
            record(taskId, "auto_approve_plan", rule)
            AutonomyDecision.AutoApproved(rule)
        }
    }

    override fun dispatchDecision(taskId: String, memberId: String): AutonomyDecision {
        val settings = store.loadAll()
        return if (AutonomyPolicy.dispatchConfirmationRequired(settings.initiative)) {
            AutonomyDecision.NeedsUser(
                "监督模式：派发给 $memberId 等用户确认（主动性 ${settings.initiative.level}·${settings.initiative.label}）",
            )
        } else {
            val rule = "主动性 ${settings.initiative.level}·${settings.initiative.label}：派发给 $memberId 自动放行"
            record(taskId, "auto_dispatch", rule)
            AutonomyDecision.AutoApproved(rule)
        }
    }

    override fun verdictDecision(taskId: String, verdict: String, reworkRounds: Int): AutonomyDecision {
        val settings = store.loadAll()
        return if (AutonomyPolicy.verdictApprovalRequired(settings.initiative, reworkRounds)) {
            val why = if (reworkRounds >= AutonomyPolicy.REWORK_LIMIT) {
                "返工已达上限（${reworkRounds}/${AutonomyPolicy.REWORK_LIMIT} 轮），升级给用户"
            } else {
                "监督模式：审查 verdict 采纳等用户点（主动性 ${settings.initiative.level}·${settings.initiative.label}）"
            }
            AutonomyDecision.NeedsUser(why)
        } else {
            val rule = "主动性 ${settings.initiative.level}·${settings.initiative.label}：" +
                "审查 verdict=$verdict 自动采纳（返工 ${reworkRounds}/${AutonomyPolicy.REWORK_LIMIT} 轮）"
            record(taskId, "auto_adopt_verdict", rule)
            AutonomyDecision.AutoApproved(rule)
        }
    }

    override fun writeDecision(taskId: String, tool: String, isReadOnlyRun: Boolean): AutonomyDecision? {
        val settings = store.loadAll()
        if (tool == "dispatch_task") {
            return if (AutonomyPolicy.dispatchConfirmationRequired(settings.initiative)) {
                AutonomyDecision.NeedsUser(
                    "监督模式：派发任务等用户确认（主动性 ${settings.initiative.level}·${settings.initiative.label}）",
                )
            } else {
                val rule = "主动性 ${settings.initiative.level}·${settings.initiative.label}：dispatch_task 自动放行"
                record(taskId, "auto_dispatch_task", rule)
                AutonomyDecision.AutoApproved(rule)
            }
        }
        val needs = AutonomyPolicy.writeNeedsApproval(tool, settings.executionLevel, isReadOnlyRun)
            ?: return null
        return if (needs) {
            AutonomyDecision.NeedsUser(
                "执行权限 ${settings.executionLevel.label}：$tool 需用户审批",
            )
        } else {
            val rule = "执行权限 ${settings.executionLevel.label}：$tool 自动放行"
            // 只记“本来会问用户”的放行：L0 也问的操作才记；只读操作
            // 在任何等级都不问用户，不记（否则审计会被读操作淹没）。
            val wouldAskAtL0 =
                AutonomyPolicy.writeNeedsApproval(tool, ExecutionLevel.L0, isReadOnlyRun) == true
            if (wouldAskAtL0) record(taskId, "auto_approve_write", rule)
            AutonomyDecision.AutoApproved(rule)
        }
    }

    override fun autoDecisionsFor(taskId: String): List<ControlAuditEntry> =
        auditLog?.listForTask(taskId)?.filter { it.actor == ACTOR } ?: emptyList()

    /**
     * 写一条自动决定审计。审计写失败不阻塞决策本身（工具执行结果
     * 仍由 ToolRegistry 的审计记 outcome）；失败即丢弃，不抛错、
     * 不让一次审计故障拖垮整轮执行。
     */
    private fun record(taskId: String, action: String, rule: String) {
        val log = auditLog ?: return
        try {
            log.record(
                ControlAuditEntry(
                    taskId = taskId,
                    actor = ACTOR,
                    action = action,
                    goalVersion = controlStore?.goalVersion(taskId) ?: 1,
                    epoch = controlStore?.authEpoch(taskId) ?: 1,
                    operationId = UUID.randomUUID().toString(),
                    ok = true,
                    reason = rule,
                    ts = nowEpochSeconds(),
                ),
            )
        } catch (_: Exception) {
            // 审计是记录，不是门禁：记不上也不改决策。
        }
    }
}
