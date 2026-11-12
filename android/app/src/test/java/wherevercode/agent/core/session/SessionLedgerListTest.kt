package wherevercode.agent.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.core.security.InMemoryAuditLog
import wherevercode.agent.core.security.InMemoryAuthorizationStore

/**
 * SessionLedger.listSessions（阶段 3，U5/批 2）：会话中心按 owner
 * 列会话。纯 JVM：复用 SessionLedgerTest 的内存 fake，时钟可注入，
 * 断言按 createdAt 倒序（与 Room `ORDER BY createdAtEpochSeconds
 * DESC` 一致）。
 */
class SessionLedgerListTest {

    private var fakeNow = 1_700_000_000L

    private fun ledger(): SessionLedgerImpl {
        val sessionDao = FakeSessionDao()
        val messageDao = FakeSessionMessageDao()
        val deliveryDao = FakeSessionDeliveryDao()
        val taskStore = SessionFakeTaskStore()
        val authStore = InMemoryAuthorizationStore()
        val auditLog = InMemoryAuditLog()
        val router = ControlInstructionRouter(
            taskStore, messageDao, FakeControlCommandPort(), authStore, auditLog, false,
        )
        return SessionLedgerImpl(
            sessionDao, messageDao, deliveryDao, router, authStore, auditLog,
            nowSeconds = { fakeNow },
        )
    }

    @Test
    fun `listSessions returns only the owner sessions newest first`() {
        val ledger = ledger()
        ledger.createSession("旧会话", SessionKind.PRIVATE, "user", listOf("agent-a"))
        fakeNow += 10
        ledger.createSession("新会话", SessionKind.PRIVATE, "user", listOf("agent-b"))
        fakeNow += 10
        ledger.createSession("别人的会话", SessionKind.PRIVATE, "other", listOf("agent-c"))

        val mine = ledger.listSessions("user")
        assertEquals(2, mine.size)
        assertEquals("新会话", mine[0].name)
        assertEquals("旧会话", mine[1].name)
        assertTrue(mine.all { it.ownerId == "user" })
    }

    @Test
    fun `listSessions is empty for an unknown owner`() {
        val ledger = ledger()
        ledger.createSession("会话", SessionKind.PRIVATE, "user", listOf("agent-a"))
        assertTrue(ledger.listSessions("nobody").isEmpty())
    }

    @Test
    fun `listSessions includes task sessions of the same owner`() {
        val ledger = ledger()
        ledger.createSession(
            "任务讨论", SessionKind.TASK, "user", listOf("agent-a"), taskId = "task-1",
        )
        val mine = ledger.listSessions("user")
        assertEquals(1, mine.size)
        assertEquals(SessionKind.TASK, mine[0].kind)
        assertEquals("task-1", mine[0].taskId)
    }
}
