package wherevercode.agent.core.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException

/**
 * LanTransport 测试（JUnit4 纯 JVM 测试）。
 *
 * 覆盖：超时常量、帧编解码、指纹 pinning 纯函数。
 * NsdManager 与真实网络不在此测：本机 VM 不在 maintainer 的局域网内，
 * 真机发现 / 连接验证见回报里的“局域网真机验证步骤清单”，在设备端跑。
 */
class LanTransportTest {

    @Test
    fun timeoutConstants_areAsSpecified() {
        // 契约：连接 5s、读 30s。
        assertEquals(5_000, LanTransport.CONNECT_TIMEOUT_MS)
        assertEquals(30_000, LanTransport.READ_TIMEOUT_MS)
    }

    @Test
    fun frameRoundTrip_twoFrames() {
        val out = ByteArrayOutputStream()
        writeFrame(out, "hello".toByteArray(Charsets.UTF_8))
        writeFrame(out, byteArrayOf(0, 1, 2, 3))
        val input = ByteArrayInputStream(out.toByteArray())
        assertArrayEquals("hello".toByteArray(Charsets.UTF_8), readFrame(input))
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), readFrame(input))
        assertNull(readFrame(input))
    }

    @Test
    fun frameRoundTrip_emptyPayload() {
        val out = ByteArrayOutputStream()
        writeFrame(out, byteArrayOf())
        val input = ByteArrayInputStream(out.toByteArray())
        assertArrayEquals(byteArrayOf(), readFrame(input))
        assertNull(readFrame(input))
    }

    @Test
    fun readFrame_cleanEof_returnsNull() {
        assertNull(readFrame(ByteArrayInputStream(byteArrayOf())))
    }

    @Test(expected = EOFException::class)
    fun readFrame_truncatedHeader_throws() {
        readFrame(ByteArrayInputStream(byteArrayOf(0, 0, 0)))
    }

    @Test(expected = EOFException::class)
    fun readFrame_truncatedBody_throws() {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, 5))
        out.write(byteArrayOf(1, 2))
        readFrame(ByteArrayInputStream(out.toByteArray()))
    }

    @Test(expected = IOException::class)
    fun readFrame_oversizedLength_throws() {
        val out = ByteArrayOutputStream()
        val tooBig = LanTransport.MAX_FRAME_BYTES + 1
        out.write(
            byteArrayOf(
                (tooBig shr 24).toByte(),
                (tooBig shr 16).toByte(),
                (tooBig shr 8).toByte(),
                tooBig.toByte(),
            ),
        )
        readFrame(ByteArrayInputStream(out.toByteArray()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun writeFrame_oversizedPayload_throws() {
        writeFrame(
            ByteArrayOutputStream(),
            ByteArray(LanTransport.MAX_FRAME_BYTES + 1),
        )
    }

    @Test
    fun pinnedFingerprint_matchPassesMismatchFails() {
        val key = "device-tls-public-key".toByteArray(Charsets.UTF_8)
        val fp = Discovery.fingerprintSha256Hex(key)
        assertEquals(true, Discovery.verifyPinnedFingerprint(key, fp))
        val otherKey = "another-key".toByteArray(Charsets.UTF_8)
        assertEquals(false, Discovery.verifyPinnedFingerprint(otherKey, fp))
    }
}
