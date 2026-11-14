import XCTest
@testable import WhereverCodeAgent

/// Cloudflare family, ported behaviour from the Android
/// Cloudflare tests on main @4496658e: parser terminal semantics
/// (always Completed, truncation fails), error-code mapping (3036 /
/// 4006 quota, code-less 429 is rate limiting), request-body
/// pairing rules, provider guards, and the M2 bridge's quota
/// notice.
final class CloudflareTests: XCTestCase {

    // MARK: - Fakes

    private final class FakeConnection: CloudflareConnection {
        let statusCode: Int
        let lines: [String]
        let errorBody: String?
        init(statusCode: Int = 200, lines: [String] = [], errorBody: String? = nil) {
            self.statusCode = statusCode
            self.lines = lines
            self.errorBody = errorBody
        }
        func bodyLines() -> AsyncThrowingStream<String, Error> {
            let lines = self.lines
            return AsyncThrowingStream { continuation in
                for line in lines { continuation.yield(line) }
                continuation.finish()
            }
        }
        func readBodyText() async throws -> String { lines.joined(separator: "\n") }
        func readErrorBody() async -> String? { errorBody }
    }

    private final class FakeTransport: CloudflareTransport {
        var connection: CloudflareConnection?
        var lastUrl: String?
        var lastBody: String?
        func open(url: String, apiToken: String, requestBody: String) async throws -> CloudflareConnection {
            lastUrl = url
            lastBody = requestBody
            return connection ?? FakeConnection()
        }
    }

    private final class MemoryCredentialStore: CloudflareCredentialStore {
        var token: String?
        func saveToken(_ token: String) { self.token = token }
        func loadToken() -> String? { token }
        func clearToken() { token = nil }
    }

    // MARK: - Parser

    func testParserTextUsageAndDone() {
        let sse = """
        data: {"choices":[{"delta":{"content":"你好"},"finish_reason":null}]}

        data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}

        data: [DONE]
        """
        let events = CloudflareStreamParser.parseSseText(sse)
        XCTAssertEqual(events, [
            .textDelta("你好"),
            .completed(
                usage: CloudflareUsage(inputTokens: 10, outputTokens: 5, totalTokens: 15),
                fullText: "你好"),
        ])
    }

    func testParserToolCallAndFallbackId() {
        let sse = """
        data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"srv-1","function":{"name":"run","arguments":"{\\"a\\":"}}]},"finish_reason":null}]}

        data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"1}"}}]},"finish_reason":null}]}

        data: {"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"name":"ping","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}

        data: [DONE]
        """
        let events = CloudflareStreamParser.parseSseText(sse)
        XCTAssertEqual(events, [
            .toolCallDone(name: "run", arguments: "{\"a\":1}", callId: "srv-1"),
            .toolCallDone(name: "ping", arguments: "{}", callId: "cf-call-1"),
            .completed(usage: nil, fullText: nil),
        ])
    }

    func testParserTruncatedStreamFails() {
        let sse = """
        data: {"choices":[{"delta":{"content":"半截"},"finish_reason":null}]}
        """
        let events = CloudflareStreamParser.parseSseText(sse)
        XCTAssertEqual(events, [
            .textDelta("半截"),
            .failed(code: "stream_ended_without_terminal",
                    message: "Cloudflare 流在未收到 data: [DONE] 前结束（可能为网络中断或代理截断），已收到的文本不作成功回复。",
                    retryable: true),
        ])
    }

    // MARK: - URL + error bodies + HTTP mapping

    func testUrlAndErrorBodyParsing() {
        XCTAssertEqual(
            CloudflareApiClient.buildChatCompletionsUrl(
                baseUrl: "https://api.cloudflare.com/client/v4/accounts/", accountId: "acc1"),
            "https://api.cloudflare.com/client/v4/accounts/acc1/ai/v1/chat/completions")
        let openAi = CloudflareApiClient.parseErrorBody(
            "{\"error\":{\"code\":\"4006\",\"message\":\"daily free allocation used\"}}")
        XCTAssertEqual(openAi.code, "4006")
        XCTAssertEqual(openAi.message, "daily free allocation used")
        let envelope = CloudflareApiClient.parseErrorBody(
            "{\"success\":false,\"errors\":[{\"code\":3036,\"message\":\"Account limited\"}]}")
        XCTAssertEqual(envelope.code, "3036")
        XCTAssertEqual(envelope.message, "Account limited")
        let garbage = CloudflareApiClient.parseErrorBody("not json")
        XCTAssertNil(garbage.code)
        XCTAssertNil(garbage.message)
    }

    func testHttpErrorMapping() async {
        let quota = await CloudflareApiClient.mapHttpError(
            connection: FakeConnection(
                statusCode: 429,
                errorBody: "{\"success\":false,\"errors\":[{\"code\":3036,\"message\":\"limit\"}]}"),
            model: "m")
        XCTAssertEqual(quota.kind, .quotaExceeded)
        XCTAssertTrue(quota.message.contains("今日免费额度已用完"))
        XCTAssertTrue(quota.message.contains("服务器错误码：3036"))

        let rateLimited = await CloudflareApiClient.mapHttpError(
            connection: FakeConnection(statusCode: 429, errorBody: nil), model: "m")
        XCTAssertEqual(rateLimited.kind, .transport)
        XCTAssertTrue(rateLimited.message.contains("请求速率受限"))

        let paid = await CloudflareApiClient.mapHttpError(
            connection: FakeConnection(
                statusCode: 403,
                errorBody: "{\"error\":{\"code\":\"5035\",\"message\":\"paid\"}}"),
            model: "@cf/x")
        XCTAssertEqual(paid.kind, .unsupported)
        XCTAssertTrue(paid.message.contains("Workers Paid"))

        let auth = await CloudflareApiClient.mapHttpError(
            connection: FakeConnection(statusCode: 401, errorBody: nil), model: "m")
        XCTAssertEqual(auth.kind, .authFailed)
    }

    // MARK: - Request body

    func testRequestBodyPairing() throws {
        let request = CloudflareApiRequest(
            model: "@cf/meta/llama-3.1-8b-instruct-fp8-fast",
            messages: [
                CloudflareInputMessage(role: "developer", content: "指引"),
                CloudflareInputMessage(role: "user", content: "问题"),
                CloudflareInputMessage(
                    role: "assistant", content: "",
                    toolCalls: [CloudflareToolCall(id: "c1", name: "run", argumentsJson: "{}")]),
                CloudflareInputMessage(role: "tool", content: "结果", toolCallId: "c1"),
                CloudflareInputMessage(role: "tool", content: "旧结果"),
            ],
            instructions: "系统指引",
            tools: [CloudflareTool(name: "run", description: "跑", parametersJson: "{\"type\":\"object\"}")],
            temperature: 0.5)
        let body = try CloudflareApiClient.buildRequestBody(request)
        XCTAssertEqual(body["model"] as? String, "@cf/meta/llama-3.1-8b-instruct-fp8-fast")
        XCTAssertEqual(body["stream"] as? Bool, true)
        let streamOptions = body["stream_options"] as? [String: Any]
        XCTAssertEqual(streamOptions?["include_usage"] as? Bool, true)
        let messages = body["messages"] as? [[String: Any]]
        XCTAssertEqual(messages?.count, 6)
        XCTAssertEqual(messages?[0]["content"] as? String, "系统指引")
        XCTAssertEqual(messages?[1]["role"] as? String, "system")
        XCTAssertEqual(messages?[1]["content"] as? String, "指引")
        XCTAssertEqual(messages?[3]["content"] as? NSNull, NSNull())
        let calls = messages?[3]["tool_calls"] as? [[String: Any]]
        XCTAssertEqual(calls?[0]["id"] as? String, "c1")
        XCTAssertEqual(messages?[4]["role"] as? String, "tool")
        XCTAssertEqual(messages?[4]["tool_call_id"] as? String, "c1")
        XCTAssertEqual(messages?[5]["role"] as? String, "user")
        XCTAssertEqual(messages?[5]["content"] as? String, "[工具结果]\n旧结果")
        XCTAssertEqual(body["tool_choice"] as? String, "auto")

        XCTAssertThrowsError(try CloudflareApiClient.buildRequestBody(
            CloudflareApiRequest(
                model: "m",
                messages: [CloudflareInputMessage(role: "user", content: "x")],
                temperature: 6.0))) { error in
            XCTAssertEqual((error as? BrainError)?.kind, .unsupported)
        }
    }

    // MARK: - Provider + bridge

    private func collectProvider(
        _ stream: AsyncThrowingStream<BrainStreamEvent, Error>
    ) async -> [BrainStreamEvent] {
        var events: [BrainStreamEvent] = []
        do {
            for try await event in stream { events.append(event) }
        } catch {
            XCTFail("provider stream should not throw: \(error)")
        }
        return events
    }

    func testProviderGuardsAndHappyPath() async {
        let credentials = MemoryCredentialStore()
        let transport = FakeTransport()
        let unconfigured = CloudflareBrainAdapter(
            credentialStore: credentials,
            apiClient: CloudflareApiClient(transport: transport),
            accountIdProvider: { "acc1" },
            baseUrlProvider: { CloudflareBrainAdapter.accountsBaseUrl },
            configuredModelProvider: { "@cf/meta/llama-3.1-8b-instruct-fp8-fast" })
        // No token yet.
        var events = await collectProvider(unconfigured.streamInfer(
            request: BrainRequest(messages: [BrainMessage(role: .user, content: "在吗")])))
        XCTAssertEqual(events.count, 1)
        if case .failed(let error) = events[0] {
            XCTAssertEqual(error.kind, .notConfigured)
            XCTAssertTrue(error.message.contains("API Token"))
        } else {
            XCTFail("expected failed event, got \(events)")
        }
        XCTAssertFalse(unconfigured.isConfigured())

        credentials.token = "tok"
        XCTAssertTrue(unconfigured.isConfigured())
        transport.connection = FakeConnection(lines: [
            "data: {\"choices\":[{\"delta\":{\"content\":\"在的\"},\"finish_reason\":null}]}",
            "",
            "data: [DONE]",
        ])
        events = await collectProvider(unconfigured.streamInfer(
            request: BrainRequest(messages: [BrainMessage(role: .user, content: "在吗")])))
        XCTAssertEqual(events, [
            .delta("在的"),
            .completed(BrainResponse(text: "在的", model: "@cf/meta/llama-3.1-8b-instruct-fp8-fast")),
        ])
        XCTAssertEqual(
            transport.lastUrl,
            "https://api.cloudflare.com/client/v4/accounts/acc1/ai/v1/chat/completions")

        // Temperature out of range is rejected by the provider
        // before any request.
        events = await collectProvider(unconfigured.streamInfer(
            request: BrainRequest(
                messages: [BrainMessage(role: .user, content: "在吗")], temperature: 9.0)))
        XCTAssertEqual(events.count, 1)
        if case .failed(let error) = events[0] {
            XCTAssertEqual(error.kind, .unsupported)
        } else {
            XCTFail("expected unsupported failure, got \(events)")
        }
    }

    func testBridgeQuotaNoticeAndToolPairing() async {
        // Bridge request mapping: tool results keep their role and
        // id; assistant tool calls pass through.
        let mapped = CloudflareM2BrainAdapter.mapRequest(ChatRequest(
            messages: [
                ChatMessage(role: "assistant", content: "",
                            toolCalls: [ChatToolCall(id: "c1", name: "run", argumentsJson: "{}")]),
                ChatMessage(role: "tool", content: "结果", toolCallId: "c1"),
            ],
            model: "m", maxTokens: 100, temperature: 1.0))
        XCTAssertEqual(mapped.messages[0].toolCalls.count, 1)
        XCTAssertEqual(mapped.messages[1].role, .tool)
        XCTAssertEqual(mapped.messages[1].toolCallId, "c1")
        XCTAssertEqual(mapped.temperature, 1.0)
        XCTAssertEqual(mapped.maxOutputTokens, 100)

        // Quota failure: notice as text first, then the error.
        let credentials = MemoryCredentialStore()
        credentials.token = "tok"
        let transport = FakeTransport()
        transport.connection = FakeConnection(
            statusCode: 429,
            errorBody: "{\"success\":false,\"errors\":[{\"code\":3036,\"message\":\"limit\"}]}")
        let provider = CloudflareBrainAdapter(
            credentialStore: credentials,
            apiClient: CloudflareApiClient(transport: transport),
            accountIdProvider: { "acc1" },
            baseUrlProvider: { CloudflareBrainAdapter.accountsBaseUrl },
            configuredModelProvider: { "m" })
        let bridge = CloudflareM2BrainAdapter(
            provider: provider, configuredModel: { "m" })
        var events: [BrainEvent] = []
        do {
            for try await event in bridge.chatStream(request: ChatRequest(
                messages: [ChatMessage(role: "user", content: "在吗")], model: "")) {
                events.append(event)
            }
        } catch {
            XCTFail("bridge stream should not throw: \(error)")
        }
        XCTAssertEqual(events.count, 3)
        XCTAssertEqual(events[0], .textDelta(CloudflareM2BrainAdapter.quotaNotice))
        if case .error(let info) = events[1] {
            XCTAssertEqual(info.code, "quota_exceeded")
            XCTAssertEqual(info.retryable, false)
        } else {
            XCTFail("expected quota error, got \(events[1])")
        }
        XCTAssertEqual(events[2], .done(finishReason: "error"))
        let models = await bridge.listModels()
        XCTAssertEqual(try? models.get().map(\.id), ["m"])
    }

    func testCredentialStore() {
        let store = KeystoreCloudflareCredentialStore()
        XCTAssertNil(store.loadToken())
        XCTAssertNoThrow(try store.saveToken("tok-1"))
        XCTAssertEqual(store.loadToken(), "tok-1")
        XCTAssertThrowsError(try store.saveToken("  "))
        store.clearToken()
        XCTAssertNil(store.loadToken())
    }
}
