package wherevercode.agent.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog
import wherevercode.agent.R

/**
 * 带说明文字 + 选项列表的对话框。
 *
 * Android 的 AlertDialog 不能同时用 setMessage + setItems：AlertController
 * 只在没有 message 时才把列表挂进界面，两者同用时列表直接不显示——
 * 2026-11-11 真机实证：添加项目弹框、项目管理单、大脑管理单、选设备/
 * 选文件夹弹框全是空的，只剩说明文字 + 取消/关闭（maintainer 19:37 截图，
 * “选来源呢？删除呢？创建呢？修改呢？”）。根因不是选项列表为空，
 * 也不是哪个分支删了按钮，是 setMessage 把 setItems 的列表顶掉了，
 * 这套写法从 update5 起就没生效过。
 *
 * 本 helper 用自定义 view 拼说明文字 + 可点选项行，说明和选项同时可见。
 * 以后凡是“说明 + 选一项”都走这里，不要再直接拼 setMessage + setItems。
 */
object ListDialog {

    /**
     * @param selectedIndex 默认选中的行下标（可为 null = 无默认选中）：
     *   该行行首画“✓”并加粗——是真的选中态，不是只换文案假装选中。
     *   2026-11-12 远程文件夹选择器用它标出项目的当前家。
     */
    fun show(
        context: Context,
        title: String,
        message: String?,
        items: List<String>,
        negativeLabel: String = "取消",
        selectedIndex: Int? = null,
        onPick: (Int) -> Unit,
    ): AlertDialog {
        val container = UiKit.vertical(context).apply {
            setPadding(
                UiKit.dp(context, 24),
                UiKit.dp(context, 8),
                UiKit.dp(context, 24),
                UiKit.dp(context, 4),
            )
        }
        if (!message.isNullOrBlank()) {
            container.addView(
                UiKit.text(context, message, 14f, R.color.text_secondary).apply {
                    setPadding(0, 0, 0, UiKit.dp(context, 8))
                },
            )
        }
        var dialog: AlertDialog? = null
        items.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            container.addView(
                UiKit.text(
                    context,
                    if (selected) "✓ $label" else label,
                    16f,
                    R.color.text_primary,
                    bold = selected,
                ).apply {
                    setPadding(
                        0,
                        UiKit.dp(context, 12),
                        0,
                        UiKit.dp(context, 12),
                    )
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        dialog?.dismiss()
                        onPick(index)
                    }
                },
            )
        }
        dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(container)
            .setNegativeButton(negativeLabel, null)
            .create()
        dialog.show()
        return dialog
    }

    /**
     * 带“名 + 一行说明”的选项列表（2026-11-12，项目来源收成一个入口）：
     * 一个入口、一个对话框、一张单子，每项有名（加粗主行）+ 一行说明
     * （小字副行）。老项目“打开 / 导入项目”对话框的模式：选项多不要紧，
     * 清楚就行。整行可点，点即回调下标并关掉。
     */
    fun showWithDescriptions(
        context: Context,
        title: String,
        message: String?,
        items: List<Pair<String, String>>,
        negativeLabel: String = "取消",
        onPick: (Int) -> Unit,
    ): AlertDialog {
        val container = UiKit.vertical(context).apply {
            setPadding(
                UiKit.dp(context, 24),
                UiKit.dp(context, 8),
                UiKit.dp(context, 24),
                UiKit.dp(context, 4),
            )
        }
        if (!message.isNullOrBlank()) {
            container.addView(
                UiKit.text(context, message, 14f, R.color.text_secondary).apply {
                    setPadding(0, 0, 0, UiKit.dp(context, 8))
                },
            )
        }
        var dialog: AlertDialog? = null
        items.forEachIndexed { index, (name, description) ->
            val row = UiKit.vertical(context).apply {
                setPadding(
                    0,
                    UiKit.dp(context, 10),
                    0,
                    UiKit.dp(context, 10),
                )
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dialog?.dismiss()
                    onPick(index)
                }
            }
            row.addView(UiKit.text(context, name, 16f, R.color.text_primary, bold = true))
            row.addView(
                UiKit.text(context, description, 13f, R.color.text_secondary).apply {
                    setPadding(0, UiKit.dp(context, 2), 0, 0)
                },
            )
            container.addView(row)
        }
        dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(container)
            .setNegativeButton(negativeLabel, null)
            .create()
        dialog.show()
        return dialog
    }
}
