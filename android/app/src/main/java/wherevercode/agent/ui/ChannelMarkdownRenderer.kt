package wherevercode.agent.ui

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan

/**
 * 频道消息 Markdown 容错渲染器（频道孤儿项补全二期）：
 * 支持粗斜体、代码块、链接、列表、引用。
 * 任何解析或 Span 样式异常绝不崩溃、绝不吞字，无损回退纯文本。
 */
object ChannelMarkdownRenderer {

    fun render(text: String): CharSequence {
        if (text.isBlank()) return text
        return runCatching {
            renderMarkdownSpannable(text)
        }.getOrDefault(text)
    }

    private fun renderMarkdownSpannable(text: String): CharSequence {
        val blocks = MarkdownLite.parse(text)
        if (blocks.isEmpty()) return text
        val out = SpannableStringBuilder()
        for ((index, block) in blocks.withIndex()) {
            if (index > 0) out.append("\n\n")
            when (block) {
                is MdBlock.Heading -> {
                    val start = out.length
                    appendInlines(out, block.inlines)
                    out.setSpan(
                        StyleSpan(Typeface.BOLD),
                        start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    out.setSpan(
                        RelativeSizeSpan(if (block.level <= 1) 1.2f else 1.1f),
                        start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                is MdBlock.Paragraph -> appendInlines(out, block.inlines)
                is MdBlock.CodeBlock -> {
                    val start = out.length
                    out.append(block.code)
                    out.setSpan(
                        TypefaceSpan("monospace"),
                        start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                is MdBlock.BulletList -> {
                    block.items.forEachIndexed { itemIndex, item ->
                        if (itemIndex > 0) out.append('\n')
                        out.append("• ")
                        appendInlines(out, item)
                    }
                }
                is MdBlock.Quote -> {
                    val start = out.length
                    appendInlines(out, block.inlines)
                    out.setSpan(
                        StyleSpan(Typeface.ITALIC),
                        start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
        }
        return out
    }

    private fun appendInlines(out: SpannableStringBuilder, inlines: List<MdInline>) {
        for (inline in inlines) {
            when (inline) {
                is MdInline.Plain -> out.append(inline.text)
                is MdInline.Bold -> {
                    val start = out.length
                    out.append(inline.text)
                    out.setSpan(
                        StyleSpan(Typeface.BOLD),
                        start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                is MdInline.Italic -> {
                    val start = out.length
                    out.append(inline.text)
                    out.setSpan(
                        StyleSpan(Typeface.ITALIC),
                        start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                is MdInline.Code -> {
                    val start = out.length
                    out.append(inline.text)
                    out.setSpan(
                        TypefaceSpan("monospace"),
                        start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                is MdInline.Link -> {
                    val start = out.length
                    out.append(inline.text)
                    out.setSpan(
                        URLSpan(inline.url),
                        start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
        }
    }
}
