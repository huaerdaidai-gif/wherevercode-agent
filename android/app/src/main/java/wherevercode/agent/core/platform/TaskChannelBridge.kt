package wherevercode.agent.core.platform

/**
 * 任务派工的事件写回口（spec §5）：账本事件反向写成频道消息（带
 * task_id 引用）。实现由 LocalChannelService 在 W8 提供（以 App
 * 身份发帖）；W1 只有接口，服务层不调用。
 */
interface TaskChannelBridge {
    fun postTaskEvent(channelId: String, taskId: String, kind: String, text: String)
}
