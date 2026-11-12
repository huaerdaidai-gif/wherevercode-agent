package wherevercode.agent.core.team

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import wherevercode.agent.core.mcp.HandEvent
import wherevercode.agent.core.mcp.RemoteAgentChatPort
import wherevercode.agent.core.mcp.RemoteTarget

/**
 * P3 只读扇出执行器（2026-11-11）。
 *
 * 一条任务/消息同时问最多 [TeamChatLimits.MAX_FANOUT_BRANCHES] 个
 * Agent，只读看，不并行写：
 * - 每个分支是一次一次性的 [RemoteAgentChatPort.runAgent] 调用，带
 *   [TeamChatRules.buildFanoutBrief] 的只读指令；分支之间不共享状态、
 *   不互相看见对方的回答。
 * - 绝不建 P0 任务：taskId 只用作 `fanout-<runId>-<i>` 这样的调用
 *   标识（Hand 侧的调用 id），不写 TaskStore、不认领、不提交。
 * - 每个分支只收一个终态回执：Result 的 output 截断到
 *   [TeamChatLimits.FANOUT_SUMMARY_CHARS] 字，Blocked 记原因，超时
 *   记 TIMEOUT。分支失败不影响其它分支。
 * - 结果持久化到 [FanoutStore]（Room v10），供频道页展示与复查。
 *
 * 线程模型：[run] 是挂起函数，在调用方的协程里并行跑各分支；超时
 * 用 [withTimeoutOrNull] 兜底（runAgent 自己的 timeoutSeconds 是第
 * 一道，[timeoutMarginSeconds] 是第二道，防止 Flow 一直不终态）。
 */
class TeamFanoutRunner(
    private val port: RemoteAgentChatPort,
    private val store: FanoutStore,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val newRunId: () -> String = { newFanoutRunId() },
    private val newBranchId: () -> String = { newFanoutBranchId() },
    /**
     * runAgent 自己的 timeoutSeconds 之外的兜底余量（秒）：Flow 一直
     * 不终态时，超过 timeoutSeconds + 余量即判 TIMEOUT。生产默认 30
     * 秒；测试传 0 才能快速覆盖超时路径。
     */
    private val timeoutMarginSeconds: Long = 30,
) {
    /**
     * 跑一次扇出。[targets] 是已解析好的（候选，RemoteTarget）对——
     * 解析（设备 + 配对 + 文件夹）由调用方用
     * [resolveRemoteTargetFromRecords] 做，本执行器不读任何 store。
     * 调用方应先用 [TeamChatRules.selectFanoutTargets] 限流到 ≤5。
     */
    suspend fun run(
        brief: String,
        targets: List<Pair<FanoutCandidate, RemoteTarget>>,
        taskId: String? = null,
        timeoutSeconds: Long = TeamChatLimits.FANOUT_TIMEOUT_SECONDS,
    ): FanoutRun {
        val runId = newRunId()
        val createdAt = nowSeconds()
        val branches = targets.map { (candidate, _) ->
            FanoutBranch(
                branchId = newBranchId(),
                runId = runId,
                agentId = candidate.agentId,
                deviceId = candidate.deviceId,
                displayName = candidate.displayName,
                createdAtEpochSeconds = createdAt,
            )
        }
        store.insertRun(
            FanoutRun(
                runId = runId,
                question = brief,
                taskId = taskId,
                branches = branches,
                createdAtEpochSeconds = createdAt,
            ),
        )
        val finished = coroutineScope {
            branches.zip(targets).mapIndexed { index, (branch, pair) ->
                async {
                    runBranch(
                        branch = branch,
                        target = pair.second,
                        callId = "fanout-$runId-$index",
                        brief = brief,
                        timeoutSeconds = timeoutSeconds,
                    )
                }
            }.map { it.await() }
        }
        store.finishRun(runId, nowSeconds())
        return FanoutRun(
            runId = runId,
            question = brief,
            taskId = taskId,
            branches = finished,
            createdAtEpochSeconds = createdAt,
            finishedAtEpochSeconds = nowSeconds(),
        )
    }

    private suspend fun runBranch(
        branch: FanoutBranch,
        target: RemoteTarget,
        callId: String,
        brief: String,
        timeoutSeconds: Long,
    ): FanoutBranch {
        val running = branch.copy(status = FanoutBranchStatus.RUNNING)
        store.updateBranch(running)
        var failure: String? = null
        var timedOut = false
        val terminal = try {
            val event = withTimeoutOrNull((timeoutSeconds + timeoutMarginSeconds) * 1000) {
                port.runAgent(
                    target = target,
                    taskId = callId,
                    text = brief,
                    timeoutSeconds = timeoutSeconds,
                ).firstOrNull { it is HandEvent.Result || it is HandEvent.Blocked }
            }
            if (event == null) timedOut = true
            event
        } catch (e: Exception) {
            failure = (e.message ?: e.javaClass.simpleName).take(300)
            null
        }
        val finished = when {
            terminal is HandEvent.Result -> {
                // 2026-11-12（重试任务 4f3b597c-ae0d）：分支回的正文也可能
                // 明说“没找到仓库，请提供路径”——这是没答上问题，不是答上了，
                // 不能记 DONE（频道页会显示“已回”）。先过分类器，
                // Failed → FAILED，原因原样展示；Success 才记 DONE。
                when (val verdict = classifyRemoteMemberOutput(terminal.output)) {
                    is RemoteMemberVerdict.Failed -> running.copy(
                        status = FanoutBranchStatus.FAILED,
                        error = verdict.reason.take(300),
                        finishedAtEpochSeconds = nowSeconds(),
                    )
                    RemoteMemberVerdict.Success -> running.copy(
                        status = FanoutBranchStatus.DONE,
                        summary = terminal.output
                            .take(TeamChatLimits.FANOUT_SUMMARY_CHARS),
                        finishedAtEpochSeconds = nowSeconds(),
                    )
                }
            }
            terminal is HandEvent.Blocked -> running.copy(
                status = FanoutBranchStatus.FAILED,
                error = terminal.reason.take(300),
                finishedAtEpochSeconds = nowSeconds(),
            )
            failure != null -> running.copy(
                status = FanoutBranchStatus.FAILED,
                error = failure,
                finishedAtEpochSeconds = nowSeconds(),
            )
            timedOut -> running.copy(
                status = FanoutBranchStatus.TIMEOUT,
                error = "分支在 ${timeoutSeconds} 秒内没有回终态。",
                finishedAtEpochSeconds = nowSeconds(),
            )
            else -> running.copy(
                status = FanoutBranchStatus.FAILED,
                error = "分支没有回终态。",
                finishedAtEpochSeconds = nowSeconds(),
            )
        }
        store.updateBranch(finished)
        return finished
    }
}
