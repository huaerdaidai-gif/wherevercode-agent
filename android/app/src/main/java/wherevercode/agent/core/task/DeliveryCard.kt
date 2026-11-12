package wherevercode.agent.core.task

import java.security.MessageDigest
import wherevercode.agent.core.workspace.ChangeKind
import wherevercode.agent.core.workspace.ChangeSet

/**
 * a-3 任务交付卡 / 产物展示（V5 Phase B 查缺补漏）。
 *
 * 字段清单复用老项目 taskDeliveryCard（v6-collab.js:334）+
 * renderExecutionTaskFiles（v6-core.js:627）：状态 + 清单进度 +
 * 测试 verdict 与来源 + 文件列表 + 轮次改动量 + 保留/撤销；
 * 交付记录形状复用 delivery_record.py（R3-01）与
 * execution_artifacts.py（R3-03）的规则，锚点按查缺补漏报告 §7：
 * V5 单机无 git 远端，不照搬 branch/commit/pr_url，锚点 =
 * 轮次 id + ChangeSet + artifact sha256。
 *
 * 数据来源（只用真实数据，不编造）：
 * - 文件列表：ToolRegistry 写轮次的 finishRound() 返回的 ChangeSet，
 *   经 onChangeSet 按轮次任务 id 记入 [DeliveryCardStore]；
 * - 清单进度：V5 没有计划清单，卡上显示的是任务步骤完成数
 *  （TaskStepStore 的 SUCCESS / 总数），不冒充清单；
 * - 测试 verdict：本地轮次暂无测试结果来源，默认 UNKNOWN；只有
 *   调用方显式传入 verdict + 来源时才显示通过/未通过；
 * - 行数（+N/−N）：ChangeSet 只有文件级 kind，没有行数，卡上不明示
 *   数字，只在备注里写清暂无来源。
 *
 * 保留/撤销复用老项目二次确认文案与语义（映射 D4）：
 * 撤销前确认“将恢复任务开始前的文件；如果你后来改过这些文件，
 * 撤销会停止”；重复撤销提示“已撤销过”；保留/撤销按轮次 id 绑定，
 * 轮次对不上不写（复用 execution_artifacts.update_workspace_result
 * 的 round_id 匹配语义）。
 *
 * 进程内存储：ChangeSet 与轮次备份本身只活在本进程
 *（FileWorkspaceAdapter 的 finishedRounds 在内存里，重启即空），
 * 所以交付卡也只保留在本进程内，重启后不再显示；UI 上明标这一点，
 * 与任务步骤同一口径。
 */
enum class DeliveryTestVerdict {
    UNKNOWN,
    NOT_RUN,
    PASSED,
    FAILED,
}

/** 一轮保留/撤销结果（老项目 workspace_round_result：kept / undone）。 */
enum class DeliveryRoundResult {
    KEPT,
    UNDONE,
}

/**
 * 一个交付文件。insertions/deletions 只有行级 diff 来源时才有值；
 * 本地写轮次的 ChangeSet 不带行数，保持 null，不编数字。
 */
data class DeliveryFile(
    val path: String,
    val kind: ChangeKind,
    val insertions: Int? = null,
    val deletions: Int? = null,
)

data class DeliveryRound(
    val roundId: String,
    val files: List<DeliveryFile>,
    val recordedAtEpochSeconds: Long,
    val result: DeliveryRoundResult? = null,
)

/** 一张任务交付卡：一个任务全部写轮次的净改动 + 交付锚点。 */
data class DeliveryCard(
    val taskId: String,
    val rounds: List<DeliveryRound>,
    val files: List<DeliveryFile>,
    val anchorSha256: String,
    val verdict: DeliveryTestVerdict = DeliveryTestVerdict.UNKNOWN,
    val verdictSource: String? = null,
) {
    val addedFiles: Int get() = files.count { it.kind == ChangeKind.A }
    val modifiedFiles: Int get() = files.count { it.kind == ChangeKind.M }
    val deletedFiles: Int get() = files.count { it.kind == ChangeKind.D }
}

object DeliveryCardBuilder {

    /**
     * 两轮对同一文件的净改动。A 后改仍是新增；A 后删等于没动过
     *（返回 null，文件从清单里去掉）；删后重建等于修改。
     */
    fun composeKind(existing: ChangeKind, next: ChangeKind): ChangeKind? =
        when (existing) {
            ChangeKind.A -> when (next) {
                ChangeKind.M -> ChangeKind.A
                ChangeKind.D -> null
                ChangeKind.A -> ChangeKind.A
            }
            ChangeKind.M -> when (next) {
                ChangeKind.D -> ChangeKind.D
                ChangeKind.M -> ChangeKind.M
                ChangeKind.A -> ChangeKind.M
            }
            ChangeKind.D -> when (next) {
                ChangeKind.A -> ChangeKind.M
                ChangeKind.M -> ChangeKind.M
                ChangeKind.D -> ChangeKind.D
            }
        }

    /**
     * 交付锚点：sha256(taskId + 轮次 id + 净改动文件清单)。
     * 规范串按轮次 id 与路径排序，同一内容锚点稳定、内容一变锚点即变。
     */
    fun anchorSha256(taskId: String, roundIds: List<String>, files: List<DeliveryFile>): String {
        val canonical = buildString {
            append(taskId)
            append('\n')
            roundIds.sorted().forEach { append(it).append('\n') }
            files.sortedBy { it.path }.forEach { file ->
                append(file.kind.name).append(' ').append(file.path).append('\n')
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * 建卡。没有产物的任务不建卡：rounds 为空，或全部轮次都没有
     * 文件，或净改动为空（新增后又删），一律返回 null——调用方不
     * 渲染一张空交付卡。
     */
    fun build(
        taskId: String,
        rounds: List<DeliveryRound>,
        verdict: DeliveryTestVerdict = DeliveryTestVerdict.UNKNOWN,
        verdictSource: String? = null,
    ): DeliveryCard? {
        val withFiles = rounds.filter { it.files.isNotEmpty() }
        if (withFiles.isEmpty()) return null
        val merged = LinkedHashMap<String, ChangeKind>()
        for (round in withFiles) {
            for (file in round.files) {
                val existing = merged[file.path]
                if (existing == null) {
                    merged[file.path] = file.kind
                } else {
                    val composed = composeKind(existing, file.kind)
                    if (composed == null) merged.remove(file.path) else merged[file.path] = composed
                }
            }
        }
        if (merged.isEmpty()) return null
        val files = merged.entries.sortedBy { it.key }
            .map { DeliveryFile(it.key, it.value) }
        return DeliveryCard(
            taskId = taskId,
            rounds = withFiles,
            files = files,
            anchorSha256 = anchorSha256(taskId, withFiles.map { it.roundId }, files),
            verdict = verdict,
            verdictSource = verdictSource,
        )
    }
}

/** 交付卡存储：按任务记写轮次（ChangeSet），按轮次记保留/撤销。 */
interface DeliveryCardStore {
    /** 记一轮改动；空 ChangeSet 不记（纯对话不发布幻影交付卡）。 */
    fun recordRound(taskId: String, roundId: String, changeSet: ChangeSet)

    /** 该任务的交付卡；没有产物时返回 null。 */
    fun cardFor(taskId: String): DeliveryCard?

    /**
     * 记一轮的保留/撤销结果。轮次 id 对不上返回 false，不写；
     * 同一结果重复记返回 true（幂等）；已记一种结果后再记另一种
     * 返回 false（保留后不可再撤销，反之亦然）。
     */
    fun markRound(taskId: String, roundId: String, result: DeliveryRoundResult): Boolean
}

class InMemoryDeliveryCardStore(
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : DeliveryCardStore {
    private val lock = Any()
    private val roundsByTask = LinkedHashMap<String, LinkedHashMap<String, DeliveryRound>>()

    override fun recordRound(taskId: String, roundId: String, changeSet: ChangeSet) {
        if (changeSet.files.isEmpty()) return
        val files = changeSet.files.map { DeliveryFile(it.path, it.change) }
        synchronized(lock) {
            val rounds = roundsByTask.getOrPut(taskId) { LinkedHashMap() }
            val previous = rounds[roundId]
            rounds[roundId] = DeliveryRound(
                roundId = roundId,
                files = files,
                recordedAtEpochSeconds = nowSeconds(),
                result = previous?.result,
            )
        }
    }

    override fun cardFor(taskId: String): DeliveryCard? {
        val rounds = synchronized(lock) {
            roundsByTask[taskId]?.values?.toList() ?: return null
        }
        return DeliveryCardBuilder.build(taskId, rounds)
    }

    override fun markRound(taskId: String, roundId: String, result: DeliveryRoundResult): Boolean {
        synchronized(lock) {
            val rounds = roundsByTask[taskId] ?: return false
            val round = rounds[roundId] ?: return false
            if (round.result == result) return true
            if (round.result != null) return false
            rounds[roundId] = round.copy(result = result)
            return true
        }
    }
}

/** 交付卡的一行文件（UI 用：kind 文案固定为新增/修改/删除）。 */
data class DeliveryFileRow(val path: String, val kindLabel: String)

/** 交付卡的一轮（UI 用：保留/撤销只在任务终态且本轮未定论时可点）。 */
data class DeliveryRoundRow(
    val roundId: String,
    val shortRoundId: String,
    val fileCount: Int,
    val resultLabel: String?,
    val canKeep: Boolean,
    val canUndo: Boolean,
)

/** 交付卡视图模型（纯数据，MainActivity 只按它画视图）。 */
data class DeliveryCardViewModel(
    val summaryLine: String,
    val progressLine: String?,
    val verdictLine: String,
    val files: List<DeliveryFileRow>,
    val rounds: List<DeliveryRoundRow>,
    val anchorLine: String,
    val noteLine: String,
)

object DeliveryCardView {
    fun build(
        card: DeliveryCard,
        stepsDone: Int?,
        stepsTotal: Int?,
        taskTerminal: Boolean,
    ): DeliveryCardViewModel {
        val progressLine = if (stepsDone != null && stepsTotal != null) {
            "步骤 $stepsDone/$stepsTotal 已完成"
        } else {
            null
        }
        val verdictLine = when (card.verdict) {
            DeliveryTestVerdict.UNKNOWN -> "测试结论：未验证（暂无测试结果来源）"
            DeliveryTestVerdict.NOT_RUN -> "测试结论：未运行测试"
            DeliveryTestVerdict.PASSED ->
                "测试结论：通过（来源：${card.verdictSource ?: "未记录"}）"
            DeliveryTestVerdict.FAILED ->
                "测试结论：未通过（来源：${card.verdictSource ?: "未记录"}）"
        }
        return DeliveryCardViewModel(
            summaryLine = "共 ${card.files.size} 个文件" +
                "（新增 ${card.addedFiles} · 修改 ${card.modifiedFiles} · 删除 ${card.deletedFiles}）" +
                "，${card.rounds.size} 个轮次",
            progressLine = progressLine,
            verdictLine = verdictLine,
            files = card.files.map {
                DeliveryFileRow(
                    path = it.path,
                    kindLabel = when (it.kind) {
                        ChangeKind.A -> "新增"
                        ChangeKind.M -> "修改"
                        ChangeKind.D -> "删除"
                    },
                )
            },
            rounds = card.rounds.map { round ->
                val resultLabel = when (round.result) {
                    DeliveryRoundResult.KEPT -> "已保留"
                    DeliveryRoundResult.UNDONE -> "已撤销"
                    null -> null
                }
                val actionable = taskTerminal && round.result == null
                DeliveryRoundRow(
                    roundId = round.roundId,
                    shortRoundId = round.roundId.take(8),
                    fileCount = round.files.size,
                    resultLabel = resultLabel,
                    canKeep = actionable,
                    canUndo = actionable,
                )
            },
            anchorLine = "交付锚点：${card.rounds.size} 个轮次 · sha256 ${card.anchorSha256.take(12)}…",
            noteLine = "行数（+N/−N）暂无来源：改动记录只有文件级 kind，未编数字；" +
                "交付卡只保留在本进程内，重启后不再显示。",
        )
    }
}
