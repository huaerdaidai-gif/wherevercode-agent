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
        assertEquals(89, EntryRegistry.ENTRIES.size)
        assertEquals(89, EntryRegistry.ENTRIES.map { it.id }.distinct().size)
    }

    @Test
    fun `home has four suggestion pills and two top entries`() {
        assertEquals(
            listOf("home_dev_app", "home_import_project", "home_organize_team", "home_automation"),
            EntryRegistry.homeSuggestions().map { it.id },
        )
        assertEquals(
            listOf("home_search", "home_new"),
            EntryRegistry.byGroup(Group.HOME_TOP).map { it.id },
        )
    }

    @Test
    fun `brain is demoted to feature center only`() {
        // 大脑降级（Funk 2026-11-17 定）：首页顶栏与抽屉一级入口无大脑；
        // 旧 home_brain 快捷入口已撤下。
        assertEquals(null, EntryRegistry.find("home_brain"))
        assertFalse(EntryRegistry.byGroup(Group.HOME_TOP).any { it.id.contains("brain") })
        assertFalse(EntryRegistry.byGroup(Group.DRAWER_TOP).any { it.id.contains("brain") })
        // 大脑只在「全部功能」（FEATURE 组，父入口 nav_feature_center）里
        // 一条枢纽入口：点开分模型对话 / 大脑管理两路（见 MainActivity
        // showBrainHubDialog）；适配器与管理页本体不删。
        val brain = EntryRegistry.find("feature_brain")
        assertEquals("大脑", brain?.title)
        assertEquals(Group.FEATURE, brain?.group)
        assertEquals("nav_feature_center", brain?.parentId)
        assertEquals(Status.IMPLEMENTED, brain?.status)
        assertEquals(EntryRegistry.TargetKind.ACTION, brain?.targetKind)
        assertEquals("open_brain_hub", brain?.target)
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
        // 「控制台」已与「控制」合并为一份：旧 nav_console 注册删除，
        // 旧路由常量 ID_CONSOLE 保留兼容（见 all old routing targets 测试）。
        assertFalse(ids.contains("nav_console"))
        assertTrue(ids.contains("nav_control"))
        assertTrue(ids.contains("feature_permissions"))
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
    fun `channel and task gap entries are registered as todo features`() {
        // 2026-11-15 查缺补漏新增：频道管理/频道文件/私聊/扩展 Manifest
        // 四个真缺口仍以诚实待开发入口落地，不伪装已实现。新建任务
        // （feature_create_task）已于核心功能推进第 3 项真实现，
        // 见 create task 测试，不再在本组。
        val gapIds = listOf(
            "feature_channel_manage",
            "feature_channel_files",
            "feature_direct_message",
            "feature_extension_manifest",
        )
        gapIds.forEach { id ->
            val entry = EntryRegistry.find(id)
            assertTrue("missing entry: $id", entry != null)
            assertEquals(Status.TODO, entry!!.status)
            assertEquals(Group.FEATURE, entry.group)
            assertTrue(entry.todoMessage!!.contains("待开发"))
        }
        assertEquals("nav_channels", EntryRegistry.find("feature_channel_manage")?.parentId)
        assertEquals("nav_channels", EntryRegistry.find("feature_channel_files")?.parentId)
        assertEquals("nav_channels", EntryRegistry.find("feature_direct_message")?.parentId)
        assertEquals("nav_mcp_plugins", EntryRegistry.find("feature_extension_manifest")?.parentId)
    }

    @Test
    fun `master list v1 additions are registered as implemented screen features`() {
        // 2026-11-15 项目整理：主清单 v1.0 已裁决采纳的三项——代码编辑器、
        // 自主度、应用更新——均已在页内真实实现（WorkspaceEditSession 接线 /
        // 设置页自主度分节 / 设置页检查更新），本轮补登为已实现 SCREEN
        // 入口，从功能中心落到能力所在页（项目页 / 设置页）。
        val editor = EntryRegistry.find("feature_code_editor")!!
        assertEquals(Status.IMPLEMENTED, editor.status)
        assertEquals(EntryRegistry.TargetKind.SCREEN, editor.targetKind)
        assertEquals("code_editor", editor.target)
        assertEquals(Group.FEATURE, editor.group)
        assertEquals("feature_project_files", editor.parentId)

        val autonomy = EntryRegistry.find("feature_autonomy")!!
        assertEquals(Status.IMPLEMENTED, autonomy.status)
        assertEquals(EntryRegistry.TargetKind.SCREEN, autonomy.targetKind)
        assertEquals("autonomy", autonomy.target)
        assertEquals(Group.FEATURE, autonomy.group)
        assertEquals("nav_workbench", autonomy.parentId)

        val update = EntryRegistry.find("feature_app_update")!!
        assertEquals(Status.IMPLEMENTED, update.status)
        assertEquals(EntryRegistry.TargetKind.SCREEN, update.targetKind)
        assertEquals("app_update", update.target)
        assertEquals(Group.FEATURE, update.group)
        assertEquals("nav_workbench", update.parentId)
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
    fun `global search is registered as implemented screen feature`() {
        // 2026-11-15 核心功能推进第 1 项：全局搜索已真实现（GlobalSearch
        // 纯逻辑 + GlobalSearchDialog，搜本机会话/项目/任务），从待开发
        // 升为已实现 SCREEN 入口，落点 target = "global_search"。
        val entry = EntryRegistry.find("feature_global_search")!!
        assertEquals(Status.IMPLEMENTED, entry.status)
        assertEquals(EntryRegistry.TargetKind.SCREEN, entry.targetKind)
        assertEquals("global_search", entry.target)
        assertEquals(Group.FEATURE, entry.group)
        assertEquals("nav_chat", entry.parentId)
    }

    @Test
    fun `queue schedule is registered as implemented screen feature`() {
        // 2026-11-15 核心功能推进第 2 项：队列与调度已真实现（QueueBoard
        // 纯逻辑 + QueueBoardDialog，按 TaskStore 真实状态分组看板），
        // 从待开发升为已实现 SCREEN 入口，落点 target = "queue_schedule"。
        val entry = EntryRegistry.find("feature_queue_schedule")!!
        assertEquals(Status.IMPLEMENTED, entry.status)
        assertEquals(EntryRegistry.TargetKind.SCREEN, entry.targetKind)
        assertEquals("queue_schedule", entry.target)
        assertEquals(Group.FEATURE, entry.group)
        assertEquals("nav_running_tasks", entry.parentId)
        // 状态计数（随第 3 项新建任务升级后）：待开发 56、已实现 33，总数仍 89。
        assertEquals(56, EntryRegistry.ENTRIES.count { it.status == Status.TODO })
        assertEquals(33, EntryRegistry.ENTRIES.count { it.status == Status.IMPLEMENTED })
    }

    @Test
    fun `create task is registered as implemented screen feature`() {
        // 2026-11-15 核心功能推进第 3 项：新建任务已真实现（CreateTaskForm
        // 纯逻辑 + CreateTaskFormDialog，提交走与对话派单同一创建路径），
        // 从待开发升为已实现 SCREEN 入口，落点 target = "create_task"。
        val entry = EntryRegistry.find("feature_create_task")!!
        assertEquals(Status.IMPLEMENTED, entry.status)
        assertEquals(EntryRegistry.TargetKind.SCREEN, entry.targetKind)
        assertEquals("create_task", entry.target)
        assertEquals(Group.FEATURE, entry.group)
        assertEquals("nav_running_tasks", entry.parentId)
    }

    @Test
    fun `seventy screen gap entries are registered as todo features`() {
        val newIds = listOf(
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
