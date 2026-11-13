// WhereverCode Agent — iOS
//
// Settings, aligned with the Android app's platform configuration:
// - The platform address is PREFILLED with the platform's public
//   endpoint (editable; a user-saved address is never overwritten
//   by the prefill). Only https; ATS stays on, self-signed
//   certificates are never trusted.
// - The channel is picked by TAPPING a row in the channel list
//   (loaded live from `agent_channel_list`); a single visible
//   channel is selected automatically. Typing a channel id by hand
//   survives only as a collapsed advanced fallback.
// - The main token path is "生成并保存 Token": the app issues a
//   token through the platform's `issue_agent_token` tool (the
//   same call Android's PlatformTokenIssuer makes) and stores it
//   straight into the Keychain as this app's own credential — the
//   user never sees, copies or pastes the token string. Pasting an
//   existing token is the collapsed advanced fallback (it is also
//   the bootstrap path: issuance is authorized by an existing
//   owner credential, exactly as on Android).
// - After every save the app runs a REAL connection check and
//   shows the outcome here; entering Settings (and launching the
//   app) re-checks automatically. Failures always show the
//   server's / transport's real error text.

import SwiftUI

/// The tappable channel rows of the Settings picker. Split out so
/// the layout is renderable/testable without a live platform.
struct ChannelPickerContentView: View {
    let channels: [ChannelInfo]
    let selectedChannelID: String
    let onSelect: (String) -> Void

    var body: some View {
        ForEach(channels) { channel in
            Button {
                onSelect(channel.id)
            } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(channel.displayName)
                            .font(.headline)
                        if !channel.participants.isEmpty {
                            Text(channel.participants.joined(separator: "、"))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    Spacer()
                    if channel.id == selectedChannelID {
                        Image(systemName: "checkmark")
                            .foregroundStyle(.blue)
                    }
                }
            }
            .foregroundStyle(.primary)
        }
    }
}

struct SettingsView: View {
    @EnvironmentObject private var appState: AppState
    @State private var baseURLText = ""
    @State private var channelIDText = ""
    @State private var tokenText = ""
    @State private var agentIDText = PlatformTokenIssuer.defaultAgentID
    @State private var tokenNameText = PlatformTokenIssuer.defaultName
    @State private var isGenerating = false
    @State private var generateError: String?
    @State private var showGenerateConfirm = false
    @State private var saveMessage: String?
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("平台连接") {
                    TextField("平台地址（https://…）", text: $baseURLText)
                        .accessibilityIdentifier("settings.address")
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Text("已预填平台公开地址，可改；填主机名或完整端点都可以。只接受 https；不信任自签证书，不关闭 ATS。")
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
                        Text("还没有选择频道：连上平台后在下面点选。")
                            .font(.caption)
                            .foregroundStyle(.red)
                    }
                    channelPickerRows
                    DisclosureGroup("高级：手动填写频道 ID") {
                        TextField("频道 ID", text: $channelIDText)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        Text("点选即可，通常不用手填；这里是兜底入口，保存的是同一个「当前频道」。")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Button("保存地址与频道") {
                        saveSettings()
                    }
                    .accessibilityIdentifier("settings.saveAddressChannel")
                }
                Section("平台令牌") {
                    Text(tokenSectionStatus)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    TextField("签发对象（Agent ID）", text: $agentIDText)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("令牌名称", text: $tokenNameText)
                        .autocorrectionDisabled()
                    Text("填平台上已存在且启用的 Agent ID：生成的令牌归这个 Agent，本 App 之后就以它的身份连接。签发调平台的 issue_agent_token（与安卓同一个接口），用本 App 当前的平台凭证授权，需要 owner 权限。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    if isGenerating {
                        HStack(spacing: 8) {
                            ProgressView()
                            Text("正在签发…")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                    } else {
                        Button("生成并保存 Token") {
                            if appState.hasToken {
                                showGenerateConfirm = true
                            } else {
                                Task { await generate() }
                            }
                        }
                        .accessibilityIdentifier("settings.generateToken")
                    }
                    if let generateError {
                        Text(generateError)
                            .font(.caption)
                            .foregroundStyle(.red)
                            .textSelection(.enabled)
                    }
                }
                Section("高级") {
                    DisclosureGroup("手工粘贴 Token（兜底）") {
                        SecureField("粘贴 Agent Token", text: $tokenText)
                            .accessibilityIdentifier("settings.tokenField")
                        Text("已有一个平台签发的 Token 时用这里（例如管理员在平台终端签发的第一个凭证）。只存本机 Keychain（仅本设备可读，不同步 iCloud），不会显示在界面其他地方，也不会写进日志。")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        HStack {
                            Button("保存 Token") {
                                saveToken()
                            }
                            .accessibilityIdentifier("settings.saveToken")
                            .disabled(tokenText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                            Spacer()
                            Button("清除 Token", role: .destructive) {
                                clearToken()
                            }
                            .disabled(!appState.hasToken)
                        }
                        LabeledContent("Token 状态", value: appState.hasToken ? "已保存" : "未保存")
                    }
                    // NOTE: no accessibilityIdentifier on the
                    // DisclosureGroup itself — SwiftUI propagates a
                    // container's identifier to every child, which
                    // shadowed settings.tokenField / settings.saveToken
                    // in the accessibility tree (found via CI screen
                    // recording + element dump). The UI test finds this
                    // header by its label instead.
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
                    Text("可用：团队频道（读 / 发 / 实时推送）、我的任务（领取 / 汇报 / 心跳 / 交活 / 放手）、设置与连接状态、平台令牌生成（issue_agent_token，与安卓同接口；需服务端已部署该工具且本机凭证为 owner，未部署时点生成会如实报错）。阻塞中：审批、暂停 / 恢复 / 停止、改目标与设备管理——服务端无此接口，入口处有明确标注。大脑对话与单兵对话未实现。")
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
            .confirmationDialog("覆盖当前凭证？", isPresented: $showGenerateConfirm) {
                Button("生成并覆盖", role: .destructive) {
                    Task { await generate() }
                }
                Button("取消", role: .cancel) {}
            } message: {
                Text("本 App 已存有一个平台凭证。生成新 Token 会覆盖它，之后本 App 改以新令牌对应 Agent 的身份连接。")
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

    // MARK: - Channel picker

    @ViewBuilder
    private var channelPickerRows: some View {
        if appState.isLoadingChannels {
            HStack(spacing: 8) {
                ProgressView()
                Text("正在加载频道列表…")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        } else if !appState.availableChannels.isEmpty {
            ChannelPickerContentView(
                channels: appState.availableChannels,
                selectedChannelID: appState.settings.channelID,
                onSelect: { channelID in
                    appState.selectChannel(channelID)
                    channelIDText = channelID
                }
            )
        } else if let channelListError = appState.channelListError {
            Text(channelListError)
                .font(.subheadline)
                .foregroundStyle(.red)
                .textSelection(.enabled)
        } else {
            Text("还没有加载频道列表。连上平台后点「刷新频道列表」，频道会列在这里，点一下即可选中；只有一个频道时会自动选中。")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        Button("刷新频道列表") {
            Task { await appState.loadChannels() }
        }
    }

    /// Android's 平台令牌 status line, same semantics: "connected"
    /// here means this app holds a platform credential and an
    /// endpoint is resolvable — the live check lives in the
    /// 连接状态 section below.
    private var tokenSectionStatus: String {
        if appState.hasToken, let endpoint = appState.settings.mcpEndpoint {
            return "平台：已连接（\(endpoint.absoluteString)）"
        }
        return "平台：未连接（先在下方生成 Token，或在高级区粘贴已有凭证）"
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

    /// "1.2 (3)" — read from the bundle, never hardcoded, so this row
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

    /// "生成并保存 Token": persist the address in the form first
    /// (issuance goes to the address on screen, not a stale saved
    /// one), then let AppState issue + store + re-check. The token
    /// value never appears here — only the outcome text does.
    private func generate() async {
        var settings = appState.settings
        settings.platformBaseURLString = baseURLText.trimmingCharacters(in: .whitespacesAndNewlines)
        appState.settings = settings
        isGenerating = true
        generateError = nil
        defer { isGenerating = false }
        let result = await appState.generateAndSaveToken(agentId: agentIDText, name: tokenNameText)
        switch result {
        case .issued(_, _, let agentId, _):
            saveMessage = "已为 \(agentId) 生成 Token 并存入 Keychain（全程不显示令牌），正在检查连接…"
        case .failed(let message):
            generateError = message
        }
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
