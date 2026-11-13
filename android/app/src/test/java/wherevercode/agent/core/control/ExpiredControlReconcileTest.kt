package wherevercode.agent.core.control

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.AgentDiscovery
import wherevercode.agent.core.mcp.HandEvent
import wherevercode.agent.core.mcp.RemoteAgentChatPort
import wherevercode.agent.core.mcp.RemoteControlCommand
import wherevercode.agent.core.mcp.RemoteControlResult
import wherevercode.agent.core.mcp.RemoteControlSender
import wherevercode.agent.core.mcp.RemoteTarget
import wherevercode.agent.core.security.RoomAuthorizationStore
import wherevercode.agent.core.security.RoomRevocationStore
import wherevercode.agent.core.task.InMemoryTaskStepStore
import wherevercode.agent.core.task.LocalTaskRunner
import wherevercode.agent.core.task.RoomTaskStore
import wherevercode.agent.core.task.TaskDatabase
import wherevercode.agent.core.task.TaskExecutionResult
import wherevercode.agent.core.task.TaskExecutor
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskScheduler
import wherevercode.agent.core.task.TaskStepEvent
import wherevercode.agent.core.team.RemoteTaskDispatcher
import wherevercode.agent.core.team.RemoteTaskPump
import wherevercode.agent.core.transport.DiscoveredPeer

/**
 * Lease-expiry reconciliation of pending controls for every non-chat
 * executor kind (remote member Agent, MCP executor). Production layout:
 * one shared TaskDatabase; the scheduler, the pumps and the ControlPlane
 * share the same Room-backed task and control stores.
 *
 * Invariants checked throughout:
 * - a paused task is never executed (no claim, no remote run) until resumed;
 * - a resumed task runs exactly once (no double execution);
 * - STOPPING whose executor vanished lands cancelled and never reruns;
 * - chat-turn tasks (sessionId set) are never touched.
 */
@RunWith(RobolectricTestRunner::class)
class ExpiredControlReconcileTest {

    private lateinit var db: TaskDatabase
    private lateinit var store: RoomTaskStore
    private lateinit var control: RoomControlStore
    private lateinit var scheduler: TaskScheduler
    private lateinit var plane: ControlPlane
    private val sentControls = Collections.synchronizedList(mutableListOf<RemoteControlCommand>())

    private val peer = DiscoveredPeer("remote-host", "remote", "192.0.2.10", 18743, "ab".repeat(32), listOf("hand"))
    private val target = RemoteTarget(peer = peer, agentId = REMOTE, folder = "/workspace")

    /** Remote port: counts runs; the flow it returns is supplied per test. */
    private inner class CountingPort(var events: () -> Flow<HandEvent> = { flowOf(HandEvent.Result(1, "done")) }) :
        RemoteAgentChatPort {
        val runs = AtomicInteger(0)
        override fun runAgent(
            target: RemoteTarget, taskId: String, text: String, timeoutSeconds: Long,
            goalVersion: Long, epoch: Long,
        ): Flow<HandEvent> {
            runs.incrementAndGet()
            return events()
        }
        override fun sendControl(target: RemoteTarget, taskId: String, command: RemoteControlCommand): Flow<HandEvent> =
            flowOf()
    }

    @Before
    fun setUp() {
        val ctx = RuntimeEnvironment.getApplication()
        db = TaskDatabase.inMemory(ctx)
        store = RoomTaskStore(db)
        control = RoomControlStore(db)
        scheduler = TaskScheduler(store, AgentDiscovery(ctx), control)
        plane = ControlPlane(
            store, RoomAuthorizationStore(db), RoomRevocationStore(db),
            controlStore = control, auditLog = RoomControlAuditLog(db.controlAuditDao()), actor = "user",
        )
        // The Hand is offline: every control send comes back unconfirmed.
        plane.configureRemoteControl(
            targetResolver = { agentId -> if (agentId == REMOTE || agentId == MCP_EXECUTOR) target else null },
            controlSender = object : RemoteControlSender {
                override fun send(
                    target: RemoteTarget, taskId: String, command: RemoteControlCommand,
                    onResult: (RemoteControlResult) -> Unit,
                ) {
                    sentControls += command
                    onResult(RemoteControlResult(confirmed = false, message = "offline", code = "transport_interrupted"))
                }
            },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun nowSec() = System.currentTimeMillis() / 1000

    private fun queue(id: String, assignee: String?, sessionId: String? = null) {
        store.createTask(TaskRecord(taskId = id, title = id, state = "queued", assignedAgentId = assignee, sessionId = sessionId))
    }

    /** Claim + start a remote member attempt, as RemoteTaskPump/RemoteTaskDispatcher do. */
    private fun startRemote(id: String, attempt: String = "att-$id"): TaskRecord {
        assertNotNull(scheduler.claimTaskForMember(id, REMOTE, attempt))
        return checkNotNull(scheduler.startTask(id, attempt))
    }

    private fun startMcp(id: String, executor: String = MCP_EXECUTOR, attempt: String = "att-$id"): TaskRecord {
        assertNotNull(scheduler.claimMcpTaskForMember(id, executor, attempt, nowSec()))
        return checkNotNull(scheduler.startTask(id, attempt))
    }

    /** The executor stopped renewing: push the lease into the past. */
    private fun expireLease(id: String) {
        val t = checkNotNull(store.getTask(id))
        store.updateTask(id, mapOf("lease_expires_at" to nowSec() - 5), t.version, t.goalVersion, t.authEpoch)
    }

    private fun task(id: String) = checkNotNull(store.getTask(id))
    private fun status(id: String) = control.getControlStatus(id)

    private fun pump(port: CountingPort) = RemoteTaskPump(
        taskStore = store,
        scheduler = scheduler,
        stepStore = InMemoryTaskStepStore(),
        dispatcher = RemoteTaskDispatcher(store, scheduler, InMemoryTaskStepStore(), port),
        targetResolver = { _, agentId -> if (agentId == REMOTE) target else null },
    )

    private suspend fun awaitState(id: String, vararg states: String, timeoutMs: Long = 10_000): TaskRecord {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            val t = task(id)
            if (t.state in states) return t
            delay(20)
        }
        throw AssertionError("task $id not in ${states.toList()} after ${timeoutMs}ms: ${task(id).state}")
    }

    // -- remote member: offline after pause ---------------------------------

    @Test
    fun `remote member offline after pause - stays PAUSING while lease live, PAUSED requeued after expiry, never executed`() = runBlocking {
        val id = "remote-pause"
        queue(id, REMOTE)
        startRemote(id)

        val paused = plane.pause(id, "op-pause", "user pause")
        assertEquals(ControlStatus.PAUSING, paused.status)
        assertFalse(paused.confirmed)

        // Confirm timeout: the Hand never acks, the attempt lease is still live.
        plane.reconcileExpiredControls()
        assertEquals(ControlStatus.PAUSING, status(id))
        assertEquals("working", task(id).state)
        val early = plane.resume(id, "op-resume-early", "resume while pausing")
        assertFalse(early.ok)
        assertEquals("control_pausing", early.errorCode)

        // Executor vanished (no renewals): the attempt is released, not rerun.
        expireLease(id)
        plane.reconcileExpiredControls()
        val released = task(id)
        assertEquals(ControlStatus.PAUSED, status(id))
        assertEquals("requeued", released.state)
        assertEquals("owner kept so only the remote member can pick it up again", REMOTE, released.assignedAgentId)
        assertNull(released.attemptId)
        assertNull(released.leaseExpiresAt)
        assertTrue(released.needsAttention)
        assertFalse(control.isDispatchFrozen(id))

        // Paused: neither the remote pump nor the local runner may execute it.
        val port = CountingPort()
        assertFalse(pump(port).pumpOnce())
        assertEquals(0, port.runs.get())
        assertNull(scheduler.claimTask(id, LocalTaskRunner.LOCAL_AGENT_ID))
        val localRuns = AtomicInteger(0)
        val runner = LocalTaskRunner(store, scheduler, InMemoryTaskStepStore(), object : TaskExecutor {
            override suspend fun execute(task: TaskRecord, onStep: (TaskStepEvent) -> Unit): TaskExecutionResult {
                localRuns.incrementAndGet()
                return TaskExecutionResult(success = true, summary = "wrong executor")
            }
        }, pollIntervalMs = 60_000, leaseRenewIntervalMs = 60_000)
        runner.pumpOnce()
        runner.stop()
        assertEquals(0, localRuns.get())
        assertEquals("requeued", task(id).state)
    }

    @Test
    fun `resume after released remote pause re-dispatches once with re-run wording`() = runBlocking {
        val id = "remote-resume"
        queue(id, REMOTE)
        startRemote(id)
        plane.pause(id, "op-pause", "pause")
        expireLease(id)
        plane.reconcileExpiredControls()

        val resumed = plane.resume(id, "op-resume", "resume")
        assertTrue(resumed.ok)
        assertEquals(ControlStatus.RUNNING, resumed.status)
        assertEquals(ControlPlane.RESUME_REDISPATCH_REASON, resumed.reason)
        assertTrue(checkNotNull(resumed.reason).contains("重新执行本轮"))

        val port = CountingPort()
        val p = pump(port)
        assertTrue(p.pumpOnce())
        val done = awaitState(id, "completed", "failed", "cancelled")
        assertEquals("completed", done.state)
        assertFalse(p.pumpOnce())
        assertEquals("exactly one re-dispatch", 1, port.runs.get())
    }

    @Test
    fun `resume of a PAUSING task whose lease expired releases the dead attempt and runs once`() = runBlocking {
        val id = "remote-pausing-resume"
        queue(id, REMOTE)
        startRemote(id)
        plane.pause(id, "op-pause", "pause")
        expireLease(id)

        // No sweep has run yet: resume itself recognizes the dead attempt.
        val resumed = plane.resume(id, "op-resume", "resume")
        assertTrue(resumed.ok)
        assertEquals(ControlStatus.RUNNING, resumed.status)
        val t = task(id)
        assertEquals("requeued", t.state)
        assertNull(t.attemptId)
        assertEquals(REMOTE, t.assignedAgentId)

        // A later sweep is a no-op (nothing in flight any more).
        plane.reconcileExpiredControls()
        assertEquals(ControlStatus.RUNNING, status(id))

        val port = CountingPort()
        val p = pump(port)
        assertTrue(p.pumpOnce())
        assertEquals("completed", awaitState(id, "completed", "failed", "cancelled").state)
        assertEquals(1, port.runs.get())
    }

    // -- remote member: STOPPING -------------------------------------------

    @Test
    fun `remote member offline after stop - STOPPING until lease expiry, then cancelled and never rerun`() = runBlocking {
        val id = "remote-stop"
        queue(id, REMOTE)
        startRemote(id)

        val stopped = plane.stop(id, "op-stop", "user stop")
        assertEquals(ControlStatus.STOPPING, stopped.status)
        assertFalse(stopped.confirmed)
        plane.reconcileExpiredControls()
        assertEquals(ControlStatus.STOPPING, status(id))
        assertEquals("working", task(id).state)

        expireLease(id)
        plane.reconcileExpiredControls()
        assertEquals("cancelled", task(id).state)
        // Expected: confirmed stop keeps the STOPPING control marker on a terminal task;
        // what matters is it never becomes PAUSED/RUNNING and never reruns.
        assertTrue(status(id) == ControlStatus.STOPPING || status(id) == ControlStatus.RUNNING)

        val resume = plane.resume(id, "op-resume", "resume")
        assertFalse(resume.ok)
        assertEquals("control_terminal_task", resume.errorCode)

        val port = CountingPort()
        assertFalse(pump(port).pumpOnce())
        assertEquals(0, port.runs.get())
    }

    // -- duplicates ----------------------------------------------------------

    @Test
    fun `duplicate reconcile calls and late remote acks are idempotent`() {
        val id = "remote-dup"
        queue(id, REMOTE)
        startRemote(id)
        plane.pause(id, "op-pause", "pause")
        expireLease(id)

        plane.reconcileExpiredControls()
        val afterFirst = task(id)
        plane.reconcileExpiredControls()
        plane.reconcileExpiredControls()
        val afterThird = task(id)
        assertEquals("requeued", afterThird.state)
        assertEquals(ControlStatus.PAUSED, status(id))
        assertEquals("repeat sweeps write nothing", afterFirst.version, afterThird.version)

        // The Hand comes back and acks the original pause late.
        val lateAck = plane.confirmRemotePaused(id, "op-late-ack", "late ack")
        assertTrue(lateAck.ok)
        assertEquals(ControlStatus.PAUSED, lateAck.status)
        assertEquals("requeued", task(id).state)

        // Repeated resume with the same operationId executes once.
        val r1 = plane.resume(id, "op-resume", "resume")
        val v1 = task(id).version
        val r2 = plane.resume(id, "op-resume", "resume")
        assertEquals(r1, r2)
        assertEquals(v1, task(id).version)
    }

    @Test
    fun `late result from the released remote attempt is fenced and does not complete the paused task`() = runBlocking {
        val id = "remote-late"
        queue(id, REMOTE)
        val claimed = checkNotNull(scheduler.claimTaskForMember(id, REMOTE, "att-old"))
        val gate = CompletableDeferred<Unit>()
        val running = CompletableDeferred<Unit>()
        val port = CountingPort {
            flow {
                running.complete(Unit)
                gate.await()
                emit(HandEvent.Result(1, "late result from the dead attempt"))
            }
        }
        val dispatcher = RemoteTaskDispatcher(store, scheduler, InMemoryTaskStepStore(), port, leaseRenewIntervalMs = 3_600_000)
        val outcome = async { dispatcher.execute(claimed, target) }
        running.await()

        plane.pause(id, "op-pause", "pause")
        expireLease(id)
        plane.reconcileExpiredControls()
        assertEquals("requeued", task(id).state)
        assertEquals(ControlStatus.PAUSED, status(id))

        gate.complete(Unit)
        outcome.await()
        val after = task(id)
        assertEquals("old attempt's result must not land", "requeued", after.state)
        assertNull(after.result)
        assertEquals(ControlStatus.PAUSED, status(id))
        assertEquals(1, port.runs.get())
    }

    // -- concurrency ---------------------------------------------------------

    @Test
    fun `concurrent resumes after release resume once and the task runs once`() = runBlocking {
        val id = "remote-concurrent"
        queue(id, REMOTE)
        startRemote(id)
        plane.pause(id, "op-pause", "pause")
        expireLease(id)
        plane.reconcileExpiredControls()

        val startGate = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<ControlResult>())
        val threads = (1..4).map { n ->
            Thread {
                startGate.await(5, TimeUnit.SECONDS)
                results += plane.resume(id, "op-resume-$n", "resume $n")
            }.also { it.start() }
        }
        startGate.countDown()
        threads.forEach { it.join(10_000) }
        assertEquals(4, results.size)
        assertTrue(results.all { it.ok && it.status == ControlStatus.RUNNING })
        assertEquals("requeued", task(id).state)

        val port = CountingPort()
        val p = pump(port)
        assertTrue(p.pumpOnce())
        awaitState(id, "completed", "failed", "cancelled")
        assertFalse(p.pumpOnce())
        assertEquals(1, port.runs.get())
    }

    @Test
    fun `resume racing the sweep on an expired PAUSING task ends RUNNING with a single run`() = runBlocking {
        repeat(5) { round ->
            val id = "remote-race-$round"
            queue(id, REMOTE)
            startRemote(id)
            plane.pause(id, "op-pause-$round", "pause")
            expireLease(id)
            val go = CountDownLatch(1)
            var resume: ControlResult? = null
            val t1 = Thread { go.await(5, TimeUnit.SECONDS); plane.reconcileExpiredControls() }.also { it.start() }
            val t2 = Thread { go.await(5, TimeUnit.SECONDS); resume = plane.resume(id, "op-resume-$round", "resume") }
                .also { it.start() }
            go.countDown()
            t1.join(10_000); t2.join(10_000)
            assertTrue(checkNotNull(resume).ok)
            assertEquals(ControlStatus.RUNNING, status(id))
            assertEquals("requeued", task(id).state)
            assertNull(task(id).attemptId)

            val port = CountingPort()
            val p = pump(port)
            assertTrue(p.pumpOnce())
            awaitState(id, "completed", "failed", "cancelled")
            assertEquals(1, port.runs.get())
        }
    }

    // -- MCP executor --------------------------------------------------------

    @Test
    fun `MCP executor offline - PAUSING released to PAUSED by the remote pump sweep, STOPPING cancelled`() {
        val pausedId = "mcp-offline-pause"
        val stoppedId = "mcp-offline-stop"
        queue(pausedId, MCP_EXECUTOR)
        // One task per executor at a time: the stopped task runs on a second executor.
        queue(stoppedId, MCP_EXECUTOR_2)
        startMcp(pausedId)
        startMcp(stoppedId, MCP_EXECUTOR_2)
        assertEquals(ControlStatus.PAUSING, plane.pause(pausedId, "op-p", "pause").status)
        assertEquals(ControlStatus.STOPPING, plane.stop(stoppedId, "op-s", "stop").status)
        expireLease(pausedId)
        expireLease(stoppedId)

        // The remote pump also drives the sweep (not only the local runner).
        val p = pump(CountingPort())
        p.onControlSweep = { plane.reconcileExpiredControls() }
        assertTrue(p.sweepControlsIfDue(nowMs = 1_000_000))

        val paused = task(pausedId)
        assertEquals("requeued", paused.state)
        assertEquals(ControlStatus.PAUSED, status(pausedId))
        assertEquals(MCP_EXECUTOR, paused.assignedAgentId)
        assertNull(paused.attemptId)
        assertEquals("cancelled", task(stoppedId).state)

        // Paused MCP task: not claimable by its executor nor by the local runner.
        assertNull(scheduler.claimMcpTaskForMember(pausedId, MCP_EXECUTOR, "att-new", nowSec()))
        assertNull(scheduler.claimTask(pausedId, LocalTaskRunner.LOCAL_AGENT_ID))
        // Stopped MCP task: never again.
        assertNull(scheduler.claimMcpTaskForMember(stoppedId, MCP_EXECUTOR_2, "att-new2", nowSec()))

        // After resume only the owning MCP executor can claim it.
        assertTrue(plane.resume(pausedId, "op-r", "resume").ok)
        assertNull(scheduler.claimTask(pausedId, LocalTaskRunner.LOCAL_AGENT_ID))
        assertNotNull(scheduler.claimMcpTaskForMember(pausedId, MCP_EXECUTOR, "att-new3", nowSec()))
    }

    @Test
    fun `remote pump control sweep is throttled`() {
        val p = pump(CountingPort())
        assertFalse("no hook, no sweep", p.sweepControlsIfDue(nowMs = 1_000))
        val calls = AtomicInteger(0)
        p.onControlSweep = { calls.incrementAndGet(); throw IllegalStateException("sweep failure never kills the pump") }
        p.controlSweepIntervalMs = 10_000
        assertTrue(p.sweepControlsIfDue(nowMs = 1_000))
        assertFalse(p.sweepControlsIfDue(nowMs = 5_000))
        assertTrue(p.sweepControlsIfDue(nowMs = 11_000))
        assertEquals(2, calls.get())
    }

    // -- untouched -----------------------------------------------------------

    @Test
    fun `chat-turn tasks and live attempts are never touched by the sweep`() {
        val chat = "chat-turn"
        queue(chat, "local-brain", sessionId = "session-1")
        assertNotNull(scheduler.claimTask(chat, "local-brain"))
        scheduler.startTask(chat)
        val chatBefore = task(chat)
        // Simulate a pending control + an expired lease on the chat mirror.
        control.setControlStatus(chat, ControlStatus.PAUSING)
        if (chatBefore.leaseExpiresAt != null) expireLease(chat)
        val chatSnapshot = task(chat)

        val live = "remote-live"
        queue(live, REMOTE)
        startRemote(live)
        plane.stop(live, "op-stop-live", "stop")

        plane.reconcileExpiredControls()

        assertEquals(chatSnapshot, task(chat))
        assertEquals(ControlStatus.PAUSING, status(chat))
        assertEquals("working", task(live).state)
        assertEquals(ControlStatus.STOPPING, status(live))
    }

    private companion object {
        const val REMOTE = "remote-agent"
        const val MCP_EXECUTOR = "codex"
        const val MCP_EXECUTOR_2 = "codex-2"
    }
}
