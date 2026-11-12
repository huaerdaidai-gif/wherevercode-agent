package wherevercode.agent.core.task

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.core.control.ControlStateEntity

/**
 * 任务事件日志（P0 耐久任务引擎，2026-11-11），Robolectric + 真实
 * Room（内存库）：创建 → created；每次状态转移 → 一条事件；租约
 * 过期 → lease_expired + 回队/死信；终态全记；已暂停任务的过期租约
 * 只记一次 lease_expired；事件 append-only（DAO 只有 insert +
 * query，结构上无 update/delete——见 TaskEventLog.kt 注释；这里断言
 * 事件只增不改：重复读到同一批事件，条数只涨不掉）。
 */
@RunWith(RobolectricTestRunner::class)
class TaskEventLogTest {
    private lateinit var db: TaskDatabase
    private lateinit var store: RoomTaskStore
    private lateinit var events: RoomTaskEventStore

    @Before
    fun setUp() {
        db = TaskDatabase.inMemory(RuntimeEnvironment.getApplication())
        store = RoomTaskStore(db)
        events = RoomTaskEventStore(db)
    }

    @After
    fun tearDown() {
        db.close()
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

    private fun assign(id: String, leaseExpiresAt: Long = 1_700_000_000L + 600) {
        val task = store.getTask(id)!!
        store.updateTask(
            id,
            mapOf(
                "state" to "assigned",
                "assigned_agent_id" to "agent-1",
                "lease_owner" to "agent-1",
                "lease_expires_at" to leaseExpiresAt,
            ),
            task.version, 1, 1,
        )
    }

    private fun kinds(id: String): Set<String> =
        events.listEvents(id).map { it.kind }.toSet()

    @Test
    fun `create writes one created event`() {
        store.createTask(record("t1"))
        val list = events.listEvents("t1")
        assertEquals(1, list.size)
        assertEquals(TaskEventKind.CREATED, list[0].kind)
        assertNull(list[0].fromState)
        assertEquals("queued", list[0].toState)
        assertEquals("t1", list[0].taskId)
    }

    @Test
    fun `every state change appends one event including terminal states`() {
        store.createTask(record("t1"))
        var task = store.getTask("t1")!!
        store.updateTask("t1", mapOf("state" to "assigned"), task.version, 1, 1)
        task = store.getTask("t1")!!
        store.updateTask("t1", mapOf("state" to "working"), task.version, 1, 1)
        task = store.getTask("t1")!!
        store.updateTask("t1", mapOf("state" to "review"), task.version, 1, 1)
        task = store.getTask("t1")!!
        store.updateTask("t1", mapOf("state" to "completed"), task.version, 1, 1)
        // created + claimed + started + review + completed：终态也记，
        // 不像老项目 §5.1 那样终态事件静默丢。
        assertEquals(
            setOf(
                TaskEventKind.CREATED,
                TaskEventKind.CLAIMED,
                TaskEventKind.STARTED,
                TaskEventKind.REVIEW,
                TaskEventKind.COMPLETED,
            ),
            kinds("t1"),
        )
        assertEquals(5, events.listEvents("t1").size)
        // 事件只增不改：重复读到同一批。
        assertEquals(5, events.listEvents("t1").size)
    }

    @Test
    fun `lease expiry writes lease_expired plus requeued`() {
        store.createTask(record("t1"))
        assign("t1")
        store.expireLeases(1_700_000_000L + 601)
        assertTrue(kinds("t1").contains(TaskEventKind.LEASE_EXPIRED))
        assertTrue(kinds("t1").contains(TaskEventKind.REQUEUED))
        val requeued = events.listEvents("t1").first { it.kind == TaskEventKind.REQUEUED }
        assertEquals("assigned", requeued.fromState)
        assertEquals("requeued", requeued.toState)
    }

    @Test
    fun `third lease expiry writes lease_expired plus failed dead letter`() {
        store.createTask(record("t1"))
        val task = store.getTask("t1")!!
        store.updateTask(
            "t1",
            mapOf(
                "state" to "assigned",
                "attempt" to 3,
                "assigned_agent_id" to "agent-1",
                "lease_owner" to "agent-1",
                "lease_expires_at" to (1_700_000_000L + 90),
            ),
            task.version, 1, 1,
        )
        store.expireLeases(1_700_000_000L + 91)
        assertEquals("failed", store.getTask("t1")?.state)
        assertTrue(kinds("t1").contains(TaskEventKind.LEASE_EXPIRED))
        assertTrue(kinds("t1").contains(TaskEventKind.FAILED))
    }

    @Test
    fun `paused lease expiry writes a single lease_expired event across sweeps`() {
        store.createTask(record("t1"))
        assign("t1")
        db.controlDao().upsertState(ControlStateEntity("t1", status = "PAUSED"))
        store.expireLeases(1_700_000_000L + 601)
        store.expireLeases(1_700_000_000L + 602)
        store.expireLeases(1_700_000_000L + 603)
        // 已暂停任务的过期租约只在 needsAttention 翻转那一次记事件，
        // 否则每 10 秒扫一次会无限刷事件。
        assertEquals(1, events.listEvents("t1").count { it.kind == TaskEventKind.LEASE_EXPIRED })
        assertEquals("assigned", store.getTask("t1")?.state)
    }

    @Test
    fun `recent events cover all tasks`() {
        store.createTask(record("t1"))
        store.createTask(record("t2"))
        val recent = events.recentEvents(40)
        assertEquals(2, recent.size)
        assertEquals(setOf("t1", "t2"), recent.map { it.taskId }.toSet())
        assertTrue(recent.all { it.kind == TaskEventKind.CREATED })
    }

    // V5 跨设备开发（2026-11-12，D4 新口径 (b)）：任务详情“工作目录”
    // 行从事件反查——folder_resolved / folder_fallback 两类事件的
    // reason 原文里抽文件夹，取时间最晚的一条；两类都没有时不显示。
    private fun folderEvent(kind: String, reason: String, at: Long = 1_700_000_100L) =
        TaskEvent(
            eventId = "$kind-$at",
            taskId = "t1",
            attemptId = null,
            kind = kind,
            fromState = null,
            toState = "queued",
            actor = null,
            reason = reason,
            createdAtEpochSeconds = at,
        )

    @Test
    fun `working directory comes from the latest folder event`() {
        val fromResolved = workingDirectoryFromEvents(
            listOf(
                folderEvent(
                    TaskEventKind.FOLDER_RESOLVED,
                    "项目 WhereverCode Agent 在设备 mac 上使用检出文件夹 /Users/example-user/Projects/wherevercode-agent 执行",
                    at = 100,
                ),
            ),
        )
        assertEquals("/Users/example-user/Projects/wherevercode-agent", fromResolved?.folder)
        assertEquals(true, fromResolved?.isProjectCheckout)

        val fromFallback = workingDirectoryFromEvents(
            listOf(
                folderEvent(
                    TaskEventKind.FOLDER_FALLBACK,
                    "项目 WhereverCode Agent 在设备 mac 上无检出链接，使用 Agent 文件夹 /Users/example-user/v5-codex-work 执行",
                    at = 100,
                ),
            ),
        )
        assertEquals("/Users/example-user/v5-codex-work", fromFallback?.folder)
        assertEquals(false, fromFallback?.isProjectCheckout)
    }

    @Test
    fun `working directory takes the later event when a task was re-dispatched`() {
        val dir = workingDirectoryFromEvents(
            listOf(
                folderEvent(
                    TaskEventKind.FOLDER_FALLBACK,
                    "任务未绑定项目，在设备 mac 上无项目检出链接，使用 Agent 文件夹 /Users/example-user/v5-codex-work 执行",
                    at = 100,
                ),
                folderEvent(
                    TaskEventKind.FOLDER_RESOLVED,
                    "项目 WhereverCode Agent 在设备 mac 上使用检出文件夹 /Users/example-user/Projects/wherevercode-agent 执行",
                    at = 200,
                ),
            ),
        )
        assertEquals("/Users/example-user/Projects/wherevercode-agent", dir?.folder)
        assertEquals(true, dir?.isProjectCheckout)
    }

    @Test
    fun `working directory is null without any folder event`() {
        assertNull(workingDirectoryFromEvents(emptyList()))
        assertNull(
            workingDirectoryFromEvents(
                listOf(folderEvent(TaskEventKind.CREATED, "任务已创建", at = 100)),
            ),
        )
    }
}
