package wherevercode.agent.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * 对话消息列表宿主（对话组件收敛，2026-11-20）：单 Agent 对话页与
 * 频道页共用的同一块列表核心——ScrollView + 纵向内容容器 + 贴底
 * 判定/滚动。两个页面此前各搭一套（单聊在 screen_chat.xml 里、
 * 频道在 TeamChannelController 里手搭），结构与行为口径一致，抽出
 * 共用，避免再各改各的。
 *
 * 本组件只管宿主：滚动条、clipToPadding、内容 padding 等呈现细节
 * 由各页面按自家现状配置（单聊无滚动条、频道默认），消息内容
 * 渲染仍归各页面（单聊是轮次对 ChatTurnView，频道是多发送者单条
 * 消息行——消息模型不同，不硬塞同一渲染器）。
 */
class MessageListView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    val scrollView: ScrollView = ScrollView(context).apply {
        isFillViewport = true
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
        )
    }

    /** 消息内容容器（纵向）；页面把消息行加在这里。 */
    val content: LinearLayout = UiKit.vertical(context).apply {
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT,
        )
    }

    init {
        // 内层视图不再挂 R.id.chatScroll / R.id.messageList：
        // screen_chat.xml 里已无这两个 id 声明，R 类不会生成它们
        // （A3 曾在此赋值导致编译失败，见 run 记录）。
        // 调用方改走本组件真实存在的公开属性 scrollView / content。
        scrollView.addView(content)
        addView(scrollView)
    }

    /**
     * 是否已贴近底部（与单聊页原 isNearBottom 同口径：内容底部与
     * 视口底部距离小于 [thresholdDp] 算贴底；无内容时算贴底）。
     */
    fun isNearBottom(thresholdDp: Int = 140): Boolean {
        val child = scrollView.getChildAt(0) ?: return true
        val distance = child.bottom - (scrollView.height + scrollView.scrollY)
        return distance < UiKit.dp(context, thresholdDp)
    }

    /** 滚动到底部（下一帧执行，与原 fullScroll 调用方式一致）。 */
    fun scrollToBottom() {
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }
}
