import Foundation

/// One Cloudflare inference request (assembled by the caller;
/// token / account_id are not in here).
public struct CloudflareApiRequest: Equatable {
    /// Full model id, e.g. `@cf/meta/llama-3.1-8b-instruct-fp8-fast`.
    public var model: String
    /// Full conversation history (Workers AI is stateless REST).
    public var messages: [CloudflareInputMessage]
    /// System-level guidance: sent as the first system message.
    public var instructions: String? = nil
    public var tools: [CloudflareTool] = []
    /// 0–5; nil = not sent (server default).
    public var temperature: Double? = nil
    /// Mapped to max_tokens; nil = not sent.
    public var maxOutputTokens: Int? = nil

    public init(
        model: String,
        messages: [CloudflareInputMessage],
        instructions: String? = nil,
        tools: [CloudflareTool] = [],
        temperature: Double? = nil,
        maxOutputTokens: Int? = nil
    ) {
        self.model = model
        self.messages = messages
        self.instructions = instructions
        self.tools = tools
        self.temperature = temperature
        self.maxOutputTokens = maxOutputTokens
    }
}

/// A function tool (OpenAI nested shape); parametersJson must be a
/// JSON object string.
public struct CloudflareTool: Equatable {
    public var name: String
    public var description: String
    public var parametersJson: String

    public init(name: String, description: String, parametersJson: String) {
        self.name = name
        self.description = description
        self.parametersJson = parametersJson
    }
}

/// One input message. role is user / assistant / developer / tool
/// (developer is sent as system; tool carries its tool_call_id).
public struct CloudflareInputMessage: Equatable {
    public var role: String
    public var content: String
    public var toolCallId: String? = nil
    public var toolCalls: [CloudflareToolCall] = []

    public init(
        role: String, content: String,
        toolCallId: String? = nil, toolCalls: [CloudflareToolCall] = []
    ) {
        self.role = role
        self.content = content
        self.toolCallId = toolCallId
        self.toolCalls = toolCalls
    }
}

/// One tool call carried by an assistant message (wire shape).
public struct CloudflareToolCall: Equatable {
    public var id: String
    public var name: String
    public var argumentsJson: String

    public init(id: String, name: String, argumentsJson: String) {
        self.id = id
        self.name = name
        self.argumentsJson = argumentsJson
    }
}

/// Injectable HTTP transport: production uses URLSession, tests use
/// fakes. The token passes only through `open`'s parameters; the
/// transport must not store or log it.
public protocol CloudflareTransport {
    func open(url: String, apiToken: String, requestBody: String) async throws -> CloudflareConnection
}

/// An opened HTTP connection. (Same shape as the DeepSeek
/// connection; Cloudflare keeps its own protocol, as on Android.)
public typealias CloudflareConnection = DeepSeekConnection

/// URLSession transport. Timeouts are a local choice (not verified
/// against official docs): 30s to connect, 120s between events on
/// the long-lived SSE stream.
public final class URLSessionCloudflareTransport: CloudflareTransport {
    private let session: URLSession

    public init() {
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 120
        configuration.timeoutIntervalForResource = 300
        self.session = URLSession(configuration: configuration)
    }

    public func open(url: String, apiToken: String, requestBody: String) async throws -> CloudflareConnection {
        guard let endpoint = URL(string: url) else {
            throw BrainError.transport("连接 Cloudflare Workers AI 失败（网络异常）：服务地址无效")
        }
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        // The token goes only into the Authorization header; this
        // class never stores, logs or embeds it in an error.
        request.setValue("Bearer \(apiToken)", forHTTPHeaderField: "Authorization")
        request.httpBody = Data(requestBody.utf8)
        let (bytes, response) = try await session.bytes(for: request)
        return URLSessionCloudflareConnection(bytes: bytes, response: response)
    }
}

private final class URLSessionCloudflareConnection: CloudflareConnection {
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

/// Cloudflare Workers AI client. Ported from Android
/// `CloudflareApiClient.kt` on main @4496658e.
///
/// Does exactly one thing: with the caller's user-owned API Token,
/// POST one streaming inference request to the official
/// OpenAI-compatible endpoint `{baseUrl}/{account_id}/ai/v1/chat/completions`
/// and hand the SSE lines to [CloudflareStreamParser]. The native
/// `/ai/run` endpoint is NOT used (its messages cannot pair tool
/// calls). AI Gateway is never used.
///
/// Error semantics:
/// - pre-flight problems (blank token / account id / model,
///   unsupported fields): thrown [BrainError], no events;
/// - HTTP non-200: thrown [BrainError] (401 auth / 403-5035
///   unsupported / 403-other auth / 429-3036 or 4006 quota /
///   429-3040 transport / 429-other transport / 400 unsupported /
///   5xx transport), no events;
/// - in-stream failure: a Failed event, stream ends normally;
/// - stream cut before `[DONE]`: if nothing was emitted yet, a
///   thrown Transport error; otherwise a Failed event with code
///   `stream_ended_without_terminal`.
public final class CloudflareApiClient {
    private let transport: CloudflareTransport
    private let onMalformedLine: (String) -> Void

    public init(
        transport: CloudflareTransport = URLSessionCloudflareTransport(),
        onMalformedLine: @escaping (String) -> Void = { _ in }
    ) {
        self.transport = transport
        self.onMalformedLine = onMalformedLine
    }

    public func streamResponse(
        request: CloudflareApiRequest,
        accountId: String,
        apiToken: String,
        baseUrl: String
    ) -> AsyncThrowingStream<CloudflareStreamEvent, Error> {
        let transport = self.transport
        let onMalformedLine = self.onMalformedLine
        return AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    if apiToken.trimmingCharacters(in: .whitespaces).isEmpty {
                        throw BrainError.notConfigured(
                            "未提供 Cloudflare API Token：请用户先在控制台的 Cloudflare 设置里" +
                                "填写自己的 API Token，本层不持有、不生成 token。")
                    }
                    if accountId.trimmingCharacters(in: .whitespaces).isEmpty {
                        throw BrainError.notConfigured(
                            "未提供 Cloudflare Account ID：请用户先在控制台的 Cloudflare 设置里" +
                                "填写自己的 Account ID。")
                    }
                    if baseUrl.trimmingCharacters(in: .whitespaces).isEmpty {
                        throw BrainError.notConfigured(
                            "未提供 Cloudflare 服务地址：请检查 Cloudflare 大脑配置的 baseUrl。")
                    }
                    let requestBodyObject = try Self.buildRequestBody(request)
                    let requestBodyData = try JSONSerialization.data(
                        withJSONObject: requestBodyObject)
                    let requestBody = String(data: requestBodyData, encoding: .utf8) ?? "{}"
                    let url = Self.buildChatCompletionsUrl(baseUrl: baseUrl, accountId: accountId)
                    let connection: CloudflareConnection
                    do {
                        connection = try await transport.open(
                            url: url, apiToken: apiToken, requestBody: requestBody)
                    } catch let error as BrainError {
                        throw error
                    } catch {
                        throw BrainError.transport(
                            "连接 Cloudflare Workers AI 失败（网络异常）：\(error.localizedDescription)")
                    }
                    if connection.statusCode != 200 {
                        throw await Self.mapHttpError(connection: connection, model: request.model)
                    }
                    let parser = CloudflareStreamParser(onMalformedLine: onMalformedLine)
                    var emittedAny = false
                    do {
                        for try await line in connection.bodyLines() {
                            for event in parser.feedLine(line) {
                                emittedAny = true
                                continuation.yield(event)
                            }
                            if parser.sawTerminal { break }
                        }
                    } catch let error as BrainError {
                        throw error
                    } catch {
                        throw BrainError.transport(
                            "读取 Cloudflare 流失败（网络异常）：\(error.localizedDescription)")
                    }
                    for event in parser.finish() {
                        if case .failed = event, !parser.sawTerminal, !emittedAny {
                            // Synthetic truncation failure before any
                            // real content: throw Transport instead of
                            // emitting a lone Failed event.
                            if case .failed(_, let message, _) = event {
                                throw BrainError.transport(message)
                            }
                        }
                        emittedAny = true
                        continuation.yield(event)
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    // MARK: - URL

    /// `{baseUrl}/{accountId}/ai/v1/chat/completions` — the model
    /// id travels in the body's `model` field, not the URL.
    public static func buildChatCompletionsUrl(baseUrl: String, accountId: String) -> String {
        var base = baseUrl.trimmingCharacters(in: .whitespaces)
        while base.hasSuffix("/") { base.removeLast() }
        return "\(base)/\(accountId)/ai/v1/chat/completions"
    }

    // MARK: - HTTP errors

    /// HTTP status -> BrainError (official error-code page, updated
    /// 2026-11-06: 3036/429 quota, 5035/403 paid plan, 3040/429
    /// capacity; third-party reports of 4006 are treated as quota
    /// too; a code-less 429 is per-minute rate limiting, retryable
    /// transport — never reported as quota exhaustion).
    static func mapHttpError(connection: CloudflareConnection, model: String) async -> BrainError {
        let status = connection.statusCode
        let (serverCode, serverMessage) = parseErrorBody(await connection.readErrorBody())
        var detail = ""
        if let serverCode { detail += "（服务器错误码：\(serverCode)）" }
        if let serverMessage { detail += " \(serverMessage)" }
        let isQuotaCode = serverCode == "3036" || serverCode == "4006"
            || (serverMessage?.contains("daily free allocation") ?? false)
        switch status {
        case 401:
            return .authFailed(
                "Cloudflare 授权失败（401）：API Token 无效或已过期，" +
                    "请检查控制台 Cloudflare 设置里的 Token。\(detail)")
        case 403:
            if serverCode == "5035" {
                return .unsupported(
                    "模型 \(model) 需要 Workers Paid 计划，免费层不可用（403 / 5035）。" +
                        "请在设置里换一个免费层模型（例如 " +
                        CloudflareBrainAdapter.suggestedDefaultModel + "）。\(detail)")
            }
            return .authFailed(
                "Cloudflare 请求被拒绝（403）：可能是 Token 缺少 Workers AI 权限，" +
                    "请检查 Token 是否有 Account > Workers AI > Read 权限，" +
                    "并确认 Account ID 与 Token 属于同一账号。\(detail)")
        case 429:
            if isQuotaCode {
                return .quotaExceeded(
                    "Cloudflare 今日免费额度已用完（每天 10,000 Neurons，全账户共享）。" +
                        "按官方说法每日 00:00 UTC 重置，但有实测称个别情况下可能持续更久" +
                        "（最长到首次使用后 24 小时）；本轮不再重试。\(detail)")
            }
            if serverCode == "3040" {
                return .transport("Cloudflare 容量不足（429 / 3040），可重试。\(detail)")
            }
            return .transport(
                "Cloudflare 请求速率受限（429，未带额度错误码）：可能是每分钟" +
                    "请求数超限，可重试。\(detail)")
        case 400:
            return .unsupported("请求被 Cloudflare 拒绝（400）：\(detail)")
        case 404:
            return .unsupported(
                "Cloudflare 找不到该模型（404）：请在设置里检查模型 ID 是否正确，" +
                    "例如 " + CloudflareBrainAdapter.suggestedDefaultModel + "。\(detail)")
        case 500, 502, 503, 504, 520, 521, 522, 523, 524, 525, 526, 527:
            return .transport("Cloudflare 服务暂不可用（HTTP \(status)），可重试。\(detail)")
        default:
            return .transport("Cloudflare 请求失败（HTTP \(status)），可重试。\(detail)")
        }
    }

    /// Parse an error body in either shape: OpenAI
    /// `{"error": {"code", "message"}}` or the Cloudflare envelope
    /// `{"success": false, "errors": [{"code", "message"}]}`. The
    /// code may be a number or a string.
    public static func parseErrorBody(_ errorBody: String?) -> (code: String?, message: String?) {
        guard let errorBody, !errorBody.trimmingCharacters(in: .whitespaces).isEmpty,
              let root = (try? JSONSerialization.jsonObject(with: Data(errorBody.utf8)))
                as? [String: Any] else {
            return (nil, nil)
        }
        func codeString(_ raw: Any?) -> String? {
            if let number = raw as? NSNumber { return number.stringValue }
            if let string = raw as? String, !string.isEmpty { return string }
            return nil
        }
        func messageString(_ raw: Any?) -> String? {
            if let string = raw as? String, !string.isEmpty { return string }
            return nil
        }
        if let error = root["error"] as? [String: Any] {
            return (codeString(error["code"]), messageString(error["message"]))
        }
        guard let errors = root["errors"] as? [Any], !errors.isEmpty,
              let first = errors[0] as? [String: Any] else {
            return (nil, nil)
        }
        return (codeString(first["code"]), messageString(first["message"]))
    }

    // MARK: - Request body

    /// Build the OpenAI-compatible request body (stream: true
    /// always; this client only streams). Throws BrainError on
    /// locally-enforced limits — never silently clamps or rewrites.
    public static func buildRequestBody(_ request: CloudflareApiRequest) throws -> [String: Any] {
        if request.model.trimmingCharacters(in: .whitespaces).isEmpty {
            throw BrainError.unsupported("model 不能为空")
        }
        if let temperature = request.temperature, temperature < 0.0 || temperature > 5.0 {
            throw BrainError.unsupported(
                "Cloudflare Workers AI 的 temperature 范围是 0–5，调用方请求了 temperature=\(temperature)，" +
                    "已拒绝，未静默改写。")
        }
        if let maxTokens = request.maxOutputTokens, maxTokens < 1 {
            throw BrainError.unsupported(
                "Cloudflare Workers AI 的 max_tokens 必须 ≥ 1，调用方请求了 max_tokens=\(maxTokens)，" +
                    "已拒绝，未静默改写。")
        }
        if request.messages.isEmpty
            && (request.instructions ?? "").trimmingCharacters(in: .whitespaces).isEmpty {
            throw BrainError.unsupported("input 与 instructions 不能同时为空")
        }
        var body: [String: Any] = [
            "model": request.model,
            "stream": true,
            "stream_options": ["include_usage": true],
        ]
        if let temperature = request.temperature {
            body["temperature"] = temperature
        }
        if let maxTokens = request.maxOutputTokens {
            body["max_tokens"] = maxTokens
        }
        var messages: [[String: Any]] = []
        if let instructions = request.instructions,
           !instructions.trimmingCharacters(in: .whitespaces).isEmpty {
            messages.append(["role": "system", "content": instructions])
        }
        for message in request.messages {
            switch message.role {
            case "user":
                messages.append(["role": "user", "content": message.content])
            case "assistant":
                if !message.toolCalls.isEmpty {
                    let calls: [[String: Any]] = message.toolCalls.map { call in
                        [
                            "id": call.id,
                            "type": "function",
                            "function": [
                                "name": call.name,
                                "arguments": call.argumentsJson
                                    .trimmingCharacters(in: .whitespaces).isEmpty
                                    ? "{}" : call.argumentsJson,
                            ],
                        ]
                    }
                    messages.append([
                        "role": "assistant",
                        "content": NSNull(),
                        "tool_calls": calls,
                    ])
                } else {
                    messages.append(["role": "assistant", "content": message.content])
                }
            case "tool":
                if let toolCallId = message.toolCallId,
                   !toolCallId.trimmingCharacters(in: .whitespaces).isEmpty {
                    messages.append([
                        "role": "tool",
                        "tool_call_id": toolCallId,
                        "content": message.content,
                    ])
                } else {
                    // Legacy tool message without an id cannot be
                    // paired; downgrade to a visible user result
                    // note — never send an unpaired role="tool".
                    messages.append([
                        "role": "user",
                        "content": "[工具结果]\n" + message.content,
                    ])
                }
            case "developer":
                messages.append(["role": "system", "content": message.content])
            case "system":
                throw BrainError.unsupported(
                    "Cloudflare 适配器内部错误：system 角色应走 instructions，不要直接进 messages。")
            default:
                throw BrainError.unsupported("不支持的消息角色：\(message.role)")
            }
        }
        body["messages"] = messages
        if !request.tools.isEmpty {
            var tools: [[String: Any]] = []
            for tool in request.tools {
                guard let parameters = (try? JSONSerialization.jsonObject(
                        with: Data(tool.parametersJson.utf8))) as? [String: Any] else {
                    throw BrainError.unsupported(
                        "工具 \(tool.name) 的 parametersJson 不是合法 JSON 对象：无法解析")
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
        return body
    }
}
