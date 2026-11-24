package wherevercode.agent.core.platform

/**
 * 任务派工的事件写回口（spec §5）：账本事件反向写成频道消息（带
 * task_id 引用）。实现由 LocalChannelService 提供（W8 接线，以
 * App 身份发帖，正文末尾带 [task:<id>] 引用行）。
 */
interface TaskChannelBridge {
    fun postTaskEvent(channelId: String, taskId: String, kind: String, text: String)
}
