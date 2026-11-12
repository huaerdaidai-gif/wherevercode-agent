package wherevercode.agent.core.install

/**
 * 一键安装流程的状态机（纯逻辑，可单测；渲染在 DeviceInstallController）。
 *
 * 流程（maintainer 2026-11-10 09:18）：
 * 管理 → 添加设备 → 选系统 → 安装/配对 → 授权项目目录与执行能力 → 在线管理。
 * 本文件只管“安装”这一段；装好后交回 MainActivity 现有的
 * hello → 指纹 → 配对流程（probeAndAddRemoteDevice），再由配对
 * 成功后的 offerAddProjectAfterPairing 问授权项目。
 */
sealed interface InstallStep {
    /** 选系统。 */
    data object SelectOs : InstallStep

    /** 选安装方式。 */
    data class SelectMethod(val os: DeviceOs) : InstallStep

    /** SSH 一键安装表单（主机/端口/用户名/密码或密钥）。 */
    data class SshForm(val os: DeviceOs) : InstallStep

    /** 显示本机安装命令（复制 / 分享安装包 / 我已装好，继续配对）。 */
    data class ShowCommand(
        val os: DeviceOs,
        val method: InstallMethod,
        val command: String,
        val note: String,
    ) : InstallStep

    /** 正在安装（SSH 上传 + 跑脚本）。 */
    data class Installing(val os: DeviceOs, val status: String) : InstallStep

    /** 已装好（交回配对流程）。 */
    data class Installed(
        val deviceId: String,
        val fingerprint: String,
        val port: Int,
        val host: String,
        val displayName: String,
    ) : InstallStep

    /** 失败（显示原因，可重试）。 */
    data class Failed(val message: String) : InstallStep

    /** 纯指引（iOS / Android 扫码 / 云端适配，暂无一键安装）。 */
    data class Guidance(val os: DeviceOs, val method: InstallMethod, val text: String) : InstallStep
}

object InstallFlow {
    /** 流程起点。 */
    fun firstStep(): InstallStep = InstallStep.SelectOs

    /** 某系统选方式后，下一步是什么。 */
    fun stepAfterMethod(os: DeviceOs, method: InstallMethod, bundle: HandInstallBundle): InstallStep {
        return when (method) {
            InstallMethod.SSH -> InstallStep.SshForm(os)
            InstallMethod.LOCAL_COMMAND -> InstallStep.ShowCommand(
                os = os,
                method = method,
                command = InstallCommand.unixLocalCommand(bundle.version, "~/wherevercode-v5-hand-install"),
                note = "先把安装包传到目标机（App 里点“分享安装包”可导出 4 个文件），再在目标机上跑这条命令。装好后点“我已装好，继续配对”。",
            )
            InstallMethod.POWERSHELL -> InstallStep.ShowCommand(
                os = os,
                method = method,
                command = InstallCommand.windowsLocalCommand(bundle.version, "\$env:LOCALAPPDATA\\WhereverCode\\V5Hand"),
                note = "先把安装包传到目标机（App 里点“分享安装包”可导出 4 个文件），再在目标机上跑这条命令。远程一键安装的前提（WinRM/SSH）未评估，本轮只给本机命令。装好后点“我已装好，继续配对”。",
            )
            InstallMethod.EXISTING_HAND -> InstallStep.Guidance(
                os = os,
                method = method,
                text = "目标机上已经装好 Hand，直接配对即可。",
            )
            InstallMethod.QR_PAIR -> InstallStep.Guidance(
                os = os,
                method = method,
                text = "已装 V5 App 的 Android 设备走局域网发现或直接配对；Termux 里跑 Hand 的设备用“已装 Hand，直接配对”。扫码配对暂未实现。",
            )
            InstallMethod.GUIDANCE -> InstallStep.Guidance(
                os = os,
                method = method,
                text = "iOS 按合法签名安装/配对，不承诺静默安装。先在目标 iOS 设备上装好 V5 App（或 Hand），再用“已装 Hand，直接配对”。",
            )
            InstallMethod.ADAPTER -> InstallStep.Guidance(
                os = os,
                method = method,
                text = "云端 Agent 不装 Hand，走 API / Bot / MCP 适配。在 Agent 页添加对应 Agent 即可。",
            )
        }
    }

    /** 是否终态（不用再往下走）。 */
    fun isTerminal(step: InstallStep): Boolean = step is InstallStep.Installed || step is InstallStep.Failed
}
