package wherevercode.agent.core.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 聊天选项目（2026-11-11，maintainer 真机发火）：
 * 新对话悄悄清空绑定、聊天里选不了项目、两行同名项目分不清。
 * 纯逻辑单测，不跑 Robolectric。
 */
class ChatProjectBindingTest {

    @Test
    fun `setProject updates immediately and persists`() {
        var saved: String? = "unset"
        val binding = ChatProjectBinding(load = { null }, save = { saved = it })
        binding.setProject("wherevercode-agent-2")
        assertEquals("wherevercode-agent-2", binding.projectId)
        assertEquals("wherevercode-agent-2", saved)
    }

    @Test
    fun `new conversation keeps the bound project`() {
        // 2026-11-11 真机 bug：开新对话悄悄清空绑定，聊天里又没选项目入口，卡死。
        var saved: String? = "not-written"
        val binding = ChatProjectBinding(
            load = { "wherevercode-agent-2" },
            save = { saved = it },
        )
        assertEquals("wherevercode-agent-2", binding.projectId)
        binding.onNewConversation()
        assertEquals("wherevercode-agent-2", binding.projectId)
        // 没重写持久化（还是上次的值，没被清成 null）。
        assertEquals("not-written", saved)
    }

    @Test
    fun `load restores the bound project and clear works`() {
        var saved: String? = "p1"
        val binding = ChatProjectBinding(load = { "p1" }, save = { saved = it })
        assertEquals("p1", binding.projectId)
        binding.clear()
        assertNull(binding.projectId)
        assertNull(saved)
    }

    @Test
    fun `uniqueProjectDisplayName leaves a fresh name alone`() {
        assertEquals("my-app", uniqueProjectDisplayName("my-app", listOf("other")))
    }

    @Test
    fun `uniqueProjectDisplayName suffixes a duplicate name`() {
        assertEquals(
            "wherevercode-agent 2",
            uniqueProjectDisplayName("wherevercode-agent", listOf("wherevercode-agent")),
        )
        assertEquals(
            "wherevercode-agent 3",
            uniqueProjectDisplayName(
                "wherevercode-agent",
                listOf("wherevercode-agent", "wherevercode-agent 2"),
            ),
        )
    }

    @Test
    fun `picker items show name with home label and empty stays empty`() {
        val gh = Project(
            projectId = "gh",
            name = "wherevercode-agent",
            repoUrl = "https://github.com/o/r",
        )
        val plain = Project(projectId = "plain", name = "notes")
        val items = chatProjectPickerItems(listOf(gh, plain)) { project ->
            if (project.projectId == "gh") "GitHub · o/r" else "未指定家"
        }
        assertEquals(
            listOf(
                "wherevercode-agent（GitHub · o/r）" to "gh",
                "notes（未指定家）" to "plain",
            ),
            items,
        )
        assertTrue(chatProjectPickerItems(emptyList()) { "x" }.isEmpty())
    }
}
