package wherevercode.agent.update

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import wherevercode.agent.BuildConfig

/**
 * App 内一键自动更新：纯逻辑层（无 Android 依赖，可 JVM 单测）。
 *
 * Google Drive update source is optional and configured per build. Public builds
 * leave the update file id empty, which disables update checks.
 *
 * 版本信号（所有构建都是 versionCode 2 / 0.2.0-ui2，版本号区分不了构建）：
 * Drive 文件的元数据自带 `sha256Checksum` / `md5Checksum` / `size` /
 * `modifiedTime`（Drive API v3 File 资源字段，见 DriveUpdateSource），
 * 本机已装 APK 的 SHA-256 / MD5 / 大小从
 * `PackageManager.applicationInfo.sourceDir` 的文件算出。两边比校验和，
 * 相同就是最新，不同就是有新版——不用版本号，不用新服务器。
 *
 * When configured, the app uses Google Drive API v3 and the user's OAuth
 * session to read the selected file metadata and download the update package.
 */

/** Optional build-configured update file id; empty in public builds. */
val DRIVE_APK_FILE_ID: String get() = BuildConfig.UPDATE_DRIVE_FILE_ID.trim()

/** Google Drive 只读 scope：只读文件元数据与内容，不写、不删、不改分享。 */
const val DRIVE_READONLY_SCOPE = "https://www.googleapis.com/auth/drive.readonly"

/** Drive API v3 根地址。 */
const val DRIVE_API_BASE_URL = "https://www.googleapis.com/drive/v3"

/**
 * Drive 上分发文件的元数据（`files.get` 返回的字段子集）。
 * `sha256Checksum` / `md5Checksum` 只对二进制文件有；缺失时比大小。
 */
data class DriveFileInfo(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val modifiedTime: String,
    val md5Checksum: String?,
    val sha256Checksum: String?,
)

/** 本机已装 APK 的身份（从 `sourceDir` 文件算出）。 */
data class InstalledApkInfo(
    val sha256: String,
    val md5: String,
    val sizeBytes: Long,
)

/** 更新判定结果。 */
sealed class UpdateDecision {
    /** Drive 文件与本机一致，无需更新。 */
    data class UpToDate(val remote: DriveFileInfo, val installed: InstalledApkInfo) :
        UpdateDecision()

    /** Drive 文件与本机不同，有新版。 */
    data class UpdateAvailable(val remote: DriveFileInfo, val installed: InstalledApkInfo) :
        UpdateDecision()

    /** 比不出来（两边都没有校验和且大小也相同）：不谎称已是最新，也不谎称有新版。 */
    data class CannotTell(val reason: String) : UpdateDecision()
}

/**
 * 判定 Drive 文件与本机 APK 是否同一份。
 *
 * 优先级：SHA-256 > MD5 > 大小。校验和比较忽略大小写与空白；大小只有
 * 在两边都没有校验和时才作唯一依据——大小相同但校验和缺失时返回
 * [UpdateDecision.CannotTell]，不猜。
 */
fun decideUpdate(remote: DriveFileInfo, installed: InstalledApkInfo): UpdateDecision {
    val remoteSha = remote.sha256Checksum?.trim()?.lowercase().orEmpty()
    val installedSha = installed.sha256.trim().lowercase()
    if (remoteSha.isNotEmpty() && installedSha.isNotEmpty()) {
        return if (remoteSha == installedSha) {
            UpdateDecision.UpToDate(remote, installed)
        } else {
            UpdateDecision.UpdateAvailable(remote, installed)
        }
    }
    val remoteMd5 = remote.md5Checksum?.trim()?.lowercase().orEmpty()
    val installedMd5 = installed.md5.trim().lowercase()
    if (remoteMd5.isNotEmpty() && installedMd5.isNotEmpty()) {
        return if (remoteMd5 == installedMd5) {
            UpdateDecision.UpToDate(remote, installed)
        } else {
            UpdateDecision.UpdateAvailable(remote, installed)
        }
    }
    if (remote.sizeBytes != installed.sizeBytes) {
        return UpdateDecision.UpdateAvailable(remote, installed)
    }
    return UpdateDecision.CannotTell(
        "Drive 文件没有返回校验和，且大小与本机相同，无法确认是不是同一份。",
    )
}

/** 一段字节的 SHA-256（小写十六进制）。 */
fun sha256Hex(bytes: ByteArray): String = digestHex("SHA-256", bytes)

/** 一段字节的 MD5（小写十六进制）。 */
fun md5Hex(bytes: ByteArray): String = digestHex("MD5", bytes)

private fun digestHex(algorithm: String, bytes: ByteArray): String {
    val digest = MessageDigest.getInstance(algorithm)
    return digest.digest(bytes).toHex()
}

/** 流式算一个文件的摘要（APK 约 54 MB，不 whole 读进内存）。 */
fun hashFile(file: File, algorithm: String): String {
    val digest = MessageDigest.getInstance(algorithm)
    FileInputStream(file).use { input ->
        val buffer = ByteArray(64 * 1024)
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHex()
}

private fun ByteArray.toHex(): String {
    val hex = StringBuilder(size * 2)
    for (b in this) {
        hex.append(String.format("%02x", b.toInt() and 0xFF))
    }
    return hex.toString()
}

/** 字节数的人类可读写法（“54.4 MB”）。 */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

/** 短 SHA（前 8 位），用于状态行显示。 */
fun shortChecksum(checksum: String): String {
    val clean = checksum.trim()
    if (clean.length < 8) return clean
    return clean.take(8)
}
