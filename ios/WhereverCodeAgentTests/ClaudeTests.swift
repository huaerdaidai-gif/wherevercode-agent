import XCTest
@testable import WhereverCodeAgent

/// Claude family, ported behaviour from the Android Claude tests
/// on main @4496658e: Messages-API SSE parsing (no [DONE], ends at
/// message_stop), tool input accumulated across fragments, empty
/// replies failing instead of showing a blank bubble, the native
/// request-body rules (top-level system, strict alternation,
/// tool_use/tool_result blocks), and the HTTP error mapping.
final class ClaudeTests: XCTestCase {

    // MARK: - Parser

    func testParserFullStream() {
        let sse = """
        event: message_start
        data: {"type":"message_start","message":{"usage":{"input_tokens":10}}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"你好"}}

        event: content_block_start
        data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"run"}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"cmd\\":"}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\\"ls\\"}"}}

        event: message_delta
        data: {"type":"message_delta","delta":{"stop_reason":"tool_calls"},"usage":{"output_tokens":5}}

        event: message_stop
        data: {"type":"message_stop"}
        """
        let events = ClaudeStreamParser.parseSseText(sse)
        XCTAssertEqual(events, [
            .usage(inputTokens: 10, outputTokens: nil),
            .text("你好"),
            .usage(inputTokens: nil, outputTokens: 5),
            .toolCall(id: "toolu_1", name: "run", argumentsJson: "{\"cmd\":\"ls\"}"),
            .finished(reason: "tool_calls"),
        ])
    }

    func testParserEmptyTruncatedAndErrorEvent() {
        XCTAssertEqual(ClaudeStreamParser.parseSseText(""), [
            .failed(code: "stream_empty",
                    message: "Claude 未返回任何内容（流为空）。",
                    retryable: true),
        ])
        let truncated = """
        data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"半截"}}
        """
        XCTAssertEqual(ClaudeStreamParser.parseSseText(truncated), [
            .text("半截"),
            .failed(code: "stream_ended_without_terminal",
                    message: "Claude 流在收到 message_stop 前结束（网络中断或代理截断），已收到的内容不作为成功回复。",
                    retryable: true),
        ])
        let errorEvent = """
        data: {"type":"error","error":{"type":"overloaded_error","message":"过载了"}}
        """
        XCTAssertEqual(ClaudeStreamParser.parseSseText(errorEvent), [
            .failed(code: "overloaded_error", message: "过载了", retryable: true),
        ])
        // Usage/stop only, no text and no tool call: NOT a success.
        let usageOnly = """
        data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":3}}

        data: {"type":"message_stop"}
        """
        XCTAssertEqual(ClaudeStreamParser.parseSseText(usageOnly), [
            .usage(inputTokens: nil, outputTokens: 3),
            .failed(code: "empty_response",
                    message: "Claude 返回了空回复（没有文字或工具调用）。已停止本轮。",
                    retryable: true),
        ])
    }

    // MARK: - URLs + HTTP mapping + error body

    func testUrlsAndHttpMapping() {
        XCTAssertEqual(
            ClaudeApiClient.messagesUrl("https://api.anthropic.com/"),
            "https://api.anthropic.com/v1/messages")
        XCTAssertEqual(
            ClaudeApiClient.messagesUrl("https://proxy.example/v1/messages"),
            "https://proxy.example/v1/messages")
        XCTAssertEqual(
            ClaudeApiClient.modelsUrl("https://proxy.example/v1/messages"),
            "https://proxy.example/v1/models")
        XCTAssertEqual(
            ClaudeApiClient.modelsUrl("https://api.anthropic.com"),
            "https://api.anthropic.com/v1/models")

        XCTAssertEqual(
            ClaudeApiClient.mapHttpError(status: 401, detail: "（错误：authentication_error） bad key"),
            .failed(code: "auth_failed",
                    message: "Claude 授权失败（401）：API Key 无效或已失效。（错误：authentication_error） bad key",
                    retryable: false))
        XCTAssertEqual(
            ClaudeApiClient.mapHttpError(status: 529, detail: ""),
            .failed(code: "overloaded",
                    message: "Claude 服务过载（529）：请稍后再试。",
                    retryable: true))
        XCTAssertEqual(
            ClaudeApiClient.parseErrorBody(
                "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"too long\"}}"),
            "（错误：invalid_request_error） too long")
        XCTAssertEqual(ClaudeApiClient.parseErrorBody(nil), "")
    }

    // MARK: - Request body

    func testRequestBodyRules() {
        let request = ChatRequest(
            messages: [
                ChatMessage(role: "system", content: "规则一"),
                ChatMessage(role: "system", content: "规则二"),
                ChatMessage(role: "assistant", content: "过时的开场"),
                ChatMessage(role: "user", content: "问题一"),
                ChatMessage(role: "user", content: "问题二"),
                ChatMessage(role: "assistant", content: "",
                            toolCalls: [ChatToolCall(id: "t1", name: "run", argumentsJson: "{\"a\":1}")]),
                ChatMessage(role: "tool", content: "结果", toolCallId: "t1"),
            ],
            model: "claude-sonnet-4-5",
            tools: [ToolDef(name: "run", description: "跑命令",
                            inputSchema: ["type": .string("object")])])
        let body = ClaudeApiClient.buildRequestBody(request)
        XCTAssertEqual(body["model"] as? String, "claude-sonnet-4-5")
        XCTAssertEqual(body["max_tokens"] as? Int, 4096)
        XCTAssertEqual(body["stream"] as? Bool, true)
        XCTAssertEqual(body["system"] as? String, "规则一\n\n规则二")
        let messages = body["messages"] as? [[String: Any]]
        // Leading assistant dropped; the two users merged; then
        // assistant tool_use; then user tool_result.
        XCTAssertEqual(messages?.count, 3)
        XCTAssertEqual(messages?[0]["role"] as? String, "user")
        let mergedUser = messages?[0]["content"] as? [[String: Any]]
        XCTAssertEqual(mergedUser?.count, 2)
        let assistantBlocks = messages?[1]["content"] as? [[String: Any]]
        XCTAssertEqual(assistantBlocks?[0]["type"] as? String, "tool_use")
        XCTAssertEqual(assistantBlocks?[0]["id"] as? String, "t1")
        let input = assistantBlocks?[0]["input"] as? [String: Any]
        XCTAssertEqual(input?["a"] as? Int, 1)
        let resultBlocks = messages?[2]["content"] as? [[String: Any]]
        XCTAssertEqual(resultBlocks?[0]["type"] as? String, "tool_result")
        XCTAssertEqual(resultBlocks?[0]["tool_use_id"] as? String, "t1")
        let tools = body["tools"] as? [[String: Any]]
        XCTAssertEqual(tools?[0]["name"] as? String, "run")
        XCTAssertNotNil(tools?[0]["input_schema"] as? [String: Any])
    }

    // MARK: - Adapter with fake transport

    private final class MemoryCredentialStore: ClaudeCredentialStore {
        var key: String?
        func saveApiKey(_ apiKey: String) { key = apiKey }
        func loadApiKey() -> String? { key }
        func clearApiKey() { key = nil }
    }

    private final class FakeConnection: ClaudeConnection {
        let statusCode: Int = 200
        let lines: [String]
        init(lines: [String]) { self.lines = lines }
        func bodyLines() -> AsyncThrowingStream<String, Error> {
            let lines = self.lines
            return AsyncThrowingStream { continuation in
                for line in lines { continuation.yield(line) }
                continuation.finish()
            }
        }
        func readBodyText() async throws -> String { "" }
        func readErrorBody() async -> String? { nil }
    }

    private final class FakeTransport: ClaudeTransport {
        var connection: ClaudeConnection?
        var lastUrl: String?
        var lastHeadersKey: String?
        func post(url: String, apiKey: String, requestBody: String) async throws -> ClaudeConnection {
            lastUrl = url
            lastHeadersKey = apiKey
            return connection ?? FakeConnection(lines: [])
        }
        func get(url: String, apiKey: String) async throws -> ClaudeConnection {
            FakeConnection(lines: [])
        }
    }

    func testAdapterHappyPathAndNoKey() async {
        let credentials = MemoryCredentialStore()
        let transport = FakeTransport()
        let adapter = ClaudeBrainAdapter(
            credentialStore: credentials,
            configuredModelProvider: { "claude-sonnet-4-5" },
            apiClient: ClaudeApiClient(transport: transport))

        var events: [BrainEvent] = []
        do {
            for try await event in adapter.chatStream(request: ChatRequest(
                messages: [ChatMessage(role: "user", content: "在吗")], model: "")) {
                events.append(event)
            }
        } catch { XCTFail("adapter stream should not throw: \(error)") }
        XCTAssertEqual(events, [
            .error(code: "not_configured",
                   message: "未配置 Claude API Key：请先在设置里填写你自己的 Key。",
                   retryable: false),
            .done(finishReason: "error"),
        ])

        credentials.key = "sk-ant-x"
        transport.connection = FakeConnection(lines: [
            "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":3}}}",
            "",
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"在的\"}}",
            "",
            "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":2}}",
            "",
            "data: {\"type\":\"message_stop\"}",
        ])
        events = []
        do {
            for try await event in adapter.chatStream(request: ChatRequest(
                messages: [ChatMessage(role: "user", content: "在吗")], model: "")) {
                events.append(event)
            }
        } catch { XCTFail("adapter stream should not throw: \(error)") }
        XCTAssertEqual(events, [
            .usage(inputTokens: 3, outputTokens: nil),
            .textDelta("在的"),
            .usage(inputTokens: nil, outputTokens: 2),
            .done(finishReason: "end_turn"),
        ])
        XCTAssertEqual(transport.lastUrl, "https://api.anthropic.com/v1/messages")
        XCTAssertEqual(adapter.adapterId, "model-api-claude")
        XCTAssertEqual(adapter.label, "Claude API")
    }
}
