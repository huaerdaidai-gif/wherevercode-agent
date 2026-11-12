package wherevercode.agent.update

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.io.FileOutputStream

/**
 * 一键更新的编排器：检查更新、下载并校验。
 *
 * 流程（全在后台线程跑，调用方负责线程与 UI 回调）：
 * 1. `check`：拿可用 access token（过期自动 refresh）→ 读 Drive 文件
 *    元数据 → 读本机已装 APK 身份 → [decideUpdate]。
 * 2. `downloadAndVerify`：同上拿 token → 读元数据 → 流式下载到
 *    `cacheDir/app-update/wherevercode-android-debug.apk` → 校验
 *    SHA-256（Drive 元数据有 `sha256Checksum` 时必验；另验 MD5）→
 *    不符就删文件并抛 [AppUpdateError.ChecksumMismatch]，绝不交出去安装。
 *
 * 本类不做 UI、不打开浏览器、不调安装器；那是 [AppUpdateInstaller]
 * 与设置页控制器的事。
 */
class AppUpdateChecker(
    private val auth: GoogleDriveAuth,
    private val source: DriveUpdateSource,
    private val appContext: Context,
    private val installedApkInfoProvider: InstalledApkInfoProvider =
        PackageManagerInstalledApkInfoProvider(appContext),
) {
    /** 检查更新。未配 client id / 未登录时抛对应 [AppUpdateError]。 */
    suspend fun check(clientId: String): UpdateDecision {
        requireClientId(clientId)
        val accessToken = auth.ensureAccessToken(clientId)
        val remote = source.fetchMetadata(accessToken)
        val installed = installedApkInfoProvider.installedApkInfo()
        return decideUpdate(remote, installed)
    }

    /**
     * 下载并校验。返回校验通过的 APK 文件（调用方再调安装器）。
     * 校验不通过时文件已删，抛 [AppUpdateError.ChecksumMismatch]。
     */
    suspend fun downloadAndVerify(
        clientId: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): File {
        requireClientId(clientId)
        val accessToken = auth.ensureAccessToken(clientId)
        val remote = source.fetchMetadata(accessToken)
        val outFile = downloadFile()
        try {
            FileOutputStream(outFile).use { out ->
                source.downloadApk(accessToken, out, onProgress)
            }
            verifyDownloaded(outFile, remote)
            return outFile
        } catch (e: Exception) {
            // 任何失败（网络中断 / 校验不符）都不留半截文件给安装器。
            try {
                outFile.delete()
            } catch (_: Exception) {
            }
            throw e
        }
    }

    /** 校验下载文件的 SHA-256 与 MD5（Drive 元数据给了哪个就验哪个；两个都没有就只验大小）。 */
    fun verifyDownloaded(file: File, remote: DriveFileInfo) {
        val expectedSha = remote.sha256Checksum?.trim()?.lowercase().orEmpty()
        if (expectedSha.isNotEmpty()) {
            val actualSha = hashFile(file, "SHA-256").lowercase()
            if (actualSha != expectedSha) {
                throw AppUpdateError.ChecksumMismatch(
                    "下载的更新包 SHA-256 与 Drive 上的不一致（可能下载损坏），已删除，未安装。",
                )
            }
        }
        val expectedMd5 = remote.md5Checksum?.trim()?.lowercase().orEmpty()
        if (expectedMd5.isNotEmpty()) {
            val actualMd5 = hashFile(file, "MD5").lowercase()
            if (actualMd5 != expectedMd5) {
                throw AppUpdateError.ChecksumMismatch(
                    "下载的更新包 MD5 与 Drive 上的不一致（可能下载损坏），已删除，未安装。",
                )
            }
        }
        if (remote.sizeBytes > 0 && file.length() != remote.sizeBytes) {
            throw AppUpdateError.ChecksumMismatch(
                "下载的更新包大小与 Drive 上的不一致（可能下载不完整），已删除，未安装。",
            )
        }
    }

    /** 下载文件的落点（App 私有缓存，FileProvider 从这里分享给安装器）。 */
    fun downloadFile(): File {
        val dir = File(appContext.cacheDir, DOWNLOAD_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, DOWNLOAD_FILE_NAME)
    }

    private fun requireClientId(clientId: String) {
        if (clientId.isBlank()) {
            throw AppUpdateError.NotConfigured(
                "未设置 Google OAuth 客户端 ID：先在设置里填入客户端 ID，才能连接 Google Drive 检查更新。",
            )
        }
    }

    companion object {
        const val DOWNLOAD_DIR_NAME = "app-update"
        const val DOWNLOAD_FILE_NAME = "wherevercode-android-debug.apk"
    }
}

/** 本机已装 APK 身份的来源（测试可替换 fake；生产读包管理器）。 */
fun interface InstalledApkInfoProvider {
    fun installedApkInfo(): InstalledApkInfo
}

/** 生产实现：从 `sourceDir` 的 APK 文件算 SHA-256 / MD5 / 大小。 */
class PackageManagerInstalledApkInfoProvider(
    private val appContext: Context,
) : InstalledApkInfoProvider {
    override fun installedApkInfo(): InstalledApkInfo {
        val apkFile = try {
            val info = appContext.packageManager.getApplicationInfo(
                appContext.packageName,
                0,
            )
            File(info.sourceDir)
        } catch (e: PackageManager.NameNotFoundException) {
            throw AppUpdateError.Transport("读不到本机已装包的信息。")
        }
        if (!apkFile.exists() || !apkFile.isFile) {
            throw AppUpdateError.Transport("读不到本机已装包的文件。")
        }
        return InstalledApkInfo(
            sha256 = hashFile(apkFile, "SHA-256"),
            md5 = hashFile(apkFile, "MD5"),
            sizeBytes = apkFile.length(),
        )
    }
}
