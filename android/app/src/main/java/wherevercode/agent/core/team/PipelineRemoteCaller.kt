package wherevercode.agent.core.team

import kotlinx.coroutines.withTimeoutOrNull
import wherevercode.agent.core.mcp.HandEvent
import wherevercode.agent.core.mcp.RemoteAgentChatPort
import wherevercode.agent.core.mcp.RemoteTarget
import wherevercode.agent.core.task.PipelineAgentCallResult
import wherevercode.agent.core.task.PipelineAgentCaller

/**
 * 远端成员调用方（P2 流水线自动执行，2026-11-11）：经 Hand
 * [RemoteAgentChatPort.runAgent] 把 prompt 发给对端设备上的 Agent，
 * 等 Result 回原文；Blocked / 流断无终态 / 超时（默认 600 秒，与
 * [RemoteTaskDispatcher.DEFAULT_TIMEOUT_SECONDS] 同口径）→ Failure。
 *
 * 只做“一句话调用”：不认领任务、不写任务态、不碰租约——流水线
 * runner 的规划/审查调用不需要任务认领（那是编码执行的活）。
 * [targetFor] 找不到目标（成员未配对、无远端目标）→ [canCall] 为
 * false，合成调用方会改走本机大脑；绝不编一个目标出来。
 */
class RemotePipelineAgentCaller(
    private val port: RemoteAgentChatPort,
    private val targetFor: (agentId: String?) -> RemoteTarget?,
    private val timeoutSeconds: Long = RemoteTaskDispatcher.DEFAULT_TIMEOUT_SECONDS,
) : PipelineAgentCaller {
    override fun canCall(agentId: String?, deviceId: String?): Boolean {
        if (agentId == null) return false
        return try {
            targetFor(agentId) != null
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun callAgent(
        taskId: String,
        agentId: String?,
        deviceId: String?,
        prompt: String,
        conversationId: String,
    ): PipelineAgentCallResult {
        val target = try {
            targetFor(agentId)
        } catch (e: Exception) {
            return PipelineAgentCallResult.Failure("远端目标解析失败：${e.message ?: "未知错误"}")
        } ?: return PipelineAgentCallResult.Failure("成员 $agentId 无远端目标（未配对或目标不可达）")
        var output: String? = null
        var blocked: HandEvent.Blocked? = null
        val finished = withTimeoutOrNull(timeoutSeconds * 1000) {
            try {
                port.runAgent(
                    target = target,
                    taskId = taskId,
                    text = prompt,
                    timeoutSeconds = timeoutSeconds,
                    goalVersion = 1,
                    epoch = 1,
                ).collect { event ->
                    when (event) {
                        is HandEvent.Result -> output = event.output
                        is HandEvent.Blocked -> blocked = event
                        else -> Unit
                    }
                }
                true
            } catch (e: Exception) {
                blocked = HandEvent.Blocked("远端调用异常：${e.message ?: "未知错误"}", "remote_call_error")
                true
            }
        }
        if (finished == null) {
            return PipelineAgentCallResult.Failure("远端调用超时（$timeoutSeconds 秒未返回）")
        }
        blocked?.let { return PipelineAgentCallResult.Failure(it.reason) }
        val out = output?.trim().orEmpty()
        if (out.isEmpty()) return PipelineAgentCallResult.Failure("远端未返回结果")
        // 2026-11-12（重试任务 4f3b597c-ae0d）：远端回的正文也可能明说
        // “没找到仓库，请提供路径”——这种需要信息/被挡住的汇报不是可用的
        // plan/verdict，绝不能当 Text 交给解析器：审查 verdict 的词扫描
        // 会把提到 approve 的求助正文误读成 APPROVE。先过分类器，
        // Failed 直接按失败回，调用方（runPlanner/runReview）落 failed。
        // 结构化 JSON 不受影响（分类器对 `{`/`[`/``` 开头的正文不判）。
        when (val verdict = classifyRemoteMemberOutput(out)) {
            is RemoteMemberVerdict.Failed -> return PipelineAgentCallResult.Failure(verdict.reason)
            RemoteMemberVerdict.Success -> Unit
        }
        return PipelineAgentCallResult.Text(out)
    }
}
