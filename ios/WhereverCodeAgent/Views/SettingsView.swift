// WhereverCode Agent — iOS
//
// Platform connection form, moved out of SettingsView when
// Settings became a home + section-pages structure mirroring
// Android (unification 212, SPEC §1/§2). Everything below is the
// former SettingsView form, unchanged in state logic, bindings,
// accessibility identifiers and behaviour — only the container
// changed:
//
// - The platform connection is ONE connection item of the app —
//   this page is the iOS counterpart of Android's platform-agent
//   form (`PlatformAgentForm` / the platform-agent form in
//   Android's MainActivity): platform address, the app's platform
//   credential field, and the channel selection, all in one place.
//   It is never the app's front door and never a first-run gate.
// - The platform address is PREFILLED with the platform's public
//   endpoint (the maintainer build's default, like Android's
//   BuildConfig-provided host; editable, and a user-saved address
//   is never overwritten by the prefill). Only https; ATS stays
//   on, self-signed certificates are never trusted.
// - The app's platform credential lives in the SAME form, exactly
//   where Android puts it: a password field labelled with where
//   the credential comes from ("在平台服务器终端签发后填入" —
//   Android's field label, MainActivity's platform-agent form).
//   It is stored only in the Keychain. It is not a first step for
//   the app: every page works without it, it only connects the
//   platform-backed channel and task lists.
// - The channel is picked by TAPPING a row in the channel list
//   (loaded live from `agent_channel_list`); a single visible
//   channel is selected automatically. Typing a channel id by
//   hand survives only as a collapsed advanced fallback, like
//   Android's optional dedicated-channel field.
// - 「平台令牌」 is Android's settings section of the same name
//   (`PlatformTokenIssuer`): with an existing owner credential
//   the app issues a token through the platform's
//   `issue_agent_token` tool. Difference from Android, recorded
//   in the alignment table: Android shows the issued token once
//   for OTHER agents (its own credential is provisioned by the
//   maintainer beforehand); here the issued token is stored
//   straight into the Keychain as this app's own credential, so
//   the user never has to copy or paste the string anywhere.
// - After every save the app runs a REAL connection check and
//   shows the outcome here; entering this page (and launching the
//   app) re-checks automatically. Failures always show the
//   server's / transport's real error text.

import SwiftUI
import UIKit

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
    @ObservedObject private var appState: AppState
    var onNavigateRoute: (String) -> Void = { _ in }

    init() {
        self.appState = AppState()
    }
    init(appState: AppState, onNavigateRoute: @escaping (String) -> Void = { _ in }) {
        self.appState = appState
        self.onNavigateRoute = onNavigateRoute
    }
    private enum FocusField: Hashable { case address, token }
    @FocusState private var focusedField: FocusField?
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
    // 内网频道服务（频道三层二期）
    @State private var lanRunning = false
    @State private var lanTokenNameText = ""
    @State private var lanEntries: [LanTokenRegistry.Entry] = []
    @State private var issuedLanEntry: LanTokenRegistry.Entry?
    @State private var revokeLanCandidate: LanTokenRegistry.Entry?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("平台地址（https://…）", text: $baseURLText)
                        .accessibilityIdentifier("settings.address")
                        .focused($focusedField, equals: .address)
                        .submitLabel(.done)
                        .onSubmit { focusedField = nil }
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Text("没有预置地址：填你自己的平台主机名或完整端点。公网只接受 https；内网地址（192.168.x.x 等）可用 http。不填也不影响本地使用。")
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
                    // The app's platform credential field — the
                    // same field, in the same form, as Android's
                    // platform-agent form ("App 的平台凭证值（在
                    // 平台服务器终端签发后填入…）"). One field of
                    // this connection item, not an app gate.
                    SecureField("粘贴平台凭证（v5_ 开头）", text: $tokenText)
                        .accessibilityIdentifier("settings.tokenField")
                        .focused($focusedField, equals: .token)
                        .submitLabel(.done)
                        .onSubmit { focusedField = nil }
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Text("App 的平台凭证：在平台服务器终端签发后填入（与安卓平台 Agent 表单同一栏）。只存本机 Keychain（仅本设备可读，不同步 iCloud），不会显示在界面其他地方，也不会写进日志。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    HStack {
                        Button("保存凭证") {
                            saveToken()
                        }
                        .accessibilityIdentifier("settings.saveToken")
                        .disabled(tokenText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        Spacer()
                        Button("清除凭证", role: .destructive) {
                            clearToken()
                        }
                        .disabled(!appState.hasToken)
                    }
                    LabeledContent("凭证状态", value: appState.hasToken ? "已保存" : "未保存")
                    channelPickerRows
                    DisclosureGroup("高级：手动填写频道 ID") {
                        TextField("频道 ID", text: $channelIDText)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        Text("点选即可，通常不用手填；这里是兜底入口，保存的是同一个「当前频道」。")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                } header: { Text("平台连接").textCase(nil) }
                Section {
                    LabeledContent("服务状态", value: lanRunning ? "运行中" : "未开启")
                    if lanRunning {
                        LabeledContent("本机地址", value: lanServiceAddressText)
                    }
                    Text("开启后，同一局域网的设备可用成员令牌连入本机频道（全功能，不依赖公网）。iOS 仅前台可用：App 退到后台系统会暂停，服务随之不可达。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Button(lanRunning ? "关闭内网服务" : "开启内网服务") {
                        toggleLanService()
                    }
                    .accessibilityIdentifier("settings.toggleLanService")
                    TextField("成员显示名（如 Mac、备用手机）", text: $lanTokenNameText)
                        .autocorrectionDisabled()
                    Button("签发成员令牌") {
                        issueLanToken()
                    }
                    .accessibilityIdentifier("settings.issueLanToken")
                    if lanEntries.isEmpty {
                        Text("暂无成员令牌")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(lanEntries, id: \.token) { entry in
                            HStack {
                                Text("\(entry.agentName) · \(entry.agentId) · ****\(String(entry.token.suffix(4)))")
                                    .font(.caption)
                                Spacer()
                                Button("撤销", role: .destructive) {
                                    revokeLanCandidate = entry
                                }
                                .font(.caption)
                            }
                        }
                    }
                } header: { Text("内网频道服务").textCase(nil) }
                Section {
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
                } header: { Text("平台令牌").textCase(nil) }
                Section {
                    connectionStatusContent
                    Button("重新检查连接") {
                        Task { await appState.refreshConnectionStatus() }
                    }
                } header: { Text("连接状态").textCase(nil) }
                Section {
                    LabeledContent("本地使用", value: "无需账号")
                    LabeledContent("云端协作", value: appState.hasToken ? "已配置平台凭证" : "可选登录")
                    LabeledContent("GitHub", value: "未连接 · 授权接入开发中")
                    LabeledContent("Google Drive", value: "未连接 · 授权接入开发中")
                    LabeledContent("MCP", value: "按工具与范围单独授权")
                    HStack(spacing: 12) {
                        Button("添加连接") {
                            saveMessage = "外部账号（GitHub / 网盘）OAuth 接入开发中；模型与平台凭证可在上方直接配置。"
                        }
                        .buttonStyle(.bordered)
                        Button("权限管理") {
                            onNavigateRoute("settings")
                        }
                        .buttonStyle(.bordered)
                        Button("Webhook 唤醒") {
                            onNavigateRoute("triggers")
                        }
                        .buttonStyle(.bordered)
                    }
                    .font(.caption)
                } header: { Text("账号与外部连接").textCase(nil) }
                if let saveMessage {
                    Section {
                        Text(saveMessage)
                            .font(.caption)
                            .foregroundStyle(.green)
                    }
                }
            }
            .navigationTitle("平台连接与令牌签发")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("保存") { saveSettings(); focusedField = nil }
                        .accessibilityIdentifier("settings.saveAddressChannel")
                }
                ToolbarItemGroup(placement: .keyboard) {
                    if focusedField == .token {
                        Button("保存凭证") { saveToken(); focusedField = nil }
                            .accessibilityIdentifier("settings.saveTokenKeyboard")
                            .disabled(tokenText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                    Spacer()
                    Button("收起键盘") { focusedField = nil }
                        .accessibilityIdentifier("settings.dismissKeyboard")
                }
            }
            .onAppear {
                baseURLText = appState.settings.platformBaseURLString
                channelIDText = appState.settings.channelID
                refreshLanState()
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
            .alert("成员令牌（只显示一次）", isPresented: Binding(
                get: { issuedLanEntry != nil },
                set: { if !$0 { issuedLanEntry = nil } }
            )) {
                Button("复制令牌") {
                    if let entry = issuedLanEntry {
                        UIPasteboard.general.string = entry.token
                    }
                    issuedLanEntry = nil
                }
                Button("完成", role: .cancel) { issuedLanEntry = nil }
            } message: {
                if let entry = issuedLanEntry {
                    Text("成员：\(entry.agentName)（\(entry.agentId)）\n令牌：\(entry.token)\n对方在平台地址填本机地址、凭证填这枚令牌即可连入。关闭本弹窗后无法再查看完整令牌。")
                }
            }
            .confirmationDialog("撤销这枚成员令牌？", isPresented: Binding(
                get: { revokeLanCandidate != nil },
                set: { if !$0 { revokeLanCandidate = nil } }
            )) {
                Button("撤销", role: .destructive) {
                    if let entry = revokeLanCandidate {
                        LocalPlatform.lanRegistryOrNull()?.revoke(token: entry.token)
                        refreshLanState()
                    }
                    revokeLanCandidate = nil
                }
                Button("取消", role: .cancel) { revokeLanCandidate = nil }
            } message: {
                if let entry = revokeLanCandidate {
                    Text("撤销后 \(entry.agentName) 立即无法再连入本机频道。")
                }
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
        return "平台：未连接（先在上方「平台连接」里填入平台凭证，之后即可在这里生成新令牌）"
    }

    // MARK: - Connection status

    @ViewBuilder
    private var connectionStatusContent: some View {
        switch appState.connectionStatus {
        case .unknown:
            Text("尚未检查。保存地址与凭证后会自动检查，也可以点下面的「重新检查连接」。")
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

    // MARK: - Actions

    // ---- 内网频道服务（频道三层二期） ---------------------------------------

    private var lanServiceAddressText: String {
        if let ip = Self.lanIpv4Address() {
            return "\(ip):\(LanPlatformServer.defaultPort)"
        }
        return "本机地址读取失败"
    }

    private func refreshLanState() {
        lanRunning = LocalPlatform.lanServerRunning()
        lanEntries = LocalPlatform.lanRegistryOrNull()?.list() ?? []
    }

    private func toggleLanService() {
        if lanRunning {
            LocalPlatform.stopLanServer()
            UserDefaults.standard.set(false, forKey: LocalPlatform.lanServiceEnabledKey)
        } else {
            if LocalPlatform.startLanServer() {
                UserDefaults.standard.set(true, forKey: LocalPlatform.lanServiceEnabledKey)
            } else {
                errorMessage = "内网服务启动失败。"
            }
        }
        refreshLanState()
    }

    private func issueLanToken() {
        guard let registry = LocalPlatform.lanRegistryOrNull() else {
            errorMessage = "本地平台服务未启动，无法签发。"
            return
        }
        let entry = registry.issue(agentName: lanTokenNameText)
        lanTokenNameText = ""
        issuedLanEntry = entry
        refreshLanState()
    }

    /// 本机首选 IPv4（与安卓同口径：私有段优先，尾网 100. 次选）。
    private static func lanIpv4Address() -> String? {
        var fallback: String?
        var interfaces: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&interfaces) == 0, let first = interfaces else { return nil }
        defer { freeifaddrs(interfaces) }
        var current: UnsafeMutablePointer<ifaddrs>? = first
        while let iface = current {
            defer { current = iface.pointee.ifa_next }
            guard let addr = iface.pointee.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET) else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let result = getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST)
            guard result == 0 else { continue }
            let ip = String(cString: host)
            if ip.hasPrefix("127.") { continue }
            if LanAddress.isLanAddress(ip) {
                if ip.hasPrefix("100.") {
                    if fallback == nil { fallback = ip }
                } else {
                    return ip
                }
            }
        }
        return fallback
    }

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
            saveMessage = "平台凭证已存入 Keychain，正在检查连接…"
            errorMessage = nil
            Task { await appState.refreshConnectionStatus() }
        } catch {
            errorMessage = (error as? KeychainError)?.message ?? "保存凭证失败。"
        }
    }

    private func clearToken() {
        do {
            try appState.deleteToken()
            saveMessage = "平台凭证已清除。"
            errorMessage = nil
            Task { await appState.refreshConnectionStatus() }
        } catch {
            errorMessage = (error as? KeychainError)?.message ?? "清除凭证失败。"
        }
    }
}
