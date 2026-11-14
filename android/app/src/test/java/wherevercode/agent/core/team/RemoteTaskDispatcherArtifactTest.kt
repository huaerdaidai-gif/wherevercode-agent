package wherevercode.agent.core.team

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.AgentDiscovery
import wherevercode.agent.core.mcp.HandClient
import wherevercode.agent.core.mcp.HandEvent
import wherevercode.agent.core.mcp.RemoteAgentChatPort
import wherevercode.agent.core.mcp.RemoteControlCommand
import wherevercode.agent.core.mcp.RemoteTarget
import wherevercode.agent.core.task.InMemoryTaskStepStore
import wherevercode.agent.core.task.RoomTaskStore
import wherevercode.agent.core.task.TaskDatabase
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.testTaskScheduler
import wherevercode.agent.core.transport.DiscoveredPeer

/**
 * Cross-device delivery receive side: bounded retry with backoff and
 * integrity checks (declared SHA-256 must match the bytes) before anything
 * is stored or the task is marked completed.
 */
@RunWith(RobolectricTestRunner::class)
class RemoteTaskDispatcherArtifactTest {

    private lateinit var db: TaskDatabase
    private lateinit var store: RoomTaskStore
    private lateinit var artifactDir: File
    private val target = RemoteTarget(
        peer = DiscoveredPeer("remote-host", "remote", "192.0.2.10", 18743, "ab".repeat(32), listOf("hand")),
        agentId = AGENT, folder = "/workspace",
    )

    private val content = "delivered file".toByteArray()
    private val good = HandEvent.Result(
        seq = 2, output = "RESULT: done", artifactPresent = true,
        artifactBase64 = Base64.getEncoder().encodeToString(content), artifactSha256 = sha256(content),
    )
    /** Same declared hash, bytes damaged in transit. */
    private val damaged = good.copy(artifactBase64 = Base64.getEncoder().encodeToString("delivered fil3".toByteArray()))

    /** Port whose run stream and artifact fetches are scripted per test. */
    private class ScriptedPort(
        private val runEvents: List<HandEvent>,
        fetches: List<() -> Flow<HandEvent>>,
    ) : RemoteAgentChatPort {
        private val queue = ArrayDeque(fetches)
        val fetchCalls = Collections.synchronizedList(mutableListOf<String>())
        override fun runAgent(
            target: RemoteTarget, taskId: String, text: String, timeoutSeconds: Long, goalVersion: Long, epoch: Long,
        ): Flow<HandEvent> = flowOf(*runEvents.toTypedArray())

        override fun getArtifact(target: RemoteTarget, taskId: String, attemptId: String): Flow<HandEvent> {
            fetchCalls += attemptId
            val next = queue.removeFirstOrNull() ?: return flowOf(HandEvent.Blocked("none", "artifact_missing"))
            return next()
        }

        override fun sendControl(target: RemoteTarget, taskId: String, command: RemoteControlCommand): Flow<HandEvent> =
            flowOf()
    }

    @Before
    fun setUp() {
        val ctx = RuntimeEnvironment.getApplication()
        db = TaskDatabase.inMemory(ctx)
        store = RoomTaskStore(db)
        artifactDir = File(ctx.cacheDir, "artifacts-${System.nanoTime()}")
    }

    @After
    fun tearDown() {
        db.close()
        artifactDir.deleteRecursively()
    }

    private fun run(port: ScriptedPort, id: String = "task-${System.nanoTime()}"): Pair<RemoteTaskOutcome, TaskRecord> =
        runBlocking {
            val scheduler = testTaskScheduler(store, AgentDiscovery(RuntimeEnvironment.getApplication()))
            store.createTask(TaskRecord(taskId = id, title = id, state = "queued", assignedAgentId = AGENT))
            val claimed = checkNotNull(scheduler.claimTaskForMember(id, AGENT, "att-1"))
            val dispatcher = RemoteTaskDispatcher(
                store, scheduler, InMemoryTaskStepStore(), port,
                artifactDirectory = artifactDir,
                artifactRetryDelaysMs = listOf(0L, 0L, 0L),
            )
            val outcome = dispatcher.execute(claimed, target)
            outcome to checkNotNull(store.getTask(id))
        }

    private fun storedFiles() = artifactDir.listFiles()?.filter { it.name.endsWith(".bin") }.orEmpty()

    @Test
    fun `intact artifact is verified and stored without any refetch`() {
        val port = ScriptedPort(listOf(good), emptyList())
        val (outcome, task) = run(port)
        assertTrue(outcome is RemoteTaskOutcome.Completed)
        assertEquals("completed", task.state)
        assertEquals(0, port.fetchCalls.size)
        val files = storedFiles()
        assertEquals(1, files.size)
        assertTrue(files[0].readBytes().contentEquals(content))
        assertTrue(files[0].name.endsWith("-${sha256(content)}.bin"))
    }

    @Test
    fun `artifact damaged in transit is refetched from the Host and verified`() {
        val port = ScriptedPort(listOf(damaged), listOf({ flowOf(good) }))
        val (outcome, task) = run(port)
        assertTrue(outcome is RemoteTaskOutcome.Completed)
        assertEquals("completed", task.state)
        assertEquals(listOf("att-1"), port.fetchCalls)
        assertEquals(1, storedFiles().size)
    }

    @Test
    fun `artifact without a checksum is treated as damaged and refetched`() {
        val port = ScriptedPort(listOf(good.copy(artifactSha256 = null)), listOf({ flowOf(good) }))
        val (_, task) = run(port)
        assertEquals("completed", task.state)
        assertEquals(1, port.fetchCalls.size)
    }

    @Test
    fun `persistently damaged artifact fails after the bounded retries and stores nothing`() {
        val port = ScriptedPort(listOf(damaged), List(10) { { flowOf(damaged) } })
        val (outcome, task) = run(port)
        assertTrue(outcome is RemoteTaskOutcome.Failed)
        assertEquals("failed", task.state)
        assertEquals("exactly the bounded number of refetches", 3, port.fetchCalls.size)
        assertTrue(storedFiles().isEmpty())
        assertTrue(task.result.orEmpty().contains("产物校验失败") || task.error.orEmpty().contains("产物校验失败"))
    }

    @Test
    fun `Host reporting a corrupt record stops refetching immediately`() {
        val port = ScriptedPort(
            listOf(damaged),
            listOf({ flowOf(HandEvent.Blocked("已交付产物校验失败", "artifact_corrupt")) }, { flowOf(good) }),
        )
        val (_, task) = run(port)
        assertEquals("failed", task.state)
        assertEquals(1, port.fetchCalls.size)
        assertTrue(storedFiles().isEmpty())
    }

    @Test
    fun `lost result frame is recovered through transient failures with bounded retries`() {
        val port = ScriptedPort(
            listOf(HandEvent.Blocked("连接中断", HandClient.TRANSPORT_INTERRUPTED)),
            listOf(
                { flow { throw java.io.IOException("connection reset") } },
                { flowOf(HandEvent.Blocked("暂时无法读取", "artifact_unavailable")) },
                { flowOf(good) },
            ),
        )
        val (outcome, task) = run(port)
        assertTrue(outcome is RemoteTaskOutcome.Completed)
        assertEquals("completed", task.state)
        assertEquals(3, port.fetchCalls.size)
        assertEquals(1, storedFiles().size)
    }

    @Test
    fun `lost result with nothing delivered stops at artifact_missing`() {
        val port = ScriptedPort(listOf(), listOf({ flowOf(HandEvent.Blocked("没有产物", "artifact_missing")) }, { flowOf(good) }))
        val (outcome, task) = run(port)
        assertTrue(outcome is RemoteTaskOutcome.Failed)
        assertEquals("failed", task.state)
        assertEquals(1, port.fetchCalls.size)
    }

    @Test
    fun `lost result recovery gives up after the bounded schedule`() {
        val port = ScriptedPort(listOf(), List(10) { { flowOf(HandEvent.Blocked("暂时无法读取", "artifact_unavailable")) } })
        val (_, task) = run(port)
        assertEquals("failed", task.state)
        assertEquals(3, port.fetchCalls.size)
    }

    private companion object {
        const val AGENT = "remote-agent"
        fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    }
}
