package wherevercode.agent.ui

/**
 * Markdown 轻量渲染（阶段 3，U1/批 4）：纯 Kotlin，无 Android 依赖。
 *
 * 只支持聊天里常见的子集：标题、段落、粗体/斜体/行内代码/链接文本、
 * 无序列表、引用、围栏代码块。解析结果由 ChatTurnView 画成
 * Spannable；代码块原文可整体复制（codeBlocks）。
 * 故意不支持表格、图片、HTML、脚注——遇到就当普通文本显示，
 * 绝不静默丢内容。
 */
sealed interface MdInline {
    data class Plain(val text: String) : MdInline
    data class Bold(val text: String) : MdInline
    data class Italic(val text: String) : MdInline
    data class Code(val text: String) : MdInline
    data class Link(val text: String, val url: String) : MdInline
}

sealed interface MdBlock {
    data class Heading(val level: Int, val inlines: List<MdInline>) : MdBlock
    data class Paragraph(val inlines: List<MdInline>) : MdBlock
    data class CodeBlock(val language: String, val code: String) : MdBlock
    data class BulletList(val items: List<List<MdInline>>) : MdBlock
    data class Quote(val inlines: List<MdInline>) : MdBlock
}

object MarkdownLite {

    fun parse(text: String): List<MdBlock> {
        val lines = text.split('\n')
        val blocks = mutableListOf<MdBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith("```") -> {
                    val language = line.removePrefix("```").trim()
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].startsWith("```")) {
                        if (code.isNotEmpty()) code.append('\n')
                        code.append(lines[i])
                        i++
                    }
                    // 跳过闭合围栏（若有）；无闭合围栏时块到文末为止。
                    if (i < lines.size) i++
                    blocks += MdBlock.CodeBlock(language, code.toString())
                }
                line.trim().isEmpty() -> i++
                line.trimStart().startsWith("#") -> {
                    val trimmed = line.trimStart()
                    val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 6)
                    val rest = trimmed.drop(level).trim()
                    if (rest.isNotEmpty()) {
                        blocks += MdBlock.Heading(level, parseInline(rest))
                    } else {
                        blocks += MdBlock.Paragraph(parseInline(line))
                    }
                    i++
                }
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                    val items = mutableListOf<List<MdInline>>()
                    while (i < lines.size) {
                        val item = lines[i].trimStart()
                        if (!item.startsWith("- ") && !item.startsWith("* ")) break
                        items += parseInline(item.drop(2))
                        i++
                    }
                    blocks += MdBlock.BulletList(items)
                }
                line.trimStart().startsWith(">") -> {
                    blocks += MdBlock.Quote(parseInline(line.trimStart().removePrefix(">").trim()))
                    i++
                }
                else -> {
                    val paragraph = StringBuilder(line)
                    i++
                    while (i < lines.size) {
                        val next = lines[i]
                        if (next.trim().isEmpty() || next.startsWith("```") ||
                            next.trimStart().startsWith("#") ||
                            next.trimStart().startsWith("- ") ||
                            next.trimStart().startsWith("* ") ||
                            next.trimStart().startsWith(">")
                        ) {
                            break
                        }
                        paragraph.append('\n').append(next)
                        i++
                    }
                    blocks += MdBlock.Paragraph(parseInline(paragraph.toString()))
                }
            }
        }
        return blocks
    }

    /** 行内解析：`代码`、**粗体**、*斜体*、[文本](链接)；未闭合的标记当普通文本。 */
    fun parseInline(text: String): List<MdInline> {
        val out = mutableListOf<MdInline>()
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) {
                out += MdInline.Plain(plain.toString())
                plain.clear()
            }
        }
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c == '`' -> {
                    val close = text.indexOf('`', i + 1)
                    if (close == -1 || close == i + 1) {
                        plain.append(c)
                        i++
                    } else {
                        flush()
                        out += MdInline.Code(text.substring(i + 1, close))
                        i = close + 1
                    }
                }
                text.startsWith("**", i) -> {
                    val close = text.indexOf("**", i + 2)
                    if (close == -1 || close == i + 2) {
                        plain.append("**")
                        i += 2
                    } else {
                        flush()
                        out += MdInline.Bold(text.substring(i + 2, close))
                        i = close + 2
                    }
                }
                c == '*' -> {
                    val close = text.indexOf('*', i + 1)
                    if (close == -1 || close == i + 1) {
                        plain.append(c)
                        i++
                    } else {
                        flush()
                        out += MdInline.Italic(text.substring(i + 1, close))
                        i = close + 1
                    }
                }
                c == '[' -> {
                    val closeBracket = text.indexOf(']', i + 1)
                    val openParen = if (closeBracket != -1) closeBracket + 1 else -1
                    val closeParen = if (openParen != -1 && openParen < n && text[openParen] == '(') {
                        text.indexOf(')', openParen + 1)
                    } else {
                        -1
                    }
                    if (closeBracket == -1 || closeParen == -1) {
                        plain.append(c)
                        i++
                    } else {
                        flush()
                        out += MdInline.Link(
                            text.substring(i + 1, closeBracket),
                            text.substring(openParen + 1, closeParen),
                        )
                        i = closeParen + 1
                    }
                }
                else -> {
                    plain.append(c)
                    i++
                }
            }
        }
        flush()
        return out
    }

    /** 文本里的全部围栏代码块（按出现顺序），供“复制代码”用。 */
    fun codeBlocks(text: String): List<MdBlock.CodeBlock> =
        parse(text).filterIsInstance<MdBlock.CodeBlock>()

    /** 去标记后的纯文本（标题/列表/引用只留文字，代码块原样保留）。 */
    fun plainText(text: String): String {
        val out = StringBuilder()
        for (block in parse(text)) {
            if (out.isNotEmpty()) out.append('\n')
            when (block) {
                is MdBlock.Heading -> out.append(inlineText(block.inlines))
                is MdBlock.Paragraph -> out.append(inlineText(block.inlines))
                is MdBlock.CodeBlock -> out.append(block.code)
                is MdBlock.BulletList -> block.items.forEachIndexed { index, item ->
                    if (index > 0) out.append('\n')
                    out.append("• ").append(inlineText(item))
                }
                is MdBlock.Quote -> out.append(inlineText(block.inlines))
            }
        }
        return out.toString()
    }

    fun inlineText(inlines: List<MdInline>): String = buildString {
        for (inline in inlines) {
            when (inline) {
                is MdInline.Plain -> append(inline.text)
                is MdInline.Bold -> append(inline.text)
                is MdInline.Italic -> append(inline.text)
                is MdInline.Code -> append(inline.text)
                is MdInline.Link -> append(inline.text)
            }
        }
    }
}
