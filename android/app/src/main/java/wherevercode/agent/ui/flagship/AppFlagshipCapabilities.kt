package wherevercode.agent.ui.flagship

import android.content.Context
import android.content.Intent
import wherevercode.agent.MainActivity

/**
 * 70 屏路由 → 既有真实能力的适配器（双端组装第一轮）。
 *
 * 状态口径（与实施契约的状态机一致，只许由真实证据驱动）：
 * - READY_VERIFIED：route 背后是既有真实屏，且已有真机/模拟器实测证据
 *   （证据摘要写在 [READY] 的 value 里，逐屏明细见双端集成验收表）。
 * - IMPLEMENTED_UNVERIFIED：既有真实屏已接线，但缺本轮设备验收。
 * - PLANNED：没有对应既有实现，统一「待开发」，按钮保持禁用。
 *
 * [openExistingFeature] 把 route 深链回 MainActivity 的既有注册表入口
 * （entry id 与 EntryRegistry 一一对应），不复制任何业务实现。
 */
class AppFlagshipCapabilities(private val context: Context) : FlagshipCapabilityProvider {

    override fun status(routeId: String): FeatureStatus {
        READY[routeId]?.let { return FeatureStatus(FeatureState.READY_VERIFIED, it) }
        if (routeId in UNVERIFIED) return FeatureStatus(FeatureState.IMPLEMENTED_UNVERIFIED)
        return FeatureStatus(FeatureState.PLANNED)
    }

    override fun openExistingFeature(routeId: String): Boolean {
        val entryId = ROUTE_TO_ENTRY[routeId] ?: return false
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_FLAGSHIP_ENTRY, entryId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(intent)
        return true
    }

    private companion object {
        /** route → 证据摘要（仅限已有实测证据的既有真实屏）。 */
        val READY: Map<String, String> = mapOf(
            "chat" to "K70 真机历代更新包在用；大脑对话已真机验证",
            "conversation" to "K70 真机在用；执行中会话在对话屏内呈现",
            "history" to "K70 真机在用；会话列表已实现",
            "compose" to "模拟器验收 run 43588029626：表单建任务真实落库",
            "tasks" to "模拟器验收 run 43588029626：任务列表与认领有实测记录",
            "taskdetail" to "模拟器验收 run 43588029626：任务详情与控制入口有实测记录",
            "projects" to "K70 真机在用；项目列表已实现",
            "projectdetail" to "K70 真机在用；项目概览在项目屏内呈现",
            "files" to "K70 真机在用；项目文件树只读查看已实现（注册表自标）",
            "agents" to "K70 真机在用；Agent 注册表与开关已实现",
            "channels" to "V5 频道生产在跑；App 侧 WebSocket 订阅有生产日志",
            "channel" to "V5 频道生产在跑；频道会话已实现",
            "devices" to "K70 真机在用；设备添加与 Hand 探活 2026-11-12 已验证",
            "models" to "K70 真机在用；设置内大脑管理（增删改/设当前）已实现",
            "settings" to "K70 真机在用；设置屏已实现",
            "artifacts" to "K70 真机在用；成果聚合屏已实现",
        )

        /** 已接线既有真实屏、缺本轮设备验收的 route。 */
        val UNVERIFIED: Set<String> = setOf(
            "approvals", "editor", "team", "agentdetail", "devicedetail",
            "mcp", "connections", "dashboard", "activity", "onboarding",
        )

        /** route → EntryRegistry 入口 id（深链目标）。onboarding 无入口：首次启动覆盖层，不可重开。 */
        val ROUTE_TO_ENTRY: Map<String, String> = mapOf(
            "chat" to "nav_chat",
            "conversation" to "nav_chat",
            "history" to "nav_history",
            "compose" to "nav_running_tasks",
            "tasks" to "nav_running_tasks",
            "taskdetail" to "nav_running_tasks",
            "approvals" to "nav_pending_approval",
            "artifacts" to "nav_results",
            "projects" to "nav_projects",
            "projectdetail" to "nav_projects",
            "files" to "nav_projects",
            "editor" to "nav_projects",
            "team" to "nav_team",
            "agents" to "nav_team",
            "agentdetail" to "nav_team",
            "channels" to "nav_team_channel",
            "channel" to "nav_team_channel",
            "devices" to "nav_devices",
            "devicedetail" to "nav_devices",
            "models" to "nav_workbench",
            "settings" to "nav_workbench",
            "mcp" to "nav_mcp_plugins",
            "connections" to "nav_tools",
            "dashboard" to "nav_console",
            "activity" to "feature_run_logs",
        )
    }
}
