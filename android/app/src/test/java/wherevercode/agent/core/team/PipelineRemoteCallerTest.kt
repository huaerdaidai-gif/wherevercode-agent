package wherevercode.agent.core.team

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import wherevercode.agent.core.transport.DiscoveredPeer
import wherevercode.agent.core.mcp.HandEvent
import wherevercode.agent.core.mcp.RemoteAgentChatPort
import wherevercode.agent.core.mcp.RemoteControlCommand
import wherevercode.agent.core.mcp.RemoteTarget
import wherevercode.agent.core.task.PipelineAgentCallResult

/**
 * 远端成员调用方（P2 自动执行，2026-11-11）：Result 回原文、
 * Blocked / 无终态 / 超时 / 无目标一律 Failure，不编结果。
 */
@RunWith(RobolectricTestRunner::class)
class PipelineRemoteCallerTest {
    private val peer = DiscoveredPeer(
        deviceId = "mac",
        displayName = "Mac",
        host = "192.168.1.7",
        port = 18743,
        fingerprintSha256 = "ab".repeat(32),
        capabilities = listOf("hand"),
    )
    private val target = RemoteTarget(peer = peer, agentId = "codex-mac", folder = "/tmp/work")

    private class ScriptedPort(
        private val events: Flow<HandEvent>,
    ) : RemoteAgentChatPort {
        override fun runAgent(
            target: RemoteTarget,
            taskId: String,
            text: String,
            timeoutSeconds: Long,
            goalVersion: Long,
            epoch: Long,
        ): Flow<HandEvent> = events

        override fun sendControl(
            target: RemoteTarget,
            taskId: String,
            command: RemoteControlCommand,
        ): Flow<HandEvent> = flowOf()
    }

    private fun caller(port: RemoteAgentChatPort, targetFor: (String?) -> RemoteTarget? = { target }) =
        RemotePipelineAgentCaller(port, targetFor)

    @Test
    fun resultReturnsText() = runBlocking {
        val result = caller(ScriptedPort(flowOf(HandEvent.Result(1, "approve"))))
            .callAgent("t1", "codex-mac", "mac", "审查", "pipeline-review-t1-r0")
        assertEquals(PipelineAgentCallResult.Text("approve"), result)
    }

    @Test
    fun blockedReturnsFailure() = runBlocking {
        val result = caller(ScriptedPort(flowOf(HandEvent.Blocked("远端拒绝：未授权", "not_allowed"))))
            .callAgent("t1", "codex-mac", "mac", "审查", "pipeline-review-t1-r0")
        assertTrue(result is PipelineAgentCallResult.Failure)
        assertEquals("远端拒绝：未授权", (result as PipelineAgentCallResult.Failure).error)
    }

    @Test
    fun noResultReturnsFailure() = runBlocking {
        val result = caller(ScriptedPort(flowOf(HandEvent.Progress(1, "干活中"))))
            .callAgent("t1", "codex-mac", "mac", "审查", "pipeline-review-t1-r0")
        assertTrue(result is PipelineAgentCallResult.Failure)
        assertEquals("远端未返回结果", (result as PipelineAgentCallResult.Failure).error)
    }

    @Test
    fun noTargetCannotCall() = runBlocking {
        val c = caller(ScriptedPort(flowOf()), targetFor = { null })
        assertFalse(c.canCall("codex-mac", "mac"))
        val result = c.callAgent("t1", "codex-mac", "mac", "审查", "pipeline-review-t1-r0")
        assertTrue(result is PipelineAgentCallResult.Failure)
    }

    @Test
    fun timeoutReturnsFailure() = runBlocking {
        val slow = flow<HandEvent> {
            delay(3000)
            emit(HandEvent.Result(1, "approve"))
        }
        val result = RemotePipelineAgentCaller(ScriptedPort(slow), { target }, timeoutSeconds = 1)
            .callAgent("t1", "codex-mac", "mac", "审查", "pipeline-review-t1-r0")
        assertTrue(result is PipelineAgentCallResult.Failure)
        assertTrue((result as PipelineAgentCallResult.Failure).error.contains("超时"))
    }

    @Test
    fun needInfoResultReturnsFailureWithRealReason() = runBlocking {
        // 2026-11-12（重试任务 4f3b597c-ae0d）：远端回“没找到仓库，请提供
        // 路径”不是可用的 plan/verdict，必须按失败回，不能当 Text 交给
        // 解析器（审查词扫描会把提到 approve 的求助正文误读成 APPROVE）。
        val output = "在当前工作区 `/Users/example-user/v5-codex-work` 中没有找到 `wherevercode-agent` 仓库或 Git 元数据，" +
            "因此尚未创建分支、文件或提交。请提供该仓库在此环境中的路径。"
        val result = caller(ScriptedPort(flowOf(HandEvent.Result(1, output))))
            .callAgent("t1", "codex-mac", "mac", "审查", "pipeline-review-t1-r0")
        assertTrue(result is PipelineAgentCallResult.Failure)
        assertEquals(output, (result as PipelineAgentCallResult.Failure).error)
    }

    @Test
    fun jsonPlanTextPassesThrough() = runBlocking {
        // 结构化 JSON 不按正文启发式判：首行含“尚未创建”也放行，解析器定。
        val json = """{"goal": "修复尚未创建分支的问题", "steps": []}"""
        val result = caller(ScriptedPort(flowOf(HandEvent.Result(1, json))))
            .callAgent("t1", "codex-mac", "mac", "规划", "pipeline-plan-t1")
        assertEquals(PipelineAgentCallResult.Text(json), result)
    }
}
