package wherevercode.agent.ui.flagship

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Native UI scaffold. All features are planned until a verified app adapter supplies evidence. */
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
  fun find(id:String)=all.firstOrNull{it.id==id} ?: error("Unknown route: $id")
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

/** Parent membership is copied exactly from the uploaded cross-platform contract. */
object V5Navigation {
  val parents = listOf("对话", "工作台", "控制台", "设置")
  val parentByRoute = mapOf(
    "chat" to "对话",
    "conversation" to "对话",
    "history" to "对话",
    "search" to "对话",
    "compose" to "工作台",
    "tasks" to "工作台",
    "taskdetail" to "工作台",
    "plan" to "工作台",
    "queue" to "工作台",
    "approvals" to "工作台",
    "review" to "工作台",
    "artifacts" to "工作台",
    "projects" to "工作台",
    "projectdetail" to "工作台",
    "files" to "工作台",
    "editor" to "工作台",
    "diff" to "工作台",
    "terminal" to "工作台",
    "git" to "工作台",
    "preview" to "工作台",
    "team" to "工作台",
    "channels" to "工作台",
    "channel" to "工作台",
    "agents" to "工作台",
    "agentdetail" to "工作台",
    "delegate" to "工作台",
    "devices" to "工作台",
    "devicedetail" to "工作台",
    "models" to "工作台",
    "mcp" to "工作台",
    "skills" to "工作台",
    "connections" to "工作台",
    "dashboard" to "控制台",
    "activity" to "控制台",
    "settings" to "控制台",
    "onboarding" to "控制台",
    "connectmodel" to "工作台",
    "modelroute" to "工作台",
    "agentimport" to "工作台",
    "agentresume" to "工作台",
    "autoloop" to "控制台",
    "loopguard" to "控制台",
    "memory" to "设置",
    "skillsmarket" to "设置",
    "skillbuilder" to "设置",
    "context" to "设置",
    "research" to "设置",
    "browser" to "工作台",
    "testmatrix" to "工作台",
    "agentcompare" to "工作台",
    "orchestrator" to "工作台",
    "handoff" to "工作台",
    "audit" to "控制台",
    "notifications" to "控制台",
    "voice" to "对话",
    "personas" to "设置",
    "usage" to "控制台",
    "templates" to "控制台",
    "sync" to "控制台",
    "recovery" to "控制台",
    "automation" to "控制台",
    "automaker" to "控制台",
    "triggers" to "控制台",
    "wakepolicy" to "控制台",
    "network" to "工作台",
    "routes" to "工作台",
    "extensions" to "设置",
    "extensiondetail" to "设置",
    "harness" to "设置",
    "baseline" to "控制台",
  )
  fun shortcuts(parent: String): List<String> = when (parent) {
    "控制台" -> listOf("tasks", "queue", "devices", "agents", "approvals", "review", "delegate", "mcp", "network", "harness", "connections")
    "设置" -> listOf("settings", "models", "connectmodel", "devices", "mcp", "network", "skills", "audit")
    else -> emptyList()
  }
  fun routes(parent: String) = FlagshipRoutes.all.filter { parentByRoute[it.id] == parent }
  fun search(parent: String, query: String) = routes(parent).filter {
    it.title.contains(query, true) || it.id.contains(query, true)
  }
}

@Composable internal fun V5Theme(content: @Composable () -> Unit) {
  val dark = isSystemInDarkTheme()
  MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme(
    primary = Color(0xFFAE6145), background = Color(0xFFFAF9F6),
    surface = Color(0xFFFAF9F6), onSurface = Color(0xFF292825)), content = content)
}

/** The primary drawer contains exactly four equally sized destinations. */
@Composable fun FourParentDrawer(onSelect: (String) -> Unit) {
  V5Theme {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(16.dp)) {
      Text("WhereverCode", Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.titleLarge)
      V5Navigation.parents.forEach { parent ->
        TextButton(onClick = { onSelect(parent) }, modifier = Modifier.fillMaxWidth().height(56.dp)
          .testTag("parent_$parent")) {
          Text(parent, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
          Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
    }
  }
}

/** Embedded in MainActivity; existing controllers retain their lifecycle and permissions. */
@Composable fun FlagshipHub(parent: String, onOpen: (String) -> Unit,
  onBack: () -> Unit, provider: FlagshipCapabilityProvider,
  registerBack: ((() -> Boolean) -> Unit) = {}, onUtility: (String) -> Unit = {}, initialRoute: String? = null) {
  var query by rememberSaveable(parent) { mutableStateOf("") }
  var selected by rememberSaveable(parent, initialRoute) { mutableStateOf(initialRoute) }
  SideEffect { registerBack { if (selected != null) { selected = null; true } else false } }
  V5Theme {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 18.dp)) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { if (selected != null) selected = null else onBack() }) { Text("返回") }
        Text(selected?.let { FlagshipRoutes.find(it).title } ?: parent,
          style = MaterialTheme.typography.titleLarge)
      }
      if (selected == null) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("route_search"),
          label = { Text("搜索功能或路由") }, singleLine = true)
        val found = V5Navigation.search(parent, query)
        LazyColumn(Modifier.weight(1f)) {
          if (query.isBlank() && parent == "设置") {
            item(key = "theme_help") {
              Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = { onUtility("nav_theme") }) { Text("界面与主题") }
                TextButton(onClick = { onUtility("nav_help") }) { Text("帮助") }
              }
            }
          }
          if (query.isBlank() && V5Navigation.shortcuts(parent).isNotEmpty()) {
            item(key = "shared_heading") { Text("常用管理", Modifier.padding(top = 24.dp, bottom = 8.dp)) }
            items(V5Navigation.shortcuts(parent), key = { "shortcut_$it" }) { id ->
              TextButton(onClick = { selected = id }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(FlagshipRoutes.find(id).title, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                Text("›")
              }
            }
          }
          if (found.isEmpty()) item { Text("没有匹配的功能", Modifier.padding(vertical = 24.dp)) }
          found.groupBy { it.group }.forEach { (group, entries) ->
            item(key = "group_$group") { Text(group, Modifier.padding(top = 24.dp, bottom = 8.dp),
              style = MaterialTheme.typography.labelLarge) }
            items(entries, key = { it.id }) { route ->
              val supported = provider.status(route.id).state == FeatureState.IMPLEMENTED_UNVERIFIED
              TextButton(onClick = { selected = route.id }, modifier = Modifier.fillMaxWidth()
                .heightIn(min = 56.dp).testTag("route_${route.id}")) {
                Text(route.title, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                Text(if (supported) "已有能力 · 待验收 ›" else "待开发 ›",
                  style = MaterialTheme.typography.labelSmall)
              }
            }
          }
        }
      } else {
        val id = selected!!
        val binding = V5Bindings.all[id]
        val implemented = binding != null
        SelectionContainer {
          Column(Modifier.padding(vertical = 24.dp)) {
            Text(if (implemented) "已有能力，尚未完成本轮设备验收" else "此功能待开发",
              style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            Text(binding?.description ?: V5Bindings.gap(id))
            Spacer(Modifier.height(16.dp))
            Text("路由：$id", style = MaterialTheme.typography.bodySmall)
          }
        }
        if (implemented) Button(onClick = { onOpen(id) }, Modifier.testTag("open_$id")) { Text("进入已有功能") }
      }
    }
  }
}

/** Compatibility entry, sharing the same hierarchy; never claims verified runtime data. */
@Composable fun FlagshipApp(provider: FlagshipCapabilityProvider = PlannedCapabilities()) {
  var parent by rememberSaveable { mutableStateOf<String?>(null) }
  if (parent == null) FourParentDrawer { parent = it }
  else FlagshipHub(parent!!, { provider.openExistingFeature(it) }, { parent = null }, provider)
}
