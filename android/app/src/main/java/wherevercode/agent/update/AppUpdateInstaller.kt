package wherevercode.agent.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * 一键安装的薄封装：只建 Intent，不启动、不做决定。
 *
 * 安装 APK 的 Android 规则（targetSdk 28 / minSdk 26 均适用）：
 * - 必须声明 `REQUEST_INSTALL_PACKAGES`（见 AndroidManifest）。
 * - 安装包文件不能用 `file://` URI 直接给安装器，要走 FileProvider
 *   的 content URI + `FLAG_GRANT_READ_URI_PERMISSION`。
 * - 用户必须在系统设置里给本 App 开“允许安装未知应用”
 *   （`PackageManager.canRequestPackageInstalls()`）；没开时不要静默
 *   失败——调 [unknownSourcesIntent] 带用户去开，回来再点安装。
 * - 包名必须相同，versionCode 必须更高，当前签名集合必须完全相同。
 *   缺少历史签名密钥是发布阻塞，不能通过卸载或新 debug 密钥掩盖。

 */
object AppUpdateInstaller {

    @Suppress("DEPRECATION")
    fun requireCompatiblePackage(context: Context, apkFile: File) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = try {
            context.packageManager.getPackageInfo(context.packageName, flags)
        } catch (_: Exception) {
            throw AppUpdateError.IncompatiblePackage("无法读取已安装应用的版本与签名，未安装。")
        }
        val archive = try {
            context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
        } catch (_: Exception) { null }
            ?: throw AppUpdateError.IncompatiblePackage("更新包不是可解析的 Android APK，未安装。")
        fun identity(info: PackageInfo): ApkUpgradeIdentity {
            val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return ApkUpgradeIdentity(
                packageName = info.packageName.orEmpty(),
                versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
                signerSha256 = signatures.orEmpty().map { sha256Hex(it.toByteArray()) }.toSet(),
            )
        }
        apkUpgradeRejection(identity(installed), identity(archive))?.let {
            throw AppUpdateError.IncompatiblePackage(it)
        }
    }

    /** 本 App 当前是否被允许安装未知应用（系统设置里的开关）。 */
    fun canInstallPackages(packageManager: PackageManager): Boolean {
        return try {
            packageManager.canRequestPackageInstalls()
        } catch (_: Exception) {
            false
        }
    }

    /** 打开系统安装器装 [apkFile] 的 Intent（FileProvider content URI）。 */
    fun installApkIntent(context: Context, apkFile: File): Intent {
        requireCompatiblePackage(context, apkFile)
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** 打开系统“允许安装未知应用”设置页（本 App 的那一页）的 Intent。 */
    fun unknownSourcesIntent(packageName: String): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName"),
            )
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
    }
}
