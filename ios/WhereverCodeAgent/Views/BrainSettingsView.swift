// BrainSettingsView.swift
// The brain management UI (checklist B9) — the visible surface of
// the brain layer: every brain the bootstrap seeds or the user
// adds is listed here with its kind, model and key state; each
// row opens an editor that writes the SAME stores the engine
// reads (BrainConfigStore for the config, BrainKeyStore for the
// secret). Front and back move together: an adapter that exists
// in code but cannot be configured here would be a shell.
//
// Android counterpart: the brain list + BrainManageDialog in the
// settings screen. Rules carried over:
// - The API key is write-only here: a SecureField that is never
//   prefilled and never displayed; leaving it empty keeps the
//   stored key. Keys live only in the Keychain (BrainKeyStore).
// - Deleting a built-in brain tombstones it (it does not come
//   back on the next launch or after an app update) and wipes its
//   key; the list's "恢复内置大脑" section brings it back.
// - "设为当前大脑" is the brain layer's own switch: turning every
//   brain off (no current brain) stops only brain-dependent
//   features — device Agents and single-agent chats keep working.
// - Model lists come from the provider's real /models endpoint
//   through the registered adapter; failures show the real error.

import SwiftUI

@MainActor
final class BrainSettingsModel: ObservableObject {
    @Published private(set) var configs: [BrainConfig] = []
    @Published private(set) var currentBrainId: String?

    let configStore: BrainConfigStore
    let keyStore: BrainKeyStore
    /// Called after every mutation so the owner can rebuild the
    /// adapter registry / engine from the new store state.
    var onChange: () -> Void = {}
    /// The owner's live adapter registry (rebuilt on change), for
    /// the editor's real model-list fetches.
    var registryProvider: () -> BrainAdapterRegistry = { BrainAdapterRegistry() }

    init(configStore: BrainConfigStore, keyStore: BrainKeyStore) {
        self.configStore = configStore
        self.keyStore = keyStore
        reload()
    }

    func reload() {
        configs = configStore.listConfigs()
        currentBrainId = configStore.currentBrainId()
    }

    func hasKey(for config: BrainConfig) -> Bool {
        guard let authRef = config.authRef else { return false }
        return keyStore.getSecret(authRef: authRef) != nil
    }

    func setCurrent(brainId: String?) {
        configStore.setCurrentBrainId(brainId)
        reload()
        onChange()
    }

    /// Save an edited config; a non-empty `apiKey` replaces the
    /// stored secret under the config's authRef (an empty field
    /// keeps the existing key — the field is never prefilled).
    func save(config: BrainConfig, apiKey: String) {
        let key = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        if let authRef = config.authRef, !key.isEmpty {
            try? keyStore.putSecret(authRef: authRef, value: key)
        }
        configStore.saveConfig(config)
        reload()
        onChange()
    }

    /// Delete a brain: wipe its secret, remove the config, and
    /// tombstone a built-in so the bootstrap never reseeds it.
    func delete(config: BrainConfig) {
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

    /// Built-ins the user deleted and can bring back.
    var restorableBrainIds: [String] {
        let present = Set(configs.map(\.brainId))
        return BrainBootstrap.seedableBrainIds.filter {
            !present.contains($0) && configStore.deletedBrainIds().contains($0)
        }
    }

    func restore(brainId: String) {
        if BrainBootstrap.restoreSeededBrain(configStore: configStore, brainId: brainId) {
            reload()
            onChange()
        }
    }

    /// Create a user generic-OpenAI brain. The brainId derives
    /// from the label slug with a counter suffix on collision; the
    /// authRef is per-brain so each generic brain owns its key.
    func createGenericBrain(label: String, preset: GenericOpenAiPreset?) -> BrainConfig {
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

    private static func slug(_ label: String) -> String {
        let lowered = label.lowercased()
        let scalars = lowered.unicodeScalars.map { scalar -> Character in
            CharacterSet.alphanumerics.contains(scalar) ? Character(scalar) : "-"
        }
        let joined = String(scalars).split(separator: "-", omittingEmptySubsequences: true)
            .joined(separator: "-")
        return joined.isEmpty ? "custom" : joined
    }
}

struct BrainSettingsView: View {
    @ObservedObject var model: BrainSettingsModel
    @State private var editing: BrainConfig?
    @State private var showAddGeneric = false
    @State private var newGenericLabel = ""
    @State private var newGenericPreset: GenericOpenAiPreset?

    var body: some View {
        Form {
            Section {
                if model.configs.isEmpty {
                    Text("还没有大脑。在下面添加一个模型 API 大脑，或恢复被删除的内置大脑。没有当前大脑时，只有大脑对话、团队协调和自动派活不可用；设备 Agent 和单兵对话不受影响。")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                ForEach(model.configs, id: \.brainId) { config in
                    Button {
                        editing = config
                    } label: {
                        BrainRowView(
                            config: config,
                            isCurrent: model.currentBrainId == config.brainId,
                            hasKey: model.hasKey(for: config))
                    }
                    .foregroundStyle(.primary)
                }
            } header: {
                Text("大脑列表")
            }
            Section("添加大脑") {
                ForEach(missingSeedableBrains, id: \.self) { brainId in
                    Button("添加 \(BrainBootstrap.seedConfigFor(brainId: brainId)?.label ?? brainId)") {
                        model.restore(brainId: brainId)
                    }
                }
                Button("添加通用模型 API（OpenAI 兼容）") {
                    showAddGeneric = true
                }
            }
            if !model.restorableBrainIds.isEmpty {
                Section("恢复内置大脑") {
                    ForEach(model.restorableBrainIds, id: \.self) { brainId in
                        Button("恢复 \(BrainBootstrap.seedConfigFor(brainId: brainId)?.label ?? brainId)") {
                            model.restore(brainId: brainId)
                        }
                    }
                }
            }
            Section {
                Text("大脑是可替换的推理后端：模型 API Key 只存本机 Keychain，不经过平台服务器。删掉全部大脑或不设当前大脑，不影响设备 Agent 与单兵对话。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("大脑")
        .onAppear { model.reload() }
        .sheet(item: $editing) { config in
            BrainEditorView(model: model, config: config)
        }
        .alert("添加通用模型 API", isPresented: $showAddGeneric) {
            TextField("名称（如 OpenAI）", text: $newGenericLabel)
            Button("添加") {
                let created = model.createGenericBrain(
                    label: newGenericLabel.trimmingCharacters(in: .whitespacesAndNewlines),
                    preset: newGenericPreset)
                newGenericLabel = ""
                editing = created
            }
            Button("取消", role: .cancel) { newGenericLabel = "" }
        } message: {
            Text("先给它起个名字，添加后在编辑页里选预置、填地址、模型和 Key。")
        }
    }

    /// Seedable built-ins that are neither present nor tombstoned
    /// (a tombstoned one belongs to the restore section, not add).
    private var missingSeedableBrains: [String] {
        let present = Set(model.configs.map(\.brainId))
        let deleted = model.configStore.deletedBrainIds()
        return BrainBootstrap.seedableBrainIds.filter {
            !present.contains($0) && !deleted.contains($0)
        }
    }
}

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
                            .font(.caption)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Color.blue.opacity(0.15))
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
                        Button("设为当前大脑") {
                            save()
                            model.setCurrent(brainId: original.brainId)
                            dismiss()
                        }
                    } else {
                        Button("取消当前大脑（停用大脑层）") {
                            model.setCurrent(brainId: nil)
                            dismiss()
                        }
                    }
                    Button("保存") {
                        save()
                        dismiss()
                    }
                    Button("删除这个大脑", role: .destructive) {
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
            .confirmationDialog("删除这个大脑？", isPresented: $showDeleteConfirm) {
                Button("删除", role: .destructive) {
                    model.delete(config: original)
                    dismiss()
                }
                Button("取消", role: .cancel) {}
            } message: {
                Text("配置和已存的 Key 都会删掉。内置大脑删除后不会自己回来，可以在列表里恢复（Key 需要重新填）。")
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

    /// Pull the provider's real model list through the registered
    /// adapter. A just-typed key is saved first so the adapter can
    /// use it; failures show the provider's real error text.
    private func fetchModels() async {
        // Save first (config + any just-typed key): the adapters
        // read the stores, so unsaved edits would fetch with the
        // old address and no key.
        save()
        isFetchingModels = true
        modelFetchError = nil
        defer { isFetchingModels = false }
        var adapter = appRegistryAdapter()
        if let generic = adapter as? GenericOpenAiBrainAdapter {
            // The shared generic adapter resolves the CURRENT brain;
            // bind it to the brain being edited before fetching.
            adapter = generic.forBrain(original.brainId)
        }
        guard let adapter else {
            modelFetchError = "这个大脑的适配器还没有接入，暂时拉不到模型列表。"
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

extension BrainConfig: Identifiable {
    public var id: String { brainId }
}
