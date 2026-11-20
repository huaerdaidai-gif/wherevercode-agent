package wherevercode.agent.ui

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import wherevercode.agent.R

/** [ChatComposerView] 的排布（见 res/values/attrs.xml）。 */
private fun composerArrangementFrom(attrs: AttributeSet?, context: Context): ChatComposerView.Arrangement {
    if (attrs == null) return ChatComposerView.Arrangement.STACKED
    val typed = context.obtainStyledAttributes(attrs, R.styleable.ChatComposerView)
    return try {
        if (typed.getInt(R.styleable.ChatComposerView_composerArrangement, 0) == 1) {
            ChatComposerView.Arrangement.INLINE
        } else {
            ChatComposerView.Arrangement.STACKED
        }
    } finally {
        typed.recycle()
    }
}

/**
 * 对话输入区（对话组件收敛，2026-11-20）：单 Agent 对话页与频道页
 * 共用的同一个输入区核心——文本输入框 + 发送钮 + 发送状态呈现
 * （enabled + alpha）+ 发送请求接线（点按发送钮、键盘发送键）。
 * 两个页面此前各搭一套（单聊在 screen_chat.xml 里、频道在
 * TeamChannelController.buildInputBar 里手搭），抽出共用。
 *
 * 两页的排布是既有家规，不在本组件里抹平，只作排布参数：
 * - [Arrangement.STACKED]（单聊首页）：双层——上层只有输入框，
 *   下层一行是页面注入的前置控件 + 发送钮（ImageView，36dp）。
 *   容器底色/内边距由页面在 XML 上给（原 inputBox 的样式）。
 * - [Arrangement.INLINE]（频道）：单行——说明行在上，输入框与
 *   发送钮（TextView chip）同行。频道自家的发送方式 RadioGroup、
 *   工具行、扇出行仍由频道页排在本组件前后，不进本组件。
 *
 * 页面差异经插槽/回调注入：STACKED 的上层插槽 [topSlot] 与下层
 * 前置控件（[addLeadingControl]）；发送语义（门控、校验、真正
 * 发给谁）全部留在页面，本组件只发 [onSendRequest]。
 * 输入框样式按排布沿用各页现状（单聊 14sp/最多 5 行/最小高
 * 36dp；频道最多 4 行、主题默认字号），不借收敛改观感。
 */
class ChatComposerView private constructor(
    context: Context,
    attrs: AttributeSet?,
    val arrangement: Arrangement,
) : LinearLayout(context, attrs) {

    enum class Arrangement { STACKED, INLINE }

    @JvmOverloads
    constructor(context: Context, attrs: AttributeSet? = null) :
        this(context, attrs, composerArrangementFrom(attrs, context))

    constructor(context: Context, arrangement: Arrangement) :
        this(context, null, arrangement)

    /** 文本输入框（两页共用同一实例口径：页面读写文本、改 hint 都走它）。 */
    val input: EditText

    /** 发送钮（STACKED 是 ImageView，INLINE 是 [sendLabel]）。 */
    val sendButton: View

    /** INLINE 排布的发送标签（频道要按发送方式改文案）；STACKED 时为 null。 */
    val sendLabel: TextView?

    /** STACKED 排布的上层插槽（单聊的项目上下文胶囊放这里）；INLINE 时为 null。 */
    val topSlot: LinearLayout?

    /** STACKED 排布的下层控制行（id 沿用 inputLowerRow）；INLINE 时为 null。 */
    val lowerRow: LinearLayout?

    private val captionView: TextView?

    /** 发送请求（点按发送钮；STACKED 另含键盘发送键）。语义在页面。 */
    var onSendRequest: (() -> Unit)? = null

    /**
     * 说明行（INLINE 排布，输入行上方的小字，如频道「所有成员
     * 可见…」）。null/空 = 不显示。STACKED 排布没有说明行，设置无效。
     */
    var caption: String?
        get() = captionView?.text?.toString()?.takeIf { it.isNotEmpty() }
        set(value) {
            val view = captionView ?: return
            view.text = value.orEmpty()
            view.visibility = if (value.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

    init {
        orientation = VERTICAL
        val dp = { value: Int -> UiKit.dp(context, value) }
        var caption: TextView? = null
        var top: LinearLayout? = null
        var lower: LinearLayout? = null
        var label: TextView? = null
        when (arrangement) {
            Arrangement.STACKED -> {
                top = UiKit.vertical(context).apply {
                    layoutParams = LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.WRAP_CONTENT,
                    )
                }
                addView(top)
                input = EditText(context).apply {
                    id = R.id.inputPrompt
                    layoutParams = LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.WRAP_CONTENT,
                    )
                    background = null
                    hint = "向 Agent 提问或分派任务"
                    imeOptions = EditorInfo.IME_ACTION_SEND
                    inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    maxLines = 5
                    minHeight = dp(36)
                    setPaddingRelative(dp(12), dp(7), dp(8), dp(7))
                    setTextColor(ContextCompat.getColor(context, R.color.home_text))
                    setHintTextColor(ContextCompat.getColor(context, R.color.home_text_secondary))
                    textSize = 14f
                }
                addView(input)
                lower = UiKit.horizontal(context).apply {
                    id = R.id.inputLowerRow
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, 0)
                    layoutParams = LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.WRAP_CONTENT,
                    )
                }
                lower.addView(
                    View(context).apply {
                        layoutParams = LayoutParams(0, dp(1), 1f)
                    },
                )
                sendButton = ImageView(context).apply {
                    id = R.id.buttonSend
                    layoutParams = LayoutParams(dp(36), dp(36)).apply {
                        marginStart = dp(6)
                    }
                    setBackgroundResource(R.drawable.bg_send_home)
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    setImageResource(R.drawable.ic_send)
                    contentDescription = "发送"
                }
                lower.addView(sendButton)
                addView(lower)
                input.setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_SEND) {
                        onSendRequest?.invoke()
                        true
                    } else {
                        false
                    }
                }
            }
            Arrangement.INLINE -> {
                caption = UiKit.text(context, "", 10f, R.color.text_tertiary).apply {
                    visibility = View.GONE
                }
                addView(caption)
                val row = UiKit.horizontal(context)
                input = EditText(context).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    minLines = 1
                    maxLines = 4
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                }
                row.addView(input)
                label = UiKit.text(context, "发送", 12f, R.color.accent, bold = true).apply {
                    minHeight = dp(48)
                    gravity = Gravity.CENTER
                    setBackgroundResource(R.drawable.bg_chip_accent)
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                }
                sendButton = label
                row.addView(label)
                addView(row)
            }
        }
        captionView = caption
        topSlot = top
        lowerRow = lower
        sendLabel = label
        sendButton.setOnClickListener { onSendRequest?.invoke() }
    }

    /**
     * STACKED 排布：把页面前置控件（+ 号、选对话方等）加进下层行，
     * 插在弹性空白与发送钮之前（保持 XML 时代的左→右顺序）。
     * INLINE 排布无下层行，调用无效果。
     */
    fun addLeadingControl(view: View) {
        val row = lowerRow ?: return
        row.addView(view, row.childCount - 2)
    }

    /** 发送状态呈现（与单聊页原 updateSendButton 同口径：enabled + alpha）。 */
    fun setSendEnabled(enabled: Boolean) {
        sendButton.isEnabled = enabled
        sendButton.alpha = if (enabled) 1f else 0.4f
    }
}
