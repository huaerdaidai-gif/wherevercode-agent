package wherevercode.agent.core.team

import androidx.room.Room
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.core.task.TaskDatabase

/**
 * 成员存储（V5 Phase B C3 第二阶段，成员框架），Robolectric。
 *
 * 覆盖：Room 成员增删改查 + 授权绑定持久化往返 + 回传/控制行
 * 读写 + Room 打不开时回退内存、不删库。零真实 CLI、零真实连接。
 */
@RunWith(RobolectricTestRunner::class)
class TeamMemberStoreTest {
    private val dbName = "team-member-store-test.db"
    private var db: TaskDatabase? = null

    @After
    fun tearDown() {
        db?.close()
        val app = RuntimeEnvironment.getApplication()
        app.deleteDatabase(dbName)
        app.deleteDatabase("team-member-store-corrupt-test.db")
        app.deleteDatabase("team-member-store-healthy-test.db")
    }

    private fun roomStore(): RoomTeamMemberStore {
        val context = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, TaskDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db = database
        return RoomTeamMemberStore(database)
    }

    private fun codexMac() = TeamMember.remoteCliMember(
        memberId = "codex-mac",
        agentId = "codex-cli",
        displayName = "Mac 上的 Codex",
        transport = MemberTransport.LAN,
        endpointDeviceId = "mac-1",
        endpointLabel = "Mac",
        shapeStatus = MemberShapeStatus.VERIFIED,
    )

    @Test
    fun `room member CRUD round trip keeps every field`() {
        val store = roomStore()
        store.saveMember(codexMac())
        val loaded = store.getMember("codex-mac")!!
        assertEquals("Mac 上的 Codex", loaded.displayName)
        assertEquals(MemberKind.DEVICE_CLI, loaded.kind)
        assertEquals(MemberTransport.LAN, loaded.transport)
        assertEquals("mac-1", loaded.endpointDeviceId)
        assertEquals("Mac", loaded.endpointLabel)
        assertEquals(MemberShapeStatus.VERIFIED, loaded.shapeStatus)
        assertEquals(MemberConnectionState.NOT_CONNECTED, loaded.connectionState)
        assertEquals(MemberConnectivity.NOT_CHECKED, loaded.connectivity)
        // §12–§14 新增字段的默认值：额度状态 UNKNOWN、驱动 agent 为空。
        assertEquals(MemberQuotaState.UNKNOWN, loaded.quotaState)
        assertEquals(null, loaded.driverAgentId)
        assertEquals(1, store.listMembers().size)

        // §12–§14 新增字段往返：额度状态与驱动 agent 都落盘。
        store.saveMember(
            codexMac().copy(
                memberId = "workbuddy-mac",
                displayName = "Mac 端 Workbuddy",
                kind = MemberKind.DESKTOP_APP,
                quotaState = MemberQuotaState.FREE_AVAILABLE,
                driverAgentId = "codebuddy-cli",
            ),
        )
        val desktop = store.getMember("workbuddy-mac")!!
        assertEquals(MemberQuotaState.FREE_AVAILABLE, desktop.quotaState)
        assertEquals("codebuddy-cli", desktop.driverAgentId)

        store.saveMember(loaded.copy(displayName = "Mac Codex 改名"))
        assertEquals("Mac Codex 改名", store.getMember("codex-mac")?.displayName)

        store.deleteMember("codex-mac")
        store.deleteMember("workbuddy-mac")
        assertNull(store.getMember("codex-mac"))
        assertTrue(store.listMembers().isEmpty())
    }

    @Test
    fun `room project grants persist and mirror the member row`() {
        val store = roomStore()
        store.saveMember(codexMac())
        store.grantProject("codex-mac", "proj-a", "user", 1_700_000_000L)
        store.grantProject("codex-mac", "proj-b", "user", 1_700_000_001L)
        assertEquals(listOf("proj-a", "proj-b"), store.listProjects("codex-mac"))
        assertTrue(store.isAuthorized("codex-mac", "proj-a"))
        assertFalse(store.isAuthorized("codex-mac", "proj-c"))
        // 成员行上的列表是显示镜像，与绑定表一致。
        assertEquals(
            listOf("proj-a", "proj-b"),
            store.getMember("codex-mac")?.authorizedProjectIds,
        )

        store.revokeProject("codex-mac", "proj-a")
        assertEquals(listOf("proj-b"), store.listProjects("codex-mac"))
        assertEquals(listOf("proj-b"), store.getMember("codex-mac")?.authorizedProjectIds)

        // 删成员连带删绑定。
        store.deleteMember("codex-mac")
        assertTrue(store.listProjects("codex-mac").isEmpty())
    }

    @Test
    fun `room return and control rows round trip`() {
        val store = roomStore()
        store.saveReturn(
            MemberReturnEntity(
                taskId = "t1",
                attemptId = "a1",
                memberId = "codex-mac",
                branch = "dev/codex-t1",
                commit = "ab".repeat(20),
                prUrl = null,
                totalPages = 1,
                pagesReceived = 1,
                truncated = false,
                filesJson = "[\"a.kt\"]",
                summary = "改了一行",
                logsJson = "[\"done\"]",
                diffSummary = "1 file changed",
                testVerdict = "PASSED",
                testSummary = "3 passed",
                recordedAtEpochSeconds = 1_700_000_010L,
            ),
        )
        val loaded = store.getReturn("t1", "a1")!!
        assertEquals("dev/codex-t1", loaded.branch)
        assertEquals("PASSED", loaded.testVerdict)
        assertEquals(1, store.listReturns("t1").size)

        store.saveControl(
            MemberTaskControlEntity(
                taskId = "t1",
                memberId = "codex-mac",
                paused = true,
                pendingGoal = null,
                updatedAtEpochSeconds = 1_700_000_020L,
            ),
        )
        assertEquals(true, store.getControl("t1")?.paused)
        store.deleteControl("t1")
        assertNull(store.getControl("t1"))
    }

    @Test
    fun `inMemory store behaves the same as Room for grants`() {
        val store = InMemoryTeamMemberStore()
        store.saveMember(codexMac())
        store.grantProject("codex-mac", "proj-a", "user", 1_700_000_000L)
        assertTrue(store.isAuthorized("codex-mac", "proj-a"))
        assertEquals(listOf("proj-a"), store.getMember("codex-mac")?.authorizedProjectIds)
        store.revokeProject("codex-mac", "proj-a")
        assertFalse(store.isAuthorized("codex-mac", "proj-a"))
    }

    @Test
    fun `open falls back to memory when Room cannot open and never deletes the file`() {
        val context = RuntimeEnvironment.getApplication()
        val corruptName = "team-member-store-corrupt-test.db"
        val dbFile = context.getDatabasePath(corruptName)
        dbFile.parentFile?.mkdirs()
        // 写坏文件：不是 SQLite 数据库。
        dbFile.writeText("this is not a database file", Charsets.UTF_8)
        assertTrue(dbFile.exists())

        val store = TeamMemberStores.open(context, corruptName, allowMainThreadQueries = true)
        assertTrue(store is InMemoryTeamMemberStore)
        store.saveMember(codexMac())
        assertEquals("Mac 上的 Codex", store.getMember("codex-mac")?.displayName)
        // 坏文件原样保留，没有被删库。
        assertTrue(dbFile.exists())
        assertEquals(
            "this is not a database file",
            dbFile.readText(Charsets.UTF_8),
        )
    }

    @Test
    fun `open returns Room store for a healthy file database`() {
        val context = RuntimeEnvironment.getApplication()
        val store = TeamMemberStores.open(
            context,
            "team-member-store-healthy-test.db",
            allowMainThreadQueries = true,
        )
        assertTrue(store is RoomTeamMemberStore)
        store.saveMember(codexMac())
        assertEquals("Mac 上的 Codex", store.getMember("codex-mac")?.displayName)
        // 文件数据库的清理交给 tearDown 的 deleteDatabase；本测试只
        // 证明健康文件走 Room（回退只发生在打不开时）。
    }
}
