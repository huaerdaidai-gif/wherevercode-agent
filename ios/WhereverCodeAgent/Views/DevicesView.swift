import SwiftUI

public struct DevicesView: View {
    @ObservedObject public var data: LocalData = .shared

    public init() {}

    public var body: some View {
        List {
            Section("本机执行环境（iPhone 沙盒真实边界）") {
                LabeledContent("当前设备", value: "iPhone 本机 (iOS 17+ Sandbox)")
                LabeledContent("本地 SQLite 存储", value: "已就绪 (WAL 模式)")
                LabeledContent("Keychain 硬件级隔离", value: "WhenUnlockedThisDeviceOnly")
                LabeledContent("本地任意 Shell / 子进程", value: "此平台暂不支持 (.unsupported)")
            }

            Section("远程 Hand 设备配对与服务端接口状态") {
                VStack(alignment: .leading, spacing: 6) {
                    Label("服务端无此接口，阻塞中", systemImage: "exclamationmark.shield.fill")
                        .font(.subheadline.bold())
                        .foregroundStyle(.orange)
                    Text("iOS 尚未接入局域网发现与 Hand 配对；服务端无此接口，阻塞中：V5 平台的 13 个 MCP 工具里没有设备 / Agent 列表与配对工具，本页读不到真实设备；已保存记录也不代表设备在线。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 4)
            }

            Section("Hand 协议契约（X25519 + HKDF-SHA256 + AES-256-GCM）") {
                Text("• 握手帧：wherevercode_hello v1（仅交换 X25519 公钥与可用 Agent/repos 列表，绝不无凭证执行）\n• 会话派生：HKDF-SHA256 (info: wherevercode-cross-device/v1)\n• 调用信封：cross_device_tool_call (list | read | write | run | authorize_project_folder)\n• 执行栅栏：goal_version 与 epoch 快照校验，旧轮次以 stale_goal_version / stale_epoch 拒绝")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("设备列表与配对")
    }
}
