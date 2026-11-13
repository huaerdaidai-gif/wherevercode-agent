// WhereverCode Agent — iOS MVP
//
// Devices tab: explicitly not implemented yet. There is no fake
// device list, no fake online state — only the honest placeholder
// and what the feature will do when it lands.

import SwiftUI

struct DevicesView: View {
    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 12) {
                Text("设备管理未实现")
                    .font(.headline)
                Text(ReservedFeature.deviceManagement.note)
                    .font(.body)
                    .foregroundStyle(.secondary)
                Text("后续版本会接：添加设备（局域网发现 / 手动地址）、配对（双向批准 + 指纹核对）、添加 Agent、开关热插拔，以及控制台。")
                    .font(.body)
                    .foregroundStyle(.secondary)
                Spacer()
            }
            .padding()
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .navigationTitle("设备")
        }
    }
}
