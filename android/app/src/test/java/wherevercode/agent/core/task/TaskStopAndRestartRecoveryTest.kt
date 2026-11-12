package wherevercode.agent.core.task

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 2026-11-13 回归：任务停止/取消后的写入 + App 重启恢复。
 *
 * - 停止后写入：取消落地后，迟到的执行者写 result / error / 续租 / 换 attempt
 *   一律被 [TerminalWriteGuardTaskStore] 拒绝，取消说明不被覆盖。
 * - App 重启恢复：用文件型 Room 库建任务 → 关库（模拟进程被杀）→ 重新打开：
 *   终态、结果、版本号原样恢复；未续租的在途任务按租约过期回队
 *   （requeued + needs_attention），不会悄悄“继续跑”；守卫在重启后照样生效。
 */
@RunWith(RobolectricTestRunner::class)
class TaskStopAndRestartRecoveryTest {

    private val dbName = "restart-recovery-test.db"
    private lateinit var db: TaskDatabase

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(dbName)
        db = TaskDatabase.create(context, dbName, allowMainThreadQueries = true)
    }

    @After
    fun tearDown() {
        db.close()
        RuntimeEnvironment.getApplication().deleteDatabase(dbName)
    }

    private fun record(id: String) = TaskRecord(
        taskId = id,
        title = "task $id",
        description = "desc",
        state = "queued",
        createdAt = 1_700_000_000L,
        updatedAt = 1_700_000_000L,
        version = 1,
    )

    private fun TaskStore.move(id: String, changes: Map<String, Any?>): TaskRecord {
        val current = getTask(id)!!
        return updateTask(id, changes, current.version, current.goalVersion, current.authEpoch)!!
    }

    @Test
    fun `late result after cancellation is rejected and the cancel note survives`() {
        val store = TerminalWriteGuardTaskStore(RoomTaskStore(db))
        store.createTask(record("t1"))
        store.move("t1", mapOf("state" to "assigned"))
        store.move("t1", mapOf("state" to "working"))
        store.move("t1", mapOf("state" to "cancelled", "result" to "任务被用户停止。"))

        val cancelled = store.getTask("t1")!!
        val error = assertThrows(TerminalTaskWriteRejected::class.java) {
            store.updateTask("t1", mapOf("result" to "迟到的成品"), cancelled.version, cancelled.goalVersion, cancelled.authEpoch)
        }
        assertEquals(setOf("result"), error.fields)
        assertThrows(TerminalTaskWriteRejected::class.java) {
            store.updateTask(
                "t1",
                mapOf("lease_expires_at" to 9_999_999_999L),
                cancelled.version,
                cancelled.goalVersion,
                cancelled.authEpoch,
            )
        }
        assertThrows(TerminalTaskWriteRejected::class.java) {
            store.updateTask("t1", mapOf("attempt_id" to "attempt-2"), cancelled.version, cancelled.goalVersion, cancelled.authEpoch)
        }
        val after = store.getTask("t1")!!
        assertEquals("任务被用户停止。", after.result)
        assertEquals(cancelled.version, after.version)
    }

    @Test
    fun `housekeeping writes are still allowed on terminal tasks`() {
        val store = TerminalWriteGuardTaskStore(RoomTaskStore(db))
        store.createTask(record("t2"))
        store.move("t2", mapOf("state" to "cancelled"))
        store.move("t2", mapOf("title" to "改个名", "needs_attention" to true, "lease_owner" to null))
        val after = store.getTask("t2")!!
        assertEquals("改个名", after.title)
        assertTrue(after.needsAttention)
    }

    @Test
    fun `state change out of a terminal state is still refused by the transition table`() {
        val store = TerminalWriteGuardTaskStore(RoomTaskStore(db))
        store.createTask(record("t3"))
        store.move("t3", mapOf("state" to "cancelled"))
        val current = store.getTask("t3")!!
        assertThrows(TaskStates.InvalidStateTransition::class.java) {
            store.updateTask("t3", mapOf("state" to "working"), current.version, current.goalVersion, current.authEpoch)
        }
    }

    @Test
    fun `non-terminal tasks are unaffected by the guard`() {
        val store = TerminalWriteGuardTaskStore(RoomTaskStore(db))
        store.createTask(record("t4"))
        store.move("t4", mapOf("state" to "assigned"))
        store.move("t4", mapOf("state" to "working"))
        store.move("t4", mapOf("result" to "中间结果"))
        assertEquals("中间结果", store.getTask("t4")!!.result)
    }

    @Test
    fun `app restart restores persisted task state and requeues abandoned in-flight work`() {
        val context = RuntimeEnvironment.getApplication()
        run {
            val store = TerminalWriteGuardTaskStore(RoomTaskStore(db))
            store.createTask(record("done"))
            store.move("done", mapOf("state" to "assigned"))
            store.move("done", mapOf("state" to "working"))
            store.move("done", mapOf("state" to "review"))
            store.move("done", mapOf("state" to "completed", "result" to "交付 OK"))

            store.createTask(record("stopped"))
            store.move("stopped", mapOf("state" to "cancelled", "result" to "任务被用户停止。"))

            store.createTask(record("inflight"))
            store.move("inflight", mapOf("state" to "assigned"))
            store.move(
                "inflight",
                mapOf("state" to "working", "lease_owner" to "worker-1", "lease_expires_at" to 1_000L, "attempt" to 1),
            )

            store.createTask(record("waiting"))
        }
        val versionsBefore = RoomTaskStore(db).listTasks().associate { it.taskId to it.version }
        // 模拟进程被杀：关库，重新打开同一个文件。
        db.close()
        db = TaskDatabase.create(context, dbName, allowMainThreadQueries = true)
        val reopened = TerminalWriteGuardTaskStore(RoomTaskStore(db))

        val done = reopened.getTask("done")
        assertNotNull(done)
        assertEquals("completed", done!!.state)
        assertEquals("交付 OK", done.result)
        assertNotNull(done.finishedAt)
        assertEquals("cancelled", reopened.getTask("stopped")!!.state)
        assertEquals("任务被用户停止。", reopened.getTask("stopped")!!.result)
        assertEquals("queued", reopened.getTask("waiting")!!.state)
        assertEquals(versionsBefore, reopened.listTasks().associate { it.taskId to it.version })

        // 重启后的恢复扫：上一进程留下的在途任务租约早已过期 → 回队并挂提醒旗。
        val expired = reopened.expireLeases(nowEpochSeconds = 2_000L)
        assertEquals(listOf("inflight"), expired)
        val recovered = reopened.getTask("inflight")!!
        assertEquals("requeued", recovered.state)
        assertTrue(recovered.needsAttention)
        assertNull(recovered.leaseOwner)
        assertEquals("终态任务不受恢复扫影响", "completed", reopened.getTask("done")!!.state)

        // 守卫在重启后照样生效：被停止的任务不接受迟到写入。
        val stopped = reopened.getTask("stopped")!!
        assertThrows(TerminalTaskWriteRejected::class.java) {
            reopened.updateTask("stopped", mapOf("error" to "late"), stopped.version, stopped.goalVersion, stopped.authEpoch)
        }
    }
}
