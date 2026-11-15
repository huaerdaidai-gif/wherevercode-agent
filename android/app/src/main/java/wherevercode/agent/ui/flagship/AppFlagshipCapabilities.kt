package wherevercode.agent.ui.flagship

import android.content.Context
import android.content.Intent
import wherevercode.agent.MainActivity

data class V5Binding(val entry: String, val description: String)
object V5Bindings {
  val all = mapOf(
    "search" to V5Binding("local_search", "本机搜索会话正文、任务、项目与全部页面；不含云端索引。"),
    "memory" to V5Binding("local_memory", "与大脑共用持久记忆；事实经用户确认，拒绝敏感信息。"),
    "skills" to V5Binding("local_skills", "现有技能库的项目筛选、详情、人工记录与删除；不等于规则自动化。"),
    "chat" to V5Binding("nav_chat", "打开新对话；运行中的轮次仍由现有确认流程保护。"),
    "conversation" to V5Binding("current_conversation", "继续当前会话，保留 Agent、项目和执行状态。"),
    "history" to V5Binding("nav_history", "本地历史会话列表，可搜索与恢复；不包含云端同步。"),
    "compose" to V5Binding("create_task", "打开现有任务创建表单，提交后真实写入 TaskStore。"),
    "tasks" to V5Binding("nav_running_tasks", "任务库、状态筛选与控制回执。"),
    "taskdetail" to V5Binding("nav_running_tasks", "先从任务库选择任务，再进入对应详情。"),
    "plan" to V5Binding("nav_running_tasks", "先选择任务，在详情查看计划与检查点。"),
    "queue" to V5Binding("nav_running_tasks", "查看真实任务库；自动调度由现有执行器负责，无可视化调度编辑器。"),
    "review" to V5Binding("nav_running_tasks", "先选择任务，在详情使用现有审查、返工和集成操作。"),
    "approvals" to V5Binding("nav_pending_approval", "控制台待审批与历史；批准和拒绝沿用原审批门。"),
    "artifacts" to V5Binding("nav_results", "从任务和步骤聚合真实产物，无虚构成果。"),
    "projects" to V5Binding("nav_projects", "现有本地和 Git 托管项目列表。"),
    "projectdetail" to V5Binding("nav_projects", "先选择项目，再打开项目文件与详情。"),
    "files" to V5Binding("nav_projects", "先选择授权项目，再浏览真实目录。"),
    "editor" to V5Binding("nav_projects", "先选择项目文件；编辑、保存确认和写轮次沿用原逻辑。"),
    "diff" to V5Binding("nav_projects", "先编辑文件，保存确认中查看行级差异；不是全仓库 Git diff。"),
    "team" to V5Binding("nav_team", "现有成员与 Agent 管理，不包含独立团队权限后台。"),
    "agents" to V5Binding("nav_team", "现有 Agent 列表、添加、测试与派单。"),
    "agentdetail" to V5Binding("nav_team", "先选择 Agent，在现有列表打开其配置或会话。"),
    "channels" to V5Binding("nav_team_channel", "现有团队频道；消息发送不代替任务创建。"),
    "channel" to V5Binding("nav_team_channel", "现有配置频道会话；多频道选择器尚缺。"),
    "devices" to V5Binding("nav_devices", "设备列表、配对与能力测试；配对不代表在线。"),
    "devicedetail" to V5Binding("nav_devices", "先选择设备，沿用原配对和授权流程。"),
    "models" to V5Binding("nav_workbench", "设置中的真实模型与提供方管理。"),
    "connectmodel" to V5Binding("nav_tools", "设置中的连接模型表单；不含独立向导。"),
    "mcp" to V5Binding("nav_mcp_plugins", "真实 MCP 列表、配置和连接；工具执行保留审批。"),
    "connections" to V5Binding("nav_tools", "现有账号与模型连接设置。"),
    "dashboard" to V5Binding("nav_console", "现有全局控制台，数据来自任务、事件、设备与审批快照。"),
    "activity" to V5Binding("feature_run_logs", "控制台真实事件和持久化任务审计；不包含系统通知服务。"),
    "terminal" to V5Binding("feature_run_logs", "仅现有执行日志；交互终端待开发。"),
    "settings" to V5Binding("nav_workbench", "完整现有设置，保留身份、配对、模型、主题及高级配置。"),
    "extensions" to V5Binding("nav_mcp_plugins", "现有 MCP 与扩展声明目录；声明不代表插件已安装或可执行。"),
    "audit" to V5Binding("feature_run_logs", "现有任务事件审计；统一权限安全审计后台待开发。"),
  )
  fun gap(id: String): String = when (id) {
    "usage" -> "缺少统一用量、额度与成本账本；不显示估算数字。"
    "automation", "automaker", "triggers", "templates", "autoloop" -> "缺少完整规则服务、持久触发器、预算、幂等与唤醒账本。现有任务调度器不能替代它们。"
    "sync", "handoff", "recovery" -> "缺少跨端同步、冲突处理和耐久离线发件箱；离线不代表任务停止。"
    "voice" -> "附件能力仍在对话的加号菜单。语音识别与录音授权链路尚未接通。"
    "network", "routes" -> "已有设备直连和 LAN 传输；统一路径诊断与离线队列页面待开发。"
    "onboarding" -> "首次启动引导由现有持久化状态控制，不自动清除或重置引导记录。"
    else -> "当前没有完整可调用页面。相关内部能力不等于此产品功能已接通；具体缺口见 70 路由审计表。"
  }
}
class AppFlagshipCapabilities(private val context: Context) : FlagshipCapabilityProvider {
  override fun status(routeId: String) = FeatureStatus(
    if (routeId in V5Bindings.all) FeatureState.IMPLEMENTED_UNVERIFIED else FeatureState.PLANNED)
  override fun openExistingFeature(routeId: String): Boolean {
    if (routeId !in V5Bindings.all) return false
    context.startActivity(Intent(context, MainActivity::class.java)
      .putExtra(EXTRA_V5_ROUTE, routeId)
      .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    return true
  }
}
const val EXTRA_V5_ROUTE = "wherevercode.agent.extra.V5_ROUTE"
