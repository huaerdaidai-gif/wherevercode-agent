package wherevercode.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.ui.EntryRegistry.Group
import wherevercode.agent.ui.EntryRegistry.Status

/**
 * 入口注册表回归测试（V5 导航整改最终定稿，2026-11-15）。
 *
 * 一级菜单严格只有四项：对话、项目、控制。
 * 旧入口从四个主入口的子页面内逐层访问。
 */
class EntryRegistryTest {

    @Test
    fun `registry validates with no errors`() {
        assertEquals(emptyList<String>(), EntryRegistry.validate())
    }

    @Test
    fun `registry has exactly the de-duplicated entries`() {
        assertEquals(83, EntryRegistry.ENTRIES.size)
        assertEquals(83, EntryRegistry.ENTRIES.map { it.id }.distinct().size)
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
    fun `drawer top has exactly four primary entries`() {
        assertEquals(
            listOf("nav_chat", "nav_projects", "nav_channels", "nav_control"),
            EntryRegistry.byGroup(Group.DRAWER_TOP).map { it.id },
        )
        // 对话指向 ID_CHAT
        assertEquals(DrawerController.ID_CHAT, EntryRegistry.find("nav_chat")?.target)
        assertEquals("对话", EntryRegistry.find("nav_chat")?.title)
        // 项目指向 ID_PROJECTS
        assertEquals(DrawerController.ID_PROJECTS, EntryRegistry.find("nav_projects")?.target)
        assertEquals("项目", EntryRegistry.find("nav_projects")?.title)
        // 控制指向 ID_CONTROL
        assertEquals(DrawerController.ID_CONTROL, EntryRegistry.find("nav_control")?.target)
        assertEquals("控制", EntryRegistry.find("nav_control")?.title)
    }

    @Test
    fun `drawer workspace group has secondary entries accessible from primary entries`() {
        val workspace = EntryRegistry.byGroup(Group.DRAWER_WORKSPACE).map { it.id }
        // 旧一级入口移到工作空间分组，从四个主入口内逐层访问
        assertTrue(workspace.contains("nav_history"))
        assertTrue(workspace.contains("nav_running_tasks"))
        assertTrue(workspace.contains("nav_results"))
        assertTrue(workspace.contains("nav_team"))
        assertTrue(workspace.contains("nav_devices"))
        assertTrue(workspace.contains("nav_tools"))
        assertTrue(workspace.contains("nav_help"))
        assertTrue(workspace.contains("nav_workbench"))
        assertTrue(workspace.contains("nav_theme"))
        assertTrue(workspace.contains("nav_pending_approval"))
    }

    @Test
    fun `settings is not in drawer top`() {
        // 设置不再是一级菜单：点击抽屉底部账号头像进入独立设置页面。
        val topIds = EntryRegistry.byGroup(Group.DRAWER_TOP).map { it.id }
        assertFalse(topIds.contains("nav_workbench"))
    }

    @Test
    fun `pending approval and run logs open the console`() {
        val pending = EntryRegistry.find("nav_pending_approval")!!
        assertEquals(Status.IMPLEMENTED, pending.status)
        assertEquals(EntryRegistry.TargetKind.DRAWER, pending.targetKind)
        assertEquals(DrawerController.ID_CONSOLE, pending.target)
        val logs = EntryRegistry.find("feature_run_logs")!!
        assertEquals(Status.IMPLEMENTED, logs.status)
        assertEquals(EntryRegistry.TargetKind.DRAWER, logs.targetKind)
        assertEquals(DrawerController.ID_CONSOLE, logs.target)
        val usage = EntryRegistry.find("feature_usage_cost")!!
        assertEquals(Status.TODO, usage.status)
        assertTrue(usage.todoMessage!!.contains("待开发"))
    }

    @Test
    fun `all old routing targets are still reachable`() {
        // 旧入口 id 保留为路由常量，从子页面内逐层访问
        val targets = EntryRegistry.ENTRIES
            .filter { it.targetKind == EntryRegistry.TargetKind.DRAWER }
            .map { it.target }
            .toSet()
        assertTrue(targets.contains(DrawerController.ID_CHAT))
        assertTrue(targets.contains(DrawerController.ID_PROJECTS))
        assertTrue(targets.contains(DrawerController.ID_CONTROL))
        assertTrue(targets.contains(DrawerController.ID_NEW_CONVERSATION))
        assertTrue(targets.contains(DrawerController.ID_HISTORY_SEARCH))
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
        assertTrue(ids.contains("feature_task_detail"))
        assertTrue(ids.contains("nav_console"))
        assertTrue(ids.contains("nav_control"))
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
    fun `feature center is implemented and opens the all-features list`() {
        val center = EntryRegistry.find("nav_feature_center")!!
        assertEquals(Status.IMPLEMENTED, center.status)
        assertEquals(EntryRegistry.TargetKind.ACTION, center.targetKind)
        assertEquals("open_feature_center", center.target)
    }

    @Test
    fun `seventy screen gap entries are registered as todo features`() {
        val newIds = listOf(
            "feature_global_search",
            "feature_change_diff",
            "feature_agent_detail",
            "feature_network",
            "feature_create_automation",
            "feature_triggers_wakeup",
            "feature_wakeup_policy",
        )
        newIds.forEach { id ->
            val entry = EntryRegistry.find(id)
            assertTrue("missing entry: $id", entry != null)
            assertEquals(Status.TODO, entry!!.status)
            assertEquals(Group.FEATURE, entry.group)
            assertTrue(entry.todoMessage!!.contains("待开发"))
        }
        assertEquals("nav_projects", EntryRegistry.find("feature_change_diff")?.parentId)
        assertEquals("nav_feature_center", EntryRegistry.find("feature_create_automation")?.parentId)
        assertEquals("nav_devices", EntryRegistry.find("feature_network")?.parentId)
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
