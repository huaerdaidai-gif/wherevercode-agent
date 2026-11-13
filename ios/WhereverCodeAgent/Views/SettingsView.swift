// WhereverCode Agent — iOS
//
// Settings: platform address + channel id (plain config) and the
// Agent token (Keychain only). The address defaults to empty and is
// never guessed; the token is never displayed in full, never logged,
// and never leaves the Keychain except into an Authorization header.
// After every save the app runs a REAL connection check and shows
// the outcome here — connected summary, what is missing, or the
// server's real error text.

import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var appState: AppState
    @State private var baseURLText = ""
    @State private var channelIDText = ""
    @State private var tokenText = ""
    @State private var saveMessage: String?
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("平台连接") {
                    TextField("平台地址（https://…）", text: $baseURLText)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Text("默认为空，请填写你的平台地址。只接受 https；不信任自签证书，不关闭 ATS。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    TextField("频道 ID", text: $channelIDText)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
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
                Section("连接状态") {
                    connectionStatusContent
                    Button("重新检查连接") {
                        Task { await appState.refreshConnectionStatus() }
                    }
                }
                Section("关于") {
                    LabeledContent("版本", value: Self.versionString)
                    LabeledContent("Bundle ID", value: "wherevercode.agent")
                    LabeledContent("最低系统", value: "iOS 17.0")
                    Text("可用：团队频道（读 / 发 / 实时推送）、我的任务（领取 / 汇报 / 心跳 / 交活 / 放手）、设置与连接状态。阻塞中：审批、暂停 / 恢复 / 停止、改目标与设备管理——服务端无此接口，入口处有明确标注。大脑对话与单兵对话未实现。")
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
            .task {
                // First visit with a complete setup: check once so the
                // status section is truthful without a manual tap.
                if appState.isConfigured, appState.connectionStatus == .unknown {
                    await appState.refreshConnectionStatus()
                }
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

    // MARK: - Connection status

    @ViewBuilder
    private var connectionStatusContent: some View {
        switch appState.connectionStatus {
        case .unknown:
            Text("尚未检查。保存地址与 Token 后会自动检查，也可以点下面的「重新检查连接」。")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        case .notConfigured(let reason):
            Label(reason, systemImage: "exclamationmark.circle")
                .font(.subheadline)
                .foregroundStyle(.orange)
        case .checking:
            HStack(spacing: 8) {
                ProgressView()
                Text("正在连接平台…")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        case .connected(let summary):
            Label(summary, systemImage: "checkmark.circle.fill")
                .font(.subheadline)
                .foregroundStyle(.green)
        case .failed(let message):
            VStack(alignment: .leading, spacing: 4) {
                Label("连接失败", systemImage: "xmark.circle.fill")
                    .font(.subheadline)
                    .foregroundStyle(.red)
                Text(message)
                    .font(.caption)
                    .foregroundStyle(.red)
                    .textSelection(.enabled)
            }
        }
    }

    /// "1.1 (2)" — read from the bundle, never hardcoded, so this row
    /// can never drift from the shipped build.
    static var versionString: String {
        let info = Bundle.main.infoDictionary
        let short = info?["CFBundleShortVersionString"] as? String ?? "?"
        let build = info?["CFBundleVersion"] as? String ?? "?"
        return "\(short) (\(build))"
    }

    // MARK: - Actions

    private func saveSettings() {
        var settings = appState.settings
        settings.platformBaseURLString = baseURLText.trimmingCharacters(in: .whitespacesAndNewlines)
        settings.channelID = channelIDText.trimmingCharacters(in: .whitespacesAndNewlines)
        appState.settings = settings
        saveMessage = "已保存地址与频道，正在检查连接…"
        errorMessage = nil
        Task { await appState.refreshConnectionStatus() }
    }

    private func saveToken() {
        let token = tokenText.trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            try appState.saveToken(token)
            tokenText = ""
            saveMessage = "Token 已存入 Keychain，正在检查连接…"
            errorMessage = nil
            Task { await appState.refreshConnectionStatus() }
        } catch {
            errorMessage = (error as? KeychainError)?.message ?? "保存 Token 失败。"
        }
    }

    private func clearToken() {
        do {
            try appState.deleteToken()
            saveMessage = "Token 已清除。"
            errorMessage = nil
            Task { await appState.refreshConnectionStatus() }
        } catch {
            errorMessage = (error as? KeychainError)?.message ?? "清除 Token 失败。"
        }
    }
}
