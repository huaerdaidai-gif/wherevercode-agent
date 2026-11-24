package wherevercode.agent.core.platform.uplink

import org.json.JSONObject
import wherevercode.agent.core.platform.TaskEventRecord
import wherevercode.agent.core.platform.TaskRecord

/** 本地账本事件 → 契约 task_event 帧体。只回流契约列出的 6 种迁移。 */
object UplinkEventMapper {
    val UPLINK_KINDS = setOf("claimed", "progress", "submitted", "decided", "withdrawn", "lease_expired")

    fun map(event: TaskEventRecord, task: TaskRecord): UplinkEvent? {
        if (event.kind !in UPLINK_KINDS) return null
        val payload = JSONObject().apply {
            put("text", event.text)
            put("state", task.state)
            put("version", task.version)
            task.branch?.let { put("branch", it) }
            task.commitSha?.let { put("commit", it) }
            task.prUrl?.let { put("pr_url", it) }
        }
        return UplinkEvent(event.taskId, event.seq, event.kind, event.who, event.at, payload.toString())
    }
}
