// WhereverCode Agent — iOS phase-2 tests
//
// Covers the phase-2 acceptance points that can be proven without a
// real platform account:
// - The tab-selection regression (phase 1 pushed a Settings screen
//   onto the chat tab's stack; now selection is one AppState fact
//   and "去设置" is a tab switch).
// - Connection status reporting for the unconfigured case.
// - The reserved controls are labelled "服务端无此接口，阻塞中".
// - HTTP failures carry the platform's real response body.
// - ChannelFeed delivers the initial history poll and reports it
//   through onInitialPoll (network stubbed at URLProtocol level for
//   the MCP HTTP calls; the app code path is the production one).

import Foundation
import XCTest
@testable import WhereverCodeAgent

// MARK: - Tab navigation regression

@MainActor
final class TabNavigationTests: XCTestCase {

    func testDefaultTabIsChat() {
        let state = AppState()
        XCTAssertEqual(state.selectedTab, .chat)
    }

    func testGoToSettingsSelectsSettingsTab() {
        let state = AppState()
        state.goToSettings()
        XCTAssertEqual(state.selectedTab, .settings)
    }

    func testSelectionCanMoveToEveryTab() {
        let state = AppState()
        for tab in [AppTab.tasks, .devices, .settings, .chat] {
            state.selectedTab = tab
            XCTAssertEqual(state.selectedTab, tab)
        }
    }
}

// MARK: - Connection status

@MainActor
final class ConnectionStatusTests: XCTestCase {

    func testClearedAddressStatusNamesTheMissingAddress() async {
        // The prefill means a loaded state always has an address;
        // the missing-address status now belongs to an address the
        // user deliberately cleared in the form. Set it directly on
        // the state (the refresh reads the in-memory settings).
        let state = AppState()
        state.settings = AppSettings(platformBaseURLString: "", channelID: AppSettings.defaultChannelID)
        await state.refreshConnectionStatus()
        XCTAssertEqual(state.connectionStatus, .notConfigured("还没有填写平台地址。"))
        // Restore pristine defaults for later tests in this process.
        SettingsStore.save(AppSettings())
    }

    func testValidAddressWithoutTokenNamesTheMissingCredential() async {
        SettingsStore.save(AppSettings(platformBaseURLString: "https://example.invalid", channelID: "ch-1"))
        let state = AppState()
        // The test host must not have a token for this to be the
        // missing piece; remove any leftover from other tests.
        try? state.deleteToken()
        await state.refreshConnectionStatus()
        XCTAssertEqual(state.connectionStatus, .notConfigured("还没有平台凭证：在设置的「平台连接」里填入平台凭证，或在「平台令牌」里生成一个。"))
        // Restore pristine defaults (prefilled address, default channel).
        SettingsStore.save(AppSettings())
    }
}

// MARK: - Blocked-feature labelling

final class BlockedFeatureLabelTests: XCTestCase {

    func testServerlessControlsSaySo() {
        for feature in [ReservedFeature.approval, .pauseTask, .resumeTask, .stopTask, .retargetTask, .deviceManagement] {
            XCTAssertTrue(
                feature.note.contains("服务端无此接口，阻塞中"),
                "\(feature.label) must be labelled as blocked by a missing server interface, got: \(feature.note)"
            )
        }
    }
}

// MARK: - HTTP error body

final class HttpErrorBodyTests: XCTestCase {

    func testHttpErrorIncludesServerBody() {
        let error = McpClient.httpError(statusCode: 401, body: Data(#"{"error":"invalid token"}"#.utf8))
        XCTAssertTrue(error.message.contains("HTTP 401"), error.message)
        XCTAssertTrue(error.message.contains("invalid token"), error.message)
    }

    func testHttpErrorWithoutBodyFallsBackToStatusOnly() {
        let error = McpClient.httpError(statusCode: 502, body: Data())
        XCTAssertEqual(error.message, "平台返回 HTTP 502")
    }

    func testCallToolSurfacesRealHttpError() async throws {
        StubURLProtocol.handler = { _ in
            (HTTPURLResponse(url: URL(string: "https://stub.invalid/api/open/v1/mcp")!,
                             statusCode: 403, httpVersion: nil, headerFields: nil)!,
             Data("token lacks channel scope".utf8))
        }
        defer { StubURLProtocol.handler = nil }
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubURLProtocol.self]
        let client = McpClient(
            endpoint: URL(string: "https://stub.invalid/api/open/v1/mcp")!,
            tokenProvider: { "unit-test-token" },
            session: URLSession(configuration: config)
        )
        do {
            _ = try await client.callTool(name: "agent_channel_list")
            XCTFail("callTool must throw on HTTP 403")
        } catch let error as McpError {
            XCTAssertTrue(error.message.contains("HTTP 403"), error.message)
            XCTAssertTrue(error.message.contains("token lacks channel scope"), error.message)
        }
    }
}

// MARK: - ChannelFeed initial history

final class ChannelFeedInitialPollTests: XCTestCase {

    func testInitialPollDeliversHistoryAndFiresCallback() async throws {
        let toolJSON = #"{"messages":[{"id":"m-1","agent_id":"agent-a","agent_name":"示例 Agent","text":"第一条","created_at":1700000000.0}]}"#
        let envelope: [String: Any] = [
            "jsonrpc": "2.0",
            "result": [
                "content": [["type": "text", "text": toolJSON]],
                "isError": false,
            ] as [String: Any],
        ]
        let envelopeData = try JSONSerialization.data(withJSONObject: envelope)
        StubURLProtocol.handler = { _ in
            (HTTPURLResponse(url: URL(string: "https://stub.invalid/api/open/v1/mcp")!,
                             statusCode: 200, httpVersion: nil, headerFields: nil)!,
             envelopeData)
        }
        defer { StubURLProtocol.handler = nil }

        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubURLProtocol.self]
        let session = URLSession(configuration: config)
        let client = McpClient(
            endpoint: URL(string: "https://stub.invalid/api/open/v1/mcp")!,
            tokenProvider: { "unit-test-token" },
            session: session
        )
        let feed = ChannelFeed(
            channelID: "ch-1",
            transport: ChannelTransport(client: client),
            webSocketURL: URL(string: "wss://stub.invalid/api/open/v1/channel/ws?channel_id=ch-1")!,
            tokenProvider: { "unit-test-token" },
            session: session
        )

        let messagesArrived = expectation(description: "initial history delivered")
        let initialPollDone = expectation(description: "onInitialPoll fired")
        feed.onMessages = { messages in
            if messages.contains(where: { $0.id == "m-1" }) { messagesArrived.fulfill() }
        }
        feed.onInitialPoll = { error in
            XCTAssertNil(error)
            initialPollDone.fulfill()
        }
        feed.start()
        await fulfillment(of: [messagesArrived, initialPollDone], timeout: 20)
        feed.stop()
    }
}

// MARK: - Platform token issuance

/// The issuer is a port of Android's PlatformTokenIssuer: same tool
/// name, same arguments, same validation and error wording. The
/// network is a stub closure; nothing here touches a real platform.
final class PlatformTokenIssuerTests: XCTestCase {

    func testBlankAgentIdRejectedLocally() async {
        var called = false
        let issuer = PlatformTokenIssuer(callTool: { _, _ in
            called = true
            return [:]
        })
        let result = await issuer.issueToken(agentId: "  ", name: "App 生成")
        XCTAssertEqual(result, .failed("Agent ID 不能为空（如 chatgpt）。"))
        XCTAssertFalse(called, "invalid input must never reach the network")
    }

    func testAgentIdWithIllegalCharactersRejectedLocally() async {
        let issuer = PlatformTokenIssuer(callTool: { _, _ in [:] })
        let result = await issuer.issueToken(agentId: "bad id!", name: "App 生成")
        XCTAssertEqual(result, .failed("Agent ID 只能用字母、数字、横杠和下划线。"))
    }

    func testBlankNameRejectedLocally() async {
        let issuer = PlatformTokenIssuer(callTool: { _, _ in [:] })
        let result = await issuer.issueToken(agentId: "chatgpt", name: " ")
        XCTAssertEqual(result, .failed("令牌名称不能为空。"))
    }

    func testOverlongNameRejectedLocally() async {
        let issuer = PlatformTokenIssuer(callTool: { _, _ in [:] })
        let result = await issuer.issueToken(
            agentId: "chatgpt", name: String(repeating: "x", count: 101)
        )
        XCTAssertEqual(result, .failed("令牌名称不能超过 100 字。"))
    }

    func testSuccessParsesTokenAndSendsAndroidArguments() async {
        var seenTool: String?
        var seenArgs: [String: Any]?
        let issuer = PlatformTokenIssuer(callTool: { tool, args in
            seenTool = tool
            seenArgs = args
            return ["token": "v5_fixture-token", "token_id": "t-1",
                    "agent_id": "chatgpt", "name": "App 生成"]
        })
        // Surrounding whitespace is trimmed before the call, exactly
        // like Android's `trim()`.
        let result = await issuer.issueToken(agentId: " chatgpt ", name: " App 生成 ")
        XCTAssertEqual(
            result,
            .issued(token: "v5_fixture-token", tokenId: "t-1", agentId: "chatgpt", name: "App 生成")
        )
        XCTAssertEqual(seenTool, "issue_agent_token")
        XCTAssertEqual(seenArgs?["agent_id"] as? String, "chatgpt")
        XCTAssertEqual(seenArgs?["name"] as? String, "App 生成")
    }

    func testBlankTokenInResponseIsFailure() async {
        let issuer = PlatformTokenIssuer(callTool: { _, _ in ["token_id": "t-1"] })
        let result = await issuer.issueToken(agentId: "chatgpt", name: "App 生成")
        XCTAssertEqual(result, .failed("平台没有返回令牌（签发失败）。"))
    }

    func testNoCredentialMapsToNotConnected() async {
        let issuer = PlatformTokenIssuer(callTool: { _, _ in
            throw McpError.notConfigured("未配置平台 Agent 凭证：请先在设置里保存 Token。")
        })
        let result = await issuer.issueToken(agentId: "chatgpt", name: "App 生成")
        guard case .failed(let message) = result else {
            XCTFail("expected failure, got \(result)")
            return
        }
        XCTAssertTrue(message.hasPrefix("未连接平台"), message)
        XCTAssertTrue(message.contains("平台连接"), message)
    }

    func testUnknownToolMapsToOwnerHint() async {
        // The server answers non-owner calls and missing tools the
        // same way; the message must name both possibilities.
        let issuer = PlatformTokenIssuer(callTool: { _, _ in
            throw McpError.serverError("请求失败：未知工具")
        })
        let result = await issuer.issueToken(agentId: "chatgpt", name: "App 生成")
        guard case .failed(let message) = result else {
            XCTFail("expected failure, got \(result)")
            return
        }
        XCTAssertTrue(message.contains("issue_agent_token"), message)
        XCTAssertTrue(message.contains("owner"), message)
    }

    func testMissingAgentErrorPassesThrough() async {
        let issuer = PlatformTokenIssuer(callTool: { _, _ in
            throw McpError.serverError("Agent 不存在或已停用")
        })
        let result = await issuer.issueToken(agentId: "ghost", name: "App 生成")
        XCTAssertEqual(result, .failed("签发失败：Agent 不存在或已停用"))
    }
}

// MARK: - Channel selection

final class ChannelSelectionTests: XCTestCase {

    private func channel(_ id: String) -> ChannelInfo { ChannelInfo(id: id, name: id) }

    func testCurrentSelectionSurvivesWhenStillListed() {
        let resolved = ChannelSelection.resolvedChannelID(
            channels: [channel("a"), channel("b")], current: "b"
        )
        XCTAssertEqual(resolved, "b")
    }

    func testSingleChannelIsAutoSelected() {
        let resolved = ChannelSelection.resolvedChannelID(
            channels: [channel("only")], current: "something-else"
        )
        XCTAssertEqual(resolved, "only")
    }

    func testMultipleChannelsLeaveSelectionUntouched() {
        let resolved = ChannelSelection.resolvedChannelID(
            channels: [channel("a"), channel("b")], current: "something-else"
        )
        XCTAssertEqual(resolved, "something-else")
    }

    func testEmptyListLeavesSelectionUntouched() {
        let resolved = ChannelSelection.resolvedChannelID(channels: [], current: "keep-me")
        XCTAssertEqual(resolved, "keep-me")
    }
}

// MARK: - URLProtocol stub

/// Intercepts HTTP requests made through a session configured with
/// it, so tests exercise the production networking code without a
/// network. (WebSocket tasks do not go through URLProtocol; the feed
/// test only asserts the HTTP history path.)
final class StubURLProtocol: URLProtocol {
    static var handler: ((URLRequest) -> (HTTPURLResponse, Data))?

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let handler = Self.handler else {
            client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse))
            return
        }
        let (response, data) = handler(request)
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

@MainActor
final class LocalChatHomeTests: XCTestCase {
    func testMissingModelKeepsDraftWithoutCreatingConversation() async throws {
        let state = AppState()
        let data = try LocalData.inMemory()
        let model = LocalChatModel(appState: state, data: data)
        model.selectedBrain = ""
        model.draft = "保留这条消息"
        await model.send()
        XCTAssertEqual(model.draft, "保留这条消息")
        XCTAssertNotNil(model.error)
        XCTAssertFalse(model.isSending)
        XCTAssertTrue(try data.conversations.listConversations().isEmpty)
    }

    func testHistoryRestoresSameConversationAndNewDoesNotDeleteMessages() throws {
        let state = AppState()
        let data = try LocalData.inMemory()
        let conversation = try data.conversations.createConversation(brainId: "custom-local", title: "已有会话")
        try data.conversations.addMessage(ConversationMessage(id: "message-1", conversationId: conversation.id,
            role: "user", content: "已有消息", source: "user", createdAtEpochSeconds: 1))
        data.chatPrefs.currentSessionId = conversation.id
        let model = LocalChatModel(appState: state, data: data)
        XCTAssertEqual(model.conversationID, conversation.id)
        XCTAssertEqual(model.messages.map(\.content), ["已有消息"])
        model.newConversation()
        XCTAssertNil(data.chatPrefs.currentSessionId)
        XCTAssertTrue(model.messages.isEmpty)
        XCTAssertEqual(try data.conversations.listMessages(conversationId: conversation.id).count, 1)
        model.open(conversation)
        XCTAssertEqual(model.selectedBrain, "custom-local")
        XCTAssertEqual(model.messages.first?.id, "message-1")
        XCTAssertEqual(data.chatPrefs.currentSessionId, conversation.id)
    }

    func testNavigationCannotDiscardRunningTurn() throws {
        let state = AppState()
        let model = LocalChatModel(appState: state, data: try LocalData.inMemory())
        model.draft = "待发送"
        model.isSending = true
        model.newConversation()
        XCTAssertEqual(model.draft, "待发送")
        XCTAssertNotNil(model.error)
    }

    func testTwoTurnsUseSelectedAdapterAndPersistOneConversationWithoutPlatform() async throws {
        let state = AppState()
        let data = try LocalData.inMemory()
        let adapter = BrainLayerTests.FakeBrainAdapter(adapterId: "ui-test-adapter") { _, turn in
            [.textDelta("reply-\(turn)"), .done(finishReason: "stop")]
        }
        state.brainRegistry.registerAdapter(adapter)
        state.brainRegistry.registerConfig(BrainConfig(brainId: "ui-test-brain", kind: .modelApi,
            adapterId: adapter.adapterId, label: "Test", baseUrl: "https://example.invalid", model: "dynamic-model", authRef: nil))
        let model = LocalChatModel(appState: state, data: data)
        model.selectedBrain = "ui-test-brain"
        model.draft = "first"
        await model.send()
        let id = try XCTUnwrap(model.conversationID)
        model.draft = "second"
        await model.send()
        XCTAssertNil(model.error)
        XCTAssertEqual(model.conversationID, id)
        XCTAssertEqual(try data.conversations.listConversations().count, 1)
        XCTAssertEqual(model.messages.filter { $0.role == "user" }.map(\.content), ["first", "second"])
        XCTAssertEqual(model.messages.filter { $0.role == "assistant" }.map(\.content), ["reply-1", "reply-2"])
        XCTAssertEqual(adapter.requests.map(\.model), ["dynamic-model", "dynamic-model"])
        XCTAssertTrue(adapter.requests.allSatisfy { $0.tools.isEmpty })
        XCTAssertFalse(model.isSending)
    }

    func testFeatureIndexRetainsSeventyRoutesWithoutSecondShell() {
        XCTAssertEqual(FlagshipRoutes.all.count, 70)
        XCTAssertEqual(Set(FlagshipRoutes.all.map(\.id)).count, 70)
        var opened: AppTab?
        let capabilities = AppFlagshipCapabilities { opened = $0 }
        XCTAssertTrue(capabilities.openExistingFeature("channels"))
        XCTAssertEqual(opened, .channels)
        XCTAssertFalse(capabilities.openExistingFeature("editor"))
    }
}
