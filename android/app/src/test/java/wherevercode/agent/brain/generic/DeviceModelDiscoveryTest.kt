package wherevercode.agent.brain.generic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 自动发现的候选端点构造：纯函数部分逐项断言，不依赖网络。 */
class DeviceModelDiscoveryTest {

    @Test
    fun `candidate urls cover the three common local servers`() {
        val candidates = DeviceModelDiscovery.candidateBaseUrls("192.168.1.4")
        assertEquals(
            listOf(
                "Ollama" to "http://192.168.1.4:11434/v1",
                "LM Studio" to "http://192.168.1.4:1234/v1",
                "vLLM" to "http://192.168.1.4:8000/v1",
            ),
            candidates,
        )
    }

    @Test
    fun `host is trimmed of whitespace and trailing slash`() {
        val candidates = DeviceModelDiscovery.candidateBaseUrls(" 127.0.0.1/ ")
        assertTrue(candidates.all { it.second.startsWith("http://127.0.0.1:") })
    }

    @Test
    fun `endpoint ports are the official defaults`() {
        val ports = DeviceModelDiscovery.ENDPOINTS.associate { it.serviceName to it.port }
        assertEquals(11434, ports["Ollama"])
        assertEquals(1234, ports["LM Studio"])
        assertEquals(8000, ports["vLLM"])
    }
}
