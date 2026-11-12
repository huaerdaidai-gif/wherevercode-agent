package wherevercode.agent.core.security

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.core.task.TaskDatabase

/**
 * RoomAuthorizationStore tests (M2, plan §2.1; 5 use cases, 7 assertion
 * points). Room/Robolectric — runs in CI, not on a plain JVM.
 */
@RunWith(RobolectricTestRunner::class)
class RoomAuthorizationStoreTest {
    private lateinit var db: TaskDatabase
    private lateinit var store: RoomAuthorizationStore
    private val dbName = "auth-store-test.db"

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(dbName)
        db = TaskDatabase.create(context, dbName, allowMainThreadQueries = true)
        store = RoomAuthorizationStore(db)
    }

    @After
    fun tearDown() {
        db.close()
        RuntimeEnvironment.getApplication().deleteDatabase(dbName)
    }

    @Test
    fun `grant and revoke round-trip`() {
        assertFalse(store.isAuthorized("agent-1", "/proj"))
        val record = store.grant("agent-1", "/proj")
        assertEquals("agent-1", record.agentId)
        assertTrue(store.isAuthorized("agent-1", "/proj"))
        assertEquals(1, store.listGrants().size)
        assertTrue(store.revoke("agent-1", "/proj"))
        assertFalse(store.isAuthorized("agent-1", "/proj"))
        assertTrue(store.listGrants().isEmpty())
        assertFalse(store.revoke("agent-1", "/proj"))
    }

    @Test
    fun `whole-device grant and revoke`() {
        assertFalse(store.deviceAuthorized())
        assertTrue(store.grantDevice())
        assertTrue(store.deviceAuthorized())
        assertTrue("整机授权覆盖任意 agent 与文件夹", store.isAuthorized("any-agent", "/any/folder"))
        assertTrue(store.revokeDevice())
        assertFalse(store.deviceAuthorized())
        assertFalse(store.revokeDevice())
    }

    @Test
    fun `revoke then re-grant restores authorization`() {
        store.grant("agent-1", "/proj")
        assertTrue(store.isAuthorized("agent-1", "/proj"))
        store.revoke("agent-1", "/proj")
        assertFalse("撤权后 verify 拒绝", store.isAuthorized("agent-1", "/proj"))
        store.grant("agent-1", "/proj")
        assertTrue("撤权后重新 grant 恢复", store.isAuthorized("agent-1", "/proj"))
    }

    @Test
    fun `no same-account default allow`() {
        // A fresh store authorizes nothing: no grant, no device grant.
        assertFalse(store.isAuthorized("agent-1", "/proj"))
        assertFalse(store.deviceAuthorized())
        assertTrue(store.listGrants().isEmpty())
    }

    @Test
    fun `grants survive restart`() {
        store.grant("agent-1", "/proj")
        store.grantDevice()
        db.close()
        val context = RuntimeEnvironment.getApplication()
        db = TaskDatabase.create(context, dbName, allowMainThreadQueries = true)
        store = RoomAuthorizationStore(db)
        assertTrue(store.isAuthorized("agent-1", "/proj"))
        assertTrue(store.deviceAuthorized())
        assertEquals(1, store.listGrants().size)
    }
}
