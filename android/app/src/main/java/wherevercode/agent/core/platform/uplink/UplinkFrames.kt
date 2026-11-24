package wherevercode.agent.core.platform.uplink

import org.json.JSONArray
import org.json.JSONObject

/**
 * 频道三期（跨设备层）契约 v1 的帧编解码（App ⇄ 云端 WS 席位）。
 *
 * 出站：seat_register / task_ack / task_event；入站：task_dispatch（以及可选的
 * task_event_ack——契约 v1 未定义确认帧，这里作为向前兼容的可选项，见 [UplinkConfig.autoAckOnSend]）。
 * 解码绝不抛：坏帧返回 [UplinkInbound.Invalid]，未知 type 返回 [UplinkInbound.Unknown]。
 */
object UplinkFrames {
    const val TYPE_SEAT_REGISTER = "seat_register"
    const val TYPE_TASK_DISPATCH = "task_dispatch"
    const val TYPE_TASK_ACK = "task_ack"
    const val TYPE_TASK_EVENT = "task_event"
    const val TYPE_TASK_EVENT_ACK = "task_event_ack"

    fun seatRegister(
        appInstanceId: String,
        agentId: String,
        channels: List<String>,
        platform: String,
        appVersion: String,
    ): String = JSONObject().apply {
        put("type", TYPE_SEAT_REGISTER)
        put("app_instance_id", appInstanceId)
        put("agent_id", agentId)
        put("channels", JSONArray(channels))
        put("platform", platform)
        put("app_version", appVersion)
    }.toString()

    fun taskAck(taskId: String): String = JSONObject().apply {
        put("type", TYPE_TASK_ACK)
        put("task_id", taskId)
    }.toString()

    fun taskEvent(event: UplinkEvent): String = JSONObject().apply {
        put("type", TYPE_TASK_EVENT)
        put("task_id", event.taskId)
        put("seq", event.seq)
        put("kind", event.kind)
        put("actor", event.actor ?: JSONObject.NULL)
        put("at", event.at)
        put("payload", JSONObject(event.payloadJson))
    }.toString()

    fun decodeInbound(text: String): UplinkInbound {
        val obj = try {
            JSONObject(text)
        } catch (e: Exception) {
            return UplinkInbound.Invalid("不是合法 JSON")
        }
        return when (val type = obj.optString("type", "")) {
            TYPE_TASK_DISPATCH -> {
                val task = obj.optJSONObject("task")
                    ?: return UplinkInbound.Invalid("task_dispatch 缺 task")
                if (task.optString("task_id", "").isEmpty()) {
                    UplinkInbound.Invalid("task_dispatch 缺 task.task_id")
                } else {
                    UplinkInbound.Dispatch(task)
                }
            }
            TYPE_TASK_EVENT_ACK -> {
                val id = obj.optString("task_id", "")
                if (id.isEmpty() || !obj.has("seq")) UplinkInbound.Invalid("task_event_ack 缺 task_id/seq")
                else UplinkInbound.EventAck(id, obj.optLong("seq"))
            }
            "" -> UplinkInbound.Invalid("缺 type")
            else -> UplinkInbound.Unknown(type)
        }
    }
}

/** 回流事件（seq 为本地账本单调序号；服务端按 (task_id, seq) 幂等去重）。 */
data class UplinkEvent(
    val taskId: String,
    val seq: Long,
    val kind: String,
    val actor: String?,
    val at: Double,
    /** payload 的 JSON 文本（保持 data class 可比较，编码时再解析）。 */
    val payloadJson: String,
)

sealed interface UplinkInbound {
    data class Dispatch(val task: JSONObject) : UplinkInbound
    data class EventAck(val taskId: String, val seq: Long) : UplinkInbound
    data class Unknown(val type: String) : UplinkInbound
    data class Invalid(val reason: String) : UplinkInbound
}
