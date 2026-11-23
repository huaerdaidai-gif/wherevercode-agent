// WhereverCode Agent — iOS
//
// 内网平台服务（频道三层二期·内网层，方案便签 11 号）：把一期的
// 本地账本 LocalChannelService 以与公网平台完全相同的线协议挂到
// 局域网——同网设备用现成的平台客户端连进来，就是多了一位普通
// 成员，全功能不依赖公网。这里只做三件事：HTTP 上的 MCP
// tools/call、WS 订阅推送、令牌鉴权（LanTokenRegistry）；一切频道
// 语义（成员门禁、4000 字、幂等、角色保护）仍由账本 dispatch
// 执行，本类不复制规则。与安卓 LanPlatformServer 同协议同口径。
//
// 实现用 Network.framework（NWListener/NWConnection）：HTTP 手写
// 解析（体积上限与安卓同值），WS 手写帧编解码（客户端帧必须带
// 掩码，RFC 6455 5.3 节）。
//
// 诚实口径：iOS 宿主仅前台——App 退后台系统即暂停进程，服务随之
// 不可达；界面文案如实标注，不冒充常驻服务器。

import CommonCrypto
import Foundation
import Network

public final class LanPlatformServer {
    public static let defaultPort: UInt16 = 18744
    private static let mcpPath = "/api/open/v1/mcp"
    private static let wsPath = "/api/open/v1/channel/ws"
    private static let maxHeaderBytes = 16 * 1024
    private static let maxBodyBytes = 1024 * 1024
    private static let maxWsPayloadBytes = 1024 * 1024

    private let serviceProvider: () -> LocalChannelService?
    private let registry: LanTokenRegistry
    private let requestedPort: UInt16
    private let stateLock = NSLock()
    private var listener: NWListener?
    private var connections: [ObjectIdentifier: Connection] = [:]
    private var running = false
    private var actualPort: UInt16 = 0

    public init(
        serviceProvider: @escaping () -> LocalChannelService?,
        registry: LanTokenRegistry,
        port: UInt16 = LanPlatformServer.defaultPort
    ) {
        self.serviceProvider = serviceProvider
        self.registry = registry
        self.requestedPort = port
    }

    public var isRunning: Bool {
        stateLock.lock(); defer { stateLock.unlock() }
        return running
    }

    /// 实际监听端口（start 后有效；请求 0 时是系统分配的临时端口）。
    public var port: UInt16 {
        stateLock.lock(); defer { stateLock.unlock() }
        return actualPort
    }

    @discardableResult
    public func start() -> Bool {
        stateLock.lock()
        if running { stateLock.unlock(); return true }
        stateLock.unlock()
        let params = NWParameters.tcp
        params.allowLocalEndpointReuse = true
        let endpointPort = NWEndpoint.Port(rawValue: requestedPort) ?? .any
        guard let nwListener = try? NWListener(using: params, on: endpointPort) else {
            return false
        }
        nwListener.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.stateLock.lock()
                self.running = true
                if let bound = nwListener.port?.rawValue { self.actualPort = bound }
                self.stateLock.unlock()
            case .failed, .cancelled:
                self.stateLock.lock()
                if self.listener === nwListener {
                    self.running = false
                    self.listener = nil
                }
                self.stateLock.unlock()
            default:
                break
            }
        }
        nwListener.newConnectionHandler = { [weak self] nwConnection in
            self?.accept(nwConnection)
        }
        stateLock.lock()
        listener = nwListener
        stateLock.unlock()
        nwListener.start(queue: DispatchQueue(label: "lan-platform-listener"))
        return true
    }

    public func stop() {
        stateLock.lock()
        let current = listener
        listener = nil
        running = false
        let conns = Array(connections.values)
        connections.removeAll()
        stateLock.unlock()
        current?.cancel()
        for conn in conns {
            conn.close()
        }
    }

    // ---- 连接管理 ----------------------------------------------------------

    private func accept(_ nwConnection: NWConnection) {
        let connection = Connection(server: self, connection: nwConnection)
        stateLock.lock()
        connections[ObjectIdentifier(connection)] = connection
        stateLock.unlock()
        connection.start()
    }

    fileprivate func connectionClosed(_ connection: Connection) {
        stateLock.lock()
        connections.removeValue(forKey: ObjectIdentifier(connection))
        stateLock.unlock()
    }

    fileprivate func service() -> LocalChannelService? { serviceProvider() }

    fileprivate func authenticate(headers: [String: String]) -> LanTokenRegistry.Entry? {
        guard let auth = headers["authorization"] else { return nil }
        let prefix = "Bearer "
        guard auth.lowercased().hasPrefix(prefix.lowercased()) else { return nil }
        let token = String(auth.dropFirst(prefix.count)).trimmingCharacters(in: .whitespacesAndNewlines)
        return registry.authenticate(token: token)
    }

    // ---- HTTP 语义（纯请求到响应，与安卓 handle 同口径） ---------------------

    public struct HttpRequest {
        public let method: String
        public let target: String
        public let headers: [String: String]
        public let body: Data?
    }

    public struct HttpResponse {
        public let status: Int
        public let body: String
        public let contentType: String
    }

    public func handle(_ req: HttpRequest) -> HttpResponse {
        let path = req.target.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? req.target
        if req.method == "GET", path == "/" || path == "/api/open/v1/health" {
            return HttpResponse(status: 200, body: "{\"ok\":true,\"service\":\"lan-platform\",\"version\":\"1\"}", contentType: "application/json")
        }
        if req.method == "POST", path == Self.mcpPath {
            return handleMcp(req)
        }
        return Self.jsonError(404, "not found")
    }

    private func handleMcp(_ req: HttpRequest) -> HttpResponse {
        guard let entry = authenticate(headers: req.headers) else {
            return Self.jsonError(401, "unauthorized")
        }
        guard let service = serviceProvider() else {
            return Self.jsonError(503, "本地平台服务未启动")
        }
        // 登记身份（幂等）：open_dm 等工具要求对方是已登记 Agent。
        service.registerAgent(agentId: entry.agentId, agentName: entry.agentName)
        guard let body = req.body else { return Self.jsonError(400, "missing body") }
        guard let parsed = try? JSONSerialization.jsonObject(with: body),
              let request = parsed as? [String: Any] else {
            return Self.jsonError(400, "bad json")
        }
        let id: Any = request["id"] ?? NSNull()
        if (request["method"] as? String) != "tools/call" {
            return Self.rpcEnvelope(id: id, result: Self.errorResult("内网平台只支持 tools/call"))
        }
        let params = (request["params"] as? [String: Any]) ?? [:]
        let name = (params["name"] as? String) ?? ""
        let arguments = (params["arguments"] as? [String: Any]) ?? [:]
        do {
            let result = try service.dispatch(tool: name, args: arguments, callerId: entry.agentId, callerName: entry.agentName)
            return Self.rpcEnvelope(id: id, result: Self.okResult(result))
        } catch let error as LocalServiceError {
            return Self.rpcEnvelope(id: id, result: Self.errorResult(error.message))
        } catch {
            return Self.rpcEnvelope(id: id, result: Self.errorResult("内网平台调用失败"))
        }
    }

    public static func jsonError(_ status: Int, _ message: String) -> HttpResponse {
        let body = (try? JSONSerialization.data(withJSONObject: ["error": message]))
            .flatMap { String(data: $0, encoding: .utf8) } ?? "{\"error\":\"error\"}"
        return HttpResponse(status: status, body: body, contentType: "application/json")
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

    private static func rpcEnvelope(id: Any, result: [String: Any]) -> HttpResponse {
        let body = (try? JSONSerialization.data(withJSONObject: [
            "jsonrpc": "2.0",
            "id": id,
            "result": result,
        ])).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
        return HttpResponse(status: 200, body: body, contentType: "application/json")
    }

    public static func reason(_ status: Int) -> String {
        switch status {
        case 200: return "OK"
        case 400: return "Bad Request"
        case 401: return "Unauthorized"
        case 403: return "Forbidden"
        case 404: return "Not Found"
        case 411: return "Length Required"
        case 413: return "Payload Too Large"
        case 503: return "Service Unavailable"
        default: return "Error"
        }
    }

    public static func httpResponseBytes(_ resp: HttpResponse) -> Data {
        let bodyBytes = Data(resp.body.utf8)
        var head = "HTTP/1.1 \(resp.status) \(reason(resp.status))\r\n"
        head += "Content-Type: \(resp.contentType)\r\n"
        head += "Content-Length: \(bodyBytes.count)\r\n"
        head += "Cache-Control: no-store\r\n"
        head += "Connection: close\r\n\r\n"
        var data = Data(head.utf8)
        data.append(bodyBytes)
        return data
    }

    // ---- WebSocket ----------------------------------------------------------

    /// Sec-WebSocket-Accept（RFC 6455 1.3 节）：SHA1(key + GUID) 的 base64。
    public static func wsAccept(key: String) -> String {
        let guid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        let source = Data((key + guid).utf8)
        var digest = [UInt8](repeating: 0, count: Int(CC_SHA1_DIGEST_LENGTH))
        source.withUnsafeBytes { buffer in
            _ = CC_SHA1(buffer.baseAddress, CC_LONG(source.count), &digest)
        }
        return Data(digest).base64EncodedString()
    }

    /// 编一帧服务端文本帧（不带掩码）。
    public static func encodeTextFrame(_ text: String) -> Data {
        encodeFrame(opcode: 0x1, payload: Data(text.utf8))
    }

    public static func encodeFrame(opcode: UInt8, payload: Data) -> Data {
        var frame = Data()
        frame.append(0x80 | opcode)
        let length = payload.count
        if length < 126 {
            frame.append(UInt8(length))
        } else if length <= 0xFFFF {
            frame.append(126)
            frame.append(UInt8((length >> 8) & 0xFF))
            frame.append(UInt8(length & 0xFF))
        } else {
            frame.append(127)
            var big = UInt64(length)
            for _ in 0..<8 {
                frame.append(UInt8(big >> 56))
                big <<= 8
            }
        }
        frame.append(payload)
        return frame
    }

    public static func queryParam(target: String, name: String) -> String? {
        guard let qIndex = target.firstIndex(of: "?") else { return nil }
        let query = target[target.index(after: qIndex)...]
        for pair in query.split(separator: "&", omittingEmptySubsequences: false) {
            let key = pair.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? ""
            if key == name {
                let raw = pair.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false).dropFirst().first.map(String.init) ?? ""
                return raw.removingPercentEncoding ?? raw
            }
        }
        return nil
    }
}

// MARK: - 单连接状态机

/// 一条 TCP 连接：先按 HTTP 读请求头+体，路由后关连接；WS 升级
/// 成功则转帧模式长连接。读写都在连接自己的串行队列上，帧发送
/// 靠 NWConnection 的异步 send，不阻塞账本锁。
private final class Connection {
    private let server: LanPlatformServer
    private let connection: NWConnection
    private let queue = DispatchQueue(label: "lan-platform-connection")
    private var buffer = Data()
    private var upgraded = false
    private var closed = false
    private var listenerToken: LocalChannelListenerToken?
    private var subscribedService: LocalChannelService?

    init(server: LanPlatformServer, connection: NWConnection) {
        self.server = server
        self.connection = connection
    }

    func start() {
        connection.stateUpdateHandler = { [weak self] state in
            if case .cancelled = state { self?.cleanup() }
            if case .failed = state { self?.cleanup() }
        }
        connection.start(queue: queue)
        receiveMore()
    }

    func close() {
        queue.async { [weak self] in
            self?.connection.cancel()
        }
    }

    private func cleanup() {
        if closed { return }
        closed = true
        if let token = listenerToken, let service = subscribedService {
            service.removeMessageListener(token)
        }
        listenerToken = nil
        subscribedService = nil
        server.connectionClosed(self)
    }

    private func receiveMore() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let data, !data.isEmpty {
                self.buffer.append(data)
                self.processBuffer()
            }
            if isComplete || error != nil {
                self.connection.cancel()
                return
            }
            if !self.closed {
                self.receiveMore()
            }
        }
    }

    private func processBuffer() {
        if upgraded {
            processFrames()
            return
        }
        guard let headerEnd = Self.findHeaderEnd(buffer) else {
            if buffer.count > LanPlatformServer.maxHeaderBytesLimit {
                connection.cancel()
            }
            return
        }
        let headerData = buffer.prefix(headerEnd)
        guard let headerText = String(data: headerData, encoding: .isoLatin1) else {
            connection.cancel()
            return
        }
        let lines = headerText.components(separatedBy: "\r\n").filter { !$0.isEmpty }
        guard let first = lines.first else { connection.cancel(); return }
        let parts = first.split(separator: " ", omittingEmptySubsequences: false)
        guard parts.count >= 3 else { connection.cancel(); return }
        var headers: [String: String] = [:]
        for line in lines.dropFirst().prefix(LanPlatformServer.maxHeadersLimit) {
            guard let idx = line.firstIndex(of: ":") else { continue }
            let name = line[..<idx].trimmingCharacters(in: .whitespaces).lowercased()
            let value = line[line.index(after: idx)...].trimmingCharacters(in: .whitespaces)
            headers[name] = value
        }
        let method = parts[0].uppercased()
        let target = String(parts[1])
        let rest = buffer.suffix(from: headerEnd)

        if method == "GET",
           target.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) == LanPlatformServer.wsPathConst,
           headers["upgrade"]?.caseInsensitiveCompare("websocket") == .orderedSame {
            buffer = Data(rest)
            serveWs(method: method, target: target, headers: headers)
            return
        }

        // 普通 HTTP：按 Content-Length 等齐请求体再路由。
        if headers["transfer-encoding"] != nil {
            respond(LanPlatformServer.jsonError(411, "length required"))
            return
        }
        var body: Data? = nil
        if let lengthHeader = headers["content-length"] {
            guard let length = Int(lengthHeader), length >= 0 else {
                connection.cancel()
                return
            }
            if length > LanPlatformServer.maxBodyBytesLimit {
                respond(LanPlatformServer.jsonError(413, "payload too large"))
                return
            }
            if rest.count < length { return } // 等后续数据
            body = Data(rest.prefix(length))
        }
        let request = LanPlatformServer.HttpRequest(method: method, target: target, headers: headers, body: body)
        respond(server.handle(request))
    }

    private func respond(_ resp: LanPlatformServer.HttpResponse) {
        let bytes = LanPlatformServer.httpResponseBytes(resp)
        connection.send(content: bytes, completion: .contentProcessed { [weak self] _ in
            self?.connection.cancel()
        })
    }

    // ---- WS 升级与帧循环 -----------------------------------------------------

    private func serveWs(method: String, target: String, headers: [String: String]) {
        func refuse(_ status: Int, _ message: String) {
            respond(LanPlatformServer.jsonError(status, message))
        }
        guard let entry = server.authenticate(headers: headers) else {
            refuse(401, "unauthorized")
            return
        }
        guard let service = server.service() else {
            refuse(503, "本地平台服务未启动")
            return
        }
        service.registerAgent(agentId: entry.agentId, agentName: entry.agentName)
        guard let channelId = LanPlatformServer.queryParam(target: target, name: "channel_id"),
              !channelId.isEmpty else {
            refuse(400, "missing channel_id")
            return
        }
        if !service.channelExists(channelId) {
            refuse(404, "channel_not_found")
            return
        }
        if !service.isChannelMember(channelId: channelId, agentId: entry.agentId) {
            // 与远程平台成员门禁同口径：非成员订阅直接拒，不给任何消息。
            refuse(403, "not_a_channel_member")
            return
        }
        guard let key = headers["sec-websocket-key"], !key.isEmpty else {
            refuse(400, "missing sec-websocket-key")
            return
        }
        let accept = LanPlatformServer.wsAccept(key: key)
        let head = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: \(accept)\r\n\r\n"
        connection.send(content: Data(head.utf8), completion: .contentProcessed { _ in })
        upgraded = true
        subscribedService = service
        listenerToken = service.addMessageListener(channelId: channelId) { [weak self] event in
            guard let self, !self.closed else { return }
            guard let data = try? JSONSerialization.data(withJSONObject: event),
                  let text = String(data: data, encoding: .utf8) else { return }
            let frame = LanPlatformServer.encodeTextFrame(text)
            self.queue.async {
                if !self.closed {
                    self.connection.send(content: frame, completion: .contentProcessed { _ in })
                }
            }
        }
        processFrames()
    }

    private func processFrames() {
        while true {
            guard let frame = nextFrame() else { return }
            switch frame.opcode {
            case 0x9: // ping -> pong
                let pong = LanPlatformServer.encodeFrame(opcode: 0xA, payload: frame.payload)
                connection.send(content: pong, completion: .contentProcessed { _ in })
            case 0x8: // close
                let echo = LanPlatformServer.encodeFrame(opcode: 0x8, payload: frame.payload)
                connection.send(content: echo, completion: .contentProcessed { [weak self] _ in
                    self?.connection.cancel()
                })
                return
            default:
                // 频道协议里客户端不发数据帧；收到也忽略。
                break
            }
        }
    }

    private struct ClientFrame {
        let opcode: UInt8
        let payload: Data
    }

    /// 从缓冲区取一帧完整的客户端帧；不完整返回 nil 等后续数据，
    /// 协议损坏（未掩码/超长）直接关连接并返回 nil。
    private func nextFrame() -> ClientFrame? {
        if buffer.count < 2 { return nil }
        let b0 = buffer[buffer.startIndex]
        let b1 = buffer[buffer.startIndex + 1]
        let opcode = b0 & 0x0F
        let masked = (b1 & 0x80) != 0
        if !masked {
            connection.cancel()
            return nil
        }
        var length = Int(b1 & 0x7F)
        var offset = 2
        if length == 126 {
            if buffer.count < offset + 2 { return nil }
            length = Int(buffer[buffer.startIndex + offset]) << 8 | Int(buffer[buffer.startIndex + offset + 1])
            offset += 2
        } else if length == 127 {
            if buffer.count < offset + 8 { return nil }
            var big: UInt64 = 0
            for i in 0..<8 {
                big = (big << 8) | UInt64(buffer[buffer.startIndex + offset + i])
            }
            if big > UInt64(LanPlatformServer.maxWsPayloadBytesLimit) {
                connection.cancel()
                return nil
            }
            length = Int(big)
            offset += 8
        }
        if length > LanPlatformServer.maxWsPayloadBytesLimit {
            connection.cancel()
            return nil
        }
        if buffer.count < offset + 4 + length { return nil }
        var maskKey = [UInt8](repeating: 0, count: 4)
        for i in 0..<4 { maskKey[i] = buffer[buffer.startIndex + offset + i] }
        offset += 4
        var payload = Data()
        payload.reserveCapacity(length)
        for i in 0..<length {
            payload.append(buffer[buffer.startIndex + offset + i] ^ maskKey[i % 4])
        }
        buffer = Data(buffer.suffix(from: buffer.startIndex + offset + length))
        return ClientFrame(opcode: opcode, payload: payload)
    }

    private static func findHeaderEnd(_ data: Data) -> Int? {
        let terminator: [UInt8] = [0x0D, 0x0A, 0x0D, 0x0A]
        if data.count < 4 { return nil }
        var i = data.startIndex
        let end = data.endIndex - 3
        while i < end {
            if data[i] == terminator[0], data[i + 1] == terminator[1],
               data[i + 2] == terminator[2], data[i + 3] == terminator[3] {
                return i + 4
            }
            i += 1
        }
        return nil
    }
}

// MARK: - 连接层共用常量（与类内私有常量同值，供同文件连接状态机引用）

extension LanPlatformServer {
    static var wsPathConst: String { "/api/open/v1/channel/ws" }
    static var maxHeaderBytesLimit: Int { 16 * 1024 }
    static var maxHeadersLimit: Int { 64 }
    static var maxBodyBytesLimit: Int { 1024 * 1024 }
    static var maxWsPayloadBytesLimit: Int { 1024 * 1024 }
}
