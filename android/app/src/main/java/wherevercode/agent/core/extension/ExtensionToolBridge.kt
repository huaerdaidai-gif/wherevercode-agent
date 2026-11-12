package wherevercode.agent.core.extension

import wherevercode.agent.brain.Action
import wherevercode.agent.brain.ToolContext
import wherevercode.agent.brain.ToolDef
import wherevercode.agent.brain.ToolOutcome
import wherevercode.agent.core.extension.mcp.McpToolSource
import wherevercode.agent.core.extension.plugin.PluginRegistry

/**
 * 扩展工具桥：插件 + MCP 两路工具汇成一个 [ExtensionToolProvider]。
 *
 * 名字空间（互不冲突，进大脑前已加前缀）：
 * - 插件：plugin_<pluginId>_<tool>（如 plugin_local_utils_calc）
 * - MCP：mcp_<serverId>_<tool>（如 mcp_hand_mcp_stdio_list）
 *
 * 执行路由按前缀分流；两路都不认的名字报未知工具（validate 层），
 * 绝不静默吞掉。MCP 工具未连接时如实报 mcp_not_connected，不伪装。
 */
class ExtensionToolBridge(
    private val plugins: PluginRegistry,
    private val mcp: McpToolSource,
) : ExtensionToolProvider {

    override fun toolNames(): Set<String> = plugins.toolNames() + mcp.toolDefs().map { it.name }.toSet()

    override fun toolDefs(): List<ToolDef> = plugins.toolDefs() + mcp.toolDefs()

    override fun isReadOnly(toolName: String): Boolean =
        when {
            toolName.startsWith("plugin_") -> plugins.isReadOnly(toolName)
            toolName.startsWith("mcp_") -> mcp.isReadOnly(toolName)
            else -> false
        }

    override fun needsApproval(toolName: String): Boolean = !isReadOnly(toolName)

    override fun validate(action: Action): String? {
        return when {
            action.tool.startsWith("plugin_") -> plugins.validate(action.tool, action.args)
            action.tool.startsWith("mcp_") -> null // 参数形状由 MCP 服务端按 inputSchema 校验
            else -> "未知扩展工具：${action.tool}"
        }
    }

    override suspend fun execute(action: Action, context: ToolContext, stopped: () -> Boolean): ToolOutcome {
        if (stopped()) {
            return ToolOutcome(ok = false, summary = "已停止，未执行", errorCode = "turn_stopped")
        }
        return when {
            action.tool.startsWith("plugin_") -> {
                plugins.execute(action.tool, action.args)
                    ?: ToolOutcome(ok = false, summary = "未知插件工具：${action.tool}", errorCode = "invalid_action")
            }
            action.tool.startsWith("mcp_") -> mcp.execute(action.tool, action.args)
            else -> ToolOutcome(ok = false, summary = "未知扩展工具：${action.tool}", errorCode = "invalid_action")
        }
    }
}
