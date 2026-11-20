// BrainBootstrap.swift
// Ported from the Android app (brain/siwc/BrainBootstrap.kt, main
// @ 4496658) — the assembly the engine actually runs with, plus the
// first-run seed configs. Android's build() also wires the Room
// task stores, the tool plugins and the fanout/pipeline stores;
// those iOS counterparts land with the task-engine group (G) —
// what is here is the complete BRAIN side: seed configs, adapter
// registration and the engine factory.
//
// Seeding rules (Android, verbatim semantics): a stored config is
// never overwritten by a seed; a tombstoned (user-deleted) brain
// is never reseeded — across restarts AND across app updates,
// because the tombstones persist. The credential is never seeded:
// the user enters their own key or logs in with their own account.
//
// Registered adapters today: DeepSeek, the generic OpenAI-
// compatible adapter (any number of user brains), Claude, Grok,
// Muse Spark and Cloudflare (through its M2 bridge). The SIWC
// config is seeded like Android's, but its adapter registers only
// when a provider is injected — the SIWC body (OAuth + Responses
// API) is the next backend batch; until then a ChatGPT brain row
// exists in the list and honestly reports not-connected rather
// than faking a chat path. Membership seeds (Qwen / Kimi / Grok
// membership / Gemini login-only) are seeded with the device-code
// / login-only batch: the three device-code adapters register
// with their managers, and Gemini registers its login-only
// adapter (signing in never makes it a brain).

import Foundation

public enum BrainBootstrap {

    public static let siwcBrainId = "siwc-chatgpt"
    public static let siwcAuthRef = "siwc:tokens"
    public static let cloudflareBrainId = "cloudflare-workers-ai"
    public static let cloudflareAuthRef = "cloudflare:token"
    public static let deepseekBrainId = "deepseek"
    public static let deepseekAuthRef = "deepseek:api-key"
    public static let museBrainId = "muse-spark"
    public static let museAuthRef = "muse:api-key"
    public static let grokBrainId = "grok"
    public static let grokAuthRef = "grok:api-key"
    public static let claudeBrainId = "claude"
    public static let claudeAuthRef = "claude:api-key"
    public static let qwenBrainId = DeviceCodeConfigs.qwen.brainId
    public static let qwenAuthRef = DeviceCodeConfigs.qwen.authRef
    public static let kimiBrainId = DeviceCodeConfigs.kimi.brainId
    public static let kimiAuthRef = DeviceCodeConfigs.kimi.authRef
    public static let grokMembershipBrainId = DeviceCodeConfigs.grok.brainId
    public static let grokMembershipAuthRef = DeviceCodeConfigs.grok.authRef
    public static let geminiBrainId = "gemini"

    /// Brain ids this build seeds on first run — the same ten as
    /// Android's SEEDABLE_BRAIN_IDS, in the same order.
    public static let seedableBrainIds: [String] = [
        siwcBrainId,
        cloudflareBrainId,
        deepseekBrainId,
        museBrainId,
        grokBrainId,
        claudeBrainId,
        qwenBrainId,
        kimiBrainId,
        grokMembershipBrainId,
        geminiBrainId,
    ]

    public static var seededConfigs: [BrainConfig] {
        seedableBrainIds.compactMap { seedConfigFor(brainId: $0) }
    }

    /// The first-run seed config for a seedable brain id, or nil.
    /// Values are Android's seedConfigFor, verbatim.
    public static func seedConfigFor(brainId: String) -> BrainConfig? {
        switch brainId {
        case siwcBrainId:
            return BrainConfig(
                brainId: siwcBrainId, kind: .subscription, adapterId: siwcBrainId,
                label: "ChatGPT (Sign in with ChatGPT)", baseUrl: nil, model: "",
                authRef: siwcAuthRef, extra: [:], fallbackBrainIds: [])
        case cloudflareBrainId:
            return BrainConfig(
                brainId: cloudflareBrainId, kind: .modelApi, adapterId: cloudflareBrainId,
                label: "Cloudflare Workers AI", baseUrl: CloudflareBrainAdapter.accountsBaseUrl,
                model: CloudflareBrainAdapter.suggestedDefaultModel,
                authRef: cloudflareAuthRef,
                extra: [CloudflareBrainAdapter.extraAccountId: ""],
                fallbackBrainIds: [siwcBrainId])
        case deepseekBrainId:
            return BrainConfig(
                brainId: deepseekBrainId, kind: .modelApi, adapterId: DeepSeekBrainAdapter.adapterId,
                label: "DeepSeek API", baseUrl: DeepSeekBrainAdapter.defaultBaseUrl,
                model: DeepSeekApiClient.suggestedModels.first ?? "",
                authRef: deepseekAuthRef, extra: [:], fallbackBrainIds: [])
        case museBrainId:
            return BrainConfig(
                brainId: museBrainId, kind: .modelApi, adapterId: MuseBrainAdapter.adapterId,
                label: "Muse Spark", baseUrl: MuseBrainAdapter.defaultBaseUrl,
                model: MuseBrainAdapter.suggestedModel,
                authRef: museAuthRef, extra: [:], fallbackBrainIds: [])
        case grokBrainId:
            return BrainConfig(
                brainId: grokBrainId, kind: .modelApi, adapterId: GrokBrainAdapter.adapterId,
                label: "Grok", baseUrl: GrokBrainAdapter.defaultBaseUrl,
                model: GrokBrainAdapter.suggestedModel,
                authRef: grokAuthRef, extra: [:], fallbackBrainIds: [])
        case claudeBrainId:
            return BrainConfig(
                brainId: claudeBrainId, kind: .modelApi, adapterId: ClaudeBrainAdapter.adapterId,
                label: "Claude API", baseUrl: ClaudeBrainAdapter.defaultBaseUrl,
                model: ClaudeBrainAdapter.suggestedModel,
                authRef: claudeAuthRef, extra: [:], fallbackBrainIds: [])
        case qwenBrainId:
            return BrainConfig(
                brainId: qwenBrainId, kind: .subscription,
                adapterId: DeviceCodeConfigs.qwen.adapterId,
                label: DeviceCodeConfigs.qwen.displayName,
                baseUrl: DeviceCodeConfigs.qwen.inferenceBaseUrl,
                model: "", authRef: qwenAuthRef, extra: [:], fallbackBrainIds: [])
        case kimiBrainId:
            return BrainConfig(
                brainId: kimiBrainId, kind: .subscription,
                adapterId: DeviceCodeConfigs.kimi.adapterId,
                label: DeviceCodeConfigs.kimi.displayName,
                baseUrl: DeviceCodeConfigs.kimi.inferenceBaseUrl,
                model: "", authRef: kimiAuthRef, extra: [:], fallbackBrainIds: [])
        case grokMembershipBrainId:
            return BrainConfig(
                brainId: grokMembershipBrainId, kind: .subscription,
                adapterId: DeviceCodeConfigs.grok.adapterId,
                label: DeviceCodeConfigs.grok.displayName,
                baseUrl: DeviceCodeConfigs.grok.inferenceBaseUrl,
                model: "", authRef: grokMembershipAuthRef, extra: [:],
                fallbackBrainIds: [])
        case geminiBrainId:
            return BrainConfig(
                brainId: geminiBrainId, kind: .subscription,
                adapterId: LoginOnlyBrainAdapter.adapterId,
                label: "Gemini（仅登录）", baseUrl: nil, model: "",
                authRef: nil, extra: [loginOnlyExtraKey: "true"],
                fallbackBrainIds: [])
        default:
            return nil
        }
    }

    /// One device-code manager per vendor, keyed by brain id —
    /// the single construction the registry's adapters and the
    /// settings UI's login actions share. Token state lives in
    /// the Keychain-backed store, so separate manager instances
    /// over the same store always agree.
    public static func makeDeviceCodeManagers(
        keyStore: BrainKeyStore = BrainKeyStore(),
        http: OAuthHttpTransport = URLSessionOAuthHttpTransport()
    ) -> [String: DeviceCodeManager] {
        var managers: [String: DeviceCodeManager] = [:]
        for config in DeviceCodeConfigs.all {
            managers[config.brainId] = DeviceCodeManager(
                config: config,
                tokenStore: DeviceCodeTokenStore(
                    keyStore: keyStore, authRef: config.authRef),
                http: http,
                deviceHeaders: {
                    config.needsDeviceHeaders ? KimiDeviceHeaders.make() : [:]
                })
        }
        return managers
    }

    /// The config to register for a first-line brain: the stored
    /// one when present, else the seed — nil when the user deleted
    /// the brain (tombstone). The one seeding gate (Android).
    public static func seededConfig(
        configStore: BrainConfigStore, brainId: String
    ) -> BrainConfig? {
        if let stored = configStore.getConfig(brainId: brainId) { return stored }
        if configStore.deletedBrainIds().contains(brainId) { return nil }
        return seedConfigFor(brainId: brainId)
    }

    /// Re-add a deleted first-line brain (the brain list's
    /// "恢复内置大脑" action): clear the tombstone and write the
    /// seed back when no config is stored. The deleted credential
    /// is NOT restored — it was wiped. Returns false for ids that
    /// are not seedable first-line brains.
    @discardableResult
    public static func restoreSeededBrain(
        configStore: BrainConfigStore, brainId: String
    ) -> Bool {
        guard let seed = seedConfigFor(brainId: brainId) else { return false }
        configStore.clearBrainDeleted(brainId: brainId)
        if configStore.getConfig(brainId: brainId) == nil {
            configStore.saveConfig(seed)
        }
        return true
    }

    /// Build the adapter registry from the config store: seeds are
    /// persisted on first run, one adapter instance per adapterId,
    /// bound to its brain's stored config. `siwcProvider` plugs the
    /// SIWC body in when it lands; nil leaves ChatGPT unregistered
    /// (the engine then reports it unavailable, never fakes it).
    public static func buildRegistry(
        configStore: BrainConfigStore,
        keyStore: BrainKeyStore = BrainKeyStore(),
        siwcProvider: BrainProvider? = nil
    ) -> BrainAdapterRegistry {
        let registry = BrainAdapterRegistry()

        // Persist + register the first-line seeds.
        for brainId in seedableBrainIds {
            guard let config = seededConfig(configStore: configStore, brainId: brainId)
            else { continue }
            if configStore.getConfig(brainId: brainId) == nil {
                configStore.saveConfig(config)
            }
            registry.registerConfig(config)
        }
        // Register every stored config (user brains included).
        for config in configStore.listConfigs() {
            registry.registerConfig(config)
        }

        var registeredAdapterIds = Set<String>()
        func registerOnce(_ adapterId: String, _ make: () -> BrainAdapter) {
            guard !registeredAdapterIds.contains(adapterId) else { return }
            registeredAdapterIds.insert(adapterId)
            registry.registerAdapter(make())
        }

        let deviceCodeManagers = makeDeviceCodeManagers(keyStore: keyStore)

        for config in registry.allConfigs() {
            switch config.adapterId {
            case DeepSeekBrainAdapter.adapterId:
                registerOnce(config.adapterId) {
                    DeepSeekBrainAdapter(
                        credentialStore: KeystoreDeepSeekCredentialStore(keyStore: keyStore),
                        baseUrlProvider: {
                            configStore.getConfig(brainId: deepseekBrainId)?.baseUrl
                                ?? DeepSeekBrainAdapter.defaultBaseUrl
                        },
                        configuredModelProvider: {
                            configStore.getConfig(brainId: deepseekBrainId)?.model
                        })
                }
            case GenericOpenAiBrainAdapter.adapterId:
                registerOnce(config.adapterId) {
                    GenericOpenAiBrainAdapter(
                        configStore: configStore,
                        credentialStore: KeystoreGenericOpenAiCredentialStore(
                            keyStore: keyStore))
                }
            case ClaudeBrainAdapter.adapterId:
                registerOnce(config.adapterId) {
                    ClaudeBrainAdapter(
                        credentialStore: KeystoreClaudeCredentialStore(keyStore: keyStore),
                        baseUrlProvider: {
                            configStore.getConfig(brainId: claudeBrainId)?.baseUrl
                                ?? ClaudeBrainAdapter.defaultBaseUrl
                        },
                        configuredModelProvider: {
                            configStore.getConfig(brainId: claudeBrainId)?.model
                        })
                }
            case GrokBrainAdapter.adapterId:
                registerOnce(config.adapterId) {
                    GrokBrainAdapter(
                        credentialStore: KeystoreGrokCredentialStore(keyStore: keyStore),
                        baseUrlProvider: {
                            configStore.getConfig(brainId: grokBrainId)?.baseUrl
                                ?? GrokBrainAdapter.defaultBaseUrl
                        },
                        configuredModelProvider: {
                            configStore.getConfig(brainId: grokBrainId)?.model
                        })
                }
            case MuseBrainAdapter.adapterId:
                registerOnce(config.adapterId) {
                    MuseBrainAdapter(
                        credentialStore: KeystoreMuseCredentialStore(keyStore: keyStore),
                        baseUrlProvider: {
                            configStore.getConfig(brainId: museBrainId)?.baseUrl
                                ?? MuseBrainAdapter.defaultBaseUrl
                        },
                        configuredModelProvider: {
                            configStore.getConfig(brainId: museBrainId)?.model
                        })
                }
            case CloudflareBrainAdapter.adapterId:
                registerOnce(config.adapterId) {
                    let provider = CloudflareBrainAdapter(
                        credentialStore: KeystoreCloudflareCredentialStore(
                            keyStore: keyStore),
                        accountIdProvider: {
                            configStore.getConfig(brainId: cloudflareBrainId)?
                                .extra[CloudflareBrainAdapter.extraAccountId]
                        },
                        baseUrlProvider: {
                            configStore.getConfig(brainId: cloudflareBrainId)?.baseUrl
                                ?? CloudflareBrainAdapter.accountsBaseUrl
                        },
                        configuredModelProvider: {
                            configStore.getConfig(brainId: cloudflareBrainId)?.model
                        })
                    return CloudflareM2BrainAdapter(
                        provider: provider,
                        configuredModel: {
                            configStore.getConfig(brainId: cloudflareBrainId)?.model
                        })
                }
            case siwcBrainId:
                if let siwcProvider {
                    registerOnce(config.adapterId) {
                        SiwcM2BrainAdapter(provider: siwcProvider)
                    }
                }
            case DeviceCodeConfigs.qwen.adapterId,
                 DeviceCodeConfigs.kimi.adapterId,
                 DeviceCodeConfigs.grok.adapterId:
                registerOnce(config.adapterId) {
                    let deviceConfig = DeviceCodeConfigs.all
                        .first { $0.adapterId == config.adapterId }!
                    return DeviceCodeBrainAdapter(
                        config: deviceConfig,
                        manager: deviceCodeManagers[deviceConfig.brainId]!,
                        configStore: configStore)
                }
            case LoginOnlyBrainAdapter.adapterId:
                registerOnce(config.adapterId) {
                    LoginOnlyBrainAdapter(config: VendorOAuthConfigs.gemini)
                }
            default:
                break
            }
        }
        return registry
    }

    /// The engine the brain chat runs on, assembled the same way
    /// the registry is. The conversation store, tool registry,
    /// event log and approval gate are the app's own (data layer /
    /// services) and are passed in; callers rebuild the engine
    /// after config changes — components are cheap holders over
    /// the shared stores.
    public static func buildEngine(
        configStore: BrainConfigStore,
        registry: BrainAdapterRegistry,
        conversations: ConversationStore,
        tools: ToolRegistry,
        eventLog: EventLog,
        approvalGate: ApprovalGate,
        memory: MemoryStore = InMemoryMemoryStore(),
        skillStore: SkillStore? = nil
    ) -> BrainEngine {
        BrainEngine(
            adapters: registry,
            conversations: conversations,
            contextManager: ContextManager(),
            memory: memory,
            skillStore: skillStore,
            tools: tools,
            eventLog: eventLog,
            approvalGate: approvalGate,
            currentBrainId: { configStore.currentBrainId() },
            failoverPolicyProvider: { brainId in
                let routingSettings = UserDefaultsModelRoutingStore().load()
                guard routingSettings.enabled else { return nil }
                guard let config = configStore.getConfig(brainId: brainId),
                      config.kind == .modelApi else { return nil }
                return ModelRouting.policyFor(
                    settings: routingSettings,
                    configs: configStore.listConfigs(),
                    currentBrainId: brainId)
            })
    }
}
