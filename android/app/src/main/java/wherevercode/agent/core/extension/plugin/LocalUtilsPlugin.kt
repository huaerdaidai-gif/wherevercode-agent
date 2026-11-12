package wherevercode.agent.core.extension.plugin

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import wherevercode.agent.brain.ToolOutcome

/**
 * 本地小工具插件（App 内置示例插件，第一版，2026-11-11）。
 *
 * 两个真实工具（都只读、无副作用）：
 * - local_time：本机当前时间（日期 + 时区 + epoch 秒）。
 * - calc：四则运算（+ - * / %、括号、一元正负号、小数），自研
 *   递归下降求值器，不用脚本引擎、不执行任意代码。
 *
 * 注册表里的对应行：kind=plugin，id=local-utils，version=1。
 */
class LocalUtilsPlugin : ExtensionPlugin {
    override val id: String = "local-utils"
    override val displayName: String = "本地小工具"
    override val version: String = "1"

    override val tools: List<PluginTool> = listOf(
        PluginTool(
            name = "local_time",
            description = "返回本机当前时间（日期、时区、epoch 秒）。",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to emptyMap<String, Any?>(),
            ),
            readOnly = true,
        ),
        PluginTool(
            name = "calc",
            description = "计算一个四则运算表达式（支持 + - * / %、括号、小数），返回数值结果。",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "expression" to mapOf(
                        "type" to "string",
                        "description" to "要计算的表达式，例如 (2+3)*4。",
                    ),
                ),
                "required" to listOf("expression"),
            ),
            readOnly = true,
        ),
    )

    override fun execute(toolName: String, args: Map<String, Any?>): ToolOutcome {
        return when (toolName) {
            "local_time" -> {
                val now = Date()
                val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val zone = TimeZone.getDefault()
                val text = "本地时间 ${format.format(now)}（${zone.id}，epoch ${now.time / 1000}）"
                ToolOutcome(ok = true, summary = text, data = text)
            }
            "calc" -> {
                val expression = (args["expression"] as? String).orEmpty()
                if (expression.isBlank()) {
                    return ToolOutcome(ok = false, summary = "缺少参数 expression（须为非空字符串）", errorCode = "invalid_action")
                }
                val result = CalcEvaluator.evaluate(expression)
                if (result is CalcEvaluator.Failure) {
                    ToolOutcome(ok = false, summary = "算式无效：${result.reason}", errorCode = "calc_error")
                } else {
                    val value = (result as CalcEvaluator.Value).value
                    val text = formatNumber(value)
                    ToolOutcome(ok = true, summary = "$expression = $text", data = text)
                }
            }
            else -> ToolOutcome(ok = false, summary = "未知插件工具：$toolName", errorCode = "invalid_action")
        }
    }

    private fun formatNumber(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return value.toString()
        val asLong = value.toLong()
        return if (value == asLong.toDouble() && asLong in -9_000_000_000_000_000L..9_000_000_000_000_000L) {
            asLong.toString()
        } else {
            value.toString()
        }
    }
}

/**
 * 四则运算求值器（自研递归下降，文法：
 * expr := term (('+'|'-') term)*；term := factor (('*'|'/'|'%') factor)*；
 * factor := ('+'|'-') factor | number | '(' expr ')'）。
 * 只认数字、运算符、括号与空白；其余字符一律报错，不执行任何代码。
 */
object CalcEvaluator {
    sealed class Outcome
    data class Value(val value: Double) : Outcome()
    data class Failure(val reason: String) : Outcome()

    fun evaluate(expression: String): Outcome {
        val parser = Parser(expression)
        return try {
            val value = parser.parseExpr()
            parser.skipWhitespace()
            if (!parser.atEnd()) {
                Failure("位置 ${parser.pos + 1} 处有多余字符“${parser.peek()}”")
            } else {
                Value(value)
            }
        } catch (e: CalcError) {
            Failure(e.message ?: "算式无效")
        }
    }

    private class CalcError(message: String) : Exception(message)

    private class Parser(private val input: String) {
        var pos: Int = 0

        fun atEnd(): Boolean {
            skipWhitespace()
            return pos >= input.length
        }

        fun peek(): Char = if (pos < input.length) input[pos] else '\u0000'

        fun skipWhitespace() {
            while (pos < input.length && input[pos].isWhitespace()) pos++
        }

        fun parseExpr(): Double {
            var value = parseTerm()
            while (true) {
                skipWhitespace()
                when (peek()) {
                    '+' -> {
                        pos++
                        value += parseTerm()
                    }
                    '-' -> {
                        pos++
                        value -= parseTerm()
                    }
                    else -> return value
                }
            }
        }

        private fun parseTerm(): Double {
            var value = parseFactor()
            while (true) {
                skipWhitespace()
                when (peek()) {
                    '*' -> {
                        pos++
                        value *= parseFactor()
                    }
                    '/' -> {
                        pos++
                        val divisor = parseFactor()
                        if (divisor == 0.0) throw CalcError("除数不能为 0")
                        value /= divisor
                    }
                    '%' -> {
                        pos++
                        val divisor = parseFactor()
                        if (divisor == 0.0) throw CalcError("除数不能为 0")
                        value %= divisor
                    }
                    else -> return value
                }
            }
        }

        private fun parseFactor(): Double {
            skipWhitespace()
            return when (val c = peek()) {
                '+' -> {
                    pos++
                    parseFactor()
                }
                '-' -> {
                    pos++
                    -parseFactor()
                }
                '(' -> {
                    pos++
                    val value = parseExpr()
                    skipWhitespace()
                    if (peek() != ')') throw CalcError("括号未闭合")
                    pos++
                    value
                }
                else -> parseNumber(c)
            }
        }

        private fun parseNumber(first: Char): Double {
            if (!first.isDigit() && first != '.') {
                throw CalcError("位置 ${pos + 1} 处应为数字或括号，实际是“$first”")
            }
            val start = pos
            var dots = 0
            while (pos < input.length && (input[pos].isDigit() || input[pos] == '.')) {
                if (input[pos] == '.') dots++
                pos++
            }
            val text = input.substring(start, pos)
            if (dots > 1) throw CalcError("数字“$text”里小数点太多")
            return text.toDoubleOrNull() ?: throw CalcError("数字无效：$text")
        }
    }
}
