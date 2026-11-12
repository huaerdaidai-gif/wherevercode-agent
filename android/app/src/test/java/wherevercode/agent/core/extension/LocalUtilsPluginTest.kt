package wherevercode.agent.core.extension.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地小工具插件测试（纯 JVM）：时间真实返回、算式真实求值。
 */
class LocalUtilsPluginTest {

    private val plugin = LocalUtilsPlugin()

    @Test
    fun `plugin manifest lists two read-only tools`() {
        assertEquals("local-utils", plugin.id)
        assertEquals("1", plugin.version)
        assertEquals(listOf("local_time", "calc"), plugin.tools.map { it.name })
        assertTrue(plugin.tools.all { it.readOnly })
    }

    @Test
    fun `local_time returns a parseable timestamp`() {
        val outcome = plugin.execute("local_time", emptyMap())
        assertTrue(outcome.ok)
        // "本地时间 2026-11-11 09:41:23（...，epoch ...）"
        assertTrue(outcome.data!!.contains("本地时间"))
        assertTrue(outcome.data!!.contains("epoch"))
        val epoch = outcome.data!!.substringAfter("epoch ").trimEnd('）').trim().toLong()
        val now = System.currentTimeMillis() / 1000
        assertTrue(kotlin.math.abs(now - epoch) < 5)
    }

    @Test
    fun `calc evaluates arithmetic with precedence and parentheses`() {
        assertCalc("2+3*4", "14")
        assertCalc("(2+3)*4", "20")
        assertCalc("10/4", "2.5")
        assertCalc("10%3", "1")
        assertCalc("-5+3", "-2")
        assertCalc("2.5*4", "10")
        assertCalc(" 1 + 2 ", "3")
    }

    @Test
    fun `calc rejects bad input instead of faking a result`() {
        val bad = plugin.execute("calc", mapOf("expression" to "2+"))
        assertFalse(bad.ok)
        assertEquals("calc_error", bad.errorCode)
        val divZero = plugin.execute("calc", mapOf("expression" to "1/0"))
        assertFalse(divZero.ok)
        assertEquals("calc_error", divZero.errorCode)
        val injected = plugin.execute("calc", mapOf("expression" to "1; rm -rf /"))
        assertFalse(injected.ok)
        assertEquals("calc_error", injected.errorCode)
        val missing = plugin.execute("calc", emptyMap())
        assertFalse(missing.ok)
        assertEquals("invalid_action", missing.errorCode)
    }

    @Test
    fun `unknown tool is an error not a crash`() {
        val outcome = plugin.execute("nope", emptyMap())
        assertFalse(outcome.ok)
        assertEquals("invalid_action", outcome.errorCode)
    }

    private fun assertCalc(expression: String, expected: String) {
        val outcome = plugin.execute("calc", mapOf("expression" to expression))
        assertTrue("calc($expression) should succeed: ${outcome.summary}", outcome.ok)
        assertEquals(expected, outcome.data)
    }
}
