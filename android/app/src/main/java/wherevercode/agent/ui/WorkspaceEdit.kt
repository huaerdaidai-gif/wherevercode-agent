package wherevercode.agent.ui

import java.io.File
import wherevercode.agent.core.workspace.ChangeSet
import wherevercode.agent.core.workspace.KeepResult
import wherevercode.agent.core.workspace.UndoResult
import wherevercode.agent.core.workspace.WorkspaceAdapter

/**
 * 项目页编辑会话（阶段 3，U2/批 4）：纯 Kotlin（java.io），无 Android 依赖。
 *
 * 写路径与 Agent 工具完全一致，走 WorkspaceAdapter 写轮次：
 * beginRound → activateForWrite → guardWrite → 落盘 → finishRound。
 * 用户本人在保存前二次确认即为本次编辑的审批（工作稿“关键语义决策”）；
 * 未确认绝不写盘。保存后改动记录（ChangeSet + 行级 FileDiff）保留
 * 在会话里，undo()/keep() 直接调 adapter 的真实实现并生效。
 *
 * 渲染形状复用老项目 `renderExecutionTaskFiles`（v6-core.js:627）：
 * 文件清单 + insertions/deletions 总数 + 新增/删除/修改 kind 标签；
 * 本实现用 ChangeSet（ChangeKind A/D/M）+ LineDiff 的 insertions/
 * deletions 计数接线，保留/撤销按钮接 WorkspaceAdapter。稳定性审查
 * （maintainer 12:53）：只复用清单形状与 kind 标签，不移植旧 JS 的 DOM
 * 逻辑与备份提示；写保护本身是 App 已有且已测试的 FileWorkspaceAdapter，
 * 不依赖旧项目。回归覆盖：WorkspaceEditTest。
 */
data class EditSaveResult(
    val roundId: String,
    val changeSet: ChangeSet,
    val diff: FileDiff,
)

class WorkspaceEditSession(
    private val adapter: WorkspaceAdapter,
    private val browser: WorkspaceBrowser,
    private val taskId: String = "user-edit",
) {
    var currentPath: String? = null
        private set

    var originalText: String? = null
        private set

    private var draftText: String? = null

    var lastRoundId: String? = null
        private set

    var lastChangeSet: ChangeSet? = null
        private set

    var lastDiff: FileDiff? = null
        private set

    /** 打开文件：读当前盘上内容作为原文与草稿起点；返回浏览器读取结果。 */
    fun open(path: String): WorkspaceRead {
        val read = browser.read(path)
        if (read.kind == WorkspaceReadKind.TEXT) {
            currentPath = path
            originalText = read.text
            draftText = read.text
            lastRoundId = null
            lastChangeSet = null
            lastDiff = null
        }
        return read
    }

    fun updateDraft(text: String) {
        draftText = text
    }

    fun draft(): String? = draftText

    fun hasChanges(): Boolean {
        val original = originalText ?: return false
        return draftText != null && draftText != original
    }

    /** 原文 vs 草稿的行级 diff；未打开文件时为 null。 */
    fun diff(): FileDiff? {
        val path = currentPath ?: return null
        val original = originalText ?: return null
        val draft = draftText ?: return null
        return LineDiff.diff(path, original, draft)
    }

    /**
     * 保存。必须 confirm=true（调用方已做二次确认），否则拒绝写盘。
     * 无改动时不开启轮次，直接返回上一次的保存结果（若有）或空 ChangeSet。
     * 写盘失败时 abandon 当前轮次并原样抛出异常，绝不谎称已保存。
     */
    fun save(confirm: Boolean): EditSaveResult {
        require(confirm) { "保存需要用户二次确认" }
        val path = currentPath ?: throw IllegalStateException("没有打开的文件")
        val original = originalText ?: throw IllegalStateException("没有打开的文件")
        val draft = draftText ?: throw IllegalStateException("没有打开的文件")
        val fileDiff = LineDiff.diff(path, original, draft)
        if (!hasChanges()) {
            val changeSet = lastChangeSet ?: ChangeSet(lastRoundId ?: "", emptyList())
            return EditSaveResult(lastRoundId ?: "", changeSet, fileDiff)
        }
        val file = browser.resolve(path)
            ?: throw IllegalStateException("路径越界：$path")
        val ref = adapter.beginRound(taskId, owner = "user")
        try {
            adapter.activateForWrite()
            // V5 C1：guardWrite 按目标文件相对工作区根目录算备份路径，
            // 绝对/相对路径输入均可（见 FileWorkspaceAdapter.resolveWorkspacePath）。
            adapter.guardWrite(File(path))
            file.parentFile?.mkdirs()
            file.writeText(draft, Charsets.UTF_8)
            val changeSet = adapter.finishRound(ref.roundId)
            lastRoundId = ref.roundId
            lastChangeSet = changeSet
            lastDiff = fileDiff
            originalText = draft
            return EditSaveResult(ref.roundId, changeSet, fileDiff)
        } catch (e: Exception) {
            try {
                adapter.abandon()
            } catch (_: Exception) {
                // 放弃失败也不掩盖原始写盘错误。
            }
            throw e
        }
    }

    /** 撤销最近一次保存（真实恢复备份内容 / 删除新增文件）。 */
    fun undo(): UndoResult {
        val roundId = lastRoundId ?: throw IllegalStateException("没有可撤销的保存")
        val result = adapter.undo(roundId, confirm = true)
        if (!result.alreadyUndone) {
            // 撤销后盘上内容回到原文：草稿与原文重新同步。
            val read = browser.read(currentPath ?: return result)
            if (read.kind == WorkspaceReadKind.TEXT) {
                originalText = read.text
                draftText = read.text
            }
            lastRoundId = null
            lastChangeSet = null
            lastDiff = null
        }
        return result
    }

    /** 保留最近一次保存（清理备份，之后不可再撤销该轮）。 */
    fun keep(): KeepResult {
        val roundId = lastRoundId ?: throw IllegalStateException("没有可保留的保存")
        val result = adapter.keep(roundId, confirm = true)
        lastRoundId = null
        lastChangeSet = null
        lastDiff = null
        return result
    }

    /** 放弃未保存的草稿，回到原文。 */
    fun discardDraft() {
        draftText = originalText
    }
}
