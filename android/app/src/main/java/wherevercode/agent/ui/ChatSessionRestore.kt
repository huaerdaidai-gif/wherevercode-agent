package wherevercode.agent.ui

import wherevercode.agent.brain.Message

/**
 * 会话恢复映射（V5 C3 chat-bugfix）：把 Room conversation_messages 的
 * user/assistant 行按顺序配对成轮次，供 ChatViewModel 恢复时间线。
 * 纯函数，无 Android 依赖，可 JVM 单测。
 *
 * 映射规则：
 * - user 行开一轮；随后 assistant 行拼进同一轮（多条以换行连接）。
 * - tool 行与 system 行不进时间线。
 * - 工具调用回显的 assistant 行（source 以 `tool-call:` 开头）不进
 *   时间线——它是给模型的协议 JSON，不是用户可见的回复。
 * - 没有 user 行的孤儿 assistant 行丢弃；只有 user 行没有助手回复的
 *   轮次照常恢复（正文为空、终态 DONE）。
 * 恢复出的轮次一律终态，不重跑。
 *
 * 大脑/Agent 边界（maintainer 2026-11-12）：恢复带任务归属——Message.taskId
 * 有值时记进 SavedTurn.taskId，界面渲染归属行。说话者不从 Room 恢复
 * （Message.source 是内部标识、不是显示名），由调用方按对话方补
 * （一个对话、一个说话者：恢复出的轮次就属于该对话的对话方）。
 */
object ChatSessionRestore {

    fun messagesToTurns(messages: List<Message>): List<SavedTurn> {
        val turns = mutableListOf<SavedTurn>()
        var pendingUser: String? = null
        var pendingAssistant = StringBuilder()
        var pendingTaskId: String? = null
        fun flush() {
            val user = pendingUser ?: return
            turns += SavedTurn(
                userText = user,
                assistantText = pendingAssistant.toString(),
                status = TurnStatus.DONE.name,
                error = null,
                taskId = pendingTaskId,
            )
            pendingUser = null
            pendingAssistant = StringBuilder()
            pendingTaskId = null
        }
        for (message in messages) {
            when (message.role) {
                "user" -> {
                    flush()
                    pendingUser = message.content
                }
                "assistant" -> {
                    if (pendingUser == null) continue
                    if (message.source.startsWith("tool-call:")) continue
                    if (pendingAssistant.isNotEmpty()) pendingAssistant.append("\n")
                    pendingAssistant.append(message.content)
                    if (pendingTaskId == null) pendingTaskId = message.taskId
                }
                else -> Unit
            }
        }
        flush()
        return turns
    }
}
