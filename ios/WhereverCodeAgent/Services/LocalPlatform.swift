// WhereverCode Agent — iOS
//
// 本地平台运行时（频道三层一期·本地层）：App 内嵌平台服务的唯一
// 持有者。AppState 初始化时 install 一次，之后频道链路用
// local.platform 这个伪主机走本地优先客户端在进程内直达
// LocalChannelService——不经网络、不填地址、不要令牌。
//
// 远程平台仍是原来的路：设置里存了远程凭证时频道端点优先远程，
// 本地层只是新装/未配置时的底座（平台自由切换是三层三期）。

import Foundation

public enum LocalPlatform {
    /// 本地平台伪主机（只在本机进程内有意义，永远不会真的解析 DNS）。
    public static let localHost = "local.platform"

    /// 本地调用凭证（进程内固定值，不是秘密；只用来区分本机 App 身份）。
    public static let localToken = "local-app-token"

    /// 本机 App 在本地频道里的成员身份（与频道成员表同 id）。
    public static let appAgentID = "local-app"
    public static let appAgentName = "本机 App"

    /// 本地平台的 MCP 端点（形状与远程同构，请求永不真的出网）。
    public static let localEndpoint = URL(string: "https://local.platform/api/open/v1/mcp")!

    /// 本地模式下频道工具全集（tools/list 的本地答复；与账本 dispatch 的分支一一对应）。
    public static let toolNames: [String] = [
        "agent_channel_list",
        "agent_channel_join",
        "agent_channel_open_dm",
        "agent_channel_post",
        "agent_channel_messages",
        "agent_channel_create",
        "agent_channel_rename",
        "agent_channel_set_archived",
        "agent_channel_set_history_visible",
        "agent_channel_delete",
        "agent_channel_add_member",
        "agent_channel_remove_member",
        "agent_channel_set_member_role",
        "agent_channel_create_webhook",
        "agent_channel_list_webhooks",
        "agent_channel_delete_webhook",
        "agent_channel_recall",
        "agent_channel_delete_message",
        "agent_channel_pin",
        "agent_channel_react",
        "agent_channel_readers",
    ]

    private static let lock = NSLock()
    private static var service: LocalChannelService?

    /// 安装本地服务（幂等；已装直接返回）。须在任何频道调用之前完成。
    public static func install() {
        lock.lock(); defer { lock.unlock() }
        if service != nil { return }
        let file = storageFileURL()
        service = LocalChannelService(persistence: FileLocalChannelPersistence(fileURL: file))
    }

    /// 测试专用：直接装入一个服务实例。
    public static func installForTest(_ instance: LocalChannelService) {
        lock.lock(); defer { lock.unlock() }
        service = instance
    }

    public static func serviceOrNull() -> LocalChannelService? {
        lock.lock(); defer { lock.unlock() }
        return service
    }

    public static var isInstalled: Bool { serviceOrNull() != nil }

    public static func isLocalHost(_ host: String?) -> Bool {
        host?.trimmingCharacters(in: .whitespacesAndNewlines)
            .caseInsensitiveCompare(localHost) == .orderedSame
    }

    public static func isLocalEndpoint(_ url: URL?) -> Bool {
        guard let url else { return false }
        return isLocalHost(url.host)
    }

    public static func defaultChannelId() -> String? {
        serviceOrNull()?.defaultChannelId()
    }

    /// 本地模式的 WS 形状 URL（不会真的建套接字；本地推送走消息
    /// 总线，见 subscribeLocalChannelEvents）。
    public static func localWebSocketURL(channelID: String) -> URL? {
        var components = URLComponents()
        components.scheme = "wss"
        components.host = localHost
        components.path = "/api/open/v1/channel/ws"
        components.queryItems = [URLQueryItem(name: "channel_id", value: channelID)]
        return components.url
    }

    private static func storageFileURL() -> URL {
        let manager = FileManager.default
        let base = manager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? manager.temporaryDirectory
        try? manager.createDirectory(at: base, withIntermediateDirectories: true)
        return base.appendingPathComponent("local_platform_channels.json")
    }

    // ---- 本地优先路由（McpClient 的进程内处理器） --------------------------

    /// 处理一个发往本地端点的 MCP 请求体，返回响应体（与远程平台
    /// 同一信封形状）。语义同安卓 LocalFirstPlatformHttpClient：
    /// 只支持 tools/call（另加 tools/list 的本地答复）；凭证不对
    /// 回 isError 信封，传输层会把文案原样抛给界面，绝不假成功。
    public static func handleMcpRequest(body: Data, token: String) throws -> Data {
        guard let service = serviceOrNull() else {
            throw McpError.transport("本地平台服务未启动")
        }
        guard let parsed = try? JSONSerialization.jsonObject(with: body),
              let request = parsed as? [String: Any] else {
            throw McpError.transport("本地平台收到无法解析的请求")
        }
        let id: Any = request["id"] ?? NSNull()
        let method = (request["method"] as? String) ?? ""
        if method == "tools/list" {
            let tools: [[String: Any]] = toolNames.map { ["name": $0] }
            return try envelope(id: id, result: ["tools": tools])
        }
        if method != "tools/call" {
            return try envelope(id: id, result: errorResult("本地平台只支持 tools/call"))
        }
        if token != localToken {
            return try envelope(id: id, result: errorResult("本地平台凭证无效"))
        }
        let params = (request["params"] as? [String: Any]) ?? [:]
        let name = (params["name"] as? String) ?? ""
        let arguments = (params["arguments"] as? [String: Any]) ?? [:]
        do {
            let result = try service.dispatch(
                tool: name, args: arguments, callerId: appAgentID, callerName: appAgentName)
            return try envelope(id: id, result: okResult(result))
        } catch let error as LocalServiceError {
            return try envelope(id: id, result: errorResult(error.message))
        }
    }

    private static func okResult(_ result: [String: Any]) -> [String: Any] {
        let text = (try? JSONSerialization.data(withJSONObject: result))
            .flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
        return [
            "content": [["type": "text", "text": text] as [String: Any]],
            "isError": false,
        ]
    }

    private static func errorResult(_ message: String) -> [String: Any] {
        [
            "content": [["type": "text", "text": message] as [String: Any]],
            "isError": true,
        ]
    }

    private static func envelope(id: Any, result: [String: Any]) throws -> Data {
        try JSONSerialization.data(withJSONObject: [
            "jsonrpc": "2.0",
            "id": id,
            "result": result,
        ])
    }

    // ---- 本地推送（消息总线订阅，喂给 ChannelFeed 的本地分支） -------------

    /// 订阅本地频道的消息事件：新帖以与平台 WS 完全相同的事件
    /// JSON 文本交给 handler；返回取消订阅闭包。本地推送是进程内
    /// 直达（比网络 WS 更快，不冒充网络连接）。
    public static func subscribeLocalChannelEvents(
        channelId: String,
        handler: @escaping (String) -> Void
    ) -> () -> Void {
        guard let service = serviceOrNull() else { return {} }
        let token = service.addMessageListener(channelId: channelId) { event in
            if let data = try? JSONSerialization.data(withJSONObject: event),
               let text = String(data: data, encoding: .utf8) {
                handler(text)
            }
        }
        return { service.removeMessageListener(token) }
    }
}

/// 本地账本的文件持久化：临时文件写完再改名，避免半截文件毁账本。
public final class FileLocalChannelPersistence: LocalChannelPersistence {
    private let fileURL: URL

    public init(fileURL: URL) {
        self.fileURL = fileURL
    }

    public func load() -> String? {
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return nil }
        return try? String(contentsOf: fileURL, encoding: .utf8)
    }

    public func save(_ text: String) {
        let manager = FileManager.default
        let directory = fileURL.deletingLastPathComponent()
        try? manager.createDirectory(at: directory, withIntermediateDirectories: true)
        let tmp = directory.appendingPathComponent(fileURL.lastPathComponent + ".tmp")
        do {
            try text.write(to: tmp, atomically: false, encoding: .utf8)
            if manager.fileExists(atPath: fileURL.path) {
                try manager.removeItem(at: fileURL)
            }
            try manager.moveItem(at: tmp, to: fileURL)
        } catch {
            try? text.write(to: fileURL, atomically: false, encoding: .utf8)
            try? manager.removeItem(at: tmp)
        }
    }
}
