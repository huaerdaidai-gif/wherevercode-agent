import XCTest
@testable import WhereverCodeAgent

/// Provider layer + SIWC M2 bridge, ported behaviour from the
/// Android bridge contract: system folds into instructions, tool
/// results render as user text, Preview-rejected knobs fail loudly,
/// error kinds map to stable codes with only transport retryable.
final class BrainProviderTests: XCTestCase {

    private final class FakeProvider: BrainProvider {
        var providerId: String = "fake"
        var displayName: String = "Fake 大脑"
        var capabilities = BrainProviderCapabilities(
            supportsStreaming: true, supportsTools: true,
            supportsSystemMessages: false, requiresFullHistory: true,
            supportsTemperature: false, supportsMaxOutputTokens: false,
            billsUserPlan: true, limitsNote: "fake limits")
        var configured = true
        var events: [BrainStreamEvent] = []
        var lastRequest: BrainRequest?
        var models: [String] = []

        func isConfigured() -> Bool { configured }
        func infer(request: BrainRequest) async throws -> BrainResponse {
            BrainResponse(text: "", model: "")
        }
        func streamInfer(request: BrainRequest) -> AsyncThrowingStream<BrainStreamEvent, Error> {
            lastRequest = request
            let events = self.events
            return AsyncThrowingStream { continuation in
                for event in events { continuation.yield(event) }
                continuation.finish()
            }
        }
        func listModels() async -> Result<[String], Error> { .success(models) }
        func revoke() async {}
    }

    private func collect(_ stream: AsyncThrowingStream<BrainEvent, Error>) async -> [BrainEvent] {
        var events: [BrainEvent] = []
        do {
            for try await event in stream { events.append(event) }
        } catch {
            XCTFail("bridge stream should not throw: \(error)")
        }
        return events
    }

    func testMapRequestRules() {
        let request = ChatRequest(
            messages: [
                ChatMessage(role: "system", content: "规则一"),
                ChatMessage(role: "system", content: "规则二"),
                ChatMessage(role: "user", content: "问题"),
                ChatMessage(role: "assistant", content: "答复"),
                ChatMessage(role: "tool", content: "结果文本", name: "run"),
            ],
            model: " ",
            tools: [ToolDef(name: "run", description: "跑命令",
                            inputSchema: ["type": .string("object")])])
        let mapped = SiwcM2BrainAdapter.mapRequest(request)
        XCTAssertEqual(mapped.instructions, "规则一\n规则二")
        XCTAssertNil(mapped.model)
        XCTAssertEqual(mapped.messages, [
            BrainMessage(role: .user, content: "问题"),
            BrainMessage(role: .assistant, content: "答复"),
            BrainMessage(role: .user, content: "工具run执行结果：结果文本"),
        ])
        XCTAssertEqual(mapped.tools.count, 1)
        XCTAssertEqual(mapped.tools[0].parametersJson, "{\"type\":\"object\"}")
    }

    func testBridgeRejectsPreviewKnobs() async {
        let provider = FakeProvider()
        let adapter = SiwcM2BrainAdapter(provider: provider)
        let events = await collect(adapter.chatStream(request: ChatRequest(
            messages: [ChatMessage(role: "user", content: "在吗")],
            model: "m", temperature: 0.7)))
        XCTAssertEqual(events, [
            .error(code: "unsupported",
                   message: "SIWC Preview 不支持 temperature / max_output_tokens，请去掉这两个参数。",
                   retryable: false),
            .done(finishReason: "error"),
        ])
        XCTAssertNil(provider.lastRequest)
    }

    func testBridgeEventMapping() async {
        let provider = FakeProvider()
        provider.events = [
            .delta("你好"),
            .toolCall(id: "fc1", name: "run", argumentsJson: "{\"cmd\":\"ls\"}"),
            .completed(BrainResponse(
                text: "你好", model: "gpt-x",
                usage: BrainUsage(inputTokens: 10, outputTokens: 5),
                billedToUserPlan: true)),
        ]
        let adapter = SiwcM2BrainAdapter(provider: provider)
        let events = await collect(adapter.chatStream(request: ChatRequest(
            messages: [ChatMessage(role: "user", content: "在吗")], model: "gpt-x")))
        XCTAssertEqual(events, [
            .textDelta("你好"),
            .toolCall(BrainToolCall(id: "fc1", name: "run", args: ["cmd": .string("ls")])),
            .usage(inputTokens: 10, outputTokens: 5),
            .done(finishReason: "stop"),
        ])
        XCTAssertEqual(adapter.adapterId, "siwc-chatgpt")
        XCTAssertEqual(adapter.kind, .subscription)
        XCTAssertEqual(adapter.label, "Fake 大脑")
        XCTAssertEqual(adapter.capabilities().toolCalling, true)
        XCTAssertEqual(adapter.capabilities().notes, "fake limits")
    }

    func testBridgeErrorMapping() async {
        let provider = FakeProvider()
        provider.events = [.failed(.quotaExceeded("额度没了"))]
        let adapter = SiwcM2BrainAdapter(provider: provider)
        let events = await collect(adapter.chatStream(request: ChatRequest(
            messages: [ChatMessage(role: "user", content: "在吗")], model: "m")))
        XCTAssertEqual(events, [
            .error(code: "quota_exceeded", message: "额度没了", retryable: false),
            .done(finishReason: "error"),
        ])
        // Transport is the only retryable kind.
        XCTAssertEqual(
            SiwcM2BrainAdapter.mapError(.transport("")),
            .error(code: "transport_error", message: "网络或服务器错误，请稍后重试。", retryable: true))
        XCTAssertEqual(
            SiwcM2BrainAdapter.mapError(.authFailed("")),
            .error(code: "auth_failed", message: "ChatGPT 授权失败，请重新登录。", retryable: false))
    }

    func testBridgeToolArgsMalformedBecomesEmpty() {
        XCTAssertEqual(SiwcM2BrainAdapter.parseToolArgs("not json"), [:])
        XCTAssertEqual(SiwcM2BrainAdapter.parseToolArgs("  "), [:])
        XCTAssertEqual(SiwcM2BrainAdapter.parseToolArgs("{\"a\":1}"), ["a": .number(1)])
    }
}
