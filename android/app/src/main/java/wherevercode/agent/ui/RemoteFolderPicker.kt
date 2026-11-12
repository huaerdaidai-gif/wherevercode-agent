package wherevercode.agent.ui

import android.app.Activity
import android.app.AlertDialog
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import wherevercode.agent.R
import wherevercode.agent.core.mcp.RemoteTarget
import wherevercode.agent.core.project.RemoteFolderEntry
import wherevercode.agent.core.project.joinFolderPath

/**
 * 远端文件夹浏览/选择对话框（经 Hand list 列设备上的文件夹）。
 *
 * 用途：加项目时选家文件夹、项目详情里浏览家文件夹。内容一律来自
 * [listEntries] 的实时 Hand list 回包，不伪造、不缓存假数据；
 * 列出失败时如实显示服务端/传输给的原因，不存假项目。
 *
 * [listEntries] 的回调在主线程执行；根是 [RemoteTarget.folder]
 *（某 Agent 在设备上的授权文件夹），relativePath 是它下面的
 * 相对子路径（"" = 文件夹本身）。
 */
class RemoteFolderPicker(
    private val activity: Activity,
    private val listEntries: (
        target: RemoteTarget,
        relativePath: String,
        callback: (Result<List<RemoteFolderEntry>>) -> Unit,
    ) -> Unit,
) {

    /** 选文件夹：浏览 target 文件夹下面，点“选此文件夹”确认（返回完整路径）。 */
    fun pickFolder(
        target: RemoteTarget,
        initialRelativePath: String = "",
        title: String = "选择文件夹",
        onPicked: (fullPath: String) -> Unit,
    ) {
        show(target, initialRelativePath, title, pickMode = true, onPicked)
    }

    /** 只浏览：查看 target 文件夹内容，可下钻，不选。 */
    fun browseFolder(
        target: RemoteTarget,
        initialRelativePath: String = "",
        title: String = "浏览文件夹",
    ) {
        show(target, initialRelativePath, title, pickMode = false, onPicked = null)
    }

    private fun show(
        target: RemoteTarget,
        initialRelativePath: String,
        title: String,
        pickMode: Boolean,
        onPicked: ((String) -> Unit)?,
    ) {
        val context = activity
        var currentRelative = initialRelativePath.trim().trim('/')
        val pathView = UiKit.text(context, "", 11f, R.color.text_tertiary, mono = true).apply {
            setPadding(dp(4), dp(2), dp(4), dp(8))
        }
        val statusView = UiKit.text(context, "", 12f, R.color.text_secondary).apply {
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        val entriesView = UiKit.vertical(context)
        val scroll = ScrollView(context).apply {
            addView(entriesView)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(320),
            )
        }
        val container = UiKit.vertical(context).apply {
            setPadding(dp(20), dp(8), dp(20), dp(4))
            addView(pathView)
            addView(statusView)
            addView(scroll)
        }

        fun currentFullPath(): String = joinFolderPath(target.folder, currentRelative)

        fun load() {
            pathView.text = currentFullPath()
            statusView.text = "正在列出…"
            statusView.setOnClickListener(null)
            entriesView.removeAllViews()
            listEntries(target, currentRelative) { result ->
                if (activity.isFinishing || activity.isDestroyed) return@listEntries
                result.onSuccess { entries ->
                    statusView.text = if (entries.isEmpty()) "空文件夹。" else ""
                    val sorted = entries.sortedWith(
                        compareByDescending<RemoteFolderEntry> { it.isDir }.thenBy { it.name },
                    )
                    if (currentRelative.isNotEmpty()) {
                        entriesView.addView(
                            folderRow("↑ 上一级", isDir = true) {
                                currentRelative = currentRelative
                                    .substringBeforeLast('/', "")
                                    .trim('/')
                                load()
                            },
                        )
                    }
                    sorted.forEach { entry ->
                        entriesView.addView(
                            folderRow(
                                if (entry.isDir) "${entry.name} /" else entry.name,
                                isDir = entry.isDir,
                            ) {
                                if (!entry.isDir) return@folderRow
                                currentRelative = joinFolderPath(currentRelative, entry.name)
                                    .trim('/')
                                load()
                            },
                        )
                    }
                }.onFailure { error ->
                    statusView.text = "列出失败：${error.message}（点此重试）"
                    statusView.setOnClickListener { load() }
                }
            }
        }

        val builder = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(container)
        if (pickMode) {
            builder.setPositiveButton("选此文件夹") { _, _ ->
                onPicked?.invoke(currentFullPath())
            }
            builder.setNegativeButton("取消", null)
        } else {
            builder.setPositiveButton("关闭", null)
        }
        builder.show()
        load()
    }

    private fun folderRow(
        label: String,
        isDir: Boolean,
        onClick: (() -> Unit)?,
    ): TextView {
        val color = if (isDir) R.color.text_primary else R.color.text_tertiary
        return UiKit.text(activity, label, 13f, color, bold = isDir).apply {
            setPadding(dp(4), dp(10), dp(4), dp(10))
            if (isDir && onClick != null) {
                setOnClickListener { onClick() }
            }
        }
    }

    private fun dp(value: Int): Int = UiKit.dp(activity, value)
}
