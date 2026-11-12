package wherevercode.agent.core.install

import java.security.MessageDigest

/**
 * App 内置的 V5 Hand 安装包（assets/install/ 下的文件）。
 *
 * Bundle 来源（§25.3-1 的本轮定法）：App 内置——App 自己带安装包，
 * SSH 一键安装时由 App 上传到目标机，不从旧服务器下载、不依赖
 * 外部链接。新用户装好 App 就能加设备，不用先找安装包。
 *
 * 版本 = hand_server.py 的 SHA-256 前 12 位（内容定版本，改了
 * 文件版本就变，安装脚本拿它当 release 名）。
 */
data class HandInstallBundle(
    /** hand_server.py 的 SHA-256 前 12 位，也是 release 名。 */
    val version: String,
    /** v5-install-hand.sh（Mac/Linux 安装脚本）。 */
    val installSh: ByteArray,
    /** v5-install-hand.ps1（Windows 安装脚本）。 */
    val installPs1: ByteArray,
    /** v5-uninstall-hand.sh（Mac/Linux 卸载脚本）。 */
    val uninstallSh: ByteArray,
    /** hand_server.py（V5 Hand 服务端，与 VPS 上跑的版本同款）。 */
    val handServerPy: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HandInstallBundle) return false
        return version == other.version &&
            installSh.contentEquals(other.installSh) &&
            installPs1.contentEquals(other.installPs1) &&
            uninstallSh.contentEquals(other.uninstallSh) &&
            handServerPy.contentEquals(other.handServerPy)
    }

    override fun hashCode(): Int {
        var result = version.hashCode()
        result = 31 * result + installSh.contentHashCode()
        result = 31 * result + installPs1.contentHashCode()
        result = 31 * result + uninstallSh.contentHashCode()
        result = 31 * result + handServerPy.contentHashCode()
        return result
    }
}

/** 读 assets 的接口（可单测：传假实现，不依赖 Android）。 */
fun interface AssetReader {
    /** 读不到返回 null。 */
    fun readAsset(path: String): ByteArray?
}

object HandInstallBundleLoader {
    const val ASSET_DIR: String = "install"
    const val INSTALL_SH: String = "v5-install-hand.sh"
    const val INSTALL_PS1: String = "v5-install-hand.ps1"
    const val UNINSTALL_SH: String = "v5-uninstall-hand.sh"
    const val HAND_SERVER_PY: String = "hand_server.py"

    /** hand_server.py 内容的 SHA-256 前 12 位。 */
    fun versionOf(handServerPy: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(handServerPy)
        return digest.joinToString("") { "%02x".format(it) }.take(12)
    }

    /** 从 assets 读齐 4 个文件；缺任何一个返回 null（不装半个包）。 */
    fun load(reader: AssetReader): HandInstallBundle? {
        val installSh = reader.readAsset("$ASSET_DIR/$INSTALL_SH") ?: return null
        val installPs1 = reader.readAsset("$ASSET_DIR/$INSTALL_PS1") ?: return null
        val uninstallSh = reader.readAsset("$ASSET_DIR/$UNINSTALL_SH") ?: return null
        val handServerPy = reader.readAsset("$ASSET_DIR/$HAND_SERVER_PY") ?: return null
        if (installSh.isEmpty() || installPs1.isEmpty() || uninstallSh.isEmpty() || handServerPy.isEmpty()) return null
        return HandInstallBundle(
            version = versionOf(handServerPy),
            installSh = installSh,
            installPs1 = installPs1,
            uninstallSh = uninstallSh,
            handServerPy = handServerPy,
        )
    }
}
