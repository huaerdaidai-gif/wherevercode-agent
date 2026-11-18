import XCTest
@testable import WhereverCodeAgent

/// DeepSeek adapter family, ported behaviour from the Android
/// DeepSeek tests on main @4496658e: fragmented tool calls, the
/// null finish_reason trap, terminal honesty, wire-body pairing,
/// and adapter event mapping over a fake transport.
final class DeepSeekTests: XCTestCase {

    // MARK: - Stream parser

    func testParserAccumulatesFragmentedToolCall() {
        let sse = """
        data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_0","function":{"name":"list_files","arguments":""}}]},"finish_reason":null}]}

        data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\"path\\":"}}]},"finish_reason":null}]}

        data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"\\"}"}}]},"finish_reason":null}]}

        data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}

        data: [DONE]
        """
        let events = DeepSeekStreamParser.parseSseText(sse)
        XCTAssertEqual(events, [
            .toolCall(id: "call_0", name: "list_files", argumentsJson: "{\"path\":\"\"}"),
            .finished(reason: "tool_calls"),
        ])
    }

    func testParserNullFinishReasonDoesNotTerminateEarly() {
        // finish_reason is JSON null in non-terminal chunks; treating
        // it as a real reason was the Android blank-bubble bug.
        let sse = """
        data: {"choices":[{"delta":{"content":"你"},"finish_reason":null}]}

        data: {"choices":[{"delta":{"content":"好"},"finish_reason":null}]}

        data: [DONE]
        """
        let events = DeepSeekStreamParser.parseSseText(sse)
        XCTAssertEqual(events, [.text("你"), .text("好"), .finished(reason: "stop")])
    }

    func testParserReasoningOnlyStreamFails() {
        let sse = """
        data: {"choices":[{"delta":{"reasoning_content":"想一想"},"finish_reason":null}]}

        data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

        data: [DONE]
        """
        let events = DeepSeekStreamParser.parseSseText(sse)
        XCTAssertEqual(events, [
            .reasoning("想一想"),
            .failed(code: "reasoning_only",
                    message: "DeepSeek 只返回了推理内容，没有返回可显示的回答。已停止本轮。",
                    retryable: true),
        ])
    }

    func testParserStreamEndingWithoutDoneFails() {
        let parser = DeepSeekStreamParser()
        var events = parser.feedLine(
            "data: {\"choices\":[{\"delta\":{\"content\":\"半截\"},\"finish_reason\":null}]}")
        events += parser.finish()
        XCTAssertEqual(events, [
            .text("半截"),
            .failed(code: "stream_ended_without_terminal",
                    message: "DeepSeek 流在收到 [DONE] 前结束（网络中断或代理截断），已收到的内容不作为成功回复。",
                    retryable: true),
        ])
    }

    func testParserEmptyStreamFails() {
        let events = DeepSeekStreamParser.parseSseText("")
        XCTAssertEqual(events, [
            .failed(code: "stream_empty",
                    message: "DeepSeek 未返回任何内容（流为空）。",
                    retryable: true),
        ])
    }

    func testParserUsageChunk() {
        let sse = """
        data: {"choices":[{"delta":{"content":"答"},"finish_reason":null}]}

        data: {"choices":[],"usage":{"prompt_tokens":11,"completion_tokens":7}}

        data: [DONE]
        """
        let events = DeepSeekStreamParser.parseSseText(sse)
        XCTAssertEqual(events, [
            .text("答"),
            .usage(inputTokens: 11, outputTokens: 7),
            .finished(reason: "stop"),
        ])
    }

    func testParserErrorChunk() {
        let sse = """
        data: {"error":{"code":"invalid_api_key","message":"bad key"}}
        """
        let events = DeepSeekStreamParser.parseSseText(sse)
        XCTAssertEqual(events, [
            .failed(code: "invalid_api_key", message: "bad key", retryable: false),
        ])
    }

    // MARK: - Request body / URLs

    func testRequestBodyPairsToolCallsAndResults() {
        let request = ChatRequest(
            messages: [
                ChatMessage(role: "user", content: "列一下"),
                ChatMessage(
                    role: "assistant", content: "",
                    toolCalls: [ChatToolCall(id: "c1", name: "list_files", argumentsJson: "{\"path\":\"\"}")]),
                ChatMessage(role: "tool", content: "a.txt", toolCallId: "c1"),
                ChatMessage(role: "tool", content: "旧结果", toolCallId: nil),
            ],
            model: "deepseek-flash",
            tools: [ToolDef(name: "list_files", description: "列文件",
                            inputSchema: ["type": .string("object")])])
        let body = DeepSeekApiClient.buildRequestBody(request)
        XCTAssertEqual(body["model"] as? String, "deepseek-flash")
        let messages = body["messages"] as? [[String: Any]]
        XCTAssertEqual(messages?.count, 4)
        let assistant = messages?[1]
        XCTAssertTrue(assistant?["content"] is NSNull)
        let calls = assistant?["tool_calls"] as? [[String: Any]]
        XCTAssertEqual(calls?.first?["id"] as? String, "c1")
        let paired = messages?[2]
        XCTAssertEqual(paired?["role"] as? String, "tool")
        XCTAssertEqual(paired?["tool_call_id"] as? String, "c1")
        // Legacy unpaired tool result is downgraded to a user note.
        let legacy = messages?[3]
        XCTAssertEqual(legacy?["role"] as? String, "user")
        XCTAssertEqual(legacy?["content"] as? String, "[工具结果]\n旧结果")
        let tools = body["tools"] as? [[String: Any]]
        XCTAssertEqual(tools?.first?["type"] as? String, "function")
        XCTAssertEqual(body["tool_choice"] as? String, "auto")
    }

    func testUrlBuilding() {
        XCTAssertEqual(
            DeepSeekApiClient.chatCompletionsUrl("https://api.deepseek.com"),
            "https://api.deepseek.com/chat/completions")
        XCTAssertEqual(
            DeepSeekApiClient.chatCompletionsUrl("https://api.deepseek.com/"),
            "https://api.deepseek.com/chat/completions")
        XCTAssertEqual(
            DeepSeekApiClient.chatCompletionsUrl("https://x.example/chat/completions"),
            "https://x.example/chat/completions")
        XCTAssertEqual(
            DeepSeekApiClient.modelsUrl("https://api.deepseek.com"),
            "https://api.deepseek.com/models")
        XCTAssertEqual(
            DeepSeekApiClient.modelsUrl("https://x.example/chat/completions"),
            "https://x.example/models")
    }

    func testHttpErrorMapping() {
        XCTAssertEqual(
            DeepSeekApiClient.mapHttpError(401, detail: ""),
            .failed(code: "auth_failed",
                    message: "DeepSeek 授权失败（401）：API Key 无效或已失效。",
                    retryable: false))
        XCTAssertEqual(
            DeepSeekApiClient.mapHttpError(429, detail: ""),
            .failed(code: "rate_limited",
                    message: "DeepSeek 请求过于频繁（429）：请稍后再试。",
                    retryable: true))
    }

    // MARK: - Adapter over a fake transport

    private final class MemoryCredentialStore: DeepSeekCredentialStore {
        var key: String?
        func saveApiKey(_ apiKey: String) { key = apiKey }
        func loadApiKey() -> String? { key }
        func clearApiKey() { key = nil }
    }

    private final class FakeConnection: DeepSeekConnection {
        let statusCode: Int
        let lines: [String]
        let text: String
        init(statusCode: Int = 200, lines: [String] = [], text: String = "") {
            self.statusCode = statusCode
            self.lines = lines
            self.text = text
        }
        func bodyLines() -> AsyncThrowingStream<String, Error> {
            let lines = self.lines
            return AsyncThrowingStream { continuation in
                for line in lines { continuation.yield(line) }
                continuation.finish()
            }
        }
        func readBodyText() async throws -> String { text }
        func readErrorBody() async -> String? { text.isEmpty ? nil : text }
    }

    private final class FakeTransport: DeepSeekTransport {
        var postConnection: DeepSeekConnection?
        var getConnection: DeepSeekConnection?
        var lastPostedBody: String?
        func post(url: String, apiKey: String, requestBody: String) async throws -> DeepSeekConnection {
            lastPostedBody = requestBody
            return postConnection ?? FakeConnection()
        }
        func get(url: String, apiKey: String) async throws -> DeepSeekConnection {
            getConnection ?? FakeConnection()
        }
    }

    private func collect(_ stream: AsyncThrowingStream<BrainEvent, Error>) async -> [BrainEvent] {
        var events: [BrainEvent] = []
        do {
            for try await event in stream { events.append(event) }
        } catch {
            XCTFail("adapter stream should not throw: \(error)")
        }
        return events
    }

    func testAdapterMissingKey() async {
        let store = MemoryCredentialStore()
        let adapter = DeepSeekBrainAdapter(credentialStore: store)
        let events = await collect(adapter.chatStream(
            request: ChatRequest(messages: [ChatMessage(role: "user", content: "在吗")], model: "m")))
        XCTAssertEqual(events, [
            .error(code: "not_configured",
                   message: "未配置 DeepSeek API Key：请在控制台的 DeepSeek 设置里填写你自己的 Key。",
                   retryable: false),
            .done(finishReason: "error"),
        ])
    }

    func testAdapterStreamsTextAndToolCall() async {
        let store = MemoryCredentialStore()
        store.key = "sk-test"
        let transport = FakeTransport()
        transport.postConnection = FakeConnection(lines: [
            "data: {\"choices\":[{\"delta\":{\"content\":\"我看看\"},\"finish_reason\":null}]}",
            "",
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c9\",\"function\":{\"name\":\"list_files\",\"arguments\":\"{\\\"path\\\":\\\"docs\\\"}\"}}]},\"finish_reason\":null}]}",
            "",
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
            "",
            "data: [DONE]",
        ])
        let client = DeepSeekApiClient(transport: transport)
        let adapter = DeepSeekBrainAdapter(
            credentialStore: store,
            baseUrlProvider: { "https://api.deepseek.com" },
            configuredModelProvider: { "deepseek-flash" },
            apiClient: client)
        let events = await collect(adapter.chatStream(
            request: ChatRequest(messages: [ChatMessage(role: "user", content: "列文件")], model: "")))
        XCTAssertEqual(events, [
            .textDelta("我看看"),
            .toolCall(BrainToolCall(id: "c9", name: "list_files", args: ["path": .string("docs")])),
            .done(finishReason: "tool_calls"),
        ])
        // The configured model fills the blank request model.
        XCTAssertTrue(transport.lastPostedBody?.contains("\"deepseek-flash\"") == true)
    }

    func testAdapterInvalidToolArguments() async {
        let store = MemoryCredentialStore()
        store.key = "sk-test"
        let transport = FakeTransport()
        transport.postConnection = FakeConnection(lines: [
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"run\",\"arguments\":\"not-json\"}}]},\"finish_reason\":null}]}",
            "",
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
            "",
            "data: [DONE]",
        ])
        let adapter = DeepSeekBrainAdapter(
            credentialStore: store,
            baseUrlProvider: { "https://api.deepseek.com" },
            configuredModelProvider: { "deepseek-flash" },
            apiClient: DeepSeekApiClient(transport: transport))
        let events = await collect(adapter.chatStream(
            request: ChatRequest(messages: [ChatMessage(role: "user", content: "跑")], model: "")))
        XCTAssertTrue(events.contains(.error(
            code: "tool_arguments_invalid",
            message: "模型返回的工具参数不是合法 JSON（run），已停止本轮。",
            retryable: false)))
    }

    func testAdapterListModels() async {
        let store = MemoryCredentialStore()
        store.key = "sk-test"
        let transport = FakeTransport()
        transport.getConnection = FakeConnection(
            text: "{\"data\":[{\"id\":\"deepseek-flash\"},{\"id\":\"deepseek-v4-pro\"}]}")
        let adapter = DeepSeekBrainAdapter(
            credentialStore: store,
            baseUrlProvider: { "https://api.deepseek.com" },
            apiClient: DeepSeekApiClient(transport: transport))
        let result = await adapter.listModels()
        switch result {
        case .success(let models):
            XCTAssertEqual(models.map(\.id), ["deepseek-flash", "deepseek-v4-pro"])
        case .failure(let error):
            XCTFail("listModels failed: \(error)")
        }
    }
}
