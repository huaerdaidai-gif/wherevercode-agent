import SwiftUI

public final class BrainSettingsModel: ObservableObject {
    @Published public private(set) var configs: [BrainConfig] = []
    @Published public private(set) var currentBrainId: String?

    public let configStore: BrainConfigStore
    public let keyStore: BrainKeyStore
    public var onChange: (() -> Void)?

    public init(configStore: BrainConfigStore, keyStore: BrainKeyStore) {
        self.configStore = configStore
        self.keyStore = keyStore
        reload()
    }

    public func reload() {
        configs = configStore.listConfigs()
        currentBrainId = configStore.currentBrainId()
    }

    public var restorableBrainIds: [String] {
        let deleted = configStore.deletedBrainIds()
        return BrainBootstrap.seededConfigs.map(\.brainId).filter { deleted.contains($0) }
    }

    public func setCurrentBrain(id: String?) {
        configStore.setCurrentBrainId(id)
        reload()
        onChange?()
    }

    public func save(config: BrainConfig, apiKey: String) {
        configStore.saveConfig(config)
        let cleanKey = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        if !cleanKey.isEmpty, let authRef = config.authRef {
            try? keyStore.saveKey(cleanKey, for: authRef)
        }
        reload()
        onChange?()
    }

    public func delete(brainId: String) {
        if let cfg = configStore.getConfig(brainId: brainId), let ref = cfg.authRef {
            keyStore.deleteKey(for: ref)
        }
        configStore.deleteConfig(brainId: brainId)
        configStore.markBrainDeleted(brainId: brainId)
        reload()
        onChange?()
    }

    public func restoreSeeded(brainId: String) {
        if BrainBootstrap.restoreSeededBrain(configStore: configStore, brainId: brainId) {
            reload()
            onChange?()
        }
    }

    public func createGenericBrain(label: String, preset: GenericOpenAiPreset?) -> BrainConfig {
        let clean = label.trimmingCharacters(in: .whitespacesAndNewlines)
        let slug = clean.lowercased().replacingOccurrences(of: " ", with: "-")
        let id = "generic-\(slug)-\(Int(Date().timeIntervalSince1970))"
        let cfg = BrainConfig(
            brainId: id,
            kind: .modelApi,
            adapterId: "model-api-generic",
            label: clean.isEmpty ? "通用模型 API" : clean,
            baseUrl: preset?.baseUrl ?? "https://api.openai.com/v1",
            model: "gpt-4o-mini",
            authRef: "model-api:\(id)"
        )
        configStore.saveConfig(cfg)
        reload()
        onChange?()
        return cfg
    }
}

public struct BrainSettingsView: View {
    @ObservedObject public var model: BrainSettingsModel
    @State private var newBrainName = ""
    @State private var selectedPreset: GenericOpenAiPreset = GenericOpenAiPreset.all[0]

    public init(model: BrainSettingsModel) {
        self.model = model
    }

    public var body: some View {
        List {
            Section {
                Text("大脑是可替换的推理后端：模型 API Key 只存本机 Keychain，不经过平台服务器。删掉全部大脑或不设当前大脑，不影响设备 Agent 与单兵对话。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section("已配置大脑列表") {
                if model.configs.isEmpty {
                    Text("还没有大脑。在下面添加一个模型 API 大脑，或恢复被删除的内置大脑。")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(model.configs) { cfg in
                        NavigationLink {
                            BrainEditorView(model: model, config: cfg)
                        } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(cfg.label).font(.headline)
                                    Text("\(cfg.adapterId) · 模型：\(cfg.model ?? "未选择")")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                if model.currentBrainId == cfg.brainId {
                                    Text("当前大脑")
                                        .font(.caption2.bold())
                                        .padding(.horizontal, 8)
                                        .padding(.vertical, 4)
                                        .background(Color.green.opacity(0.18), in: Capsule())
                                }
                            }
                        }
                    }
                }
            }

            Section("添加通用模型 API（OpenAI 兼容）") {
                TextField("名称（如 OpenAI / Ollama）", text: $newBrainName)
                Picker("预设端点", selection: $selectedPreset) {
                    ForEach(GenericOpenAiPreset.all) { preset in
                        Text(preset.displayName).tag(preset)
                    }
                }
                Button("添加通用模型 API") {
                    _ = model.createGenericBrain(label: newBrainName, preset: selectedPreset)
                    newBrainName = ""
                }
            }

            if !model.restorableBrainIds.isEmpty {
                Section("恢复内置大脑") {
                    ForEach(model.restorableBrainIds, id: \.self) { bid in
                        Button("恢复 \(bid)") {
                            model.restoreSeeded(brainId: bid)
                        }
                    }
                }
            }
        }
        .navigationTitle("模型与提供方")
    }
}

public struct BrainEditorView: View {
    @ObservedObject public var model: BrainSettingsModel
    @State public var config: BrainConfig
    @State private var apiKeyInput = ""
    @State private var savedBanner: String?

    public var body: some View {
        Form {
            Section("基本信息") {
                LabeledContent("大脑 ID", value: config.brainId)
                TextField("显示名称", text: $config.label)
                if config.adapterId == "siwc-m2" {
                    Text("ChatGPT 会员大脑：通过 Sign in with ChatGPT 登录后使用订阅额度。登录接入正在开发中，这一页先如实标注状态，不提供假的登录按钮。")
                        .font(.caption)
                        .foregroundStyle(.orange)
                } else {
                    TextField("服务地址（https://…）", text: Binding(
                        get: { config.baseUrl ?? "" },
                        set: { config.baseUrl = $0 }
                    ))
                    TextField("模型 ID", text: Binding(
                        get: { config.model ?? "" },
                        set: { config.model = $0 }
                    ))
                }
            }

            if config.adapterId != "siwc-m2" {
                Section("API 密钥（仅存 iOS Keychain）") {
                    SecureField("已保存（留空不改，输入则替换）", text: $apiKeyInput)
                    Text("只存本机 Keychain，不显示、不写日志、不经过平台服务器。")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }

            Section("操作") {
                Button("保存配置") {
                    model.save(config: config, apiKey: apiKeyInput)
                    apiKeyInput = ""
                    savedBanner = "配置已保存。"
                }
                Button("设为当前大脑") {
                    model.setCurrentBrain(id: config.brainId)
                    savedBanner = "已设为当前大脑。"
                }
                Button("删除这个大脑", role: .destructive) {
                    model.delete(brainId: config.brainId)
                }
            }

            if let banner = savedBanner {
                Section {
                    Text(banner).foregroundStyle(.green).font(.caption)
                }
            }
        }
        .navigationTitle(config.label)
    }
}
