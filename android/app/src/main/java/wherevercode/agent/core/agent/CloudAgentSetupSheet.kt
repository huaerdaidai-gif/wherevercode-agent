package wherevercode.agent.core.agent

/**
* 云端 Agent 的设置说明（V5，2026-11-11）。
*
* 老项目“添加云端 Agent”对话框的各 App 设置说明（照抄写法，
* 不抄凭证值）：身份验证一律选“无”；连接地址填进对方 App 的
* 自定义连接器，钥匙放进 Authorization: Bearer 请求头。
*
* 纯函数：输入记录 + 钥匙值（调用方从本机 Keystore 现取，只活在
* 本次展示里）+ 端点地址，输出整份说明文本。本对象不读存储、
* 不发网络；钥匙值绝不进日志（调用方展示完即丢）。
*/
object CloudAgentSetupSheet {

/**
* 整份设置说明。钥匙值只在 [mcpKey] 里出现一次（展示给用户去
* 对方 App 里填）；端点地址为空时如实写缺口（见
* [CloudAgentMcpEndpoint.gapNote]），不编地址。
*/
fun build(agent: AgentRecord, mcpKey: String, endpointUrl: String?): String {
val out = StringBuilder()
out.append("云端 Agent 设置说明\n")
out.append("角色：${CloudAgent.rolesLabel(agent.cloudRoles)}；")
out.append(if (agent.canDispatch) "允许它派活：开（也能给其他 Agent 派活）。\n" else "允许它派活：关（只能领活、读、交结果）。\n")
agent.cloudHomeDeviceId?.let {
out.append("在哪干活：归到设备 $it 下（只做分组，不改变权限）。\n")
}?: out.append("在哪干活：云端。\n")
out.append("\n连接地址：\n")
if (endpointUrl.isNullOrBlank()) {
out.append("尚未开通。${CloudAgentMcpEndpoint.gapNote()}\n")
} else {
out.append("$endpointUrl\n")
}
out.append("\n钥匙（只存本机 Keystore，请去对方 App 里填，不要转发给别人）：\n")
out.append("Authorization: Bearer $mcpKey\n")
out.append("\n各 App 设置：\n")
out.append("\n— Grok —\n")
out.append("身份验证选“无”；把连接地址填进 Grok 的自定义连接器，钥匙放进 Authorization: Bearer 请求头。\n")
out.append("\n— ChatGPT —\n")
out.append("身份验证选“无”；ChatGPT 只支持每天定时：建多个每天定时任务，覆盖想让它来查的时间（例如每 2 小时一个）。\n")
out.append("\n— Manus —\n")
out.append("身份验证选“无”；可填请求头 Authorization: Bearer 钥匙，另加 User-Agent: WhereverCode-CloudAgent/1.0。\n")
out.append("\n— Muse —\n")
out.append("身份验证选“无”；在 Muse 的连接器设置里填连接地址，钥匙放进 Authorization: Bearer 请求头。\n")
out.append("\n— 其他 —\n")
out.append("身份验证选“无”；把连接地址填进该 App 的自定义连接器，钥匙放进 Authorization: Bearer 请求头。\n")
out.append("\n权限提醒：它要改哪个文件就会读到那个文件的内容；“允许它派活”关掉后只能领活、读、交结果。\n")
return out.toString()
}
}
