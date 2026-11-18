// BrainSettingsView.swift
// 模型管理 UI（三件套之三，Funk 2026-11-18 定案）：
// - 模型区标题与分组改名「模型」：已添加列表叫「已添加的模型」，分组沿用原有分法（模型 API / CLI 会员 / 订阅授权等）。
// - 预设、自动发现、智能路由不许藏在折叠里，放在添加区顶层，与已添加列表同页可见。
// - 双预设两个独立功能，绝不合并：(a) 常用模型服务商：填服务器地址 → 自动刷新模型列表供选；(b) 免费 token 模型服务商：填注册好的 API key → 自动刷新模型列表供选。
// - 免费预设 6 家（数据以安卓 FreeProviderPresets.kt 为准）：Google AI Studio、OpenRouter、Groq、硅基流动（UI 须注明须实名认证）、智谱 GLM、Cloudflare Workers AI。
// - 名单外扩展口：用户可加自定义服务商（名称 + 地址 + LIVE/FIXED 形态），与内置名单分开存储、标「自定义」。
// - 智能路由：默认关；默认只在免费模型之间切换；付费模型须用户主动开启才进路由池；额度不足/限流/出错时按池顺序切换。
// - 模型自动发现：与配对完全独立，默认关、不自动扫；勾选来源（本机/已配对设备，含局域网、Tailscale、公网地址）再探；私有/网状网段走 http、公网走 https。

import SwiftUI

@MainActor
public final class BrainSettingsModel: ObservableObject {
    @Published public private(set) var configs: [BrainConfig] = []
    @Published public private(set) var currentBrainId: String?
    @Published public var routingSettings: ModelRoutingSettings = ModelRoutingSettings()

    public let configStore: BrainConfigStore
    public let keyStore: BrainKeyStore
    public let routingStore: ModelRoutingStore
    public let customProviderStore: CustomFreeProviderStore

    /// Called after every mutation so the owner can rebuild the
    /// adapter registry / engine from the new store state.
    public var onChange: () -> Void = {}
    /// The owner's live adapter registry (rebuilt on change), for
    /// the editor's real model-list fetches.
    public var registryProvider: () -> BrainAdapterRegistry = { BrainAdapterRegistry() }

    public init(
        configStore: BrainConfigStore,
        keyStore: BrainKeyStore,
        routingStore: ModelRoutingStore = UserDefaultsModelRoutingStore(),
        customProviderStore: CustomFreeProviderStore = KeyValueCustomFreeProviderStore()
    ) {
        self.configStore = configStore
        self.keyStore = keyStore
        self.routingStore = routingStore
        self.customProviderStore = customProviderStore
        reload()
    }

    public func reload() {
        configs = configStore.listConfigs()
        currentBrainId = configStore.currentBrainId()
        routingSettings = routingStore.load()
    }

    public func saveRoutingSettings(_ settings: ModelRoutingSettings) {
        routingSettings = settings
        routingStore.save(settings)
    }

    public func hasKey(for config: BrainConfig) -> Bool {
        guard let authRef = config.authRef else { return false }
        return keyStore.getSecret(authRef: authRef) != nil
    }

    public func unusableReason(for config: BrainConfig) -> String? {
        if registryProvider().get(config.adapterId) == nil {
            return config.kind == .subscription
                ? "会员登录在 iOS 上尚未接入，不能作为当前模型"
                : "此模型的接入模块未加载"
        }
        if config.kind != .subscription && !hasKey(for: config) {
            return "尚未填写 API Key"
        }
        return nil
    }

    public func setCurrent(brainId: String?) {
        if let brainId, let config = configStore.getConfig(brainId: brainId),
           unusableReason(for: config) != nil {
            return
        }
        configStore.setCurrentBrainId(brainId)
        reload()
        onChange()
    }

    public func save(config: BrainConfig, apiKey: String) {
        let key = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        if let authRef = config.authRef, !key.isEmpty {
            try? keyStore.putSecret(authRef: authRef, value: key)
        }
        configStore.saveConfig(config)
        reload()
        onChange()
    }

    public func delete(config: BrainConfig) {
        if let authRef = config.authRef {
            keyStore.deleteSecret(authRef: authRef)
        }
        if BrainBootstrap.seedConfigFor(brainId: config.brainId) != nil {
            configStore.markBrainDeleted(brainId: config.brainId)
        }
        configStore.deleteConfig(brainId: config.brainId)
        reload()
        onChange()
    }

    public var restorableBrainIds: [String] {
        let present = Set(configs.map(\.brainId))
        return BrainBootstrap.seedableBrainIds.filter {
            !present.contains($0) && configStore.deletedBrainIds().contains($0)
        }
    }

    public func restore(brainId: String) {
        if BrainBootstrap.restoreSeededBrain(configStore: configStore, brainId: brainId) {
            reload()
            onChange()
        }
    }

    public func createGenericBrain(label: String, preset: GenericOpenAiPreset?) -> BrainConfig {
        let base = "model-api-" + Self.slug(label)
        var candidate = base
        var counter = 2
        let existing = Set(configs.map(\.brainId))
        while existing.contains(candidate) {
            candidate = "\(base)-\(counter)"
            counter += 1
        }
        let config = BrainConfig(
            brainId: candidate, kind: .modelApi,
            adapterId: GenericOpenAiBrainAdapter.adapterId,
            label: label.isEmpty ? "通用模型 API" : label,
            baseUrl: preset?.baseUrl ?? "",
            model: "",
            authRef: "generic:\(candidate)",
            extra: [:], fallbackBrainIds: [])
        configStore.saveConfig(config)
        reload()
        onChange()
        return config
    }

    public func addModelConfig(
        label: String,
        baseUrl: String,
        model: String,
        apiKey: String,
        extra: [String: String] = [:]
    ) {
        let hash = String(format: "%04x", abs(model.hashValue) & 0xFFFF)
        let baseSlug = Self.slug(label)
        let brainId = "model-api-\(baseSlug)-\(hash)"
        let authRef = "generic:\(brainId)"

        if !apiKey.isEmpty {
            try? keyStore.putSecret(authRef: authRef, value: apiKey)
        }

        let config = BrainConfig(
            brainId: brainId,
            kind: .modelApi,
            adapterId: GenericOpenAiBrainAdapter.adapterId,
            label: label,
            baseUrl: baseUrl,
            model: model,
            authRef: authRef,
            extra: extra,
            fallbackBrainIds: []
        )
        configStore.saveConfig(config)
        reload()
        onChange()
    }

    public static func slug(_ label: String) -> String {
        let lowered = label.lowercased()
        let scalars = lowered.unicodeScalars.map { scalar -> Character in
            CharacterSet.alphanumerics.contains(scalar) ? Character(scalar) : "-"
        }
        let joined = String(scalars).split(separator: "-", omittingEmptySubsequences: true)
            .joined(separator: "-")
        return joined.isEmpty ? "custom" : joined
    }
}

// MARK: - 主视图：模型设置

public struct BrainSettingsView: View {
    @ObservedObject var model: BrainSettingsModel
    @State private var editing: BrainConfig?
    @State private var showCommonPicker = false
    @State private var showFreePicker = false
    @State private var showDiscoveryPicker = false
    @State private var showRoutingPoolPicker = false
    @State private var showAddGeneric = false
    @State private var newGenericLabel = ""
    @State private var testStatusMessage: String?
    @State private var isTestingConnection = false

    public init(model: BrainSettingsModel) {
        self.model = model
    }

    public var body: some View {
        Form {
            // 一、从服务商预设添加模型
            addSection

            // 二、智能路由
            routingSection

            // 三、已添加的模型
            addedModelsSection

            // 四、恢复内置模型
            if !model.restorableBrainIds.isEmpty {
                Section("恢复内置模型") {
                    ForEach(model.restorableBrainIds, id: \.self) { brainId in
                        Button("恢复 \(BrainBootstrap.seedConfigFor(brainId: brainId)?.label ?? brainId)") {
                            model.restore(brainId: brainId)
                        }
                    }
                }
            }

            // 五、测试连接
            Section("测试当前连接") {
                Button(isTestingConnection ? "正在测试连接…" : "测试连接") {
                    Task { await runConnectionProbe() }
                }
                .disabled(isTestingConnection)
                if let testStatusMessage {
                    Text(testStatusMessage)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle("模型")
        .onAppear { model.reload() }
        .sheet(item: $editing) { config in
            BrainEditorView(model: model, config: config)
        }
        .sheet(isPresented: $showCommonPicker) {
            CommonProviderPickerSheet(model: model, onConfigCreated: { created in
                editing = created
            })
        }
        .sheet(isPresented: $showFreePicker) {
            FreeProviderPickerSheet(model: model)
        }
        .sheet(isPresented: $showDiscoveryPicker) {
            DeviceModelDiscoverySheet(model: model)
        }
        .sheet(isPresented: $showRoutingPoolPicker) {
            RoutingPoolPickerSheet(model: model)
        }
        .alert("添加通用模型 API", isPresented: $showAddGeneric) {
            TextField("名称（如 OpenAI）", text: $newGenericLabel)
            Button("添加") {
                let created = model.createGenericBrain(
                    label: newGenericLabel.trimmingCharacters(in: .whitespacesAndNewlines),
                    preset: nil)
                newGenericLabel = ""
                editing = created
            }
            Button("取消", role: .cancel) { newGenericLabel = "" }
        } message: {
            Text("填写模型名称，添加后在编辑页中设置地址与 API Key。")
        }
    }

    // MARK: - 1. 添加模型区（顶层可见，不折叠）

    @ViewBuilder
    private var addSection: some View {
        Section("从预设添加模型") {
            Button {
                showCommonPicker = true
            } label: {
                HStack {
                    Label("常用模型服务商", systemImage: "sparkles")
                    Spacer()
                    Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
                }
            }

            Button {
                showFreePicker = true
            } label: {
                HStack {
                    Label("免费 token 模型服务商", systemImage: "gift")
                    Spacer()
                    Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
                }
            }

            Button {
                showDiscoveryPicker = true
            } label: {
                HStack {
                    Label("自动发现设备上的模型", systemImage: "magnifyingglass")
                    Spacer()
                    Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
                }
            }

            Button {
                showAddGeneric = true
            } label: {
                HStack {
                    Label("添加通用模型 API（手动输入）", systemImage: "plus.circle")
                    Spacer()
                    Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
                }
            }
        }
    }

    // MARK: - 2. 智能路由区（顶层可见，不折叠）

    @ViewBuilder
    private var routingSection: some View {
        Section("智能路由") {
            Toggle("智能路由（自动切换模型）", isOn: Binding(
                get: { model.routingSettings.enabled },
                set: { val in
                    var s = model.routingSettings
                    s.enabled = val
                    model.saveRoutingSettings(s)
                }
            ))

            Toggle("允许切到付费模型", isOn: Binding(
                get: { model.routingSettings.allowPaid },
                set: { val in
                    var s = model.routingSettings
                    s.allowPaid = val
                    model.saveRoutingSettings(s)
                }
            ))

            Text("模型额度不足、被限流或出错时，按路由池顺序自动切到下一个。")
                .font(.caption)
                .foregroundStyle(.secondary)

            Button {
                showRoutingPoolPicker = true
            } label: {
                HStack {
                    Text("选择路由池模型")
                    Spacer()
                    let count = model.routingSettings.orderedBrainIds.count
                    Text(count > 0 ? "\(count) 个模型参与" : "未配置")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
                }
            }
        }
    }

    // MARK: - 3. 已添加的模型（分类呈现）

    private var modelApis: [BrainConfig] {
        model.configs.filter { $0.kind == .modelApi && !BrainConfigValidator.isLocalModelUrl($0.baseUrl ?? "") }
    }
    private var localModels: [BrainConfig] {
        model.configs.filter { $0.kind == .modelApi && BrainConfigValidator.isLocalModelUrl($0.baseUrl ?? "") }
    }
    private var cliBrains: [BrainConfig] {
        model.configs.filter { $0.kind == .agentRuntime }
    }
    private var subscriptions: [BrainConfig] {
        model.configs.filter { $0.kind == .subscription }
    }

    @ViewBuilder
    private var addedModelsSection: some View {
        Section("已添加的模型") {
            if model.configs.isEmpty {
                Text("还没有添加模型。")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            } else {
                if !modelApis.isEmpty {
                    SectionHeaderView(title: "模型 API")
                    ForEach(modelApis, id: \.brainId) { config in
                        modelRow(config: config)
                    }
                }

                if !cliBrains.isEmpty {
                    SectionHeaderView(title: "CLI 会员")
                    ForEach(cliBrains, id: \.brainId) { config in
                        modelRow(config: config)
                    }
                }

                if !subscriptions.isEmpty {
                    SectionHeaderView(title: "订阅授权")
                    ForEach(subscriptions, id: \.brainId) { config in
                        modelRow(config: config)
                    }
                }

                if !localModels.isEmpty {
                    SectionHeaderView(title: "本地模型")
                    ForEach(localModels, id: \.brainId) { config in
                        modelRow(config: config)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func modelRow(config: BrainConfig) -> some View {
        Button {
            editing = config
        } label: {
            BrainRowView(
                config: config,
                isCurrent: model.currentBrainId == config.brainId,
                hasKey: model.hasKey(for: config)
            )
        }
        .foregroundStyle(.primary)
    }

    private func runConnectionProbe() async {
        isTestingConnection = true
        defer { isTestingConnection = false }
        let targetId = model.currentBrainId ?? model.configs.first(where: { model.unusableReason(for: $0) == nil })?.brainId
        guard let targetId,
              let cfg = model.configs.first(where: { $0.brainId == targetId }),
              let adapter = model.registryProvider().get(cfg.adapterId) else {
            testStatusMessage = "尚未配置可用的 API Key 模型：请点击上方任一模型填入 API Key。"
            return
        }
        switch await adapter.validateCredentials() {
        case .success:
            testStatusMessage = "「\(cfg.label)」连接验证通过。"
        case .failure(let err):
            testStatusMessage = "「\(cfg.label)」验证失败：\(SecretRedactor.redact(err.localizedDescription))"
        }
    }
}

private struct SectionHeaderView: View {
    let title: String
    var body: some View {
        Text(title)
            .font(.caption.weight(.semibold))
            .foregroundStyle(.secondary)
            .padding(.top, 4)
    }
}

// MARK: - 模型行组件

private struct BrainRowView: View {
    let config: BrainConfig
    let isCurrent: Bool
    let hasKey: Bool

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text(config.label)
                        .font(.headline)
                    if isCurrent {
                        Text("当前")
                            .font(.caption2.weight(.bold))
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Color.blue.opacity(0.15))
                            .clipShape(Capsule())
                    }
                    if ModelRouting.isFree(config: config) {
                        Text("免费")
                            .font(.caption2)
                            .padding(.horizontal, 5)
                            .padding(.vertical, 1)
                            .background(Color.green.opacity(0.15))
                            .clipShape(Capsule())
                    }
                }
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Image(systemName: "chevron.right")
                .font(.caption)
                .foregroundStyle(.tertiary)
        }
    }

    private var subtitle: String {
        let kind = config.kind == .subscription ? "会员" : "模型 API"
        let model = config.model.isEmpty ? "未选模型" : config.model
        if config.adapterId == BrainBootstrap.siwcBrainId {
            return "\(kind) · 登录接入开发中"
        }
        return "\(kind) · \(model) · \(hasKey ? "已填 Key" : "未填 Key")"
    }
}

// MARK: - 常用模型服务商 Sheet

private struct CommonProviderPickerSheet: View {
    @ObservedObject var model: BrainSettingsModel
    var onConfigCreated: (BrainConfig) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List(GenericOpenAiPresets.presets, id: \.displayName) { preset in
                Button {
                    let created = model.createGenericBrain(label: preset.displayName, preset: preset)
                    dismiss()
                    onConfigCreated(created)
                } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(preset.displayName).font(.subheadline.weight(.semibold)).foregroundStyle(.primary)
                        Text(preset.baseUrl).font(.caption).foregroundStyle(.secondary)
                        if !preset.note.isEmpty {
                            Text(preset.note).font(.caption2).foregroundStyle(.tertiary)
                        }
                    }
                    .padding(.vertical, 2)
                }
            }
            .navigationTitle("常用模型服务商")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
            }
        }
    }
}

// MARK: - 免费 Token 服务商 Sheet

private struct FreeProviderPickerSheet: View {
    @ObservedObject var model: BrainSettingsModel
    @Environment(\.dismiss) private var dismiss

    @State private var selectedPreset: FreeProviderPreset?
    @State private var showCustomDialog = false
    @State private var accountId = ""
    @State private var apiKey = ""
    @State private var isFetchingLiveModels = false
    @State private var liveFetchError: String?
    @State private var modelsToSelect: [String] = []
    @State private var checkedModelIndices: Set<Int> = []
    @State private var step: FreeProviderStep = .selectProvider

    private enum FreeProviderStep {
        case selectProvider
        case inputAccountId
        case inputApiKey
        case selectModels
    }

    var body: some View {
        NavigationStack {
            Group {
                switch step {
                case .selectProvider:
                    providerSelectionView
                case .inputAccountId:
                    accountIdView
                case .inputApiKey:
                    apiKeyView
                case .selectModels:
                    modelSelectionView
                }
            }
            .navigationTitle(navigationTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(step == .selectProvider ? "取消" : "上一步") {
                        handleBackOrCancel()
                    }
                }
            }
            .sheet(isPresented: $showCustomDialog) {
                CustomFreeProviderDialog(model: model)
            }
        }
    }

    private var navigationTitle: String {
        switch step {
        case .selectProvider: return "免费 token 模型服务商"
        case .inputAccountId: return selectedPreset?.displayName ?? "Account ID"
        case .inputApiKey: return selectedPreset?.displayName ?? "API Key"
        case .selectModels: return "选择要添加的模型"
        }
    }

    private var providerSelectionView: some View {
        List {
            let customs = model.customProviderStore.list()
            let all = CustomFreeProviders.merged(builtins: FreeProviderPresets.presets, customs: customs)

            Section {
                ForEach(all) { preset in
                    Button {
                        selectedPreset = preset
                        if preset.needsAccountId {
                            step = .inputAccountId
                        } else {
                            step = .inputApiKey
                        }
                    } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text(preset.displayName)
                                    .font(.subheadline.weight(.semibold))
                                    .foregroundStyle(.primary)
                                if CustomFreeProviders.isCustom(preset) {
                                    Text("自定义")
                                        .font(.caption2)
                                        .padding(.horizontal, 4)
                                        .padding(.vertical, 1)
                                        .background(Color.secondary.opacity(0.12), in: Capsule())
                                }
                            }
                            Text(preset.keyHelp)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            if !preset.note.isEmpty {
                                Text(preset.note)
                                    .font(.caption2)
                                    .foregroundStyle(.tertiary)
                            }
                        }
                        .padding(.vertical, 2)
                    }
                }
            }

            Section {
                Button {
                    showCustomDialog = true
                } label: {
                    Label("＋ 添加自定义服务商（名单上没有的）", systemImage: "plus")
                        .font(.subheadline)
                }
            }
        }
    }

    private var accountIdView: some View {
        Form {
            Section {
                TextField("Cloudflare Account ID", text: $accountId)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            } header: {
                Text("输入 Account ID")
            } footer: {
                Text("可在 Cloudflare 控制台右侧侧边栏查看。")
            }

            Button("下一步") {
                if !accountId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    step = .inputApiKey
                }
            }
            .disabled(accountId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
    }

    private var apiKeyView: some View {
        Form {
            if let preset = selectedPreset {
                Section {
                    Text(preset.keyHelp)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    if !preset.note.isEmpty {
                        Text(preset.note)
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }

                Section("API Key") {
                    SecureField("粘贴 API Key（只存本机 Keychain）", text: $apiKey)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }

                if isFetchingLiveModels {
                    HStack(spacing: 8) {
                        ProgressView()
                        Text("正在拉取模型列表…").font(.caption).foregroundStyle(.secondary)
                    }
                } else {
                    Button("下一步") {
                        proceedFromApiKey(preset: preset)
                    }
                    .disabled(apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }

                if let liveFetchError {
                    Text(liveFetchError).font(.caption).foregroundStyle(.red)
                }
            }
        }
    }

    private func proceedFromApiKey(preset: FreeProviderPreset) {
        let cleanKey = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        let resolvedBase = preset.resolveBaseUrl(accountId: accountId)
        if preset.listMode == .fixed {
            modelsToSelect = preset.fixedModels
            checkedModelIndices = Set(0..<modelsToSelect.count)
            step = .selectModels
        } else {
            isFetchingLiveModels = true
            liveFetchError = nil
            Task {
                let res = await GenericOpenAiApiClient().listModels(apiKey: cleanKey, baseUrl: resolvedBase)
                await MainActor.run {
                    self.isFetchingLiveModels = false
                    switch res {
                    case .success(let items):
                        if items.isEmpty {
                            self.liveFetchError = "服务商返回了空的模型列表；请检查 Key 是否有效。"
                        } else {
                            self.modelsToSelect = items.map(\.id)
                            self.checkedModelIndices = Set(0..<self.modelsToSelect.count)
                            self.step = .selectModels
                        }
                    case .failure(let err):
                        self.liveFetchError = "拉取失败：\(err.message)"
                    }
                }
            }
        }
    }

    private var modelSelectionView: some View {
        List {
            Section("勾选要添加的模型") {
                ForEach(Array(modelsToSelect.enumerated()), id: \.offset) { idx, modelId in
                    Button {
                        if checkedModelIndices.contains(idx) {
                            checkedModelIndices.remove(idx)
                        } else {
                            checkedModelIndices.insert(idx)
                        }
                    } label: {
                        HStack {
                            Text(modelId).font(.subheadline).foregroundStyle(.primary)
                            Spacer()
                            if checkedModelIndices.contains(idx) {
                                Image(systemName: "checkmark.circle.fill").foregroundStyle(.blue)
                            } else {
                                Image(systemName: "circle").foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }

            Section {
                Button("添加所选 (\(checkedModelIndices.count))") {
                    saveSelectedModels()
                }
                .disabled(checkedModelIndices.isEmpty)
            }
        }
    }

    private func saveSelectedModels() {
        guard let preset = selectedPreset else { return }
        let cleanKey = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        let resolvedBase = preset.resolveBaseUrl(accountId: accountId)

        for idx in checkedModelIndices.sorted() {
            let mName = modelsToSelect[idx]
            let label = "\(preset.displayName) · \(mName)"
            model.addModelConfig(
                label: label,
                baseUrl: resolvedBase,
                model: mName,
                apiKey: cleanKey,
                extra: [
                    FreeProviderPresets.extraTier: FreeProviderPresets.tierFree,
                    FreeProviderPresets.extraPreset: preset.id
                ]
            )
        }
        dismiss()
    }

    private func handleBackOrCancel() {
        switch step {
        case .selectProvider:
            dismiss()
        case .inputAccountId:
            step = .selectProvider
        case .inputApiKey:
            step = (selectedPreset?.needsAccountId == true) ? .inputAccountId : .selectProvider
        case .selectModels:
            step = .inputApiKey
        }
    }
}

// MARK: - 添加自定义免费服务商 Dialog

private struct CustomFreeProviderDialog: View {
    @ObservedObject var model: BrainSettingsModel
    @Environment(\.dismiss) private var dismiss

    @State private var name = ""
    @State private var baseUrl = ""
    @State private var listMode: FreeModelListMode = .live
    @State private var fixedModelsText = ""
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("服务商信息") {
                    TextField("服务商名称（如 我的实验室）", text: $name)
                    TextField("服务地址（如 https://api.example.com/v1）", text: $baseUrl)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }

                Section("模型列表模式") {
                    Picker("模式", selection: $listMode) {
                        Text("实时拉取 (/models)").tag(FreeModelListMode.live)
                        Text("固定模型名单").tag(FreeModelListMode.fixed)
                    }
                    .pickerStyle(.segmented)

                    if listMode == .fixed {
                        TextField("固定模型 ID（逗号或空格分隔）", text: $fixedModelsText)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    }
                }

                if let errorMessage {
                    Section {
                        Text(errorMessage).font(.caption).foregroundStyle(.red)
                    }
                }

                Section {
                    Button("保存服务商") {
                        save()
                    }
                    .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || baseUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .navigationTitle("添加自定义免费服务商")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
            }
        }
    }

    private func save() {
        let cleanName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanUrl = baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)

        guard !cleanName.isEmpty && !cleanUrl.isEmpty else {
            errorMessage = "名称与服务地址均须填写。"
            return
        }

        guard BrainConfigValidator.isAllowedBaseUrl(cleanUrl) else {
            errorMessage = "服务地址只允许 https（本机/局域网模型可用 http）。"
            return
        }

        let existingIds = Set(model.customProviderStore.list().map(\.id))
        let id = CustomFreeProviders.customId(cleanName, existing: existingIds)

        var fixedList: [String] = []
        if listMode == .fixed {
            fixedList = fixedModelsText.components(separatedBy: CharacterSet(charactersIn: ", \n\t"))
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                .filter { !$0.isEmpty }
            if fixedList.isEmpty {
                errorMessage = "固定名单模式须至少填写一个模型 ID。"
                return
            }
        }

        let preset = FreeProviderPreset(
            id: id,
            displayName: cleanName,
            fixedModels: fixedList,
            listMode: listMode,
            baseUrlTemplate: cleanUrl,
            keyHelp: "在 \(cleanName) 控制台领 key。",
            note: "自定义免费服务商。"
        )
        model.customProviderStore.add(preset)
        dismiss()
    }
}

// MARK: - 自动发现设备上的模型 Sheet

private struct DeviceModelDiscoverySheet: View {
    @ObservedObject var model: BrainSettingsModel
    @Environment(\.dismiss) private var dismiss

    @State private var sources: [(label: String, host: String)] = []
    @State private var checkedSources: Set<Int> = []
    @State private var isProbing = false
    @State private var discoveredModels: [(sourceLabel: String, baseUrl: String, model: String)] = []
    @State private var checkedDiscoveredIndices: Set<Int> = []
    @State private var step: DiscoveryStep = .selectSources
    @State private var statusNote: String?

    private enum DiscoveryStep {
        case selectSources
        case selectModels
    }

    var body: some View {
        NavigationStack {
            Group {
                switch step {
                case .selectSources:
                    sourcesView
                case .selectModels:
                    modelsView
                }
            }
            .navigationTitle(step == .selectSources ? "勾选要扫描的设备" : "发现的模型 (\(discoveredModels.count))")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(step == .selectSources ? "取消" : "上一步") {
                        if step == .selectSources {
                            dismiss()
                        } else {
                            step = .selectSources
                        }
                    }
                }
            }
            .onAppear(perform: loadSources)
        }
    }

    private var sourcesView: some View {
        List {
            Section {
                ForEach(Array(sources.enumerated()), id: \.offset) { idx, item in
                    Button {
                        if checkedSources.contains(idx) {
                            checkedSources.remove(idx)
                        } else {
                            checkedSources.insert(idx)
                        }
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(item.label).font(.subheadline.weight(.medium)).foregroundStyle(.primary)
                                Text(item.host).font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            if checkedSources.contains(idx) {
                                Image(systemName: "checkmark.circle.fill").foregroundStyle(.blue)
                            } else {
                                Image(systemName: "circle").foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            } footer: {
                Text("扫描 Ollama (11434)、LM Studio (1234) 及 vLLM (8000) 常见模型端点。私有/专网走 http，公网走 https。")
            }

            if isProbing {
                HStack(spacing: 8) {
                    ProgressView()
                    Text("正在探测模型端点…").font(.caption).foregroundStyle(.secondary)
                }
            } else {
                Button("开始扫描") {
                    startScan()
                }
                .disabled(checkedSources.isEmpty)
            }

            if let statusNote {
                Text(statusNote).font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    private var modelsView: some View {
        List {
            Section("勾选要添加的模型") {
                ForEach(Array(discoveredModels.enumerated()), id: \.offset) { idx, item in
                    Button {
                        if checkedDiscoveredIndices.contains(idx) {
                            checkedDiscoveredIndices.remove(idx)
                        } else {
                            checkedDiscoveredIndices.insert(idx)
                        }
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(item.model).font(.subheadline.weight(.semibold)).foregroundStyle(.primary)
                                Text("\(item.sourceLabel) · \(item.baseUrl)").font(.caption2).foregroundStyle(.secondary)
                            }
                            Spacer()
                            if checkedDiscoveredIndices.contains(idx) {
                                Image(systemName: "checkmark.circle.fill").foregroundStyle(.blue)
                            } else {
                                Image(systemName: "circle").foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }

            Button("添加所选 (\(checkedDiscoveredIndices.count))") {
                saveDiscovered()
            }
            .disabled(checkedDiscoveredIndices.isEmpty)
        }
    }

    private func loadSources() {
        var list: [(label: String, host: String)] = [("本机", "127.0.0.1")]
        if let agents = try? LocalData.shared.agents.listAgents() {
            let devices = agents.filter { $0.kind == .device && $0.access == .app }
            for d in devices {
                if let raw = d.baseUrl {
                    let host = raw.replacingOccurrences(of: "http://", with: "").replacingOccurrences(of: "https://", with: "").components(separatedBy: ":").first ?? ""
                    if !host.isEmpty && !list.contains(where: { $0.host == host }) {
                        list.append((d.displayName, host))
                    }
                }
            }
        }
        sources = list
        // 默认一个都不勾 (先勾选再扫描)
        checkedSources = []
    }

    private func startScan() {
        guard !checkedSources.isEmpty else {
            statusNote = "先勾选要扫描的设备。"
            return
        }
        let targets = checkedSources.map { sources[$0] }
        isProbing = true
        statusNote = nil

        Task {
            var found: [(sourceLabel: String, baseUrl: String, model: String)] = []
            for t in targets {
                let endpoints = await DeviceModelDiscovery.probeHost(t.host)
                for ep in endpoints {
                    for m in ep.models {
                        found.append(("\(t.label) · \(ep.serviceName)", ep.baseUrl, m))
                    }
                }
            }
            await MainActor.run {
                self.isProbing = false
                if found.isEmpty {
                    self.statusNote = "未发现模型端点：勾选来源未在常用端口返回模型。"
                } else {
                    self.discoveredModels = found
                    self.checkedDiscoveredIndices = Set(0..<found.count)
                    self.step = .selectModels
                }
            }
        }
    }

    private func saveDiscovered() {
        for idx in checkedDiscoveredIndices.sorted() {
            let item = discoveredModels[idx]
            let label = "\(item.sourceLabel) · \(item.model)"
            model.addModelConfig(
                label: label,
                baseUrl: item.baseUrl,
                model: item.model,
                apiKey: "local",
                extra: [
                    FreeProviderPresets.extraTier: FreeProviderPresets.tierFree,
                    "discovery": "local_endpoint"
                ]
            )
        }
        dismiss()
    }
}

// MARK: - 智能路由池配置 Sheet

private struct RoutingPoolPickerSheet: View {
    @ObservedObject var model: BrainSettingsModel
    @Environment(\.dismiss) private var dismiss

    @State private var availableModels: [BrainConfig] = []
    @State private var selectedBrainIds: Set<String> = []

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("智能路由默认只在免费模型之间切换；若开启允许付费模型，则付费模型也可进入路由池。遇额度不足或限流时按列表顺序自动切换。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Section("路由池候选模型") {
                    if availableModels.isEmpty {
                        Text("还没有任何模型连接：先添加模型，再来选路由池。")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(availableModels) { config in
                            Button {
                                if selectedBrainIds.contains(config.brainId) {
                                    selectedBrainIds.remove(config.brainId)
                                } else {
                                    selectedBrainIds.insert(config.brainId)
                                }
                            } label: {
                                HStack {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(config.label)
                                            .font(.subheadline.weight(.semibold))
                                            .foregroundStyle(.primary)
                                        Text(ModelRouting.isFree(config: config) ? "免费档" : "付费档")
                                            .font(.caption2)
                                            .foregroundStyle(ModelRouting.isFree(config: config) ? .green : .orange)
                                    }
                                    Spacer()
                                    if selectedBrainIds.contains(config.brainId) {
                                        Image(systemName: "checkmark.circle.fill")
                                            .foregroundStyle(.blue)
                                    } else {
                                        Image(systemName: "circle")
                                            .foregroundStyle(.secondary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("智能路由池（按顺序切换）")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        savePool()
                    }
                    .disabled(availableModels.isEmpty)
                }
            }
            .onAppear(perform: loadData)
        }
    }

    private func loadData() {
        availableModels = model.configs.filter { $0.kind == .modelApi }
        selectedBrainIds = Set(model.routingSettings.orderedBrainIds)
    }

    private func savePool() {
        let kept = model.routingSettings.orderedBrainIds.filter { selectedBrainIds.contains($0) }
        let added = availableModels.map(\.brainId).filter { selectedBrainIds.contains($0) && !kept.contains($0) }
        var settings = model.routingSettings
        settings.orderedBrainIds = kept + added
        model.saveRoutingSettings(settings)
        dismiss()
    }
}

// MARK: - 模型详情与编辑视图

private struct BrainEditorView: View {
    @ObservedObject var model: BrainSettingsModel
    @Environment(\.dismiss) private var dismiss

    @State private var label: String
    @State private var baseUrl: String
    @State private var modelName: String
    @State private var accountId: String
    @State private var apiKey: String = ""
    @State private var fetchedModels: [ModelInfo] = []
    @State private var isFetchingModels = false
    @State private var modelFetchError: String?
    @State private var showDeleteConfirm = false
    @State private var saveMessage: String?

    private let original: BrainConfig

    init(model: BrainSettingsModel, config: BrainConfig) {
        self.model = model
        self.original = config
        _label = State(initialValue: config.label)
        _baseUrl = State(initialValue: config.baseUrl ?? "")
        _modelName = State(initialValue: config.model)
        _accountId = State(initialValue: config.extra[CloudflareBrainAdapter.extraAccountId] ?? "")
    }

    private var isSiwc: Bool { original.adapterId == BrainBootstrap.siwcBrainId }
    private var isCloudflare: Bool { original.adapterId == CloudflareBrainAdapter.adapterId }
    private var isGeneric: Bool { original.adapterId == GenericOpenAiBrainAdapter.adapterId }

    var body: some View {
        NavigationStack {
            Form {
                if isSiwc {
                    Section {
                        Text("ChatGPT 会员大脑：通过 Sign in with ChatGPT 登录后使用订阅额度。登录接入正在开发中，这一页先如实标注状态，不提供假的登录按钮。配置保留，接入完成后即可直接使用。")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                } else {
                    Section("基本信息") {
                        TextField("名称", text: $label)
                        TextField("服务地址（https://…）", text: $baseUrl)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        if isCloudflare {
                            TextField("Cloudflare Account ID", text: $accountId)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                        }
                        if isGeneric {
                            Menu("套用预置") {
                                ForEach(GenericOpenAiPresets.presets, id: \.displayName) { preset in
                                    Button(preset.displayName) {
                                        label = preset.displayName
                                        baseUrl = preset.baseUrl
                                    }
                                }
                            }
                        }
                    }
                    Section("模型") {
                        TextField("模型名", text: $modelName)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        if isFetchingModels {
                            HStack(spacing: 8) {
                                ProgressView()
                                Text("正在从服务商拉取模型列表…")
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                            }
                        } else {
                            Button("刷新模型列表") {
                                Task { await fetchModels() }
                            }
                            .disabled(!model.hasKey(for: original) && apiKey.isEmpty)
                        }
                        if let modelFetchError {
                            Text(modelFetchError)
                                .font(.caption)
                                .foregroundStyle(.red)
                                .textSelection(.enabled)
                        }
                        ForEach(fetchedModels, id: \.id) { info in
                            Button {
                                modelName = info.id
                            } label: {
                                HStack {
                                    Text(info.id)
                                    Spacer()
                                    if info.id == modelName {
                                        Image(systemName: "checkmark")
                                            .foregroundStyle(.blue)
                                    }
                                }
                            }
                            .foregroundStyle(.primary)
                        }
                    }
                    Section("API Key") {
                        SecureField(
                            model.hasKey(for: original) ? "已保存（留空不改，输入则替换）" : "粘贴 API Key",
                            text: $apiKey)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        Text("只存本机 Keychain，不显示、不写日志、不经过平台服务器。")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Section {
                    if model.currentBrainId != original.brainId {
                        Button("设为当前模型") {
                            save()
                            model.setCurrent(brainId: original.brainId)
                            dismiss()
                        }
                        .disabled(isSiwc || (!model.hasKey(for: original) && apiKey.isEmpty))
                        if let reason = model.unusableReason(for: original), apiKey.isEmpty {
                            Text(reason)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    } else {
                        Button("取消当前模型（停用大脑层）") {
                            model.setCurrent(brainId: nil)
                            dismiss()
                        }
                    }
                    Button("保存") {
                        save()
                        dismiss()
                    }
                    Button("删除这个模型", role: .destructive) {
                        showDeleteConfirm = true
                    }
                }
                if let saveMessage {
                    Section {
                        Text(saveMessage)
                            .font(.caption)
                            .foregroundStyle(.green)
                    }
                }
            }
            .navigationTitle(original.label)
            .confirmationDialog("删除这个模型？", isPresented: $showDeleteConfirm) {
                Button("删除", role: .destructive) {
                    model.delete(config: original)
                    dismiss()
                }
                Button("取消", role: .cancel) {}
            } message: {
                Text("配置和已存的 Key 都会删掉。内置模型删除后不会自己回来，可以在列表里恢复（Key 需要重新填）。")
            }
        }
    }

    private func save() {
        var config = original
        config.label = label.trimmingCharacters(in: .whitespacesAndNewlines)
        let url = baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        config.baseUrl = url.isEmpty ? nil : url
        config.model = modelName.trimmingCharacters(in: .whitespacesAndNewlines)
        if isCloudflare {
            config.extra[CloudflareBrainAdapter.extraAccountId] =
                accountId.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        model.save(config: config, apiKey: apiKey)
    }

    private func fetchModels() async {
        save()
        isFetchingModels = true
        modelFetchError = nil
        defer { isFetchingModels = false }
        var adapter = appRegistryAdapter()
        if let generic = adapter as? GenericOpenAiBrainAdapter {
            adapter = generic.forBrain(original.brainId)
        }
        guard let adapter else {
            modelFetchError = "这个模型的适配器还没有接入，暂时拉不到模型列表。"
            return
        }
        switch await adapter.listModels() {
        case .success(let models):
            fetchedModels = models
            if models.isEmpty {
                modelFetchError = "服务商返回了空的模型列表。"
            }
        case .failure(let error):
            modelFetchError = (error as? BrainErrorInfo)?.message
                ?? "拉取模型列表失败。"
        }
    }

    private func appRegistryAdapter() -> BrainAdapter? {
        model.registryProvider().get(original.adapterId)
    }
}
