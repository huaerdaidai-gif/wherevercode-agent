package wherevercode.agent.core.extension

import wherevercode.agent.brain.Action
import wherevercode.agent.brain.ToolContext
import wherevercode.agent.brain.ToolDef
import wherevercode.agent.brain.ToolOutcome

/**
 * 扩展工具来源（ToolRegistry 的可选挂接点）。
 *
 * ToolRegistry 只认内置六工具；扩展工具（插件 + MCP）经本接口
 * 进入同一条工具调用链：toolsForCapabilities 列给大脑 →
 * BrainEngine 的 ToolCall → ToolRegistry.execute → 这里。
 * 扩展工具的审批与只读判定由来源自己声明，ToolRegistry 只负责
 * 走同一套“结构 → 权限 → 审批 → 执行 → 审计”流程。
 */
interface ExtensionToolProvider {
    fun toolNames(): Set<String>
    fun toolDefs(): List<ToolDef>
    fun isReadOnly(toolName: String): Boolean
    fun needsApproval(toolName: String): Boolean

    /** 结构校验：返回中文错误，null = 通过。只做参数形状检查，不执行。 */
    fun validate(action: Action): String?

    suspend fun execute(action: Action, context: ToolContext, stopped: () -> Boolean): ToolOutcome
}
