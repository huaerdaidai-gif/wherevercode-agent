package wherevercode.agent.brain

import org.json.JSONObject

/**
 * Planner: task planning -> structured actions (stage 2, audit section 4.7).
 *
 * The Planner PRODUCES plans; it never executes. Execution belongs to the
 * BrainEngine + ToolRegistry validation flow (audit section 4.6).
 *
 * Plan generation itself comes from the brain (the brain outputs a JSON
 * plan); this file validates and structures that output:
 * - every step references a registered tool,
 * - every step carries an idempotency key in `task_id:seq` form, so the
 *   same action is never executed twice (same idea as the cross-device
 *   SequenceStore anti-replay),
 * - risks are carried as plain strings for the user to see.
 */
data class Action(
    val actionId: String,
    val tool: String,
    val args: Map<String, Any?> = emptyMap(),
    val requiresApproval: Boolean = false,
    val idempotencyKey: String,
)

data class Plan(
    val goal: String,
    val steps: List<Action>,
    val risks: List<String> = emptyList(),
)

interface Planner {
    /**
     * Parse + validate a brain-produced JSON plan.
     * Returns null when the JSON is not a plan at all; returns a Plan
     * with validation errors reported separately via [PlanValidator].
     */
    fun parsePlanJson(json: String, taskId: String): Plan?
}

object PlanValidator {
    private val IDEMPOTENCY_KEY_REGEX = Regex("^[^:]+:[0-9]+$")

    fun idempotencyKey(taskId: String, seq: Int): String = "$taskId:$seq"

    fun isValidIdempotencyKey(key: String): Boolean = IDEMPOTENCY_KEY_REGEX.matches(key)

    /**
     * Returns a list of validation errors; empty means the plan is valid.
     * Checks: goal non-blank, at least one step, every step's tool is
     * registered, every idempotency key is `task_id:seq` for this task,
     * no duplicate idempotency keys.
     */
    fun validate(plan: Plan, registeredTools: Set<String>, taskId: String): List<String> {
        val errors = mutableListOf<String>()
        if (plan.goal.isBlank()) errors += "目标为空"
        if (plan.steps.isEmpty()) errors += "计划没有步骤"
        val seenKeys = mutableSetOf<String>()
        plan.steps.forEachIndexed { index, step ->
            if (step.tool !in registeredTools) {
                errors += "步骤 ${index + 1}：未知工具 ${step.tool}"
            }
            if (!isValidIdempotencyKey(step.idempotencyKey)) {
                errors += "步骤 ${index + 1}：幂等键格式错误 ${step.idempotencyKey}（应为 task_id:seq）"
            } else if (!step.idempotencyKey.startsWith("$taskId:")) {
                errors += "步骤 ${index + 1}：幂等键不属于本任务 ${step.idempotencyKey}"
            }
            if (!seenKeys.add(step.idempotencyKey)) {
                errors += "步骤 ${index + 1}：幂等键重复 ${step.idempotencyKey}"
            }
        }
        return errors
    }
}

/** Parses the brain's JSON plan shape: {"goal","steps":[{"tool","args","requires_approval"}],"risks":[...]} */
class JsonPlanner : Planner {
    override fun parsePlanJson(json: String, taskId: String): Plan? {
        val obj = try {
            JSONObject(json)
        } catch (_: Exception) {
            return null
        }
        val goal = obj.optString("goal", "")
        val stepsArray = obj.optJSONArray("steps") ?: return null
        val steps = mutableListOf<Action>()
        for (i in 0 until stepsArray.length()) {
            val step = stepsArray.optJSONObject(i) ?: return null
            val tool = step.optString("tool", "")
            if (tool.isEmpty()) return null
            val argsObj = step.optJSONObject("args")
            val args: Map<String, Any?> = if (argsObj == null) {
                emptyMap()
            } else {
                StreamParser.jsonObjectToMap(argsObj)
            }
            val key = step.optString("idempotency_key", "")
                .ifEmpty { PlanValidator.idempotencyKey(taskId, i) }
            steps += Action(
                actionId = step.optString("action_id", "step-$i"),
                tool = tool,
                args = args,
                requiresApproval = step.optBoolean("requires_approval", false),
                idempotencyKey = key,
            )
        }
        val risks = mutableListOf<String>()
        val risksArray = obj.optJSONArray("risks")
        if (risksArray != null) {
            for (i in 0 until risksArray.length()) {
                risks += risksArray.optString(i, "")
            }
        }
        return Plan(goal = goal, steps = steps, risks = risks.filter { it.isNotBlank() })
    }
}
