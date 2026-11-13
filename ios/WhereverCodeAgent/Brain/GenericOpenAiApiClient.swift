import Foundation

/// Generic OpenAI-compatible API client (`/chat/completions` +
/// `/models`). Ported from Android `GenericOpenAiApiClient.kt` on
/// main @4496658e — itself a faithful port of the DeepSeek client:
/// same transport shape, same URL normalisation, same request-body
/// builder, same HTTP error mapping; only the wording is
/// vendor-neutral and there is no vendor default base URL or
/// suggested model.
///
/// Differences from the DeepSeek client:
/// - a local model (http://127.0.0.1 / http://localhost) may run
///   without a key, and no Authorization header is sent then;
/// - user/system messages with images are sent as OpenAI content
///   parts (`text` + `image_url`).
///
/// The API key is passed in per call, only in the Authorization
/// header. It is never stored, logged, or included in error text.
public final class GenericOpenAiApiClient {
    private let transport: GenericOpenAiTransport
    private let onMalformedLine: (String) -> Void

    public init(
        transport: GenericOpenAiTransport = URLSessionGenericOpenAiTransport(),
        onMalformedLine: @escaping (String) -> Void = { _ in }
    ) {
        self.transport = transport
        self.onMalformedLine = onMalformedLine
    }

    /// Stream one chat completion. Failures become
    /// `GenericOpenAiEvent.failed` events, never thrown errors.
    public func streamChat(
        request: ChatRequest,
        apiKey: String,
        baseUrl: String
    ) -> AsyncStream<GenericOpenAiEvent> {
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
        emit: (GenericOpenAiEvent) -> Void
    ) async {
        // 本机模型可无 Key（不发 Authorization 头）；非本机地址无
        // Key 仍拒绝，不静默调一个没 Key 的远端。
        if apiKey.trimmingCharacters(in: .whitespaces).isEmpty
            && !BrainConfigValidator.isLocalModelUrl(baseUrl) {
            emit(.failed(
                code: "not_configured",
                message: "未配置模型 API Key：请先在设置里填写你自己的 Key。",
                retryable: false))
            return
        }
        if request.model.trimmingCharacters(in: .whitespaces).isEmpty {
            emit(.failed(
                code: "not_configured",
                message: "未选择模型：请先在设置里选择或刷新模型列表。",
                retryable: false))
            return
        }
        let body = Self.buildRequestBody(request)
        guard let bodyText = DeepSeekStreamParser.serializeJson(body) else {
            emit(.failed(
                code: "transport_error",
                message: "模型 API 请求失败：无法序列化请求。",
                retryable: true))
            return
        }
        let connection: GenericOpenAiConnection
        do {
            connection = try await transport.post(
                url: Self.chatCompletionsUrl(baseUrl), apiKey: apiKey, requestBody: bodyText)
        } catch {
            emit(.failed(
                code: "transport_error",
                message: "连接模型 API 失败（\(DeepSeekApiClient.ioReason(error))）：\(error.localizedDescription)",
                retryable: true))
            return
        }
        if connection.statusCode != 200 {
            let detail = Self.parseErrorBody(await connection.readErrorBody())
            emit(Self.mapHttpError(connection.statusCode, detail: detail))
            return
        }
        let parser = GenericOpenAiStreamParser(onMalformedLine: onMalformedLine)
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
                message: "读取模型 API 流失败（\(DeepSeekApiClient.ioReason(error))）：\(error.localizedDescription)",
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
        if apiKey.trimmingCharacters(in: .whitespaces).isEmpty
            && !BrainConfigValidator.isLocalModelUrl(baseUrl) {
            return .failure(BrainErrorInfo(
                code: "not_configured", message: "未配置模型 API Key。", retryable: false))
        }
        let connection: GenericOpenAiConnection
        do {
            connection = try await transport.get(url: Self.modelsUrl(baseUrl), apiKey: apiKey)
        } catch {
            return .failure(BrainErrorInfo(
                code: "transport_error",
                message: "连接模型 API 失败（网络异常）：\(error.localizedDescription)",
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
                code: "transport_error", message: "模型 API 请求失败。", retryable: true))
        }
        do {
            let text = try await connection.readBodyText()
            guard let root = DeepSeekStreamParser.parseJsonObject(text) else {
                return .failure(BrainErrorInfo(
                    code: "parse_error", message: "解析模型 API 模型列表失败：响应不是 JSON 对象。",
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
                message: "解析模型 API 模型列表失败：\(error.localizedDescription)",
                retryable: true))
        }
    }

    // MARK: - HTTP error mapping (identical table to Android)

    static func mapHttpError(_ status: Int, detail: String) -> GenericOpenAiEvent {
        switch status {
        case -1:
            return .failed(
                code: "transport_error",
                message: "连接模型 API 失败或超时（未收到 HTTP 响应）。\(detail)",
                retryable: true)
        case 401:
            return .failed(
                code: "auth_failed",
                message: "模型 API 授权失败（401）：API Key 无效或已失效。\(detail)",
                retryable: false)
        case 402:
            return .failed(
                code: "insufficient_balance",
                message: "模型 API 账户余额不足（402）：请充值或换用其他大脑。\(detail)",
                retryable: false)
        case 400:
            return .failed(
                code: "invalid_request",
                message: "模型 API 拒绝了请求（400）：\(detail)",
                retryable: false)
        case 404:
            return .failed(
                code: "model_not_found",
                message: "模型 API 找不到该模型（404）：请在设置里刷新模型列表并重新选择。\(detail)",
                retryable: false)
        case 422:
            return .failed(
                code: "invalid_request",
                message: "模型 API 无法处理该请求（422）：\(detail)",
                retryable: false)
        case 429:
            return .failed(
                code: "rate_limited",
                message: "模型 API 请求过于频繁（429）：请稍后再试。\(detail)",
                retryable: true)
        case 500...599:
            return .failed(
                code: "transport_error",
                message: "模型 API 服务暂不可用（HTTP \(status)）：\(detail)",
                retryable: true)
        default:
            return .failed(
                code: "transport_error",
                message: "模型 API 请求失败（HTTP \(status)）：\(detail)",
                retryable: true)
        }
    }

    static func parseErrorBody(_ body: String?) -> String {
        DeepSeekApiClient.parseErrorBody(body)
    }

    // MARK: - URL building (identical rules to Android)

    public static func chatCompletionsUrl(_ baseUrl: String) -> String {
        DeepSeekApiClient.chatCompletionsUrl(baseUrl)
    }

    public static func modelsUrl(_ baseUrl: String) -> String {
        DeepSeekApiClient.modelsUrl(baseUrl)
    }

    // MARK: - Request body (exact OpenAI-compatible wire format)

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
            if !message.images.isEmpty {
                // 带图片的消息在 wire 上是 content parts 数组
                // （OpenAI / xAI 同款格式）。引擎只在适配器报告
                // vision=true 时才让图片走到这里。
                var parts: [[String: Any]] = [
                    ["type": "text", "text": message.content],
                ]
                for image in message.images {
                    parts.append([
                        "type": "image_url",
                        "image_url": ["url": image.dataUrl],
                    ])
                }
                return ["role": message.role, "content": parts]
            }
            return ["role": message.role, "content": message.content]
        case "assistant", "tool":
            return DeepSeekApiClient.toWireMessage(message)
        default:
            return ["role": "user", "content": message.content]
        }
    }
}

// MARK: - Transport

/// Injectable HTTP transport (Kotlin `GenericOpenAiTransport`).
/// Same shape as the DeepSeek transport; the production
/// implementation differs only in omitting the Authorization header
/// when the key is blank (local models).
public protocol GenericOpenAiTransport {
    func post(url: String, apiKey: String, requestBody: String) async throws -> GenericOpenAiConnection
    func get(url: String, apiKey: String) async throws -> GenericOpenAiConnection
}

public typealias GenericOpenAiConnection = DeepSeekConnection

/// URLSession implementation; no extra HTTP dependency.
public final class URLSessionGenericOpenAiTransport: GenericOpenAiTransport {
    private let session: URLSession

    public init(session: URLSession = .shared) {
        self.session = session
    }

    public func post(url: String, apiKey: String, requestBody: String) async throws -> GenericOpenAiConnection {
        guard let url = URL(string: url) else {
            throw URLError(.badURL)
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 120
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        // 本机模型无 Key 时不发 Authorization 头（不发 "Bearer " 空值）。
        if !apiKey.trimmingCharacters(in: .whitespaces).isEmpty {
            request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        }
        request.httpBody = requestBody.data(using: .utf8)
        let (bytes, response) = try await session.bytes(for: request)
        return URLSessionGenericConnection(bytes: bytes, response: response)
    }

    public func get(url: String, apiKey: String) async throws -> GenericOpenAiConnection {
        guard let url = URL(string: url) else {
            throw URLError(.badURL)
        }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 120
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if !apiKey.trimmingCharacters(in: .whitespaces).isEmpty {
            request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        }
        let (bytes, response) = try await session.bytes(for: request)
        return URLSessionGenericConnection(bytes: bytes, response: response)
    }
}

private final class URLSessionGenericConnection: DeepSeekConnection {
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
