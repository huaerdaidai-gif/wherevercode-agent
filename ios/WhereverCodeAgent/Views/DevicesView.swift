// WhereverCode Agent — iOS
//
// Devices tab: honestly blocked. The V5 platform's MCP tool set has
// no device list, no agent list and no pairing tool, so this page
// cannot read real devices — and it does not fake any. It says
// exactly what is missing and where devices are managed today.

import SwiftUI

struct DevicesView: View {
    var body: some View {
        NavigationStack {
            List {
                Section("状态") {
                    Label("服务端无此接口，阻塞中", systemImage: "exclamationmark.triangle")
                        .font(.headline)
                    Text(ReservedFeature.deviceManagement.note)
                        .font(.body)
                        .foregroundStyle(.secondary)
                }
                Section("现在在哪里管设备") {
                    Text("设备与 Agent 目前在 Android 端 App 里添加和管理（局域网发现、Hand 协议配对都在那条链路上）。iOS 端等平台 MCP 补上设备接口后再接，届时这里会直接读平台的真实设备列表。")
                        .font(.body)
                        .foregroundStyle(.secondary)
                }
                Section("接口补齐后这里会有什么") {
                    Text("添加设备（局域网发现 / 手动地址）、配对（双向批准 + 指纹核对）、添加 Agent、开关热插拔，以及控制台。")
                        .font(.body)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("设备")
        }
    }
}
