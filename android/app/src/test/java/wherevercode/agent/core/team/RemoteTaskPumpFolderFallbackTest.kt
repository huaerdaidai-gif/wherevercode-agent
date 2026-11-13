package wherevercode.agent.core.team

import wherevercode.agent.core.task.testTaskScheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.AgentDiscovery
import wherevercode.agent.brain.BrainKind
import wherevercode.agent.core.agent.AgentAccess
import wherevercode.agent.core.agent.AgentKind
import wherevercode.agent.core.agent.AgentRecord
import wherevercode.agent.core.mcp.HandEvent
import wherevercode.agent.core.mcp.RemoteAgentChatPort
import wherevercode.agent.core.mcp.RemoteControlCommand
import wherevercode.agent.core.mcp.RemoteTarget
import wherevercode.agent.core.project.InMemoryProjectStore
import wherevercode.agent.core.project.Project
import wherevercode.agent.core.project.ProjectLocation
import wherevercode.agent.core.project.dispatchBlockedReason
import wherevercode.agent.core.project.DispatchFolderResult
import wherevercode.agent.core.project.resolveDispatchFolder
import wherevercode.agent.core.security.PairingRecord
import wherevercode.agent.core.security.PairingState
import wherevercode.agent.core.task.InMemoryTaskStepStore
import wherevercode.agent.core.task.RoomTaskEventStore
import wherevercode.agent.core.task.RoomTaskStore
import wherevercode.agent.core.task.TaskDatabase
import wherevercode.agent.core.task.TaskEventKind
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskScheduler
import wherevercode.agent.core.transport.DiscoveredPeer
import wherevercode.agent.core.transport.RemoteDevice
import java.util.Collections

/**
 * V5 跨设备开发（2026-11-12，A5/D4/D7 新口径）：泵认领时按任务的
 * 项目解析派活文件夹。
 * - 项目文件夹命中时用检出文件夹执行，记一条 folder_resolved 事件
 *   （详情“工作目录”行从事件反查）；
 * - 无检出链接回落 Agent 文件夹时记一条 folder_fallback 事件；
 * - git 家且目标设备上无 DEVICE 检出时不认领、不派活：任务留
 *   queued + needs_attention + 原因，并记 not_dispatchable 事件，
 *   绝不派到空目录（D7 新口径）。
 */
@RunWith(RobolectricTestRunner::class)
class RemoteTaskPumpFolderFallbackTest {

    private var db: TaskDatabase? = null
    private val now = 1_700_000_000L

    @After
    fun tearDown() {
        db?.close()
    }

    private val peer = DiscoveredPeer(
        deviceId = "mac",
        displayName = "Mac",
        host = "192.168.1.4",
        port = 18743,
        fingerprintSha256 = "ab".repeat(32),
        capabilities = listOf("hand"),
    )
    private val device = RemoteDevice(
        deviceId = "mac",
        displayName = "Mac",
        host = "192.168.1.4",
        port = 18743,
        addedAtEpochSeconds = 1_794_334_408L,
    )
    private val pairing = PairingRecord(
        deviceId = "mac",
        displayName = "Mac",
        fingerprintSha256 = "ab".repeat(32),
        peerPublicKey = ByteArray(32) { 7 },
        state = PairingState.CONFIRMED,
        updatedAtEpochSeconds = 1_794_334_408L,
    )
    private val agent = AgentRecord(
        agentId = "codex-mac",
        displayName = "Codex Mac",
        kind = AgentKind.DEVICE,
        access = AgentAccess.CLI,
        brainKind = BrainKind.AGENT_RUNTIME,
        adapterId = "codex",
        binary = "codex",
        deviceId = "mac",
        deviceFolder = "/Users/example-user/v5-codex-work",
    )

    private class ScriptedPort(private val events: List<HandEvent>) : RemoteAgentChatPort {
        data class Call(val target: RemoteTarget, val taskId: String, val text: String)

        val calls = Collections.synchronizedList(mutableListOf<Call>())
        override fun runAgent(
            target: RemoteTarget,
            taskId: String,
            text: String,
            timeoutSeconds: Long,
            goalVersion: Long,
            epoch: Long,
        ): Flow<HandEvent> {
            calls += Call(target, taskId, text)
            return flowOf(*events.toTypedArray())
        }

        override fun sendControl(
            target: RemoteTarget,
            taskId: String,
            command: RemoteControlCommand,
        ): Flow<HandEvent> = flowOf()
    }

    private data class Env(
        val taskStore: RoomTaskStore,
        val events: RoomTaskEventStore,
        val pump: RemoteTaskPump,
        val port: ScriptedPort,
        val projectStore: InMemoryProjectStore,
    )

    private fun setup(projectStore: InMemoryProjectStore): Env {
        val context = RuntimeEnvironment.getApplication()
        val database = TaskDatabase.inMemory(context)
        db = database
        val taskStore = RoomTaskStore(database)
        val scheduler = testTaskScheduler(taskStore, AgentDiscovery(context))
        val stepStore = InMemoryTaskStepStore()
        val port = ScriptedPort(listOf(HandEvent.Result(3, "改好了，测试通过")))
        val dispatcher = RemoteTaskDispatcher(taskStore, scheduler, stepStore, port)
        val pump = RemoteTaskPump(
            taskStore = taskStore,
            scheduler = scheduler,
            stepStore = stepStore,
            dispatcher = dispatcher,
            targetResolver = { task, agentId ->
                if (agentId != "codex-mac") {
                    null
                } else {
                    val override = when (val result = resolveDispatchFolder(projectStore, task, agent)) {
                        is DispatchFolderResult.Resolved -> result.folder
                        is DispatchFolderResult.NotDispatchable -> null
                        null -> null
                    }
                    resolveRemoteTargetFromRecords(agent, device, pairing, override)
                }
            },
            notDispatchableReason = { task, agentId ->
                if (agentId != "codex-mac") null else dispatchBlockedReason(projectStore, task, agent)
            },
            projectStore = projectStore,
        )
        return Env(taskStore, RoomTaskEventStore(database), pump, port, projectStore)
    }

    private fun queuedTask(taskId: String, projectId: String?) =
        TaskRecord(
            taskId = taskId,
            title = "改代码",
            description = "改代码并跑测试",
            state = "queued",
            projectId = projectId,
            assignedAgentId = "codex-mac",
            createdAt = now,
            updatedAt = now,
            version = 1,
        )

    private suspend fun awaitTerminal(env: Env, taskId: String, timeoutMs: Long = 10_000L): TaskRecord {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            val task = env.taskStore.getTask(taskId)!!
            if (task.state in setOf("completed", "failed", "cancelled")) return task
            delay(50)
        }
        throw AssertionError("任务 $taskId 在 ${timeoutMs}ms 内未落终态，当前态=${env.taskStore.getTask(taskId)?.state}")
    }

    private fun projectStoreWithDeviceHome(): InMemoryProjectStore {
        val store = InMemoryProjectStore()
        store.saveProject(Project(projectId = "wherevercode-agent", name = "WhereverCode Agent"))
        store.saveLocation(
            ProjectLocation.create(
                "wherevercode-agent",
                "mac",
                "/Users/example-user/Projects/wherevercode-agent",
            ),
        )
        return store
    }

    private fun projectStoreWithGitHome(): InMemoryProjectStore {
        val store = InMemoryProjectStore()
        store.saveProject(Project(projectId = "wherevercode-agent", name = "WhereverCode Agent"))
        store.saveLocation(
            ProjectLocation.createGithub("wherevercode-agent", "huaerdaidai-gif", "wherevercode-agent"),
        )
        return store
    }

    @Test
    fun `project checkout folder is used and a resolved event is recorded`() = runBlocking {
        val env = setup(projectStoreWithDeviceHome())
        env.taskStore.createTask(queuedTask("task-project", "wherevercode-agent"))

        env.pump.pumpOnce()

        val task = awaitTerminal(env, "task-project")
        assertEquals("completed", task.state)
        assertEquals("/Users/example-user/Projects/wherevercode-agent", env.port.calls[0].target.folder)
        assertTrue(env.port.calls[0].text.contains("工作目录：/Users/example-user/Projects/wherevercode-agent"))
        assertEquals(
            0,
            env.events.listEvents("task-project").count { it.kind == TaskEventKind.FOLDER_FALLBACK },
        )
        val resolved = env.events.listEvents("task-project")
            .firstOrNull { it.kind == TaskEventKind.FOLDER_RESOLVED }
        assertTrue("应记一条 folder_resolved 事件", resolved != null)
        val reason = resolved!!.reason ?: ""
        assertTrue(reason.contains("WhereverCode Agent"))
        assertTrue(reason.contains("mac"))
        assertTrue(reason.contains("/Users/example-user/Projects/wherevercode-agent"))
    }

    @Test
    fun `git home task with no checkout on the device stays queued with needs attention`() = runBlocking {
        val env = setup(projectStoreWithGitHome())
        env.taskStore.createTask(queuedTask("task-git", "wherevercode-agent"))

        env.pump.pumpOnce()

        val task = env.taskStore.getTask("task-git")!!
        assertEquals("queued", task.state)
        assertTrue("不可派任务应置 needs_attention", task.needsAttention)
        assertTrue((task.error ?: "").contains("代码不在设备上"))
        assertTrue("原因须写清 git 家地址", (task.error ?: "").contains("huaerdaidai-gif/wherevercode-agent"))
        assertTrue("远端一次都不应被叫到", env.port.calls.isEmpty())
        val blocked = env.events.listEvents("task-git")
            .firstOrNull { it.kind == TaskEventKind.NOT_DISPATCHABLE }
        assertTrue("应记一条 not_dispatchable 事件", blocked != null)
        assertTrue((blocked!!.reason ?: "").contains("代码不在设备上"))
        assertTrue((blocked.reason ?: "").contains("huaerdaidai-gif/wherevercode-agent"))
        // 再跑一轮泵：原因与事件都不重复写。
        env.pump.pumpOnce()
        val again = env.taskStore.getTask("task-git")!!
        assertEquals("queued", again.state)
        assertEquals(
            1,
            env.events.listEvents("task-git").count { it.kind == TaskEventKind.NOT_DISPATCHABLE },
        )
    }

    @Test
    fun `unbound task falls back with an event that says so`() = runBlocking {
        val env = setup(projectStoreWithDeviceHome())
        env.taskStore.createTask(queuedTask("task-unbound", null))

        env.pump.pumpOnce()

        val task = awaitTerminal(env, "task-unbound")
        assertEquals("completed", task.state)
        assertEquals("/Users/example-user/v5-codex-work", env.port.calls[0].target.folder)
        val fallback = env.events.listEvents("task-unbound")
            .firstOrNull { it.kind == TaskEventKind.FOLDER_FALLBACK }
        assertTrue("应记一条 folder_fallback 事件", fallback != null)
        assertTrue((fallback!!.reason ?: "").contains("未绑定项目"))
    }
}
