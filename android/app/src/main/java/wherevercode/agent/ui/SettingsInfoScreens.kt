package wherevercode.agent.ui

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import wherevercode.agent.R

/**
 * 设置页三个信息屏（控制对齐批五，2026-11-27）：个人资料 / 隐私与安全 /
 * 用量。与 iOS 设置页同构（ProfileEditScreen / PrivacyAndSecurityScreen /
 * 控制台 usage 子页）：
 *
 * - 个人资料：昵称/邮箱表单＋「工作模式」两行；读写由调用方经回调
 *   持久化，本控制器不直接碰存储。
 * - 隐私与安全：三段说明，文案与 iOS 逐字同。
 * - 用量：分节「Token 用量与成本统计」——已记录模型调用事件数＝引擎
 *   事件里 kind=turn_done 的条数（每条对应一轮已完成的模型对话，与
 *   iOS 统计 USAGE 事件同口径）；本机引擎不记录 Token 数与费用，
 *   明说不给数字，绝不编造。数据由调用方现取传入，本控制器不读
 *   任何 store。
 */
private object InfoScreenChrome {

    fun dp(context: Context, value: Int): Int = UiKit.dp(context, value)

    fun scrollRoot(context: Context): ScrollView = ScrollView(context).apply {
        visibility = View.GONE
        isFillViewport = true
        setBackgroundColor(UiKit.color(context, R.color.bg))
    }

    fun content(context: Context): LinearLayout = UiKit.vertical(context).apply {
        setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 20))
    }

    fun header(context: Context, title: String, onBack: () -> Unit): View {
        val row = UiKit.horizontal(context).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(
            UiKit.text(context, "‹ 返回", 14f, R.color.accent, bold = true).apply {
                setPadding(0, dp(context, 8), dp(context, 12), dp(context, 8))
                setOnClickListener { onBack() }
            },
        )
        row.addView(UiKit.text(context, title, 18f, R.color.text_primary, bold = true))
        return row
    }

    fun sectionLabel(context: Context, text: String): TextView =
        UiKit.text(context, text, 12f, R.color.text_secondary, bold = true).apply {
            setPadding(0, dp(context, 14), 0, dp(context, 6))
        }

    fun card(context: Context): LinearLayout = UiKit.vertical(context).apply {
        setBackgroundResource(R.drawable.bg_card)
        setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }

    /** 左标签右值一行（与 iOS LabeledContent 同形）。 */
    fun labeledRow(context: Context, label: String, value: String): View {
        val row = UiKit.horizontal(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 6), 0, dp(context, 6))
        }
        row.addView(
            UiKit.text(context, label, 13f, R.color.text_primary),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(UiKit.text(context, value, 13f, R.color.text_secondary))
        return row
    }

    fun bodyText(context: Context, text: String): TextView =
        UiKit.text(context, text, 12f, R.color.text_secondary).apply {
            setPadding(0, dp(context, 4), 0, dp(context, 4))
        }

    fun detailText(context: Context, text: String): TextView =
        UiKit.text(context, text, 11f, R.color.text_secondary).apply {
            setPadding(0, dp(context, 2), 0, 0)
        }
}

/** 个人资料（与 iOS ProfileEditScreen 同构）。 */
class ProfileScreenController(
    private val context: Context,
    private val onBack: () -> Unit,
    private val initial: () -> Pair<String, String>,
    private val onSave: (String, String) -> Unit,
) {
    val root: ScrollView = InfoScreenChrome.scrollRoot(context)

    private val nicknameInput: EditText = EditText(context).apply {
        hint = "昵称（如 WhereverCode 用户）"
        inputType = InputType.TYPE_CLASS_TEXT
        setSingleLine(true)
    }

    private val emailInput: EditText = EditText(context).apply {
        hint = "邮箱（如 user@wherevercode.local）"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        setSingleLine(true)
    }

    init {
        val content = InfoScreenChrome.content(context)
        content.addView(InfoScreenChrome.header(context, "个人资料", onBack))
        content.addView(InfoScreenChrome.sectionLabel(context, "基本信息"))
        val formCard = InfoScreenChrome.card(context)
        formCard.addView(nicknameInput)
        formCard.addView(emailInput)
        content.addView(formCard)
        content.addView(InfoScreenChrome.sectionLabel(context, "工作模式"))
        val modeCard = InfoScreenChrome.card(context)
        modeCard.addView(InfoScreenChrome.labeledRow(context, "默认模式", "本地优先"))
        modeCard.addView(InfoScreenChrome.labeledRow(context, "凭证存储", "仅保存在本设备安全区域"))
        content.addView(modeCard)
        content.addView(
            UiKit.text(context, "保存", 13f, R.color.accent, bold = true).apply {
                setBackgroundResource(R.drawable.bg_chip_accent)
                setPadding(
                    InfoScreenChrome.dp(context, 16), InfoScreenChrome.dp(context, 9),
                    InfoScreenChrome.dp(context, 16), InfoScreenChrome.dp(context, 9),
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = InfoScreenChrome.dp(context, 16) }
                setOnClickListener {
                    onSave(
                        nicknameInput.text.toString().trim(),
                        emailInput.text.toString().trim(),
                    )
                }
            },
        )
        root.addView(content)
    }

    /** 每次打开时回填已保存值（现取，不缓存）。 */
    fun refresh() {
        val (nickname, email) = initial()
        nicknameInput.setText(nickname)
        emailInput.setText(email)
    }
}

/** 隐私与安全（三段说明与 iOS PrivacyAndSecurityScreen 逐字同）。 */
class PrivacyScreenController(
    private val context: Context,
    private val onBack: () -> Unit,
) {
    val root: ScrollView = InfoScreenChrome.scrollRoot(context)

    init {
        val content = InfoScreenChrome.content(context)
        content.addView(InfoScreenChrome.header(context, "隐私与安全", onBack))
        val sections = listOf(
            "凭证安全保护" to
                "所有模型 API Key 与平台令牌均仅保存在本机系统安全存储中，不会随云端备份自动外泄，也不会写入对话记录。",
            "加密传输保护" to
                "应用默认要求使用加密的 HTTPS 连接，防止凭证与代码在网络传输中被窃听。",
            "敏感信息自动屏蔽" to
                "界面与日志若检测到密钥或访问令牌格式的字符串，将在展示前自动隐藏。",
        )
        sections.forEach { (title, body) ->
            content.addView(InfoScreenChrome.sectionLabel(context, title))
            val card = InfoScreenChrome.card(context)
            card.addView(InfoScreenChrome.bodyText(context, body))
            content.addView(card)
        }
        root.addView(content)
    }
}

/** 用量（与 iOS 控制台 usage 子页同节名同口径，内容只用本机真实计数）。 */
class UsageScreenController(
    private val context: Context,
    private val onBack: () -> Unit,
) {
    data class UsageData(
        val available: Boolean,
        val modelCallCount: Int,
        val modelCallDetails: List<String>,
        val totalEvents: Int,
        val kindCounts: List<Pair<String, Int>>,
        val taskCountsLine: String,
    )

    val root: ScrollView = InfoScreenChrome.scrollRoot(context)

    private val container: LinearLayout = InfoScreenChrome.content(context)

    init {
        container.addView(InfoScreenChrome.header(context, "用量", onBack))
        root.addView(container)
    }

    fun renderLoading() {
        rebuild { container.addView(InfoScreenChrome.bodyText(context, "正在读取本机记录…")) }
    }

    fun render(data: UsageData) {
        rebuild {
            container.addView(InfoScreenChrome.sectionLabel(context, "Token 用量与成本统计"))
            val usageCard = InfoScreenChrome.card(context)
            if (!data.available) {
                usageCard.addView(InfoScreenChrome.bodyText(context, "大脑还在加载，用量暂不可读。"))
            } else {
                usageCard.addView(
                    InfoScreenChrome.labeledRow(context, "已记录模型调用事件数", "${data.modelCallCount}"),
                )
                usageCard.addView(
                    InfoScreenChrome.bodyText(
                        context,
                        "每条对应一轮已完成的模型对话（引擎事件 turn_done）。本机引擎不记录 Token 数与费用，这里不给出 Token/成本数字。",
                    ),
                )
                if (data.modelCallDetails.isEmpty()) {
                    usageCard.addView(InfoScreenChrome.bodyText(context, "暂无已完成的模型对话记录。"))
                } else {
                    data.modelCallDetails.forEach { detail ->
                        usageCard.addView(InfoScreenChrome.detailText(context, detail))
                    }
                }
            }
            container.addView(usageCard)
            container.addView(InfoScreenChrome.sectionLabel(context, "任务与运行事件"))
            val taskCard = InfoScreenChrome.card(context)
            if (!data.available) {
                taskCard.addView(InfoScreenChrome.bodyText(context, "暂无数据。"))
            } else {
                taskCard.addView(InfoScreenChrome.labeledRow(context, "任务计数", data.taskCountsLine))
                taskCard.addView(
                    InfoScreenChrome.labeledRow(context, "已记录运行事件", "${data.totalEvents} 条"),
                )
                data.kindCounts.forEach { (kind, count) ->
                    taskCard.addView(InfoScreenChrome.detailText(context, "$kind · $count 条"))
                }
            }
            container.addView(taskCard)
        }
    }

    private fun rebuild(fill: () -> Unit) {
        // 保留页头（第一个子级），其余重建。
        while (container.childCount > 1) container.removeViewAt(1)
        fill()
        root.scrollTo(0, 0)
    }
}
