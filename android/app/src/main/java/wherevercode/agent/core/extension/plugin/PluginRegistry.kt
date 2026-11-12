package wherevercode.agent.core.extension.plugin

import wherevercode.agent.brain.ToolDef
import wherevercode.agent.brain.ToolOutcome

/**
 * App 内插件（V5 MCP + 插件，第一版，2026-11-11）。
 *
 * 插件 = 一段 App 内代码 + 它提供的工具清单（manifest 在代码里，
 * 不是外来文件——本版不加载外部插件代码，只注册随 App 一起编译
 * 进来的插件；加载外部代码是下一步，且须过安全评审）。
 *
 * 插件工具进大脑时加前缀 `plugin_<pluginId>_<tool>`，与 MCP 工具
 * 的 `mcp_<serverId>_<tool>` 同形、同入口（见 ExtensionToolBridge）。
 */
data class PluginTool(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any?> = emptyMap(),
    /** 只读工具自动执行、可缓存；否则走审批。 */
    val readOnly: Boolean,
)

interface ExtensionPlugin {
    val id: String
    val displayName: String
    val version: String
    val tools: List<PluginTool>
    fun execute(toolName: String, args: Map<String, Any?>): ToolOutcome
}

class PluginRegistry(
    /** 插件是否启用：默认全启用；生产传入注册表的启用开关。 */
    private val isEnabled: (String) -> Boolean = { true },
) {
    private val plugins = LinkedHashMap<String, ExtensionPlugin>()

    fun register(plugin: ExtensionPlugin) {
        plugins[plugin.id] = plugin
    }

    fun all(): List<ExtensionPlugin> = plugins.values.toList()

    fun enabled(): List<ExtensionPlugin> = plugins.values.filter { isEnabled(it.id) }

    fun get(id: String): ExtensionPlugin? = plugins[id]

    /** 已启用插件的全部工具（大脑看到的形态）。 */
    fun toolDefs(): List<ToolDef> =
        enabled().flatMap { plugin ->
            plugin.tools.map { tool ->
                ToolDef(
                    name = toolNameFor(plugin.id, tool.name),
                    description = "插件 ${plugin.displayName} · ${tool.description}",
                    inputSchema = tool.inputSchema,
                )
            }
        }

    fun toolNames(): Set<String> = toolDefs().map { it.name }.toSet()

    fun isReadOnly(toolName: String): Boolean {
        val (plugin, tool) = parseToolName(toolName) ?: return false
        return plugin?.tools?.firstOrNull { it.name == tool }?.readOnly ?: false
    }

    fun needsApproval(toolName: String): Boolean = !isReadOnly(toolName)

    /**
     * 执行一个插件工具。返回 null = 不是本注册表的工具（调用方继续
     * 找别的来源或报未知工具）。
     */
    fun execute(toolName: String, args: Map<String, Any?>): ToolOutcome? {
        val (plugin, tool) = parseToolName(toolName) ?: return null
        if (plugin == null) return null
        if (!isEnabled(plugin.id)) {
            return ToolOutcome(
                ok = false,
                summary = "插件未启用：${plugin.displayName}",
                errorCode = "plugin_disabled",
            )
        }
        return plugin.execute(tool, args)
    }

    /** 必填参数校验（按 inputSchema.required；字符串须非空）。返回错误文案，null = 通过。 */
    fun validate(toolName: String, args: Map<String, Any?>): String? {
        val (plugin, tool) = parseToolName(toolName) ?: return "未知插件工具：$toolName"
        if (plugin == null) return "未知插件工具：$toolName"
        val def = plugin.tools.firstOrNull { it.name == tool } ?: return "未知插件工具：$toolName"
        val required = (def.inputSchema["required"] as? List<*>)
            ?.mapNotNull { it as? String } ?: emptyList()
        for (key in required) {
            val value = args[key]
            if (value == null || (value is String && value.isBlank())) {
                return "缺少参数 $key（须为非空字符串）"
            }
        }
        return null
    }

    private fun parseToolName(prefixed: String): Pair<ExtensionPlugin?, String>? {
        if (!prefixed.startsWith("plugin_")) return null
        val rest = prefixed.removePrefix("plugin_")
        // 插件 id 已知：按最长 id 前缀匹配（id 里可能有下划线）。
        val candidates = plugins.keys.map { sanitizeId(it) }.sortedByDescending { it.length }
        for (pluginId in candidates) {
            if (rest.startsWith(pluginId + "_")) {
                return plugins.values.firstOrNull { sanitizeId(it.id) == pluginId } to
                    rest.removePrefix(pluginId + "_")
            }
        }
        val idx = rest.lastIndexOf('_')
        if (idx <= 0 || idx == rest.length - 1) return null
        val pluginId = rest.substring(0, idx)
        val tool = rest.substring(idx + 1)
        return plugins.values.firstOrNull { sanitizeId(it.id) == pluginId } to tool
    }

    companion object {
        fun sanitizeId(raw: String): String =
            raw.map { c -> if (c.isLetterOrDigit() || c == '_') c else '_' }.joinToString("")

        fun toolNameFor(pluginId: String, toolName: String): String =
            "plugin_${sanitizeId(pluginId)}_${sanitizeId(toolName)}"
    }
}
