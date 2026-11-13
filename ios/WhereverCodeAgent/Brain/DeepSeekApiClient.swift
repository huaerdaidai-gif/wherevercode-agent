import Foundation

/// DeepSeek API client (OpenAI-compatible `/chat/completions`).
/// Ported from Android `DeepSeekApiClient.kt` on main @4496658e.
///
/// The API key is passed in per call, only in the Authorization
/// header. It is never stored, logged, or included in error text.
/// Streaming uses SSE `data: {...}` chunks and `data: [DONE]`;
/// tool-call arguments arrive fragmented across chunks and are
/// accumulated by [DeepSeekStreamParser].
public final class DeepSeekApiClient {
    private let transport: DeepSeekTransport
    private let onMalformedLine: (String) -> Void

    public init(
        transport: DeepSeekTransport = URLSessionDeepSeekTransport(),
        onMalformedLine: @escaping (String) -> Void = { _ in }
    ) {
        self.transport = transport
        self.onMalformedLine = onMalformedLine
    }

    public static let defaultBaseUrl = "https://api.deepseek.com"

    /// Suggested models at first configuration only; never a silent
    /// fallback.
    public static let suggestedModels: [String] = ["deepseek-flash", "deepseek-v4-pro"]

    /// Stream one chat completion. Failures become
    /// `DeepSeekEvent.failed` events, never thrown errors.
    public func streamChat(
        request: ChatRequest,
        apiKey: String,
        baseUrl: String
    ) -> AsyncStream<DeepSeekEvent> {
        AsyncStream { continuation in
            let task = Task {
                await self.runStream(request: request, apiKey: apiKey, baseUrl: baseUrl) { event in
                    continuation.yield(event)
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    private func runStream(
        request: ChatRequest,
        apiKey: String,
        baseUrl: String,
        emit: (DeepSeekEvent) -> Void
    ) async {
        if apiKey.trimmingCharacters(in: .whitespaces).isEmpty {
            emit(.failed(
                code: "not_configured",
                message: "未配置 DeepSeek API Key：请先在控制台的 DeepSeek 设置里填写你自己的 Key。",
                retryable: false))
            return
        }
        if request.model.trimmingCharacters(in: .whitespaces).isEmpty {
            emit(.failed(
                code: "not_configured",
                message: "未选择 DeepSeek 模型：请先在设置里选择或刷新模型列表。",
                retryable: false))
            return
        }
        let body = Self.buildRequestBody(request)
        guard let bodyText = DeepSeekStreamParser.serializeJson(body) else {
            emit(.failed(
                code: "transport_error",
                message: "DeepSeek 请求失败：无法序列化请求。",
                retryable: true))
            return
        }
        let connection: DeepSeekConnection
        do {
            connection = try await transport.post(
                url: Self.chatCompletionsUrl(baseUrl), apiKey: apiKey, requestBody: bodyText)
        } catch {
            emit(.failed(
                code: "transport_error",
                message: "连接 DeepSeek 失败（\(Self.ioReason(error))）：\(error.localizedDescription)",
                retryable: true))
            return
        }
        if connection.statusCode != 200 {
            let detail = Self.parseErrorBody(await connection.readErrorBody())
            emit(Self.mapHttpError(connection.statusCode, detail: detail))
            return
        }
        let parser = DeepSeekStreamParser(onMalformedLine: onMalformedLine)
        do {
            for try await line in connection.bodyLines() {
                if Task.isCancelled { return }
                for event in parser.feedLine(line) { emit(event) }
                if parser.terminal { break }
            }
        } catch {
            if Task.isCancelled { return }
            emit(.failed(
                code: "transport_error",
                message: "读取 DeepSeek 流失败（\(Self.ioReason(error))）：\(error.localizedDescription)",
                retryable: true))
            return
        }
        for event in parser.finish() { emit(event) }
    }

    /// Probe the model catalogue. This is a read-only GET; no
    /// inference cost.
    public func listModels(
        apiKey: String,
        baseUrl: String
    ) async -> Result<[ModelInfo], BrainErrorInfo> {
        if apiKey.trimmingCharacters(in: .whitespaces).isEmpty {
            return .failure(BrainErrorInfo(
                code: "not_configured", message: "未配置 DeepSeek API Key。", retryable: false))
        }
        let connection: DeepSeekConnection
        do {
            connection = try await transport.get(url: Self.modelsUrl(baseUrl), apiKey: apiKey)
        } catch {
            return .failure(BrainErrorInfo(
                code: "transport_error",
                message: "连接 DeepSeek 失败（网络异常）：\(error.localizedDescription)",
                retryable: true))
        }
        if connection.statusCode != 200 {
            let detail = Self.parseErrorBody(await connection.readErrorBody())
            let mapped = Self.mapHttpError(connection.statusCode, detail: detail)
            if case .failed(_, let message, let retryable) = mapped {
                return .failure(BrainErrorInfo(
                    code: "http_\(connection.statusCode)", message: message, retryable: retryable))
            }
            return .failure(BrainErrorInfo(
                code: "transport_error", message: "DeepSeek 请求失败。", retryable: true))
        }
        do {
            let text = try await connection.readBodyText()
            guard let root = DeepSeekStreamParser.parseJsonObject(text) else {
                return .failure(BrainErrorInfo(
                    code: "parse_error", message: "解析 DeepSeek 模型列表失败：响应不是 JSON 对象。",
                    retryable: true))
            }
            let data = root["data"] as? [Any] ?? []
            var models: [ModelInfo] = []
            for raw in data {
                guard let item = raw as? [String: Any] else { continue }
                let id = DeepSeekStreamParser.stringOrEmpty(item, "id")
                if !id.trimmingCharacters(in: .whitespaces).isEmpty {
                    models.append(ModelInfo(id: id, displayName: id))
                }
            }
            return .success(models)
        } catch {
            return .failure(BrainErrorInfo(
                code: "parse_error",
                message: "解析 DeepSeek 模型列表失败：\(error.localizedDescription)",
                retryable: true))
        }
    }

    // MARK: - HTTP error mapping (identical table to Android)

    static func mapHttpError(_ status: Int, detail: String) -> DeepSeekEvent {
        switch status {
        case -1:
            return .failed(
                code: "transport_error",
                message: "连接 DeepSeek 失败或超时（未收到 HTTP 响应）。\(detail)",
                retryable: true)
        case 401:
            return .failed(
                code: "auth_failed",
                message: "DeepSeek 授权失败（401）：API Key 无效或已失效。\(detail)",
                retryable: false)
        case 402:
            return .failed(
                code: "insufficient_balance",
                message: "DeepSeek 账户余额不足（402）：请充值或换用其他大脑。\(detail)",
                retryable: false)
        case 400:
            return .failed(
                code: "invalid_request",
                message: "DeepSeek 拒绝了请求（400）：\(detail)",
                retryable: false)
        case 404:
            return .failed(
                code: "model_not_found",
                message: "DeepSeek 找不到该模型（404）：请在设置里刷新模型列表并重新选择。\(detail)",
                retryable: false)
        case 422:
            return .failed(
                code: "invalid_request",
                message: "DeepSeek 无法处理该请求（422）：\(detail)",
                retryable: false)
        case 429:
            return .failed(
                code: "rate_limited",
                message: "DeepSeek 请求过于频繁（429）：请稍后再试。\(detail)",
                retryable: true)
        case 500...599:
            return .failed(
                code: "transport_error",
                message: "DeepSeek 服务暂不可用（HTTP \(status)）：\(detail)",
                retryable: true)
        default:
            return .failed(
                code: "transport_error",
                message: "DeepSeek 请求失败（HTTP \(status)）：\(detail)",
                retryable: true)
        }
    }

    static func parseErrorBody(_ body: String?) -> String {
        guard let body, !body.trimmingCharacters(in: .whitespaces).isEmpty else { return "" }
        guard let root = DeepSeekStreamParser.parseJsonObject(body) else {
            return String(body.prefix(300))
        }
        let error = root["error"] as? [String: Any]
        let messageFromError = error.map { DeepSeekStreamParser.stringOrEmpty($0, "message") } ?? ""
        let message = !messageFromError.isEmpty
            ? messageFromError
            : DeepSeekStreamParser.stringOrEmpty(root, "message")
        let code = error.map { DeepSeekStreamParser.stringOrEmpty($0, "code") } ?? ""
        var result = ""
        if !code.isEmpty { result += "（错误码：\(code)）" }
        if !message.isEmpty { result += " \(message)" }
        return result.trimmingCharacters(in: .whitespaces)
    }

    static func ioReason(_ error: Error) -> String {
        if let urlError = error as? URLError, urlError.code == .timedOut { return "超时" }
        return "网络异常"
    }

    // MARK: - URL building (identical rules to Android)

    public static func chatCompletionsUrl(_ baseUrl: String) -> String {
        let base = baseUrl.trimmingCharacters(in: .whitespaces).withTrailingSlashTrimmed()
        return base.hasSuffix("/chat/completions") ? base : base + "/chat/completions"
    }

    public static func modelsUrl(_ baseUrl: String) -> String {
        let base = baseUrl.trimmingCharacters(in: .whitespaces).withTrailingSlashTrimmed()
        if base.hasSuffix("/chat/completions") {
            return String(base.dropLast("/chat/completions".count)) + "/models"
        }
        if base.hasSuffix("/models") { return base }
        return base + "/models"
    }

    // MARK: - Request body (exact OpenAI-compatible wire format)

    /// The native tool protocol keeps assistant tool-call ids and
    /// pairs each tool result with its `tool_call_id`. A persisted
    /// tool message with no id is downgraded to a user-visible result
    /// note instead of an invalid `role="tool"` message.
    public static func buildRequestBody(_ request: ChatRequest) -> [String: Any] {
        var body: [String: Any] = [:]
        body["model"] = request.model
        body["stream"] = true
        body["messages"] = request.messages.map { toWireMessage($0) }
        if !request.tools.isEmpty {
            var tools: [[String: Any]] = []
            for tool in request.tools {
                var parameters: [String: Any] = [:]
                for (key, value) in tool.inputSchema { parameters[key] = value.anyObject }
                if parameters.isEmpty {
                    parameters = ["type": "object", "properties": [String: Any]()]
                }
                tools.append([
                    "type": "function",
                    "function": [
                        "name": tool.name,
                        "description": tool.description,
                        "parameters": parameters,
                    ],
                ])
            }
            body["tools"] = tools
            body["tool_choice"] = "auto"
        }
        if let temperature = request.temperature { body["temperature"] = temperature }
        if let maxTokens = request.maxTokens { body["max_tokens"] = maxTokens }
        return body
    }

    static func toWireMessage(_ message: ChatMessage) -> [String: Any] {
        switch message.role {
        case "system", "user":
            return ["role": message.role, "content": message.content]
        case "assistant":
            var obj: [String: Any] = ["role": "assistant"]
            if !message.toolCalls.isEmpty {
                obj["content"] = NSNull()
                var calls: [[String: Any]] = []
                for call in message.toolCalls {
                    calls.append([
                        "id": call.id,
                        "type": "function",
                        "function": [
                            "name": call.name,
                            "arguments": call.argumentsJson.isEmpty ? "{}" : call.argumentsJson,
                        ],
                    ])
                }
                obj["tool_calls"] = calls
            } else {
                obj["content"] = message.content
            }
            return obj
        case "tool":
            if let toolCallId = message.toolCallId,
               !toolCallId.trimmingCharacters(in: .whitespaces).isEmpty {
                return ["role": "tool", "tool_call_id": toolCallId, "content": message.content]
            }
            // Legacy persisted history: a tool message without an id
            // cannot be paired and would make DeepSeek reject the
            // whole request. Keep the information as a user message.
            return ["role": "user", "content": "[工具结果]\n" + message.content]
        default:
            return ["role": "user", "content": message.content]
        }
    }
}

private extension String {
    /// Kotlin `trimEnd('/')`.
    func withTrailingSlashTrimmed() -> String {
        var s = self
        while s.hasSuffix("/") { s.removeLast() }
        return s
    }
}

// MARK: - Transport

/// Injectable HTTP transport. Production uses
/// [URLSessionDeepSeekTransport]; tests use a fake that returns
/// canned SSE. The key crosses only post/get and must never be
/// stored or logged by an implementation.
public protocol DeepSeekTransport {
    func post(url: String, apiKey: String, requestBody: String) async throws -> DeepSeekConnection
    func get(url: String, apiKey: String) async throws -> DeepSeekConnection
}

public protocol DeepSeekConnection {
    var statusCode: Int { get }
    func bodyLines() -> AsyncThrowingStream<String, Error>
    func readBodyText() async throws -> String
    func readErrorBody() async -> String?
}

/// URLSession implementation; no extra HTTP dependency. Android
/// uses connect 30s / read 120s; URLSession exposes a single
/// per-request timeout, set to 120s here.
public final class URLSessionDeepSeekTransport: DeepSeekTransport {
    private let session: URLSession

    public init(session: URLSession = .shared) {
        self.session = session
    }

    public func post(url: String, apiKey: String, requestBody: String) async throws -> DeepSeekConnection {
        guard let url = URL(string: url) else {
            throw URLError(.badURL)
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 120
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        request.httpBody = requestBody.data(using: .utf8)
        let (bytes, response) = try await session.bytes(for: request)
        return URLSessionDeepSeekConnection(bytes: bytes, response: response)
    }

    public func get(url: String, apiKey: String) async throws -> DeepSeekConnection {
        guard let url = URL(string: url) else {
            throw URLError(.badURL)
        }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 120
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        let (bytes, response) = try await session.bytes(for: request)
        return URLSessionDeepSeekConnection(bytes: bytes, response: response)
    }
}

private final class URLSessionDeepSeekConnection: DeepSeekConnection {
    private let bytes: URLSession.AsyncBytes
    private let httpResponse: HTTPURLResponse?

    init(bytes: URLSession.AsyncBytes, response: URLResponse) {
        self.bytes = bytes
        self.httpResponse = response as? HTTPURLResponse
    }

    var statusCode: Int { httpResponse?.statusCode ?? -1 }

    func bodyLines() -> AsyncThrowingStream<String, Error> {
        let bytes = self.bytes
        return AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    for try await line in bytes.lines {
                        continuation.yield(line)
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    func readBodyText() async throws -> String {
        var data = Data()
        for try await byte in bytes {
            data.append(byte)
        }
        return String(data: data, encoding: .utf8) ?? ""
    }

    func readErrorBody() async -> String? {
        do {
            let text = try await readBodyText()
            return text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : text
        } catch {
            return nil
        }
    }
}
