package wherevercode.agent.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import wherevercode.agent.R
import wherevercode.agent.brain.BrainConfig

/**
 * 设置页大脑列表一行（2026-11-11，maintainer：大脑增删改添要看得见）。
 *
 * 内容与 Activity 分开：行由 [BrainLoginRows.BrainListRow] 生成，状态取
 * 真实值；本文件只负责渲染与点击接线，调用方（MainActivity）只传回调。
 * 单测可直接断言行里的“···”与点击（见 BrainListRowViewTest）。
 *
 * 行结构（与项目行同模式）：名 + 一句话状态 + 右侧打勾（当前大脑）；
 * 点行即切换；行尾“···”= 管理（与长按同一入口）；长按行同样出管理。
 * 管理以前只藏在长按里，设置页上没有任何提示，maintainer 根本找不到——
 * 藏着的等于没有。2026-11-12 起全部类型的大脑都有管理动作（通用与
 * 一线给编辑/删除，SIWC 另有退出登录，CLI 给删除；判定在
 * BrainLoginRows.manageActions，调用方的 showBrainManage 执行）。
 * 删一线大脑不会再“复活”：BrainBootstrap 只在墓碑为空时播种。
 */
object BrainListRowView {

    fun build(
        context: Context,
        row: BrainLoginRows.BrainListRow,
        config: BrainConfig?,
        onSwitch: () -> Unit,
        onManage: (BrainConfig) -> Unit,
    ): View {
        val container = UiKit.horizontal(context).apply {
            setPadding(UiKit.dp(context, 12), UiKit.dp(context, 10), UiKit.dp(context, 12), UiKit.dp(context, 10))
            setBackgroundResource(R.drawable.bg_card)
            isClickable = true
            isFocusable = true
        }
        val textCol = UiKit.vertical(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(UiKit.text(context, row.title, 12f, R.color.text_primary, bold = true))
        textCol.addView(UiKit.text(context, row.statusText, 11f, R.color.text_secondary))
        container.addView(textCol)
        if (row.isCurrent) {
            container.addView(UiKit.text(context, "✓", 16f, R.color.accent, bold = true))
        }
        // 可见的管理入口：点它就打开与长按同一张管理单。子 View 可点按后
        // 只接自己的点击，不触发行的切换。
        if (config != null) {
            container.addView(
                UiKit.text(context, "···", 18f, R.color.text_secondary, bold = true).apply {
                    setPadding(UiKit.dp(context, 12), UiKit.dp(context, 8), UiKit.dp(context, 12), UiKit.dp(context, 8))
                    setOnClickListener { onManage(config) }
                },
            )
        }
        container.setOnClickListener { onSwitch() }
        // 长按管理仍通，但不再是唯一入口（行尾“···”与它同一张单子）。
        if (config != null) {
            container.setOnLongClickListener { onManage(config); true }
        }
        return container
    }
}
