package wherevercode.agent.core.task

import org.json.JSONArray
import org.json.JSONObject

/**
* 任务包（P0 耐久任务引擎，2026-11-11）：跨设备交接契约，versioned。
*
* 一次分派只传任务包，不传聊天记录：本地执行器（大脑轮次）与远端
* 执行器（Hand 上的 CLI）收到的都是同一份包渲染的文本。包里只写
* 交接时已知的事实，未知的字段是 null / 空清单，绝不编：
* - `expectedOutput` / `acceptanceCriteria`：v1 为空——任务行里今天
* 没有这些字段；结构化创建（dispatch_task 带验收标准）是后续项。
* - `repo` / `baseCommit`：v1 为 null——执行器只知道项目 id，仓库 +
* commit 由调用方以后补；执行器在任务的项目家目录里干活。
* - `filesInScope`：v1 为空 = 整个项目家（不限文件）。
* - `branch`：`task/<taskId>`，老项目一任务一分支的命名规则；分支
* 创建是执行器的活，回传的交付记录（MemberDeliveryRecord）带同
* 一分支/commit，与包对得上。
* - `attempt` / `attemptId` / `leaseSeconds` / `leaseExpiresAt`：
* 认领时定的围栏令牌与租约；执行器迟到的终态写按 attemptId 拒绝。
*
* 版本：`version` 恒为 [CURRENT_VERSION]；`fromJson` 遇到未知版本
* 直接抛错，不猜、不兼容旧格式（包是调度器内部契约，不是公开 API）。
*/
data class TaskPacket(
val version: Int = CURRENT_VERSION,
val taskId: String,
val attempt: Int,
val attemptId: String?,
val objective: String,
val expectedOutput: String? = null,
val acceptanceCriteria: List<String> = emptyList(),
val filesInScope: List<String> = emptyList(),
val branch: String,
val repo: String? = null,
val baseCommit: String? = null,
/**
 * V5 跨设备开发（2026-11-12，A4）：信息性字段，只说明本次执行
 * 的工作目录，不参与租约/幂等/版本语义；版本保持 1。folder =
 * 实际 cwd（项目检出文件夹或回落的 Agent 文件夹）；folderSource
 * = "project" / "agent_fallback"（见 DispatchFolderSource 小写）。
 * 执行不需要包里再带 folder（folder 已走信封/凭证），这里只为
 * 让执行器 prompt 与审查如实看到 cwd。
 */
val folder: String? = null,
val folderSource: String? = null,
val constraints: List<String> = emptyList(),
val leaseSeconds: Long,
val leaseExpiresAt: Long?,
) {
companion object {
const val CURRENT_VERSION: Int = 1

/** 从任务行建包：目标取 description（无则 title）；分支按规则生成。 */
fun fromTask(
task: TaskRecord,
repo: String? = null,
baseCommit: String? = null,
folder: String? = null,
folderSource: String? = null,
): TaskPacket {
val constraints = mutableListOf<String>()
if (task.priority > 0) constraints += "优先级 ${task.priority}"
if (task.dependencies.isNotEmpty()) {
constraints += "依赖任务先完成：${task.dependencies.joinToString("、")}"
}
return TaskPacket(
taskId = task.taskId,
attempt = task.attempt,
attemptId = task.attemptId,
objective = task.description?.takeIf { it.isNotBlank()}?: task.title,
branch = "task/${task.taskId}",
repo = repo,
baseCommit = baseCommit,
folder = folder,
folderSource = folderSource,
constraints = constraints,
leaseSeconds = RoomTaskStore.LEASE_SECONDS,
leaseExpiresAt = task.leaseExpiresAt,
)
}

fun fromJson(json: String): TaskPacket {
val obj = JSONObject(json)
val version = obj.optInt("version", -1)
require(version == CURRENT_VERSION) { "未知任务包版本：$version"}
return TaskPacket(
version = version,
taskId = obj.getString("task_id"),
attempt = obj.optInt("attempt", 0),
attemptId = obj.optString("attempt_id").takeIf { it.isNotEmpty()},
objective = obj.getString("objective"),
expectedOutput = obj.optString("expected_output").takeIf { it.isNotEmpty()},
acceptanceCriteria = obj.optJSONArray("acceptance_criteria")?.toStringList()
?: emptyList(),
filesInScope = obj.optJSONArray("files_in_scope")?.toStringList()
?: emptyList(),
branch = obj.getString("branch"),
repo = obj.optString("repo").takeIf { it.isNotEmpty()},
baseCommit = obj.optString("base_commit").takeIf { it.isNotEmpty()},
folder = obj.optString("folder").takeIf { it.isNotEmpty()},
folderSource = obj.optString("folder_source").takeIf { it.isNotEmpty()},
constraints = obj.optJSONArray("constraints")?.toStringList()?: emptyList(),
leaseSeconds = obj.optLong("lease_seconds", RoomTaskStore.LEASE_SECONDS),
leaseExpiresAt = if (obj.has("lease_expires_at") &&!obj.isNull("lease_expires_at")) {
obj.optLong("lease_expires_at")
} else {
null
},
)
}

private fun JSONArray.toStringList(): List<String> {
val out = mutableListOf<String>()
for (i in 0 until length()) {
val s = optString(i, "")
if (s.isNotEmpty()) out += s
}
return out
}
}

fun toJson(): String {
val obj = JSONObject()
obj.put("version", version)
obj.put("task_id", taskId)
obj.put("attempt", attempt)
if (attemptId!= null) obj.put("attempt_id", attemptId)
obj.put("objective", objective)
if (expectedOutput!= null) obj.put("expected_output", expectedOutput)
obj.put("acceptance_criteria", JSONArray(acceptanceCriteria))
obj.put("files_in_scope", JSONArray(filesInScope))
obj.put("branch", branch)
if (repo!= null) obj.put("repo", repo)
if (baseCommit!= null) obj.put("base_commit", baseCommit)
if (folder!= null) obj.put("folder", folder)
if (folderSource!= null) obj.put("folder_source", folderSource)
obj.put("constraints", JSONArray(constraints))
obj.put("lease_seconds", leaseSeconds)
if (leaseExpiresAt!= null) obj.put("lease_expires_at", leaseExpiresAt)
return obj.toString()
}

/**
* 渲染给执行器的文本：目标原文在前、一字未改；契约段落在后。
* 无验收标准 / 无文件范围 / 无仓库信息时写“未指定”，不编。
*/
fun toPromptText(): String = buildString {
append(objective)
append("\n\n")
append("\n任务：$taskId · 第 $attempt 次认领")
attemptId?.let { append("（attemptId：$it）")}
append("\n租约：$leaseSeconds 秒")
leaseExpiresAt?.let { append("，过期 $it（过期未续租会被回队重派）")}
append("\n分支：在 $branch 分支上干活（一个任务一个分支）")
append("\n文件范围：${if (filesInScope.isEmpty()) "未指定（整个项目家）" else filesInScope.joinToString("、")}")
append("\n验收标准：${if (acceptanceCriteria.isEmpty()) "未指定（按任务目标验收）" else acceptanceCriteria.joinToString("；")}")
expectedOutput?.let { append("\n预期输出：$it")}
repo?.let { append("\n仓库：$it")}
baseCommit?.let { append("\n基底 commit：$it")}
folder?.let {
    val sourceLabel = when (folderSource) {
        "project" -> "项目检出"
        "agent_fallback" -> "Agent 文件夹（回落）"
        else -> null
    }
    append("\n工作目录：$it")
    sourceLabel?.let { label -> append("（$label）") }
}
if (constraints.isNotEmpty()) append("\n约束：${constraints.joinToString("；")}")
}
}

/**
* P2：plan 批准后，把 plan 的验收标准、文件清单、预期输出填进给
* 编码者的包里（P0 v1 的空位）。plan 为空的字段不覆盖包里已有的值；
* 未批准的 plan 不进包——调用方只传已批准的 plan。
*/
fun TaskPacket.withPlan(plan: PlanArtifact): TaskPacket = copy(
expectedOutput = plan.expectedOutput ?: expectedOutput,
acceptanceCriteria = plan.acceptanceCriteria.ifEmpty { acceptanceCriteria },
filesInScope = plan.filesInScope.ifEmpty { filesInScope },
)
