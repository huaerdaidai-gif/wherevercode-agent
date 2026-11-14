import Foundation

public enum McpError: Error, LocalizedError, Equatable {
    case notConfigured
    case transport(String)
    case serverError(Int, String)
    case unparsableResponse

    public var errorDescription: String? {
        switch self {
        case .notConfigured:
            return "未配置平台 Agent 凭证：请先在设置里保存 Token。"
        case .transport(let msg):
            return "网络请求失败：\(msg)"
        case .serverError(let code, let msg):
            return "平台返回 HTTP \(code)：\(msg)"
        case .unparsableResponse:
            return "平台返回了无法解析的响应。"
        }
    }
}

public struct McpClient: Sendable {
    public static let userAgent = "WhereverCode-iOS/1.4 (WhereverCodeAgent)"
    public let endpoint: URL
    public let tokenProvider: @Sendable () -> String?

    public init(endpoint: URL, tokenProvider: @escaping @Sendable () -> String?) {
        self.endpoint = endpoint
        self.tokenProvider = tokenProvider
    }

    public static func toolsCallBody(id: String = UUID().uuidString, name: String, arguments: [String: Any]) -> [String: Any] {
        [
            "jsonrpc": "2.0",
            "id": id,
            "method": "tools/call",
            "params": [
                "name": name,
                "arguments": arguments
            ]
        ]
    }

    public static func unwrapToolResult(data: Data) throws -> [String: Any] {
        guard let top = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw McpError.unparsableResponse
        }
        if let err = top["error"] as? [String: Any], let msg = err["message"] as? String {
            throw McpError.serverError(400, msg)
        }
        guard let result = top["result"] as? [String: Any] else {
            throw McpError.unparsableResponse
        }
        if let content = result["content"] as? [[String: Any]],
           let firstText = content.first?["text"] as? String,
           let innerData = firstText.data(using: .utf8),
           let innerObj = try? JSONSerialization.jsonObject(with: innerData) as? [String: Any] {
            return innerObj
        }
        return result
    }

    public func listToolNames() async throws -> [String] {
        guard let token = tokenProvider(), !token.isEmpty else {
            throw McpError.notConfigured
        }
        var req = URLRequest(url: endpoint)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        let body: [String: Any] = ["jsonrpc": "2.0", "id": UUID().uuidString, "method": "tools/list", "params": [:]]
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, resp) = try await URLSession.shared.data(for: req)
        if let http = resp as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw McpError.serverError(http.statusCode, String(data: data, encoding: .utf8) ?? "")
        }
        guard let top = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let result = top["result"] as? [String: Any],
              let tools = result["tools"] as? [[String: Any]] else {
            throw McpError.unparsableResponse
        }
        return tools.compactMap { $0["name"] as? String }
    }

    public func callTool(name: String, arguments: [String: Any] = [:]) async throws -> [String: Any] {
        guard let token = tokenProvider(), !token.isEmpty else {
            throw McpError.notConfigured
        }
        var req = URLRequest(url: endpoint)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        req.httpBody = try JSONSerialization.data(withJSONObject: Self.toolsCallBody(name: name, arguments: arguments))
        let (data, resp) = try await URLSession.shared.data(for: req)
        if let http = resp as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw McpError.serverError(http.statusCode, String(data: data, encoding: .utf8) ?? "")
        }
        return try Self.unwrapToolResult(data: data)
    }
}

public struct PlatformTokenIssuer: Sendable {
    public let client: McpClient

    public init(client: McpClient) {
        self.client = client
    }

    public func issueToken(agentId: String, name: String) async -> IssueTokenResult {
        let cleanAgent = agentId.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanAgent.isEmpty else { return .failed("Agent ID 不能为空（如 chatgpt）。") }
        guard !cleanName.isEmpty else { return .failed("令牌名称不能为空。") }
        do {
            let res = try await client.callTool(name: "issue_agent_token", arguments: ["agent_id": cleanAgent, "name": cleanName])
            guard let token = res["token"] as? String, !token.isEmpty else {
                return .failed("平台没有返回令牌（签发失败）。")
            }
            let tokenId = (res["token_id"] as? String) ?? UUID().uuidString
            return .issued(token: token, tokenId: tokenId, agentId: cleanAgent, name: cleanName)
        } catch {
            return .failed("签发失败：平台端点不支持签发令牌，或当前平台凭证没有 owner 权限。(\(error.localizedDescription))")
        }
    }
}
