package wherevercode.agent.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * DriveUpdateSource 测试：fake HTTP 端口，不碰真 Drive。
 */
class DriveUpdateSourceTest {
    private val fixtureDriveFileId = "test-update-file-id"

    private class FakePort(
        val metadataStatus: Int = 200,
        val metadataBody: String = METADATA_JSON,
        val downloadStatus: Int = 200,
        val downloadBytes: ByteArray = "apk-bytes".toByteArray(Charsets.UTF_8),
    ) : DriveHttpPort {
        var lastGetUrl: String? = null
        var lastDownloadUrl: String? = null
        var lastAccessToken: String? = null

        override fun getJson(url: String, accessToken: String): DriveHttpResponse {
            lastGetUrl = url
            lastAccessToken = accessToken
            return DriveHttpResponse(metadataStatus, metadataBody)
        }

        override fun download(
            url: String,
            accessToken: String,
            out: java.io.OutputStream,
            onProgress: (Long, Long) -> Unit,
        ): Long {
            lastDownloadUrl = url
            lastAccessToken = accessToken
            if (downloadStatus != 200) {
                throw DriveHttpStatusException(downloadStatus, "{}")
            }
            out.write(downloadBytes)
            onProgress(downloadBytes.size.toLong(), downloadBytes.size.toLong())
            return downloadBytes.size.toLong()
        }
    }

    @Test
    fun `fetchMetadata parses the file and uses the fixed file id`() {
        val port = FakePort()
        val source = DriveUpdateSource(port, fixtureDriveFileId)
        val info = source.fetchMetadata("token-1")
        assertEquals(fixtureDriveFileId, info.id)
        assertEquals(54_432_757L, info.sizeBytes)
        assertEquals("abc-md5", info.md5Checksum)
        assertEquals("def-sha256", info.sha256Checksum)
        assertTrue(port.lastGetUrl!!.contains("/files/$fixtureDriveFileId"))
        assertTrue(port.lastGetUrl!!.contains("fields="))
        assertEquals("token-1", port.lastAccessToken)
    }

    @Test
    fun `fetchMetadata 401 is AuthFailed`() {
        val source = DriveUpdateSource(FakePort(metadataStatus = 401, metadataBody = "{}"), fixtureDriveFileId)
        try {
            source.fetchMetadata("token-1")
            fail("expected AuthFailed")
        } catch (e: AppUpdateError.AuthFailed) {
            assertTrue(e.message!!.contains("重新连接"))
        }
    }

    @Test
    fun `fetchMetadata 404 is NotFound`() {
        val source = DriveUpdateSource(FakePort(metadataStatus = 404, metadataBody = "{}"), fixtureDriveFileId)
        try {
            source.fetchMetadata("token-1")
            fail("expected NotFound")
        } catch (e: AppUpdateError.NotFound) {
            assertTrue(e.message!!.contains("找不到"))
        }
    }

    @Test
    fun `fetchMetadata 500 is Transport`() {
        val source = DriveUpdateSource(FakePort(metadataStatus = 500, metadataBody = "{}"), fixtureDriveFileId)
        try {
            source.fetchMetadata("token-1")
            fail("expected Transport")
        } catch (e: AppUpdateError.Transport) {
            assertTrue(e.message!!.contains("稍后重试"))
        }
    }

    @Test
    fun `downloadApk writes bytes and reports progress`() {
        val port = FakePort()
        val source = DriveUpdateSource(port, fixtureDriveFileId)
        val out = ByteArrayOutputStream()
        var progressCalls = 0
        val bytes = source.downloadApk("token-1", out) { _, _ -> progressCalls++ }
        assertEquals("apk-bytes".toByteArray(Charsets.UTF_8).size.toLong(), bytes)
        assertEquals("apk-bytes", out.toString(Charsets.UTF_8))
        assertTrue(progressCalls >= 1)
        assertTrue(port.lastDownloadUrl!!.contains("alt=media"))
    }

    @Test
    fun `downloadApk 401 is AuthFailed`() {
        val source = DriveUpdateSource(FakePort(downloadStatus = 401), fixtureDriveFileId)
        try {
            source.downloadApk("token-1", ByteArrayOutputStream())
            fail("expected AuthFailed")
        } catch (e: AppUpdateError.AuthFailed) {
            assertTrue(e.message!!.contains("重新连接"))
        }
    }

    companion object {
        const val METADATA_JSON =
            """{"id":"test-update-file-id","name":"wherevercode-android-debug.apk","size":"54432757","modifiedTime":"2026-11-11T05:07:16.419Z","md5Checksum":"abc-md5","sha256Checksum":"def-sha256"}"""
    }
}
