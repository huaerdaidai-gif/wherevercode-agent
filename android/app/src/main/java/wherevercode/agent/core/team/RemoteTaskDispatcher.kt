package wherevercode.agent.core.team

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import wherevercode.agent.core.mcp.HandClient
import wherevercode.agent.core.mcp.HandEvent
import wherevercode.agent.core.mcp.RemoteAgentChatPort
import wherevercode.agent.core.mcp.RemoteResultProtocol
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
 * - Result（T6 结构化结果协议 v1，2026-11-15）：优先读结构化
 *   status（[RemoteResultProtocol]）：succeeded/failed/cancelled
 *   按协议落态，正文 output 只用于展示、日志与产物拼接，**不再参与
 *   成败判定**；旧 Hand 缺 status 字段时保留正文分类器兜底
 *   （[RemoteMemberVerdictClassifier]），但必须带
 *   legacy_verdict_fallback 显式标记，不得伪装成结构化结果；
 *   status 非法或自相矛盾 → invalid_protocol_status 保守失败，
 *   绝不降级成功（见 T6-S2 §4 映射表）；
 * - Blocked（远端拒绝/执行失败，含 error code）→ failed，code 透传
 *   进 error 前缀；
 * - Blocked（控制停止，code=[REMOTE_CONTROLLED_STOP_CODE]）→ cancelled；
 * - Blocked（传输中断，code=transport_interrupted）或无任何终态帧
 *   且交付恢复失败 → failed + remote_result_unknown：**结果未知**
 *  （远端可能仍在执行或已完成），绝不写成确定失败或取消成功；
 *   原始歧义说明进审计事件（Muse 批复 #1 条件）；
 * - Host 确认“无交付记录”（artifact_missing 等终态码）→ 确认失败，
 *   与结果未知严格区分；
 * - 执行中途目标变了（goalVersion 与派发时不一致）→ 本次结果作废，
 *   任务 cancelled（与 TurnTaskRecorder GOAL_CHANGED → cancelled
 *   一致），不把旧目标的结果当成新目标的交付。
 *
 * 租约：执行中每 180 秒续一次 600 秒租约（与 LocalTaskRunner 同口径），
 * 长任务不会中途被回队。每次状态写都按认领到的 attemptId 核对
 * （P0 围栏）：任务被回队重认领后，旧执行的迟到终态写丢弃。
 *
 * 发给远端的是版本化任务包（[TaskPacket]）渲染的文本，不是裸目标：
 * 跨设备只传任务包，不传聊天记录。
 */
/**
 * 一次远端执行的终态。[fromStructured]/[errorCode] 是 T6 协议来源
 * 标记：结构化（帧带合法 status 或 error code）为 true；旧协议正文
 * 兜底为 false。[errorCode] 机器可读（[RemoteResultProtocol] 代码表），
 * null 表示无码（如用户停止、目标变更）。
 */
sealed class RemoteTaskOutcome {
    data class Completed(val result: String, val fromStructured: Boolean = false) : RemoteTaskOutcome()
    data class Failed(val error: String, val errorCode: String? = null) : RemoteTaskOutcome()
    data class Cancelled(val note: String, val errorCode: String? = null) : RemoteTaskOutcome()
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
    private val artifactDirectory: File? = null,
    /**
     * Bounded backoff for recovering a delivery from the Host (lost result
     * frame, transient `artifact_unavailable`, or an artifact that failed the
     * integrity check in transit). One fetch per delay; never unbounded.
     */
    private val artifactRetryDelaysMs: List<Long> = DEFAULT_ARTIFACT_RETRY_DELAYS_MS,
) {
    /** A delivered artifact that does not match its declared size/SHA-256. */
    private class ArtifactIntegrityException(message: String) : Exception(message)

    private class VerifiedArtifact(val bytes: ByteArray, val sha256: String)

    /** Result of a bounded delivery fetch. */
    private sealed class Fetch {
        class Delivered(val result: HandEvent.Result) : Fetch()
        /**
         * The Host says refetching cannot help (nothing delivered / corrupt /
         * stale). [code] 是 Host 的终态码（artifact_missing 等）：确认失败，
         * 与“结果未知”（Exhausted / 无帧）严格区分。
         */
        class Terminal(val code: String?) : Fetch()
        /** Every bounded attempt hit a transient failure. */
        object Exhausted : Fetch()
    }

    companion object {
        /** Retry schedule for artifact recovery: 3 fetches, 250ms -> 1s. */
        val DEFAULT_ARTIFACT_RETRY_DELAYS_MS: List<Long> = listOf(250L, 500L, 1_000L)

        /** Host codes after which refetching cannot help. */
        private val TERMINAL_ARTIFACT_CODES = setOf("artifact_missing", "artifact_corrupt", "artifact_invalid", "stale_attempt")

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
                    // 执行器异常（T6-S2 §4 情形 4）：App 侧链路坏，与远端结果无关。
                    val error = RemoteResultProtocol.errorText(
                        RemoteResultProtocol.CODE_REMOTE_EXECUTOR_ERROR,
                        "远端执行异常：${e.message ?: "未知错误"}",
                    )
                    finishAsFailed(task.taskId, error, attemptId)
                    RemoteTaskOutcome.Failed(error, RemoteResultProtocol.CODE_REMOTE_EXECUTOR_ERROR)
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
        var finalResult: HandEvent.Result? = null
        var finalBlocked: HandEvent.Blocked? = null
        // Host 确认“无交付记录/记录损坏/轮次过期”时的终态码：确认失败，
        // 不是结果未知（与 [RemoteResultProtocol.CODE_REMOTE_RESULT_UNKNOWN] 区分）。
        var deliveryTerminalCode: String? = null
        val text = pipelineHook?.coderPrompt(task) ?: TaskPacket.fromTask(
            task, folder = target.folder, folderSource = target.folderSource.name.lowercase(),
        ).toPromptText()
        val events = if (attemptId == null) {
            port.runAgent(target, task.taskId, text, DEFAULT_TIMEOUT_SECONDS, startGoalVersion, task.authEpoch)
        } else {
            port.runTask(target, task.taskId, text, DEFAULT_TIMEOUT_SECONDS, startGoalVersion, task.authEpoch, attemptId)
        }
        events.collect { event ->
            when (event) {
                is HandEvent.Progress -> recordProgress(task.taskId, event)
                is HandEvent.Result -> finalResult = event
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
        // A response lost after the Host committed the delivery can be
        // recovered without invoking the Agent a second time.
        if (attemptId != null && finalResult == null &&
            (finalBlocked == null || finalBlocked?.code == HandClient.TRANSPORT_INTERRUPTED)
        ) {
            // First fetch immediately, then the bounded backoff schedule.
            when (val fetched = fetchDelivery(target, task.taskId, attemptId, listOf(0L) + artifactRetryDelaysMs.drop(1))) {
                is Fetch.Delivered -> {
                    finalResult = fetched.result
                    finalBlocked = null
                }
                is Fetch.Terminal -> deliveryTerminalCode = fetched.code
                Fetch.Exhausted -> Unit
            }
        }
        val blocked = finalBlocked
        if (blocked != null) {
            if (blocked.code == REMOTE_CONTROLLED_STOP_CODE) {
                // 远端确认取消（T6-S2 §4 情形 3）：只有这个出口能落 cancelled。
                finishAsCancelled(task.taskId, "已按控制指令停止。", attemptId)
                return RemoteTaskOutcome.Cancelled("已按控制指令停止。", blocked.code)
            }
            if (blocked.code == HandClient.TRANSPORT_INTERRUPTED) {
                // 客户端传输中断（读超时/连接重置/零帧）：结果未知，不是确定失败。
                return finishAsUnknown(task.taskId, attemptId, blocked.reason)
            }
            // error 帧本身就是结构化出口：code 透传进前缀，按失败落账
            //（含旧 Hand 滥用 agent_not_found 报非 0 退出码的情形——映射表 #9）。
            val error = RemoteResultProtocol.errorText(blocked.code, blocked.reason)
            finishAsFailed(task.taskId, error, attemptId)
            return RemoteTaskOutcome.Failed(error, blocked.code)
        }
        val result = finalResult
        if (result == null) {
            if (deliveryTerminalCode != null) {
                // Host 已明确回答“这次执行没有可用交付”：确认失败。
                val text = "远端确认本次执行没有可用的交付记录：请重新派发。"
                val error = RemoteResultProtocol.errorText(deliveryTerminalCode, text)
                finishAsFailed(task.taskId, error, attemptId)
                return RemoteTaskOutcome.Failed(error, deliveryTerminalCode)
            }
            // 连一个终态帧都没有：结果未知（旧文案“未收到结果”按 T6-S2
            // §4 情形 5b 改为显式未知，不许写成普通失败）。
            return finishAsUnknown(task.taskId, attemptId, "流结束，未收到任何终态帧")
        }
        // —— T6 结构化优先判定（2026-11-15）——
        // status 在 → 协议说了算，正文不参与成败；status 缺 → 旧协议
        // 兜底（分类器）但显式标记；status 非法 → 保守失败。
        return when (
            val verdict = RemoteResultProtocol.resolveStructured(
                result.status, result.errorCode, result.errorMessage, result.output,
            )
        ) {
            is RemoteResultProtocol.StructuredVerdict.Failed -> {
                val error = RemoteResultProtocol.errorText(verdict.code, verdict.message)
                finishAsFailed(task.taskId, error, attemptId)
                RemoteTaskOutcome.Failed(error, verdict.code)
            }

            is RemoteResultProtocol.StructuredVerdict.Cancelled -> {
                val note = result.errorMessage?.takeIf { it.isNotBlank() } ?: "远端确认执行已取消。"
                finishAsCancelled(task.taskId, note, attemptId)
                RemoteTaskOutcome.Cancelled(note, verdict.code)
            }

            is RemoteResultProtocol.StructuredVerdict.Invalid -> {
                val error = RemoteResultProtocol.errorText(
                    RemoteResultProtocol.CODE_INVALID_PROTOCOL_STATUS,
                    "远端回包状态非法（status=${verdict.rawStatus}）：请检查目标设备 Hand 版本",
                )
                finishAsFailed(task.taskId, error, attemptId)
                RemoteTaskOutcome.Failed(error, RemoteResultProtocol.CODE_INVALID_PROTOCOL_STATUS)
            }

            RemoteResultProtocol.StructuredVerdict.Succeeded ->
                deliverSuccess(task, target, attemptId, result, fromStructured = true)

            RemoteResultProtocol.StructuredVerdict.NeedsLegacyFallback -> {
                // 旧 Hand（Result 无 status 字段）：保留原有正文分类器兼容判定，
                // 但失败必须带 legacy_verdict_fallback + fallback 来源显式标记，
                // 成功记审计事件——兜底不得伪装成结构化结果（T6-S2 §5）。
                when (val legacy = classifyRemoteMemberOutput(result.output)) {
                    is RemoteMemberVerdict.Failed -> {
                        val error = RemoteResultProtocol.errorPrefix(RemoteResultProtocol.CODE_LEGACY_VERDICT_FALLBACK) +
                            RemoteResultProtocol.sourcePrefix(RemoteResultProtocol.SOURCE_FALLBACK) +
                            legacy.reason
                        finishAsFailed(task.taskId, error, attemptId)
                        RemoteTaskOutcome.Failed(error, RemoteResultProtocol.CODE_LEGACY_VERDICT_FALLBACK)
                    }
                    RemoteMemberVerdict.Success ->
                        deliverSuccess(task, target, attemptId, result, fromStructured = false)
                }
            }
        }
    }

    /**
     * 成功交付（结构化 succeeded 或旧协议兜底 Success 共用）：产物校验、
     * 落态 review → completed（流水线 hold 停 review，P2 行为不变）。
     * [fromStructured] 只影响来源标记：兜底成功额外记
     * [RemoteResultProtocol.EVENT_REMOTE_RESULT_FALLBACK] 审计事件，
     * result 正文不加前缀（用户可见文案不污染）。
     */
    private suspend fun deliverSuccess(
        task: TaskRecord,
        target: RemoteTarget,
        attemptId: String?,
        result: HandEvent.Result,
        fromStructured: Boolean,
    ): RemoteTaskOutcome {
        val artifact = if (result.artifactPresent == true) {
            val verified = verifyOrRefetchArtifact(target, task.taskId, attemptId, result)
            if (verified == null) {
                val error = RemoteResultProtocol.errorText(
                    RemoteResultProtocol.CODE_ARTIFACT_VERIFY_FAILED,
                    "产物校验失败：已向远端重取 ${artifactRetryDelaysMs.size} 次仍不一致，未保存产物",
                )
                finishAsFailed(task.taskId, error, attemptId)
                return RemoteTaskOutcome.Failed(error, RemoteResultProtocol.CODE_ARTIFACT_VERIFY_FAILED)
            }
            storeArtifact(task.taskId, checkNotNull(attemptId), verified)
        } else null
        val delivery = if (artifact == null) result.output else "产物：$artifact\n\n${result.output}"
        if (!fromStructured) {
            taskStore.recordTaskEvent(
                task.taskId,
                RemoteResultProtocol.EVENT_REMOTE_RESULT_FALLBACK,
                "终态由旧协议正文分类器兜底判定（远端 Hand 未返回结构化 status）",
                "remote-dispatcher",
            )
        }
        finishAsCompleted(task.taskId, delivery, attemptId)
        return RemoteTaskOutcome.Completed(delivery, fromStructured)
    }

    /**
     * 结果未知落账（T6-S2 §4 情形 5b）：断线/客户端超时且交付恢复失败。
     * 落 failed（TaskStates 禁止扩状态集，无法新增 unknown 态）但
     * error_code=remote_result_unknown 显式区分，文案必须含“结果未知”，
     * 不许只显示普通“失败”；原始歧义说明（[detail]）进审计事件
     * （Muse 2026-11-15 批复 #1 条件）。
     */
    private fun finishAsUnknown(taskId: String, attemptId: String?, detail: String): RemoteTaskOutcome {
        val note = "远端连接中断或超时，本次执行结果未知（远端可能仍在执行或已完成）：" +
            "请在远端确认实际结果后重新派发。"
        taskStore.recordTaskEvent(
            taskId,
            RemoteResultProtocol.EVENT_REMOTE_RESULT_UNKNOWN,
            detail.take(500),
            "remote-dispatcher",
        )
        val error = RemoteResultProtocol.errorText(
            RemoteResultProtocol.CODE_REMOTE_RESULT_UNKNOWN,
            note,
        )
        finishAsFailed(taskId, error, attemptId)
        return RemoteTaskOutcome.Failed(error, RemoteResultProtocol.CODE_REMOTE_RESULT_UNKNOWN)
    }

    /**
     * Fetch the Host's persisted delivery record for [attemptId] with bounded
     * backoff. Retries transport failures and `artifact_unavailable`; stops on
     * terminal codes (nothing delivered, corrupt on the Host, stale attempt).
     */
    private suspend fun fetchDelivery(
        target: RemoteTarget,
        taskId: String,
        attemptId: String,
        delays: List<Long>,
    ): Fetch {
        for (wait in delays) {
            if (wait > 0) delay(wait)
            var result: HandEvent.Result? = null
            var blocked: HandEvent.Blocked? = null
            try {
                port.getArtifact(target, taskId, attemptId).collect { event ->
                    when (event) {
                        is HandEvent.Result -> result = event
                        is HandEvent.Blocked -> blocked = event
                        else -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue // Transport failure: next bounded attempt.
            }
            result?.let { return Fetch.Delivered(it) }
            if (blocked?.code in TERMINAL_ARTIFACT_CODES) return Fetch.Terminal(blocked?.code)
        }
        return Fetch.Exhausted
    }

    /**
     * Verify the artifact carried by [first]; when it fails integrity (bytes
     * damaged or truncated in transit) refetch the Host's persisted record
     * with bounded backoff. Returns null when no intact copy was obtained.
     */
    private suspend fun verifyOrRefetchArtifact(
        target: RemoteTarget,
        taskId: String,
        attemptId: String?,
        first: HandEvent.Result,
    ): VerifiedArtifact? {
        try {
            return verifyArtifact(first)
        } catch (_: ArtifactIntegrityException) {
            // Fall through to bounded refetch below.
        }
        val attempt = attemptId ?: return null
        for (wait in artifactRetryDelaysMs) {
            val again = when (val fetched = fetchDelivery(target, taskId, attempt, listOf(wait))) {
                is Fetch.Delivered -> fetched.result
                is Fetch.Terminal -> return null
                Fetch.Exhausted -> continue
            }
            if (again.artifactPresent != true) return null
            try {
                return verifyArtifact(again)
            } catch (_: ArtifactIntegrityException) {
                // Damaged again: next bounded attempt.
            }
        }
        return null
    }

    private fun sha256Hex(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data)
        .joinToString("") { "%02x".format(it) }

    private fun verifyArtifact(result: HandEvent.Result): VerifiedArtifact {
        val encoded = result.artifactBase64 ?: throw ArtifactIntegrityException("产物内容缺失")
        val declared = result.artifactSha256?.lowercase()
            ?: throw ArtifactIntegrityException("产物缺少校验和")
        if (!declared.matches(Regex("[0-9a-f]{64}"))) throw ArtifactIntegrityException("产物校验和格式无效")
        require(encoded.length <= 1_398_104) { "产物过大" }
        val bytes = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            throw ArtifactIntegrityException("产物编码损坏")
        }
        require(bytes.size <= 1_048_576) { "产物过大" }
        val hash = sha256Hex(bytes)
        if (!MessageDigest.isEqual(hash.toByteArray(), declared.toByteArray())) {
            throw ArtifactIntegrityException("产物校验失败")
        }
        return VerifiedArtifact(bytes, hash)
    }

    private fun storeArtifact(taskId: String, attemptId: String, artifact: VerifiedArtifact): String {
        val directory = checkNotNull(artifactDirectory) { "产物目录不可用" }
        val bytes = artifact.bytes
        val hash = artifact.sha256
        check(directory.isDirectory || directory.mkdirs()) { "无法创建产物目录" }
        val key = sha256Hex("$taskId\n$attemptId".toByteArray())
        val destination = File(directory, "$key-$hash.bin")
        val temp = File.createTempFile("delivery-", ".tmp", directory)
        try {
            temp.outputStream().use { it.write(bytes); it.fd.sync() }
            // Re-read what landed on disk before publishing it.
            check(sha256Hex(temp.readBytes()) == hash) { "产物落盘校验失败" }
            check(temp.renameTo(destination)) { "无法保存任务产物" }
        } finally {
            temp.delete()
        }
        return destination.name
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
