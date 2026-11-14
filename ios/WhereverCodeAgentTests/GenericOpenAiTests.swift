import XCTest
@testable import WhereverCodeAgent

/// Generic OpenAI-compatible adapter family, ported behaviour from
/// the Android generic tests on main @4496658e: config resolution
/// (another adapter's config is not ours), local-model no-key rule,
/// baseUrl validation at call time, the failed-turn suppression
/// flag, image content parts, credential-ref prefix rules and
/// presets.
final class GenericOpenAiTests: XCTestCase {

    // MARK: - Parser wording / id prefix

    func testParserGenericWordingAndIdPrefix() {
        let empty = GenericOpenAiStreamParser.parseSseText("")
        XCTAssertEqual(empty, [
            .failed(code: "stream_empty",
                    message: "模型 API 未返回任何内容（流为空）。",
                    retryable: true),
        ])
        let sse = """
        data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"ping","arguments":"{}"}}]},"finish_reason":null}]}

        data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}

        data: [DONE]
        """
        let events = GenericOpenAiStreamParser.parseSseText(sse)
        XCTAssertEqual(events, [
            .toolCall(id: "generic-call-1", name: "ping", argumentsJson: "{}"),
            .finished(reason: "tool_calls"),
        ])
    }

    // MARK: - Image content parts

    func testRequestBodyImageParts() {
        let request = ChatRequest(
            messages: [
                ChatMessage(role: "user", content: "这是什么",
                            images: [ChatImage(dataUrl: "data:image/jpeg;base64,AA")]),
                ChatMessage(role: "user", content: "纯文字"),
            ],
            model: "gpt-x")
        let body = GenericOpenAiApiClient.buildRequestBody(request)
        let messages = body["messages"] as? [[String: Any]]
        let parts = messages?[0]["content"] as? [[String: Any]]
        XCTAssertEqual(parts?.count, 2)
        XCTAssertEqual(parts?[0]["type"] as? String, "text")
        XCTAssertEqual(parts?[0]["text"] as? String, "这是什么")
        XCTAssertEqual(parts?[1]["type"] as? String, "image_url")
        let imageUrl = parts?[1]["image_url"] as? [String: Any]
        XCTAssertEqual(imageUrl?["url"] as? String, "data:image/jpeg;base64,AA")
        XCTAssertEqual(messages?[1]["content"] as? String, "纯文字")
    }

    // MARK: - Fakes

    private final class MemoryCredentialStore: GenericOpenAiCredentialStore {
        var keys: [String: String] = [:]
        func saveApiKey(authRef: String, apiKey: String) { keys[authRef] = apiKey }
        func loadApiKey(authRef: String) -> String? { keys[authRef] }
        func clearApiKey(authRef: String) { keys[authRef] = nil }
    }

    private final class FakeConnection: DeepSeekConnection {
        let statusCode: Int = 200
        let lines: [String]
        let text: String
        init(lines: [String] = [], text: String = "") {
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
        func readErrorBody() async -> String? { nil }
    }

    private final class FakeTransport: GenericOpenAiTransport {
        var postConnection: DeepSeekConnection?
        func post(url: String, apiKey: String, requestBody: String) async throws -> GenericOpenAiConnection {
            postConnection ?? FakeConnection()
        }
        func get(url: String, apiKey: String) async throws -> GenericOpenAiConnection {
            FakeConnection(text: "{\"data\":[{\"id\":\"m1\"}]}")
        }
    }

    private func makeStore() throws -> KeyValueBrainConfigStore {
        let data = try LocalData.inMemory()
        return KeyValueBrainConfigStore(kv: data.kv)
    }

    private func genericConfig(
        brainId: String = "model-api-test",
        baseUrl: String? = "https://api.example.com/v1",
        model: String = "m1",
        adapterId: String = GenericOpenAiBrainAdapter.adapterId
    ) -> BrainConfig {
        BrainConfig(
            brainId: brainId, kind: .modelApi, adapterId: adapterId,
            label: "测试", baseUrl: baseUrl, model: model,
            authRef: "model-api:\(brainId)")
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

    // MARK: - Adapter behaviour

    func testAdapterIgnoresOtherAdaptersConfig() async throws {
        let store = try makeStore()
        // The current brain belongs to the DeepSeek adapter: the
        // generic adapter must NOT resolve it as its own config.
        store.saveConfig(genericConfig(adapterId: "deepseek"))
        store.setCurrentBrainId("model-api-test")
        let adapter = GenericOpenAiBrainAdapter(
            configStore: store, credentialStore: MemoryCredentialStore())
        let events = await collect(adapter.chatStream(
            request: ChatRequest(messages: [ChatMessage(role: "user", content: "在吗")], model: "")))
        XCTAssertEqual(events, [
            .error(code: "not_configured",
                   message: "未配置通用模型 API 大脑：请先在设置里添加一个通用模型 API 大脑。",
                   retryable: false),
            .done(finishReason: "error"),
        ])
    }

    func testAdapterHappyPath() async throws {
        let store = try makeStore()
        store.saveConfig(genericConfig())
        store.setCurrentBrainId("model-api-test")
        let credentials = MemoryCredentialStore()
        credentials.keys["model-api:model-api-test"] = "sk-x"
        let transport = FakeTransport()
        transport.postConnection = FakeConnection(lines: [
            "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"},\"finish_reason\":null}]}",
            "",
            "data: [DONE]",
        ])
        let adapter = GenericOpenAiBrainAdapter(
            configStore: store, credentialStore: credentials,
            apiClient: GenericOpenAiApiClient(transport: transport))
        let events = await collect(adapter.chatStream(
            request: ChatRequest(messages: [ChatMessage(role: "user", content: "在吗")], model: "")))
        XCTAssertEqual(events, [.textDelta("你好"), .done(finishReason: "stop")])
    }

    func testAdapterRejectsPlainHttpRemote() async throws {
        let store = try makeStore()
        store.saveConfig(genericConfig(baseUrl: "http://api.example.com/v1"))
        store.setCurrentBrainId("model-api-test")
        let credentials = MemoryCredentialStore()
        credentials.keys["model-api:model-api-test"] = "sk-x"
        let adapter = GenericOpenAiBrainAdapter(
            configStore: store, credentialStore: credentials)
        let events = await collect(adapter.chatStream(
            request: ChatRequest(messages: [ChatMessage(role: "user", content: "在吗")], model: "")))
        XCTAssertEqual(events, [
            .error(code: "not_configured",
                   message: "服务地址只允许 https（本地模型允许 http://127.0.0.1 或 http://localhost）：http://api.example.com/v1",
                   retryable: false),
            .done(finishReason: "error"),
        ])
    }

    func testAdapterLocalModelNeedsNoKey() async throws {
        let store = try makeStore()
        store.saveConfig(genericConfig(baseUrl: "http://localhost:11434/v1"))
        store.setCurrentBrainId("model-api-test")
        let transport = FakeTransport()
        transport.postConnection = FakeConnection(lines: [
            "data: {\"choices\":[{\"delta\":{\"content\":\"本地答复\"},\"finish_reason\":null}]}",
            "",
            "data: [DONE]",
        ])
        let adapter = GenericOpenAiBrainAdapter(
            configStore: store, credentialStore: MemoryCredentialStore(),
            apiClient: GenericOpenAiApiClient(transport: transport))
        let events = await collect(adapter.chatStream(
            request: ChatRequest(messages: [ChatMessage(role: "user", content: "在吗")], model: "")))
        XCTAssertEqual(events, [.textDelta("本地答复"), .done(finishReason: "stop")])
    }

    func testAdapterSuppressesEventsAfterToolArgsFailure() async throws {
        let store = try makeStore()
        store.saveConfig(genericConfig())
        store.setCurrentBrainId("model-api-test")
        let credentials = MemoryCredentialStore()
        credentials.keys["model-api:model-api-test"] = "sk-x"
        let transport = FakeTransport()
        transport.postConnection = FakeConnection(lines: [
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"run\",\"arguments\":\"oops\"}}]},\"finish_reason\":null}]}",
            "",
            "data: {\"choices\":[{\"delta\":{\"content\":\"不应出现\"},\"finish_reason\":null}]}",
            "",
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
            "",
            "data: [DONE]",
        ])
        let adapter = GenericOpenAiBrainAdapter(
            configStore: store, credentialStore: credentials,
            apiClient: GenericOpenAiApiClient(transport: transport))
        let events = await collect(adapter.chatStream(
            request: ChatRequest(messages: [ChatMessage(role: "user", content: "跑")], model: "")))
        // The text chunk streamed before the tool call is real
        // output; what the flag suppresses is everything AFTER the
        // invalid-arguments failure — above all the parser's
        // Finished event must not become a second, successful done.
        XCTAssertEqual(events, [
            .textDelta("不应出现"),
            .error(code: "tool_arguments_invalid",
                   message: "模型返回的工具参数不是合法 JSON（run），已停止本轮。",
                   retryable: false),
            .done(finishReason: "error"),
        ])
    }

    func testAdapterForBrainBinding() async throws {
        let store = try makeStore()
        store.saveConfig(genericConfig(brainId: "model-api-other"))
        // Current brain is something else entirely.
        store.setCurrentBrainId(nil)
        let credentials = MemoryCredentialStore()
        credentials.keys["model-api:model-api-other"] = "sk-y"
        let transport = FakeTransport()
        let adapter = GenericOpenAiBrainAdapter(
            configStore: store, credentialStore: credentials,
            apiClient: GenericOpenAiApiClient(transport: transport))
        let result = await adapter.forBrain("model-api-other").listModels()
        switch result {
        case .success(let models):
            XCTAssertEqual(models.map(\.id), ["m1"])
        case .failure(let error):
            XCTFail("forBrain listModels failed: \(error)")
        }
    }

    // MARK: - Credential store + presets

    func testCredentialStorePrefixRules() {
        let store = KeystoreGenericOpenAiCredentialStore()
        XCTAssertNil(store.loadApiKey(authRef: "deepseek:api-key"))
        XCTAssertThrowsError(try store.saveApiKey(authRef: "other:x", apiKey: "k"))
        XCTAssertNoThrow(try store.saveApiKey(authRef: "model-api:b1", apiKey: "k1"))
        XCTAssertEqual(store.loadApiKey(authRef: "model-api:b1"), "k1")
        store.clearApiKey(authRef: "model-api:b1")
        XCTAssertNil(store.loadApiKey(authRef: "model-api:b1"))
    }

    func testPresets() {
        XCTAssertEqual(GenericOpenAiPresets.presets.count, 8)
        XCTAssertEqual(
            GenericOpenAiPresets.presetForBaseUrl("https://api.openai.com/v1/")?.displayName,
            "OpenAI")
        XCTAssertEqual(
            GenericOpenAiPresets.presetForBaseUrl("https://generativelanguage.googleapis.com/v1beta/openai/")?.displayName,
            "Google Gemini")
        XCTAssertNil(GenericOpenAiPresets.presetForBaseUrl("https://unknown.example/v1"))
    }
}
