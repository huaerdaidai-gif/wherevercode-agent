package wherevercode.agent.core.install

/**
 * 本机安装命令（纯逻辑，可单测）。
 *
 * 用户在目标机上自己跑的命令。安装包（hand_server.py + 安装脚本）
 * 须先传到目标机——App 里点“分享安装包”可导出 4 个文件。
 * 命令本身不从任何服务器下载文件（V5 不用旧服务器的分发）。
 */
object InstallCommand {
    /** Hand 默认端口（与 Hand 服务端 DEFAULT_PORT 一致）。 */
    const val HAND_PORT: Int = 18743

    /**
     * Mac/Linux 本机安装命令。
     * @param version release 名（HandInstallBundle.version）。
     * @param bundleDir 安装包在目标机上的目录（含 hand_server.py 与脚本）。
     * @param port Hand 监听端口。
     * @param deviceId 设备 id（可空，空 = 目标机 hostname）。
     * @param displayName 显示名（可空，空 = hostname）。
     */
    fun unixLocalCommand(
        version: String,
        bundleDir: String,
        port: Int = HAND_PORT,
        deviceId: String? = null,
        displayName: String? = null,
    ): String {
        val sb = StringBuilder()
        sb.append("cd ").append(shellQuote(bundleDir)).append(" && ")
        sb.append("sh v5-install-hand.sh --version ").append(shellQuote(version))
        sb.append(" --port ").append(port)
        if (!deviceId.isNullOrBlank()) sb.append(" --device-id ").append(shellQuote(deviceId))
        if (!displayName.isNullOrBlank()) sb.append(" --display-name ").append(shellQuote(displayName))
        return sb.toString()
    }

    /** Windows PowerShell 本机安装命令。 */
    fun windowsLocalCommand(
        version: String,
        bundleDir: String,
        port: Int = HAND_PORT,
        deviceId: String? = null,
        displayName: String? = null,
    ): String {
        val sb = StringBuilder()
        sb.append("cd ").append(psQuote(bundleDir)).append("; ")
        sb.append("powershell -ExecutionPolicy Bypass -File .\\v5-install-hand.ps1 -Version ").append(psQuote(version))
        sb.append(" -Port ").append(port)
        if (!deviceId.isNullOrBlank()) sb.append(" -DeviceId ").append(psQuote(deviceId))
        if (!displayName.isNullOrBlank()) sb.append(" -DisplayName ").append(psQuote(displayName))
        return sb.toString()
    }

    /**
     * SSH 一键安装时 App 在目标机上跑的命令（安装包已由 App 上传到
     * remoteBundleDir）。与 unixLocalCommand 同形，只是 bundle 已就位。
     */
    fun sshInstallCommand(
        version: String,
        remoteBundleDir: String,
        port: Int = HAND_PORT,
        deviceId: String? = null,
        displayName: String? = null,
    ): String = unixLocalCommand(version, remoteBundleDir, port, deviceId, displayName)

    /** 卸载命令（SSH 或本机；卸载脚本与安装包同目录时直接跑）。 */
    fun uninstallCommand(bundleDir: String): String =
        "cd " + shellQuote(bundleDir) + " && sh v5-uninstall-hand.sh"

    /** shell 单引号转义（' → '\''）。 */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** PowerShell 单引号转义（' → ''）。 */
    fun psQuote(value: String): String = "'" + value.replace("'", "''") + "'"
}
