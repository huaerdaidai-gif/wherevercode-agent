package wherevercode.agent.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 更新纯逻辑测试：判定、校验和、格式化、元数据解析。
 * 无 Android 依赖，JVM 单测。测试向量是公开标准值。
 */
class AppUpdateTest {
    private val fixtureDriveFileId = "test-update-file-id"

    @Test
    fun `sha256 of abc matches the known vector`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun `md5 of abc matches the known vector`() {
        assertEquals(
            "900150983cd24fb0d6963f7d28e17f72",
            md5Hex("abc".toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun `hashFile matches the in-memory digest`() {
        val file = File.createTempFile("app-update-test", ".bin")
        try {
            file.writeBytes("abc".toByteArray(Charsets.UTF_8))
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                hashFile(file, "SHA-256"),
            )
            assertEquals(
                "900150983cd24fb0d6963f7d28e17f72",
                hashFile(file, "MD5"),
            )
        } finally {
            file.delete()
        }
    }

    private fun remote(
        sha256: String? = "aabbcc",
        md5: String? = "ddeeff",
        size: Long = 100L,
    ) = DriveFileInfo(
        id = fixtureDriveFileId,
        name = "wherevercode-android-debug.apk",
        sizeBytes = size,
        modifiedTime = "2026-11-11T05:07:16.419Z",
        md5Checksum = md5,
        sha256Checksum = sha256,
    )

    private fun installed(
        sha256: String = "aabbcc",
        md5: String = "ddeeff",
        size: Long = 100L,
    ) = InstalledApkInfo(sha256 = sha256, md5 = md5, sizeBytes = size)

    @Test
    fun `same sha256 is up to date`() {
        assertTrue(decideUpdate(remote(), installed()) is UpdateDecision.UpToDate)
    }

    @Test
    fun `different sha256 is an update even when size matches`() {
        val decision = decideUpdate(remote(), installed(sha256 = "001122"))
        assertTrue(decision is UpdateDecision.UpdateAvailable)
    }

    @Test
    fun `sha256 comparison ignores case`() {
        assertTrue(
            decideUpdate(remote(sha256 = "AABBCC"), installed()) is UpdateDecision.UpToDate,
        )
    }

    @Test
    fun `no sha256 falls back to md5`() {
        assertTrue(
            decideUpdate(remote(sha256 = null), installed()) is UpdateDecision.UpToDate,
        )
        assertTrue(
            decideUpdate(
                remote(sha256 = null),
                installed(md5 = "001122"),
            ) is UpdateDecision.UpdateAvailable,
        )
    }

    @Test
    fun `no checksums falls back to size`() {
        assertTrue(
            decideUpdate(
                remote(sha256 = null, md5 = null, size = 200L),
                installed(size = 100L),
            ) is UpdateDecision.UpdateAvailable,
        )
    }

    @Test
    fun `no checksums and same size cannot tell`() {
        val decision = decideUpdate(
            remote(sha256 = null, md5 = null, size = 100L),
            installed(size = 100L),
        )
        assertTrue(decision is UpdateDecision.CannotTell)
    }

    @Test
    fun `formatBytes uses B KB MB`() {
        assertEquals("0 B", formatBytes(0L))
        assertEquals("1023 B", formatBytes(1023L))
        assertEquals("1.5 KB", formatBytes(1536L))
        assertEquals("51.9 MB", formatBytes(54_432_757L))
    }

    @Test
    fun `parseMetadata reads all fields`() {
        val info = DriveUpdateSource.parseMetadata(
            """{"id":"$fixtureDriveFileId","name":"wherevercode-android-debug.apk","size":"54432757","modifiedTime":"2026-11-11T05:07:16.419Z","md5Checksum":"abc123","sha256Checksum":"def456"}""",
        )
        assertEquals(fixtureDriveFileId, info.id)
        assertEquals(54_432_757L, info.sizeBytes)
        assertEquals("2026-11-11T05:07:16.419Z", info.modifiedTime)
        assertEquals("abc123", info.md5Checksum)
        assertEquals("def456", info.sha256Checksum)
    }

    @Test
    fun `parseMetadata tolerates missing checksums`() {
        val info = DriveUpdateSource.parseMetadata(
            """{"id":"x","name":"a.apk","size":"10","modifiedTime":"2026-11-11T00:00:00Z"}""",
        )
        assertEquals(null, info.md5Checksum)
        assertEquals(null, info.sha256Checksum)
        assertEquals(10L, info.sizeBytes)
    }

    @Test
    fun `shortChecksum takes 8 chars`() {
        assertEquals("55139d6e", shortChecksum("55139d6e55ee6bf86da67125a89f957f8b3c02b5914fb5bd89b3845f73834a57"))
        assertEquals("abc", shortChecksum("abc"))
    }
}
