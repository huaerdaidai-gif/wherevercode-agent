package wherevercode.agent.core.control

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONObject
import wherevercode.agent.core.mcp.HandEvent
import wherevercode.agent.core.mcp.RemoteAgentChatPort
import wherevercode.agent.core.mcp.RemoteControlCommand
import wherevercode.agent.core.mcp.RemoteControlResult
import wherevercode.agent.core.mcp.RemoteControlSender
import wherevercode.agent.core.mcp.RemoteTarget

/**
 * 阶段 B（V5 紧急开发）：经 Hand 直连投递控制指令的
 * [RemoteControlSender] 实现。
 *
 * 控制走 [RemoteAgentChatPort.sendControl]（与聊天同一条 Hand 通道，
 * argv `["__control__", 动作, operationId, 新目标?]`）；回执是
 * Result 帧，output 为 JSON
 * `{"control":…,"confirmed":bool,"message":…}`。不是 JSON 或没有
 * confirmed 字段的回执一律按未确认处理，不把任意输出当成已确认。
 */
class HandRemoteControlSender(
    private val port: RemoteAgentChatPort,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : RemoteControlSender {

    override fun send(
        target: RemoteTarget,
        taskId: String,
        command: RemoteControlCommand,
        onResult: (RemoteControlResult) -> Unit,
    ) {
        scope.launch {
            var reported = false
            try {
                port.sendControl(target, taskId, command).collect { event ->
                    when (event) {
                        is HandEvent.Result -> {
                            if (!reported) {
                                reported = true
                                onResult(parseControlResult(event.output))
                            }
                        }
                        is HandEvent.Blocked -> {
                            if (!reported) {
                                reported = true
                                onResult(
                                    RemoteControlResult(
                                        confirmed = false,
                                        message = event.reason,
                                        code = event.code,
                                    ),
                                )
                            }
                        }
                        is HandEvent.Progress -> Unit
                    }
                }
                if (!reported) {
                    onResult(
                        RemoteControlResult(
                            confirmed = false,
                            message = "远端没有回执控制结果：连接已断开，请检查网络后重试",
                        ),
                    )
                }
            } catch (e: Exception) {
                if (!reported) {
                    onResult(
                        RemoteControlResult(
                            confirmed = false,
                            message = "发送控制指令失败：${e.message ?: "未知错误"}",
                        ),
                    )
                }
            }
        }
    }

    /**
     * 解析服务端的控制回执。回执是 JSON
     * {"control":"paused","confirmed":true,"message":"…"}；
     * 不是 JSON 时按未确认处理（不把任意输出当成已确认）。
     */
    internal fun parseControlResult(output: String): RemoteControlResult {
        return try {
            val obj = JSONObject(output)
            if (!obj.has("confirmed")) {
                return RemoteControlResult(
                    confirmed = false,
                    message = "远端回执格式不对：没有 confirmed 字段",
                )
            }
            RemoteControlResult(
                confirmed = obj.optBoolean("confirmed", false),
                message = obj.optString("message", "").ifBlank { output.take(500) },
                code = obj.optString("code", "").takeIf { it.isNotBlank() },
            )
        } catch (_: Exception) {
            RemoteControlResult(
                confirmed = false,
                message = "远端回执格式不对：不是合法 JSON",
            )
        }
    }
}
