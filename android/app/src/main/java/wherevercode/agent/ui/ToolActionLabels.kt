package wherevercode.agent.ui

/**
 * 工具中文动作标签（V5 C2-3-R1b 流式复用：老项目 agent_runners.py:61
 * _tool_event——工具名译成中文动作）。
 *
 * 老映射（逐段移植，只取翻译规则，不取传输层）：Read→读取、
 * Edit/Write→修改、Bash→运行、Grep→搜索、Glob→查找文件。
 * 新 App 注册表工具同规则：read_file→读取文件、write_file→修改文件、
 * run_command→运行命令、list_files→列出文件。
 * 未列出的工具名（dispatch_task、ask_user 及未来工具）一律回落为
 * 工具名本身，绝不编造翻译。
 */
object ToolActionLabels {

    private val LABELS = mapOf(
        "Read" to "读取",
        "Edit" to "修改",
        "Write" to "修改",
        "Bash" to "运行",
        "Grep" to "搜索",
        "Glob" to "查找文件",
        "read_file" to "读取文件",
        "write_file" to "修改文件",
        "run_command" to "运行命令",
        "list_files" to "列出文件",
    )

    fun labelFor(toolName: String): String = LABELS[toolName] ?: toolName
}
