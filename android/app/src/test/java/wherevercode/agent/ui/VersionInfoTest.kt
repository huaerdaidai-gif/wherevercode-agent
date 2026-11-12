package wherevercode.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5 C1：真实版本信息格式化（只断言格式与回退行为，不断言具体 SHA）。 */
class VersionInfoTest {

    private val fullSha = "abcdef0123456789abcdef0123456789abcdef01"

    @Test
    fun `detail lines are non-empty and carry both fields`() {
        val lines = VersionInfo.detailLines(fullSha, "2026-11-09T14:32:10.123Z")
        assertTrue(lines.isNotBlank())
        assertTrue(lines.contains("提交："))
        assertTrue(lines.contains("构建时间："))
        assertTrue(lines.contains("2026-11-09T14:32:10.123Z"))
    }

    @Test
    fun `short sha takes first seven hex chars`() {
        assertEquals("abcdef0", VersionInfo.shortSha(fullSha))
        assertEquals("ABCDEF0", VersionInfo.shortSha("ABCDEF0123456789abcdef0123456789abcdef01"))
    }

    @Test
    fun `unknown sha stays unknown instead of garbage`() {
        assertEquals("unknown", VersionInfo.shortSha("unknown"))
        assertEquals("unknown", VersionInfo.shortSha("UNKNOWN"))
        assertEquals("unknown", VersionInfo.shortSha("  unknown  "))
        val lines = VersionInfo.detailLines("unknown", "2026-11-09T14:32:10.123Z")
        assertTrue(lines.contains("提交：unknown"))
    }

    @Test
    fun `blank or too-short sha degrades to unknown`() {
        assertEquals("unknown", VersionInfo.shortSha(""))
        assertEquals("unknown", VersionInfo.shortSha("   "))
        assertEquals("unknown", VersionInfo.shortSha("abc123"))
    }

    @Test
    fun `non-hex sha degrades to unknown`() {
        assertEquals("unknown", VersionInfo.shortSha("zzzzzzz1234567890abcdef1234567890abcdef"))
    }

    @Test
    fun `blank build time shows unknown label instead of blank`() {
        val lines = VersionInfo.detailLines(fullSha, "   ")
        assertTrue(lines.contains("构建时间：未知"))
    }

    @Test
    fun `about text keeps the version line first and appends details`() {
        val versionLine = "WhereverCode 超级 Agent · 0.2.0-ui2（2）"
        val text = VersionInfo.aboutText(versionLine, fullSha, "2026-11-09T14:32:10.123Z")
        assertTrue(text.startsWith(versionLine))
        assertTrue(text.contains("\n提交：abcdef0\n构建时间：2026-11-09T14:32:10.123Z"))
    }

    @Test
    fun `about text with unknown values never crashes or shows garbage`() {
        val text = VersionInfo.aboutText("WhereverCode 超级 Agent", "unknown", "")
        assertEquals("WhereverCode 超级 Agent\n提交：unknown\n构建时间：未知", text)
    }
}
