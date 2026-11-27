// WhereverCode Agent — iOS
//
// 通用 MCP 服务器 + 扩展注册表（控制对齐批四，2026-11-27）。
//
// 与安卓 core/extension/mcp + core/extension/ExtensionRegistry 同口径：
// - 通用 MCP 服务器：名称唯一（id 即名称），传输 http（Streamable
//   HTTP，主流）/ sse / stdio，请求头（值可写 keychain:<引用名>，
//   连接时从本机钥匙串解析，配置里不落明文密钥）、OAuth 字段、
//   启停、连接→工具数、导入标准 mcpServers JSON。
// - iOS 平台边界（如实标注，不假装）：stdio 服务器可登记，但 iOS
//   不能运行本机命令，连接时明示；iOS 暂不支持 SSE 长连接事件流，
//   连接时明示；OAuth 只存字段，401 时如实报服务端错误。
// - 扩展注册表：与安卓同一套七类 kind 与内置行 id（同一身份），
//   overlay 存 kv，用户行可覆盖内置行的启用状态；只有 MCP 与插件
//   两类在本屏有真实操作，其余五类只登记展示。

import Foundation

// MARK: - 通用 MCP 服务器配置

public struct GenericMcpServerConfig: Codable, Identifiable, Equatable, Sendable {
    public var name: String
    /// http（Streamable HTTP）/ sse / stdio。
    public var transport: String
    public var url: String
    public var command: String
    public var headers: [String: String]
    public var oauth: Bool
    public var oauthClientId: String
    public var oauthScopes: String
    public var enabled: Bool

    public var id: String { name }

    public init(
        name: String = "",
        transport: String = "http",
        url: String = "",
        command: String = "",
        headers: [String: String] = [:],
        oauth: Bool = false,
        oauthClientId: String = "",
        oauthScopes: String = "",
        enabled: Bool = true
    ) {
        self.name = name
        self.transport = transport
        self.url = url
        self.command = command
        self.headers = headers
        self.oauth = oauth
        self.oauthClientId = oauthClientId
        self.oauthScopes = oauthScopes
        self.enabled = enabled
    }

    public var transportLabel: String {
        switch transport {
        case "sse": return "SSE · \(url)"
        case "stdio": return "stdio · \(command)"
        default: return "HTTP · \(url)"
        }
    }
}

public enum GenericMcpServerStore {
    private static let storageKey = "mcp.generic_servers.v1"

    public static func list() -> [GenericMcpServerConfig] {
        if let stored = try? LocalData.shared.kv.getJSON(storageKey, as: [GenericMcpServerConfig].self) {
            return stored
        }
        return []
    }

    public static func saveAll(_ list: [GenericMcpServerConfig]) {
        try? LocalData.shared.kv.setJSON(storageKey, list)
    }

    /// 按名称新增或整体替换（编辑表单保存走这里）。
    public static func upsert(_ config: GenericMcpServerConfig) {
        var list = list()
        if let idx = list.firstIndex(where: { $0.name == config.name }) {
            list[idx] = config
        } else {
            list.append(config)
        }
        saveAll(list)
    }

    public static func remove(name: String) {
        saveAll(list().filter { $0.name != name })
    }

    public static func setEnabled(name: String, enabled: Bool) {
        var list = list()
        if let idx = list.firstIndex(where: { $0.name == name }) {
            list[idx].enabled = enabled
            saveAll(list)
        }
    }
}

// MARK: - 通用 MCP 客户端（Streamable HTTP）

public enum GenericMcpError: Error, Equatable {
    case message(String)

    public var text: String {
        switch self {
        case .message(let detail): return detail
        }
    }
}

public struct GenericMcpClient {
    public let config: GenericMcpServerConfig

    public init(config: GenericMcpServerConfig) {
        self.config = config
    }

    /// 连接并列出工具名（initialize 尽力而为 + tools/list）。
    public func listTools() async throws -> [String] {
        switch config.transport {
        case "stdio":
            throw GenericMcpError.message("iOS 不能运行本机命令：stdio 服务器请在已配对设备上运行，本机仅登记。")
        case "sse":
            throw GenericMcpError.message("iOS 暂不支持 SSE 传输（需长连接事件流）；请改用 Streamable HTTP。")
        default:
            break
        }
        guard let url = URL(string: config.url.trimmingCharacters(in: .whitespacesAndNewlines)),
              let scheme = url.scheme, scheme == "http" || scheme == "https" else {
            throw GenericMcpError.message("服务器地址无效。")
        }
        _ = try? await rpc(
            method: "initialize",
            params: [
                "protocolVersion": "2024-11-05",
                "capabilities": [String: Any](),
                "clientInfo": ["name": "WhereverCodeAgent", "version": "1.0"],
            ],
            url: url
        )
        let result = try await rpc(method: "tools/list", params: [String: Any](), url: url)
        let tools = result["tools"] as? [[String: Any]] ?? []
        return tools.compactMap { $0["name"] as? String }.filter { !$0.isEmpty }
    }

    private func rpc(method: String, params: [String: Any], url: URL) async throws -> [String: Any] {
        let body: [String: Any] = [
            "jsonrpc": "2.0",
            "id": UUID().uuidString,
            "method": method,
            "params": params,
        ]
        let bodyData = try JSONSerialization.data(withJSONObject: body)
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.httpBody = bodyData
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json, text/event-stream", forHTTPHeaderField: "Accept")
        request.setValue(McpClient.userAgent, forHTTPHeaderField: "User-Agent")
        request.timeoutInterval = 30
        for (name, rawValue) in config.headers {
            request.setValue(resolveHeaderValue(rawValue), forHTTPHeaderField: name)
        }
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await URLSession.shared.data(for: request)
        } catch {
            throw GenericMcpError.message("网络请求失败：\(String(error.localizedDescription.prefix(160)))")
        }
        if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
            let text = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            throw GenericMcpError.message("服务器返回 HTTP \(http.statusCode)\(text.isEmpty ? "" : "：\(String(text.prefix(200)))")")
        }
        let json = try Self.decodeEnvelope(data)
        if let error = json["error"] as? [String: Any] {
            throw GenericMcpError.message(String((error["message"] as? String ?? "服务器返回错误").prefix(300)))
        }
        return (json["result"] as? [String: Any]) ?? json
    }

    /// 请求头的值：`keychain:<引用名>` 从本机钥匙串解析，其余原样。
    private func resolveHeaderValue(_ raw: String) -> String {
        let prefix = "keychain:"
        guard raw.hasPrefix(prefix) else { return raw }
        let account = String(raw.dropFirst(prefix.count))
        let store = KeychainStore(service: "wherevercode.agent", account: account)
        return (try? store.loadToken()) ?? ""
    }

    /// 解 JSON-RPC 信封；兼容 Streamable HTTP 的 SSE 文本帧
    /// （逐行取 `data:` 后的 JSON，取最后一条）。
    private static func decodeEnvelope(_ data: Data) throws -> [String: Any] {
        if let json = try? JSONSerialization.jsonObject(with: data),
           let dict = json as? [String: Any] {
            return dict
        }
        if let text = String(data: data, encoding: .utf8) {
            var last: [String: Any]?
            for line in text.split(separator: "\n") {
                let trimmed = line.trimmingCharacters(in: .whitespaces)
                guard trimmed.hasPrefix("data:") else { continue }
                let payload = String(trimmed.dropFirst(5)).trimmingCharacters(in: .whitespaces)
                if let payloadData = payload.data(using: .utf8),
                   let json = try? JSONSerialization.jsonObject(with: payloadData),
                   let dict = json as? [String: Any] {
                    last = dict
                }
            }
            if let last { return last }
        }
        throw GenericMcpError.message("服务器返回了无法解析的响应。")
    }
}

// MARK: - mcpServers JSON 导入

public enum GenericMcpImport {
    /// 解析标准 mcpServers JSON（Claude Desktop / VS Code 同款格式）。
    /// 同名覆盖与落盘由调用方处理；解析不出任何服务器时返回空数组。
    public static func parse(_ text: String) -> [GenericMcpServerConfig] {
        guard let data = text.data(using: .utf8),
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let servers = root["mcpServers"] as? [String: Any] else {
            return []
        }
        var out: [GenericMcpServerConfig] = []
        for (name, raw) in servers {
            guard let entry = raw as? [String: Any] else { continue }
            var config = GenericMcpServerConfig(name: name)
            if let command = entry["command"] as? String, !command.isEmpty {
                let args = (entry["args"] as? [Any] ?? []).map { "\($0)" }
                config.transport = "stdio"
                config.command = ([command] + args).joined(separator: " ")
            } else if let url = entry["url"] as? String, !url.isEmpty {
                config.url = url
                config.transport = (entry["type"] as? String) == "sse" ? "sse" : "http"
            } else {
                continue
            }
            if let headers = entry["headers"] as? [String: Any] {
                config.headers = headers.mapValues { "\($0)" }
            }
            out.append(config)
        }
        return out.sorted { $0.name < $1.name }
    }
}

// MARK: - 扩展注册表（七类，与安卓同一套身份）

public enum IosExtensionKind: String, CaseIterable, Codable, Sendable {
    case adapter
    case connector
    case plugin
    case mcp
    case wakeup
    case `protocol`
    case brain

    public var displayName: String {
        switch self {
        case .adapter: return "Agent 适配器"
        case .connector: return "连接器"
        case .plugin: return "插件"
        case .mcp: return "MCP 服务"
        case .wakeup: return "唤醒机制"
        case .protocol: return "通信协议"
        case .brain: return "推理后端"
        }
    }
}

public struct IosExtensionEntry: Codable, Equatable, Sendable {
    public var id: String
    public var kind: IosExtensionKind
    public var displayName: String
    public var implementation: String
    public var status: String
    public var enabled: Bool
    /// builtin | user
    public var source: String
    public var version: String?
    public var note: String?

    public init(
        id: String,
        kind: IosExtensionKind,
        displayName: String,
        implementation: String = "",
        status: String = "unknown",
        enabled: Bool = true,
        source: String = "builtin",
        version: String? = nil,
        note: String? = nil
    ) {
        self.id = id
        self.kind = kind
        self.displayName = displayName
        self.implementation = implementation
        self.status = status
        self.enabled = enabled
        self.source = source
        self.version = version
        self.note = note
    }
}

public enum IosExtensionRegistry {
    private static let overlayKey = "extensions.overlay.v1"

    private static func overlay() -> [IosExtensionEntry] {
        if let stored = try? LocalData.shared.kv.getJSON(overlayKey, as: [IosExtensionEntry].self) {
            return stored
        }
        return []
    }

    /// 全部行：内置行先行，用户行同 (kind, id) 覆盖（与安卓同规则）。
    public static func all() -> [IosExtensionEntry] {
        var merged: [String: IosExtensionEntry] = [:]
        var order: [String] = []
        for entry in builtinEntries() {
            let key = "\(entry.kind.rawValue)/\(entry.id)"
            if merged[key] == nil { order.append(key) }
            merged[key] = entry
        }
        for entry in overlay() {
            let key = "\(entry.kind.rawValue)/\(entry.id)"
            if merged[key] == nil { order.append(key) }
            merged[key] = entry
        }
        return order.compactMap { merged[$0] }
    }

    public static func countsByKind() -> [IosExtensionKind: Int] {
        var counts: [IosExtensionKind: Int] = [:]
        for kind in IosExtensionKind.allCases { counts[kind] = 0 }
        for entry in all() { counts[entry.kind, default: 0] += 1 }
        return counts
    }

    private static func e(
        _ id: String,
        _ kind: IosExtensionKind,
        _ name: String,
        _ impl: String = "",
        status: String = "unknown",
        version: String? = nil,
        note: String? = nil
    ) -> IosExtensionEntry {
        IosExtensionEntry(id: id, kind: kind, displayName: name, implementation: impl, status: status, version: version, note: note)
    }

    /// 内置行：与安卓 ExtensionRegistry.builtinEntries 同一套 id 与分类
    /// （源头是 Hand 侧 BUILTIN_EXTENSIONS），只作登记，不含执行代码。
    public static func builtinEntries() -> [IosExtensionEntry] {
        [
            e("wherevercode-agent", .adapter, "本机 · WhereverCode", "App 自身：BrainEngine + TaskScheduler + ChatTaskDispatcher（App 内）", status: "live"),
            e("codex", .adapter, "Codex", "CLI：codex exec --skip-git-repo-check -s workspace-write {prompt}", status: "live"),
            e("codebuddy", .adapter, "CodeBuddy", "CLI：codebuddy -p -y {prompt}", status: "live"),
            e("example-agent", .adapter, "Example Agent", "CLI：grok -p {prompt} --always-approve --sandbox workspace --output-format plain", status: "live"),
            e("gemini", .adapter, "Gemini", "CLI：gemini（设备上已登录的 CLI）", status: "live"),
            e("agy", .adapter, "Antigravity", "CLI：agy（设备上已登录的 CLI）", status: "live"),
            e("hand-tcp-transport", .connector, "Hand 直连传输", "hand_server.py 本进程：TCP 18743 + 密封信封（X25519+HKDF 会话，AES-256-GCM，v2 任务凭证）", status: "live"),
            e("tailscale", .connector, "Tailscale 组网", "Tailnet：Hand 经该网络可达", status: "live"),
            e("healthcheck", .plugin, "健康检查", "run_healthcheck()（Hand 内）；CLI：hand_server.py plugin run healthcheck", status: "live", note: "在 Hand 设备上运行，App 内不执行；同一身份，不同宿主。"),
            e("local-utils", .plugin, "本地小工具", "App 内置示例插件：local_time（本地时间）/ calc（四则运算），真实执行", status: "live", version: "1", note: "App 内置插件，随 App 一起提供；停用后大脑不再看到它的工具。"),
            e("hand-mcp-stdio", .mcp, "Hand MCP 服务（stdio）", "mcp_serve()（Hand 内）：stdio JSON-RPC 2.0，协议 2024-11-05，工具 list/read/write/run；run 走通用 runner，文件夹须在授权表里", status: "live", note: "Hand 设备上的 MCP 服务。本机登记的通用 MCP 服务器另行计入 MCP 类。"),
            e("webhook", .wakeup, "Webhook 唤醒", "webhook_deliver()（Hand 内）：出站 POST，Standard Webhooks HMAC 签名，https-only", status: "mechanism-live"),
            e("polling", .wakeup, "轮询唤醒", "App HandClient hello 轮询 + WhereverCode 队列 10 分钟轮询", status: "live"),
            e("mcp", .protocol, "MCP（通信协议）", "JSON-RPC 2.0，protocolVersion 2024-11-05；由 hand-mcp-stdio 提供；工具 list/read/write/run", status: "live"),
            e("direct-transport", .protocol, "Hand 直连协议", "hand_server.py 本进程的密封信封协议：wherevercode_hello 握手 + cross_device_tool_call 信封", status: "live"),
            e("wacf", .protocol, "WACF 自有总线", "WACF 设计（2026-11-08，U1–U12 已定：控制 + 消息账本）；Hand 内暂无实现代码", status: "design-only"),
            e("openai-compatible", .brain, "OpenAI 兼容模型", "App BrainConfig：OpenAI 兼容 /v1/models；Key 在 BrainKeyStore", status: "app-configured"),
            e("cloudflare", .brain, "Cloudflare", "App BrainConfig：Cloudflare Workers AI；Key 在 BrainKeyStore", status: "app-configured"),
            e("deepseek", .brain, "DeepSeek", "App BrainConfig：DeepSeek 模型 API；Key 在 BrainKeyStore", status: "app-configured"),
            e("chatgpt-membership", .brain, "ChatGPT 会员", "App BrainConfig：SIWC 会员额度大脑", status: "app-configured"),
            e("custom-model-api", .brain, "自定义模型 API", "App BrainConfig：用户自建 OpenAI 兼容大脑", status: "app-configured"),
            e("codex-membership", .brain, "Codex 会员", "CLI 会员额度后端（Hand 发现派生）", status: "app-configured"),
            e("codebuddy-membership", .brain, "CodeBuddy 会员", "CLI 会员额度后端（Hand 发现派生）", status: "app-configured"),
            e("example-agent-membership", .brain, "Example Agent 会员", "CLI 会员额度后端（Hand 发现派生）", status: "app-configured"),
        ]
    }
}
