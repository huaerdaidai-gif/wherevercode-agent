package wherevercode.agent.core.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/** Seat uplink endpoint derivation mirrors the MCP endpoint rules (phase 3). */
class SeatEndpointTest {
    @Test
    fun `public host uses wss`() {
        assertEquals(
            "wss://v5.cococolo.xyz/api/open/v1/seat",
            PlatformAgent.seatEndpoint("v5.cococolo.xyz"),
        )
    }

    @Test
    fun `lan host with port uses ws`() {
        assertEquals(
            "ws://192.168.1.4:18745/api/open/v1/seat",
            PlatformAgent.seatEndpoint("192.168.1.4:18745"),
        )
    }

    @Test
    fun `tailnet host uses ws`() {
        assertEquals(
            "ws://100.75.41.0:8443/api/open/v1/seat",
            PlatformAgent.seatEndpoint("100.75.41.0:8443"),
        )
    }

    @Test
    fun `blank or invalid host yields empty endpoint`() {
        assertEquals("", PlatformAgent.seatEndpoint(null))
        assertEquals("", PlatformAgent.seatEndpoint("   "))
        assertEquals("", PlatformAgent.seatEndpoint("https://v5.cococolo.xyz"))
    }
}
