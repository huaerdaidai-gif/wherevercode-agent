package wherevercode.agent.core.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import wherevercode.agent.core.mcp.RemoteControlCommand
import wherevercode.agent.core.mcp.RemoteControlResult
import wherevercode.agent.core.mcp.RemoteControlSender
import wherevercode.agent.core.mcp.RemoteTarget
import wherevercode.agent.core.security.InMemoryAuthorizationStore
import wherevercode.agent.core.security.InMemoryRevocationStore
import wherevercode.agent.core.task.ControlVersionStale
import wherevercode.agent.core.task.StaleTaskUpdate
import wherevercode.agent.core.task.TaskControlCounterStore
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskStates
import wherevercode.agent.core.task.TaskStore
import wherevercode.agent.core.transport.DiscoveredPeer

/**
 * 阶段 B（V5 紧急开发）：暂停/停止/改目标必须到达远端正在执行的任务。
 *
 * 红测试：ControlPlane 的 remoteTargetResolver / remoteControlSender
 * 接线、confirmRemotePaused / confirmRemoteStopped 尚不存在。
 *
 * 语义（与本地控制一致，见 ControlPlane 头注 U4/U6）：
 * - 远端在途任务的 pause：本地先变 PAUSING（未确认），再经 Hand
 *   下发 pause；远端回执 confirmed 后才变 PAUSED，绝不先写 PAUSED。
 * - 远端回执到不了：任务保持 PAUSING（待确认），不强行终态。
 * - 控制信封带 taskId + operationId + goalVersion + epoch；
 *   本地任务不受影响（无 remote target 时行为与原来完全一致）。
 */
class RemoteControlTest {

    private class FakeTaskStore : TaskStore, TaskControlCounterStore {
        val tasks = LinkedHashMap<String, TaskRecord>()

        override fun createTask(record: TaskRecord): TaskRecord {
            tasks[record.taskId] = record
            return record
        }

        override fun listTasks(projectId: String?, sessionId: String?): List<TaskRecord> =
            tasks.values.toList()

        override fun getTask(taskId: String): TaskRecord? = tasks[taskId]

        override fun updateTask(
            taskId: String,
            changes: Map<String, Any?>,
            expectedVersion: Long,
            expectedGoalVersion: Long,
            expectedAuthEpoch: Long,
        ): TaskRecord? {
            val current = tasks[taskId] ?: return null
            if (current.version != expectedVersion) throw StaleTaskUpdate(taskId, expectedVersion, current.version)
            if (current.goalVersion != expectedGoalVersion) {
                throw ControlVersionStale(taskId, ControlVersionStale.GOAL_VERSION, expectedGoalVersion, current.goalVersion)
            }
            if (current.authEpoch != expectedAuthEpoch) {
                throw ControlVersionStale(taskId, ControlVersionStale.AUTH_EPOCH, expectedAuthEpoch, current.authEpoch)
            }
            var next = current
            for ((key, value) in changes) {
                next = when (key) {
                    "state" -> {
                        val targetState = value as String
                        TaskStates.checkTransition(next.state, targetState)
                        next.copy(state = targetState)
                    }
                    "description" -> next.copy(description = value as? String)
                    "result" -> next.copy(result = value as? String)
                    "error" -> next.copy(error = value as? String)
                    "needs_attention" -> next.copy(needsAttention = value as Boolean)
                    else -> next
                }
            }
            next = next.copy(version = current.version + 1)
            tasks[taskId] = next
            return next
        }

        override fun updateControlCounters(taskId: String, goalVersion: Long, authEpoch: Long): TaskRecord? {
            val current = tasks[taskId] ?: return null
            val next = current.copy(goalVersion = goalVersion, authEpoch = authEpoch)
            tasks[taskId] = next
            return next
        }

        override fun expireLeases(nowEpochSeconds: Long): List<String> = emptyList()

        override fun deleteTask(taskId: String): Boolean = tasks.remove(taskId) != null
    }

    /** 测试替身：记下发出的控制指令，回调由测试手动触发（模拟远端回执）。 */
    private class FakeSender : RemoteControlSender {
        data class Sent(val target: RemoteTarget, val taskId: String, val command: RemoteControlCommand)
        val sent = mutableListOf<Sent>()
        val callbacks = mutableListOf<(RemoteControlResult) -> Unit>()

        override fun send(
            target: RemoteTarget,
            taskId: String,
            command: RemoteControlCommand,
            onResult: (RemoteControlResult) -> Unit,
        ) {
            sent += Sent(target, taskId, command)
            callbacks += onResult
        }

        fun reply(index: Int, result: RemoteControlResult) {
            callbacks[index](result)
        }
    }

    private val peer = DiscoveredPeer(
        deviceId = "remote-host",
        displayName = "远程设备",
        host = "192.0.2.10",
        port = 18743,
        fingerprintSha256 = "ab".repeat(32),
        capabilities = listOf("hand"),
    )
    private val target = RemoteTarget(peer = peer, agentId = "example-agent", folder = "/workspace")

    private lateinit var taskStore: FakeTaskStore
    private lateinit var sender: FakeSender
    private lateinit var plane: ControlPlane
    private var now = 1_700_000_000L

    @Before
    fun setUp() {
        taskStore = FakeTaskStore()
        sender = FakeSender()
        plane = ControlPlane(
            taskStore = taskStore,
            authStore = InMemoryAuthorizationStore(),
            revocationStore = InMemoryRevocationStore(),
            nowEpochSeconds = { now },
            remoteTargetResolver = { agentId -> if (agentId == "example-agent") target else null },
            remoteControlSender = sender,
        )
    }

    private fun remoteWorkingTask(taskId: String = "task-remote-1"): TaskRecord {
        val record = TaskRecord(
            taskId = taskId,
            title = "远端任务",
            description = "写一个 calculator.py",
            state = "working",
            assignedAgentId = "example-agent",
            createdAt = now,
            updatedAt = now,
            version = 1,
            goalVersion = 3,
            authEpoch = 2,
        )
        taskStore.createTask(record)
        return record
    }

    @Test
    fun `pause remote working task sends pause with task id and fence snapshot`() {
        remoteWorkingTask()
        val result = plane.pause("task-remote-1", "op-pause-1", "用户暂停")

        assertTrue(result.ok)
        assertFalse(result.confirmed)
        assertEquals(ControlStatus.PAUSING, result.status)
        assertEquals(1, sender.sent.size)
        val sent = sender.sent[0]
        assertEquals("task-remote-1", sent.taskId)
        assertEquals(ControlAction.PAUSE, sent.command.action)
        assertEquals("op-pause-1", sent.command.operationId)
        assertEquals(3, sent.command.goalVersion)
        assertEquals(2, sent.command.epoch)
        assertEquals("example-agent", sent.target.agentId)
    }

    @Test
    fun `remote pause confirmation flips status to paused only after receipt`() {
        remoteWorkingTask()
        plane.pause("task-remote-1", "op-pause-1", "用户暂停")
        assertEquals(ControlStatus.PAUSING, plane.getControlStatus("task-remote-1"))

        sender.reply(0, RemoteControlResult(confirmed = true, message = "远端已暂停"))
        assertEquals(ControlStatus.PAUSED, plane.getControlStatus("task-remote-1"))
    }

    @Test
    fun `remote pause without receipt stays pausing never forced`() {
        remoteWorkingTask()
        plane.pause("task-remote-1", "op-pause-1", "用户暂停")
        sender.reply(0, RemoteControlResult(confirmed = false, message = "远端未确认：连接中断"))
        assertEquals(ControlStatus.PAUSING, plane.getControlStatus("task-remote-1"))
    }

    @Test
    fun `stop remote working task sends stop and confirms on receipt`() {
        remoteWorkingTask()
        val result = plane.stop("task-remote-1", "op-stop-1", "用户停止")
        assertTrue(result.ok)
        assertFalse(result.confirmed)
        assertEquals(ControlStatus.STOPPING, result.status)
        assertEquals(ControlAction.STOP, sender.sent[0].command.action)

        sender.reply(0, RemoteControlResult(confirmed = true, message = "远端已停止"))
        assertEquals("cancelled", taskStore.getTask("task-remote-1")?.state)
    }

    @Test
    fun `retarget remote task sends new goal with bumped goal version`() {
        remoteWorkingTask()
        val result = plane.retarget("task-remote-1", "改成写 README", "op-retarget-1", "用户改目标")
        assertTrue(result.ok)
        assertEquals(1, sender.sent.size)
        val sent = sender.sent[0]
        assertEquals(ControlAction.CHANGE_GOAL, sent.command.action)
        assertEquals("改成写 README", sent.command.newGoal)
        // 本地 goalVersion 已 bump（3 -> 4），发出的快照必须是新的。
        assertEquals(4, sent.command.goalVersion)
        assertEquals("改成写 README", taskStore.getTask("task-remote-1")?.description)
    }

    @Test
    fun `resume remote task sends resume`() {
        remoteWorkingTask()
        plane.pause("task-remote-1", "op-pause-1", "用户暂停")
        sender.reply(0, RemoteControlResult(confirmed = true, message = "远端已暂停"))
        assertEquals(ControlStatus.PAUSED, plane.getControlStatus("task-remote-1"))

        val result = plane.resume("task-remote-1", "op-resume-1", "用户恢复")
        assertTrue(result.ok)
        assertEquals(ControlAction.RESUME, sender.sent[1].command.action)
    }

    @Test
    fun `local task without remote target sends no control`() {
        val record = TaskRecord(
            taskId = "task-local-1",
            title = "本地任务",
            description = "本地跑",
            state = "working",
            assignedAgentId = "local",
            createdAt = now,
            updatedAt = now,
            version = 1,
        )
        taskStore.createTask(record)
        val result = plane.pause("task-local-1", "op-local-1", "用户暂停")
        assertTrue(result.ok)
        assertEquals(0, sender.sent.size)
    }

    @Test
    fun `pause is idempotent on operation id even for remote tasks`() {
        remoteWorkingTask()
        plane.pause("task-remote-1", "op-pause-1", "用户暂停")
        plane.pause("task-remote-1", "op-pause-1", "用户暂停")
        assertEquals(1, sender.sent.size)
    }
}
