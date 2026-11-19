// WhereverCode Agent — iOS MVP tests
//
// Settings validation and Keychain round-trip. Fixture hosts use
// example.invalid / example.test only — no real addresses, no real
// tokens (the token fixture is an obviously fake placeholder).

import Foundation
import XCTest
@testable import WhereverCodeAgent

final class SettingsKeychainTests: XCTestCase {

    func testDefaultSettingsStartUnconfigured() {
        // Fresh-install behaviour: no platform address is baked in.
        // The default is empty, no endpoint is derived, and the
        // validation honestly reports the address as missing.
        let settings = AppSettings()
        XCTAssertEqual(settings.platformBaseURLString, AppSettings.defaultPlatformBaseURLString)
        XCTAssertEqual(settings.platformBaseURLString, "")
        XCTAssertNil(settings.mcpEndpoint)
        XCTAssertEqual(settings.validation, .missingBaseURL)
    }

    func testExplicitlyEmptyAddressIsMissing() {
        // An address the user deliberately cleared is still reported
        // as missing — no default ever overrides an in-memory value.
        let settings = AppSettings(platformBaseURLString: "", channelID: "ch-1")
        XCTAssertNil(settings.validatedBaseURL)
        XCTAssertNil(settings.mcpEndpoint)
        XCTAssertEqual(settings.validation, .missingBaseURL)
    }

    func testFullEndpointAddressBehavesLikeBareHost() {
        // Pasting the whole MCP endpoint (what every other client
        // shows as "the platform address") must work exactly like
        // the bare host form: the path is stripped, then re-derived.
        let settings = AppSettings(
            platformBaseURLString: "https://example.invalid/api/open/v1/mcp",
            channelID: "ch-1"
        )
        XCTAssertEqual(settings.validation, .ok)
        XCTAssertEqual(settings.mcpEndpoint?.absoluteString, "https://example.invalid/api/open/v1/mcp")
    }

    func testLoadDefaultsWhenNothingWasSaved() {
        let suiteName = "wherevercode.agent.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }
        XCTAssertEqual(SettingsStore.load(defaults: defaults), AppSettings())
    }

    func testLoadKeepsSavedEmptyAddressUnconfigured() {
        // A saved empty address stays empty (unconfigured) — no
        // default is ever substituted — while the user's saved
        // channel survives.
        let suiteName = "wherevercode.agent.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }
        SettingsStore.save(AppSettings(platformBaseURLString: "", channelID: "ch-9"), defaults: defaults)
        let loaded = SettingsStore.load(defaults: defaults)
        XCTAssertEqual(loaded.platformBaseURLString, "")
        XCTAssertEqual(loaded.channelID, "ch-9")
    }

    func testLoadKeepsUserSavedAddress() {
        let suiteName = "wherevercode.agent.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let original = AppSettings(platformBaseURLString: "https://example.invalid", channelID: "ch-9")
        SettingsStore.save(original, defaults: defaults)
        XCTAssertEqual(SettingsStore.load(defaults: defaults), original)
    }

    func testHttpsAddressValidatesAndDerivesEndpoints() {
        let settings = AppSettings(
            platformBaseURLString: "https://example.invalid/",
            channelID: "ch-1"
        )
        XCTAssertEqual(settings.validation, .ok)
        XCTAssertEqual(settings.mcpEndpoint?.absoluteString, "https://example.invalid/api/open/v1/mcp")
        XCTAssertEqual(
            settings.channelWebSocketURL(channelID: "ch-1")?.absoluteString,
            "wss://example.invalid/api/open/v1/channel/ws?channel_id=ch-1"
        )
    }

    func testHttpAddressIsRejected() {
        // ATS stays on: cleartext platform addresses are refused.
        let settings = AppSettings(platformBaseURLString: "http://example.invalid", channelID: "ch-1")
        XCTAssertNil(settings.validatedBaseURL)
        XCTAssertNotEqual(settings.validation, .ok)
    }

    func testAddressWithUserinfoIsRejected() {
        // A token must never hide inside the address.
        let settings = AppSettings(platformBaseURLString: "https://user:pass@example.invalid", channelID: "ch-1")
        XCTAssertNil(settings.validatedBaseURL)
    }

    func testAddressWithoutHostIsRejected() {
        XCTAssertNil(AppSettings(platformBaseURLString: "https://", channelID: "ch-1").validatedBaseURL)
        XCTAssertNil(AppSettings(platformBaseURLString: "not a url", channelID: "ch-1").validatedBaseURL)
    }

    func testMissingChannelIDIsReported() {
        let settings = AppSettings(platformBaseURLString: "https://example.invalid", channelID: "  ")
        XCTAssertEqual(settings.validation, .missingChannelID)
    }

    func testSettingsRoundTripThroughUserDefaults() {
        let suiteName = "wherevercode.agent.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let original = AppSettings(platformBaseURLString: "https://example.invalid", channelID: "ch-9")
        SettingsStore.save(original, defaults: defaults)
        XCTAssertEqual(SettingsStore.load(defaults: defaults), original)
    }

    func testKeychainSaveLoadDeleteRoundTrip() throws {
        // A unique service per test run so parallel CI runs cannot
        // collide on the simulator Keychain.
        let store = KeychainStore(service: "wherevercode.agent.test.\(UUID().uuidString)")
        let fakeToken = "test-token-placeholder-not-real"
        try store.saveToken(fakeToken)
        XCTAssertEqual(try store.loadToken(), fakeToken)
        XCTAssertTrue(store.hasToken)
        // Overwrite keeps a single item.
        try store.saveToken("test-token-placeholder-2")
        XCTAssertEqual(try store.loadToken(), "test-token-placeholder-2")
        try store.deleteToken()
        XCTAssertFalse(store.hasToken)
        XCTAssertThrowsError(try store.loadToken()) { error in
            XCTAssertEqual(error as? KeychainError, .notFound)
        }
    }
}
