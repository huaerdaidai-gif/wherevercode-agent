package wherevercode.agent.core.team

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import wherevercode.agent.core.mcp.HandEvent
import wherevercode.agent.core.mcp.RemoteAgentChatPort
import wherevercode.agent.core.mcp.RemoteControlCommand
import wherevercode.agent.core.mcp.RemoteTarget
import wherevercode.agent.core.transport.DiscoveredPeer

/**
 * P3 只读扇出执行器测试（2026-11-11）。
 *
 * 证明三件事：① 各分支并行跑、只收终态摘要（Progress 不落库）；
 * ② 一个分支失败/超时不影响其它分支；③ 扇出不建 P0 任务——调用
 * 的 taskId 是 `fanout-<runId>-<i>` 这样的调用标识，Runner 全程
 * 只写 FanoutStore，不碰 TaskStore（本测试里根本没有 TaskStore）。
 */
@RunWith(RobolectricTestRunner::class)
class TeamFanoutTest {

    private fun peer(deviceId: String) = DiscoveredPeer(
        deviceId = deviceId,
        displayName = deviceId,
        host = "127.0.0.1",
        port = 18743,
        fingerprintSha256 = "ab".repeat(32),
        capabilities = listOf("hand"),
    )

    private fun target(agentId: String, deviceId: String) =
        RemoteTarget(peer = peer(deviceId), agentId = agentId, folder = "/work")

    /** 测试替身：按 agentId 回放事件脚本，记下收到的调用。 */
    private class ScriptedPort(
        private val scripts: Map<String, List<HandEvent>>,
        private val hanging: Set<String> = emptySet(),
    ) : RemoteAgentChatPort {
        data class Call(val target: RemoteTarget, val taskId: String, val text: String)

        val calls = mutableListOf<Call>()

        override fun runAgent(
            target: RemoteTarget,
            taskId: String,
            text: String,
            timeoutSeconds: Long,
            goalVersion: Long,
            epoch: Long,
        ): Flow<HandEvent> {
            calls += Call(target, taskId, text)
            if (target.agentId in hanging) {
                return flow { delay(60_000) }
            }
            return flowOf(*(scripts[target.agentId] ?: emptyList()).toTypedArray())
        }

        override fun sendControl(
            target: RemoteTarget,
            taskId: String,
            command: RemoteControlCommand,
        ): Flow<HandEvent> = flowOf()
    }

    private val now = 1_700_000_000L

    private fun runner(port: ScriptedPort, store: InMemoryFanoutStore): TeamFanoutRunner {
        var branchSeq = 0
        return TeamFanoutRunner(
            port = port,
            store = store,
            nowSeconds = { now },
            newRunId = { "run-1" },
            newBranchId = { "b-${branchSeq++}" },
            timeoutMarginSeconds = 0,
        )
    }

    @Test
    fun `all branches done collect summaries and the run finishes`() = runBlocking {
        val store = InMemoryFanoutStore()
        val port = ScriptedPort(
            mapOf(
                "codex" to listOf(
                    HandEvent.Progress(1, "看着呢", 50),
                    HandEvent.Result(2, "行，但要先补测试。"),
                ),
                "codebuddy" to listOf(HandEvent.Result(1, "没问题。")),
            ),
        )
        val run = runner(port, store).run(
            brief = "[只读咨询] 这个方案行不行？",
            targets = listOf(
                FanoutCandidate("codex", "mac", "Mac 上的 Codex") to
                    target("codex", "mac"),
                FanoutCandidate("codebuddy", "remote-device", "VPS 上的 CodeBuddy") to
                    target("codebuddy", "remote-device"),
            ),
            timeoutSeconds = 5,
        )
        assertEquals("run-1", run.runId)
        assertEquals(2, run.branches.size)
        assertEquals(FanoutBranchStatus.DONE, run.branches[0].status)
        assertEquals("行，但要先补测试。", run.branches[0].summary)
        assertEquals(FanoutBranchStatus.DONE, run.branches[1].status)
        // Progress 不落库，只收终态摘要。
        assertEquals("没问题。", run.branches[1].summary)
        assertEquals(now, run.finishedAtEpochSeconds)
        // 持久化里也能读回同一轮。
        assertEquals(run, store.getRun("run-1"))
        // 调用标识是 fanout-<runId>-<i>，不是任务 id；简报原文到了分支。
        assertEquals(listOf("fanout-run-1-0", "fanout-run-1-1"), port.calls.map { it.taskId })
        assertTrue(port.calls.all { "[只读咨询]" in it.text })
    }

    @Test
    fun `a blocked branch fails alone and others still finish`() = runBlocking {
        val store = InMemoryFanoutStore()
        val port = ScriptedPort(
            mapOf(
                "codex" to listOf(
                    HandEvent.Blocked("未配对", "NOT_AUTHORIZED"),
                ),
                "codebuddy" to listOf(HandEvent.Result(1, "我看了，没问题。")),
            ),
        )
        val run = runner(port, store).run(
            brief = "[只读咨询] 看看？",
            targets = listOf(
                FanoutCandidate("codex", "mac", "Mac 上的 Codex") to
                    target("codex", "mac"),
                FanoutCandidate("codebuddy", "remote-device", "VPS 上的 CodeBuddy") to
                    target("codebuddy", "remote-device"),
            ),
            timeoutSeconds = 5,
        )
        assertEquals(FanoutBranchStatus.FAILED, run.branches[0].status)
        assertEquals("未配对", run.branches[0].error)
        assertEquals(FanoutBranchStatus.DONE, run.branches[1].status)
    }

    @Test
    fun `a hanging branch times out instead of blocking the run`() = runBlocking {
        val store = InMemoryFanoutStore()
        val port = ScriptedPort(
            mapOf("codebuddy" to listOf(HandEvent.Result(1, "好。"))),
            hanging = setOf("codex"),
        )
        val run = runner(port, store).run(
            brief = "[只读咨询] 看看？",
            targets = listOf(
                FanoutCandidate("codex", "mac", "Mac 上的 Codex") to
                    target("codex", "mac"),
                FanoutCandidate("codebuddy", "remote-device", "VPS 上的 CodeBuddy") to
                    target("codebuddy", "remote-device"),
            ),
            timeoutSeconds = 1,
        )
        assertEquals(FanoutBranchStatus.TIMEOUT, run.branches[0].status)
        assertEquals(FanoutBranchStatus.DONE, run.branches[1].status)
    }

    @Test
    fun `an overlong summary is truncated to the limit`() = runBlocking {
        val store = InMemoryFanoutStore()
        val long = "x".repeat(TeamChatLimits.FANOUT_SUMMARY_CHARS + 500)
        val port = ScriptedPort(
            mapOf("codex" to listOf(HandEvent.Result(1, long))),
        )
        val run = runner(port, store).run(
            brief = "[只读咨询] 看看？",
            targets = listOf(
                FanoutCandidate("codex", "mac", "Mac 上的 Codex") to
                    target("codex", "mac"),
            ),
            timeoutSeconds = 5,
        )
        assertEquals(TeamChatLimits.FANOUT_SUMMARY_CHARS, run.branches[0].summary!!.length)
    }

    @Test
    fun `a branch replying need-info fails instead of done`() = runBlocking {
        // 2026-11-12（重试任务 4f3b597c-ae0d）：分支回“没找到仓库，请提供
        // 路径”是没答上问题，不是答上了——记 DONE 会在频道页显示“已回”。
        val store = InMemoryFanoutStore()
        val port = ScriptedPort(
            mapOf(
                "codex" to listOf(
                    HandEvent.Result(
                        1,
                        "在当前工作区中没有找到 wherevercode-agent 仓库，请提供该仓库在此环境中的路径。",
                    ),
                ),
                "codebuddy" to listOf(HandEvent.Result(1, "我看了，没问题。")),
            ),
        )
        val run = runner(port, store).run(
            brief = "[只读咨询] 看看？",
            targets = listOf(
                FanoutCandidate("codex", "mac", "Mac 上的 Codex") to
                    target("codex", "mac"),
                FanoutCandidate("codebuddy", "remote-device", "VPS 上的 CodeBuddy") to
                    target("codebuddy", "remote-device"),
            ),
            timeoutSeconds = 5,
        )
        assertEquals(FanoutBranchStatus.FAILED, run.branches[0].status)
        assertTrue(run.branches[0].error!!.contains("没有找到"))
        assertEquals(FanoutBranchStatus.DONE, run.branches[1].status)
    }
}
