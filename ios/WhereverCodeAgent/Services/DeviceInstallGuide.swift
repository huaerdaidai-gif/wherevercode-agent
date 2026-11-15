import Foundation

/// 设备端一键安装与安装包引导（对齐 Android DeviceInstallController / HandInstallBundle）
/// 严格遵守契约：
/// 1. 脚本从可信发布地址获取，提供 SHA-256 校验和；
/// 2. 支持重复安装、平滑升级和干净卸载（带时间戳备份）；
/// 3. 不要求用户编造配对码或端口（默认统一使用 18743，启动后自动打印 6 位配对码与指纹）。
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
        case .cloud: return "API / Bot / MCP 适配器，无需安装 Hand"
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
        "hand_server.py": "bfbb1953827dcd4c58c65b18a2ec017d11c2be3611254869f58b49bedbe9ff4b"
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
            return "云端 Agent 走 API / MCP 适配器直连，无需在远端安装 Hand 服务。"
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

    /// 安装与配对说明
    public static func stepInstructions(for os: DeviceTargetOs) -> [String] {
        switch os {
        case .mac:
            return [
                "在 Mac 打开「终端（Terminal）」，复制并运行上方的一键安装命令。",
                "脚本会自动检查 Python 3.10+、配置隔离环境并注册 LaunchAgent 后台守护服务。",
                "安装完成后，终端最后一行将输出当前设备的「6 位数字配对码」与「SHA-256 指纹」。",
                "回到 iPhone，系统将自动发现该 Mac；核对指纹无误后填入终端显示的配对码即可。"
            ]
        case .linux, .vps:
            return [
                "在 Linux 主机或 VPS 终端通过 SSH 连接后运行安装命令。",
                "脚本会自动配置 systemd 用户服务，并监听 18743 端口。",
                "输出末尾会打印设备 ID、6 位配对码与公钥指纹。",
                "在 iPhone 上确认局域网发现或在高级设置填入 VPS IP，输入配对码完成双向授权。"
            ]
        case .windows:
            return [
                "以普通用户身份打开 PowerShell，粘贴并执行安装命令。",
                "脚本自动配置 Windows 后台计划任务或服务。",
                "运行完成后展示配对码与指纹，在 iPhone 上填入配对码完成连接。"
            ]
        case .android:
            return [
                "在 Android 手机（如 Redmi K70 / 小米）上安装并打开 Termux 终端应用。",
                "在 Termux 中粘贴并运行上方的一键安装命令。",
                "脚本会自动申请唤醒锁 (termux-wake-lock) 防止休眠，并启动 Hand 18743 服务。",
                "安装完成后，终端会打印设备指纹、6 位配对码以及 Tailscale 专网 / 局域网 IP。",
                "回到 iPhone，输入终端显示的 Tailscale 专网 IP（如 100.x.x.x）或局域网 IP 及配对码即可秒级完成连接。"
            ]
        case .ios, .cloud:
            return [
                "无需在远程设备部署 Hand 服务端。",
                "直接在「模型与连接」中添加 API Key 或 MCP 服务地址。"
            ]
        }
    }
}
