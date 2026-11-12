package wherevercode.agent.core.task

import org.json.JSONArray
import org.json.JSONObject

/**
 * P2 流水线：规划 → 编码 → 干净上下文审查（2026-11-11）。
 *
 * 结构化 plan artifact（调研报告 P2-1、老项目 team_workflows.py 的
 * 队长 plan 学思路、不学形态：老项目的 plan 是 `lead_plan` 文本解析，
 * V5 的 plan 是结构化字段）。
 *
 * 一个 plan 只认字段，不认文字：
 * - `objective`：要做什么（一段话，与任务目标一致）。
 * - `filesInScope`：要改的文件清单（空 = 整个项目家，不限文件）。
 * - `filesOutOfScope`：明确不碰的文件（机械范围检查的另一半）。
 * - `steps`：实施步骤（≥1 条才可批准）。
 * - `acceptanceCriteria`：验收标准（≥1 条才可批准；编码者的任务包
 *   从这里填，见 [TaskPacket.withPlan]）。
 * - `testCommands`：要跑的测试命令（可空；空 = plan 没要求测试，
 *   机械检查如实记 NOT_RUN，不编“已通过”）。
 * - `expectedOutput`：预期输出（可空）。
 *
 * 批准门：[validate] 为空才可批准；[PipelineOrchestrator.approvePlan]
 * 只接受已验证的草稿。plan 没批准，编码不开始（认领门禁见
 * [PipelineRunnerHook.canStartCoding]），不是约定，是门。
 */
data class PlanArtifact(
    val version: Int = CURRENT_VERSION,
    val objective: String,
    val filesInScope: List<String> = emptyList(),
    val filesOutOfScope: List<String> = emptyList(),
    val steps: List<String> = emptyList(),
    val acceptanceCriteria: List<String> = emptyList(),
    val testCommands: List<String> = emptyList(),
    val expectedOutput: String? = null,
    val plannerId: String? = null,
    val createdAtEpochSeconds: Long = 0,
) {
    companion object {
        const val CURRENT_VERSION: Int = 1

        fun fromJson(json: String): PlanArtifact {
            val obj = JSONObject(json)
            val version = obj.optInt("version", -1)
            require(version == CURRENT_VERSION) { "未知 plan 版本：$version" }
            return PlanArtifact(
                version = version,
                objective = obj.getString("objective"),
                filesInScope = obj.optJSONArray("files_in_scope")?.toStringList() ?: emptyList(),
                filesOutOfScope = obj.optJSONArray("files_out_of_scope")?.toStringList() ?: emptyList(),
                steps = obj.optJSONArray("steps")?.toStringList() ?: emptyList(),
                acceptanceCriteria = obj.optJSONArray("acceptance_criteria")?.toStringList() ?: emptyList(),
                testCommands = obj.optJSONArray("test_commands")?.toStringList() ?: emptyList(),
                expectedOutput = obj.optString("expected_output").takeIf { it.isNotEmpty() },
                plannerId = obj.optString("planner_id").takeIf { it.isNotEmpty() },
                createdAtEpochSeconds = obj.optLong("created_at", 0),
            )
        }

        private fun JSONArray.toStringList(): List<String> {
            val out = mutableListOf<String>()
            for (i in 0 until length()) {
                val s = optString(i, "").trim()
                if (s.isNotEmpty()) out += s
            }
            return out
        }

        /** 从自由文本行（每行一条）拼 plan：UI 的 plan 草稿对话框用它。 */
        fun fromLines(
            objective: String,
            steps: List<String>,
            acceptanceCriteria: List<String>,
            testCommands: List<String> = emptyList(),
            filesInScope: List<String> = emptyList(),
            filesOutOfScope: List<String> = emptyList(),
            expectedOutput: String? = null,
            plannerId: String? = null,
            createdAtEpochSeconds: Long = 0,
        ): PlanArtifact = PlanArtifact(
            objective = objective.trim(),
            filesInScope = filesInScope.map { it.trim() }.filter { it.isNotEmpty() },
            filesOutOfScope = filesOutOfScope.map { it.trim() }.filter { it.isNotEmpty() },
            steps = steps.map { it.trim() }.filter { it.isNotEmpty() },
            acceptanceCriteria = acceptanceCriteria.map { it.trim() }.filter { it.isNotEmpty() },
            testCommands = testCommands.map { it.trim() }.filter { it.isNotEmpty() },
            expectedOutput = expectedOutput?.trim()?.takeIf { it.isNotEmpty() },
            plannerId = plannerId,
            createdAtEpochSeconds = createdAtEpochSeconds,
        )
    }

    /** 批准前校验：返回问题清单；空 = 可批准。 */
    fun validate(): List<String> {
        val problems = mutableListOf<String>()
        if (objective.isBlank()) problems += "plan 目标不能为空"
        if (steps.isEmpty()) problems += "plan 至少要有 1 个实施步骤"
        if (acceptanceCriteria.isEmpty()) problems += "plan 至少要有 1 条验收标准"
        return problems
    }

    fun toJson(): String {
        val obj = JSONObject()
        obj.put("version", version)
        obj.put("objective", objective)
        obj.put("files_in_scope", JSONArray(filesInScope))
        obj.put("files_out_of_scope", JSONArray(filesOutOfScope))
        obj.put("steps", JSONArray(steps))
        obj.put("acceptance_criteria", JSONArray(acceptanceCriteria))
        obj.put("test_commands", JSONArray(testCommands))
        if (expectedOutput != null) obj.put("expected_output", expectedOutput)
        if (plannerId != null) obj.put("planner_id", plannerId)
        obj.put("created_at", createdAtEpochSeconds)
        return obj.toString()
    }
}

/**
 * 流水线阶段（任务状态之外的子状态；不改 P0 状态机）。
 * plan → code → review → done；返工时 review → code（阶段回退，
 * 任务态走 review → rework → assigned → working 的 P0 返工环）。
 */
object PipelineStage {
    const val PLAN: String = "plan"
    const val CODE: String = "code"
    const val REVIEW: String = "review"
    const val DONE: String = "done"

    fun labelOf(stage: String): String = when (stage) {
        PLAN -> "规划"
        CODE -> "编码"
        REVIEW -> "审查"
        DONE -> "完成"
        else -> stage
    }
}

/** plan 批准态。 */
object PipelinePlanStatus {
    const val NONE: String = "none"
    const val DRAFT: String = "draft"
    const val APPROVED: String = "approved"
    /**
     * 自动起草失败态（runner，2026-11-11）：规划 Agent 未返回可用 plan
     * （调用失败/超时/解析失败）。不是草稿——没有可批准的内容；
     * 任务留 queued，用户仍可手动提交 plan；runner 不自动重试。
     */
    const val FAILED: String = "failed"

    fun labelOf(status: String): String = when (status) {
        NONE -> "未提交"
        DRAFT -> "草稿待批准"
        APPROVED -> "已批准"
        FAILED -> "草稿失败"
        else -> status
    }
}

/**
 * 审查 verdict：只取两值（老项目 task_review.py 的口径；C3 §5.17
 * 的文本勾选绝不能搬——完成只认机械检查 + 这个 verdict，不认
 * Agent 自己在结果文本里说的“完成了/勾选了”）。
 */
object ReviewVerdict {
    const val APPROVE: String = "approve"
    const val CHANGES_REQUESTED: String = "changes_requested"

    fun labelOf(verdict: String): String = when (verdict) {
        APPROVE -> "通过"
        CHANGES_REQUESTED -> "打回返工"
        else -> verdict
    }

    fun isValid(verdict: String): Boolean =
        verdict == APPROVE || verdict == CHANGES_REQUESTED
}

/** 测试 verdict（流水线自己的值；成员回传的 MemberTestVerdict 在调用方映射进来）。 */
enum class PipelineTestVerdict {
    PASSED,
    FAILED,
    NOT_RUN,
}

/** 一项机械检查的单项结果。 */
enum class MechanicalOutcome {
    PASSED,
    FAILED,
    NOT_RUN,
}

data class MechanicalCheck(
    val name: String,
    val outcome: MechanicalOutcome,
    val detail: String,
)

/**
 * 一次机械检查的汇总：passed = 没有 FAILED（NOT_RUN 不拦审查，
 * 但如实列在结果里，不谎报通过）。
 */
data class MechanicalCheckResult(
    val checks: List<MechanicalCheck>,
    val createdAtEpochSeconds: Long = 0,
) {
    val passed: Boolean get() = checks.none { it.outcome == MechanicalOutcome.FAILED }
    val failed: List<MechanicalCheck> get() = checks.filter { it.outcome == MechanicalOutcome.FAILED }
    val notRun: List<MechanicalCheck> get() = checks.filter { it.outcome == MechanicalOutcome.NOT_RUN }

    fun summaryText(): String = buildString {
        append(if (passed) "机械检查通过" else "机械检查不通过")
        append("（${checks.count { it.outcome == MechanicalOutcome.PASSED }} 通过 / ")
        append("${failed.size} 失败 / ${notRun.size} 未跑）")
        failed.forEach { append("\n✗ ${it.name}：${it.detail}") }
        notRun.forEach { append("\n○ ${it.name}：${it.detail}") }
    }

    fun toJson(): String {
        val arr = JSONArray()
        for (c in checks) {
            val obj = JSONObject()
            obj.put("name", c.name)
            obj.put("outcome", c.outcome.name)
            obj.put("detail", c.detail)
            arr.put(obj)
        }
        return arr.toString()
    }

    companion object {
        fun fromJson(json: String): MechanicalCheckResult {
            val checks = mutableListOf<MechanicalCheck>()
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val outcome = try {
                        MechanicalOutcome.valueOf(obj.optString("outcome", "NOT_RUN"))
                    } catch (_: Exception) {
                        MechanicalOutcome.NOT_RUN
                    }
                    checks += MechanicalCheck(
                        name = obj.optString("name", ""),
                        outcome = outcome,
                        detail = obj.optString("detail", ""),
                    )
                }
            } catch (_: Exception) {
                // 坏 JSON 不抛：当成一次空检查（调用方看得到 checks 为空）。
            }
            return MechanicalCheckResult(checks)
        }
    }
}
