package wherevercode.agent.ui.flagship

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
private val Ink=Color(0xFF292825)
private val Warm=Color(0xFFFAF9F6)
private val Coral=Color(0xFFC87757)
private val Groups=listOf("对话","开发","任务","协作","连接","智能","自动化","扩展","系统")

@Composable fun FlagshipApp(provider:FlagshipCapabilityProvider=PlannedCapabilities()){
  var route by remember { mutableStateOf("chat") }
  var drawer by remember { mutableStateOf(false) }
  var search by remember { mutableStateOf("") }
  val page=FlagshipRoutes.find(route)
  MaterialTheme(colorScheme=lightColorScheme(primary=Coral,background=Warm,surface=Color.White,onSurface=Ink)){
    Column(Modifier.fillMaxSize().background(Warm)){
      Row(Modifier.fillMaxWidth().padding(horizontal=18.dp, vertical=14.dp),verticalAlignment=Alignment.CenterVertically){
        TextButton(onClick={drawer=!drawer}){Text("☰")}
        Text(page.title,Modifier.weight(1f),fontWeight=FontWeight.SemiBold)
        TextButton(onClick={route="dashboard";drawer=false}){Text("控制台")}
      }
      if(drawer){
        OutlinedTextField(search,{search=it},Modifier.fillMaxWidth().padding(horizontal=16.dp),label={Text("查找 70 个页面")})
        LazyColumn(Modifier.weight(1f).padding(horizontal=12.dp)){
          Groups.forEach { group ->
            val found=FlagshipRoutes.all.filter{it.group==group && (search.isBlank()||it.title.contains(search,true)||it.id.contains(search,true))}
            if(found.isNotEmpty()){
              item { Text(group,Modifier.padding(12.dp),color=Coral,fontWeight=FontWeight.Bold) }
              items(found){ p ->
                TextButton(onClick={route=p.id;drawer=false},modifier=Modifier.fillMaxWidth()){
                  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(p.title);Text(if(provider.status(p.id).state==FeatureState.READY_VERIFIED) "已验证" else "待开发",color=Color.Gray)}
                }
              }
            }
          }
        }
      } else {
        LazyColumn(Modifier.weight(1f).padding(horizontal=20.dp)){
          item {
            Spacer(Modifier.height(24.dp))
            Text(if(route=="chat") "今天想做些什么？" else page.title,style=MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            val status=provider.status(route)
            val verified=status.state==FeatureState.READY_VERIFIED && !status.evidence.isNullOrBlank()
            Text(if(verified) "已验证" else when(status.state){FeatureState.IMPLEMENTED_UNVERIFIED->"待验收";FeatureState.DISCONNECTED->"未连接";FeatureState.FAILED->"运行失败";FeatureState.UNSUPPORTED->"此平台暂不支持";else->"待开发"},color=Coral)
            Spacer(Modifier.height(18.dp))
            Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(18.dp)){
              Column(Modifier.fillMaxWidth().padding(20.dp)){
                Text("${page.group} · ${page.id}",color=Color.Gray)
                Spacer(Modifier.height(8.dp))
                Text(if(verified) "此功能已通过真机验证，可进入现有实现。" else "此页面已预留。接入真实能力并完成真机验证后才能标记可用。")
                Spacer(Modifier.height(12.dp))
                Button(enabled=verified,onClick={provider.openExistingFeature(route)}){Text("打开功能")}
              }
            }
            Spacer(Modifier.height(20.dp))
            when(route){
              "dashboard" -> DashboardPreview()
              "chat" -> Text("聊天发送必须接入现有模型会话服务；当前脚手架不伪造回复。",color=Color.Gray)
              "tasks","taskdetail" -> Text("任务状态、停止与暂停必须来自真实任务执行器。",color=Color.Gray)
              "editor","files","terminal","diff","git" -> Text("文件、代码、终端和 Git 均需要授权与真实工具适配器。",color=Color.Gray)
            }
          }
        }
        NavigationBar(containerColor=Color.White){
          listOf("chat" to "对话","projects" to "项目","tasks" to "任务","dashboard" to "控制台","settings" to "更多").forEach{(id,title)->
            NavigationBarItem(selected=route==id,onClick={route=id},icon={Text("•")},label={Text(title)})
          }
        }
      }
    }
  }
}
@Composable private fun DashboardPreview(){
  Text("运行总览",fontWeight=FontWeight.Bold)
  listOf("项目" to "未接入", "任务队列" to "未接入", "设备" to "未接入", "模型与 Agent" to "未接入", "审批与审计" to "未接入").forEach{(a,b)->
    Row(Modifier.fillMaxWidth().padding(vertical=12.dp),horizontalArrangement=Arrangement.SpaceBetween){Text(a);Text(b,color=Color.Gray)}
    Divider()
  }
}
