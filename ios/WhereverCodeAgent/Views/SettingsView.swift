// WhereverCode Agent — iOS MVP
//
// Settings: platform address + channel id (plain config) and the
// Agent token (Keychain only). The address defaults to empty and is
// never guessed; the token is never displayed in full, never logged,
// and never leaves the Keychain except into an Authorization header.

import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var appState: AppState
    @State private var baseURLText = ""
    @State private var channelIDText = ""
    @State private var tokenText = ""
    @State private var isTesting = false
    @State private var testResult: String?
    @State private var saveMessage: String?
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("平台连接") {
                    TextField("平台地址（https://…）", text: $baseURLText)
                    Text("默认为空，请填写你的平台地址。只接受 https；不信任自签证书，不关闭 ATS。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    TextField("频道 ID", text: $channelIDText)
                    Text("默认打开的团队频道，可改。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    switch appState.settings.validation {
                    case .ok:
                        Text("地址有效。")
                            .font(.caption)
                            .foregroundStyle(.green)
                    case .missingBaseURL:
                        Text("请填写平台地址。")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    case .invalidBaseURL(let detail):
                        Text(detail)
                            .font(.caption)
                            .foregroundStyle(.red)
                    case .missingChannelID:
                        Text("请填写频道 ID。")
                            .font(.caption)
                            .foregroundStyle(.red)
                    }
                    Button("保存地址与频道") {
                        saveSettings()
                    }
                }
                Section("Agent Token") {
                    SecureField("粘贴 Agent Token", text: $tokenText)
                    Text("只存本机 Keychain（仅本设备可读，不同步 iCloud）。不会显示在界面其他地方，也不会写进日志。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    HStack {
                        Button("保存 Token") {
                            saveToken()
                        }
                        .disabled(tokenText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        Spacer()
                        Button("清除 Token", role: .destructive) {
                            clearToken()
                        }
                        .disabled(!appState.hasToken)
                    }
                    LabeledContent("Token 状态", value: appState.hasToken ? "已保存" : "未保存")
                }
                Section("连接测试") {
                    Button("测试连接（tools/list）") {
                        Task { await testConnection() }
                    }
                    .disabled(isTesting || !appState.isConfigured)
                    if isTesting {
                        ProgressView()
                    }
                    if let testResult {
                        Text(testResult)
                            .font(.caption)
                    }
                    if !appState.isConfigured {
                        Text("先保存有效的平台地址与 Token 才能测试。")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Section("关于") {
                    LabeledContent("版本", value: "1.0 (MVP)")
                    LabeledContent("Bundle ID", value: "wherevercode.agent")
                    LabeledContent("最低系统", value: "iOS 17.0")
                    Text("MVP 范围：团队频道读 / 发、我的任务领取与交付、设置。审批、暂停 / 恢复 / 停止、改目标、设备管理、大脑对话与单兵对话均未实现，入口处有明确标注。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                if let saveMessage {
                    Section {
                        Text(saveMessage)
                            .font(.caption)
                            .foregroundStyle(.green)
                    }
                }
            }
            .navigationTitle("设置")
            .onAppear {
                baseURLText = appState.settings.platformBaseURLString
                channelIDText = appState.settings.channelID
            }
            .alert("操作失败", isPresented: Binding(
                get: { errorMessage != nil },
                set: { if !$0 { errorMessage = nil } }
            )) {
                Button("好", role: .cancel) {}
            } message: {
                Text(errorMessage ?? "")
            }
        }
    }

    private func saveSettings() {
        var settings = appState.settings
        settings.platformBaseURLString = baseURLText.trimmingCharacters(in: .whitespacesAndNewlines)
        settings.channelID = channelIDText.trimmingCharacters(in: .whitespacesAndNewlines)
        appState.settings = settings
        saveMessage = "已保存地址与频道。"
        errorMessage = nil
    }

    private func saveToken() {
        let token = tokenText.trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            try appState.saveToken(token)
            tokenText = ""
            saveMessage = "Token 已存入 Keychain。"
            errorMessage = nil
        } catch {
            errorMessage = (error as? KeychainError)?.message ?? "保存 Token 失败。"
        }
    }

    private func clearToken() {
        do {
            try appState.deleteToken()
            saveMessage = "Token 已清除。"
            errorMessage = nil
        } catch {
            errorMessage = (error as? KeychainError)?.message ?? "清除 Token 失败。"
        }
    }

    private func testConnection() async {
        guard let client = appState.makeMcpClient() else {
            testResult = "未配置：请先保存平台地址与 Token。"
            return
        }
        isTesting = true
        defer { isTesting = false }
        do {
            let tools = try await client.listToolNames()
            testResult = "连接成功：平台返回 \(tools.count) 个工具。"
        } catch {
            testResult = "连接失败：\((error as? McpError)?.message ?? "未知错误")"
        }
    }
}
