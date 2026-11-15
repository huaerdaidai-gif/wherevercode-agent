package wherevercode.agent.core.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T6 结构化结果协议 v1 纯函数单测（2026-11-15）：不依赖 Android、
 * 不依赖 Robolectric，纯 JVM 可跑。规则口径见 T6-S2 §3/§4。
 */
class RemoteResultProtocolTest {

    @Test
    fun `known statuses are exactly the closed set`() {
        assertEquals(
            setOf("succeeded", "failed", "cancelled"),
            RemoteResultProtocol.KNOWN_STATUSES,
        )
        assertTrue(RemoteResultProtocol.isKnownResultStatus("succeeded"))
        assertTrue(RemoteResultProtocol.isKnownResultStatus("failed"))
        assertTrue(RemoteResultProtocol.isKnownResultStatus("cancelled"))
        assertFalse(RemoteResultProtocol.isKnownResultStatus("banana"))
        assertFalse(RemoteResultProtocol.isKnownResultStatus(""))
        assertFalse(RemoteResultProtocol.isKnownResultStatus(null))
    }

    @Test
    fun `missing or blank status needs legacy fallback`() {
        assertTrue(
            RemoteResultProtocol.resolveStructured(null, null, null, "正文") is
                RemoteResultProtocol.StructuredVerdict.NeedsLegacyFallback,
        )
        assertTrue(
            RemoteResultProtocol.resolveStructured("", null, null, "正文") is
                RemoteResultProtocol.StructuredVerdict.NeedsLegacyFallback,
        )
        assertTrue(
            RemoteResultProtocol.resolveStructured("   ", null, null, "正文") is
                RemoteResultProtocol.StructuredVerdict.NeedsLegacyFallback,
        )
    }

    @Test
    fun `succeeded without error code is structured success`() {
        assertEquals(
            RemoteResultProtocol.StructuredVerdict.Succeeded,
            RemoteResultProtocol.resolveStructured("succeeded", null, null, "正文"),
        )
        assertEquals(
            RemoteResultProtocol.StructuredVerdict.Succeeded,
            RemoteResultProtocol.resolveStructured("succeeded", "", "", "正文"),
        )
    }

    @Test
    fun `succeeded with error code is contradictory and invalid`() {
        val verdict = RemoteResultProtocol.resolveStructured(
            "succeeded", "agent_exit_nonzero", null, "正文",
        )
        assertTrue(verdict is RemoteResultProtocol.StructuredVerdict.Invalid)
        assertEquals("succeeded", (verdict as RemoteResultProtocol.StructuredVerdict.Invalid).rawStatus)
    }

    @Test
    fun `unknown status value is invalid never success`() {
        val verdict = RemoteResultProtocol.resolveStructured("banana", null, null, "正文")
        assertTrue(verdict is RemoteResultProtocol.StructuredVerdict.Invalid)
        assertEquals("banana", (verdict as RemoteResultProtocol.StructuredVerdict.Invalid).rawStatus)
    }

    @Test
    fun `failed defaults to agent_exit_nonzero and uses output as message`() {
        val verdict = RemoteResultProtocol.resolveStructured("failed", null, null, "退出码 3 的正文")
        assertTrue(verdict is RemoteResultProtocol.StructuredVerdict.Failed)
        verdict as RemoteResultProtocol.StructuredVerdict.Failed
        assertEquals(RemoteResultProtocol.CODE_AGENT_EXIT_NONZERO, verdict.code)
        assertEquals("退出码 3 的正文", verdict.message)
    }

    @Test
    fun `failed keeps frame code and error message when present`() {
        val verdict = RemoteResultProtocol.resolveStructured(
            "failed", "agent_signal_killed", "Agent 被信号终止（退出码 -9）。", "stdout 全文",
        )
        verdict as RemoteResultProtocol.StructuredVerdict.Failed
        assertEquals("agent_signal_killed", verdict.code)
        assertEquals("Agent 被信号终止（退出码 -9）。", verdict.message)
    }

    @Test
    fun `cancelled passes the frame code through`() {
        val withCode = RemoteResultProtocol.resolveStructured(
            "cancelled", "remote_controlled_stop", "已按控制指令停止。", "",
        ) as RemoteResultProtocol.StructuredVerdict.Cancelled
        assertEquals("remote_controlled_stop", withCode.code)
        val withoutCode = RemoteResultProtocol.resolveStructured(
            "cancelled", null, null, "",
        ) as RemoteResultProtocol.StructuredVerdict.Cancelled
        assertEquals(null, withoutCode.code)
    }

    @Test
    fun `error prefix is generated from constants and empty code adds nothing`() {
        assertEquals(
            "[error_code=remote_result_unknown] ",
            RemoteResultProtocol.errorPrefix(RemoteResultProtocol.CODE_REMOTE_RESULT_UNKNOWN),
        )
        assertEquals("", RemoteResultProtocol.errorPrefix(null))
        assertEquals("", RemoteResultProtocol.errorPrefix(""))
        assertEquals(
            "[error_code=remote_result_unknown] 请稍后在远端确认。",
            RemoteResultProtocol.errorText(
                RemoteResultProtocol.CODE_REMOTE_RESULT_UNKNOWN,
                "请稍后在远端确认。",
            ),
        )
        assertEquals(
            "[source=fallback] ",
            RemoteResultProtocol.sourcePrefix(RemoteResultProtocol.SOURCE_FALLBACK),
        )
    }
}
