package wherevercode.agent.core.task

import org.json.JSONArray
import org.json.JSONObject

/**
 * 任务拆分器（2026-11-11，大脑总管自动拆分）：纯函数，零 I/O。
 *
 * 把一个开发任务按**文件/模块边界**拆成若干互不冲突的子任务：
 * - 分组键是文件的**直接父目录**（模块边界的最小单位；子任务
 *   之间不共享目录，也就不共享文件）。
 * - 同一个文件绝不分给两个人：分组由构造保证互不相交，[decompose]
 *   返回前再验一遍——验出重叠即当拆分失败，不带病拆分。
 * - 拆不开的诚实报 [DecompositionProposal.Unsplittable]，不硬拆：
 *   只有一个文件、全部文件在同一目录、没有文件清单可供分组，
 *   都是“单写手”的合法结论，不是错误。
 * - 子任务数上限 [MAX_SUBTASKS]（P3 只读扇出 ≤5 的同数；并行写
 *   手再多也管不过来）。父目录多于上限时合并最小的两组，直到
 *   够数——合并只扩大文件范围，不制造重叠。
 *
 * 拆分只定**文件范围**，不定执行顺序：子任务之间没有依赖关系，
 * 可并行认领（P0 认领/租约/幂等各走各的）；子任务的执行隔离靠
 * [derivedPlan]——每个子任务拿到的派生 plan 把**其他子任务的
 * 文件全写进 filesOutOfScope**，机械范围检查（P2）会在子任务
 * 越界改别人的文件时判 FAILED，隔离是门禁，不是约定。
 */
data class SubtaskSpec(
    /** 子任务序号（0 起；合并分支名与任务标题由此取）。 */
    val index: Int,
    /** 模块标签：父目录（根目录文件为“项目根目录”）。 */
    val moduleLabel: String,
    /** 子任务目标：父 plan 目标 + 模块范围。 */
    val objective: String,
    /** 该子任务独占的文件清单（已排序、去重，与其他子任务无交集）。 */
    val files: List<String>,
)

sealed class DecompositionProposal {
    /** 可拆：子任务清单（≥2 个，互不相交）。 */
    data class Splittable(val subtasks: List<SubtaskSpec>) : DecompositionProposal()

    /** 拆不开：原因写明，调用方如实报“单写手”，不硬拆。 */
    data class Unsplittable(val reason: String) : DecompositionProposal()
}

object TaskDecomposer {
    /** 并行子任务上限（P3 只读扇出 ≤5 的同数）。 */
    const val MAX_SUBTASKS: Int = 5

    /** 拆分方案 JSON 版本（与 TaskPacket / PlanArtifact 同款：未知版本拒绝）。 */
    const val PROPOSAL_VERSION: Int = 1

    /**
     * 按 plan 拆分。
     *
     * 文件范围取 plan.filesInScope（非空时）；plan 不限定范围时取
     * [availableFiles]（项目文件清单，调用方供）；两者都没有即拆不开。
     */
    fun decompose(
        plan: PlanArtifact,
        availableFiles: List<String> = emptyList(),
    ): DecompositionProposal {
        val scope = normalizeFiles(plan.filesInScope.ifEmpty { availableFiles })
        if (scope.isEmpty()) {
            return DecompositionProposal.Unsplittable(
                "没有文件清单：plan 未限定文件范围，也没有项目文件清单，无法按模块拆分，单写手",
            )
        }
        val contradictions = scope.filter { it in plan.filesOutOfScope }
        if (contradictions.isNotEmpty()) {
            return DecompositionProposal.Unsplittable(
                "plan 自相矛盾：文件 ${contradictions.take(3).joinToString("、")} 既在改动范围又在禁改清单里，先修 plan",
            )
        }
        if (scope.size == 1) {
            return DecompositionProposal.Unsplittable(
                "只有一个文件 ${scope.single()}，拆不开，单写手",
            )
        }
        val grouped = scope.groupBy { parentDir(it) }
        if (grouped.size == 1) {
            val dir = grouped.keys.single()
            return DecompositionProposal.Unsplittable(
                "全部 ${scope.size} 个文件都在同一目录${if (dir.isEmpty()) "（项目根目录）" else " $dir"}下，没有模块边界可拆，单写手",
            )
        }
        // 父目录多于上限：反复合并最小的两组，直到够数。
        val groups = grouped.entries
            .map { (dir, files) -> dir to files.sorted() }
            .toMutableList()
        while (groups.size > MAX_SUBTASKS) {
            groups.sortBy { it.second.size }
            val smallest = groups.removeAt(0)
            val second = groups.removeAt(0)
            val mergedLabel = commonDir(smallest.first, second.first) ?: "混合模块"
            groups += mergedLabel to (smallest.second + second.second).sorted()
        }
        val subtasks = groups
            .sortedWith(compareBy({ it.first }, { it.second.firstOrNull() }))
            .mapIndexed { index, (dir, files) ->
                val label = if (dir.isEmpty()) "项目根目录" else dir
                SubtaskSpec(
                    index = index,
                    moduleLabel = label,
                    objective = "${plan.objective}（模块：$label，共 ${files.size} 个文件）",
                    files = files,
                )
            }
        // 互不相交是构造保证，这里再验一遍：验出重叠即拆分失败。
        val seen = HashSet<String>()
        for (spec in subtasks) {
            for (file in spec.files) {
                if (!seen.add(file)) {
                    return DecompositionProposal.Unsplittable(
                        "拆分失败：文件 $file 被分给了两个子任务，不带病拆分",
                    )
                }
            }
        }
        return DecompositionProposal.Splittable(subtasks)
    }

    /**
     * 子任务的派生 plan：文件范围只含自己的文件；**其他子任务的
     * 文件全写进 filesOutOfScope**，机械范围检查据此拦越界改动。
     * 步骤/验收标准/测试命令照抄父 plan（子任务的编码者看到完整
     * plan，但只能碰自己的文件）。
     */
    fun derivedPlan(
        plan: PlanArtifact,
        spec: SubtaskSpec,
        siblingFiles: List<String>,
    ): PlanArtifact = plan.copy(
        objective = spec.objective,
        filesInScope = spec.files,
        filesOutOfScope = (plan.filesOutOfScope + siblingFiles).distinct().sorted(),
    )

    /** 文件的直接父目录（无斜杠即项目根目录，返回 ""）。 */
    fun parentDir(path: String): String {
        val clean = path.trim().trimStart('/').trimStart('.').trimStart('/')
        val slash = clean.lastIndexOf('/')
        return if (slash < 0) "" else clean.substring(0, slash)
    }

    /** 两个目录的最长公共父目录；无公共父目录返回 null。 */
    fun commonDir(a: String, b: String): String? {
        if (a.isEmpty() || b.isEmpty()) return null
        val aParts = a.split('/')
        val bParts = b.split('/')
        val common = ArrayList<String>()
        for (i in 0 until minOf(aParts.size, bParts.size)) {
            if (aParts[i] != bParts[i]) break
            common += aParts[i]
        }
        return common.joinToString("/").takeIf { it.isNotEmpty() }
    }

    private fun normalizeFiles(files: List<String>): List<String> =
        files.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()

    /** 拆分方案 JSON（存 decomposition_states.proposalJson）。 */
    fun proposalToJson(proposal: DecompositionProposal.Splittable): String {
        val obj = JSONObject()
        obj.put("version", PROPOSAL_VERSION)
        val arr = JSONArray()
        for (spec in proposal.subtasks) {
            val item = JSONObject()
            item.put("index", spec.index)
            item.put("module", spec.moduleLabel)
            item.put("objective", spec.objective)
            item.put("files", JSONArray(spec.files))
            arr.put(item)
        }
        obj.put("subtasks", arr)
        return obj.toString()
    }

    /** 读拆分方案 JSON：坏 JSON / 未知版本返回 null，不抛。 */
    fun proposalFromJson(json: String): DecompositionProposal.Splittable? {
        return try {
            val obj = JSONObject(json)
            if (obj.optInt("version", -1) != PROPOSAL_VERSION) return null
            val arr = obj.optJSONArray("subtasks") ?: return null
            val subtasks = ArrayList<SubtaskSpec>()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val files = ArrayList<String>()
                val filesArr = item.optJSONArray("files")
                if (filesArr != null) {
                    for (j in 0 until filesArr.length()) {
                        files += filesArr.optString(j, "")
                    }
                }
                subtasks += SubtaskSpec(
                    index = item.optInt("index", i),
                    moduleLabel = item.optString("module", ""),
                    objective = item.optString("objective", ""),
                    files = files.filter { it.isNotEmpty() },
                )
            }
            if (subtasks.isEmpty()) return null
            DecompositionProposal.Splittable(subtasks.sortedBy { it.index })
        } catch (_: Exception) {
            null
        }
    }
}
