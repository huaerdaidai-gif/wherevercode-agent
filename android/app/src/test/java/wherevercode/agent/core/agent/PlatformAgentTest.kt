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
        assertTrue(PlatformAgent.ENDPOINT_NOTE.contains("受信任 HTTPS 证书"))
        assertTrue(PlatformAgent.ENDPOINT_NOTE.contains("自签名证书会被拒绝"))
        assertTrue(PlatformAgent.CHANNEL_NOTE.contains("不预置频道 ID"))
        assertFalse(PlatformAgent.ENDPOINT_NOTE.contains("192.0.2.10"))
        assertFalse(PlatformAgent.CHANNEL_NOTE.contains("dedicated-channel-test"))
    }

    @Test
    fun `lan addresses build http endpoints while public hosts stay https`() {
        // 频道三层二期：内网服务没有公网证书，私有/环回/尾网走 http。
        assertEquals("http://192.168.1.7:18744/api/open/v1/mcp", PlatformAgent.mcpEndpoint("192.168.1.7:18744"))
        assertEquals("http://10.0.0.5/api/open/v1/mcp", PlatformAgent.mcpEndpoint("10.0.0.5"))
        assertEquals("http://172.16.0.9:18744/api/open/v1/mcp", PlatformAgent.mcpEndpoint("172.16.0.9:18744"))
        assertEquals("http://100.94.147.89:18744/api/open/v1/mcp", PlatformAgent.mcpEndpoint("100.94.147.89:18744"))
        assertEquals("http://127.0.0.1:18744/api/open/v1/mcp", PlatformAgent.mcpEndpoint("127.0.0.1:18744"))
        assertEquals("http://localhost:18744/api/open/v1/mcp", PlatformAgent.mcpEndpoint("localhost:18744"))
        // 段外地址不许蹭 http：172.32 不在 172.16/12，100.128 不在 100.64/10。
        assertEquals("https://172.32.0.9/api/open/v1/mcp", PlatformAgent.mcpEndpoint("172.32.0.9"))
        assertEquals("https://100.128.0.1/api/open/v1/mcp", PlatformAgent.mcpEndpoint("100.128.0.1"))
        assertEquals("https://8.8.8.8/api/open/v1/mcp", PlatformAgent.mcpEndpoint("8.8.8.8"))
    }

    @Test
    fun `lan ws target splits host and port with http default`() {
        assertEquals("192.168.1.7" to 18744, PlatformAgent.lanWsTarget("192.168.1.7:18744"))
        assertEquals("192.168.1.7" to 80, PlatformAgent.lanWsTarget("192.168.1.7"))
        assertEquals("localhost" to 18744, PlatformAgent.lanWsTarget(" localhost:18744 "))
    }
}
