import Foundation

/// Injectable HTTP transport for the Claude client. The key crosses
/// only post/get and must never be stored or logged by an
/// implementation.
public protocol ClaudeTransport {
    func post(url: String, apiKey: String, requestBody: String) async throws -> ClaudeConnection
    func get(url: String, apiKey: String) async throws -> ClaudeConnection
}

/// An opened HTTP connection (same shape as the DeepSeek
/// connection; Claude keeps its own protocol, as on Android).
public typealias ClaudeConnection = DeepSeekConnection

/// URLSession transport. Anthropic auth is `x-api-key` +
/// `anthropic-version`, NOT Bearer — a Bearer header is rejected,
/// so this client never reuses the OpenAI-compatible transport.
public final class URLSessionClaudeTransport: ClaudeTransport {
    private let session: URLSession

    public init() {
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 120
        configuration.timeoutIntervalForResource = 300
        self.session = URLSession(configuration: configuration)
    }

    public func post(url: String, apiKey: String, requestBody: String) async throws -> ClaudeConnection {
        var request = try Self.makeRequest(url: url, apiKey: apiKey)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        request.httpBody = Data(requestBody.utf8)
        let (bytes, response) = try await session.bytes(for: request)
        return URLSessionClaudeConnection(bytes: bytes, response: response)
    }

    public func get(url: String, apiKey: String) async throws -> ClaudeConnection {
        var request = try Self.makeRequest(url: url, apiKey: apiKey)
        request.httpMethod = "GET"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (bytes, response) = try await session.bytes(for: request)
        return URLSessionClaudeConnection(bytes: bytes, response: response)
    }

    private static func makeRequest(url: String, apiKey: String) throws -> URLRequest {
        guard let endpoint = URL(string: url) else {
            throw URLError(.badURL)
        }
        var request = URLRequest(url: endpoint)
        // The key goes only into the x-api-key header; it is never
        // stored, logged or included in error text.
        request.setValue(apiKey, forHTTPHeaderField: "x-api-key")
        request.setValue(ClaudeApiClient.anthropicVersion, forHTTPHeaderField: "anthropic-version")
        return request
    }
}

private final class URLSessionClaudeConnection: ClaudeConnection {
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

/// Anthropic Messages API client (`/v1/messages`). Ported from
/// Android `ClaudeApiClient.kt` on main @4496658e.
///
/// Wire facts: auth is the `x-api-key` header plus mandatory
/// `anthropic-version`; `max_tokens` is required; `system` is a
/// top-level parameter; tool calling uses native `tool_use` /
/// `tool_result` content blocks; messages must strictly alternate
/// user/assistant starting and ending with user — the body builder
/// merges consecutive same-role messages and drops a leading or
/// trailing assistant message instead of sending a request
/// Anthropic would 400.
///
/// The client never hardcodes a key and never chooses a model: the
/// model comes from the user's BrainConfig.
public final class ClaudeApiClient {
    public static let defaultBaseUrl = "https://api.anthropic.com"
    public static let anthropicVersion = "2023-06-01"
    public static let defaultMaxTokens = 4096
    /// Suggested model at first configuration only; never a silent
    /// fallback.
    public static let suggestedModels = ["claude-sonnet-4-5"]

    private let transport: ClaudeTransport
    private let onMalformedLine: (String) -> Void

    public init(
        transport: ClaudeTransport = URLSessionClaudeTransport(),
        onMalformedLine: @escaping (String) -> Void = { _ in }
    ) {
        self.transport = transport
        self.onMalformedLine = onMalformedLine
    }

    /// Stream one chat completion. Failures become Failed events
    /// (the stream itself does not throw for expected failures).
    public func streamChat(
        request: ChatRequest, apiKey: String, baseUrl: String
    ) -> AsyncThrowingStream<ClaudeEvent, Error> {
        let transport = self.transport
        let onMalformedLine = self.onMalformedLine
        return AsyncThrowingStream { continuation in
            let task = Task {
                if apiKey.trimmingCharacters(in: .whitespaces).isEmpty {
                    continuation.yield(.failed(
                        code: "not_configured",
                        message: "未配置 Claude API Key：请先在设置里填写你自己的 Key。",
                        retryable: false))
                    continuation.finish()
                    return
                }
                if request.model.trimmingCharacters(in: .whitespaces).isEmpty {
                    continuation.yield(.failed(
                        code: "not_configured",
                        message: "未选择 Claude 模型：请先在设置里选择或刷新模型列表。",
                        retryable: false))
                    continuation.finish()
                    return
                }
                let bodyObject = Self.buildRequestBody(request)
                let bodyData = (try? JSONSerialization.data(withJSONObject: bodyObject)) ?? Data()
                let body = String(data: bodyData, encoding: .utf8) ?? "{}"
                let connection: ClaudeConnection
                do {
                    connection = try await transport.post(
                        url: Self.messagesUrl(baseUrl), apiKey: apiKey, requestBody: body)
                } catch {
                    continuation.yield(.failed(
                        code: "transport_error",
                        message: "连接 Claude 失败（\(Self.ioReason(error))）：\(error.localizedDescription)",
                        retryable: true))
                    continuation.finish()
                    return
                }
                if connection.statusCode != 200 {
                    let detail = Self.parseErrorBody(await connection.readErrorBody())
                    continuation.yield(Self.mapHttpError(
                        status: connection.statusCode, detail: detail))
                    continuation.finish()
                    return
                }
                let parser = ClaudeStreamParser(onMalformedLine: onMalformedLine)
                do {
                    for try await line in connection.bodyLines() {
                        for event in parser.feedLine(line) {
                            continuation.yield(event)
                        }
                        if parser.terminal { break }
                    }
                } catch {
                    continuation.yield(.failed(
                        code: "transport_error",
                        message: "读取 Claude 流失败（\(Self.ioReason(error))）：\(error.localizedDescription)",
                        retryable: true))
                    continuation.finish()
                    return
                }
                for event in parser.finish() {
                    continuation.yield(event)
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// Probe the model catalogue (read-only GET; no inference cost).
    public func listModels(apiKey: String, baseUrl: String) async -> Result<[ModelInfo], Error> {
        if apiKey.trimmingCharacters(in: .whitespaces).isEmpty {
            return .failure(BrainErrorInfo(
                code: "not_configured", message: "未配置 Claude API Key。", retryable: false))
        }
        let connection: ClaudeConnection
        do {
            connection = try await transport.get(url: Self.modelsUrl(baseUrl), apiKey: apiKey)
        } catch {
            return .failure(BrainErrorInfo(
                code: "transport_error",
                message: "连接 Claude 失败（网络异常）：\(error.localizedDescription)",
                retryable: true))
        }
        if connection.statusCode != 200 {
            let detail = Self.parseErrorBody(await connection.readErrorBody())
            let failed = Self.mapHttpError(status: connection.statusCode, detail: detail)
            if case .failed(_, let message, _) = failed {
                return .failure(BrainErrorInfo(
                    code: "http_\(connection.statusCode)", message: message, retryable: false))
            }
            return .failure(BrainErrorInfo(
                code: "transport_error", message: "连接 Claude 失败（网络异常）", retryable: true))
        }
        do {
            let text = try await connection.readBodyText()
            guard let root = (try? JSONSerialization.jsonObject(with: Data(text.utf8)))
                    as? [String: Any] else {
                return .failure(BrainErrorInfo(
                    code: "parse_error", message: "解析 Claude 模型列表失败：格式不对", retryable: false))
            }
            let data = root["data"] as? [Any] ?? []
            var models: [ModelInfo] = []
            for raw in data {
                guard let item = raw as? [String: Any],
                      let id = item["id"] as? String,
                      !id.trimmingCharacters(in: .whitespaces).isEmpty else { continue }
                models.append(ModelInfo(id: id, displayName: id))
            }
            return .success(models)
        } catch {
            return .failure(BrainErrorInfo(
                code: "parse_error",
                message: "解析 Claude 模型列表失败：\(error.localizedDescription)",
                retryable: false))
        }
    }

    static func ioReason(_ error: Error) -> String {
        if let urlError = error as? URLError, urlError.code == .timedOut {
            return "超时"
        }
        return "网络异常"
    }

    static func mapHttpError(status: Int, detail: String) -> ClaudeEvent {
        switch status {
        case -1:
            return .failed(
                code: "transport_error",
                message: "连接 Claude 失败或超时（未收到 HTTP 响应）。\(detail)",
                retryable: true)
        case 401:
            return .failed(
                code: "auth_failed",
                message: "Claude 授权失败（401）：API Key 无效或已失效。\(detail)",
                retryable: false)
        case 400:
            return .failed(
                code: "invalid_request",
                message: "Claude 拒绝了请求（400）：\(detail)",
                retryable: false)
        case 404:
            return .failed(
                code: "model_not_found",
                message: "Claude 找不到该模型（404）：请在设置里刷新模型列表并重新选择。\(detail)",
                retryable: false)
        case 429:
            return .failed(
                code: "rate_limited",
                message: "Claude 请求过于频繁（429）：请稍后再试。\(detail)",
                retryable: true)
        case 529:
            return .failed(
                code: "overloaded",
                message: "Claude 服务过载（529）：请稍后再试。\(detail)",
                retryable: true)
        case 500...599:
            return .failed(
                code: "transport_error",
                message: "Claude 服务暂不可用（HTTP \(status)）：\(detail)",
                retryable: true)
        default:
            return .failed(
                code: "transport_error",
                message: "Claude 请求失败（HTTP \(status)）：\(detail)",
                retryable: true)
        }
    }

    static func parseErrorBody(_ body: String?) -> String {
        guard let body, !body.trimmingCharacters(in: .whitespaces).isEmpty else { return "" }
        guard let root = (try? JSONSerialization.jsonObject(with: Data(body.utf8)))
                as? [String: Any] else {
            return String(body.prefix(300))
        }
        let error = root["error"] as? [String: Any]
        let message = (error?["message"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            ?? (root["message"] as? String ?? "")
        // Anthropic errors carry `type`; a proxy may re-wrap in
        // OpenAI shape with `code` instead.
        let code = (error?["type"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            ?? (error?["code"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        var detail = ""
        if let code { detail += "（错误：\(code)）" }
        if !message.isEmpty { detail += " \(message)" }
        return detail.trimmingCharacters(in: .whitespaces)
    }

    // MARK: - URLs

    public static func messagesUrl(_ baseUrl: String) -> String {
        let base = trimmedBase(baseUrl)
        return base.hasSuffix("/v1/messages") ? base : "\(base)/v1/messages"
    }

    public static func modelsUrl(_ baseUrl: String) -> String {
        let base = trimmedBase(baseUrl)
        if base.hasSuffix("/v1/messages") {
            return String(base.dropLast("/v1/messages".count)) + "/v1/models"
        }
        if base.hasSuffix("/v1/models") { return base }
        return "\(base)/v1/models"
    }

    private static func trimmedBase(_ baseUrl: String) -> String {
        var base = baseUrl.trimmingCharacters(in: .whitespaces)
        while base.hasSuffix("/") { base.removeLast() }
        return base
    }

    // MARK: - Request body

    /// Build the exact Messages API request body. Differences from
    /// the OpenAI-compatible shape, all required by Anthropic:
    /// `system` top-level; content blocks; tool input as a JSON
    /// object; strict role alternation enforced by merging and by
    /// dropping a leading/trailing assistant message; `max_tokens`
    /// required; tools are `{name, description, input_schema}`.
    public static func buildRequestBody(_ request: ChatRequest) -> [String: Any] {
        var body: [String: Any] = [
            "model": request.model,
            "max_tokens": request.maxTokens ?? defaultMaxTokens,
            "stream": true,
        ]
        var systemParts: [String] = []
        var wire: [(role: String, blocks: [[String: Any]])] = []
        for message in request.messages {
            guard let (role, blocks) = toWireBlocks(message, systemParts: &systemParts) else {
                continue
            }
            if let last = wire.last, last.role == role {
                wire[wire.count - 1].blocks += blocks
            } else {
                wire.append((role: role, blocks: blocks))
            }
        }
        // The first message must be user.
        while let first = wire.first, first.role != "user" { wire.removeFirst() }
        // The last message must be user (a tool_result is user role).
        while let last = wire.last, last.role != "user" { wire.removeLast() }
        if !systemParts.isEmpty {
            body["system"] = systemParts.joined(separator: "\n\n")
        }
        body["messages"] = wire.map { ["role": $0.role, "content": $0.blocks] as [String: Any] }
        if !request.tools.isEmpty {
            body["tools"] = request.tools.map { tool -> [String: Any] in
                let schema = ((try? JSONSerialization.jsonObject(
                    with: Data(JSONValue.stringify(tool.inputSchema).utf8)))
                    as? [String: Any]) ?? ["type": "object", "properties": [String: Any]()]
                return [
                    "name": tool.name,
                    "description": tool.description,
                    "input_schema": schema,
                ]
            }
        }
        if let temperature = request.temperature {
            body["temperature"] = temperature
        }
        return body
    }

    /// Map one engine message to (wire role, content blocks).
    /// Returns nil for a message that carries nothing sendable, or
    /// for a system message (collected into systemParts instead).
    private static func toWireBlocks(
        _ message: ChatMessage, systemParts: inout [String]
    ) -> (role: String, blocks: [[String: Any]])? {
        switch message.role {
        case "system":
            if !message.content.trimmingCharacters(in: .whitespaces).isEmpty {
                systemParts.append(message.content)
            }
            return nil
        case "user":
            return ("user", [["type": "text", "text": message.content]])
        case "assistant":
            var blocks: [[String: Any]] = []
            if !message.content.trimmingCharacters(in: .whitespaces).isEmpty {
                blocks.append(["type": "text", "text": message.content])
            }
            for call in message.toolCalls {
                let input = ((try? JSONSerialization.jsonObject(
                    with: Data((call.argumentsJson.trimmingCharacters(in: .whitespaces).isEmpty
                        ? "{}" : call.argumentsJson).utf8))) as? [String: Any])
                    ?? [String: Any]()
                blocks.append([
                    "type": "tool_use",
                    "id": call.id,
                    "name": call.name,
                    "input": input,
                ])
            }
            if blocks.isEmpty { return nil }
            return ("assistant", blocks)
        case "tool":
            if let toolCallId = message.toolCallId,
               !toolCallId.trimmingCharacters(in: .whitespaces).isEmpty {
                return ("user", [[
                    "type": "tool_result",
                    "tool_use_id": toolCallId,
                    "content": message.content,
                ]])
            }
            // Legacy history without an id cannot be paired; keep
            // the information as a user message instead of sending
            // an invalid request.
            return ("user", [["type": "text", "text": "[工具结果]\n" + message.content]])
        default:
            return ("user", [["type": "text", "text": message.content]])
        }
    }
}
