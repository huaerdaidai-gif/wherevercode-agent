package wherevercode.agent.ui

import java.util.UUID
import wherevercode.agent.core.security.SecretRedactor
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskScheduler
import wherevercode.agent.core.task.TaskStates
import wherevercode.agent.core.task.TaskStep
import wherevercode.agent.core.task.TaskStepStatus
import wherevercode.agent.core.task.TaskStepStore
import wherevercode.agent.core.task.TaskStore
import wherevercode.agent.core.team.RemoteMemberVerdict
import wherevercode.agent.core.team.RemoteMemberVerdictClassifier
import wherevercode.agent.core.team.classifyRemoteMemberOutput

/**
 * Chat turn -> TaskStore mirror (phase 1, U3 dispatch 最小链).
 *
 * This is the write point the audit found missing: a chat turn used to
 * live only in [ChatTimeline] and never reached the task library, so
 * the task page was empty under normal use. The recorder creates one
 * [TaskRecord] per brain turn, claims + starts it through the real
 * [TaskScheduler] (single-task rule applies), records every tool call
 * as a [TaskStep], and writes the terminal state when the turn ends.
 *
 * Invariants:
 * - A mirrored turn ALWAYS carries `sessionId` (= the chat
 *   conversation id, or `chat-fallback:<turnId>` for the fallback
 *   path). [wherevercode.agent.core.task.LocalTaskRunner] only claims
 *   tasks with `sessionId == null`, so it can never steal a live
 *   turn's task; a dispatched task (created by `dispatch_task`) has
 *   no sessionId and is the runner's business, not this recorder's.
 * - The mirror is claimed under the Agent that actually does the
 *   work ([agentId], default [LOCAL_AGENT_ID] for brain turns):
 *   a remote round's mirror belongs to the remote Agent, a Manus /
 *   platform round's to that Agent — never misattributed to the
 *   local brain. The task library shows it as that Agent's work.
 * - Nothing is faked: a turn that fails to claim/start leaves no
 *   working task behind (the queued row is cancelled, visibly); a
 *   turn whose terminal write fails stays in its last real state.
 * - Terminal mapping follows [TaskStates]: DONE -> `working ->
 *   review -> completed` (no direct `working -> completed` edge);
 *   ERROR / EMPTY -> `failed`; PAUSED / STOPPED / GOAL_CHANGED /
 *   CANCELLED -> `cancelled` (pause has no resume semantics yet, so
 *   the task is honestly terminal, not left "working").
 *   DONE 只认轮次跑完，不认“做成了”：正文开头明说失败/被挡/
 *   需要信息时同样落 failed（见 [finishTurn] 的 verdict 复核，
 *   与 LocalTaskRunner.finishTask 同款）。
 * - The recorder never breaks chat: every store call is guarded, and
 *   a null return means "this turn has no task row", not an error.
 *
 * Called from [ChatViewModel]'s IO scope (Room forbids main-thread
 * access). Pure wiring over the store interfaces; no Android imports
 * except through the injected stores.
 */
class TurnTaskRecorder(
    private val taskStore: TaskStore,
    private val scheduler: TaskScheduler,
    private val stepStore: TaskStepStore,
) {
    companion object {
        /** The agent id a chat turn's task is claimed under. */
        const val LOCAL_AGENT_ID: String = "local-brain"
    }

    @Volatile
    private var currentTaskId: String? = null

    /** Last safe diagnostic for a failed row open; never includes task text. */
    @Volatile
    private var lastBeginFailure: String? = null

    fun lastBeginFailure(): String? = lastBeginFailure

    fun currentTaskId(): String? = currentTaskId

    /**
     * Open a task for a turn that is about to run. Returns the task id,
     * or null when no task row could be opened (chat continues without
     * one — the caller must not treat null as an error).
     *
     * [agentId] 是真正干活的 Agent（远端轮次传远端 Agent id，Manus /
     * 平台轮次传该 Agent id；大脑轮次默认 local-brain）。镜像按它认领、
     * assignedAgentId 按它写——任务库/控制台看到的才是真正干活的人。
     */
    fun beginTurn(
        prompt: String,
        sessionId: String,
        brainId: String?,
        /**
         * V5 跨设备开发（2026-11-12，A1）：本轮所属项目（当前聊天的
         * 项目绑定，调用方取 currentProjectId()）；轮次镜像也带上它，
         * 远端轮次的目标解析与技能记忆按它取项目。无绑定时为 null。
         */
        projectId: String? = null,
        agentId: String = LOCAL_AGENT_ID,
    ): String? {
        val taskId = UUID.randomUUID().toString()
        val now = nowSeconds()
        val record = TaskRecord(
            taskId = taskId,
            sessionId = sessionId,
            title = SecretRedactor.redact(prompt.trim().take(40), 100).ifBlank { "聊天轮次" },
            description = SecretRedactor.redact(prompt.trim(), 4000),
            state = "queued",
            projectId = projectId,
            assignedAgentId = agentId,
            createdAt = now,
            updatedAt = now,
            version = 1,
            brainId = brainId,
        )
        return try {
            taskStore.createTask(record)
            val claimed = scheduler.claimTask(taskId, agentId)
            if (claimed == null) {
                // 认领失败（已有在手任务）：不留僵尸 queued 行。
                updateTask(
                    taskId,
                    mapOf(
                        "state" to "cancelled",
                        "result" to "轮次开始时已有在手任务，未执行。",
                    ),
                )
                return null
            }
            val started = scheduler.startTask(taskId)
            if (started == null) {
                updateTask(
                    taskId,
                    mapOf(
                        "state" to "cancelled",
                        "result" to "轮次开始时启动失败，未执行。",
                    ),
                )
                return null
            }
            currentTaskId = taskId
            taskId
        } catch (_: Exception) {
            currentTaskId = null
            null
        }
    }

    /**
     * Open a task row for a remote-agent chat round (2026-11-12,
     * 回复归属修复）。与 [beginTurn] 的区别：不经过调度器的认领门禁
     * （调度器是全局单任务门禁，一脑在手时 beginTurn 回 null；远端
     * 轮次若拿 null 就只能用随机 UUID 发 Hand，活跑在任务表外面——
     * 2026-11-12 真机事故：两次 run 调用 e7690865 / 21504781 在
     * K70 任务表里无行）。轮次行是“记录”不是“可调度任务”：
     * sessionId 非空（= 对话 id），泵与执行器只认领 sessionId 为
     * null 的任务，故永不认领；ChatViewModel 的 active 互斥保证
     * 一轮一任务，不需要调度器的租约/attempt 围栏。行直接按状态机
     * queued → assigned → working 落到 working 态，终态由
     * [finishTurn] 收（working → review → completed/failed）。
     * 建行或转移任一步失败返回 null（调用方必须中止轮次、绝不发
     * Hand），失败行取消、不留 queued 僵尸。
     */
    fun beginRemoteRound(
        prompt: String,
        sessionId: String,
        agentId: String,
        projectId: String? = null,
    ): String? {
        lastBeginFailure = null
        val taskId = UUID.randomUUID().toString()
        val now = nowSeconds()
        val record = TaskRecord(
            taskId = taskId,
            sessionId = sessionId,
            title = SecretRedactor.redact(prompt.trim().take(40), 100).ifBlank { "远端轮次" },
            description = SecretRedactor.redact(prompt.trim(), 4000),
            state = "queued",
            projectId = projectId,
            assignedAgentId = agentId,
            createdAt = now,
            updatedAt = now,
            version = 1,
        )
        return try {
            taskStore.createTask(record)
            // 记录行：不经调度器认领门禁，直接按状态机转移。任一
            // 步失败即取消本行并返回 null（调用方中止轮次）。
            if (updateTask(taskId, mapOf("state" to "assigned")) == null) {
                lastBeginFailure = "任务记录未能进入 assigned 状态"
                updateTask(taskId, mapOf("state" to "cancelled", "result" to "轮次建行失败，未执行。"))
                return null
            }
            if (updateTask(taskId, mapOf("state" to "working")) == null) {
                lastBeginFailure = "任务记录未能进入 working 状态"
                updateTask(taskId, mapOf("state" to "cancelled", "result" to "轮次建行失败，未执行。"))
                return null
            }
            currentTaskId = taskId
            taskId
        } catch (e: Exception) {
            currentTaskId = null
            // Keep the actual persistence failure diagnosable without logging
            // prompt, credentials, project contents, or task arguments.
            lastBeginFailure = "${e.javaClass.simpleName}: ${e.message.orEmpty().take(180)}"
            null
        }
    }

    fun recordToolCall(taskId: String, callId: String, name: String, args: Map<String, Any?>) {
        try {
            stepStore.addStep(
                TaskStep(
                    stepId = UUID.randomUUID().toString(),
                    taskId = taskId,
                    toolCallId = callId,
                    toolName = name,
                    argsSummary = formatArgs(args),
                    status = TaskStepStatus.RUNNING,
                    startedAtEpochSeconds = nowSeconds(),
                ),
            )
        } catch (_: Exception) {
            // 步骤写失败不打断轮次。
        }
    }

    fun recordToolResult(taskId: String, callId: String, ok: Boolean, summary: String) {
        try {
            stepStore.finishStep(
                taskId = taskId,
                toolCallId = callId,
                status = if (ok) TaskStepStatus.SUCCESS else TaskStepStatus.FAILED,
                resultSummary = SecretRedactor.redact(summary, 1000),
            )
        } catch (_: Exception) {
            // 步骤写失败不打断轮次。
        }
    }

    /**
     * Write the turn's terminal state. [assistantText] becomes the
     * task result on DONE (redacted, capped); [error] becomes the task
     * error on ERROR / EMPTY.
     */
    fun finishTurn(
        taskId: String,
        status: TurnStatus,
        assistantText: String,
        error: String?,
        hadToolCalls: Boolean,
    ) {
        if (currentTaskId == taskId) currentTaskId = null
        try {
            stepStore.closeRunningSteps(taskId)
            val current = taskStore.getTask(taskId) ?: return
            if (TaskStates.isTerminal(current.state)) return
            if (current.state == "assigned") {
                if (updateTask(taskId, mapOf("state" to "working")) == null) return
            }
            when (status) {
                TurnStatus.DONE -> {
                    val result = SecretRedactor.redact(assistantText.trim(), 4000)
                        .ifBlank {
                            if (hadToolCalls) "（无最终文字回复；以工具结果为准。）"
                            else "（无最终文字回复。）"
                        }
                    // 轮次跑完不等于做成了：大脑/远端 Agent 在正文里明说
                    // 失败、被挡住、需要信息时记 failed，不记 completed
                    // （2026-11-12 09:46 实证：任务 040e9317 的大脑正文
                    // 写“这个任务我目前无法完成”，轮次终态 DONE，任务
                    // 却记了 completed）。与 LocalTaskRunner.finishTask
                    // 同款复核，用同一分类器（RemoteMemberVerdict，
                    // 含 NEED_INFO 模式）；空正文沿用占位文案，不判
                    // （工具有步骤可查，见占位文案本身）。
                    when (
                        val verdict = classifyRemoteMemberOutput(
                            result,
                            RemoteMemberVerdictClassifier.LOCAL_EMPTY_OUTPUT_REASON,
                        )
                    ) {
                        is RemoteMemberVerdict.Failed -> {
                            updateTask(
                                taskId,
                                mapOf(
                                    "state" to "failed",
                                    "error" to verdict.reason.take(4000),
                                ),
                            )
                        }
                        RemoteMemberVerdict.Success -> {
                            if (updateTask(taskId, mapOf("state" to "review")) == null) return
                            updateTask(taskId, mapOf("state" to "completed", "result" to result))
                        }
                    }
                }
                TurnStatus.ERROR, TurnStatus.EMPTY -> {
                    val message = SecretRedactor.redact(
                        error ?: "轮次失败，未返回错误信息。",
                        4000,
                    )
                    updateTask(taskId, mapOf("state" to "failed", "error" to message))
                }
                TurnStatus.PAUSED, TurnStatus.STOPPED, TurnStatus.GOAL_CHANGED,
                TurnStatus.CANCELLED -> {
                    val label = when (status) {
                        TurnStatus.PAUSED -> "已暂停"
                        TurnStatus.STOPPED -> "已停止"
                        TurnStatus.GOAL_CHANGED -> "已改目标"
                        else -> "已取消"
                    }
                    updateTask(
                        taskId,
                        mapOf(
                            "state" to "cancelled",
                            "result" to "轮次$label，剩余步骤未执行。",
                        ),
                    )
                }
                TurnStatus.RUNNING -> {
                    // finishTurn 只在终态调用；RUNNING 到这里是不变的空操作。
                }
            }
        } catch (_: Exception) {
            // 终态写失败不打断聊天；任务停在上一真实状态。
        }
    }

    private fun formatArgs(args: Map<String, Any?>): String {
        if (args.isEmpty()) return ""
        val text = args.entries.joinToString(", ") { (key, value) ->
            "$key=${value.toString().take(120)}"
        }
        return SecretRedactor.redact(text, 500)
    }

    /**
     * Write with the record's current version/counters (optimistic
     * lock). Returns null instead of throwing.
     */
    private fun updateTask(taskId: String, changes: Map<String, Any?>): TaskRecord? {
        return try {
            val current = taskStore.getTask(taskId) ?: return null
            taskStore.updateTask(
                taskId,
                changes,
                current.version,
                current.goalVersion,
                current.authEpoch,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000
}
