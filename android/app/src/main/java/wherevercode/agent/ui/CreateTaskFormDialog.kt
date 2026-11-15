package wherevercode.agent.ui

import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import wherevercode.agent.R
import wherevercode.agent.core.agent.AgentRecord
import wherevercode.agent.core.project.Project

/**
 * 新建任务独立表单对话框（核心功能推进第 3 项，2026-11-15）。
 *
 * 内容与壳分开（同 GlobalSearchDialog 的模式）：调用方（MainActivity）
 * 在后台线程用 agentStore / projectStore 的真实列出 API 组装数据再
 * 传入——agents 已按 [CreateTaskForm.dispatchableAgents] 过滤（已启用
 * 且可派单），projects 为项目全量（可为空，表单恒有「不绑定」）。
 * 校验与表单值 → 任务行的映射全在纯逻辑 [CreateTaskForm]，本类只
 * 渲染与收集。
 *
 * 提交约定：点「创建任务」先在弹窗内校验，未过校验不关弹窗、错误
 * 原样显示在表单里；过了校验把 [CreateTaskForm.Input] 交 [onSubmit]，
 * 调用方在后台线程走 TaskStore.createTask 真建单，完成后在 UI 线程
 * 回调 done——error 非空时弹窗不关、把真实错误原文显示出来（不吞
 * 错装成功）并恢复按钮；成功时弹窗关闭，之后由调用方 toast 并
 * 打开任务详情。
 *
 * 无可派单 Agent 时不编造选项：只给空态说明与「去添加 Agent」
 * 入口（回调 [onOpenAgents]，由调用方跳 Agent 与团队页）。
 */
object CreateTaskFormDialog {

    fun show(
        context: Context,
        agents: List<AgentRecord>,
        projects: List<Project>,
        onOpenAgents: () -> Unit,
        onSubmit: (
            input: CreateTaskForm.Input,
            done: (error: String?, createdTaskId: String?) -> Unit,
        ) -> Unit,
    ): AlertDialog {
        if (agents.isEmpty()) {
            return AlertDialog.Builder(context)
                .setTitle("新建任务")
                .setMessage(
                    "还没有可派单的 Agent。请先添加 Agent；" +
                        "已停用或不可派单的 Agent 不会出现在执行列表里。",
                )
                .setPositiveButton("去添加 Agent") { _, _ -> onOpenAgents() }
                .setNegativeButton("取消", null)
                .create()
                .also { it.show() }
        }

        val dp = { value: Int -> UiKit.dp(context, value) }
        val container = UiKit.vertical(context).apply {
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }

        container.addView(label(context, "任务标题（必填）"))
        val titleInput = EditText(context).apply {
            hint = "如：给登录页加记住我选项"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 15f
        }
        container.addView(titleInput)

        container.addView(label(context, "详细说明（可选）"))
        val descriptionInput = EditText(context).apply {
            hint = "补充目标、范围、验收方式等；不填则以标题为任务目标"
            setSingleLine(false)
            minLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            textSize = 15f
        }
        container.addView(descriptionInput)

        container.addView(label(context, "执行 Agent（必填）"))
        val agentSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_item,
                agents.map { it.displayName },
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        container.addView(agentSpinner)
        val hintView = UiKit.text(
            context,
            CreateTaskForm.executionHint(agents.first()),
            11f,
            R.color.text_tertiary,
        ).apply { UiKit.margin(this, topDp = 4) }
        container.addView(hintView)
        agentSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                agents.getOrNull(position)?.let { hintView.text = CreateTaskForm.executionHint(it) }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        container.addView(label(context, "项目（可选）"))
        val projectSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_item,
                listOf("不绑定") + projects.map { it.name },
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        container.addView(projectSpinner)

        val errorView = UiKit.text(context, "", 12f, R.color.danger).apply {
            visibility = View.GONE
            UiKit.margin(this, topDp = 10)
        }
        container.addView(errorView)

        val scroll = ScrollView(context).apply { addView(container) }
        val dialog = AlertDialog.Builder(context)
            .setTitle("新建任务")
            .setView(scroll)
            .setPositiveButton("创建任务", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.show()

        // 校验不过 / 建单失败都不关弹窗：覆盖正按钮的默认关闭行为，
        // 先校验、再提交，错误原样显示在表单里。
        val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        positive.setOnClickListener {
            val input = CreateTaskForm.Input(
                title = titleInput.text.toString(),
                description = descriptionInput.text.toString(),
                agent = agents.getOrNull(agentSpinner.selectedItemPosition),
                projectId = projects.getOrNull(projectSpinner.selectedItemPosition - 1)?.projectId,
            )
            val validationError = CreateTaskForm.validate(input)
            if (validationError != null) {
                errorView.text = validationError
                errorView.visibility = View.VISIBLE
                return@setOnClickListener
            }
            errorView.visibility = View.GONE
            positive.isEnabled = false
            onSubmit(input) { submitError, _ ->
                if (submitError != null) {
                    positive.isEnabled = true
                    errorView.text = "创建失败：$submitError"
                    errorView.visibility = View.VISIBLE
                } else {
                    dialog.dismiss()
                }
            }
        }
        titleInput.requestFocus()
        return dialog
    }

    private fun label(context: Context, text: String): TextView =
        UiKit.text(context, text, 12f, R.color.text_secondary, bold = true)
            .apply { UiKit.margin(this, topDp = 12, bottomDp = 4) }
}
