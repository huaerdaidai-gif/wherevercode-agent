package wherevercode.agent.core.team

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import wherevercode.agent.core.mcp.HandEvent
import wherevercode.agent.core.mcp.RemoteAgentChatPort
import wherevercode.agent.core.mcp.RemoteTarget
import wherevercode.agent.core.task.TaskPacket
import wherevercode.agent.core.task.PipelineRunnerHook
import wherevercode.agent.core.task.RoomTaskStore
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskStep
import wherevercode.agent.core.task.TaskStepStatus
import wherevercode.agent.core.task.TaskStepStore
import wherevercode.agent.core.task.TaskStore
import wherevercode.agent.core.task.TaskScheduler

/**
 * 阶段 B（V5 紧急开发）：远程任务执行——认领后真的发到远端并回传。
 *
 * 与 [wherevercode.agent.core.task.LocalTaskRunner] 对称，但执行体
 * 不在本机：[RemoteAgentChatPort.runAgent] 把任务目标发到远端
 * Agent（如 VPS 上的 Example Agent），进度流式记为任务步骤，
 * 结果/错误/停止按同一账本语义落态：
 * - Progress → 任务步骤（可见进度）；
 * - Result → 先过 [RemoteMemberVerdictClassifier] 看正文开头：
 *   显式失败表述（或空结果）→ failed；否则 working → review →
 *   completed（与本地执行同一条边，TaskStates 没有 working →
 *   completed 直边）。Hand 的 result 帧没有成功/失败标志、退出码
 *   也救不了（Codex 退出码 0 但正文报失败，见 692638e2），所以
 *   必须看正文；分类器只认开头陈述，是启发式（见
 *   [RemoteMemberVerdict]）。
 * - Blocked（远端拒绝/执行失败）→ failed，带真实原因；
 * - Blocked（控制停止，code=[REMOTE_CONTROLLED_STOP_CODE]）→ cancelled；
 * - 执行中途目标变了（goalVersion 与派发时不一致）→ 本次结果作废，
 *   任务 cancelled（与 TurnTaskRecorder GOAL_CHANGED → cancelled
 *   一致），不把旧目标的结果当成新目标的交付；
 * - 流无终态就断了（断线）→ failed，原因写明“未收到结果”，
 *   不伪造完成。
 *
 * 租约：执行中每 180 秒续一次 600 秒租约（与 LocalTaskRunner 同口径），
 * 长任务不会中途被回队。每次状态写都按认领到的 attemptId 核对
 * （P0 围栏）：任务被回队重认领后，旧执行的迟到终态写丢弃。
 *
 * 发给远端的是版本化任务包（[TaskPacket]）渲染的文本，不是裸目标：
 * 跨设备只传任务包，不传聊天记录。
 */
sealed class RemoteTaskOutcome {
    data class Completed(val result: String) : RemoteTaskOutcome()
    data class Failed(val error: String) : RemoteTaskOutcome()
    data class Cancelled(val note: String) : RemoteTaskOutcome()
}

class RemoteTaskDispatcher(
    private val taskStore: TaskStore,
    private val scheduler: TaskScheduler,
    private val stepStore: TaskStepStore,
    private val port: RemoteAgentChatPort,
    private val leaseRenewIntervalMs: Long = 180_000,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    /**
     * P2 流水线钩子（2026-11-11）：默认 null（老调用方与测试不动，
     * 行为与 P0 完全一致）；生产由 MainActivity 注入
     * PipelineRunnerHookImpl。钩子只做两件事：发给远端的是 plan
     * 批准后的任务包（[coderPrompt]），编码成功停在 review、不自动
     * completed（[finishAsCompleted] 查它）。
     */
    private val pipelineHook: PipelineRunnerHook? = null,
) {
    companion object {
        /** 服务端在运行被控制指令停掉时，终态 error 帧用的 code。 */
        const val REMOTE_CONTROLLED_STOP_CODE: String = "remote_controlled_stop"

        /** 远端单次调用的超时（秒）：凭证 TTL 900 秒，600 秒留有余量。 */
        const val DEFAULT_TIMEOUT_SECONDS: Long = 600
    }

    /**
     * 执行一个已认领（assigned）的远程任务到终态。
     * 调用方（[RemoteTaskPump]）负责认领与单任务规则，本方法只负责
     * 一次执行与落态。
     */
    suspend fun execute(task: TaskRecord, target: RemoteTarget): RemoteTaskOutcome {
        val startGoalVersion = task.goalVersion
        // P0 围栏令牌：本次执行认领到的 attemptId；全部状态写都按它核对。
        val attemptId = task.attemptId
        // The claim-to-start gap is a control boundary: serialize start with
        // pause/stop and verify the exact claim attempt under the shared
        // TaskScheduler lock/transaction. Never write `working` directly here.
        val started = scheduler.startTask(task.taskId, attemptId)
            ?: return RemoteTaskOutcome.Cancelled("任务启动前收到暂停/停止或认领已失效，未发送到远端。")
        return try {
            coroutineScope {
                val renewJob = launch {
                    while (isActive) {
                        delay(leaseRenewIntervalMs)
                        renewLease(task.taskId, attemptId)
                    }
                }
                try {
                    runRemote(started, target, startGoalVersion, attemptId)
                } catch (e: CancellationException) {
                    stepStore.closeRunningSteps(task.taskId)
                    finishAsCancelled(task.taskId, "任务被用户停止。", attemptId)
                    throw e
                } catch (e: Exception) {
                    finishAsFailed(task.taskId, "远端执行异常：${e.message ?: "未知错误"}", attemptId)
                    RemoteTaskOutcome.Failed("远端执行异常：${e.message ?: "未知错误"}")
                } finally {
                    renewJob.cancel()
                }
            }
        } catch (_: CancellationException) {
            RemoteTaskOutcome.Cancelled("任务被用户停止。")
        }
    }

    private suspend fun runRemote(
        task: TaskRecord,
        target: RemoteTarget,
        startGoalVersion: Long,
        attemptId: String?,
    ): RemoteTaskOutcome {
        var finalOutput: String? = null
        var finalBlocked: HandEvent.Blocked? = null
        port.runAgent(
            target = target,
            taskId = task.taskId,
            // P0：发给远端的是任务包（目标原文在前、契约段落在后），
            // 不是裸目标；跨设备只传任务包，不传聊天记录。
            // P2：流水线任务发 plan 批准后的包（验收标准与文件清单已
            // 填进包里，见 PipelineRunnerHook.coderPrompt）。
            // V5 跨设备开发（2026-11-12，A4）：包里只加信息性工作目录
            // （target.folder 已是解析后的派活文件夹，项目检出或回落
            // 的 Agent 文件夹）；执行仍以信封/凭证的 folder 为准。
            text = pipelineHook?.coderPrompt(task) ?: TaskPacket.fromTask(
                task,
                folder = target.folder,
                folderSource = target.folderSource.name.lowercase(),
            ).toPromptText(),
            timeoutSeconds = DEFAULT_TIMEOUT_SECONDS,
            goalVersion = startGoalVersion,
            epoch = task.authEpoch,
        ).collect { event ->
            when (event) {
                is HandEvent.Progress -> recordProgress(task.taskId, event)
                is HandEvent.Result -> finalOutput = event.output
                is HandEvent.Blocked -> finalBlocked = event
            }
        }
        stepStore.closeRunningSteps(task.taskId)
        // 执行中途目标变了：本次结果作废（无论成功还是失败），
        // 任务 cancelled，留待按新目标重新派发。
        val current = taskStore.getTask(task.taskId)
        if (current != null && current.goalVersion != startGoalVersion) {
            finishAsCancelled(
                task.taskId,
                "目标已变更，本次结果作废，请按新目标重新派发。",
                attemptId,
            )
            return RemoteTaskOutcome.Cancelled("目标已变更，本次结果作废，请按新目标重新派发。")
        }
        val blocked = finalBlocked
        if (blocked != null) {
            if (blocked.code == REMOTE_CONTROLLED_STOP_CODE) {
                finishAsCancelled(task.taskId, "已按控制指令停止。", attemptId)
                return RemoteTaskOutcome.Cancelled("已按控制指令停止。")
            }
            finishAsFailed(task.taskId, blocked.reason, attemptId)
            return RemoteTaskOutcome.Failed(blocked.reason)
        }
        val output = finalOutput
        if (output == null) {
            finishAsFailed(task.taskId, "远端连接中断，未收到结果：请检查网络与远端设备后重新派发", attemptId)
            return RemoteTaskOutcome.Failed("远端连接中断，未收到结果")
        }
        // 2026-11-11（任务 692638e2）：Result 只说明“远端回了话”，
        // 不说明“远端做成了”。正文开头是显式失败表述时必须落 failed，
        // 不能按完成记。目标变更与 Blocked 检查在前，顺序不动。
        return when (val verdict = classifyRemoteMemberOutput(output)) {
            is RemoteMemberVerdict.Failed -> {
                finishAsFailed(task.taskId, verdict.reason, attemptId)
                RemoteTaskOutcome.Failed(verdict.reason)
            }
            RemoteMemberVerdict.Success -> {
                finishAsCompleted(task.taskId, output, attemptId)
                RemoteTaskOutcome.Completed(output)
            }
        }
    }

    private fun recordProgress(taskId: String, event: HandEvent.Progress) {
        val toolCallId = "remote-progress-${event.seq}"
        try {
            stepStore.addStep(
                TaskStep(
                    stepId = UUID.randomUUID().toString(),
                    taskId = taskId,
                    toolCallId = toolCallId,
                    toolName = "remote-progress",
                    argsSummary = event.message.take(500),
                    status = TaskStepStatus.RUNNING,
                    startedAtEpochSeconds = nowSeconds(),
                ),
            )
            stepStore.finishStep(
                taskId = taskId,
                toolCallId = toolCallId,
                status = TaskStepStatus.SUCCESS,
                resultSummary = event.message.take(1000),
            )
        } catch (_: Exception) {
            // 步骤记录失败不打断执行；任务终态仍以远端回传为准。
        }
    }

    private fun finishAsCompleted(taskId: String, output: String, attemptId: String?) {
        val current = taskStore.getTask(taskId)
        if (current?.state == "assigned") {
            updateTask(taskId, mapOf("state" to "working"), attemptId)
        }
        if (updateTask(taskId, mapOf("state" to "review"), attemptId) == null) return
        // P2：流水线任务的编码完成停在 review，不自动 completed——
        // 审查走完才由 PipelineOrchestrator.recordReview 推 completed。
        // 远端输出照常落到任务行上（审查时看得到），写不上也不影响 hold。
        if (pipelineHook?.shouldHoldAtReview(taskId) == true) {
            updateTask(taskId, mapOf("result" to output.take(4000)), attemptId)
            pipelineHook.onCodeFinished(taskId)
            return
        }
        updateTask(
            taskId,
            mapOf("state" to "completed", "result" to output.take(4000)),
            attemptId,
        )
    }

    private fun finishAsFailed(taskId: String, error: String, attemptId: String?) {
        stepStore.closeRunningSteps(taskId)
        updateTask(
            taskId,
            mapOf("state" to "failed", "error" to error.take(4000)),
            attemptId,
        )
    }

    private fun finishAsCancelled(taskId: String, note: String, attemptId: String?) {
        stepStore.closeRunningSteps(taskId)
        updateTask(
            taskId,
            mapOf("state" to "cancelled", "result" to note),
            attemptId,
        )
    }

    private fun renewLease(taskId: String, attemptId: String?) {
        val now = nowSeconds()
        updateTask(
            taskId,
            mapOf("lease_expires_at" to (now + RoomTaskStore.LEASE_SECONDS)),
            attemptId,
        )
    }

    /**
     * 带乐观锁的写入（与 LocalTaskRunner 同款）：过期写返回 null，
     * 不抛错、不杀死泵循环，任务停在上一真实状态。
     *
     * P0 attempt fence：[expectedAttemptId] 是本次执行认领到的
     * attemptId；任务当前 attemptId 不同时，本次执行已过期，写丢弃。
     */
    private fun updateTask(
        taskId: String,
        changes: Map<String, Any?>,
        expectedAttemptId: String? = null,
    ): TaskRecord? {
        return try {
            val current = taskStore.getTask(taskId) ?: return null
            if (current.attemptId != expectedAttemptId) return null
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
}
