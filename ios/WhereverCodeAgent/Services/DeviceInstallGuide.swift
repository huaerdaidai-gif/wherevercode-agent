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
        case .android: return "移动端执行节点 · WhereverCode Agent APK"
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
        "v5-install-hand.sh": "42fdb32f63e40c956590a2448a6a1d97f54bf9ea90325b708b44b936255826b8",
        "v5-install-hand.ps1": "8646bad0f505cbf6075b18c86f61ed63318d0534b03ec8950d84bd3e8faeddab",
        "v5-uninstall-hand.sh": "311ce1f5ecb31726867915ab8d3ca6ef62f366d1de4dc1b7197c16c12710837f",
        "hand_server.py": "8d2217aca1d8e49bd0d60ccf94f16be22ec6f523a16410c558d2b821bf9a9783"
    ]

    /// 生成一键安装命令
    public static func installCommand(for os: DeviceTargetOs, port: Int = defaultPort) -> String {
        switch os {
        case .mac, .linux, .vps:
            return "curl -fsSL \(trustedBaseUrl)/v5-install-hand.sh | sh -s -- --version \(defaultVersion) --port \(port)"
        case .windows:
            return "powershell -ExecutionPolicy Bypass -Command \"$url='\(trustedBaseUrl)/v5-install-hand.ps1'; $file='v5-install-hand.ps1'; Invoke-WebRequest -Uri $url -OutFile $file; .\\$file -Version '\(defaultVersion)' -Port \(port)\""
        case .android:
            return "在 Android 手机上安装并打开 wherevercode-agent APK，进入「设置 → 设备服务」开启 Hand 服务即可。"
        case .ios:
            return "iOS 作为控制端和审批中心，在沙盒内运行，无需安装 Hand 服务端。"
        case .cloud:
            return "云端 Agent 走 API / MCP 适配器直连，无需在远端安装 Hand 服务。"
        }
    }

    /// 本地已下载安装包时的执行命令
    public static func localRunCommand(for os: DeviceTargetOs, port: Int = defaultPort) -> String {
        switch os {
        case .mac, .linux, .vps:
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
        case .mac, .linux, .vps:
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
                "在目标 Android 设备上安装 WhereverCode Agent 最新 Release APK。",
                "同一局域网下，iPhone 会自动通过 mDNS 扫描到该 Android 设备。",
                "点击设备卡片，核对屏幕显示的指纹与 6 位配对码即可秒级完成配对。"
            ]
        case .ios, .cloud:
            return [
                "无需在远程设备部署 Hand 服务端。",
                "直接在「模型与连接」中添加 API Key 或 MCP 服务地址。"
            ]
        }
    }
}
