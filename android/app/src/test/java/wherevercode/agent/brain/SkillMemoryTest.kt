package wherevercode.agent.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillMemoryTest {

    private fun evidence(
        taskId: String = "task-1",
        projectId: String? = null,
        goal: String = "在工作区新建 notes.txt 并写入 hello skill",
        success: Boolean = true,
        error: String? = null,
        toolsUsed: List<String> = listOf("list_files", "write_file"),
        keySteps: List<String> = listOf(
            "list_files 列出工作区",
            "write_file 写入 notes.txt",
        ),
    ) = SettledTaskEvidence(
        taskId = taskId,
        projectId = projectId,
        goal = goal,
        success = success,
        error = error,
        toolsUsed = toolsUsed,
        keySteps = keySteps,
    )

    private fun skill(
        id: String = "skill-1",
        projectId: String = "",
        triggers: List<String> = listOf("notes", "write_file"),
        successCount: Int = 1,
        failureCount: Int = 0,
        lastUsed: Long = 1_700_000_000L,
    ) = Skill(
        id = id,
        projectId = projectId,
        name = "新建文件写内容",
        triggers = triggers,
        summary = "先 list_files 看现状，再 write_file 一次写完。",
        steps = listOf("list_files 列出工作区", "write_file 写入文件"),
        tools = listOf("list_files", "write_file"),
        pitfalls = listOf("不要先写文件再看目录"),
        evidenceTaskIds = listOf("task-1"),
        successCount = successCount,
        failureCount = failureCount,
        lastUsedEpochSeconds = lastUsed,
        createdAtEpochSeconds = 1_700_000_000L,
        updatedAtEpochSeconds = 1_700_000_000L,
    )

    @Test
    fun `extractTriggers keeps latin keywords cjk phrases and tool names`() {
        val triggers = SkillCapture.extractTriggers("在工作区新建 notes.txt 并写入 hello skill", listOf("write_file"))
        assertTrue("write_file" in triggers)
        assertTrue("notes" in triggers)
        assertTrue("hello" in triggers)
        assertTrue("skill" in triggers)
        // 中文短语按标点/空白切段留整段（"在工作区新建"），不停用词单字。
        assertTrue("在工作区新建" in triggers)
        assertTrue("并写入" in triggers)
        assertFalse("在" in triggers)
        assertFalse("并" in triggers)
        assertTrue(triggers.size <= SkillMemory.MAX_TRIGGERS)
    }

    @Test
    fun `buildFromTask returns null for failures and for empty evidence`() {
        assertNull(SkillCapture.buildFromTask(evidence(success = false, error = "boom")))
        assertNull(SkillCapture.buildFromTask(evidence(toolsUsed = emptyList(), keySteps = emptyList())))
    }

    @Test
    fun `buildFromTask builds a skill with triggers tools and steps`() {
        val built = SkillCapture.buildFromTask(evidence())!!
        assertEquals("", built.projectId)
        assertTrue(built.triggers.contains("write_file"))
        assertTrue(built.tools.contains("write_file"))
        assertEquals(1, built.successCount)
        assertEquals(0, built.failureCount)
        assertEquals(listOf("task-1"), built.evidenceTaskIds)
        assertTrue(built.summary.isNotBlank())
    }

    @Test
    fun `buildFromTask keeps the project id when the task has one`() {
        val built = SkillCapture.buildFromTask(evidence(projectId = "proj-9"))!!
        assertEquals("proj-9", built.projectId)
    }

    @Test
    fun `score is zero without a trigger hit even when the project matches`() {
        val s = skill(projectId = "proj-9", triggers = listOf("docker"))
        assertEquals(0, SkillRetrieval.score(s, "在工作区新建 notes.txt", "proj-9"))
    }

    @Test
    fun `score is zero for a skill with no success yet`() {
        val s = skill(successCount = 0, triggers = listOf("notes"))
        assertEquals(0, SkillRetrieval.score(s, "新建 notes.txt", ""))
    }

    @Test
    fun `score rewards trigger hits project match success and recency`() {
        val now = 1_700_000_000L
        val projectSkill = skill(projectId = "proj-9", successCount = 3, lastUsed = now - 100)
        val globalSkill = skill(id = "skill-2", projectId = "", successCount = 3, lastUsed = now - 100)
        val projectScore = SkillRetrieval.score(projectSkill, "新建 notes.txt", "proj-9", now)
        val globalScore = SkillRetrieval.score(globalSkill, "新建 notes.txt", "proj-9", now)
        assertTrue(projectScore > globalScore)
        assertTrue(projectScore > 10)
    }

    @Test
    fun `score penalises failures`() {
        val now = 1_700_000_000L
        val clean = skill(successCount = 2, failureCount = 0, lastUsed = now - 100)
        val flaky = skill(id = "skill-2", successCount = 2, failureCount = 2, lastUsed = now - 100)
        assertTrue(SkillRetrieval.score(clean, "新建 notes.txt", "", now) > SkillRetrieval.score(flaky, "新建 notes.txt", "", now))
    }

    @Test
    fun `retrieve caps at three and skips irrelevant skills`() {
        val now = 1_700_000_000L
        val store = InMemorySkillStore()
        repeat(5) { i ->
            store.upsert(skill(id = "s$i", triggers = listOf("notes", "t$i"), lastUsed = now - i))
        }
        store.upsert(skill(id = "docker", triggers = listOf("docker")))
        val found = store.retrieveForTask("", "新建 notes.txt")
        assertEquals(SkillMemory.MAX_SKILLS_PER_CALL, found.size)
        assertTrue(found.none { it.id == "docker" })
    }

    @Test
    fun `retrieve returns nothing when nothing matches`() {
        val store = InMemorySkillStore()
        store.upsert(skill())
        assertTrue(store.retrieveForTask("", "部署 docker 容器").isEmpty())
        assertEquals("", store.summarizeForPrompt("", "部署 docker 容器"))
    }

    @Test
    fun `renderForPrompt includes name summary and pitfalls and stays under the cap`() {
        val text = SkillRetrieval.renderForPrompt(listOf(skill()))
        assertTrue(text.contains("【技能：新建文件写内容】"))
        assertTrue(text.contains("先 list_files 看现状"))
        assertTrue(text.contains("不要先写文件再看目录"))
        assertTrue(text.length <= SkillMemory.MAX_SKILL_TEXT_CHARS)
        assertEquals("", SkillRetrieval.renderForPrompt(emptyList()))
    }

    @Test
    fun `mergeInto reinforces success and unions tools triggers and evidence`() {
        val existing = skill()
        val fresh = SkillCapture.buildFromTask(
            evidence(taskId = "task-2", toolsUsed = listOf("write_file", "run_command")),
        )!!
        val merged = SkillCapture.mergeInto(existing, fresh, 1_700_000_100L)
        assertEquals(2, merged.successCount)
        assertTrue("run_command" in merged.tools)
        assertEquals(listOf("task-1", "task-2"), merged.evidenceTaskIds)
        assertTrue(merged.triggers.contains("write_file"))
    }

    @Test
    fun `withPitfall records the pitfall and counts a failure`() {
        val updated = SkillCapture.withPitfall(skill(), "路径写错了", 1_700_000_100L)
        assertEquals(1, updated.failureCount)
        assertTrue(updated.pitfalls.first() == "路径写错了")
        assertEquals(1, updated.successCount)
    }

    @Test
    fun `refiner creates a skill on success and merges a similar second one`() {
        val store = InMemorySkillStore()
        val refiner = SkillRefiner(store)
        val first = refiner.onTaskSettled(evidence())!!
        assertEquals(1, first.successCount)
        val second = refiner.onTaskSettled(evidence(taskId = "task-2"))!!
        assertEquals(first.id, second.id)
        assertEquals(2, second.successCount)
        assertEquals(1, store.listForProject("").size)
    }

    @Test
    fun `refiner records a pitfall on failure when a matching skill exists`() {
        val store = InMemorySkillStore()
        val refiner = SkillRefiner(store)
        refiner.onTaskSettled(evidence())
        val failed = refiner.onTaskSettled(
            evidence(taskId = "task-9", success = false, error = "write_file 路径越界"),
        )!!
        assertEquals(1, failed.failureCount)
        assertTrue(failed.pitfalls.any { it.contains("路径越界") })
    }

    @Test
    fun `refiner returns null on failure when no skill matches`() {
        val store = InMemorySkillStore()
        val refiner = SkillRefiner(store)
        assertNull(refiner.onTaskSettled(evidence(success = false, error = "boom")))
    }

    @Test
    fun `refiner onTaskRework corrects the skill that proved the task`() {
        val store = InMemorySkillStore()
        val refiner = SkillRefiner(store)
        refiner.onTaskSettled(evidence())
        assertTrue(refiner.onTaskRework("task-1", "写错了地方，重做"))
        val corrected = store.listForProject("").single()
        assertEquals(1, corrected.failureCount)
        assertTrue(corrected.pitfalls.any { it.contains("返工") && it.contains("写错了地方") })
        assertFalse(refiner.onTaskRework("unknown-task", "重做"))
    }

    @Test
    fun `inMemory store lists project skills plus global skills`() {
        val store = InMemorySkillStore()
        store.upsert(skill(id = "p1", projectId = "proj-9"))
        store.upsert(skill(id = "g1", projectId = ""))
        store.upsert(skill(id = "p2", projectId = "proj-other"))
        val listed = store.listForProject("proj-9").map { it.id }
        assertTrue("p1" in listed)
        assertTrue("g1" in listed)
        assertFalse("p2" in listed)
    }

    @Test
    fun `preferencesSummary shows the best skills without injecting them`() {
        val store = InMemorySkillStore()
        store.upsert(skill(successCount = 5))
        val summary = store.preferencesSummary("")
        assertTrue(summary.contains("新建文件写内容"))
        assertTrue(summary.contains("成功 5 次"))
        assertEquals("", InMemorySkillStore().preferencesSummary(""))
    }

    @Test
    fun `secret looking text is redacted out of captured skill fields`() {
        val built = SkillCapture.buildFromTask(
            evidence(
                keySteps = listOf("write_file 写入 notes.txt token=abc123SECRET"),
            ),
        )
        assertNotNull(built)
        assertFalse(built!!.summary.contains("abc123SECRET"))
    }
}
