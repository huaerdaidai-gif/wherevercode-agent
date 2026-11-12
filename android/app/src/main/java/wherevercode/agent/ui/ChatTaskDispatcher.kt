package wherevercode.agent.ui

import java.util.UUID
import wherevercode.agent.brain.BrainEngine
import wherevercode.agent.core.agent.AgentRecord
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskStates
import wherevercode.agent.core.task.TaskStore

/**
 * 聊天 → 任务通道（V5 修复，2026-11-11）：
 * “对话不能执行任务”的根因——[ChatViewModel.send] 只开聊天轮次，
 * 从不建分派任务行；任务型指令只靠大脑模型偶然调 dispatch_task
 * 工具才成任务（模型相关、不可靠；Cloudflare 8B 根本没有函数调用），
 * TurnTaskRecorder 只建带 sessionId 的轮次镜像（两台泵都明确跳过），
 * 于是任务型指令永远只剩文字回复，任务不执行。
 *
 * 本桥只做判定 + 建行（不碰 Android、不发网络）：
 * 1. 判定：任务型指令 = 文本是行动导向
 *    （[BrainEngine.looksActionOriented]，与引擎首轮分流同一套启发式，
 *    不另造口径；“在吗”这类纯聊天不过）；
 * 2. 建行：把任务型指令写成真正的分派任务行（sessionId=null，
 *    assignedAgentId=所选 Agent，state=queued），交给已在跑的泵执行——
 *    远端 Agent 走 RemoteTaskPump，进度/结果由 ChatViewModel 观察任务行
 *    回填到聊天时间线。
 *
 * 不做的事：不认领、不执行、不调 Hand——执行是泵的活；不处理未配对
 * 设备（路由层 NotRoutable 已诚实拒绝，泵也会标 needs_attention）。
 */
object ChatTaskDispatcher {

    /** 任务型指令：行动导向的文本（与引擎首轮分流同口径）。 */
    fun isTaskInstruction(text: String): Boolean = BrainEngine.looksActionOriented(text)

    /**
     * 建一个分派任务行（不写库，纯构造；写库走 [dispatch]）。
     * sessionId 恒为 null：只有无 sessionId 的任务行能被泵认领，
     * 轮次镜像（带 sessionId）永远不会被执行。
     *
     * V5 跨设备开发（2026-11-12，A1）：[projectId] 是当前聊天的
     * 项目绑定（调用方取 chatViewModel.currentProjectId()）；派活
     * 文件夹按它解析，无绑定时为 null（走回落 + folder_fallback 事件）。
     */
    fun createDispatchedTask(
        agent: AgentRecord?,
        prompt: String,
        brainId: String?,
        projectId: String? = null,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): TaskRecord {
        val clean = prompt.trim()
        return TaskRecord(
            taskId = UUID.randomUUID().toString(),
            title = clean.take(40).ifBlank { "聊天任务" },
            description = clean,
            state = "queued",
            projectId = projectId,
            assignedAgentId = agent?.agentId,
            memberId = agent?.agentId,
            createdAt = nowSeconds,
            updatedAt = nowSeconds,
            version = 1,
            brainId = brainId,
        )
    }

    /** 写库并返回 taskId；写失败返回 null（调用方回落到普通聊天轮次）。 */
    fun dispatch(
        taskStore: TaskStore,
        agent: AgentRecord?,
        prompt: String,
        brainId: String?,
        projectId: String? = null,
    ): String? {
        return try {
            taskStore.createTask(createDispatchedTask(agent, prompt, brainId, projectId)).taskId
        } catch (_: Exception) {
            null
        }
    }

    /** 终态判定（TaskStates 口径）。 */
    fun isTerminal(task: TaskRecord): Boolean = TaskStates.isTerminal(task.state)
}
