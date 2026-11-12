package wherevercode.agent.ui

/**
 * 基础语法高亮（阶段 3，U2/批 4）：纯 Kotlin，无 Android 依赖。
 *
 * 单遍扫描器：字符串、行注释、块注释、数字、关键字。返回的
 * span 互不重叠、按起始位置排序，调用方（ProjectScreenController）
 * 直接按 span 区间上色；未覆盖的区间就是普通代码色。
 * 只做“基础”高亮：不做类型推断、不解析完整语法，绝不在高亮
 * 失败时改动原文。
 */
object CodeHighlight {

    enum class Kind {
        KEYWORD,
        STRING,
        COMMENT,
        NUMBER,
    }

    data class Span(val start: Int, val end: Int, val kind: Kind)

    private val KOTLIN_KEYWORDS = setOf(
        "package", "import", "class", "object", "interface", "fun", "val", "var",
        "if", "else", "when", "for", "while", "do", "return", "break", "continue",
        "true", "false", "null", "this", "super", "is", "in", "as", "try", "catch",
        "finally", "throw", "private", "public", "internal", "protected", "open",
        "override", "abstract", "data", "sealed", "enum", "companion", "init",
        "by", "lateinit", "const", "suspend", "inline", "out", "where", "typealias",
    )
    private val PYTHON_KEYWORDS = setOf(
        "def", "class", "import", "from", "return", "if", "elif", "else", "for",
        "while", "try", "except", "finally", "with", "as", "pass", "break",
        "continue", "True", "False", "None", "and", "or", "not", "in", "is",
        "lambda", "yield", "raise", "global", "nonlocal", "assert", "del",
    )
    private val JS_KEYWORDS = setOf(
        "function", "const", "let", "var", "return", "if", "else", "for", "while",
        "do", "switch", "case", "break", "continue", "true", "false", "null",
        "undefined", "this", "new", "class", "extends", "import", "from", "export",
        "default", "try", "catch", "finally", "throw", "typeof", "instanceof",
        "async", "await", "of", "in",
    )

    fun languageFor(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "py" -> "python"
            "js", "mjs", "cjs" -> "javascript"
            "ts", "tsx" -> "typescript"
            else -> "text"
        }
    }

    fun highlight(code: String, language: String): List<Span> {
        val keywords = when (language) {
            "kotlin", "java" -> KOTLIN_KEYWORDS
            "python" -> PYTHON_KEYWORDS
            "javascript", "typescript" -> JS_KEYWORDS
            else -> return emptyList()
        }
        val lineComment = if (language == "python") "#" else "//"
        val spans = mutableListOf<Span>()
        var i = 0
        val n = code.length
        while (i < n) {
            val c = code[i]
            when {
                code.startsWith(lineComment, i) -> {
                    var end = code.indexOf('\n', i)
                    if (end == -1) end = n
                    spans += Span(i, end, Kind.COMMENT)
                    i = end
                }
                language != "python" && code.startsWith("/*", i) -> {
                    val close = code.indexOf("*/", i + 2)
                    val end = if (close == -1) n else close + 2
                    spans += Span(i, end, Kind.COMMENT)
                    i = end
                }
                c == '"' || c == '\'' || c == '`' -> {
                    val triple = language == "python" &&
                        i + 2 < n && code[i + 1] == c && code[i + 2] == c
                    val end = scanString(code, i, c, triple)
                    spans += Span(i, end, Kind.STRING)
                    i = end
                }
                c.isDigit() -> {
                    var end = i + 1
                    while (end < n && (code[end].isLetterOrDigit() || code[end] == '.' || code[end] == '_')) {
                        end++
                    }
                    spans += Span(i, end, Kind.NUMBER)
                    i = end
                }
                c.isLetter() || c == '_' -> {
                    var end = i + 1
                    while (end < n && (code[end].isLetterOrDigit() || code[end] == '_')) {
                        end++
                    }
                    if (code.substring(i, end) in keywords) {
                        spans += Span(i, end, Kind.KEYWORD)
                    }
                    i = end
                }
                else -> i++
            }
        }
        return spans
    }

    /** 扫描字符串字面量（含转义；Python 三引号跨行）；返回结束位置（ exclusive ）。 */
    private fun scanString(code: String, start: Int, quote: Char, triple: Boolean): Int {
        var i = start + if (triple) 3 else 1
        val n = code.length
        while (i < n) {
            val c = code[i]
            if (c == '\\' && i + 1 < n) {
                i += 2
                continue
            }
            if (triple) {
                if (c == quote && i + 2 < n && code[i + 1] == quote && code[i + 2] == quote) {
                    return i + 3
                }
                i++
                continue
            }
            if (c == quote) return i + 1
            if (c == '\n') return i // 未闭合：字符串只到行尾，不吞掉后面
            i++
        }
        return n
    }
}
