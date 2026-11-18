// BrainBootstrapTests.swift
// The bootstrap's contract: first-run seeds land in the store
// with Android's exact values, a stored config is never
// overwritten by a seed, a tombstoned brain never reseeds, the
// registry carries an adapter for every registered config's
// adapterId, and SIWC registers only when a provider is plugged
// in. All over the real kv-backed config store (in-memory db).

import XCTest
@testable import WhereverCodeAgent

final class BrainBootstrapTests: XCTestCase {

    private func makeStore() throws -> KeyValueBrainConfigStore {
        let data = try LocalData.inMemory()
        return KeyValueBrainConfigStore(kv: data.kv)
    }

    private struct StubSiwcProvider: BrainProvider {
        var providerId: String { BrainBootstrap.siwcBrainId }
        var displayName: String { "ChatGPT (Sign in with ChatGPT)" }
        var capabilities: BrainProviderCapabilities {
            BrainProviderCapabilities(
                supportsStreaming: true, supportsTools: false,
                supportsSystemMessages: false, requiresFullHistory: true,
                supportsTemperature: false, supportsMaxOutputTokens: false,
                billsUserPlan: true, limitsNote: "stub")
        }
        func isConfigured() -> Bool { false }
        func infer(request: BrainRequest) async throws -> BrainResponse {
            throw BrainError(kind: .transport, message: "stub")
        }
        func streamInfer(request: BrainRequest) -> AsyncThrowingStream<BrainStreamEvent, Error> {
            AsyncThrowingStream { $0.finish() }
        }
        func revoke() async {}
    }

    func testFirstBuildSeedsAllBuiltinBrains() throws {
        let store = try makeStore()
        let registry = BrainBootstrap.buildRegistry(configStore: store)
        let ids = Set(store.listConfigs().map(\.brainId))
        XCTAssertEqual(ids, Set(BrainBootstrap.seedableBrainIds))
        XCTAssertEqual(registry.allConfigs().count, BrainBootstrap.seedableBrainIds.count)
    }

    func testSeedValuesMatchAndroid() throws {
        let store = try makeStore()
        _ = BrainBootstrap.buildRegistry(configStore: store)
        let deepseek = store.getConfig(brainId: BrainBootstrap.deepseekBrainId)
        XCTAssertEqual(deepseek?.model, DeepSeekApiClient.suggestedModels.first)
        XCTAssertEqual(deepseek?.authRef, "deepseek:api-key")
        let cloudflare = store.getConfig(brainId: BrainBootstrap.cloudflareBrainId)
        XCTAssertEqual(cloudflare?.fallbackBrainIds, [BrainBootstrap.siwcBrainId])
        XCTAssertEqual(
            cloudflare?.extra[CloudflareBrainAdapter.extraAccountId], "")
        XCTAssertEqual(cloudflare?.model, CloudflareBrainAdapter.suggestedDefaultModel)
        let siwc = store.getConfig(brainId: BrainBootstrap.siwcBrainId)
        XCTAssertEqual(siwc?.kind, .subscription)
        XCTAssertEqual(siwc?.authRef, "siwc:tokens")
    }

    func testAdaptersRegisteredForSeededBrains() throws {
        let store = try makeStore()
        let registry = BrainBootstrap.buildRegistry(configStore: store)
        XCTAssertNotNil(registry.get(DeepSeekBrainAdapter.adapterId))
        XCTAssertNotNil(registry.get(ClaudeBrainAdapter.adapterId))
        XCTAssertNotNil(registry.get(GrokBrainAdapter.adapterId))
        XCTAssertNotNil(registry.get(MuseBrainAdapter.adapterId))
        XCTAssertNotNil(registry.get(CloudflareBrainAdapter.adapterId))
        // No generic config exists yet, so no generic adapter.
        XCTAssertNil(registry.get(GenericOpenAiBrainAdapter.adapterId))
        // No SIWC provider plugged in: honestly unregistered.
        XCTAssertNil(registry.get(BrainBootstrap.siwcBrainId))
    }

    func testSiwcRegistersWhenProviderPluggedIn() throws {
        let store = try makeStore()
        let registry = BrainBootstrap.buildRegistry(
            configStore: store, siwcProvider: StubSiwcProvider())
        XCTAssertNotNil(registry.get(BrainBootstrap.siwcBrainId))
    }

    func testSeedNeverOverwritesStoredConfig() throws {
        let store = try makeStore()
        var custom = try XCTUnwrap(
            BrainBootstrap.seedConfigFor(brainId: BrainBootstrap.deepseekBrainId))
        custom.model = "user-chosen-model"
        store.saveConfig(custom)
        _ = BrainBootstrap.buildRegistry(configStore: store)
        XCTAssertEqual(
            store.getConfig(brainId: BrainBootstrap.deepseekBrainId)?.model,
            "user-chosen-model")
    }

    func testTombstonedBrainNeverReseedsAndRestores() throws {
        let store = try makeStore()
        _ = BrainBootstrap.buildRegistry(configStore: store)
        store.deleteConfig(brainId: BrainBootstrap.grokBrainId)
        store.markBrainDeleted(brainId: BrainBootstrap.grokBrainId)
        let registry = BrainBootstrap.buildRegistry(configStore: store)
        XCTAssertNil(store.getConfig(brainId: BrainBootstrap.grokBrainId))
        XCTAssertNil(registry.get(GrokBrainAdapter.adapterId))

        XCTAssertTrue(BrainBootstrap.restoreSeededBrain(
            configStore: store, brainId: BrainBootstrap.grokBrainId))
        XCTAssertNotNil(store.getConfig(brainId: BrainBootstrap.grokBrainId))
        XCTAssertFalse(store.deletedBrainIds().contains(BrainBootstrap.grokBrainId))
        // Not a seedable id: restore refuses.
        XCTAssertFalse(BrainBootstrap.restoreSeededBrain(
            configStore: store, brainId: "model-api-whatever"))
    }

    func testUserGenericBrainRegistersGenericAdapter() throws {
        let store = try makeStore()
        store.saveConfig(BrainConfig(
            brainId: "model-api-openai", kind: .modelApi,
            adapterId: GenericOpenAiBrainAdapter.adapterId,
            label: "OpenAI", baseUrl: "https://api.openai.com/v1",
            model: "", authRef: "generic:model-api-openai",
            extra: [:], fallbackBrainIds: []))
        let registry = BrainBootstrap.buildRegistry(configStore: store)
        XCTAssertNotNil(registry.get(GenericOpenAiBrainAdapter.adapterId))
    }
}
