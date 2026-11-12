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
 * RoomRevocationStore tests (M2, plan §2.1). Room/Robolectric — runs
 * in CI, not on a plain JVM. The "revoked credential still refused
 * after restart" point (M1 §3) is covered by [restartRevocationPersists].
 */
@RunWith(RobolectricTestRunner::class)
class RoomRevocationStoreTest {
    private lateinit var db: TaskDatabase
    private lateinit var store: RoomRevocationStore
    private val dbName = "revocation-store-test.db"
    private val now = 1_700_000_000L

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(dbName)
        db = TaskDatabase.create(context, dbName, allowMainThreadQueries = true)
        store = RoomRevocationStore(db) { now }
    }

    @After
    fun tearDown() {
        db.close()
        RuntimeEnvironment.getApplication().deleteDatabase(dbName)
    }

    @Test
    fun `revokeTask only invalidates that task`() {
        assertFalse(store.isTaskRevoked("t1"))
        store.revokeTask("t1")
        assertTrue(store.isTaskRevoked("t1"))
        assertFalse(store.isTaskRevoked("t2"))
        // Per-credential revocation is independent of task revocation.
        assertFalse(store.isRevoked("cred-1"))
        store.revoke("cred-1")
        assertTrue(store.isRevoked("cred-1"))
        assertFalse(store.isRevoked("cred-2"))
    }

    @Test
    fun `revokeHost kills only credentials issued before revocation`() {
        store.revokeHost("device-b", revokedAtEpochSeconds = now + 100)
        assertTrue(store.isHostRevoked("device-b", now))
        assertFalse("撤销时刻后签发的凭证存活", store.isHostRevoked("device-b", now + 200))
        assertFalse(store.isHostRevoked("device-c", now))
    }

    @Test
    fun `restartRevocationPersists`() {
        store.revoke("cred-1")
        store.revokeTask("t1")
        store.revokeHost("device-b", revokedAtEpochSeconds = now + 100)
        db.close()
        val context = RuntimeEnvironment.getApplication()
        db = TaskDatabase.create(context, dbName, allowMainThreadQueries = true)
        store = RoomRevocationStore(db) { now }
        assertTrue("重启后已撤销凭证仍拒绝", store.isRevoked("cred-1"))
        assertTrue(store.isTaskRevoked("t1"))
        assertTrue(store.isHostRevoked("device-b", now))
        assertFalse(store.isHostRevoked("device-b", now + 200))
    }
}
