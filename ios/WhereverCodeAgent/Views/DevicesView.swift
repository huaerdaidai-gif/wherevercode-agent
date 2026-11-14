import SwiftUI

/// Persisted device-Agent records are local data, available without the
/// platform. Discovery/pairing is intentionally disabled until an iOS Hand
/// adapter exists; a saved record never implies an online device.
struct DevicesView: View {
    @State private var devices: [AgentRecord] = []
    @State private var loadError: String?

    var body: some View {
        NavigationStack {
            List {
                Section("设备（\(devices.count) 条本地记录）") {
                    if let loadError { Text(loadError).foregroundStyle(.red) }
                    if devices.isEmpty {
                        Text("暂无已登记设备").foregroundStyle(AgentUI.secondary)
                    }
                    ForEach(devices, id: \.agentId) { device in
                        VStack(alignment: .leading, spacing: 6) {
                            Label(device.displayName, systemImage: "desktopcomputer")
                            Text(device.lastTestSummary ?? "尚未验证连接")
                                .font(.caption).foregroundStyle(AgentUI.secondary)
                            if let folder = device.deviceFolder {
                                Text(folder).font(.caption).textSelection(.enabled)
                            }
                        }
                    }
                    Button("添加远程设备") {}.disabled(true)
                    Text("iOS 尚未接入局域网发现与 Hand 配对；服务端无此接口，阻塞中。以上操作不可用，已保存记录也不代表设备在线。")
                        .font(.caption).foregroundStyle(AgentUI.secondary)
                }
                Section("设备与授权") {
                    LabeledContent("系统", value: "iOS " + UIDevice.current.systemVersion)
                    LabeledContent("应用", value: Bundle.main.bundleIdentifier ?? "未知")
                    Text("设备通信需要双向配对与授权；本页不会自动建立信任或发送控制命令。")
                        .font(.caption).foregroundStyle(AgentUI.secondary)
                }
            }
            .navigationTitle("设备")
            .task {
                do { devices = try LocalData.shared.agents.listAgents().filter { $0.kind == .device } }
                catch { loadError = error.localizedDescription }
            }
        }
    }
}
