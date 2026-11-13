package wherevercode.agent.core.extension.mcp

import wherevercode.agent.brain.ToolDef
import wherevercode.agent.brain.ToolOutcome

/**
 * 多个 [McpToolSource] 的联合体（2026-11-13，MCP 能力补齐）。
 *
 * 背景：[ExtensionToolBridge] 只接一个 McpToolSource（通用管理器）。
 * 平台 MCP 端点（[wherevercode.agent.core.agent.PlatformMcpClient]）
 * 也是一个 McpToolSource，但它的凭证来自平台 Agent 设置、不在
 * 扩展注册表里，不能塞进通用管理器。联合体让两路（以及以后的第
 * 三路）汇成一个源，接线点只改一行（见类注释末尾），大脑工具链
 * 本身不用动——与现有单兵工具调用链兼容。
 *
 * 路由规则（名字空间互不冲突是前提：通用管理器产
 * `mcp_<serverId>_<tool>`，平台客户端产 `mcp_platform_<tool>`）：
 * - toolDefs：各路并起来（顺序即传入顺序）；
 * - isReadOnly / needsApproval：名字属于哪路就按那路的判定；哪路
 *   都不认的名字默认需要审批（安全默认，不自动执行）；
 * - execute：名字属于哪路就由那路执行；哪路都不认时报未知工具，
 *   绝不静默吞掉、不伪装执行。
 *
 * 接线（BrainBootstrap，协调员接线时改一行）：
 * `ExtensionToolBridge(pluginRegistry, CompositeMcpToolSource(mcpServerManager, platformMcpClient))`
 * ——platformMcpClient 由调用方在平台凭证就绪后建出、连接成功后
 * 再传入；未连接时它的 toolDefs 为空，大脑看不到平台工具，不伪装。
 */
class CompositeMcpToolSource(
    private val sources: List<McpToolSource>,
) : McpToolSource {

    constructor(vararg sources: McpToolSource) : this(sources.toList())

    override fun toolDefs(): List<ToolDef> =
        sources.flatMap { source ->
            try {
                source.toolDefs()
            } catch (_: Exception) {
                emptyList()
            }
        }

    override fun isReadOnly(toolName: String): Boolean {
        for (source in sources) {
            if (owns(source, toolName)) return source.isReadOnly(toolName)
        }
        return false
    }

    override fun needsApproval(toolName: String): Boolean = !isReadOnly(toolName)

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolOutcome {
        for (source in sources) {
            if (owns(source, toolName)) return source.execute(toolName, args)
        }
        return ToolOutcome(ok = false, summary = "未知 MCP 工具：$toolName", errorCode = "invalid_action")
    }

    /** 该名字是不是这路源的工具（按它当下 toolDefs 的名字集合判定）。 */
    private fun owns(source: McpToolSource, toolName: String): Boolean {
        return try {
            source.toolDefs().any { it.name == toolName }
        } catch (_: Exception) {
            false
        }
    }
}
