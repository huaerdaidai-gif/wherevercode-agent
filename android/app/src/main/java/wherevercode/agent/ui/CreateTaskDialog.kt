package wherevercode.agent.ui

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import androidx.appcompat.app.AlertDialog
import wherevercode.agent.R
import wherevercode.agent.core.agent.AgentRecord
import wherevercode.agent.core.project.Project

/** Claude C13: a goal, a project and an executor, followed by explicit submission. */
class CreateTaskDialog(
    context: Context,
    projects: List<Project>,
    agents: List<AgentRecord>,
    initialGoal: String = "",
    initialProjectId: String? = null,
    initialAgentId: String? = null,
    private val onSubmit: (String, String?, String?, (String?) -> Unit) -> Unit,
) {
    private val projectChoices = projects.toList()
    private val agentChoices = agents.filter { it.enabled && it.dispatchable }
    val goalInput = EditText(context).apply {
        hint = "想完成什么？写下目标与验收要求"
        minLines = 3
        maxLines = 8
        setText(initialGoal)
        setTextColor(UiKit.color(context, R.color.text_primary))
    }
    val projectInput = Spinner(context)
    val agentInput = Spinner(context)
    private val error = UiKit.text(context, "", 12f, R.color.danger)
    private val content = UiKit.vertical(context).apply {
        setPadding(UiKit.dp(context, 20), UiKit.dp(context, 8), UiKit.dp(context, 20), UiKit.dp(context, 16))
    }
    val dialog: AlertDialog

    init {
        fun label(text: String) = UiKit.text(context, text, 13f, R.color.text_primary, bold = true).apply {
            setPadding(0, UiKit.dp(context, 14), 0, UiKit.dp(context, 6))
        }
        projectInput.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
            listOf("未绑定项目 · 使用执行器授权工作区") + projectChoices.map { it.name })
        agentInput.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
            listOf("本机执行 · 当前模型") + agentChoices.map { it.displayName })
        projectInput.setSelection(projectChoices.indexOfFirst { it.projectId == initialProjectId } + 1)
        agentInput.setSelection(agentChoices.indexOfFirst { it.agentId == initialAgentId } + 1)
        content.addView(label("目标"))
        content.addView(goalInput)
        content.addView(label("项目"))
        content.addView(projectInput)
        content.addView(label("交给谁"))
        content.addView(agentInput)
        content.addView(label("审批与结果"))
        content.addView(UiKit.text(context,
            "沿用当前任务权限与工具审批。创建后显示真实任务编号；送达、接单与完成以执行回执为准。结果和审查从任务详情查看。",
            12f, R.color.text_secondary))
        content.addView(error)
        dialog = AlertDialog.Builder(context).setTitle("创建任务")
            .setView(ScrollView(context).apply { addView(content) })
            .setPositiveButton("创建任务", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val goal = goalInput.text.toString().trim()
                if (goal.isBlank()) {
                    goalInput.error = "请填写任务目标"
                    return@setOnClickListener
                }
                error.text = ""
                setSubmitting(true)
                onSubmit(goal, agentChoices.getOrNull(agentInput.selectedItemPosition - 1)?.agentId,
                    projectChoices.getOrNull(projectInput.selectedItemPosition - 1)?.projectId) { failure ->
                    // The host returns on the UI thread; a failure leaves all inputs intact.
                    if (failure == null) dialog.dismiss() else {
                        error.text = failure
                        setSubmitting(false)
                    }
                }
            }
        }
    }

    private fun setSubmitting(submitting: Boolean) {
        goalInput.isEnabled = !submitting
        projectInput.isEnabled = !submitting
        agentInput.isEnabled = !submitting
        dialog.setCancelable(!submitting)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = !submitting
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            isEnabled = !submitting
            text = if (submitting) "正在创建…" else "创建任务"
        }
    }

    fun show() = dialog.show()
}
