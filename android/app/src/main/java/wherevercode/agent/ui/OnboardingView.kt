package wherevercode.agent.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import wherevercode.agent.R
import wherevercode.agent.ui.OnboardingController.Companion.ADB_BENEFIT
import wherevercode.agent.ui.OnboardingController.Companion.ADB_DONE_CONTINUE
import wherevercode.agent.ui.OnboardingController.Companion.ADB_GUIDE
import wherevercode.agent.ui.OnboardingController.Companion.ADB_GUIDE_NOTE
import wherevercode.agent.ui.OnboardingController.Companion.ADB_LATER_NOTE
import wherevercode.agent.ui.OnboardingController.Companion.ADB_RECOMMEND
import wherevercode.agent.ui.OnboardingController.Companion.ADB_SKIP
import wherevercode.agent.ui.OnboardingController.Companion.ADB_STEPS
import wherevercode.agent.ui.OnboardingController.Companion.ADB_TITLE
import wherevercode.agent.ui.OnboardingController.Companion.DIRECTION_NEXT
import wherevercode.agent.ui.OnboardingController.Companion.DIRECTION_SKIP
import wherevercode.agent.ui.OnboardingController.Companion.DIRECTION_TITLE
import wherevercode.agent.ui.OnboardingController.Companion.DONE_BRAIN
import wherevercode.agent.ui.OnboardingController.Companion.DONE_DEVICE
import wherevercode.agent.ui.OnboardingController.Companion.DONE_ENTER
import wherevercode.agent.ui.OnboardingController.Companion.DONE_EXEC_ENV
import wherevercode.agent.ui.OnboardingController.Companion.DONE_HEADLINE
import wherevercode.agent.ui.OnboardingController.Companion.DONE_SUBTITLE
import wherevercode.agent.ui.OnboardingController.Companion.DONE_TITLE
import wherevercode.agent.ui.OnboardingController.Companion.WELCOME_FEATURES
import wherevercode.agent.ui.OnboardingController.Companion.WELCOME_HEADLINE
import wherevercode.agent.ui.OnboardingController.Companion.WELCOME_IMPORT
import wherevercode.agent.ui.OnboardingController.Companion.WELCOME_LATER
import wherevercode.agent.ui.OnboardingController.Companion.WELCOME_START
import wherevercode.agent.ui.OnboardingController.Companion.WELCOME_SUBTITLE
import wherevercode.agent.ui.OnboardingController.Companion.WELCOME_TITLE
import wherevercode.agent.ui.OnboardingController.Companion.WELCOME_VERSION

/**
 * 首次使用引导三屏视图（V5 Phase B C2，批 3，R3）。
 *
 * 视觉基准：V5 原型图上排前 4 屏（v5-prototype-20261110.png），
 * 首批默认浅色极简（v4.3-01）。按原型图照抄布局/密度/字体层级，
 * 不自行重新设计，不堆大圆角卡片：方向行与状态行是紧凑列表行，
 * 选中态只用 accent_soft 底色 + accent 描边高亮（与抽屉 R2 同款
 * 规矩）。颜色/圆角/间距一律走主题 token，不写死。
 *
 * 视图只渲染 [OnboardingController] 的状态并把点按交回宿主：
 * - 完成/跳过：视图先调 controller.skip()/finish() 记完成，再调
 *   [onFinished]，宿主只负责撤下遮罩、回聊天首页；
 * - “一键引导设置”：视图只调 [onAdbGuide]，宿主打开系统开发者
 *   设置做引导；本步不做配对、不声称已配对；
 * - “导入 / 恢复”：视图只调 [onImportRestore]，宿主给“待开发”提示。
 * 3/3 的三行状态由 [readinessProvider] 在渲染时从宿主取真实值；
 * 未检测的一律显示“未检测”。
 */
class OnboardingView(
    private val context: Context,
    private val controller: OnboardingController,
    private val readinessProvider: () -> OnboardingController.ReadinessRows =
        { OnboardingController.ReadinessRows(
            OnboardingController.Readiness.NOT_CHECKED,
            OnboardingController.Readiness.NOT_CHECKED,
            OnboardingController.Readiness.NOT_CHECKED,
        ) },
    private val onFinished: () -> Unit = {},
    private val onAdbGuide: () -> Unit = {},
    private val onImportRestore: () -> Unit = {},
) {

    val root: View
    private val body: LinearLayout

    init {
        body = UiKit.vertical(context).apply {
            setPadding(dp(24), dp(32), dp(24), dp(32))
        }
        root = ScrollView(context).apply {
            setBackgroundColor(UiKit.color(context, R.color.home_bg))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            addView(body)
        }
        refresh()
    }

    /** 按 controller 当前屏重渲染（宿主在每次状态变化后调一次）。 */
    fun refresh() {
        body.removeAllViews()
        when (controller.step()) {
            OnboardingController.Step.WELCOME -> renderWelcome()
            OnboardingController.Step.DIRECTION -> renderDirection()
            OnboardingController.Step.ADB -> renderAdb()
            OnboardingController.Step.DONE -> renderDone()
        }
    }

    // ------------------------------------------------------------------
    // 欢迎页（原型图第 1 屏）
    // ------------------------------------------------------------------

    private fun renderWelcome() {
        body.addView(UiKit.text(context, WELCOME_TITLE, 26f, R.color.home_text, bold = true).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(56), 0, dp(8))
        })
        body.addView(UiKit.text(context, WELCOME_VERSION, 12f, R.color.accent, bold = true).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(24))
        })
        body.addView(UiKit.text(context, WELCOME_HEADLINE, 22f, R.color.home_text, bold = true).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(12))
        })
        body.addView(UiKit.text(context, WELCOME_FEATURES, 14f, R.color.home_text_secondary).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(4))
        })
        body.addView(UiKit.text(context, WELCOME_SUBTITLE, 14f, R.color.home_text_secondary).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(40))
        })
        body.addView(primaryButton(WELCOME_START) {
            controller.next()
            refresh()
        })
        body.addView(textButton(WELCOME_IMPORT) { onImportRestore() })
        body.addView(textButton(WELCOME_LATER) {
            controller.skip()
            onFinished()
        })
    }

    // ------------------------------------------------------------------
    // 1/3 选择使用方向（原型图第 2 屏）
    // ------------------------------------------------------------------

    private fun renderDirection() {
        body.addView(stepHeader("‹  1/3  $DIRECTION_TITLE") {
            controller.back()
            refresh()
        })
        val selected = controller.selectedDirection()
        OnboardingController.Direction.values().forEach { direction ->
            val isSelected = direction == selected
            val row = UiKit.horizontal(context).apply {
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = UiKit.rounded(
                    context,
                    if (isSelected) R.color.accent_soft else R.color.home_card,
                    12,
                    if (isSelected) R.color.accent else R.color.home_card_border,
                    1,
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) }
                isClickable = true
                setOnClickListener {
                    controller.selectDirection(direction.id)
                    refresh()
                }
            }
            val texts = UiKit.vertical(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(UiKit.text(context, direction.title, 15f, R.color.home_text, bold = true))
            texts.addView(UiKit.text(context, direction.subtitle, 12f, R.color.home_text_secondary))
            row.addView(texts)
            row.addView(UiKit.text(
                context,
                if (isSelected) "●" else "○",
                14f,
                if (isSelected) R.color.accent else R.color.text_tertiary,
            ))
            body.addView(row)
        }
        body.addView(primaryButton(DIRECTION_NEXT) {
            controller.next()
            refresh()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(24)
        })
        body.addView(textButton(DIRECTION_SKIP) {
            controller.skip()
            onFinished()
        })
    }

    // ------------------------------------------------------------------
    // 2/3 无线调试 ADB 引导（原型图第 3 屏）
    // ------------------------------------------------------------------

    private fun renderAdb() {
        body.addView(stepHeader("‹  2/3  $ADB_TITLE") {
            controller.back()
            refresh()
        })
        body.addView(UiKit.text(context, ADB_RECOMMEND, 16f, R.color.home_text, bold = true).apply {
            setPadding(0, dp(16), 0, dp(8))
        })
        body.addView(UiKit.text(context, ADB_BENEFIT, 13f, R.color.home_text_secondary).apply {
            setPadding(0, 0, 0, dp(4))
        })
        body.addView(UiKit.text(context, ADB_LATER_NOTE, 12f, R.color.text_tertiary).apply {
            setPadding(0, 0, 0, dp(16))
        })
        ADB_STEPS.forEach { stepText ->
            body.addView(UiKit.text(context, stepText, 14f, R.color.home_text).apply {
                setPadding(0, dp(6), 0, dp(6))
            })
        }
        body.addView(primaryButton(ADB_GUIDE) { onAdbGuide() }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(24)
        })
        body.addView(UiKit.text(context, ADB_GUIDE_NOTE, 11f, R.color.text_tertiary).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(10), dp(8), 0)
        })
        body.addView(textButton(ADB_DONE_CONTINUE) {
            controller.next()
            refresh()
        })
        body.addView(textButton(ADB_SKIP) {
            controller.skip()
            onFinished()
        })
    }

    // ------------------------------------------------------------------
    // 3/3 准备完成（原型图第 4 屏）
    // ------------------------------------------------------------------

    private fun renderDone() {
        body.addView(stepHeader("‹  3/3  $DONE_TITLE") {
            controller.back()
            refresh()
        })
        body.addView(UiKit.text(context, "✓", 32f, R.color.home_success, bold = true).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(24), 0, dp(8))
        })
        body.addView(UiKit.text(context, DONE_HEADLINE, 18f, R.color.home_text, bold = true).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(8))
        })
        body.addView(UiKit.text(context, DONE_SUBTITLE, 13f, R.color.home_text_secondary).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), 0, dp(8), dp(24))
        })
        val rows = readinessProvider()
        body.addView(statusRow(DONE_BRAIN, rows.brain))
        body.addView(statusRow(DONE_EXEC_ENV, rows.execEnv))
        body.addView(statusRow(DONE_DEVICE, rows.device))
        body.addView(primaryButton(DONE_ENTER) {
            controller.finish()
            onFinished()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(24)
        })
    }

    private fun statusRow(label: String, readiness: OnboardingController.Readiness): View {
        val row = UiKit.horizontal(context).apply {
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        val mark = when (readiness) {
            OnboardingController.Readiness.AVAILABLE -> "✓"
            OnboardingController.Readiness.NOT_CONFIGURED -> "○"
            OnboardingController.Readiness.NOT_CHECKED -> "?"
        }
        val markColor = when (readiness) {
            OnboardingController.Readiness.AVAILABLE -> R.color.home_success
            OnboardingController.Readiness.NOT_CONFIGURED -> R.color.home_warning
            OnboardingController.Readiness.NOT_CHECKED -> R.color.text_tertiary
        }
        row.addView(UiKit.text(context, mark, 14f, markColor, bold = true).apply {
            setPadding(0, 0, dp(8), 0)
        })
        row.addView(UiKit.text(context, label, 14f, R.color.home_text).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(UiKit.text(
            context,
            OnboardingController.readinessLabel(readiness),
            13f,
            markColor,
        ))
        return row
    }

    // ------------------------------------------------------------------
    // 小件
    // ------------------------------------------------------------------

    private fun dp(value: Int): Int = UiKit.dp(context, value)

    private fun stepHeader(text: String, onBack: () -> Unit): View {
        val row = UiKit.horizontal(context)
        val back = UiKit.text(context, "‹", 20f, R.color.home_text).apply {
            setPadding(0, 0, dp(8), 0)
            isClickable = true
            setOnClickListener { onBack() }
        }
        row.addView(back)
        row.addView(UiKit.text(context, text, 17f, R.color.home_text, bold = true))
        return row
    }

    private fun primaryButton(label: String, onClick: () -> Unit): TextView =
        UiKit.text(context, label, 15f, R.color.on_accent, bold = true).apply {
            gravity = Gravity.CENTER
            background = UiKit.rounded(context, R.color.accent, 12)
            setPadding(0, dp(14), 0, dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16) }
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun textButton(label: String, onClick: () -> Unit): TextView =
        UiKit.text(context, label, 13f, R.color.home_text_secondary).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(14), 0, dp(4))
            isClickable = true
            setOnClickListener { onClick() }
        }
}
