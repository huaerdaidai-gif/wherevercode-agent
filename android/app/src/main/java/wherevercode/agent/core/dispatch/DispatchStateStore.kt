package wherevercode.agent.core.dispatch

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** 令牌记录：只存 SHA-256 哈希，明文令牌绝不落盘。 */
data class DispatchTokenRecord(
    val tokenId: String,
    val principalId: String,
    val role: DispatchRole,
    val capabilities: Set<DispatchCapability>,
    val hashHex: String,
    val label: String,
    val createdAt: Long,
    val revoked: Boolean = false,
    val projectIds: Set<String> = emptySet(),
)

/**
 * 派工元数据（不是第二个任务库）：只记“这条 TaskStore 任务是谁经
 * MCP 创建的”，用于任务级权限（派工 Agent 只能控自己建的任务）。
 * 任务本身的状态、目标、结果全在 TaskStore。
 */
data class DispatchTaskMeta(
    val taskId: String,
    val createdBy: String,
    val createdByRole: DispatchRole,
    val createdAt: Long,
)

/** 幂等记录：同一主体 + 工具 + idempotency_key 只执行一次。 */
data class IdempotencyRecord(
    val scopeKey: String,
    val fingerprint: String,
    val responseJson: String,
    val createdAt: Long,
)

/** 进度备注（已脱敏），get_task_status 展示最近若干条。 */
data class ProgressNote(
    val ts: Long,
    val actor: String,
    val attemptId: String?,
    val message: String,
    val percent: Int?,
)

interface DispatchStateStore {
    fun tokens(): List<DispatchTokenRecord>
    fun putToken(record: DispatchTokenRecord)
    fun taskMeta(taskId: String): DispatchTaskMeta?
    fun putTaskMeta(meta: DispatchTaskMeta)
    fun idempotency(scopeKey: String): IdempotencyRecord?
    fun putIdempotency(record: IdempotencyRecord)
    fun appendProgress(taskId: String, note: ProgressNote)
    fun progress(taskId: String): List<ProgressNote>

    companion object {
        const val MAX_PROGRESS_NOTES: Int = 20
        const val IDEMPOTENCY_TTL_SECONDS: Long = 24 * 3600
    }
}

open class InMemoryDispatchStateStore(
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : DispatchStateStore {
    protected val lock = Any()
    protected val tokenMap = LinkedHashMap<String, DispatchTokenRecord>()
    protected val metaMap = LinkedHashMap<String, DispatchTaskMeta>()
    protected val idemMap = LinkedHashMap<String, IdempotencyRecord>()
    protected val progressMap = LinkedHashMap<String, MutableList<ProgressNote>>()

    override fun tokens(): List<DispatchTokenRecord> = synchronized(lock) { tokenMap.values.toList() }

    override fun putToken(record: DispatchTokenRecord) {
        synchronized(lock) { tokenMap[record.tokenId] = record; persist() }
    }

    override fun taskMeta(taskId: String): DispatchTaskMeta? = synchronized(lock) { metaMap[taskId] }

    override fun putTaskMeta(meta: DispatchTaskMeta) {
        synchronized(lock) { metaMap[meta.taskId] = meta; persist() }
    }

    override fun idempotency(scopeKey: String): IdempotencyRecord? = synchronized(lock) {
        val record = idemMap[scopeKey] ?: return null
        if (nowEpochSeconds() - record.createdAt > DispatchStateStore.IDEMPOTENCY_TTL_SECONDS) {
            idemMap.remove(scopeKey)
            persist()
            return null
        }
        record
    }

    override fun putIdempotency(record: IdempotencyRecord) {
        synchronized(lock) {
            val cutoff = nowEpochSeconds() - DispatchStateStore.IDEMPOTENCY_TTL_SECONDS
            idemMap.entries.removeAll { it.value.createdAt < cutoff }
            idemMap[record.scopeKey] = record
            persist()
        }
    }

    override fun appendProgress(taskId: String, note: ProgressNote) {
        synchronized(lock) {
            val list = progressMap.getOrPut(taskId) { mutableListOf() }
            list += note
            while (list.size > DispatchStateStore.MAX_PROGRESS_NOTES) list.removeAt(0)
            persist()
        }
    }

    override fun progress(taskId: String): List<ProgressNote> =
        synchronized(lock) { progressMap[taskId]?.toList() ?: emptyList() }

    /** 子类落盘；内存版什么都不做。调用时已持有 [lock]。 */
    protected open fun persist() {}
}

/**
 * 文件版：整份状态存一个 JSON 文件，每次变更先写临时文件再原子改名，
 * App 重启后令牌哈希、任务归属、幂等记录、进度备注都还在。文件里没有
 * 任何明文令牌（只有哈希），放在 App 私有目录即可。
 */
class FileDispatchStateStore(
    private val file: File,
    nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : InMemoryDispatchStateStore(nowEpochSeconds) {
    init {
        synchronized(lock) { load() }
    }

    private fun load() {
        if (!file.exists()) return
        val root = JSONObject(file.readText(Charsets.UTF_8))
        root.optJSONArray("tokens")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val rec = DispatchTokenRecord(
                    tokenId = o.getString("token_id"),
                    principalId = o.getString("principal_id"),
                    role = DispatchRole.valueOf(o.getString("role")),
                    capabilities = o.getJSONArray("capabilities").let { c ->
                        (0 until c.length()).map { DispatchCapability.valueOf(c.getString(it)) }.toSet()
                    },
                    hashHex = o.getString("hash"),
                    label = o.optString("label", ""),
                    createdAt = o.getLong("created_at"),
                    revoked = o.optBoolean("revoked", false),
                    projectIds = o.optJSONArray("project_ids")?.let { ids ->
                        (0 until ids.length()).map { ids.getString(it) }.toSet()
                    } ?: emptySet(),
                )
                tokenMap[rec.tokenId] = rec
            }
        }
        root.optJSONArray("tasks")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val meta = DispatchTaskMeta(
                    taskId = o.getString("task_id"),
                    createdBy = o.getString("created_by"),
                    createdByRole = DispatchRole.valueOf(o.getString("created_by_role")),
                    createdAt = o.getLong("created_at"),
                )
                metaMap[meta.taskId] = meta
            }
        }
        root.optJSONArray("idempotency")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val rec = IdempotencyRecord(
                    scopeKey = o.getString("scope"),
                    fingerprint = o.getString("fingerprint"),
                    responseJson = o.getString("response"),
                    createdAt = o.getLong("created_at"),
                )
                idemMap[rec.scopeKey] = rec
            }
        }
        root.optJSONObject("progress")?.let { obj ->
            val keys = obj.keys()
            while (keys.hasNext()) {
                val taskId = keys.next()
                val arr = obj.getJSONArray(taskId)
                progressMap[taskId] = (0 until arr.length()).map { idx ->
                    val o = arr.getJSONObject(idx)
                    ProgressNote(
                        ts = o.getLong("ts"),
                        actor = o.getString("actor"),
                        attemptId = if (o.isNull("attempt_id")) null else o.optString("attempt_id"),
                        message = o.optString("message", ""),
                        percent = if (o.has("percent") && !o.isNull("percent")) o.getInt("percent") else null,
                    )
                }.toMutableList()
            }
        }
    }

    override fun persist() {
        val root = JSONObject()
        root.put("format", 1)
        root.put("tokens", JSONArray().apply {
            tokenMap.values.forEach { r ->
                put(JSONObject().apply {
                    put("token_id", r.tokenId)
                    put("principal_id", r.principalId)
                    put("role", r.role.name)
                    put("capabilities", JSONArray(r.capabilities.map { it.name }.sorted()))
                    put("hash", r.hashHex)
                    put("label", r.label)
                    put("created_at", r.createdAt)
                    put("revoked", r.revoked)
                    put("project_ids", JSONArray(r.projectIds.sorted()))
                })
            }
        })
        root.put("tasks", JSONArray().apply {
            metaMap.values.forEach { m ->
                put(JSONObject().apply {
                    put("task_id", m.taskId)
                    put("created_by", m.createdBy)
                    put("created_by_role", m.createdByRole.name)
                    put("created_at", m.createdAt)
                })
            }
        })
        root.put("idempotency", JSONArray().apply {
            idemMap.values.forEach { r ->
                put(JSONObject().apply {
                    put("scope", r.scopeKey)
                    put("fingerprint", r.fingerprint)
                    put("response", r.responseJson)
                    put("created_at", r.createdAt)
                })
            }
        })
        root.put("progress", JSONObject().apply {
            progressMap.forEach { (taskId, notes) ->
                put(taskId, JSONArray().apply {
                    notes.forEach { n ->
                        put(JSONObject().apply {
                            put("ts", n.ts)
                            put("actor", n.actor)
                            put("attempt_id", n.attemptId ?: JSONObject.NULL)
                            put("message", n.message)
                            put("percent", n.percent ?: JSONObject.NULL)
                        })
                    }
                })
            }
        })
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IllegalStateException("无法写入派工状态文件")
        }
    }
}
