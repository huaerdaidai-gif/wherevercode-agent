package wherevercode.agent.core.dispatch

import org.json.JSONArray
import org.json.JSONObject

/** 一个 MCP 工具定义；[capability] 决定谁能在 tools/list 里看到、能调用它。 */
data class DispatchToolDef(
    val name: String,
    val description: String,
    val capability: DispatchCapability,
    val inputSchema: JSONObject,
    /** 会改状态的工具：接受 idempotency_key。 */
    val mutating: Boolean,
    /** 必须带 idempotency_key（重复请求会造成重复副作用的工具）。 */
    val requiresIdempotencyKey: Boolean = false,
)

object DispatchTools {
    const val CREATE_TASK = "create_task"
    const val ASSIGN_TASK = "assign_task"
    const val GET_TASK_STATUS = "get_task_status"
    const val PAUSE_TASK = "pause_task"
    const val RESUME_TASK = "resume_task"
    const val STOP_TASK = "stop_task"
    const val CHANGE_GOAL = "change_goal"
    const val LIST_MY_TASKS = "list_my_tasks"
    const val CLAIM_TASK = "claim_task"
    const val REPORT_PROGRESS = "report_progress"
    const val SUBMIT_RESULT = "submit_result"

    private fun str(desc: String, maxLength: Int? = null) = JSONObject().apply {
        put("type", "string"); put("description", desc)
        if (maxLength != null) put("maxLength", maxLength)
    }

    private fun int(desc: String, min: Int? = null, max: Int? = null) = JSONObject().apply {
        put("type", "integer"); put("description", desc)
        if (min != null) put("minimum", min)
        if (max != null) put("maximum", max)
    }

    private fun enumStr(desc: String, vararg values: String) = JSONObject().apply {
        put("type", "string"); put("description", desc); put("enum", JSONArray(values.toList()))
    }

    private fun schema(required: List<String>, vararg props: Pair<String, JSONObject>) = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
        put("required", JSONArray(required))
        put("additionalProperties", false)
    }

    private val TASK_ID = "task_id" to str("TaskStore 任务 id（create_task 返回）", 128)
    private val IDEM = "idempotency_key" to str("调用方生成的幂等键（8–128 位 [A-Za-z0-9._:-]）；同键重试只执行一次", 128)
    private val EXPECTED_VERSION = "expected_version" to int("乐观并发：任务当前 version；不一致返回 version_conflict", 1)
    private val REASON = "reason" to str("原因（进审计，已脱敏）", 500)
    private val ATTEMPT_ID = "attempt_id" to str("claim_task 返回的 attempt_id；重新认领后旧 attempt 的写入一律拒绝", 64)

    val ALL: List<DispatchToolDef> = listOf(
        DispatchToolDef(
            CREATE_TASK,
            "创建一个正式任务（落在 App 的 TaskStore）。只有 owner 或具备 dispatch 能力的 Agent 可调用；频道消息不会自动建任务。",
            DispatchCapability.DISPATCH,
            schema(
                listOf("title", "goal", "idempotency_key"),
                "title" to str("任务标题", 200),
                "goal" to str("任务目标/说明", 8000),
                "project_id" to str("可选：App 项目 id", 128),
                "priority" to int("可选：优先级，越大越先", -100, 100),
                "assignee" to str("可选：直接指派的执行方 principal id", 64),
                IDEM,
            ),
            mutating = true, requiresIdempotencyKey = true,
        ),
        DispatchToolDef(
            ASSIGN_TASK,
            "把一个未在执行中的任务指派给已登记的执行方（agent/device）。",
            DispatchCapability.DISPATCH,
            schema(listOf("task_id", "agent_id"), TASK_ID, "agent_id" to str("执行方 principal id", 64), EXPECTED_VERSION, IDEM),
            mutating = true,
        ),
        DispatchToolDef(
            GET_TASK_STATUS,
            "查询任务状态、控制状态、最近进度与结果。派工方只能查自己建的任务，执行方只能查指派给自己的任务。",
            DispatchCapability.DISPATCH,
            schema(listOf("task_id"), TASK_ID),
            mutating = false,
        ),
        DispatchToolDef(
            PAUSE_TASK, "暂停任务（执行中则等待执行方确认）。", DispatchCapability.DISPATCH,
            schema(listOf("task_id"), TASK_ID, REASON, IDEM), mutating = true,
        ),
        DispatchToolDef(
            RESUME_TASK, "恢复已暂停的任务。", DispatchCapability.DISPATCH,
            schema(listOf("task_id"), TASK_ID, REASON, IDEM), mutating = true,
        ),
        DispatchToolDef(
            STOP_TASK,
            "停止任务。停止后该任务的进度、结果和任何写入一律拒绝；执行中则等待执行方确认后落 cancelled。",
            DispatchCapability.DISPATCH,
            schema(listOf("task_id"), TASK_ID, REASON, IDEM), mutating = true,
        ),
        DispatchToolDef(
            CHANGE_GOAL,
            "修改任务目标（goal_version+1）。执行方之后带旧 goal_version 的写入返回 goal_changed。",
            DispatchCapability.DISPATCH,
            schema(listOf("task_id", "goal"), TASK_ID, "goal" to str("新目标", 8000), EXPECTED_VERSION, REASON, IDEM),
            mutating = true,
        ),
        DispatchToolDef(
            LIST_MY_TASKS, "列出指派给我、尚未结束的任务。", DispatchCapability.EXECUTE,
            schema(emptyList()), mutating = false,
        ),
        DispatchToolDef(
            CLAIM_TASK,
            "认领一个指派给我的任务，开始执行，返回 attempt_id 与 goal_version。已由我认领的任务再次调用会返回当前 attempt（断线恢复）。",
            DispatchCapability.EXECUTE,
            schema(listOf("task_id"), TASK_ID, IDEM), mutating = true,
        ),
        DispatchToolDef(
            REPORT_PROGRESS,
            "汇报进度并续租。ack=paused/stopped 用于确认已按暂停/停止指令停下。",
            DispatchCapability.EXECUTE,
            schema(
                listOf("task_id", "attempt_id"),
                TASK_ID, ATTEMPT_ID,
                "message" to str("进度说明（已脱敏）", 2000),
                "percent" to int("0–100", 0, 100),
                "goal_version" to int("我当前执行的 goal_version", 1),
                "ack" to enumStr("确认已停下", "paused", "stopped"),
                IDEM,
            ),
            mutating = true,
        ),
        DispatchToolDef(
            SUBMIT_RESULT,
            "交付结果并结束任务（completed 或 failed）。必须带当前 goal_version。",
            DispatchCapability.EXECUTE,
            schema(
                listOf("task_id", "attempt_id", "goal_version", "result"),
                TASK_ID, ATTEMPT_ID,
                "goal_version" to int("我执行的 goal_version，须等于当前值", 1),
                "status" to enumStr("默认 completed", "completed", "failed"),
                "result" to str("结果说明 / commit / 测试结果（已脱敏）", 20000),
                EXPECTED_VERSION, IDEM,
            ),
            mutating = true,
        ),
    )

    val BY_NAME: Map<String, DispatchToolDef> = ALL.associateBy { it.name }

    /** get_task_status 对执行方也开放（只限指派给自己的任务）。 */
    fun visibleTo(principal: DispatchPrincipal): List<DispatchToolDef> =
        ALL.filter { principal.can(it.capability) || (it.name == GET_TASK_STATUS && principal.can(DispatchCapability.EXECUTE)) }
}
