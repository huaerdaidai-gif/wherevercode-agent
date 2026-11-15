package wherevercode.agent.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全局搜索纯逻辑测试（核心功能推进第 1 项，2026-11-15）。
 *
 * 覆盖：三类命中、正文/详情命中与标题优先排序、大小写不敏感、
 * 空查询、上限截断（保留最近）、无命中、摘录含命中上下文。
 */
class GlobalSearchTest {

    private fun conversation(
        id: String,
        title: String,
        bodies: List<String> = emptyList(),
        updatedAt: Long = 0L,
    ) = SearchableConversation(
        sessionId = id,
        title = title,
        bodyTexts = bodies,
        updatedAtEpochSeconds = updatedAt,
    )

    private fun project(
        id: String,
        name: String,
        details: List<String> = emptyList(),
        createdAt: Long = 0L,
    ) = SearchableProject(
        projectId = id,
        name = name,
        details = details,
        createdAtEpochSeconds = createdAt,
    )

    private fun task(
        id: String,
        title: String,
        description: String? = null,
        state: String = "queued",
        updatedAt: Long = 0L,
    ) = SearchableTask(
        taskId = id,
        title = title,
        description = description,
        stateLabel = state,
        updatedAtEpochSeconds = updatedAt,
    )

    @Test
    fun `matches across all three groups`() {
        val results = GlobalSearch.search(
            query = "支付",
            conversations = listOf(conversation("s1", "支付接口联调", updatedAt = 10)),
            projects = listOf(project("p1", "支付网关", createdAt = 10)),
            tasks = listOf(task("t1", "修复支付回调", updatedAt = 10)),
        )
        assertEquals(listOf("s1"), results.conversations.map { it.id })
        assertEquals(listOf("p1"), results.projects.map { it.id })
        assertEquals(listOf("t1"), results.tasks.map { it.id })
        assertEquals(SearchGroup.CONVERSATION, results.conversations[0].group)
        assertEquals(SearchGroup.PROJECT, results.projects[0].group)
        assertEquals(SearchGroup.TASK, results.tasks[0].group)
        assertTrue(results.conversations[0].titleMatched)
        assertEquals(3, results.totalCount)
        assertFalse(results.isEmpty)
    }

    @Test
    fun `body and detail matches rank below title matches`() {
        val results = GlobalSearch.search(
            query = "kotlin",
            conversations = listOf(
                // 仅正文命中，且更新时间更新——仍应排在标题命中之后。
                conversation("body-only", "闲聊", bodies = listOf("聊聊 kotlin 协程"), updatedAt = 999),
                conversation("title-hit", "Kotlin 学习笔记", updatedAt = 1),
            ),
            projects = listOf(
                project("path-only", "官网", details = listOf("/home/user/kotlin-site"), createdAt = 999),
                project("name-hit", "Kotlin 工具", createdAt = 1),
            ),
            tasks = listOf(
                task("desc-only", "杂项", description = "整理 kotlin 笔记", updatedAt = 999),
                task("title-hit", "Kotlin 升级", updatedAt = 1),
            ),
        )
        assertEquals(listOf("title-hit", "body-only"), results.conversations.map { it.id })
        assertEquals(listOf("name-hit", "path-only"), results.projects.map { it.id })
        assertEquals(listOf("title-hit", "desc-only"), results.tasks.map { it.id })
        assertFalse(results.conversations[1].titleMatched)
        assertTrue(results.conversations[1].snippet.contains("kotlin"))
    }

    @Test
    fun `matching is case insensitive`() {
        val results = GlobalSearch.search(
            query = "CoRoUtInE",
            conversations = listOf(conversation("s1", "coroutine 入门")),
            projects = listOf(project("p1", "COROUTINE-DEMO")),
            tasks = listOf(task("t1", "修 bug", description = "Coroutine 泄漏")),
        )
        assertEquals(1, results.conversations.size)
        assertEquals(1, results.projects.size)
        assertEquals(1, results.tasks.size)
    }

    @Test
    fun `blank query returns no results`() {
        val conversations = listOf(conversation("s1", "任何标题"))
        val projects = listOf(project("p1", "任何项目"))
        val tasks = listOf(task("t1", "任何任务"))
        assertTrue(GlobalSearch.search("", conversations, projects, tasks).isEmpty)
        assertTrue(GlobalSearch.search("   ", conversations, projects, tasks).isEmpty)
    }

    @Test
    fun `per group cap keeps the most recent twenty`() {
        val many = (1..25).map { i ->
            conversation("s$i", "日报 $i", updatedAt = i.toLong())
        }
        val results = GlobalSearch.search("日报", many, emptyList(), emptyList())
        assertEquals(GlobalSearch.MAX_PER_GROUP, results.conversations.size)
        // 截断在排序之后：保留 updatedAt 最大的 20 条（25..6），最近优先。
        assertEquals("s25", results.conversations.first().id)
        assertEquals("s6", results.conversations.last().id)
    }

    @Test
    fun `no match returns empty results`() {
        val results = GlobalSearch.search(
            query = "不存在的词",
            conversations = listOf(conversation("s1", "标题", bodies = listOf("正文"))),
            projects = listOf(project("p1", "项目", details = listOf("id-1"))),
            tasks = listOf(task("t1", "任务", description = "描述")),
        )
        assertTrue(results.isEmpty)
        assertEquals(0, results.totalCount)
    }

    @Test
    fun `task id and project repo are searchable details`() {
        val results = GlobalSearch.search(
            query = "task-42",
            conversations = emptyList(),
            projects = listOf(
                project("p1", "后端", details = listOf("p1", "https://github.com/example/task-42-api")),
            ),
            tasks = listOf(task("task-42", "无标题任务")),
        )
        // 任务 id 命中（标题回退为 id 时也算标题命中路径之外，靠 id 匹配）。
        assertEquals(listOf("task-42"), results.tasks.map { it.id })
        // 项目仓库地址命中属于详情命中。
        assertEquals(listOf("p1"), results.projects.map { it.id })
        assertFalse(results.projects[0].titleMatched)
    }

    @Test
    fun `excerpt keeps context around the match`() {
        val text = "铺垫文字".repeat(20) + "关键词" + "补充说明".repeat(20)
        val snippet = GlobalSearch.excerpt(text, "关键词")
        assertTrue(snippet.contains("关键词"))
        assertTrue(snippet.length < text.length)
        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
    }
}
