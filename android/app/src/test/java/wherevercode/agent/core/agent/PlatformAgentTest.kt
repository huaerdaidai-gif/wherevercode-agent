package wherevercode.agent.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Platform endpoints and channel defaults are explicit, configurable values. */
class PlatformAgentTest {
    @Test
    fun `endpoint is built only from an explicit host or local build setting`() {
        assertEquals("https://example.invalid/api/open/v1/mcp", PlatformAgent.mcpEndpoint("example.invalid"))
        assertEquals("", PlatformAgent.mcpEndpoint(null))
        assertEquals("", PlatformAgent.mcpEndpoint("   "))
    }

    @Test
    fun `host is trimmed before building the endpoint`() {
        assertEquals("https://us.example/api/open/v1/mcp", PlatformAgent.mcpEndpoint("  us.example  "))
    }

    @Test
    fun `platform address accepts host and port but rejects URLs and malformed ports`() {
        assertEquals(null, PlatformAgent.platformHostIssue("platform.example:8443"))
        assertEquals("https://platform.example:8443/api/open/v1/mcp", PlatformAgent.mcpEndpoint("platform.example:8443"))
        assertTrue(PlatformAgent.platformHostIssue("https://platform.example")!!.contains("主机名或主机名:端口"))
        assertTrue(PlatformAgent.platformHostIssue("platform.example/path")!!.contains("主机名或主机名:端口"))
        assertTrue(PlatformAgent.platformHostIssue("platform.example:")!!.contains("主机名或主机名:端口"))
        assertTrue(PlatformAgent.platformHostIssue("platform.example:70000")!!.contains("格式无效"))
        assertEquals("", PlatformAgent.mcpEndpoint("https://platform.example"))
    }

    @Test
    fun `public defaults do not contain a server channel`() {
        assertEquals("", PlatformAgent.DEFAULT_CHANNEL_ID)
        assertEquals("", PlatformAgent.V5_CHANNEL_ID)
        assertEquals("", PlatformAgent.effectiveChannelId(null))
        assertFalse(PlatformAgent.isSharedChannel(null))
    }

    @Test
    fun `a configured dedicated channel is used as-is`() {
        assertEquals("channel-test", PlatformAgent.effectiveChannelId("channel-test"))
        assertFalse(PlatformAgent.isSharedChannel("channel-test"))
    }

    @Test
    fun `configuration notes identify public defaults as empty`() {
        assertTrue(PlatformAgent.ENDPOINT_NOTE.contains("不会预置平台地址"))
        assertTrue(PlatformAgent.CHANNEL_NOTE.contains("不预置频道 ID"))
        assertFalse(PlatformAgent.ENDPOINT_NOTE.contains("192.0.2.10"))
        assertFalse(PlatformAgent.CHANNEL_NOTE.contains("dedicated-channel-test"))
    }
}
