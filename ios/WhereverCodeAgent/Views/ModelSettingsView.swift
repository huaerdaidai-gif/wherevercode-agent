// ModelSettingsView.swift
// 独立模型管理视图（对齐 2026-11-19 指示与 ~/agy-ios-model-decouple-brief.md）
// - 结构对齐「添加 ＋ 已添加」两块；
// - 预设（常用/免费 token 双入口）与智能路由在「添加」块；
// - 「已添加」分组列表完全基于独立 ModelRegistry / ModelConfigStore，绝不读写 brain.configs_json；
// - 增删改查全走新存储，与大脑彻底脱钩。

import Foundation
import SwiftUI

public struct ModelSettingsView: View {
    @ObservedObject var model: ModelSettingsModel
    @Environment(\.dismiss) private var dismiss

    @State private var editing: ModelConfig?
    @State private var showCommonPicker = false
    @State private var showFreePicker = false
    @State private var showDiscoveryPicker = false
    @State private var showRoutingPoolPicker = false
    @State private var showAddGeneric = false

    public init(model: ModelSettingsModel) {
        self.model = model
    }

    public var body: some View {
        Form {
            // MARK: - 块一：添加块（预设 + 智能路由）
            addSection
            routingSection

            // MARK: - 块二：已添加块（模型独立分组列表）
            addedModelsSection
        }
        .navigationTitle("模型")
        .onAppear { model.reload() }
        .sheet(item: $editing) { config in
            ModelEditorView(model: model, config: config)
        }
        .sheet(isPresented: $showCommonPicker) {
            ModelCommonProviderPickerSheet(model: model, onConfigCreated: { created in
                editing = created
            })
        }
        .sheet(isPresented: $showFreePicker) {
            ModelFreeProviderPickerSheet(model: model)
        }
        .sheet(isPresented: $showDiscoveryPicker) {
            ModelDeviceDiscoverySheet(model: model)
        }
        .sheet(isPresented: $showRoutingPoolPicker) {
            ModelRoutingPoolPickerSheet(model: model)
        }
        .sheet(isPresented: $showAddGeneric) {
            ModelGenericAddSheet(model: model)
        }
    }

    // MARK: - 1. 添加模型区（从预设添加）

    @ViewBuilder
    private var addSection: some View {
        Section {
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
                    Text("6 家官方核验")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
                }
            }

            Button {
                showDiscoveryPicker = true
            } label: {
                HStack {
                    Label("自动发现设备上的模型", systemImage: "network")
                    Spacer()
                    Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
                }
            }

            Button {
                showAddGeneric = true
            } label: {
                Label("添加通用模型 API（OpenAI 兼容）", systemImage: "plus.circle")
            }
        } header: { Text("从预设添加模型").textCase(nil) }
    }

    // MARK: - 2. 智能路由配置区

    @ViewBuilder
    private var routingSection: some View {
        Section {
            Toggle("启用智能路由", isOn: Binding(
                get: { model.routingSettings.enabled },
                set: { val in
                    var s = model.routingSettings
                    s.enabled = val
                    model.saveRoutingSettings(s)
                }
            ))

            Text("默认只在免费模型之间切换；发生额度不足、限流或网络故障时自动降级。")
                .font(.caption)
                .foregroundStyle(.secondary)

            Toggle("允许付费模型作为备选", isOn: Binding(
                get: { model.routingSettings.allowPaid },
                set: { val in
                    var s = model.routingSettings
                    s.allowPaid = val
                    model.saveRoutingSettings(s)
                }
            ))

            Button {
                showRoutingPoolPicker = true
            } label: {
                HStack {
                    Text("路由降级链配置")
                    Spacer()
                    Text("\(model.routingSettings.orderedBrainIds.count) 个模型在池")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
                }
            }
        } header: { Text("智能路由").textCase(nil) }
    }

    // MARK: - 3. 已添加模型列表（独立分组呈现）

    @ViewBuilder
    private var addedModelsSection: some View {
        Section {
        } header: { Text("已添加的模型").textCase(nil) }

        addedModelGroup(model.modelApis, kind: .modelApi, title: "模型 API")
        addedModelGroup(model.cliBrains, kind: .agentRuntime, title: "CLI 会员")
        addedModelGroup(model.subscriptions, kind: .subscription, title: "订阅授权")
    }

    private func addedModelGroup(_ configs: [ModelConfig], kind: BrainKind, title: String) -> some View {
        let deleted = model.configStore.deletedModelIds()
        let restorable = BrainBootstrap.seededConfigs.filter { seed in
            seed.kind == kind && deleted.contains(seed.brainId) && model.configStore.getConfig(modelId: seed.brainId) == nil
        }

        return Section {
            if configs.isEmpty {
                Text("暂无已添加的\(title)")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            ForEach(configs) { config in
                modelRow(config: config)
            }
            ForEach(restorable, id: \.brainId) { seed in
                Button("＋ 重新添加 \(seed.label)") {
                    let config = ModelConfig.fromBrainConfig(seed)
                    model.saveConfig(config)
                    if model.configStore.getConfig(modelId: config.modelId) != nil {
                        model.configStore.clearModelDeleted(modelId: config.modelId)
                        model.reload()
                        editing = config
                    }
                }
            }
        } header: { Text(title).textCase(nil) }
    }

    private func modelRow(config: ModelConfig) -> some View {
        Button {
            editing = config
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 6) {
                        Text(config.label)
                            .font(.headline)
                        if config.isCustomModel {
                            Text("自定义")
                                .font(.caption2)
                                .padding(.horizontal, 5)
                                .padding(.vertical, 1)
                                .background(Color.secondary.opacity(0.12), in: Capsule())
                        }
                        if config.extra[FreeProviderPresets.extraTier] == FreeProviderPresets.tierFree {
                            Text("免费")
                                .font(.caption2)
                                .padding(.horizontal, 5)
                                .padding(.vertical, 1)
                                .background(Color.green.opacity(0.15))
                                .clipShape(Capsule())
                        }
                    }
                    Text(rowSubtitle(config: config))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if model.currentModelId == config.modelId {
                    Text("✓ 在用")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(.green)
                }
                Image(systemName: "chevron.right")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
            }
        }
        .foregroundStyle(.primary)
    }

    private func rowSubtitle(config: ModelConfig) -> String {
        if config.kind == .subscription {
            // 订阅授权行只报登录状态（与安卓同口径）：ChatGPT 看 SIWC
            // 凭证，其余会员在 iOS 上尚未接入登录，一律未登录。
            if config.modelId == "siwc-chatgpt" {
                return model.hasKey(for: config) ? "认证通过" : "未连接"
            }
            return "未登录"
        }
        let m = config.model.isEmpty ? "未选模型" : config.model
        let keyStatus = model.hasKey(for: config) ? "已填 Key" : "未填 Key"
        if let summary = config.lastTestSummary, !summary.isEmpty {
            return "\(m) · \(summary)"
        }
        return "\(m) · \(keyStatus)"
    }
}

extension ModelConfig {
    var isCustomModel: Bool {
        modelId.hasPrefix("custom-") ||
        extra[FreeProviderPresets.extraPreset]?.hasPrefix("custom-") == true ||
        extra["model_origin"] == "manual"
    }
}

// MARK: - 模型编辑 Sheet

private struct ModelEditorView: View {
    @ObservedObject var model: ModelSettingsModel
    let config: ModelConfig
    @Environment(\.dismiss) private var dismiss

    @State private var label: String
    @State private var baseUrl: String
    @State private var modelName: String
    @State private var apiKey: String = ""
    @State private var showDeleteConfirm = false
    @State private var isTestingConnection = false
    @State private var connectionTestMessage: String?

    init(model: ModelSettingsModel, config: ModelConfig) {
        self.model = model
        self.config = config
        _label = State(initialValue: config.label)
        _baseUrl = State(initialValue: config.baseUrl ?? "")
        _modelName = State(initialValue: config.model)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("显示名称", text: $label)
                    TextField("服务地址（https://…）", text: $baseUrl)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("模型标识（如 gpt-4o）", text: $modelName)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } header: { Text("基本信息").textCase(nil) }

                Section {
                    SecureField(model.hasKey(for: config) ? "已保存 Key（留空保持不变）" : "输入 API Key", text: $apiKey)
                    Text("API Key 安全保存在本机 Keychain 中，绝不上传至任何云端。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } header: { Text("API Key 凭据").textCase(nil) }

                Section {
                    Button(isTestingConnection ? "正在测试连接…" : "测试连接") {
                        Task { await testSavedConnection() }
                    }
                    .disabled(isTestingConnection || hasUnsavedConnectionChanges)
                    if let connectionTestMessage, !hasUnsavedConnectionChanges {
                        Text(connectionTestMessage)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                } footer: {
                    Text("仅测试这一条已保存的连接。修改地址、模型或 Key 后，请保存并重新打开编辑页。")
                }

                Section {
                    Button("删除模型", role: .destructive) {
                        showDeleteConfirm = true
                    }
                }
            }
            .navigationTitle("编辑模型")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") { saveChanges() }
                }
            }
            .alert("确定删除模型？", isPresented: $showDeleteConfirm) {
                Button("删除", role: .destructive) {
                    model.deleteConfig(modelId: config.modelId)
                    dismiss()
                }
                Button("取消", role: .cancel) {}
            } message: {
                Text("确定删除「\(config.label)」吗？只删这一条模型配置，不删 Agent、不删设备。")
            }
        }
    }

    private var hasUnsavedConnectionChanges: Bool {
        baseUrl.trimmingCharacters(in: .whitespacesAndNewlines) != (config.baseUrl ?? "") ||
        modelName.trimmingCharacters(in: .whitespacesAndNewlines) != config.model ||
        !apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    @MainActor
    private func testSavedConnection() async {
        isTestingConnection = true
        connectionTestMessage = nil
        defer { isTestingConnection = false }

        let result: Result<Void, Error>
        switch config.adapterId {
        case GenericOpenAiBrainAdapter.adapterId, "generic":
            guard let address = config.baseUrl,
                  let url = URLComponents(string: address), url.host?.isEmpty == false,
                  url.user == nil, url.password == nil,
                  url.scheme?.lowercased() == "https" || BrainConfigValidator.isLocalModelUrl(address) else {
                connectionTestMessage = "尚未配置有效的模型服务地址。"
                return
            }
            let key = config.authRef.flatMap { model.keyStore.getSecret(authRef: $0) } ?? ""
            switch await GenericOpenAiApiClient().listModels(apiKey: key, baseUrl: address) {
            case .success: result = .success(())
            case .failure(let error): result = .failure(error)
            }
        default:
            // 内置适配器绑定内置 ID 和凭据，不能借它测试别的模型行。
            guard let seed = BrainBootstrap.seedConfigFor(brainId: config.modelId),
                  seed.adapterId == config.adapterId, seed.authRef == config.authRef,
                  let adapter = model.registryProvider().get(config.adapterId) else {
                connectionTestMessage = "iOS 暂不支持测试这条连接。"
                return
            }
            result = await adapter.validateCredentials()
        }

        switch result {
        case .success:
            connectionTestMessage = "连接验证通过。"
        case .failure(let error):
            connectionTestMessage = "连接验证失败：\(SecretRedactor.redact(error.localizedDescription))"
        }
    }

    private func saveChanges() {
        var updated = config
        updated.label = label.trimmingCharacters(in: .whitespacesAndNewlines)
        let b = baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        updated.baseUrl = b.isEmpty ? nil : b
        updated.model = modelName.trimmingCharacters(in: .whitespacesAndNewlines)
        let k = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        if !k.isEmpty {
            model.setKey(k, for: updated)
        }
        model.saveConfig(updated)
        dismiss()
    }
}

// MARK: - 常用模型服务商 Sheet

private struct ModelCommonProviderPickerSheet: View {
    @ObservedObject var model: ModelSettingsModel
    var onConfigCreated: (ModelConfig) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List(GenericOpenAiPresets.presets, id: \.displayName) { preset in
                Button {
                    let modelId = UUID().uuidString
                    let config = ModelConfig(
                        modelId: modelId,
                        kind: .modelApi,
                        adapterId: GenericOpenAiBrainAdapter.adapterId,
                        label: preset.displayName,
                        baseUrl: preset.baseUrl,
                        model: "",
                        authRef: "model-api:\(modelId)"
                    )
                    model.saveConfig(config)
                    dismiss()
                    onConfigCreated(config)
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

// MARK: - 免费 Token 模型服务商 Sheet (6 家官方核验)

private struct ModelFreeProviderPickerSheet: View {
    @ObservedObject var model: ModelSettingsModel
    @Environment(\.dismiss) private var dismiss

    @State private var selectedPreset: FreeProviderPreset?
    @State private var showCustomProviderForm = false
    @State private var accountId = ""
    @State private var apiKey = ""
    @State private var isFetchingLiveModels = false
    @State private var liveFetchError: String?
    @State private var modelsToSelect: [String] = []
    @State private var checkedModelIndices: Set<Int> = []
    @State private var step: Step = .selectProvider

    private enum Step {
        case selectProvider, inputAccountId, inputApiKey, selectModels
    }

    var body: some View {
        NavigationStack {
            Group {
                switch step {
                case .selectProvider: providerSelectionView
                case .inputAccountId: accountIdView
                case .inputApiKey: apiKeyView
                case .selectModels: modelSelectionView
                }
            }
            .navigationTitle(navTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(step == .selectProvider ? "取消" : "上一步") { handleBack() }
                }
            }
        }
    }

    private var navTitle: String {
        switch step {
        case .selectProvider: return "免费 token 模型服务商"
        case .inputAccountId: return selectedPreset?.displayName ?? "Account ID"
        case .inputApiKey: return selectedPreset?.displayName ?? "API Key"
        case .selectModels: return "选择要添加的模型"
        }
    }

    private var providerSelectionView: some View {
        let customs = model.customProviderStore.list()
        let all = CustomFreeProviders.merged(builtins: FreeProviderPresets.presets, customs: customs)
        return List {
            Section {
                ForEach(all, id: \.id) { preset in
                    Button {
                        selectedPreset = preset
                        if preset.needsAccountId { step = .inputAccountId } else { step = .inputApiKey }
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
                            Text(preset.keyHelp).font(.caption).foregroundStyle(.secondary)
                            if !preset.note.isEmpty {
                                Text(preset.note).font(.caption2).foregroundStyle(.tertiary)
                            }
                        }
                        .padding(.vertical, 2)
                    }
                }
            }
            Section {
                Button("＋ 添加自定义服务商（名单上没有的）") {
                    showCustomProviderForm = true
                }
            } footer: {
                Text("名单上没有的免费服务商可以在这里加；加完与内置预设走同一条添加流程。存本机、标「自定义」，与内置名单分开。")
            }
        }
        .sheet(isPresented: $showCustomProviderForm) {
            ModelCustomProviderFormSheet(model: model) { preset in
                selectedPreset = preset
                step = .inputApiKey
            }
        }
    }

    private var accountIdView: some View {
        Form {
            Section {
                TextField("输入 Account ID", text: $accountId)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            } header: { Text("Cloudflare Account ID").textCase(nil) }
            Section {
                Button("下一步") { step = .inputApiKey }
                    .disabled(accountId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
    }

    private var apiKeyView: some View {
        Form {
            Section {
                SecureField("输入 \(selectedPreset?.displayName ?? "") API Key", text: $apiKey)
                if let help = selectedPreset?.keyHelp {
                    Text(help).font(.caption).foregroundStyle(.secondary)
                }
            } header: { Text("API Key").textCase(nil) }
            if let err = liveFetchError {
                Section { Text(err).font(.caption).foregroundStyle(.red) }
            }
            Section {
                Button(isFetchingLiveModels ? "正在拉取模型列表…" : "获取可用模型") {
                    fetchModels()
                }
                .disabled(isFetchingLiveModels || apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
    }

    private var modelSelectionView: some View {
        List {
            Section {
                ForEach(Array(modelsToSelect.enumerated()), id: \.offset) { idx, mName in
                    Button {
                        if checkedModelIndices.contains(idx) {
                            checkedModelIndices.remove(idx)
                        } else {
                            checkedModelIndices.insert(idx)
                        }
                    } label: {
                        HStack {
                            Text(mName).font(.subheadline).foregroundStyle(.primary)
                            Spacer()
                            Image(systemName: checkedModelIndices.contains(idx) ? "checkmark.circle.fill" : "circle")
                                .foregroundStyle(checkedModelIndices.contains(idx) ? .blue : .secondary)
                        }
                    }
                }
            } header: { Text("勾选要添加的模型").textCase(nil) }
            Section {
                Button("添加所选 (\(checkedModelIndices.count))") {
                    saveSelected()
                }
                .disabled(checkedModelIndices.isEmpty)
            }
        }
    }

    private func fetchModels() {
        guard let preset = selectedPreset else { return }
        if preset.listMode == .fixed {
            modelsToSelect = preset.fixedModels
            checkedModelIndices = Set(0..<modelsToSelect.count)
            step = .selectModels
            return
        }
        isFetchingLiveModels = true
        liveFetchError = nil
        Task {
            let cleanKey = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
            let resolvedBase = preset.resolveBaseUrl(accountId: accountId)
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

    private func saveSelected() {
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

    private func handleBack() {
        switch step {
        case .selectProvider: dismiss()
        case .inputAccountId: step = .selectProvider
        case .inputApiKey: step = (selectedPreset?.needsAccountId == true) ? .inputAccountId : .selectProvider
        case .selectModels: step = .inputApiKey
        }
    }
}

// MARK: - 通用模型 API 添加 Sheet（手工添加：四栏表单＋测试连接＋保存，对齐安卓模型页手工表单）

private struct ModelGenericAddSheet: View {
    @ObservedObject var model: ModelSettingsModel
    @Environment(\.dismiss) private var dismiss

    @State private var label = ""
    @State private var baseUrl = ""
    @State private var modelName = ""
    @State private var apiKey = ""
    @State private var isTesting = false
    @State private var testMessage: String?

    private var canSave: Bool {
        !label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
        !baseUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("名称（如 OpenAI）", text: $label)
                    TextField("服务地址（https://…）", text: $baseUrl)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("模型标识（如 gpt-4o）", text: $modelName)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    SecureField("API Key（本机模型可留空）", text: $apiKey)
                } header: { Text("手工添加").textCase(nil) } footer: {
                    Text("API Key 安全保存在本机 Keychain 中，绝不上传至任何云端。")
                }

                Section {
                    Button(isTesting ? "正在测试连接…" : "测试连接") {
                        Task { await testConnection() }
                    }
                    .disabled(isTesting || baseUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    if let testMessage {
                        Text(testMessage)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                } footer: {
                    Text("测试连接会拉取该服务的模型列表，只证明服务可达并返回了模型。")
                }
            }
            .navigationTitle("添加通用模型 API")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存模型") { save() }
                        .disabled(!canSave)
                }
            }
        }
    }

    @MainActor
    private func testConnection() async {
        isTesting = true
        testMessage = nil
        defer { isTesting = false }
        let address = baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        let key = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        let result = await GenericOpenAiApiClient().listModels(apiKey: key.isEmpty ? "local" : key, baseUrl: address)
        switch result {
        case .success(let items):
            if items.isEmpty {
                testMessage = "服务可达，但没有返回任何模型；可手动填写模型标识。"
            } else {
                if modelName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    modelName = items[0].id
                }
                testMessage = "测试连接成功：读到 \(items.count) 个模型。"
            }
        case .failure(let err):
            testMessage = "测试连接失败：\(err.message)"
        }
    }

    private func save() {
        model.addModelConfig(
            label: label.trimmingCharacters(in: .whitespacesAndNewlines),
            baseUrl: baseUrl.trimmingCharacters(in: .whitespacesAndNewlines),
            model: modelName.trimmingCharacters(in: .whitespacesAndNewlines),
            apiKey: apiKey.trimmingCharacters(in: .whitespacesAndNewlines),
            extra: ["model_origin": "manual"]
        )
        dismiss()
    }
}

// MARK: - 自定义免费服务商录入 Sheet（对齐安卓 promptCustomFreeProvider 一套字段）

private struct ModelCustomProviderFormSheet: View {
    @ObservedObject var model: ModelSettingsModel
    @Environment(\.dismiss) private var dismiss
    var onSaved: (FreeProviderPreset) -> Void

    @State private var name = ""
    @State private var baseUrl = ""
    @State private var listMode: FreeModelListMode = .live
    @State private var fixedModelsText = ""
    @State private var errorMessage: String?

    private var canSave: Bool {
        !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
        !baseUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("服务商名称，如 某某云", text: $name)
                    TextField("服务地址 Base URL，如 https://api.example.com/v1", text: $baseUrl)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } header: { Text("添加自定义免费服务商").textCase(nil) } footer: {
                    Text("名单上没有的免费服务商可以在这里加；加完与内置预设走同一条添加流程。存本机、标「自定义」，与内置名单分开存。")
                }

                Section {
                    Picker("模型名单怎么来", selection: $listMode) {
                        Text("拉取模型列表（服务商有 /models 接口）").tag(FreeModelListMode.live)
                        Text("固定名单（我填模型 ID）").tag(FreeModelListMode.fixed)
                    }
                    .pickerStyle(.inline)
                    if listMode == .fixed {
                        TextEditor(text: $fixedModelsText)
                            .frame(minHeight: 96)
                        Text("把这家免费可用的模型 ID 逐行填进来（以实际调用为准）。")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                } header: { Text("模型名单怎么来").textCase(nil) }

                if let errorMessage {
                    Section {
                        Text(errorMessage).font(.caption).foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle("添加自定义服务商")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存并继续") { save() }
                        .disabled(!canSave)
                }
            }
        }
    }

    private func save() {
        let cleanName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        var cleanUrl = baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        while cleanUrl.hasSuffix("/") { cleanUrl.removeLast() }
        guard cleanUrl.hasPrefix("https://") || BrainConfigValidator.isLocalModelUrl(cleanUrl) else {
            errorMessage = "服务地址只允许 https（本机/局域网模型可用 http）。"
            return
        }
        var fixedModels: [String] = []
        if listMode == .fixed {
            fixedModels = fixedModelsText.split(separator: "\n").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
            guard !fixedModels.isEmpty else {
                errorMessage = "固定名单至少填一个模型 ID。"
                return
            }
        }
        let store = model.customProviderStore
        let existingIds = Set(FreeProviderPresets.presets.map(\.id) + store.list().map(\.id))
        let preset = FreeProviderPreset(
            id: CustomFreeProviders.customId(cleanName, existing: existingIds),
            displayName: cleanName,
            fixedModels: fixedModels,
            listMode: listMode,
            baseUrlTemplate: cleanUrl,
            keyHelp: "填你在这家注册好的免费 API Key（只存本机 Keychain）。",
            note: "自定义服务商：不在内置核验名单内，可用性以实际调用为准。"
        )
        store.add(preset)
        dismiss()
        onSaved(preset)
    }
}

// MARK: - 局域网与设备模型探测 Sheet（自动发现：勾选来源→真探测→勾选添加）

private struct ModelDeviceDiscoverySheet: View {
    struct Source: Identifiable {
        var id: String { host }
        let label: String
        let host: String
    }

    struct Candidate: Identifiable {
        var id: String { baseUrl + "|" + modelName }
        let label: String
        let modelName: String
        let baseUrl: String
    }

    @ObservedObject var model: ModelSettingsModel
    @Environment(\.dismiss) private var dismiss

    @State private var sources: [Source] = []
    @State private var checkedSourceIds: Set<String> = []
    @State private var candidates: [Candidate] = []
    @State private var selectedIndices: Set<Int> = []
    @State private var isScanning = false
    @State private var scanMessage: String?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(sources) { source in
                        Button {
                            if checkedSourceIds.contains(source.id) {
                                checkedSourceIds.remove(source.id)
                            } else {
                                checkedSourceIds.insert(source.id)
                            }
                        } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(source.label).font(.subheadline.weight(.semibold)).foregroundStyle(.primary)
                                    Text(source.host).font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer()
                                Image(systemName: checkedSourceIds.contains(source.id) ? "checkmark.circle.fill" : "circle")
                                    .foregroundStyle(checkedSourceIds.contains(source.id) ? .blue : .secondary)
                            }
                        }
                    }
                    Button(isScanning ? "正在探测…" : "开始探测") {
                        scanCheckedSources()
                    }
                    .disabled(isScanning || checkedSourceIds.isEmpty)
                } header: { Text("探测来源（勾选后才扫描）").textCase(nil) } footer: {
                    Text("探每台来源上的 Ollama（11434）/ LM Studio（1234）/ vLLM（8000）的模型列表；发现只是来源，添加的模型不与设备绑定，删设备不删模型。")
                }

                if let scanMessage {
                    Section {
                        Text(scanMessage)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                if !candidates.isEmpty {
                    Section {
                        ForEach(Array(candidates.enumerated()), id: \.offset) { idx, cand in
                            Button {
                                if selectedIndices.contains(idx) {
                                    selectedIndices.remove(idx)
                                } else {
                                    selectedIndices.insert(idx)
                                }
                            } label: {
                                HStack {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text("\(cand.label)：\(cand.modelName)").font(.subheadline.weight(.semibold)).foregroundStyle(.primary)
                                        Text(cand.baseUrl).font(.caption).foregroundStyle(.secondary)
                                    }
                                    Spacer()
                                    Image(systemName: selectedIndices.contains(idx) ? "checkmark.circle.fill" : "circle")
                                        .foregroundStyle(selectedIndices.contains(idx) ? .blue : .secondary)
                                }
                            }
                        }
                    } header: { Text("发现的模型端点").textCase(nil) }

                    Section {
                        Button("添加所选端点 (\(selectedIndices.count))") {
                            addSelected()
                        }
                        .disabled(selectedIndices.isEmpty)
                    }
                }
            }
            .navigationTitle("模型自动发现")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("完成") { dismiss() }
                }
            }
            .onAppear { loadSources() }
        }
    }

    /// 来源＝本机＋每台已登记设备（主机名取设备 baseUrl，同 DevicesView 的口径）。
    /// 默认不勾；只有本机一个来源时默认勾本机（同安卓单来源直探）。
    private func loadSources() {
        guard sources.isEmpty else { return }
        var list = [Source(label: "本机", host: "127.0.0.1")]
        let devices = (try? LocalData.shared.agents.listAgents()) ?? []
        for device in devices where device.isDeviceHost {
            guard let raw = device.baseUrl else { continue }
            let clean = raw.replacingOccurrences(of: "http://", with: "").replacingOccurrences(of: "https://", with: "")
            let host = String(clean.split(separator: ":").first ?? "")
            if !host.isEmpty && !list.contains(where: { $0.host == host }) {
                list.append(Source(label: device.displayName, host: host))
            }
        }
        sources = list
        if list.count == 1 {
            checkedSourceIds = ["127.0.0.1"]
        }
    }

    private func scanCheckedSources() {
        let targets = sources.filter { checkedSourceIds.contains($0.id) }
        guard !targets.isEmpty else { return }
        isScanning = true
        scanMessage = nil
        candidates = []
        selectedIndices = []
        Task {
            var found: [Candidate] = []
            for source in targets {
                let endpoints = await DeviceModelDiscovery.probeHost(source.host)
                for endpoint in endpoints {
                    for name in endpoint.models {
                        found.append(Candidate(
                            label: "\(source.label) · \(endpoint.serviceName)",
                            modelName: name,
                            baseUrl: endpoint.baseUrl
                        ))
                    }
                }
            }
            await MainActor.run {
                self.isScanning = false
                self.candidates = found
                self.selectedIndices = Set(found.indices)
                if found.isEmpty {
                    self.scanMessage = "没有发现模型端点：勾选的来源上都没有 Ollama / LM Studio / vLLM 在常用端口返回模型。"
                }
            }
        }
    }

    private func addSelected() {
        for idx in selectedIndices {
            let cand = candidates[idx]
            model.addModelConfig(
                label: "\(cand.label) · \(cand.modelName)",
                baseUrl: cand.baseUrl,
                model: cand.modelName,
                apiKey: "local",
                extra: [
                    FreeProviderPresets.extraTier: FreeProviderPresets.tierFree,
                    "discovery": "device",
                    "model_origin": "device-discovered"
                ]
            )
        }
        dismiss()
    }
}

// MARK: - 智能路由降级池选择 Sheet

private struct ModelRoutingPoolPickerSheet: View {
    @ObservedObject var model: ModelSettingsModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if model.configs.isEmpty {
                        Text("暂无已添加的模型，请先在模型页添加模型。")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    ForEach(model.configs, id: \.modelId) { config in
                        Button {
                            togglePool(config.modelId)
                        } label: {
                            HStack {
                                Text(config.label).foregroundStyle(.primary)
                                Spacer()
                                if isConfigInPool(config.modelId) {
                                    Image(systemName: "checkmark.circle.fill").foregroundStyle(.blue)
                                } else {
                                    Image(systemName: "circle").foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                } header: { Text("可选模型列表（点击加入/移出降级池）").textCase(nil) }
            }
            .navigationTitle("路由降级池配置")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
    }

    private func isConfigInPool(_ id: String) -> Bool {
        model.routingSettings.orderedBrainIds.contains(id)
    }

    private func togglePool(_ id: String) {
        var ids = model.routingSettings.orderedBrainIds
        if ids.contains(id) {
            ids.removeAll { $0 == id }
        } else {
            ids.append(id)
        }
        var s = model.routingSettings
        s.orderedBrainIds = ids
        model.saveRoutingSettings(s)
    }
}
