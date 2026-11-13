// WhereverCode Agent — iOS MVP
//
// Minimal MCP client for the V5 platform endpoint.
//
// Protocol (verified against the live server, 2026-11-13):
// - POST `{base}/api/open/v1/mcp`, JSON-RPC 2.0 `tools/call`.
// - Request: `{"jsonrpc":"2.0","id":"<uuid>","method":"tools/call",
//   "params":{"name":<tool>,"arguments":{...}}}`.
// - Response envelope: `{"jsonrpc":"2.0","result":{"content":
//   [{"type":"text","text":"{...tool JSON...}"}],"isError":false}}`.
//   A tool-level failure arrives as `result.isError == true` with the
//   reason in `content[0].text`; an unknown tool is NOT a JSON-RPC
//   error, it is `isError:true`.
// - `tools/list` returns the tool list the same way.
//
// Security rules enforced here:
// - The Bearer token goes ONLY in the `Authorization` header, never in
//   the URL, the body or any error/log string.
// - TLS uses the system trust store with hostname validation
//   (URLSession default). No custom trust evaluation, no ATS bypass,
//   no self-signed certificates.
// - A `User-Agent` identifying this app is sent: the platform sits
//   behind Cloudflare, which blocks requests without one.

import Foundation

public enum McpError: Error, Equatable {
    case notConfigured(String)
    case transport(String)
    case serverError(String)
    case unparsableResponse

    public var message: String {
        switch self {
        case .notConfigured(let detail): return detail
        case .transport(let detail): return detail
        case .serverError(let detail): return detail
        case .unparsableResponse: return "平台返回了无法解析的响应。"
        }
    }
}

/// Builds and executes MCP `tools/call` requests. The token is supplied
/// per call by `tokenProvider` and is never retained or logged.
public struct McpClient {
    public static let userAgent = "WhereverCode-iOS/1.1 (WhereverCodeAgent)"

    private let endpoint: URL
    private let tokenProvider: () -> String?
    private let session: URLSession

    public init(endpoint: URL, tokenProvider: @escaping () -> String?, session: URLSession = .shared) {
        self.endpoint = endpoint
        self.tokenProvider = tokenProvider
        self.session = session
    }

    /// Build the JSON-RPC request body for one `tools/call`.
    /// Public and pure so tests can assert its shape without networking.
    public static func toolsCallBody(id: String, name: String, arguments: [String: Any]) -> [String: Any] {
        [
            "jsonrpc": "2.0",
            "id": id,
            "method": "tools/call",
            "params": ["name": name, "arguments": arguments] as [String: Any],
        ]
    }

    /// Call one tool and return its decoded result dictionary.
    public func callTool(name: String, arguments: [String: Any] = [:]) async throws -> [String: Any] {
        guard let token = tokenProvider(), !token.isEmpty else {
            throw McpError.notConfigured("未配置平台 Agent 凭证：请先在设置里保存 Token。")
        }
        let body = Self.toolsCallBody(id: UUID().uuidString, name: name, arguments: arguments)
        let bodyData: Data
        do {
            bodyData = try JSONSerialization.data(withJSONObject: body)
        } catch {
            throw McpError.transport("无法构造请求。")
        }
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.httpBody = bodyData
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        request.timeoutInterval = 60

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            // Never include the token or the request in the message.
            throw McpError.transport("网络请求失败：\(shortError(error))")
        }
        if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
            throw Self.httpError(statusCode: http.statusCode, body: data)
        }
        return try Self.unwrapToolResult(data: data)
    }

    /// List the tools the server exposes (`tools/list`). Used by the
    /// Settings connection test; returns the tool names.
    public func listToolNames() async throws -> [String] {
        guard let token = tokenProvider(), !token.isEmpty else {
            throw McpError.notConfigured("未配置平台 Agent 凭证：请先在设置里保存 Token。")
        }
        let body: [String: Any] = [
            "jsonrpc": "2.0",
            "id": UUID().uuidString,
            "method": "tools/list",
            "params": [String: Any](),
        ]
        let bodyData = try JSONSerialization.data(withJSONObject: body)
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.httpBody = bodyData
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        request.timeoutInterval = 30
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw McpError.transport("网络请求失败：\(shortError(error))")
        }
        if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
            throw Self.httpError(statusCode: http.statusCode, body: data)
        }
        let json = try Self.decodeJSON(data)
        guard let result = json["result"] as? [String: Any],
              let tools = result["tools"] as? [[String: Any]] else {
            throw McpError.unparsableResponse
        }
        return tools.compactMap { $0["name"] as? String }
    }

    /// Unwrap one MCP envelope into the tool's result dictionary.
    /// Public and pure so tests can cover it without networking.
    public static func unwrapToolResult(data: Data) throws -> [String: Any] {
        let json = try decodeJSON(data)
        if let error = json["error"] as? [String: Any] {
            let detail = (error["message"] as? String ?? "平台调用失败")
            throw McpError.serverError(String(detail.prefix(300)))
        }
        let result = (json["result"] as? [String: Any]) ?? json
        if result["isError"] as? Bool == true {
            let detail = firstContentText(result) ?? "平台调用失败"
            throw McpError.serverError(String(detail.prefix(300)))
        }
        guard let text = firstContentText(result), !text.isEmpty else {
            throw McpError.unparsableResponse
        }
        guard let toolJSON = try? JSONSerialization.jsonObject(with: Data(text.utf8)),
              let dict = toolJSON as? [String: Any] else {
            throw McpError.unparsableResponse
        }
        return dict
    }

    /// Build the error for a non-2xx HTTP response, including the
    /// platform's own response body when it sent one (truncated).
    /// Showing only "HTTP 401" hides the server's real reason; the
    /// body is the platform's error text, never our token (the
    /// server does not echo request headers back).
    public static func httpError(statusCode: Int, body: Data) -> McpError {
        let text = String(data: body, encoding: .utf8)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !text.isEmpty else {
            return .transport("平台返回 HTTP \(statusCode)")
        }
        return .transport("平台返回 HTTP \(statusCode)：\(String(text.prefix(200)))")
    }

    // MARK: - Private helpers

    private static func decodeJSON(_ data: Data) throws -> [String: Any] {
        guard let json = try? JSONSerialization.jsonObject(with: data),
              let dict = json as? [String: Any] else {
            throw McpError.unparsableResponse
        }
        return dict
    }

    private static func firstContentText(_ result: [String: Any]) -> String? {
        guard let content = result["content"] as? [[String: Any]],
              let first = content.first else { return nil }
        return first["text"] as? String
    }

    /// Short, token-free description of a transport error.
    private func shortError(_ error: Error) -> String {
        let description = (error as NSError).localizedDescription
        return String(description.prefix(200))
    }
}
