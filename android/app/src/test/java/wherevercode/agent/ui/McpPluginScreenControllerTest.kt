package wherevercode.agent.ui

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.ui.McpPluginScreenController.McpPluginScreenState
import wherevercode.agent.ui.McpPluginScreenController.McpServerRow
import wherevercode.agent.ui.McpPluginScreenController.PluginRow

/**
 * MCP / 插件屏渲染测试（Robolectric）。
 *
 * 只断言渲染出来的文字：未连接如实写“未连接”不写工具数、已连接写
 * 工具数、失败写原因、插件列工具名、七类计数在场。开关与“连接”
 * 只记回调，不真连服务器。
 */
@RunWith(RobolectricTestRunner::class)
class McpPluginScreenControllerTest {

    private val toggledServers = mutableListOf<Pair<String, Boolean>>()
    private val connectedServers = mutableListOf<String>()
    private val toggledPlugins = mutableListOf<Pair<String, Boolean>>()
    private var refreshCount = 0

    private fun controller(): McpPluginScreenController = McpPluginScreenController(
        RuntimeEnvironment.getApplication(),
        onToggleServer = { id, enabled -> toggledServers += id to enabled },
        onConnectServer = { id -> connectedServers += id },
        onTogglePlugin = { id, enabled -> toggledPlugins += id to enabled },
        onRefresh = { refreshCount++ },
    )

    private fun state() = McpPluginScreenState(
        servers = listOf(
            McpServerRow(
                id = "hand-mcp-stdio", displayName = "Hand MCP 服务（stdio）",
                transportLabel = "stdio · python3 hand_server.py mcp",
                toolCount = null, statusText = "未连接", enabled = true,
                detail = "Hand 设备上的 MCP 服务。",
            ),
            McpServerRow(
                id = "test-server", displayName = "测试服务器",
                transportLabel = "HTTP · http://127.0.0.1:9/",
                toolCount = null, statusText = "连接失败：拒绝连接", enabled = true,
                detail = "",
            ),
            McpServerRow(
                id = "live-server", displayName = "已连服务器",
                transportLabel = "stdio · python3 server.py",
                toolCount = 2, statusText = "已连接 · 2 个工具", enabled = true,
                detail = "",
            ),
        ),
        plugins = listOf(
            PluginRow(
                id = "local-utils", displayName = "本地小工具", version = "1",
                toolNames = listOf("local_time", "calc"), enabled = true, detail = "",
            ),
            PluginRow(
                id = "healthcheck", displayName = "健康检查", version = null,
                toolNames = emptyList(), enabled = true,
                detail = "run_healthcheck()（Hand 内）；在 Hand 设备上运行，App 内不执行。",
            ),
        ),
        kindCounts = mapOf(
            "adapter" to 6, "connector" to 2, "plugin" to 2, "mcp" to 1,
            "wakeup" to 2, "protocol" to 3, "brain" to 8,
        ),
    )

    private fun allTexts(view: View): List<String> {
        val out = mutableListOf<String>()
        if (view is TextView) out += view.text.toString()
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) out += allTexts(view.getChildAt(i))
        }
        return out
    }

    private fun assertContains(texts: List<String>, needle: String) {
        assertTrue("expected text containing <$needle> in $texts", texts.any { it.contains(needle) })
    }

    @Test
    fun `unconnected server says unconnected and shows no tool count`() {
        val c = controller()
        c.render(state())
        val texts = allTexts(c.root)
        assertContains(texts, "Hand MCP 服务（stdio）")
        assertContains(texts, "未连接")
        // 只有已连服务器那一行带“2 个工具”；未连接行不编工具数。
        assertTrue(texts.count { it.contains("个工具") } == 1)
    }

    @Test
    fun `failed server shows the reason`() {
        val c = controller()
        c.render(state())
        assertContains(allTexts(c.root), "连接失败：拒绝连接")
    }

    @Test
    fun `connected server shows its tool count`() {
        val c = controller()
        c.render(state())
        assertContains(allTexts(c.root), "已连接 · 2 个工具")
    }

    @Test
    fun `plugins list their tools and the registry-only plugin says where it runs`() {
        val c = controller()
        c.render(state())
        val texts = allTexts(c.root)
        assertContains(texts, "本地小工具 v1")
        assertContains(texts, "工具：local_time、calc")
        assertContains(texts, "健康检查")
        assertContains(texts, "在 Hand 设备上运行")
    }

    @Test
    fun `seven kind counts are shown`() {
        val c = controller()
        c.render(state())
        val texts = allTexts(c.root)
        assertContains(texts, "mcp 1 个")
        assertContains(texts, "plugin 2 个")
        assertContains(texts, "brain 8 个")
    }

    @Test
    fun `loading state says loading instead of an empty list`() {
        val c = controller()
        c.renderLoading()
        assertContains(allTexts(c.root), "大脑加载中")
    }

    @Test
    fun `empty state says none instead of faking rows`() {
        val c = controller()
        c.render(McpPluginScreenState(servers = emptyList(), plugins = emptyList(), kindCounts = emptyMap()))
        val texts = allTexts(c.root)
        assertContains(texts, "暂无 MCP 服务器")
        assertContains(texts, "暂无插件")
    }
}
