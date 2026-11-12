package wherevercode.agent.core.task

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * P1 确定性自动派活——eval 日志 + 路由决策日志（2026-11-11）。
 *
 * 每完成一个任务记一条：任务类型、Agent/设备、单 Agent vs 流水线、
 * 耗时、返工轮次、成败、用户 verdict。约 20 条就能判断 P2 流水线
 * 有没有用（调研报告 P1 / 老项目对照 P1 的 eval 对照）。
 *
 * 两条硬线（老项目规则，照做）：
 * - 记分板是只读聚合：[EvalScorecard] 只从日志算展示数字，
 *   路由打分绝不读它（回归测试锁定）。
 * - 返工轮次是下界代理：`max(0, attempt - 1)`——泵认领不 bump
 *   attempt，精确轮次等 P0 事件日志落地后再算；此处不编造。
 *
 * 存储：SharedPreferences JSON（诊断性日志，200 条封顶；若以后
 * 长成 UI 功能再迁 Room）。不碰 TaskDatabase——P0 工人正在动
 * 任务状态机，本分支不抢它的文件。
 */
enum class EvalOutcome {
    COMPLETED,
    FAILED,
    CANCELLED,
}

enum class UserVerdict {
    NONE,
    APPROVED,
    CHANGES_REQUESTED,
}

data class EvalLogEntry(
    val taskId: String,
    val taskType: TaskType,
    val agentId: String,
    val deviceId: String,
    /** "single"（单 Agent）| "pipeline"（P2 流水线落地后填）。 */
    val mode: String,
    val startedAtMs: Long?,
    val finishedAtMs: Long,
    /** 执行耗时（毫秒）；开始时间未知时为 null（不编造）。 */
    val wallClockMs: Long?,
    /** 返工轮次（attempt-1 的下界代理，见文件头注释）。 */
    val reworkRounds: Int,
    val attempt: Int,
    val outcome: EvalOutcome,
    val userVerdict: UserVerdict = UserVerdict.NONE,
    val routingDecisionId: String? = null,
    /** "suggested"（点了建议行）| "manual" | "local" | "cloud"。 */
    val assignedVia: String,
)

interface EvalLogStore {
    fun record(entry: EvalLogEntry)
    fun listEntries(): List<EvalLogEntry>
    /** 用户在审查里批了（批准/打回）才调；此前 verdict 一律 NONE，不猜。 */
    fun recordVerdict(taskId: String, verdict: UserVerdict)
}

class InMemoryEvalLogStore : EvalLogStore {
    private val entries = mutableListOf<EvalLogEntry>()

    @Synchronized
    override fun record(entry: EvalLogEntry) {
        entries.removeAll { it.taskId == entry.taskId }
        entries += entry
        while (entries.size > EvalLog.MAX_ENTRIES) entries.removeAt(0)
    }

    @Synchronized
    override fun listEntries(): List<EvalLogEntry> = entries.toList()

    @Synchronized
    override fun recordVerdict(taskId: String, verdict: UserVerdict) {
        val index = entries.indexOfFirst { it.taskId == taskId }
        if (index >= 0) entries[index] = entries[index].copy(userVerdict = verdict)
    }
}

class SharedPreferencesEvalLogStore(context: Context) : EvalLogStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    override fun record(entry: EvalLogEntry) {
        val entries = listEntries().filter { it.taskId != entry.taskId } + entry
        persist(entries.takeLast(EvalLog.MAX_ENTRIES))
    }

    @Synchronized
    override fun listEntries(): List<EvalLogEntry> {
        val json = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { EvalLog.parseEntry(array.optJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    override fun recordVerdict(taskId: String, verdict: UserVerdict) {
        val entries = listEntries().map {
            if (it.taskId == taskId) it.copy(userVerdict = verdict) else it
        }
        persist(entries)
    }

    private fun persist(entries: List<EvalLogEntry>) {
        val array = JSONArray()
        entries.forEach { array.put(EvalLog.toJson(it)) }
        prefs.edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    companion object {
        const val PREFS_NAME: String = "wherevercode_eval_log"
        const val KEY_ENTRIES: String = "entries_json"
    }
}

object EvalLog {
    const val MAX_ENTRIES: Int = 200

    fun toJson(entry: EvalLogEntry): JSONObject {
        val obj = JSONObject()
        obj.put("taskId", entry.taskId)
        obj.put("taskType", entry.taskType.name)
        obj.put("agentId", entry.agentId)
        obj.put("deviceId", entry.deviceId)
        obj.put("mode", entry.mode)
        entry.startedAtMs?.let { obj.put("startedAtMs", it) }
        obj.put("finishedAtMs", entry.finishedAtMs)
        entry.wallClockMs?.let { obj.put("wallClockMs", it) }
        obj.put("reworkRounds", entry.reworkRounds)
        obj.put("attempt", entry.attempt)
        obj.put("outcome", entry.outcome.name)
        obj.put("userVerdict", entry.userVerdict.name)
        entry.routingDecisionId?.let { obj.put("routingDecisionId", it) }
        obj.put("assignedVia", entry.assignedVia)
        return obj
    }

    fun parseEntry(obj: JSONObject?): EvalLogEntry? {
        if (obj == null) return null
        return try {
            val taskType = try {
                TaskType.valueOf(obj.optString("taskType", "CODE"))
            } catch (_: Exception) {
                TaskType.CODE
            }
            val outcome = try {
                EvalOutcome.valueOf(obj.optString("outcome", "FAILED"))
            } catch (_: Exception) {
                EvalOutcome.FAILED
            }
            val verdict = try {
                UserVerdict.valueOf(obj.optString("userVerdict", "NONE"))
            } catch (_: Exception) {
                UserVerdict.NONE
            }
            EvalLogEntry(
                taskId = obj.optString("taskId", ""),
                taskType = taskType,
                agentId = obj.optString("agentId", ""),
                deviceId = obj.optString("deviceId", ""),
                mode = obj.optString("mode", "single"),
                startedAtMs = if (obj.has("startedAtMs")) obj.optLong("startedAtMs") else null,
                finishedAtMs = obj.optLong("finishedAtMs", 0),
                wallClockMs = if (obj.has("wallClockMs")) obj.optLong("wallClockMs") else null,
                reworkRounds = obj.optInt("reworkRounds", 0),
                attempt = obj.optInt("attempt", 1),
                outcome = outcome,
                userVerdict = verdict,
                routingDecisionId = obj.optString("routingDecisionId", "").ifBlank { null },
                assignedVia = obj.optString("assignedVia", "manual"),
            ).takeIf { it.taskId.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * 终态记录器：无状态薄壳（store + 可选路由日志）。
 * 调用方（LocalTaskRunner / RemoteTaskPump / ChatViewModel.finishManusTaskRow）
 * 在任务落终态后调，失败自己吞——eval 记录绝不影响任务终态。
 */
class EvalLogRecorder(
    private val store: EvalLogStore,
    private val routingLog: RoutingDecisionLog? = null,
) {
    /**
     * @param assignedViaFallback 路由日志里没找到该任务的决策时填：
     * 本地执行传 "local"，远端泵传 "manual"，Manus 传 "cloud"。
     * 找到决策且决策首选就是本任务的 Agent 时记 "suggested"。
     */
    fun recordTerminal(
        task: TaskRecord,
        outcome: EvalOutcome,
        mode: String = "single",
        startedAtMs: Long? = null,
        deviceId: String = "",
        assignedViaFallback: String = "manual",
    ): EvalLogEntry? {
        return try {
            val finishedAtMs = System.currentTimeMillis()
            val decision = routingLog?.decisionForTask(task.taskId)
            val (assignedVia, decisionId) =
                if (decision != null && decision.chosenAgentId == task.assignedAgentId) {
                    "suggested" to decision.decisionId
                } else if (decision != null) {
                    "manual" to decision.decisionId
                } else {
                    assignedViaFallback to null
                }
            val entry = EvalLogEntry(
                taskId = task.taskId,
                taskType = classifyTaskType(task.title, task.description),
                agentId = task.assignedAgentId ?: "local",
                deviceId = deviceId,
                mode = mode,
                startedAtMs = startedAtMs,
                finishedAtMs = finishedAtMs,
                wallClockMs = startedAtMs?.let { (finishedAtMs - it).takeIf { diff -> diff >= 0 } },
                reworkRounds = (task.attempt - 1).coerceAtLeast(0),
                attempt = task.attempt,
                outcome = outcome,
                routingDecisionId = decisionId,
                assignedVia = assignedVia,
            )
            store.record(entry)
            entry
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * 记分板：只读聚合（展示 + 冷却统计用），明确不参与派单。
 * 成功率 = completed / total（total 含失败与取消；0 条时为 null，不写 0% 冒充）。
 */
object EvalScorecard {
    data class Summary(
        val taskType: TaskType,
        val total: Int,
        val completed: Int,
        val failed: Int,
        val cancelled: Int,
        val successRate: Double?,
        val avgWallClockMs: Long?,
        val avgReworkRounds: Double,
    )

    fun summarize(entries: List<EvalLogEntry>): Map<String, Map<TaskType, Summary>> {
        return entries.groupBy { it.agentId }.mapValues { (_, agentEntries) ->
            agentEntries.groupBy { it.taskType }.mapValues { (type, typeEntries) ->
                val completed = typeEntries.count { it.outcome == EvalOutcome.COMPLETED }
                val failed = typeEntries.count { it.outcome == EvalOutcome.FAILED }
                val cancelled = typeEntries.count { it.outcome == EvalOutcome.CANCELLED }
                val walls = typeEntries.mapNotNull { it.wallClockMs }
                Summary(
                    taskType = type,
                    total = typeEntries.size,
                    completed = completed,
                    failed = failed,
                    cancelled = cancelled,
                    successRate = if (typeEntries.isEmpty()) null else completed.toDouble() / typeEntries.size,
                    avgWallClockMs = if (walls.isEmpty()) null else walls.sum() / walls.size,
                    avgReworkRounds = if (typeEntries.isEmpty()) 0.0 else typeEntries.sumOf { it.reworkRounds }.toDouble() / typeEntries.size,
                )
            }
        }
    }

    /** 能力卡展示用：agentId → taskType 名 → 成功率（0 条的任务类型不出现）。 */
    fun successRateByTaskType(entries: List<EvalLogEntry>): Map<String, Map<String, Double>> {
        return summarize(entries).mapValues { (_, byType) ->
            byType.mapNotNull { (type, summary) ->
                summary.successRate?.let { type.name to it }
            }.toMap()
        }
    }

    /**
     * 连续失败统计（供路由冷却用）：按完成时间倒序，数到第一个成功为止
     * 的失败数；取消不计数也不清零（取消不是失败也不是成功）。
     */
    fun consecutiveFailures(
        entries: List<EvalLogEntry>,
        nowEpochSeconds: Long,
    ): Map<String, ConsecutiveFailures> {
        val out = mutableMapOf<String, ConsecutiveFailures>()
        for ((agentId, agentEntries) in entries.groupBy { it.agentId }) {
            var count = 0
            var lastFailureAt = 0L
            for (entry in agentEntries.sortedByDescending { it.finishedAtMs }) {
                when (entry.outcome) {
                    EvalOutcome.COMPLETED -> break
                    EvalOutcome.FAILED -> {
                        count++
                        if (lastFailureAt == 0L) lastFailureAt = entry.finishedAtMs / 1000
                    }
                    EvalOutcome.CANCELLED -> Unit
                }
            }
            if (count > 0) out[agentId] = ConsecutiveFailures(count, lastFailureAt)
        }
        return out
    }
}

/**
 * 路由决策日志：每次路由（建议行算出时）记一条，派单后由
 * [attachTask] 把决策与任务绑起来，供 eval 回看“建议准不准”。
 * 200 条封顶，SharedPreferences JSON。
 */
interface RoutingDecisionLog {
    fun record(decision: RoutingDecision)
    fun attachTask(decisionId: String, taskId: String)
    fun decisionForTask(taskId: String): RoutingDecision?
    fun listDecisions(): List<RoutingDecision>
}

class InMemoryRoutingDecisionLog : RoutingDecisionLog {
    private val decisions = mutableListOf<RoutingDecision>()

    @Synchronized
    override fun record(decision: RoutingDecision) {
        decisions.removeAll { it.decisionId == decision.decisionId }
        decisions += decision
        while (decisions.size > EvalLog.MAX_ENTRIES) decisions.removeAt(0)
    }

    @Synchronized
    override fun attachTask(decisionId: String, taskId: String) {
        val index = decisions.indexOfFirst { it.decisionId == decisionId }
        if (index >= 0) decisions[index] = decisions[index].copy(assignedTaskId = taskId)
    }

    @Synchronized
    override fun decisionForTask(taskId: String): RoutingDecision? =
        decisions.firstOrNull { it.assignedTaskId == taskId }

    @Synchronized
    override fun listDecisions(): List<RoutingDecision> = decisions.toList()
}

class SharedPreferencesRoutingDecisionLog(context: Context) : RoutingDecisionLog {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    override fun record(decision: RoutingDecision) {
        val decisions = listDecisions().filter { it.decisionId != decision.decisionId } + decision
        persist(decisions.takeLast(EvalLog.MAX_ENTRIES))
    }

    @Synchronized
    override fun attachTask(decisionId: String, taskId: String) {
        val decisions = listDecisions().map {
            if (it.decisionId == decisionId) it.copy(assignedTaskId = taskId) else it
        }
        persist(decisions)
    }

    @Synchronized
    override fun decisionForTask(taskId: String): RoutingDecision? =
        listDecisions().firstOrNull { it.assignedTaskId == taskId }

    @Synchronized
    override fun listDecisions(): List<RoutingDecision> {
        val json = prefs.getString(KEY_DECISIONS, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { parseDecision(array.optJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persist(decisions: List<RoutingDecision>) {
        val array = JSONArray()
        decisions.forEach { array.put(toJson(it)) }
        prefs.edit().putString(KEY_DECISIONS, array.toString()).apply()
    }

    private fun toJson(decision: RoutingDecision): JSONObject {
        val obj = JSONObject()
        obj.put("decisionId", decision.decisionId)
        obj.put("createdAtEpochSeconds", decision.createdAtEpochSeconds)
        decision.projectId?.let { obj.put("projectId", it) }
        obj.put("taskType", decision.taskType.name)
        decision.pinnedAgentId?.let { obj.put("pinnedAgentId", it) }
        val candidates = JSONArray()
        for (candidate in decision.candidates) {
            val c = JSONObject()
            c.put("agentId", candidate.agentId)
            c.put("score", candidate.score)
            val breakdown = JSONObject()
            candidate.breakdown.forEach { (key, value) -> breakdown.put(key, value) }
            c.put("breakdown", breakdown)
            candidate.excludedReason?.let { c.put("excludedReason", it) }
            candidates.put(c)
        }
        obj.put("candidates", candidates)
        decision.chosenAgentId?.let { obj.put("chosenAgentId", it) }
        obj.put("reason", decision.reason)
        decision.repoHint?.let { obj.put("repoHint", it) }
        decision.assignedTaskId?.let { obj.put("assignedTaskId", it) }
        decision.triage?.let { triage ->
            val t = JSONObject()
            t.put("suggestedMode", triage.suggestedMode.name)
            t.put("reason", triage.reason)
            t.put("modelLabel", triage.modelLabel)
            t.put("latencyMs", triage.latencyMs)
            obj.put("triage", t)
        }
        return obj
    }

    private fun parseDecision(obj: JSONObject?): RoutingDecision? {
        if (obj == null) return null
        return try {
            val taskType = try {
                TaskType.valueOf(obj.optString("taskType", "CODE"))
            } catch (_: Exception) {
                TaskType.CODE
            }
            val candidates = mutableListOf<CandidateScore>()
            val arr = obj.optJSONArray("candidates")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    val breakdown = mutableMapOf<String, Int>()
                    val b = c.optJSONObject("breakdown")
                    if (b != null) {
                        val keys = b.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            breakdown[key] = b.optInt(key, 0)
                        }
                    }
                    candidates += CandidateScore(
                        agentId = c.optString("agentId", ""),
                        score = c.optInt("score", 0),
                        breakdown = breakdown,
                        excludedReason = c.optString("excludedReason", "").ifBlank { null },
                    )
                }
            }
            RoutingDecision(
                decisionId = obj.optString("decisionId", ""),
                createdAtEpochSeconds = obj.optLong("createdAtEpochSeconds", 0),
                projectId = obj.optString("projectId", "").ifBlank { null },
                taskType = taskType,
                pinnedAgentId = obj.optString("pinnedAgentId", "").ifBlank { null },
                candidates = candidates,
                chosenAgentId = obj.optString("chosenAgentId", "").ifBlank { null },
                reason = obj.optString("reason", ""),
                repoHint = obj.optString("repoHint", "").ifBlank { null },
                assignedTaskId = obj.optString("assignedTaskId", "").ifBlank { null },
                triage = parseTriage(obj.optJSONObject("triage")),
            ).takeIf { it.decisionId.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 分流记录解析（端侧小模型路由 v1）：旧条目没有 triage 键
     * （optJSONObject 为 null）→ null；字段缺失 / 模式名非法
     * → null——解析失败只丢分流记录，不丢整条决策。
     */
    private fun parseTriage(obj: JSONObject?): TriageRecord? {
        if (obj == null) return null
        return try {
            val mode = TriageMode.valueOf(obj.optString("suggestedMode", ""))
            TriageRecord(
                suggestedMode = mode,
                reason = obj.optString("reason", ""),
                modelLabel = obj.optString("modelLabel", ""),
                latencyMs = obj.optLong("latencyMs", 0),
            )
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val PREFS_NAME: String = "wherevercode_routing_log"
        const val KEY_DECISIONS: String = "decisions_json"
    }
}
