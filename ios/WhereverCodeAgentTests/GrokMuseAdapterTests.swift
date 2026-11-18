import XCTest
@testable import WhereverCodeAgent

/// Grok + Muse adapters: thin vendor adapters over the shared
/// generic client. Pinned here: stable ids, honest capability
/// notes (model ≠ the agent product), vendor-specific
/// not-configured wording, and one streamed happy path each.
final class GrokMuseAdapterTests: XCTestCase {

    private final class GrokStore: GrokCredentialStore {
        var key: String?
        func saveApiKey(_ apiKey: String) { key = apiKey }
        func loadApiKey() -> String? { key }
        func clearApiKey() { key = nil }
    }

    private final class MuseStore: MuseCredentialStore {
        var key: String?
        func saveApiKey(_ apiKey: String) { key = apiKey }
        func loadApiKey() -> String? { key }
        func clearApiKey() { key = nil }
    }

    private final class FakeConnection: DeepSeekConnection {
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
        func readBodyText() async throws -> String { "{\"data\":[{\"id\":\"m1\"}]}" }
        func readErrorBody() async -> String? { nil }
    }

    private final class FakeTransport: GenericOpenAiTransport {
        var connection: DeepSeekConnection?
        var lastUrl: String?
        func post(url: String, apiKey: String, requestBody: String) async throws -> DeepSeekConnection {
            lastUrl = url
            return connection ?? FakeConnection(lines: [])
        }
        func get(url: String, apiKey: String) async throws -> DeepSeekConnection {
            lastUrl = url
            return FakeConnection(lines: [])
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

    private var sseLines: [String] {
        [
            "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"},\"finish_reason\":null}]}",
            "",
            "data: [DONE]",
        ]
    }

    func testGrokAdapter() async {
        let store = GrokStore()
        let transport = FakeTransport()
        let adapter = GrokBrainAdapter(
            credentialStore: store,
            configuredModelProvider: { "grok-4.7" },
            apiClient: GenericOpenAiApiClient(transport: transport))
        XCTAssertEqual(adapter.adapterId, "model-api-grok")
        XCTAssertEqual(adapter.label, "Grok")
        XCTAssertEqual(adapter.capabilities().vision, true)
        XCTAssertTrue(adapter.capabilities().notes.contains("不是 Grok Bot 本人"))
        XCTAssertTrue(adapter.capabilities().notes.contains("代码不许下到 Grok Bot"))

        var events = await collect(adapter.chatStream(request: ChatRequest(
            messages: [ChatMessage(role: "user", content: "在吗")], model: "")))
        XCTAssertEqual(events, [
            .error(code: "not_configured",
                   message: "未配置 Grok API Key：请在设置里填写你自己的 xAI API Key（只存本机 Keystore，绝不发给任何人）。",
                   retryable: false),
            .done(finishReason: "error"),
        ])

        store.key = "xai-test"
        transport.connection = FakeConnection(lines: sseLines)
        events = await collect(adapter.chatStream(request: ChatRequest(
            messages: [ChatMessage(role: "user", content: "在吗")], model: "")))
        XCTAssertEqual(events, [.textDelta("你好"), .done(finishReason: "stop")])
        XCTAssertEqual(transport.lastUrl, "https://api.x.ai/v1/chat/completions")
    }

    func testMuseAdapter() async {
        let store = MuseStore()
        let transport = FakeTransport()
        let adapter = MuseBrainAdapter(
            credentialStore: store,
            configuredModelProvider: { "muse-spark-1.3" },
            apiClient: GenericOpenAiApiClient(transport: transport))
        XCTAssertEqual(adapter.adapterId, "model-api-muse")
        XCTAssertEqual(adapter.label, "Muse Spark")
        XCTAssertEqual(adapter.capabilities().vision, false)
        XCTAssertTrue(adapter.capabilities().notes.contains("不是个人助理 Muse"))

        var events = await collect(adapter.chatStream(request: ChatRequest(
            messages: [ChatMessage(role: "user", content: "在吗")], model: "")))
        XCTAssertEqual(events, [
            .error(code: "not_configured",
                   message: "未配置 Muse API Key：请在设置里填写你自己的 Meta Model API Key（只存本机 Keystore，绝不发给任何人）。",
                   retryable: false),
            .done(finishReason: "error"),
        ])

        store.key = "meta-test"
        transport.connection = FakeConnection(lines: sseLines)
        events = await collect(adapter.chatStream(request: ChatRequest(
            messages: [ChatMessage(role: "user", content: "在吗")], model: "")))
        XCTAssertEqual(events, [.textDelta("你好"), .done(finishReason: "stop")])
        XCTAssertEqual(transport.lastUrl, "https://api.meta.ai/v1/chat/completions")
    }
}
