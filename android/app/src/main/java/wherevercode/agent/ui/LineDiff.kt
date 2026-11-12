package wherevercode.agent.ui

/**
 * 行级 diff（阶段 3，U2/批 4）：纯 Kotlin，无 Android 依赖。
 *
 * 渲染形状与行种词汇复用老项目 `artifact_diff`（task_review.py:206）：
 * `{path, insertions, deletions, binary, lines[{kind, old_line,
 * new_line, text}]}`，kind 取 `add` / `delete` / `context`。
 * 解析器不移植：老项目解析的是 git patch，App 内没有 git patch
 * 输入；这里的输入是 WorkspaceAdapter 写轮次前后的两份文本
 * （WorkspaceEditSession.open() 读到的原文 vs 用户改后的草稿），
 * 用 LCS 算出行级差异。稳定性审查（maintainer 12:53）：老项目
 * artifact_diff 本身是纯函数、无 I/O、无全局状态，锚定正则
 * `@@ -a +b @@` 与 C 引用路径解码有回退；本实现只取其输出形状
 * 与行种词汇，不取其 patch 解析逻辑，因此不存在把旧解析 bug
 * 带进来的路径。回归覆盖：LineDiffTest。
 */
enum class DiffLineKind {
    ADD,
    DELETE,
    CONTEXT,
}

data class DiffLine(
    val kind: DiffLineKind,
    val oldLine: Int?,
    val newLine: Int?,
    val text: String,
)

data class FileDiff(
    val path: String,
    val insertions: Int,
    val deletions: Int,
    val binary: Boolean,
    val lines: List<DiffLine>,
)

object LineDiff {

    /** LCS 表上限（行数乘积）：超过则中段整体按删旧+增新处理，避免 O(n*m) 爆炸。 */
    private const val MAX_LCS_CELLS = 100_000

    /** 对两份完整文本做行级 diff；含 NUL 字节视为二进制，不展开行。 */
    fun diff(path: String, oldText: String, newText: String): FileDiff {
        if ('\u0000' in oldText || '\u0000' in newText) {
            return FileDiff(path, 0, 0, binary = true, lines = emptyList())
        }
        val lines = diffLines(splitLines(oldText), splitLines(newText))
        return FileDiff(
            path = path,
            insertions = lines.count { it.kind == DiffLineKind.ADD },
            deletions = lines.count { it.kind == DiffLineKind.DELETE },
            binary = false,
            lines = lines,
        )
    }

    fun diffLines(oldLines: List<String>, newLines: List<String>): List<DiffLine> {
        var prefix = 0
        while (prefix < oldLines.size && prefix < newLines.size &&
            oldLines[prefix] == newLines[prefix]
        ) {
            prefix++
        }
        var suffix = 0
        while (suffix < oldLines.size - prefix && suffix < newLines.size - prefix &&
            oldLines[oldLines.size - 1 - suffix] == newLines[newLines.size - 1 - suffix]
        ) {
            suffix++
        }
        val out = mutableListOf<DiffLine>()
        for (i in 0 until prefix) {
            out += DiffLine(DiffLineKind.CONTEXT, i + 1, i + 1, oldLines[i])
        }
        out += diffMiddle(
            oldLines.subList(prefix, oldLines.size - suffix),
            newLines.subList(prefix, newLines.size - suffix),
            oldStart = prefix,
            newStart = prefix,
        )
        for (i in 0 until suffix) {
            val oldIndex = oldLines.size - suffix + i
            val newIndex = newLines.size - suffix + i
            out += DiffLine(DiffLineKind.CONTEXT, oldIndex + 1, newIndex + 1, oldLines[oldIndex])
        }
        return out
    }

    private fun diffMiddle(
        oldMid: List<String>,
        newMid: List<String>,
        oldStart: Int,
        newStart: Int,
    ): List<DiffLine> {
        if (oldMid.isEmpty()) {
            return newMid.mapIndexed { i, text ->
                DiffLine(DiffLineKind.ADD, null, newStart + i + 1, text)
            }
        }
        if (newMid.isEmpty()) {
            return oldMid.mapIndexed { i, text ->
                DiffLine(DiffLineKind.DELETE, oldStart + i + 1, null, text)
            }
        }
        if (oldMid.size * newMid.size > MAX_LCS_CELLS) {
            // 大中段不跑 LCS：整段删旧增新，行号仍真实，绝不谎称逐行对齐。
            val out = mutableListOf<DiffLine>()
            oldMid.forEachIndexed { i, text ->
                out += DiffLine(DiffLineKind.DELETE, oldStart + i + 1, null, text)
            }
            newMid.forEachIndexed { i, text ->
                out += DiffLine(DiffLineKind.ADD, null, newStart + i + 1, text)
            }
            return out
        }
        // LCS 动态规划（行内容全等才算同一行）。
        val m = oldMid.size
        val n = newMid.size
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in m - 1 downTo 0) {
            for (j in n - 1 downTo 0) {
                dp[i][j] = if (oldMid[i] == newMid[j]) dp[i + 1][j + 1] + 1
                else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val out = mutableListOf<DiffLine>()
        var i = 0
        var j = 0
        while (i < m && j < n) {
            when {
                oldMid[i] == newMid[j] -> {
                    out += DiffLine(
                        DiffLineKind.CONTEXT,
                        oldStart + i + 1, newStart + j + 1, oldMid[i],
                    )
                    i++
                    j++
                }
                dp[i + 1][j] >= dp[i][j + 1] -> {
                    out += DiffLine(DiffLineKind.DELETE, oldStart + i + 1, null, oldMid[i])
                    i++
                }
                else -> {
                    out += DiffLine(DiffLineKind.ADD, null, newStart + j + 1, newMid[j])
                    j++
                }
            }
        }
        while (i < m) {
            out += DiffLine(DiffLineKind.DELETE, oldStart + i + 1, null, oldMid[i])
            i++
        }
        while (j < n) {
            out += DiffLine(DiffLineKind.ADD, null, newStart + j + 1, newMid[j])
            j++
        }
        return out
    }

    private fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val parts = text.split('\n')
        return if (text.endsWith('\n')) parts.dropLast(1) else parts
    }
}
