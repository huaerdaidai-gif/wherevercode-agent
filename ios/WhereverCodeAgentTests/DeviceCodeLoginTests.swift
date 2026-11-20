// DeviceCodeLoginTests.swift
// The iOS device-code / login-only batch's contract, mirroring
// Android's DeviceCodeEngineTest / DeviceCodeManagerTest /
// BrainBootstrap seed tests:
// - the vendor table is config-only (adding a vendor = one row);
// - PKCE matches the RFC 7636 test vector;
// - the engine implements RFC 8628 once: device-code request,
//   pending/slow_down polling, error-first parsing, grant-death
//   callbacks, refresh;
// - the manager persists tokens and refreshes before use,
//   keeping an unrotated refresh token;
// - BrainBootstrap seeds the four membership brains with
//   Android's exact values, bringing iOS seeds to ten;
// - the login-only adapter refuses inference honestly.
// All network is a scripted fake transport; the token store runs
// over the real BrainKeyStore with a throwaway authRef.

import XCTest
@testable import WhereverCodeAgent

final class DeviceCodeLoginTests: XCTestCase {

    // MARK: - Fakes

    private actor FakeOAuthTransport: OAuthHttpTransport {
        var responses: [OAuthHttpResponse] = []
        private(set) var requests: [(url: String, params: [(String, String)])] = []

        func enqueue(_ response: OAuthHttpResponse) {
            responses.append(response)
        }

        func postForm(
            url: String,
            params: [(String, String)],
            headers: [String: String]
        ) async throws -> OAuthHttpResponse {
            requests.append((url, params))
            if responses.isEmpty {
                throw BrainErrorInfo(
                    code: "transport_error", message: "fake: no scripted response",
                    retryable: true)
            }
            return responses.removeFirst()
        }
    }

    private func jsonResponse(_ status: Int, _ body: String) -> OAuthHttpResponse {
        OAuthHttpResponse(status: status, body: body)
    }

    private var testAuthRef = ""

    override func setUp() {
        super.setUp()
        testAuthRef = "devicecode:test-\(UUID().uuidString)"
    }

    override func tearDown() {
        BrainKeyStore().deleteSecret(authRef: testAuthRef)
        super.tearDown()
    }

    private func makeManager(
        config: DeviceCodeConfig = DeviceCodeConfigs.qwen,
        transport: FakeOAuthTransport,
        clock: @escaping () -> Int64 = { 1_000_000 }
    ) -> DeviceCodeManager {
        DeviceCodeManager(
            config: config,
            tokenStore: DeviceCodeTokenStore(
                keyStore: BrainKeyStore(), authRef: testAuthRef),
            http: transport,
            clockMillis: clock)
    }

    // MARK: - Config table

    func testVendorTableShape() {
        XCTAssertEqual(DeviceCodeConfigs.all.count, 3)
        XCTAssertEqual(DeviceCodeConfigs.qwen.adapterId, "devicecode-qwen")
        XCTAssertEqual(DeviceCodeConfigs.qwen.authRef, "devicecode:qwen")
        XCTAssertEqual(DeviceCodeConfigs.kimi.brainId, "kimi")
        XCTAssertEqual(DeviceCodeConfigs.grok.brainId, "grok-membership")
        XCTAssertTrue(DeviceCodeConfigs.grok.experimental)
        XCTAssertTrue(DeviceCodeConfigs.kimi.needsDeviceHeaders)
        XCTAssertEqual(DeviceCodeConfigs.byId("kimi")?.displayName, "Kimi Code 订阅")
        XCTAssertNil(DeviceCodeConfigs.byId("gemini"))
        XCTAssertTrue(DeviceCodeConfigs.isDeviceCodeBrainId("qwen"))
        XCTAssertFalse(DeviceCodeConfigs.isDeviceCodeBrainId("deepseek"))
    }

    // MARK: - PKCE

    func testPkceMatchesRfc7636Vector() {
        // RFC 7636 Appendix B test vector.
        let challenge = OAuthPkce.challengeFor(
            verifier: "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        XCTAssertEqual(challenge, "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
        let verifier = OAuthPkce.generateVerifier()
        XCTAssertFalse(verifier.contains("="))
        XCTAssertFalse(verifier.contains("+"))
        XCTAssertFalse(verifier.contains("/"))
    }

    // MARK: - Engine: device code request

    func testRequestDeviceCodeParsesAndSendsPkce() async throws {
        let transport = FakeOAuthTransport()
        await transport.enqueue(jsonResponse(200, """
            {"device_code":"dc-1","user_code":"ABCD-EFGH",
             "verification_uri":"https://example.com/device",
             "verification_uri_complete":"https://example.com/device?user_code=ABCD-EFGH",
             "expires_in":600,"interval":5}
            """))
        let engine = DeviceCodeEngine(
            config: DeviceCodeConfigs.qwen, http: transport,
            clockMillis: { 0 }, sleeper: { _ in })
        let response = try await engine.requestDeviceCode(codeChallenge: "chal")
        XCTAssertEqual(response.userCode, "ABCD-EFGH")
        XCTAssertEqual(response.expiresInSeconds, 600)
        XCTAssertEqual(response.intervalSeconds, 5)
        let requests = await transport.requests
        XCTAssertEqual(requests.count, 1)
        let params = Dictionary(uniqueKeysWithValues: requests[0].params)
        XCTAssertEqual(params["client_id"], DeviceCodeConfigs.qwen.clientId)
        XCTAssertEqual(params["code_challenge"], "chal")
        XCTAssertEqual(params["code_challenge_method"], "S256")
        XCTAssertEqual(params["scope"], "openid profile email model.completion")
    }

    func testRequestDeviceCodeServerErrorIsTransport() async {
        let transport = FakeOAuthTransport()
        await transport.enqueue(jsonResponse(503, "{}"))
        let engine = DeviceCodeEngine(
            config: DeviceCodeConfigs.qwen, http: transport,
            clockMillis: { 0 }, sleeper: { _ in })
        do {
            _ = try await engine.requestDeviceCode(codeChallenge: "chal")
            XCTFail("expected throw")
        } catch let error as BrainErrorInfo {
            XCTAssertEqual(error.code, "transport_error")
            XCTAssertTrue(error.retryable)
        } catch {
            XCTFail("wrong error type")
        }
    }

    // MARK: - Engine: polling

    func testPollHandlesPendingSlowDownThenSuccess() async throws {
        let transport = FakeOAuthTransport()
        await transport.enqueue(jsonResponse(400, #"{"error":"authorization_pending"}"#))
        await transport.enqueue(jsonResponse(400, #"{"error":"slow_down"}"#))
        await transport.enqueue(jsonResponse(200, """
            {"access_token":"at-1","refresh_token":"rt-1","expires_in":3600,"scope":"openid"}
            """))
        let engine = DeviceCodeEngine(
            config: DeviceCodeConfigs.qwen, http: transport,
            clockMillis: { 0 }, sleeper: { _ in })
        let tokens = try await engine.pollForToken(
            deviceCode: "dc-1", codeVerifier: "verifier",
            intervalSeconds: 5, expiresInSeconds: 600)
        XCTAssertEqual(tokens.accessToken, "at-1")
        XCTAssertEqual(tokens.refreshToken, "rt-1")
        XCTAssertTrue(tokens.refreshable)
        let requests = await transport.requests
        XCTAssertEqual(requests.count, 3)
    }

    func testPollExpiredTokenKillsGrant() async {
        let transport = FakeOAuthTransport()
        await transport.enqueue(jsonResponse(400, #"{"error":"expired_token"}"#))
        let engine = DeviceCodeEngine(
            config: DeviceCodeConfigs.qwen, http: transport,
            clockMillis: { 0 }, sleeper: { _ in })
        var grantDead = false
        do {
            _ = try await engine.pollForToken(
                deviceCode: "dc-1", codeVerifier: "verifier",
                intervalSeconds: 5, expiresInSeconds: 600,
                onGrantDead: { grantDead = true })
            XCTFail("expected throw")
        } catch let error as BrainErrorInfo {
            XCTAssertEqual(error.code, "expired_token")
        } catch {
            XCTFail("wrong error type")
        }
        XCTAssertTrue(grantDead)
    }

    func testPollErrorOnHttp200IsNotAGrant() async {
        let transport = FakeOAuthTransport()
        await transport.enqueue(jsonResponse(200, #"{"error":"access_denied"}"#))
        let engine = DeviceCodeEngine(
            config: DeviceCodeConfigs.qwen, http: transport,
            clockMillis: { 0 }, sleeper: { _ in })
        do {
            _ = try await engine.pollForToken(
                deviceCode: "dc-1", codeVerifier: "verifier",
                intervalSeconds: 5, expiresInSeconds: 600)
            XCTFail("expected throw")
        } catch let error as BrainErrorInfo {
            XCTAssertEqual(error.code, "access_denied")
        } catch {
            XCTFail("wrong error type")
        }
    }

    // MARK: - Manager: login, persistence, refresh

    func testManagerLoginSavesTokensAndSignsIn() async throws {
        let transport = FakeOAuthTransport()
        await transport.enqueue(jsonResponse(200, """
            {"device_code":"dc-1","user_code":"ABCD-EFGH",
             "verification_uri":"https://example.com/device","expires_in":600,"interval":5}
            """))
        await transport.enqueue(jsonResponse(200, """
            {"access_token":"at-1","refresh_token":"rt-1","expires_in":3600}
            """))
        let manager = makeManager(transport: transport)
        let attempt = try await manager.beginLogin()
        XCTAssertEqual(attempt.response.userCode, "ABCD-EFGH")
        _ = try await manager.awaitAuthorization(attempt: attempt)
        XCTAssertTrue(manager.hasTokens())
        XCTAssertTrue(manager.isSignedIn())
        let token = try await manager.ensureFreshAccessToken()
        XCTAssertEqual(token, "at-1")
        manager.signOut()
        XCTAssertFalse(manager.hasTokens())
    }

    func testManagerRefreshKeepsUnrotatedRefreshToken() async throws {
        let store = DeviceCodeTokenStore(
            keyStore: BrainKeyStore(), authRef: testAuthRef)
        store.saveTokens(DeviceCodeTokens(
            accessToken: "old", refreshToken: "rt-old",
            expiresInSeconds: 3600, scope: "", obtainedAtMillis: 0))
        let transport = FakeOAuthTransport()
        // Refresh response omits a rotated refresh token.
        await transport.enqueue(jsonResponse(200, """
            {"access_token":"new","expires_in":3600}
            """))
        let manager = makeManager(transport: transport, clock: { 10_000_000 })
        let fresh = try await manager.ensureFreshAccessToken()
        XCTAssertEqual(fresh, "new")
        XCTAssertEqual(store.loadTokens()?.refreshToken, "rt-old")
    }

    func testManagerNotSignedInThrowsHonestError() async {
        let transport = FakeOAuthTransport()
        let manager = makeManager(transport: transport)
        do {
            _ = try await manager.ensureFreshAccessToken()
            XCTFail("expected throw")
        } catch let error as BrainErrorInfo {
            XCTAssertEqual(error.code, "device_code_not_signed_in")
        } catch {
            XCTFail("wrong error type")
        }
    }

    // MARK: - Token store JSON

    func testTokenStoreRoundTrip() {
        let store = DeviceCodeTokenStore(
            keyStore: BrainKeyStore(), authRef: testAuthRef)
        XCTAssertNil(store.loadTokens())
        store.saveTokens(DeviceCodeTokens(
            accessToken: "at", refreshToken: nil, expiresInSeconds: 600,
            scope: "openid", obtainedAtMillis: 42))
        let loaded = store.loadTokens()
        XCTAssertEqual(loaded?.accessToken, "at")
        XCTAssertNil(loaded?.refreshToken)
        XCTAssertEqual(loaded?.obtainedAtMillis, 42)
        store.clearTokens()
        XCTAssertNil(store.loadTokens())
    }

    // MARK: - Seeds + registry

    func testMembershipSeedsMatchAndroid() throws {
        XCTAssertEqual(BrainBootstrap.seedableBrainIds.count, 10)
        let qwen = BrainBootstrap.seedConfigFor(brainId: "qwen")
        XCTAssertEqual(qwen?.kind, .subscription)
        XCTAssertEqual(qwen?.adapterId, "devicecode-qwen")
        XCTAssertEqual(qwen?.label, "Qwen 会员")
        XCTAssertEqual(qwen?.authRef, "devicecode:qwen")
        XCTAssertEqual(qwen?.baseUrl, "https://portal.qwen.ai/v1/chat/completions")
        XCTAssertEqual(qwen?.model, "")
        let kimi = BrainBootstrap.seedConfigFor(brainId: "kimi")
        XCTAssertEqual(kimi?.adapterId, "devicecode-kimi")
        XCTAssertEqual(kimi?.label, "Kimi Code 订阅")
        let grok = BrainBootstrap.seedConfigFor(brainId: "grok-membership")
        XCTAssertEqual(grok?.adapterId, "devicecode-grok-membership")
        XCTAssertEqual(grok?.label, "Grok 会员（实验性）")
        let gemini = BrainBootstrap.seedConfigFor(brainId: "gemini")
        XCTAssertEqual(gemini?.adapterId, "oauth-gemini")
        XCTAssertEqual(gemini?.label, "Gemini（仅登录）")
        XCTAssertEqual(gemini?.extra[loginOnlyExtraKey], "true")
        XCTAssertNil(gemini?.authRef)
        XCTAssertNil(gemini?.baseUrl)
    }

    func testRegistryRegistersMembershipAdapters() throws {
        let data = try LocalData.inMemory()
        let store = KeyValueBrainConfigStore(kv: data.kv)
        let registry = BrainBootstrap.buildRegistry(configStore: store)
        XCTAssertNotNil(registry.get("devicecode-qwen"))
        XCTAssertNotNil(registry.get("devicecode-kimi"))
        XCTAssertNotNil(registry.get("devicecode-grok-membership"))
        XCTAssertNotNil(registry.get("oauth-gemini"))
        XCTAssertEqual(store.listConfigs().count, 10)
    }

    // MARK: - Login-only adapter

    func testLoginOnlyAdapterRefusesInference() async {
        let adapter = LoginOnlyBrainAdapter(config: VendorOAuthConfigs.gemini)
        XCTAssertEqual(adapter.adapterId, "oauth-gemini")
        XCTAssertFalse(adapter.capabilities().streaming)
        let models = await adapter.listModels()
        XCTAssertEqual((try? models.get())?.count, 0)
        let validation = await adapter.validateCredentials()
        XCTAssertTrue(validation.isFailure)
        var events: [BrainEvent] = []
        for try await event in adapter.chatStream(
            request: ChatRequest(messages: [], model: ""))
        {
            events.append(event)
        }
        XCTAssertEqual(events.count, 2)
        if case .error(let info) = events.first {
            XCTAssertEqual(info.code, "not_supported")
            XCTAssertTrue(info.message.contains("Vertex AI"))
        } else {
            XCTFail("first event must be the honest refusal")
        }
    }
}

private extension Result {
    var isFailure: Bool {
        if case .failure = self { return true }
        return false
    }
}
