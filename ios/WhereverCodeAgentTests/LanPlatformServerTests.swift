// WhereverCode Agent — iOS tests
//
// 内网平台服务（频道三层二期·内网层）：与安卓 LanPlatformServerTest
// 同一验收清单逐条对应——健康检查免鉴权、无令牌 401、成员发读往返、
// 非成员门禁、WS 非成员 403、WS 推送到达、Accept 向量。真套接字
// loopback 实测（NWListener 临时端口），断言口径与安卓一致，不放宽。

import Foundation
import Network
import XCTest
@testable import WhereverCodeAgent

final class LanPlatformServerTests: XCTestCase {

    private var service: LocalChannelService!
    private var registry: LanTokenRegistry!
    private var server: LanPlatformServer!
    private var port: UInt16 = 0

    override func setUp() async throws {
        service = LocalChannelService(persistence: InMemoryLocalChannelPersistence())
        registry = LanTokenRegistry()
        let svc = service!
        server = LanPlatformServer(serviceProvider: { svc }, registry: registry, port: 0)
        XCTAssertTrue(server.start())
        // 等监听就绪（临时端口分配完成）。
        for _ in 0..<100 {
            if server.port != 0 { break }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        port = server.port
        XCTAssertNotEqual(port, 0)
    }

    override func tearDown() async throws {
        server.stop()
    }

    private var baseURL: String { "http://127.0.0.1:\(port)" }

    private func http(_ method: String, _ path: String, token: String? = nil, body: String? = nil) async throws -> (Int, String) {
        var request = URLRequest(url: URL(string: baseURL + path)!)
        request.httpMethod = method
        if let token {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        if let body {
            request.httpBody = Data(body.utf8)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, response) = try await URLSession.shared.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? -1
        return (status, String(data: data, encoding: .utf8) ?? "")
    }

    private func mcp(_ token: String, _ name: String, _ arguments: String = "{}") async throws -> (Int, String) {
        try await http("POST", "/api/open/v1/mcp", token: token,
                       body: "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"\(name)\",\"arguments\":\(arguments)}}")
    }

    private func innerText(_ envelope: String) -> String {
        guard let data = envelope.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let result = root["result"] as? [String: Any],
              let content = result["content"] as? [[String: Any]],
              let text = content.first?["text"] as? String else { return "" }
        return text
    }

    private func isError(_ envelope: String) -> Bool {
        guard let data = envelope.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let result = root["result"] as? [String: Any] else { return false }
        return (result["isError"] as? Bool) ?? false
    }

    func testWsAcceptMatchesRfc6455Vector() {
        XCTAssertEqual(
            LanPlatformServer.wsAccept(key: "dGhlIHNhbXBsZSBub25jZQ=="),
            "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="
        )
    }

    func testHealthIsOpenWithoutToken() async throws {
        let (status, text) = try await http("GET", "/api/open/v1/health")
        XCTAssertEqual(status, 200)
        XCTAssertTrue(text.contains("\"service\":\"lan-platform\""), text)
    }

    func testMcpWithoutTokenIs401() async throws {
        let (status, text) = try await mcp("", "agent_channel_list")
        XCTAssertEqual(status, 401)
        XCTAssertTrue(text.contains("unauthorized"), text)
        let (bogusStatus, _) = try await http("POST", "/api/open/v1/mcp", token: "bogus",
                                              body: "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"agent_channel_list\",\"arguments\":{}}}")
        XCTAssertEqual(bogusStatus, 401)
    }

    func testMemberListJoinPostReadRoundtrip() async throws {
        let entry = registry.issue(agentName: "Mac")
        let channelId = service.defaultChannelId()

        let (listStatus, listBody) = try await mcp(entry.token, "agent_channel_list")
        XCTAssertEqual(listStatus, 200)
        XCTAssertTrue(innerText(listBody).contains(channelId), listBody)

        let (joinStatus, joinBody) = try await mcp(entry.token, "agent_channel_join", "{\"channel_id\":\"\(channelId)\"}")
        XCTAssertEqual(joinStatus, 200)
        XCTAssertFalse(isError(joinBody), joinBody)

        let (postStatus, postBody) = try await mcp(entry.token, "agent_channel_post", "{\"channel_id\":\"\(channelId)\",\"text\":\"lan-ios-hello\"}")
        XCTAssertEqual(postStatus, 200)
        XCTAssertFalse(isError(postBody), postBody)
        XCTAssertTrue(innerText(postBody).contains("lan-ios-hello"), postBody)

        let (_, readBody) = try await mcp(entry.token, "agent_channel_messages", "{\"channel_id\":\"\(channelId)\",\"limit\":10}")
        XCTAssertTrue(innerText(readBody).contains("lan-ios-hello"), readBody)
    }

    func testNonMemberReadIsRefusedByLedger() async throws {
        let entry = registry.issue(agentName: "Guest")
        let channelId = service.defaultChannelId()
        let (status, body) = try await mcp(entry.token, "agent_channel_messages", "{\"channel_id\":\"\(channelId)\"}")
        XCTAssertEqual(status, 200)
        XCTAssertTrue(isError(body), body)
        XCTAssertTrue(innerText(body).contains("不是该频道成员"), body)
    }

    // ---- WS（原始 NWConnection 握手，与安卓裸套接字口径一致） ---------------

    private final class WsProbe {
        let connection: NWConnection
        var received = Data()
        var closed = false
        init(connection: NWConnection) { self.connection = connection }
    }

    private func wsConnect(channelId: String, token: String) -> WsProbe {
        let connection = NWConnection(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: port)!, using: .tcp)
        let probe = WsProbe(connection: connection)
        connection.start(queue: DispatchQueue(label: "ws-probe"))
        let request = "GET /api/open/v1/channel/ws?channel_id=\(channelId) HTTP/1.1\r\n" +
            "Host: 127.0.0.1:\(port)\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
            "Sec-WebSocket-Version: 13\r\n" +
            "Authorization: Bearer \(token)\r\n\r\n"
        connection.send(content: Data(request.utf8), completion: .contentProcessed { _ in })
        func pump() {
            connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { data, _, isComplete, error in
                if let data, !data.isEmpty { probe.received.append(data) }
                if isComplete || error != nil {
                    probe.closed = true
                    return
                }
                pump()
            }
        }
        pump()
        return probe
    }

    /// 在原始字节流里找针（帧头是二进制，整段转 UTF-8 字符串会失败，
    /// 必须按字节搜——这是本测试第一版的真 bug，推送其实到了）。
    private func receivedContains(_ probe: WsProbe, _ needle: String) -> Bool {
        probe.received.range(of: Data(needle.utf8)) != nil
    }

    private func receivedText(_ probe: WsProbe) -> String {
        String(decoding: probe.received, as: UTF8.self)
    }

    private func waitFor(_ probe: WsProbe, _ needle: String, timeoutSeconds: Double = 5) async -> Bool {
        let deadline = Date().addingTimeInterval(timeoutSeconds)
        while Date() < deadline {
            if receivedContains(probe, needle) { return true }
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
        return receivedContains(probe, needle)
    }

    func testWsNonMemberIs403AndGetsNoMessages() async throws {
        let entry = registry.issue(agentName: "Guest")
        let channelId = service.defaultChannelId()
        let probe = wsConnect(channelId: channelId, token: entry.token)
        let refused = await waitFor(probe, "not_a_channel_member", timeoutSeconds: 3)
        XCTAssertTrue(refused, receivedText(probe))
        XCTAssertTrue(receivedContains(probe, "403"), receivedText(probe))
        probe.connection.cancel()
    }

    func testWsMemberReceivesPushedMessage() async throws {
        let entry = registry.issue(agentName: "Mac")
        let channelId = service.defaultChannelId()
        _ = try await mcp(entry.token, "agent_channel_join", "{\"channel_id\":\"\(channelId)\"}")
        let probe = wsConnect(channelId: channelId, token: entry.token)
        let upgraded = await waitFor(probe, "101", timeoutSeconds: 3)
        XCTAssertTrue(upgraded, receivedText(probe))
        XCTAssertTrue(receivedContains(probe, "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="), receivedText(probe))

        _ = try await mcp(entry.token, "agent_channel_post", "{\"channel_id\":\"\(channelId)\",\"text\":\"lan-ios-push\"}")
        let pushed = await waitFor(probe, "lan-ios-push", timeoutSeconds: 5)
        XCTAssertTrue(pushed, receivedText(probe))
        probe.connection.cancel()
    }
}
