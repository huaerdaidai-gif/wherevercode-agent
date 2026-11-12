package wherevercode.agent.core.task

import java.util.UUID

/**
 * 重试副本的构造（2026-11-11，任务状态诚实修复）。
 *
 * 旧 [MainActivity.retryTask] 只抄 title/description/state/时间/
 * version/brainId，丢了 projectId/assignedAgentId/memberId：副本无
 * 执行者、[wherevercode.agent.core.team.RemoteTaskPump] 永远不认领；
 * 更糟的是 [LocalTaskRunner.pickNext] 只认 assignedAgentId == null
 * 或 == "local-brain" 的任务，丢了指派的副本会被本地执行器认领、在
 * 手机大脑上本地跑一次本该去远端 Agent 的任务——错设备执行。
 *
 * 本函数是重试副本的唯一构造点：保留执行所需的全部指派信息，
 * parentTaskId 指回原任务（供任务库归并显示重试链），新 id、
 * state="queued"、version=1、时间戳取 now，清掉 result/error
 * （步骤按 taskId 存，副本新 id 下自然无旧步骤）。
 */
object TaskRetry {
    fun buildRetryCopy(original: TaskRecord, nowSeconds: Long): TaskRecord =
        TaskRecord(
            taskId = UUID.randomUUID().toString(),
            projectId = original.projectId,
            sessionId = null,
            title = original.title,
            description = original.description,
            state = "queued",
            assignedAgentId = original.assignedAgentId,
            parentTaskId = original.taskId,
            dependencies = original.dependencies,
            priority = original.priority,
            createdAt = nowSeconds,
            updatedAt = nowSeconds,
            result = null,
            error = null,
            version = 1,
            brainId = original.brainId,
            memberId = original.memberId,
            goalVersion = original.goalVersion,
            authEpoch = original.authEpoch,
        )
}
