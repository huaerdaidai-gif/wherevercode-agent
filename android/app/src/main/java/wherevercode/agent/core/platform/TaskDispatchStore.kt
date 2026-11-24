package wherevercode.agent.core.platform

import org.json.JSONArray
import org.json.JSONObject

/** 任务派工账本的持久化口：生产实现写 App 私有文件，测试用内存实现。 */
interface TaskDispatchPersistence {
    fun load(): String?
    fun save(text: String)
}

/** 内存持久化（测试与无文件环境兜底；进程退出即丢，只用于这两处）。 */
class InMemoryTaskDispatchPersistence : TaskDispatchPersistence {
    @Volatile
    var stored: String? = null
    override fun load(): String? = stored
    override fun save(text: String) {
        stored = text
    }
}

/**
 * 任务派工的内存状态（spec §1）。与本地频道账本同一持久化风格：
 * 状态全在内存、每次变更后整表快照落盘（体量小，快照写足够；
 * 格式与 iOS TaskDispatchStore 逐字段一致）。
 *
 * 集合语义：
 * - tasks：task_id -> 任务（含 review/pack 子任务，parent_id 关联），
 *   LinkedHashMap 保持创建顺序（夹具快照与列表排序都按它）
 * - events：全局账本（seq 单调递增），按 task_id 过滤读取
 * - deliveryPages：work 任务 id -> (page -> 页)；pack 子任务递的页
 *   也落在父 work 名下（基线口径）
 * - idempotency：键 "task_id|epoch|action|attempt_id" -> 首次结果
 *   的 JSON 文本（epoch 参与键，reopen 后旧 attempt 不再命中）
 * - statusReports：agent_id -> 额度报告（report_status 落点）
 */
class TaskDispatchStore(
    private val persistence: TaskDispatchPersistence,
    @Suppress("unused") private val clock: () -> Double,
) {
    val tasks = LinkedHashMap<String, TaskRecord>()
    val events = mutableListOf<TaskEventRecord>()
    val deliveryPages = LinkedHashMap<String, MutableMap<Int, DeliveryPageRecord>>()
    val idempotency = LinkedHashMap<String, String>()
    val statusReports = LinkedHashMap<String, AgentStatusRecord>()
    var eventSeq = 0L

    init {
        restore()
    }

    /** 追加一条账本事件（seq 在这里统一铸造，调用方不自带）。 */
    fun appendEvent(taskId: String, at: Double, who: String?, kind: String, text: String) {
        eventSeq += 1
        events += TaskEventRecord(eventSeq, taskId, at, who, kind, text)
    }

    fun eventsOf(taskId: String): List<TaskEventRecord> = events.filter { it.taskId == taskId }

    // ---- 快照 ------------------------------------------------------------

    fun persist() {
        persistence.save(snapshotJson().toString())
    }

    private fun restore() {
        val text = persistence.load() ?: return
        val root = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }
        tasks.clear()
        val taskArray = root.optJSONArray("tasks") ?: JSONArray()
        for (i in 0 until taskArray.length()) {
            val record = taskFromJson(taskArray.getJSONObject(i))
            tasks[record.taskId] = record
        }
        events.clear()
        val eventArray = root.optJSONArray("events") ?: JSONArray()
        for (i in 0 until eventArray.length()) {
            val e = eventArray.getJSONObject(i)
            events += TaskEventRecord(
                seq = e.optLong("seq"),
                taskId = e.optString("task_id"),
                at = e.optDouble("at"),
                who = if (e.isNull("who")) null else e.optString("who"),
                kind = e.optString("kind"),
                text = e.optString("text"),
            )
        }
        eventSeq = root.optLong("event_seq", events.size.toLong())
        deliveryPages.clear()
        val pages = root.optJSONObject("delivery_pages")
        if (pages != null) {
            val keys = pages.keys()
            while (keys.hasNext()) {
                val taskId = keys.next()
                val arr = pages.getJSONArray(taskId)
                val map = mutableMapOf<Int, DeliveryPageRecord>()
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i)
                    val record = DeliveryPageRecord(
                        page = p.optInt("page"),
                        totalPages = p.optInt("total_pages"),
                        text = p.optString("text"),
                    )
                    map[record.page] = record
                }
                deliveryPages[taskId] = map
            }
        }
        idempotency.clear()
        val idem = root.optJSONObject("idempotency")
        if (idem != null) {
            val keys = idem.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                idempotency[key] = idem.getString(key)
            }
        }
        statusReports.clear()
        val reports = root.optJSONObject("status_reports")
        if (reports != null) {
            val keys = reports.keys()
            while (keys.hasNext()) {
                val agentId = keys.next()
                val r = reports.getJSONObject(agentId)
                statusReports[agentId] = AgentStatusRecord(
                    quotaText = if (r.isNull("quota_text")) null else r.optString("quota_text"),
                    quotaResetAt = if (r.isNull("quota_reset_at")) null else r.optString("quota_reset_at"),
                    reportedAt = r.optDouble("reported_at"),
                )
            }
        }
    }

    private fun snapshotJson(): JSONObject {
        val root = JSONObject()
        root.put("version", 1)
        root.put("event_seq", eventSeq)
        root.put("tasks", JSONArray().apply { for (task in tasks.values) put(taskToJson(task)) })
        root.put("events", JSONArray().apply {
            for (e in events) {
                put(JSONObject().apply {
                    put("seq", e.seq)
                    put("task_id", e.taskId)
                    put("at", e.at)
                    put("who", e.who ?: JSONObject.NULL)
                    put("kind", e.kind)
                    put("text", e.text)
                })
            }
        })
        root.put("delivery_pages", JSONObject().apply {
            for ((taskId, pages) in deliveryPages) {
                put(taskId, JSONArray().apply {
                    for (page in pages.values.sortedBy { it.page }) {
                        put(JSONObject().apply {
                            put("page", page.page)
                            put("total_pages", page.totalPages)
                            put("text", page.text)
                        })
                    }
                })
            }
        })
        root.put("idempotency", JSONObject().apply {
            for ((key, value) in idempotency) put(key, value)
        })
        root.put("status_reports", JSONObject().apply {
            for ((agentId, report) in statusReports) {
                put(agentId, JSONObject().apply {
                    put("quota_text", report.quotaText ?: JSONObject.NULL)
                    put("quota_reset_at", report.quotaResetAt ?: JSONObject.NULL)
                    put("reported_at", report.reportedAt)
                })
            }
        })
        return root
    }

    companion object {
        fun taskToJson(task: TaskRecord): JSONObject = JSONObject().apply {
            put("task_id", task.taskId)
            put("project_id", task.projectId)
            put("kind", task.kind)
            put("parent_id", task.parentId ?: JSONObject.NULL)
            put("assignee_id", task.assigneeId)
            put("assignee_name", task.assigneeName)
            put("reviewer_id", task.reviewerId ?: JSONObject.NULL)
            put("reviewer_name", task.reviewerName ?: JSONObject.NULL)
            put("title", task.title)
            put("goal", task.goal)
            put("state", task.state)
            put("round", task.round)
            put("max_rounds", task.maxRounds)
            put("lease_until", task.leaseUntil ?: JSONObject.NULL)
            put("summary", task.summary ?: JSONObject.NULL)
            put("branch", task.branch ?: JSONObject.NULL)
            put("commit", task.commitSha ?: JSONObject.NULL)
            put("pr_url", task.prUrl ?: JSONObject.NULL)
            put("verdict", task.verdict ?: JSONObject.NULL)
            put("feedback", task.feedback ?: JSONObject.NULL)
            put("decision_reason", task.decisionReason ?: JSONObject.NULL)
            put("claimed_by_id", task.claimedById ?: JSONObject.NULL)
            put("claimed_by_name", task.claimedByName ?: JSONObject.NULL)
            put("reserved_for_id", task.reservedForId ?: JSONObject.NULL)
            put("reserved_until", task.reservedUntil ?: JSONObject.NULL)
            put("delivery_total_pages", task.deliveryTotalPages)
            put("delivery_pages", task.deliveryPages)
            put("delivery_truncated", task.deliveryTruncated)
            put("delivery_files", task.deliveryFiles ?: JSONObject.NULL)
            put("version", task.version)
            put("attempt", task.attempt)
            put("attempt_id", task.attemptId ?: JSONObject.NULL)
            put("channel_id", task.channelId ?: JSONObject.NULL)
            put("epoch", task.epoch)
            put("created_by", task.createdBy ?: JSONObject.NULL)
            put("deleted_at", task.deletedAt ?: JSONObject.NULL)
            put("deleted_by", task.deletedBy ?: JSONObject.NULL)
            put("created_at", task.createdAt)
            put("updated_at", task.updatedAt)
        }

        fun taskFromJson(o: JSONObject): TaskRecord {
            fun str(key: String): String? = if (o.isNull(key)) null else o.optString(key)
            fun dbl(key: String): Double? = if (o.isNull(key)) null else o.optDouble(key)
            return TaskRecord(
                taskId = o.optString("task_id"),
                projectId = o.optString("project_id"),
                kind = o.optString("kind"),
                parentId = str("parent_id"),
                assigneeId = o.optString("assignee_id"),
                assigneeName = o.optString("assignee_name"),
                reviewerId = str("reviewer_id"),
                reviewerName = str("reviewer_name"),
                title = o.optString("title"),
                goal = o.optString("goal"),
                state = o.optString("state"),
                round = o.optInt("round"),
                maxRounds = o.optInt("max_rounds"),
                leaseUntil = dbl("lease_until"),
                summary = str("summary"),
                branch = str("branch"),
                commitSha = str("commit"),
                prUrl = str("pr_url"),
                verdict = str("verdict"),
                feedback = str("feedback"),
                decisionReason = str("decision_reason"),
                claimedById = str("claimed_by_id"),
                claimedByName = str("claimed_by_name"),
                reservedForId = str("reserved_for_id"),
                reservedUntil = dbl("reserved_until"),
                deliveryTotalPages = o.optInt("delivery_total_pages"),
                deliveryPages = o.optInt("delivery_pages"),
                deliveryTruncated = o.optBoolean("delivery_truncated"),
                deliveryFiles = str("delivery_files"),
                version = o.optInt("version"),
                attempt = o.optInt("attempt"),
                attemptId = str("attempt_id"),
                channelId = str("channel_id"),
                epoch = o.optInt("epoch", 1),
                createdBy = str("created_by"),
                deletedAt = dbl("deleted_at"),
                deletedBy = str("deleted_by"),
                createdAt = o.optDouble("created_at"),
                updatedAt = o.optDouble("updated_at"),
            )
        }
    }
}
