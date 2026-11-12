package wherevercode.agent.core.task

/**
 * 任务库展示层归并（2026-11-11，任务 692638e2 教训）。
 *
 * 同一逻辑任务在任务库占两行：派发轮次行由
 * [wherevercode.agent.ui.TurnTaskRecorder.beginTurn] 建
 * （sessionId = 会话 id，标题 = 用户原话前 40 字）；执行任务行由
 * 大脑 dispatch 工具建（sessionId = null，assignedAgentId = 所选
 * Agent）。写时链接需要把轮次上下文穿过 BrainEngine → 工具执行，
 * 改动面大，本分支不做写时链接；但数据里已有可认出的链接：轮次行
 * 的 result 是大脑回复原文，含“任务 ID：692638e2-…-…”；执行行的
 * taskId 就是该 UUID。[TaskRecord.parentTaskId] 另有一条结构链接
 * （重试副本指回原任务，见 [TaskRetry]）。
 *
 * 本函数是纯展示层归并，不改任何任务行：
 * - 链接判定：① parentTaskId == 另一行 taskId → 该行是其重试子行；
 *   ② 扫描某行 result（与 description）里的 UUID，若等于另一行
 *   taskId，且该行 sessionId != null、被提到的行 sessionId == null
 *   → 该行是那一行的派发轮次。
 *   ③ 历史重试启发式：旧代码造的重试副本既无 parentTaskId，result
 *   里也没有指向原任务的 UUID（如 2026-11-11 22:46 的重试行）。
 *   title 与 description 完全相同、且后一行创建时间不早于前一行落
 *   终态的时间（finishedAt，缺失时退 updatedAt；秒级时间戳下同一
 *   秒也算）→ 后一行视为前一行的重试子行。边界：用户有意两次派发
 *   同一目标文本时也会被归并——接受：重试链一眼看出是一件事的价值
 *   大于该边界 case；两行仍以子行形式可见，不丢数据。
 * - 主行 = 既不是别人派发轮次、也不是别人重试子行的行；每组挂
 *   dispatchTurn（若有）与 retries（按 createdAt 升序，含重试的重试）。
 * - 无主可挂的孤行（派发轮次找不到执行任务、重试找不到原任务）
 *   自成一组。
 * - 组按主行 updatedAt 倒序（与任务库现有列表排序一致）。
 */
data class LibraryTaskGroup(
    val primary: TaskRecord,
    val dispatchTurn: TaskRecord?,
    val retries: List<TaskRecord>,
)

object TaskLibraryGrouping {
    private val UUID_REGEX: Regex =
        Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    fun groupLibraryTasks(tasks: List<TaskRecord>): List<LibraryTaskGroup> {
        if (tasks.isEmpty()) return emptyList()
        val byId = tasks.associateBy { it.taskId }
        // 行 taskId → 它所附属的主行 taskId（派发轮次 / 重试子行）。
        val attachedTo = mutableMapOf<String, String>()
        for (task in tasks) {
            // ① 重试子行：parentTaskId 指向另一行。
            val parentId = task.parentTaskId
            if (parentId != null && parentId != task.taskId && byId.containsKey(parentId)) {
                attachedTo[task.taskId] = parentId
            }
        }
        for (task in tasks) {
            if (task.taskId in attachedTo) continue
            // ② 派发轮次：行带 sessionId，且 result/description 里出现
            // 另一行（sessionId 为空的执行行）的 taskId（UUID）。
            // 注意：被提到的行必须真实存在于列表里——byId[id] 为 null
            // 时 `?.sessionId == null` 恒为真，会把轮次挂到一个不存在
            // 的根上（孤轮次自成一组才是对的），所以用 let 显式判存在。
            if (task.sessionId == null) continue
            val mentioned = UUID_REGEX.findAll(
                task.result.orEmpty() + "\n" + task.description.orEmpty(),
            ).map { it.value }
                .firstOrNull { id ->
                    id != task.taskId && byId[id]?.let { it.sessionId == null } == true
                } ?: continue
            attachedTo[task.taskId] = mentioned
        }
        // ③ 历史重试启发式（见文件头）：按创建时间顺序处理尚未归属
        // 的行；父行取同 title/description、已落终态、且落终态时间
        // 最晚的那一行（链式重试时父行本身也可能是他人的重试子行，
        // 根由 rootOf 统一解析）。时间用 <= 而不用 <：时间戳是秒级，
        // 同一秒内点重试很常见，落终态写库与副本创建在同一秒里仍算
        // “之后”。
        val terminalTime = { task: TaskRecord -> task.finishedAt ?: task.updatedAt }
        for (child in tasks.filter { it.taskId !in attachedTo }.sortedBy { it.createdAt }) {
            val parent = tasks
                .filter { candidate ->
                    candidate.taskId != child.taskId &&
                        candidate.title == child.title &&
                        candidate.description.orEmpty() == child.description.orEmpty() &&
                        TaskStates.isTerminal(candidate.state) &&
                        terminalTime(candidate) <= child.createdAt
                }
                .maxByOrNull { terminalTime(it) }
                ?: continue
            attachedTo[child.taskId] = parent.taskId
        }
        // 沿附属链找到组根（防脏数据成环：visited 兜底）。
        fun rootOf(taskId: String): String {
            var current = taskId
            val seen = mutableSetOf<String>()
            while (true) {
                val parent = attachedTo[current] ?: return current
                if (!seen.add(current)) return current
                current = parent
            }
        }
        val membersByRoot = mutableMapOf<String, MutableList<TaskRecord>>()
        for (task in tasks) {
            membersByRoot.getOrPut(rootOf(task.taskId)) { mutableListOf() } += task
        }
        return membersByRoot.map { (rootId, members) ->
            val primary = byId.getValue(rootId)
            val attached = members.filter { it.taskId != rootId }
            LibraryTaskGroup(
                primary = primary,
                // 派发轮次行自带 sessionId；执行行与重试副本 sessionId 均为 null。
                dispatchTurn = attached
                    .filter { it.sessionId != null }
                    .minByOrNull { it.createdAt },
                retries = attached
                    .filter { it.sessionId == null }
                    .sortedBy { it.createdAt },
            )
        }.sortedByDescending { it.primary.updatedAt }
    }
}
