import Foundation
import XCTest
@testable import WhereverCodeAgent

final class ChannelCollaborationTests: XCTestCase {
    func testPlatformRosterKeepsMemberIDsAndRoles() async throws {
        let transport = makeTransport { name, arguments in
            XCTAssertEqual(name, "agent_channel_list")
            XCTAssertTrue(arguments.isEmpty)
            return (200, #"{"channels":[{"id":"ch-team","participants":[{"agent_id":"owner-1","agent_name":"主管","role":"owner","is_self":true,"is_agent":false},{"agent_id":"worker-2","agent_name":"研发","role":"member","is_agent":true}]},{"id":"ch-empty","participants":[]},{"id":"ch-legacy","participants":[{"agent_id":"legacy-3","agent_name":"旧成员"}]}]}"#)
        }
        defer { StubURLProtocol.handler = nil }

        let channels = try await transport.listChannels()
        XCTAssertEqual(channels.map(\.id), ["ch-team", "ch-empty", "ch-legacy"])
        let team = try XCTUnwrap(channels.first)
        XCTAssertEqual(team.participants, ["主管", "研发"])
        XCTAssertEqual(team.participantDetails.map(\.id), ["owner-1", "worker-2"])
        XCTAssertEqual(team.participantDetails.filter(\.canMentionAll).map(\.id), ["owner-1"])
        XCTAssertEqual(team.participantDetails.filter(\.isSelf).map(\.id), ["owner-1"])
        XCTAssertTrue(channels.first(where: { $0.id == "ch-empty" })?.participantDetails.isEmpty == true)
        XCTAssertFalse(channels.last?.participantDetails.first?.canMentionAll ?? true)
    }

    func testDiscussionPostAndReceiptUseTheSelectedChannel() async throws {
        let transport = makeTransport { name, arguments in
            XCTAssertEqual(arguments["channel_id"] as? String, "ch-team")
            switch name {
            case "agent_channel_post":
                XCTAssertEqual(arguments["text"] as? String, "@研发 请查看任务回执")
                XCTAssertEqual(arguments["reply_to"] as? String, "m-parent")
                XCTAssertEqual(arguments["effect_key"] as? String, "same-effect")
                return (200, #"{"message":{"id":"m-self"}}"#)
            case "agent_channel_messages":
                XCTAssertEqual(arguments["after_id"] as? String, "m-self")
                return (200, #"{"messages":[{"id":"m-receipt","agent_id":"worker-2","agent_name":"研发","is_agent":true,"text":"平台任务回执","reply_to":"m-self"}]}"#)
            default:
                XCTFail("Unexpected tool: \(name)")
                return (400, "{}")
            }
        }
        defer { StubURLProtocol.handler = nil }

        let posted = try await transport.postMessageEx(
            channelID: "ch-team", text: "@研发 请查看任务回执", replyTo: "m-parent", effectKey: "same-effect")
        XCTAssertEqual(posted, "m-self")
        let receipts = try await transport.pollMessages(channelID: "ch-team", afterID: posted)
        XCTAssertEqual(receipts.map(\.id), ["m-receipt"])
        XCTAssertEqual(receipts.first?.agentId, "worker-2")
        XCTAssertEqual(receipts.first?.isAgent, true)
        XCTAssertEqual(receipts.first?.replyTo, posted)
    }

    func testForwardInterceptsMentionAllWithoutTargetPermission() async {
        var posts = 0
        let blocked = await ChannelExtras.forwardMessage(
            text: "请 @所有人 看一下", canMentionAllInTarget: false) {
            posts += 1
            return "should-not-post"
        }
        guard case .failure(.blocked(let reason)) = blocked else {
            return XCTFail("expected mention-all forward to be blocked, got \(blocked)")
        }
        XCTAssertEqual(posts, 0)
        XCTAssertTrue(reason.contains("未发送"))
        XCTAssertTrue(reason.contains("@所有人"))

        let blockedAll = await ChannelExtras.forwardMessage(
            text: "@all ping", canMentionAllInTarget: false) {
            posts += 1
            return "should-not-post"
        }
        guard case .failure(.blocked(_)) = blockedAll else {
            return XCTFail("expected @all forward to be blocked")
        }
        XCTAssertEqual(posts, 0)

        let allowed = await ChannelExtras.forwardMessage(
            text: "普通讨论", canMentionAllInTarget: false) { "m-plain" }
        guard case .success("m-plain") = allowed else {
            return XCTFail("plain forward must still post")
        }
        let permitted = await ChannelExtras.forwardMessage(
            text: "@所有人 协调者转发", canMentionAllInTarget: true) {
            posts += 1
            return "m-ok"
        }
        guard case .success("m-ok") = permitted else {
            return XCTFail("mention-all forward with target permission must post")
        }
        XCTAssertEqual(posts, 1)
    }

    func testCoordinatorIsRemovableWhileOwnerAndSelfStayProtected() {
        let coordinator = ChannelParticipant(
            id: "coord-1", name: "协调", role: "coordinator", isAgent: true, isSelf: false)
        let owner = ChannelParticipant(
            id: "owner-1", name: "群主", role: "owner", isAgent: false, isSelf: false)
        XCTAssertTrue(coordinator.canMentionAll)
        XCTAssertTrue(owner.canMentionAll)
        XCTAssertFalse(ChannelExtras.isProtectedChannelMember(
            isSelf: coordinator.isSelf, role: coordinator.role))
        XCTAssertFalse(ChannelExtras.isProtectedChannelMember(isSelf: false, role: "协调者"))
        XCTAssertTrue(ChannelExtras.isProtectedChannelMember(
            isSelf: owner.isSelf, role: owner.role))
        XCTAssertTrue(ChannelExtras.isProtectedChannelMember(isSelf: false, role: "项目主管"))
        XCTAssertTrue(ChannelExtras.isProtectedChannelMember(isSelf: false, role: "所有者"))
        XCTAssertTrue(ChannelExtras.isProtectedChannelMember(isSelf: true, role: "coordinator"))
        XCTAssertTrue(ChannelExtras.isProtectedChannelMember(isSelf: true, role: "member"))
        XCTAssertFalse(ChannelExtras.isProtectedChannelMember(isSelf: false, role: "member"))
        XCTAssertFalse(ChannelExtras.isProtectedChannelMember(isSelf: false, role: nil))
    }

    func testPlatformIsAgentIsKeptForSelfWhenExplicit() {
        XCTAssertEqual(
            ChannelExtras.displayedIsAgent(platformIsAgent: true, senderIsSelf: true),
            true)
        XCTAssertEqual(
            ChannelExtras.displayedIsAgent(platformIsAgent: false, senderIsSelf: true, rosterIsAgent: true),
            false)
        XCTAssertEqual(
            ChannelExtras.displayedIsAgent(platformIsAgent: nil, senderIsSelf: true, rosterIsAgent: true),
            false)
        XCTAssertEqual(
            ChannelExtras.displayedIsAgent(platformIsAgent: nil, senderIsSelf: false, rosterIsAgent: true),
            true)
        XCTAssertEqual(
            ChannelExtras.displayedIsAgent(platformIsAgent: true, senderIsSelf: false, rosterIsAgent: false),
            true)

        var messages: [(senderID: String, isAgent: Bool, platformIsAgent: Bool?)] = [
            ("self-1", true, true),
            ("self-1", true, nil),
            ("self-1", false, false),
            ("other-2", true, nil),
        ]
        let selfSenderID = "self-1"
        for index in messages.indices where messages[index].senderID == selfSenderID {
            messages[index].isAgent = ChannelExtras.displayedIsAgent(
                platformIsAgent: messages[index].platformIsAgent, senderIsSelf: true)
        }
        XCTAssertEqual(messages.map(\.isAgent), [true, false, false, true])
    }

    func testEmptyPostReceiptKeepsScheduledItemAndEffectKey() async throws {
        let transport = makeTransport { name, arguments in
            XCTAssertEqual(name, "agent_channel_post")
            XCTAssertEqual(arguments["effect_key"] as? String, "ck_keep")
            return (200, #"{"ok":true}"#)
        }
        defer { StubURLProtocol.handler = nil }

        let posted = try await transport.postMessageEx(
            channelID: "ch-team", text: "稍后发", effectKey: "ck_keep")
        XCTAssertEqual(posted, "")
        let item = ScheduledChannelPost(
            id: "s1", channelId: "ch-team", text: "稍后发", dueAtSeconds: 10,
            effectKey: "ck_keep", attachmentRef: "drive://a", replyToId: "m0")
        let kept = ChannelExtras.scheduledQueueAfterFlush(
            items: [item], itemID: item.id, postedID: posted)
        XCTAssertEqual(kept, [item])
        XCTAssertEqual(kept.first?.effectKey, "ck_keep")
        let removed = ChannelExtras.scheduledQueueAfterFlush(
            items: [item], itemID: item.id, postedID: "m-real")
        XCTAssertTrue(removed.isEmpty)
    }

    private func makeTransport(
        respond: @escaping (String, [String: Any]) -> (Int, String)
    ) -> ChannelTransport {
        let endpoint = URL(string: "https://channel-test.invalid/mcp")!
        StubURLProtocol.handler = { request in
            let body = Self.requestData(request)
            let decoded = body.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
            let params = decoded?["params"] as? [String: Any]
            XCTAssertEqual(decoded?["method"] as? String, "tools/call")
            let (status, result) = respond(
                params?["name"] as? String ?? "", params?["arguments"] as? [String: Any] ?? [:])
            let envelope: [String: Any] = [
                "jsonrpc": "2.0",
                "result": ["content": [["type": "text", "text": result]], "isError": false],
            ]
            return (
                HTTPURLResponse(url: endpoint, statusCode: status, httpVersion: nil, headerFields: nil)!,
                try! JSONSerialization.data(withJSONObject: envelope)
            )
        }
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubURLProtocol.self]
        return ChannelTransport(client: McpClient(
            endpoint: endpoint, tokenProvider: { "test-token" }, session: URLSession(configuration: config)))
    }

    private static func requestData(_ request: URLRequest) -> Data? {
        if let data = request.httpBody { return data }
        guard let stream = request.httpBodyStream else { return nil }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 4096)
        while stream.hasBytesAvailable {
            let count = stream.read(&buffer, maxLength: buffer.count)
            if count <= 0 { break }
            data.append(contentsOf: buffer.prefix(count))
        }
        return data
    }
}
