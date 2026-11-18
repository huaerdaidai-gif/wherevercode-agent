import Foundation

/// 设备端一键安装与安装包引导（对齐 Android DeviceInstallController / HandInstallBundle）
/// 严格遵守契约：
/// 1. 脚本从可信发布地址获取，提供 SHA-256 校验和；
/// 2. 支持重复安装、平滑升级和干净卸载（带时间戳备份）；
/// 3. 不要求用户编造配对码或端口（默认统一使用 18743，启动后自动打印 6 位配对码与指纹）；
/// 4. 安装步骤全终端通用：任意终端可跑、装后自动发现、配对后授权新老项目、与 Paseo 等工具并行不冲突；云端走适配器/连接器。
public enum DeviceTargetOs: String, CaseIterable, Identifiable, Sendable {
    case mac = "Mac"
    case linux = "Linux"
    case windows = "Windows"
    case vps = "机房服务器 / VPS"
    case android = "Android"
    case ios = "iOS"
    case cloud = "云端 Agent"

    public var id: String { rawValue }

    public var iconName: String {
        switch self {
        case .mac: return "laptopcomputer"
        case .linux: return "server.rack"
        case .windows: return "desktopcomputer"
        case .vps: return "cloud"
        case .android: return "phone"
        case .ios: return "iphone"
        case .cloud: return "network"
        }
    }

    public var subtitle: String {
        switch self {
        case .mac: return "macOS 12+ · LaunchAgent 守护服务"
        case .linux: return "Ubuntu / Debian / CentOS · systemd 用户服务"
        case .windows: return "Windows 10/11 / Server · PowerShell 脚本"
        case .vps: return "云端 VPS / 远程 Linux 主机"
        case .android: return "移动端算力节点 · Termux 执行宿主 / Tailscale 专网"
        case .ios: return "本机沙盒与审批中心"
        case .cloud: return "API / Bot / MCP 适配器或连接器，无需安装 Hand"
        }
    }
}

public struct DeviceInstallGuide: Sendable {
    public static let defaultPort: Int = 18743
    public static let defaultVersion: String = "1.5.6"

    public static let trustedBaseUrl = "https://raw.githubusercontent.com/huaerdaidai-gif/wherevercode-agent/main/android/app/src/main/assets/install"

    public static let scriptSha256: [String: String] = [
        "v5-install-hand.sh": "270b0565d171d515f2e7ce07c79cefef9445b481e3af7606daa717eb4fc329ea",
        "v5-install-hand.ps1": "9c04cca0744a0804e882c7279a1873254fb642018a86e071d6b2563cf3a15909",
        "v5-uninstall-hand.sh": "8b99bc20e7f09a86863cdc804cfeecf5aec46321b165eec0abfb2a296dcfaaed",
        "hand_server.py": "78076059153e2be7b177297e6a164785a332c2cdd2bb1099ee82dee4477cbf10"
    ]

    /// 生成一键安装命令
    public static func installCommand(for os: DeviceTargetOs, port: Int = defaultPort) -> String {
        switch os {
        case .mac, .linux, .vps:
            return "curl -fsSL \(trustedBaseUrl)/v5-install-hand.sh | sh -s -- --version \(defaultVersion) --port \(port)"
        case .windows:
            return "powershell -ExecutionPolicy Bypass -Command \"$url='\(trustedBaseUrl)/v5-install-hand.ps1'; $file='v5-install-hand.ps1'; Invoke-WebRequest -Uri $url -OutFile $file; .\\$file -Version '\(defaultVersion)' -Port \(port)\""
        case .android:
            return "pkg install -y curl python python-cryptography && curl -fsSL \(trustedBaseUrl)/v5-install-hand.sh | sh -s -- --version \(defaultVersion) --port \(port)"
        case .ios:
            return "iOS 作为控制端和审批中心，在沙盒内运行，无需安装 Hand 服务端。"
        case .cloud:
            return "云端 Agent 走 API / Bot / MCP 等适配器或连接器接入，无需在远端安装 Hand 服务。"
        }
    }

    /// 本地已下载安装包时的执行命令
    public static func localRunCommand(for os: DeviceTargetOs, port: Int = defaultPort) -> String {
        switch os {
        case .mac, .linux, .vps, .android:
            return "sh v5-install-hand.sh --version \(defaultVersion) --port \(port)"
        case .windows:
            return "powershell -ExecutionPolicy Bypass -File .\\v5-install-hand.ps1 -Version \(defaultVersion) -Port \(port)"
        default:
            return installCommand(for: os, port: port)
        }
    }

    /// 卸载命令
    public static func uninstallCommand(for os: DeviceTargetOs) -> String {
        switch os {
        case .mac, .linux, .vps, .android:
            return "curl -fsSL \(trustedBaseUrl)/v5-uninstall-hand.sh | sh"
        case .windows:
            return "powershell -ExecutionPolicy Bypass -Command \"Write-Host '停止服务并清理 ~/.wherevercode/v5-hand'\""
        default:
            return "无须执行卸载操作。"
        }
    }

    /// 安装与配对说明（全终端通用化：兼容系统终端/iTerm/VSCode/SSH、新老项目、Paseo、适配器与连接器）
    public static func stepInstructions(for os: DeviceTargetOs) -> [String] {
        switch os {
        case .mac:
            return [
                "在 Mac 任意终端（系统终端、iTerm、VS Code 终端或 SSH）运行安装命令。",
                "脚本自动配置 Python 环境与 LaunchAgent 守护服务；与 Paseo 等工具并行运行互不冲突。",
                "装好后 Hand 会自动在局域网广播，回到 App 即可自动发现；配对后可授权新老项目。"
            ]
        case .linux, .vps:
            return [
                "在 Linux 主机或 VPS 终端（SSH 会话、tmux 或控制台）运行安装命令。",
                "脚本配置 systemd 用户服务监听 18743 端口；与 Paseo 等工具并行运行不冲突。",
                "输出末尾打印 6 位配对码与指纹；在 App 确认局域网发现或填入 IP 配对，可授权新老项目。"
            ]
        case .windows:
            return [
                "在 Windows PowerShell 或终端中粘贴并执行安装命令。",
                "脚本自动配置后台服务；与 Paseo 等工具并行运行不冲突。",
                "运行完成后展示配对码与指纹，回到 App 填入配对码完成连接；配对后可授权新老项目。"
            ]
        case .android:
            return [
                "在 Android 手机（如 Redmi K70 / 小米）打开 Termux 终端运行安装命令。",
                "脚本自动申请唤醒锁防休眠，启动 Hand 18743 服务；与 Paseo 等工具并行不冲突。",
                "装好后打印配对码与 IP（含 Tailscale 专网），回到 App 输入配对码秒级连接，可授权新老项目。"
            ]
        case .ios:
            return [
                "iOS 作为控制端和审批中心在沙盒内运行，无需部署 Hand 服务端。"
            ]
        case .cloud:
            return [
                "云端 Agent 不装 Hand，走 API / Bot / MCP 等适配器或连接器接入。在 Agent 页添加对应 Agent 即可。"
            ]
        }
    }
}
