package wherevercode.agent.ui

import wherevercode.agent.core.agent.AgentRecord
import wherevercode.agent.core.task.TaskRecord

/**
 * 新建任务表单纯逻辑（核心功能推进第 3 项，2026-11-15；纯 Kotlin，可单测）。
 *
 * 与对话派单同一条创建路径（核实结论，2026-11-15）：
 * - 工厂：[ChatTaskDispatcher.createDispatchedTask] 构造分派任务行，
 *   写库走 [ChatTaskDispatcher.dispatch] → TaskStore.createTask
 *   （RoomTaskStore 校验初始状态迁移与 taskId 唯一，并写 CREATED 事件）。
 *   ChatViewModel 的两处生产调用都传 brainId = null，本表单同口径。
 * - 分派行不变量（两台执行器的认领前提）：state = queued、
 *   sessionId = null（带 sessionId 的是聊天轮次镜像行，两台泵都明确
 *   跳过）、taskId 为 UUID（不进 mcp- 保留命名空间）、
 *   assignedAgentId = memberId = 所选 Agent、version = 1。
 * - 认领方：LocalTaskRunner.pickNext 只认领未指派或指派给本地
 *   执行器的行（按 createdAt FIFO）；RemoteTaskPump 只认领指派给
 *   远端设备 Agent（生产口径：agentStore 记录 deviceId 非空）的行
 *   （按 createdAt FIFO，逐成员一个在途）。指派给其他 Agent 的行
 *   进 queued 等其认领——与 Agent 页「指派任务」弹窗的既有口径
 *   一致，不假装本地会代执行；目标不可达 / Agent 不存在时泵会标
 *   needs_attention 并写明原因，不静默。
 *
 * 字段取舍（只放创建路径真实消费的）：
 * - 标题 / 详细说明：落 TaskRecord.title / description，执行方以此
 *   为任务目标文本。
 * - 执行 Agent：落 assignedAgentId / memberId，决定认领方。
 * - 项目：落 projectId，派活文件夹按它解析（无绑定走回落）。
 * - 优先级不放：TaskRecord.priority 两台执行器都不读（纯 createdAt
 *   FIFO），放了就是假选项。
 * - 执行权限不放：创建路径没有该参数，权限由运行时的审批闸门管，
 *   不是建单时能选的东西。
 */
object CreateTaskForm {

    /** 表单收集到的原始值：标题 / 说明为输入原文（未 trim），agent 为选中的真实记录（未选为 null），projectId 为选中项目 id（不绑定为 null）。 */
    data class Input(
        val title: String,
        val description: String,
        val agent: AgentRecord?,
        val projectId: String?,
    )

    /**
     * 可选执行 Agent 的口径：agentStore 全量记录里已启用且可派单的。
     * AgentStore 语义——enabled = false 的 Agent 不接派活（热插拔
     * 开关），dispatchable = false 的 Agent 只对话不接任务；两者都
     * 不进本列表，不编造选项。列表为空时界面走空态，引导先去
     * 添加 Agent。
     */
    fun dispatchableAgents(agents: List<AgentRecord>): List<AgentRecord> =
        agents.filter { it.enabled && it.dispatchable }

    /** 校验：返回错误文案（界面原样显示）；null 表示可提交。 */
    fun validate(input: Input): String? = when {
        input.title.isBlank() -> "任务标题不能为空。"
        input.agent == null -> "请选择执行 Agent。"
        else -> null
    }

    /**
     * 表单值 → 分派任务行。构造走与对话派单同一个工厂
     * [ChatTaskDispatcher.createDispatchedTask]，保证分派行不变量
     * 与聊天派单逐字段一致（见类头）。工厂按 prompt 派生标题的
     * 口径（take(40)）面向聊天文本，不适用于独立表单的显式标题
     * 字段，故最后以表单原值覆盖 title / description：
     * title = trim 后的标题；description = trim 后的说明，说明
     * 为空时退标题——与聊天派单 description = 指令原文同义，
     * 保证执行方总能拿到完整目标文本。
     *
     * 调用前须先过 [validate]；未过校验的输入在此直接抛错，
     * 不静默兜底造行。
     */
    fun buildRecord(
        input: Input,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): TaskRecord {
        val error = validate(input)
        require(error == null) { error ?: "表单未过校验" }
        val agent = input.agent!!
        val title = input.title.trim()
        val description = input.description.trim().ifBlank { title }
        val base = ChatTaskDispatcher.createDispatchedTask(
            agent = agent,
            prompt = description,
            brainId = null,
            projectId = input.projectId,
            nowSeconds = nowSeconds,
        )
        return base.copy(title = title, description = description)
    }

    /**
     * 选中 Agent 后任务的执行去向（与 Agent 页「指派任务」弹窗
     * 同一口径）：远端设备 Agent（deviceId 非空）由 RemoteTaskPump
     * 认领发到设备执行；其余 Agent 进 queued 等其认领，本地
     * 执行器不代执行。
     */
    fun executionHint(agent: AgentRecord): String =
        if (agent.deviceId != null) {
            "任务进入 queued，远端泵会发到 ${agent.displayName} 所在设备执行；本地执行器不代执行。"
        } else {
            "任务进入 queued 等 ${agent.displayName} 认领；本地执行器不代执行。"
        }
}
