package wherevercode.agent.core.platform

/**
 * 任务派工的内存状态容器（spec §1）。与本地频道账本同一持久化
 * 风格：状态全在内存、变更后整表快照落盘——快照读写在 W3 落地，
 * 本类先只持有数据集合与事件序号。
 *
 * 集合语义：
 * - tasks：task_id -> 任务（含 review/pack 子任务，parent_id 关联）
 * - events：全局账本（seq 单调递增），按 task_id 过滤读取
 * - deliveryPages：work 任务 id -> (page -> 页)；pack 子任务递的页
 *   也落在父 work 名下（基线口径）
 * - idempotency：键 "task_id|epoch|action|attempt_id" -> 首次结果
 *   的 JSON 文本（epoch 参与键，reopen 后旧 attempt 不再命中）
 * - statusReports：agent_id -> 额度报告（report_status 落点）
 */
class TaskDispatchStore(
    @Suppress("unused") private val clock: () -> Double,
) {
    val tasks = LinkedHashMap<String, TaskRecord>()
    val events = mutableListOf<TaskEventRecord>()
    val deliveryPages = LinkedHashMap<String, MutableMap<Int, DeliveryPageRecord>>()
    val idempotency = LinkedHashMap<String, String>()
    val statusReports = LinkedHashMap<String, AgentStatusRecord>()
    var eventSeq = 0L
}
