package wherevercode.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.ui.EntryRegistry.Group
import wherevercode.agent.ui.EntryRegistry.Status

/**
 * 入口注册表回归测试（V5 C2-3-R1d，maintainer 2026-11-10 补充六：去重汇集）。
 *
 * 纯 JVM 测试：断言去重后清单一一对应、无重复 id、待开发入口点按只
 * 提示（todoMessage 非空且含“待开发”）、已实现入口有目标、功能入口
 * 有已存在的父入口。R2 的抽屉直接复用本注册表，不另起一份。
 */
class EntryRegistryTest {

    @Test
    fun `registry validates with no errors`() {
        assertEquals(emptyList<String>(), EntryRegistry.validate())
    }

    @Test
    fun `registry has exactly the de-duplicated entries`() {
        assertEquals(36, EntryRegistry.ENTRIES.size)
        assertEquals(36, EntryRegistry.ENTRIES.map { it.id }.distinct().size)
    }

    @Test
    fun `home has four suggestion pills and three top entries`() {
        assertEquals(
            listOf("home_dev_app", "home_import_project", "home_organize_team", "home_automation"),
            EntryRegistry.homeSuggestions().map { it.id },
        )
        assertEquals(
            listOf("home_search", "home_brain", "home_new"),
            EntryRegistry.byGroup(Group.HOME_TOP).map { it.id },
        )
    }

    @Test
    fun `drawer workspace group keeps secondary theme and approval entries`() {
        assertEquals(
            listOf("nav_theme", "nav_pending_approval"),
            EntryRegistry.byGroup(Group.DRAWER_WORKSPACE).map { it.id },
        )
        // 界面与主题（R2）：已实现的动作入口，接 cycleTheme()，不是待开发。
        val theme = EntryRegistry.find("nav_theme")!!
        assertEquals(Status.IMPLEMENTED, theme.status)
        assertEquals(EntryRegistry.TargetKind.ACTION, theme.targetKind)
        assertEquals("cycle_theme", theme.target)
    }

    @Test
    fun `drawer top follows the ten V5 default entries`() {
        assertEquals(
            listOf("nav_chat", "nav_history", "nav_projects", "nav_running_tasks", "nav_team", "nav_devices", "nav_tools", "nav_results", "nav_help", "nav_workbench"),
            EntryRegistry.byGroup(Group.DRAWER_TOP).map { it.id },
        )
        assertEquals(DrawerController.ID_HISTORY_SEARCH, EntryRegistry.find("nav_history")?.target)
        assertEquals("任务", EntryRegistry.find("nav_running_tasks")?.title)
        // V5 控制台（2026-11-11）：nav_console 已实现（抽屉“扩展与资源”分组，
        // 指向 DrawerController.ID_CONSOLE），不再是待开发。
        val console = EntryRegistry.find("nav_console")!!
        assertEquals(Status.IMPLEMENTED, console.status)
        assertEquals(EntryRegistry.TargetKind.DRAWER, console.targetKind)
        assertEquals(DrawerController.ID_CONSOLE, console.target)
    }

    @Test
    fun `pending approval and run logs open the console`() {
        // 待审批与运行日志是控制台里的模块：点按打开控制台（深链定位到对应模块，
        // 见 MainActivity.openRegistryEntry 的 consoleSection）。
        val pending = EntryRegistry.find("nav_pending_approval")!!
        assertEquals(Status.IMPLEMENTED, pending.status)
        assertEquals(EntryRegistry.TargetKind.DRAWER, pending.targetKind)
        assertEquals(DrawerController.ID_CONSOLE, pending.target)
        val logs = EntryRegistry.find("feature_run_logs")!!
        assertEquals(Status.IMPLEMENTED, logs.status)
        assertEquals(EntryRegistry.TargetKind.DRAWER, logs.targetKind)
        assertEquals(DrawerController.ID_CONSOLE, logs.target)
        // 用量与成本仍是待开发：大脑不返回用量，不编数字。
        val usage = EntryRegistry.find("feature_usage_cost")!!
        assertEquals(Status.TODO, usage.status)
        assertTrue(usage.todoMessage!!.contains("待开发"))
    }

    @Test
    fun `all ten V5-UI section-three entries are present`() {
        val targets = EntryRegistry.ENTRIES
            .filter { it.targetKind == EntryRegistry.TargetKind.DRAWER }
            .map { it.target }
            .toSet()
        assertTrue(targets.contains(DrawerController.ID_NEW_CONVERSATION))
        assertTrue(targets.contains(DrawerController.ID_HISTORY_SEARCH))
        assertTrue(targets.contains(DrawerController.ID_PROJECTS))
        assertTrue(targets.contains(DrawerController.ID_TASKS))
        assertTrue(targets.contains(DrawerController.ID_AGENTS_TEAM))
        assertTrue(targets.contains(DrawerController.ID_DEVICES))
        assertTrue(targets.contains(DrawerController.ID_TOOLS_CONNECTIONS))
        assertTrue(targets.contains(DrawerController.ID_RESULTS))
        assertTrue(targets.contains(DrawerController.ID_HELP))
        assertTrue(targets.contains(DrawerController.ID_SETTINGS))
    }

    @Test
    fun `every de-duplicated feature is registered once`() {
        val ids = EntryRegistry.ENTRIES.map { it.id }.toSet()
        // 去重清单（补充六）：任务详情 / 控制台 / 权限 / 群聊 / 项目管理 /
        // 工具中心 / Agent 与设备 / 拓扑 / 用量与成本 / 运行日志 / 项目文件
        // 与代码 / 自动执行 / 快速操作 / 收纳系统（简报与交付归档已并进
        // 控制台总览与任务详情交付页，不单列）。
        assertTrue(ids.contains("feature_task_detail"))
        assertTrue(ids.contains("nav_console"))
        assertTrue(ids.contains("feature_permissions"))
        assertTrue(ids.contains("feature_team_chat"))
        assertTrue(ids.contains("nav_projects"))
        assertTrue(ids.contains("nav_tools"))
        assertTrue(ids.contains("nav_team"))
        assertTrue(ids.contains("feature_topology"))
        assertTrue(ids.contains("feature_usage_cost"))
        assertTrue(ids.contains("feature_run_logs"))
        assertTrue(ids.contains("feature_project_files"))
        assertTrue(ids.contains("feature_auto_exec"))
        assertTrue(ids.contains("feature_quick_actions"))
        assertTrue(ids.contains("feature_nesting"))
    }

    @Test
    fun `todo entries only ever prompt and never claim to be implemented`() {
        val todos = EntryRegistry.ENTRIES.filter { it.status == Status.TODO }
        assertTrue(todos.isNotEmpty())
        todos.forEach { entry ->
            val msg = entry.todoMessage
            assertTrue(msg != null && msg.contains("待开发"))
            assertEquals(EntryRegistry.TargetKind.TODO, entry.targetKind)
            assertEquals("", entry.target)
        }
    }

    @Test
    fun `implemented entries have a non-blank target`() {
        EntryRegistry.ENTRIES
            .filter { it.status == Status.IMPLEMENTED }
            .forEach { entry ->
                assertTrue(entry.target.isNotBlank())
            }
    }
}
