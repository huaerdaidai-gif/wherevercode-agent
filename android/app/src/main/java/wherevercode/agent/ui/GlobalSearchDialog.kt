package wherevercode.agent.ui

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import wherevercode.agent.R
import wherevercode.agent.core.search.GlobalSearch
import wherevercode.agent.core.search.GlobalSearchHit
import wherevercode.agent.core.search.SearchableConversation
import wherevercode.agent.core.search.SearchableProject
import wherevercode.agent.core.search.SearchableTask

/**
 * 全局搜索对话框（核心功能推进第 1 项，2026-11-15）。
 *
 * 内容与壳分开（同 ChatProjectPickerDialog 的模式）：调用方（MainActivity）
 * 用各 Store 的真实列出 API 组装好可搜索数据再传入，点结果只回调导航
 * id，跳转由调用方走既有打开方法（openSession / openProject /
 * openTaskDetail）。匹配与排序全在纯逻辑 [GlobalSearch]，本类只渲染。
 *
 * 范围如实标注：只搜本机会话（标题与消息正文）、项目与任务；频道消息
 * 走平台远端、本机没有消息缓存，不在本地搜索范围内——空态与范围说明
 * 都写在界面上，不假装能搜频道。
 */
object GlobalSearchDialog {

    fun show(
        context: Context,
        conversations: List<SearchableConversation>,
        projects: List<SearchableProject>,
        tasks: List<SearchableTask>,
        onOpenConversation: (sessionId: String, label: String) -> Unit,
        onOpenProject: (projectId: String) -> Unit,
        onOpenTask: (taskId: String) -> Unit,
    ): AlertDialog {
        val dp = { value: Int -> UiKit.dp(context, value) }
        val container = UiKit.vertical(context).apply {
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val input = EditText(context).apply {
            hint = "搜索会话、项目或任务"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 15f
        }
        container.addView(input)
        container.addView(
            UiKit.text(
                context,
                "范围：本机会话（含消息正文）、项目、任务；频道消息不在本地搜索范围内。",
                11f,
                R.color.text_tertiary,
            ).apply { UiKit.margin(this, topDp = 6, bottomDp = 2) },
        )
        val resultsColumn = UiKit.vertical(context)
        val scroll = ScrollView(context).apply { addView(resultsColumn) }
        container.addView(scroll)

        val dialog = AlertDialog.Builder(context)
            .setTitle("全局搜索")
            .setView(container)
            .setNegativeButton("关闭", null)
            .create()

        fun render() {
            resultsColumn.removeAllViews()
            val query = input.text.toString()
            if (query.isBlank()) {
                resultsColumn.addView(
                    UiKit.text(
                        context,
                        "输入关键词，搜索本机的会话、项目与任务。",
                        13f,
                        R.color.text_secondary,
                    ).apply { UiKit.margin(this, topDp = 12, bottomDp = 12) },
                )
                return
            }
            val results = GlobalSearch.search(query, conversations, projects, tasks)
            if (results.isEmpty) {
                resultsColumn.addView(
                    UiKit.text(
                        context,
                        "没有找到匹配的会话、项目或任务。",
                        13f,
                        R.color.text_secondary,
                    ).apply { UiKit.margin(this, topDp = 12, bottomDp = 12) },
                )
                return
            }
            renderGroup(resultsColumn, "会话", results.conversations) { hit ->
                dialog.dismiss()
                onOpenConversation(hit.id, hit.title)
            }
            renderGroup(resultsColumn, "项目", results.projects) { hit ->
                dialog.dismiss()
                onOpenProject(hit.id)
            }
            renderGroup(resultsColumn, "任务", results.tasks) { hit ->
                dialog.dismiss()
                onOpenTask(hit.id)
            }
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                render()
            }
        })
        render()
        dialog.show()
        input.requestFocus()
        return dialog
    }

    private fun renderGroup(
        column: LinearLayout,
        label: String,
        hits: List<GlobalSearchHit>,
        onOpen: (GlobalSearchHit) -> Unit,
    ) {
        if (hits.isEmpty()) return
        val context = column.context
        column.addView(
            UiKit.text(context, "$label（${hits.size}）", 13f, R.color.accent, bold = true)
                .apply { UiKit.margin(this, topDp = 12, bottomDp = 2) },
        )
        for (hit in hits) {
            val row = UiKit.vertical(context).apply {
                setPadding(0, UiKit.dp(context, 8), 0, UiKit.dp(context, 8))
                isClickable = true
                setOnClickListener { onOpen(hit) }
            }
            row.addView(UiKit.text(context, hit.title, 14f, R.color.text_primary))
            if (hit.snippet.isNotBlank()) {
                row.addView(
                    UiKit.text(context, hit.snippet, 12f, R.color.text_secondary)
                        .apply { UiKit.margin(this, topDp = 2) },
                )
            }
            column.addView(row)
            column.addView(View(context).apply {
                setBackgroundColor(UiKit.color(context, R.color.divider))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1,
                )
            })
        }
    }
}
