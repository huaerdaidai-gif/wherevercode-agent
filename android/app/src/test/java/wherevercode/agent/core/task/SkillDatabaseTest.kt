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
import wherevercode.agent.brain.Skill

/**
 * Room tests (Robolectric — the DAOs need an Android Context).
 * Run in CI (`./gradlew testDebugUnitTest`); they cannot run on a plain
 * JVM without Robolectric, so locally they only need to compile.
 *
 * Covers the V5 技能记忆 `skills` table (TaskDatabase version 7):
 * DAO round-trip (JSON list columns survive) + [RoomSkillStore]
 * behaviour (project + global listing, remove, missing -> null).
 */
@RunWith(RobolectricTestRunner::class)
class SkillDatabaseTest {
    private lateinit var db: TaskDatabase
    private lateinit var store: RoomSkillStore

    private fun skill(
        id: String,
        projectId: String = "",
        updatedAt: Long = 1_700_000_000L,
    ) = Skill(
        id = id,
        projectId = projectId,
        name = "新建文件写内容",
        triggers = listOf("notes", "write_file"),
        summary = "先 list_files 看现状，再 write_file 一次写完。",
        steps = listOf("list_files 列出工作区", "write_file 写入文件"),
        tools = listOf("list_files", "write_file"),
        pitfalls = listOf("不要先写文件再看目录"),
        evidenceTaskIds = listOf("task-1"),
        successCount = 2,
        failureCount = 1,
        lastUsedEpochSeconds = updatedAt,
        createdAtEpochSeconds = 1_700_000_000L,
        updatedAtEpochSeconds = updatedAt,
    )

    @Before
    fun setUp() {
        db = TaskDatabase.inMemory(RuntimeEnvironment.getApplication())
        store = RoomSkillStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `upsert get round-trip keeps every field including json lists`() {
        val original = skill("s1", projectId = "proj-9")
        store.upsert(original)
        val got = store.get("s1")!!
        assertEquals(original, got)
        assertEquals(listOf("notes", "write_file"), got.triggers)
        assertEquals(listOf("list_files", "write_file"), got.tools)
        assertEquals(listOf("不要先写文件再看目录"), got.pitfalls)
        assertEquals(listOf("task-1"), got.evidenceTaskIds)
        assertNull(store.get("missing"))
    }

    @Test
    fun `upsert replaces the same id`() {
        store.upsert(skill("s1"))
        store.upsert(skill("s1", updatedAt = 1_700_000_100L).copy(successCount = 5))
        assertEquals(5, store.get("s1")!!.successCount)
        assertEquals(1, store.listForProject("").size)
    }

    @Test
    fun `listForProject returns project skills plus global skills newest first`() {
        store.upsert(skill("old-proj", projectId = "proj-9", updatedAt = 1_700_000_000L))
        store.upsert(skill("new-proj", projectId = "proj-9", updatedAt = 1_700_000_200L))
        store.upsert(skill("global", projectId = "", updatedAt = 1_700_000_100L))
        store.upsert(skill("other", projectId = "proj-other", updatedAt = 1_700_000_300L))
        val listed = store.listForProject("proj-9").map { it.id }
        assertEquals(listOf("new-proj", "global", "old-proj"), listed)
    }

    @Test
    fun `remove deletes the row`() {
        store.upsert(skill("s1"))
        store.remove("s1")
        assertNull(store.get("s1"))
        assertTrue(store.listForProject("").isEmpty())
    }

    @Test
    fun `dao list is visible to the store after a direct dao insert`() {
        db.skillDao().upsert(skill("dao-1").toEntity())
        assertEquals("dao-1", store.listForProject("").single().id)
    }
}
