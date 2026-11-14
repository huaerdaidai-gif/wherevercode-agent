import SwiftUI

public struct SettingsView: View {
    @ObservedObject public var appState: AppState

    @State private var baseURLText: String = ""
    @State private var channelIDText: String = ""
    @State private var tokenText: String = ""
    @State private var agentIDText: String = ""
    @State private var tokenNameText: String = ""
    @State private var isGenerating = false
    @State private var saveMessage: String?
    @State private var errorMessage: String?

    public init(appState: AppState) {
        self.appState = appState
    }

    public var body: some View {
        Form {
            Section("推理大脑与模型管理") {
                NavigationLink {
                    BrainSettingsView(model: appState.brainSettingsModel)
                } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("模型与提供方 (BrainSettingsView)").font(.headline)
                        Text("DeepSeek、Claude、Grok、Muse Spark、Cloudflare、通用模型 API、ChatGPT 会员。Key 只存本机 Keychain。")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }

            Section("V5 平台连接（MCP HTTPS + WebSocket）") {
                HStack {
                    Image(systemName: appState.isConfigured ? "checkmark.circle.fill" : "exclamationmark.circle")
                        .foregroundStyle(appState.isConfigured ? .green : .orange)
                    Text(appState.connectionStatus.displayText)
                        .font(.footnote)
                }

                TextField("平台地址（https://…）", text: $baseURLText)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .accessibilityIdentifier("settings.address")
                Text("已预填平台公开地址，可改；只接受 https；不信任自签证书，不关闭 ATS。")
                    .font(.caption2)
                    .foregroundStyle(.secondary)

                SecureField("粘贴平台凭证（v5_ 开头）", text: $tokenText)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .accessibilityIdentifier("settings.tokenField")
                Text("App 的平台凭证：只存本机 Keychain（kSecAttrAccessibleWhenUnlockedThisDeviceOnly，不同步 iCloud），不会显示在界面其他地方，也不会写进日志。")
                    .font(.caption2)
                    .foregroundStyle(.secondary)

                HStack {
                    Button("保存地址与凭证") {
                        savePlatformConfig()
                    }
                    .buttonStyle(.borderedProminent)

                    Button("清除凭证", role: .destructive) {
                        clearToken()
                    }
                    .buttonStyle(.bordered)
                }
            }

            Section("当前团队频道选择") {
                TextField("高级：手动填写频道 ID", text: $channelIDText)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Button("刷新频道列表") {
                    Task { await appState.loadChannels() }
                }
                ForEach(appState.availableChannels) { ch in
                    Button {
                        channelIDText = ch.id
                        appState.selectChannel(ch.id)
                    } label: {
                        HStack {
                            Text(ch.name)
                            Spacer()
                            if appState.settings.channelID == ch.id {
                                Image(systemName: "checkmark").foregroundStyle(.blue)
                            }
                        }
                    }
                }
            }

            Section("签发新 Agent 令牌 (MCP: issue_agent_token)") {
                TextField("签发对象（Agent ID，如 chatgpt）", text: $agentIDText)
                    .textInputAutocapitalization(.never)
                TextField("令牌名称（如 ios-phone）", text: $tokenNameText)
                Button(isGenerating ? "正在签发…" : "生成并保存 Token") {
                    Task { await issueAndSaveToken() }
                }
                .disabled(isGenerating || agentIDText.isEmpty || tokenNameText.isEmpty)
                Text("需要当前平台凭证具有 owner 权限；签发成功后直接存入本机 Keychain。")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            if let msg = saveMessage {
                Section("操作状态") {
                    Text(msg).foregroundStyle(.green).font(.caption)
                }
            }
            if let err = errorMessage {
                Section("错误提示") {
                    Text(err).foregroundStyle(.red).font(.caption)
                }
            }
        }
        .navigationTitle("设置")
        .onAppear {
            baseURLText = appState.settings.platformBaseURLString
            channelIDText = appState.settings.channelID
        }
    }

    private func savePlatformConfig() {
        errorMessage = nil
        appState.settings.platformBaseURLString = baseURLText.trimmingCharacters(in: .whitespacesAndNewlines)
        appState.settings.channelID = channelIDText.trimmingCharacters(in: .whitespacesAndNewlines)
        appState.settings.save()
        let cleanToken = tokenText.trimmingCharacters(in: .whitespacesAndNewlines)
        if !cleanToken.isEmpty {
            do {
                try appState.saveToken(cleanToken)
                tokenText = ""
                saveMessage = "平台凭证已存入 Keychain，正在检查连接…"
            } catch {
                errorMessage = "保存凭证失败：\(error.localizedDescription)"
                return
            }
        } else {
            saveMessage = "已保存地址与频道，正在检查连接…"
        }
        Task { await appState.loadChannels() }
    }

    private func clearToken() {
        do {
            try appState.deleteToken()
            saveMessage = "平台凭证已清除。"
        } catch {
            errorMessage = "清除凭证失败：\(error.localizedDescription)"
        }
    }

    private func issueAndSaveToken() async {
        guard let client = appState.makeMcpClient() else {
            errorMessage = "未连接平台：请先在上方「平台连接」里填入平台凭证。"
            return
        }
        isGenerating = true
        defer { isGenerating = false }
        let issuer = PlatformTokenIssuer(client: client)
        let res = await issuer.issueToken(agentId: agentIDText, name: tokenNameText)
        switch res {
        case .issued(let token, _, let agentId, _):
            do {
                try appState.saveToken(token)
                saveMessage = "已为 \(agentId) 生成 Token 并存入 Keychain（全程不显示令牌），正在检查连接…"
                await appState.loadChannels()
            } catch {
                errorMessage = "Token 已签发，但存入 Keychain 失败：\(error.localizedDescription)"
            }
        case .failed(let reason):
            errorMessage = reason
        }
    }
}
