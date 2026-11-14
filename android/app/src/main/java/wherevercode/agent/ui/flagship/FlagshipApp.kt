package wherevercode.agent.ui.flagship

/** Internal feature index only. Navigation belongs to MainActivity and EntryRegistry. */
data class FlagshipRoute(val id:String,val title:String,val group:String,val p0:Boolean)
object FlagshipRoutes {
  val all = listOf(
    FlagshipRoute("chat","对话首页","对话",true),
    FlagshipRoute("conversation","执行中的对话","对话",true),
    FlagshipRoute("history","历史会话","对话",true),
    FlagshipRoute("search","全局搜索","对话",false),
    FlagshipRoute("compose","新建任务","任务",true),
    FlagshipRoute("tasks","任务中心","任务",true),
    FlagshipRoute("taskdetail","任务详情","任务",true),
    FlagshipRoute("plan","计划与检查点","任务",false),
    FlagshipRoute("queue","队列与调度","任务",false),
    FlagshipRoute("approvals","审批中心","任务",true),
    FlagshipRoute("review","代码审查","任务",false),
    FlagshipRoute("artifacts","交付成果","任务",true),
    FlagshipRoute("projects","项目列表","开发",true),
    FlagshipRoute("projectdetail","项目概览","开发",true),
    FlagshipRoute("files","文件浏览器","开发",true),
    FlagshipRoute("editor","代码编辑器","开发",true),
    FlagshipRoute("diff","变更对比","开发",true),
    FlagshipRoute("terminal","终端与日志","开发",true),
    FlagshipRoute("git","Git 与工作树","开发",false),
    FlagshipRoute("preview","运行预览","开发",false),
    FlagshipRoute("team","团队空间","协作",false),
    FlagshipRoute("channels","团队频道","协作",false),
    FlagshipRoute("channel","频道会话","协作",false),
    FlagshipRoute("agents","Agent 列表","协作",true),
    FlagshipRoute("agentdetail","Agent 详情","协作",true),
    FlagshipRoute("delegate","协作编排","协作",false),
    FlagshipRoute("devices","设备列表","连接",true),
    FlagshipRoute("devicedetail","设备详情","连接",false),
    FlagshipRoute("models","模型与提供方","连接",true),
    FlagshipRoute("mcp","MCP 与插件","连接",false),
    FlagshipRoute("skills","技能与自动化","连接",false),
    FlagshipRoute("connections","账号与连接","连接",false),
    FlagshipRoute("dashboard","全局工作台","系统",true),
    FlagshipRoute("activity","事件与通知","系统",false),
    FlagshipRoute("settings","设置","系统",true),
    FlagshipRoute("onboarding","首次使用","系统",true),
    FlagshipRoute("connectmodel","连接模型向导","连接",true),
    FlagshipRoute("modelroute","智能模型路由","连接",false),
    FlagshipRoute("agentimport","导入现有 Agent","连接",false),
    FlagshipRoute("agentresume","Agent 会话续接","协作",false),
    FlagshipRoute("autoloop","Auto-loop 自主循环","自动化",false),
    FlagshipRoute("loopguard","循环安全护栏","自动化",false),
    FlagshipRoute("memory","长期记忆","智能",false),
    FlagshipRoute("skillsmarket","技能市场","智能",false),
    FlagshipRoute("skillbuilder","技能自生成","智能",false),
    FlagshipRoute("context","上下文管理","智能",false),
    FlagshipRoute("research","深度研究","智能",false),
    FlagshipRoute("browser","浏览器工作台","开发",false),
    FlagshipRoute("testmatrix","测试矩阵","开发",false),
    FlagshipRoute("agentcompare","多 Agent 对比","协作",false),
    FlagshipRoute("orchestrator","可视化编排","协作",false),
    FlagshipRoute("handoff","跨设备接力","协作",false),
    FlagshipRoute("audit","权限与审计","系统",false),
    FlagshipRoute("notifications","消息中心","系统",false),
    FlagshipRoute("voice","语音与多模态","对话",false),
    FlagshipRoute("personas","Agent 个性与配置","智能",false),
    FlagshipRoute("usage","成本与额度","系统",false),
    FlagshipRoute("templates","工作流模板","自动化",false),
    FlagshipRoute("sync","跨端同步","系统",false),
    FlagshipRoute("recovery","恢复与回滚","系统",false),
    FlagshipRoute("automation","自动化中心","自动化",false),
    FlagshipRoute("automaker","创建自动化","自动化",false),
    FlagshipRoute("triggers","触发器与唤醒","自动化",false),
    FlagshipRoute("wakepolicy","唤醒与自治策略","自动化",false),
    FlagshipRoute("network","通信网络","连接",false),
    FlagshipRoute("routes","网络路径详情","连接",false),
    FlagshipRoute("extensions","扩展中心","扩展",false),
    FlagshipRoute("extensiondetail","插件权限与安装","扩展",false),
    FlagshipRoute("harness","Harness 运行时","扩展",false),
    FlagshipRoute("baseline","Claude 18 屏验收","系统",false),
  )
  fun find(id:String)=all.firstOrNull{it.id==id} ?: all.first()
}

enum class FeatureState { PLANNED, IMPLEMENTED_UNVERIFIED, READY_VERIFIED, DISCONNECTED, FAILED, UNSUPPORTED }
data class FeatureStatus(val state:FeatureState=FeatureState.PLANNED,val evidence:String?=null)
interface FlagshipCapabilityProvider {
  fun status(routeId:String):FeatureStatus
  /** Only call an action through a real app adapter; never simulate success. */
  fun openExistingFeature(routeId:String):Boolean
}
class PlannedCapabilities:FlagshipCapabilityProvider {
  override fun status(routeId:String)=FeatureStatus()
  override fun openExistingFeature(routeId:String)=false
}
