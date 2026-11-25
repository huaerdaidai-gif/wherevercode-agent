// WhereverCode Agent — iOS tests
//
// 本地频道账本（频道三层一期·本地层）：与安卓
// LocalChannelServiceTest 同一验收清单逐条对应——取消种子与空壳清理、
// 发读游标、成员门禁、私聊规则、角色保护、二/三期操作（置顶/
// 反应/撤回/已读）、effect_key 幂等、落盘恢复、进程内 MCP 信封
// 与本地推送总线。断言口径与安卓一致，不放宽。

import Foundation
import XCTest
@testable import WhereverCodeAgent

final class LocalChannelServiceTests: XCTestCase {

    private final class ClockBox {
        var now = 1_000.0
    }

    private func newService(
        persistence: LocalChannelPersistence = InMemoryLocalChannelPersistence(),
        clockBox: ClockBox = ClockBox()
    ) -> LocalChannelService {
        LocalChannelService(persistence: persistence) { clockBox.now }
    }

    private func call(
        _ service: LocalChannelService,
        _ tool: String,
        _ args: [String: Any] = [:],
        callerId: String = LocalPlatform.appAgentID,
        callerName: String = LocalPlatform.appAgentName
    ) throws -> [String: Any] {
        try service.dispatch(tool: tool, args: args, callerId: callerId, callerName: callerName)
    }

    private func postArgs(_ channelId: String, _ text: String) -> [String: Any] {
        ["channel_id": channelId, "text": text]
    }

    /// 无种子时代的频道夹具：显式建一个频道（本机 App 为 owner），返回其 id。
    private func makeChannel(_ service: LocalChannelService, name: String = "测试频道") throws -> String {
        let created = try call(service, "agent_channel_create", ["name": name])
        return str(created, "id")
    }

    private func messagesOf(_ result: [String: Any]) -> [[String: Any]] {
        (result["messages"] as? [[String: Any]]) ?? []
    }

    private func str(_ dict: [String: Any], _ key: String) -> String {
        (dict[key] as? String) ?? ""
    }

    private func expectLocalError(
        _ messagePart: String,
        file: StaticString = #filePath,
        line: UInt = #line,
        _ body: () throws -> [String: Any]
    ) {
        do {
            _ = try body()
            XCTFail("本应被本地平台拒绝", file: file, line: line)
        } catch let error as LocalServiceError {
            XCTAssertTrue(
                error.message.contains(messagePart),
                "错误文案应含「\(messagePart)」，实际：\(error.message)",
                file: file, line: line)
        } catch {
            XCTFail("应抛 LocalServiceError，实际：\(error)", file: file, line: line)
        }
    }

    func testFreshServiceStartsEmptyWithoutSeededChannel() throws {
        let service = newService()
        let result = try call(service, "agent_channel_list")
        let channels = (result["channels"] as? [[String: Any]]) ?? []
        XCTAssertEqual(channels.count, 0)
        XCTAssertEqual(service.defaultChannelId(), "")
    }

    func testLegacySeededEmptyChannelIsCleanedUpOnce() throws {
        let persistence = InMemoryLocalChannelPersistence()
        persistence.stored = Self.legacySeededSnapshot(messageText: nil)
        let service = newService(persistence: persistence)
        let result = try call(service, "agent_channel_list")
        let channels = (result["channels"] as? [[String: Any]]) ?? []
        XCTAssertEqual(channels.count, 0)
        XCTAssertEqual(service.defaultChannelId(), "")
    }

    func testLegacySeededChannelWithMessagesIsKept() throws {
        let persistence = InMemoryLocalChannelPersistence()
        persistence.stored = Self.legacySeededSnapshot(messageText: "种子里的真消息")
        let service = newService(persistence: persistence)
        let result = try call(service, "agent_channel_list")
        let channels = (result["channels"] as? [[String: Any]]) ?? []
        XCTAssertEqual(channels.count, 1)
        XCTAssertEqual(str(channels[0], "name"), "本地频道")
        let listed = messagesOf(try call(service, "agent_channel_messages", [
            "channel_id": str(channels[0], "id"),
        ]))
        XCTAssertEqual(listed.map { str($0, "text") }, ["种子里的真消息"])
    }

    func testCreateChannelDedupesDuplicateNames() throws {
        let service = newService()
        let first = try call(service, "agent_channel_create", ["name": "本地频道"])
        let second = try call(service, "agent_channel_create", ["name": "本地频道"])
        let third = try call(service, "agent_channel_create", ["name": "本地频道"])
        XCTAssertEqual(str(first, "name"), "本地频道")
        XCTAssertEqual(str(second, "name"), "本地频道 2")
        XCTAssertEqual(str(third, "name"), "本地频道 3")
    }

    /// 旧版本（种子时代）落盘快照的最小复刻：默认频道 + 本机 App owner + 可选一条消息。
    private static func legacySeededSnapshot(messageText: String?) -> String {
        let channelId = "seededchan000000000000000000001"
        var messages: [[String: Any]] = []
        if let messageText {
            messages.append([
                "id": "seededmsg0000000000000000000001",
                "channel_id": channelId,
                "agent_id": LocalPlatform.appAgentID,
                "agent_name": LocalPlatform.appAgentName,
                "text": messageText,
                "created_at": 1_000.0,
                "seq": 1,
                "mentions_all": false,
                "pinned": false,
                "recalled": false,
            ])
        }
        let root: [String: Any] = [
            "version": 1,
            "default_channel_id": channelId,
            "seq": 1,
            "agents": [["id": LocalPlatform.appAgentID, "name": LocalPlatform.appAgentName, "roles": [String]()]],
            "channels": [[
                "id": channelId, "name": "本地频道", "kind": "channel",
                "archived": false, "history_visible": true, "created_at": 1_000.0,
            ]],
            "members": [[
                "channel_id": channelId, "agent_id": LocalPlatform.appAgentID,
                "agent_name": LocalPlatform.appAgentName, "role": "owner", "joined_at": 1_000.0,
            ]],
            "messages": messages,
            "reactions": [[String: Any]](),
            "read_markers": [[String: Any]](),
            "webhooks": [[String: Any]](),
            "effect_keys": [[String: Any]](),
        ]
        let data = try! JSONSerialization.data(withJSONObject: root)
        return String(data: data, encoding: .utf8)!
    }

    func testPostAndReadRoundTripKeepsPlatformMessageShape() throws {
        let service = newService()
        let channelId = try makeChannel(service)
        let posted = try call(service, "agent_channel_post", postArgs(channelId, "第一条"))
        XCTAssertEqual(str(posted, "id").count, 32)
        XCTAssertEqual(str(posted, "text"), "第一条")
        XCTAssertEqual(str(posted, "agent_id"), LocalPlatform.appAgentID)
        var replyArgs = postArgs(channelId, "回复")
        replyArgs["reply_to"] = str(posted, "id")
        replyArgs["attachment_ref"] = "drive://file/abc.png"
        let reply = try call(service, "agent_channel_post", replyArgs)
        XCTAssertEqual(str(reply, "reply_to"), str(posted, "id"))
        XCTAssertEqual(str(reply, "attachment_ref"), "drive://file/abc.png")
        let listed = messagesOf(try call(service, "agent_channel_messages", [
            "channel_id": channelId, "limit": 50,
        ]))
        XCTAssertEqual(listed.map { str($0, "text") }, ["第一条", "回复"])
    }

    func testNonMemberCannotReadAndGateMentionsMembership() throws {
        let service = newService()
        service.registerAgent(agentId: "agent-b", agentName: "Agent B")
        let channelId = try makeChannel(service)
        _ = try call(service, "agent_channel_post", postArgs(channelId, "内部消息"))
        expectLocalError("成员") {
            try self.call(service, "agent_channel_messages", ["channel_id": channelId],
                          callerId: "agent-b", callerName: "Agent B")
        }
        // 群频道可自助加入，加入后可读。
        _ = try call(service, "agent_channel_join", ["channel_id": channelId],
                     callerId: "agent-b", callerName: "Agent B")
        let listed = messagesOf(try call(service, "agent_channel_messages",
                                         ["channel_id": channelId],
                                         callerId: "agent-b", callerName: "Agent B"))
        XCTAssertEqual(listed.count, 1)
    }

    func testDmCannotBeSelfJoinedAndOpensOncePerPair() throws {
        let service = newService()
        service.registerAgent(agentId: "agent-b", agentName: "Agent B")
        let dm = try call(service, "agent_channel_open_dm", ["agent_id": "agent-b"])
        XCTAssertEqual(str(dm, "kind"), "dm")
        let again = try call(service, "agent_channel_open_dm", ["agent_id": "agent-b"])
        XCTAssertEqual(str(again, "id"), str(dm, "id"))
        expectLocalError("私聊") {
            try self.call(service, "agent_channel_join", ["channel_id": self.str(dm, "id")],
                          callerId: "agent-c", callerName: "Agent C")
        }
        expectLocalError("不存在") {
            try self.call(service, "agent_channel_open_dm", ["agent_id": "ghost"])
        }
    }

    func testPaginationFollowsAfterAndBeforeCursors() throws {
        let clockBox = ClockBox()
        let service = newService(clockBox: clockBox)
        let channelId = try makeChannel(service)
        var ids: [String] = []
        for i in 1...30 {
            clockBox.now += 1
            let posted = try call(service, "agent_channel_post", postArgs(channelId, "m\(i)"))
            ids.append(str(posted, "id"))
        }
        let firstPage = messagesOf(try call(service, "agent_channel_messages", [
            "channel_id": channelId, "limit": 20,
        ]))
        XCTAssertEqual(firstPage.map { str($0, "id") }, Array(ids[10..<30]))
        let after = messagesOf(try call(service, "agent_channel_messages", [
            "channel_id": channelId, "after_id": ids[9], "limit": 50,
        ]))
        XCTAssertEqual(after.map { str($0, "id") }, Array(ids[10..<30]))
        let before = messagesOf(try call(service, "agent_channel_messages", [
            "channel_id": channelId, "before_id": ids[10], "limit": 50,
        ]))
        XCTAssertEqual(before.map { str($0, "id") }, Array(ids[0..<10]))
    }

    func testEffectKeyDedupesRepost() throws {
        let service = newService()
        let channelId = try makeChannel(service)
        var args = postArgs(channelId, "定时补发")
        args["effect_key"] = "sched-1"
        let first = try call(service, "agent_channel_post", args)
        let second = try call(service, "agent_channel_post", args)
        XCTAssertEqual(str(first, "id"), str(second, "id"))
        let listed = messagesOf(try call(service, "agent_channel_messages", [
            "channel_id": channelId,
        ]))
        XCTAssertEqual(listed.count, 1)
    }

    func testPinReactRecallAndReadersWorkEndToEnd() throws {
        let service = newService()
        service.registerAgent(agentId: "agent-b", agentName: "Agent B")
        let channelId = try makeChannel(service)
        _ = try call(service, "agent_channel_join", ["channel_id": channelId],
                     callerId: "agent-b", callerName: "Agent B")
        let posted = try call(service, "agent_channel_post", postArgs(channelId, "看这条"))
        let messageId = str(posted, "id")
        // 置顶后 pinned_only 只回这一条。
        _ = try call(service, "agent_channel_pin", [
            "channel_id": channelId, "message_id": messageId, "pinned": true,
        ])
        let pinned = messagesOf(try call(service, "agent_channel_messages", [
            "channel_id": channelId, "pinned_only": true,
        ]))
        XCTAssertEqual(pinned.map { str($0, "id") }, [messageId])
        XCTAssertEqual(pinned[0]["pinned"] as? Bool, true)
        // 反应：B 点赞后 count=1、B 视角 mine=true。
        _ = try call(service, "agent_channel_react", [
            "channel_id": channelId, "message_id": messageId, "emoji": "👍", "on": true,
        ], callerId: "agent-b", callerName: "Agent B")
        let afterReact = messagesOf(try call(service, "agent_channel_messages",
                                             ["channel_id": channelId],
                                             callerId: "agent-b", callerName: "Agent B"))
        XCTAssertEqual(afterReact.count, 1)
        let reactions = (afterReact[0]["reactions"] as? [[String: Any]]) ?? []
        XCTAssertEqual(reactions.count, 1)
        XCTAssertEqual((reactions[0]["count"] as? NSNumber)?.intValue, 1)
        XCTAssertEqual(reactions[0]["mine"] as? Bool, true)
        // B 标已读后，readers 里 B 是 read=true。
        _ = try call(service, "agent_channel_messages", [
            "channel_id": channelId, "mark_read": true,
        ], callerId: "agent-b", callerName: "Agent B")
        let readersResult = try call(service, "agent_channel_readers", [
            "channel_id": channelId, "message_id": messageId,
        ])
        let readers = (readersResult["readers"] as? [[String: Any]]) ?? []
        let bRow = readers.first { str($0, "agent_id") == "agent-b" }
        XCTAssertEqual(bRow?["read"] as? Bool, true)
        // 撤回：只能撤自己的；撤回后 text 清空、recalled=true。
        expectLocalError("自己") {
            try self.call(service, "agent_channel_recall", [
                "channel_id": channelId, "message_id": messageId,
            ], callerId: "agent-b", callerName: "Agent B")
        }
        let recalled = try call(service, "agent_channel_recall", [
            "channel_id": channelId, "message_id": messageId,
        ])
        XCTAssertEqual(recalled["recalled"] as? Bool, true)
        XCTAssertEqual(str(recalled, "text"), "")
    }

    func testRoleChangesAreOwnerOnlyAndLastOwnerIsProtected() throws {
        let service = newService()
        service.registerAgent(agentId: "agent-b", agentName: "Agent B")
        let channelId = try makeChannel(service)
        _ = try call(service, "agent_channel_join", ["channel_id": channelId],
                     callerId: "agent-b", callerName: "Agent B")
        expectLocalError("owner") {
            try self.call(service, "agent_channel_set_member_role", [
                "channel_id": channelId, "member_id": "agent-b", "role": "coordinator",
            ], callerId: "agent-b", callerName: "Agent B")
        }
        let promoted = try call(service, "agent_channel_set_member_role", [
            "channel_id": channelId, "member_id": "agent-b", "role": "coordinator",
        ])
        XCTAssertEqual(str(promoted, "role"), "coordinator")
        expectLocalError("owner") {
            try self.call(service, "agent_channel_set_member_role", [
                "channel_id": channelId, "member_id": LocalPlatform.appAgentID, "role": "member",
            ])
        }
    }

    func testMentionsAllOnlyCountsForPrivilegedPosters() throws {
        let service = newService()
        service.registerAgent(agentId: "agent-b", agentName: "Agent B")
        let channelId = try makeChannel(service)
        let ownerPost = try call(service, "agent_channel_post", postArgs(channelId, "@所有人 开会"))
        XCTAssertEqual(ownerPost["mentions_all"] as? Bool, true)
        _ = try call(service, "agent_channel_join", ["channel_id": channelId],
                     callerId: "agent-b", callerName: "Agent B")
        let memberPost = try call(service, "agent_channel_post", postArgs(channelId, "@所有人 跟一下"),
                                  callerId: "agent-b", callerName: "Agent B")
        XCTAssertNil(memberPost["mentions_all"])
    }

    func testHistoryHiddenChannelOnlyShowsMessagesAfterJoin() throws {
        let clockBox = ClockBox()
        let service = newService(clockBox: clockBox)
        service.registerAgent(agentId: "agent-b", agentName: "Agent B")
        let channel = try call(service, "agent_channel_create", ["name": "保密频道"])
        let channelId = str(channel, "id")
        clockBox.now = 2_000
        _ = try call(service, "agent_channel_post", postArgs(channelId, "入群前"))
        _ = try call(service, "agent_channel_set_history_visible", [
            "channel_id": channelId, "visible": false,
        ])
        clockBox.now = 3_000
        _ = try call(service, "agent_channel_add_member", [
            "channel_id": channelId, "agent_id": "agent-b",
        ])
        clockBox.now = 4_000
        _ = try call(service, "agent_channel_post", postArgs(channelId, "入群后"))
        let seen = messagesOf(try call(service, "agent_channel_messages",
                                       ["channel_id": channelId],
                                       callerId: "agent-b", callerName: "Agent B"))
        XCTAssertEqual(seen.map { str($0, "text") }, ["入群后"])
    }

    func testWebhooksNeedPrivilegeAndSecretShowsOnce() throws {
        let service = newService()
        service.registerAgent(agentId: "agent-b", agentName: "Agent B")
        let channelId = try makeChannel(service)
        _ = try call(service, "agent_channel_join", ["channel_id": channelId],
                     callerId: "agent-b", callerName: "Agent B")
        expectLocalError("owner") {
            try self.call(service, "agent_channel_create_webhook", [
                "channel_id": channelId, "name": "WhereverCode App",
            ], callerId: "agent-b", callerName: "Agent B")
        }
        let created = try call(service, "agent_channel_create_webhook", [
            "channel_id": channelId, "name": "WhereverCode App",
        ])
        XCTAssertFalse(str(created, "secret").isEmpty)
        let listed = try call(service, "agent_channel_list_webhooks", ["channel_id": channelId])
        let hooks = (listed["webhooks"] as? [[String: Any]]) ?? []
        XCTAssertEqual(hooks.count, 1)
        XCTAssertNil(hooks[0]["secret"])
        _ = try call(service, "agent_channel_delete_webhook", [
            "channel_id": channelId, "webhook_id": str(created, "webhook_id"),
        ])
        let after = try call(service, "agent_channel_list_webhooks", ["channel_id": channelId])
        XCTAssertEqual(((after["webhooks"] as? [[String: Any]]) ?? []).count, 0)
    }

    func testStateSurvivesRebuildOverSamePersistence() throws {
        let persistence = InMemoryLocalChannelPersistence()
        let first = newService(persistence: persistence)
        let channelId = try makeChannel(first)
        _ = try call(first, "agent_channel_post", postArgs(channelId, "落盘的消息"))
        let second = newService(persistence: persistence)
        let result = try call(second, "agent_channel_list")
        let channels = (result["channels"] as? [[String: Any]]) ?? []
        XCTAssertEqual(channels.map { str($0, "id") }, [channelId])
        let listed = messagesOf(try call(second, "agent_channel_messages", [
            "channel_id": channelId,
        ]))
        XCTAssertEqual(listed.map { str($0, "text") }, ["落盘的消息"])
    }

    func testMcpClientRoutesLocalEnvelopeAndErrors() async throws {
        let service = newService()
        LocalPlatform.installForTest(service)
        let channelId = try makeChannel(service)
        let client = McpClient(
            endpoint: LocalPlatform.localEndpoint,
            tokenProvider: { LocalPlatform.localToken },
            localHandler: LocalPlatform.handleMcpRequest
        )
        let result = try await client.callTool(
            name: "agent_channel_post", arguments: postArgs(channelId, "经信封发的"))
        XCTAssertEqual(result["text"] as? String, "经信封发的")
        // 凭证不对时走 isError 信封（解信封后是带原文案的 serverError）。
        let denied = McpClient(
            endpoint: LocalPlatform.localEndpoint,
            tokenProvider: { "wrong-token" },
            localHandler: LocalPlatform.handleMcpRequest
        )
        do {
            _ = try await denied.callTool(
                name: "agent_channel_messages", arguments: ["channel_id": channelId])
            XCTFail("凭证不对必须被本地平台拒绝")
        } catch let error as McpError {
            XCTAssertTrue(error.message.contains("凭证无效"), "实际：\(error.message)")
        }
        // tools/list 在本地也有答复（设置页连接检查走它）。
        let names = try await client.listToolNames()
        XCTAssertTrue(names.contains("agent_channel_post"))
    }

    func testLocalBusDeliversPostedMessageEvents() throws {
        let service = newService()
        LocalPlatform.installForTest(service)
        let channelId = try makeChannel(service)
        let received = expectation(description: "本地推送到达")
        var eventText: String?
        let unsubscribe = LocalPlatform.subscribeLocalChannelEvents(channelId: channelId) { text in
            eventText = text
            received.fulfill()
        }
        defer { unsubscribe() }
        _ = try call(service, "agent_channel_post", postArgs(channelId, "推送测试"))
        wait(for: [received], timeout: 5)
        let event = try XCTUnwrap(eventText)
        let parsed = try JSONSerialization.jsonObject(with: Data(event.utf8)) as? [String: Any]
        XCTAssertEqual(parsed?["type"] as? String, "message")
        let message = parsed?["message"] as? [String: Any]
        XCTAssertEqual(message?["text"] as? String, "推送测试")
    }

    func testChannelFeedDeliversLocalPostsWithoutSocket() async throws {
        let service = newService()
        LocalPlatform.installForTest(service)
        let channelId = try makeChannel(service)
        let client = McpClient(
            endpoint: LocalPlatform.localEndpoint,
            tokenProvider: { LocalPlatform.localToken },
            localHandler: LocalPlatform.handleMcpRequest
        )
        let feed = ChannelFeed(
            channelID: channelId,
            transport: ChannelTransport(client: client),
            webSocketURL: try XCTUnwrap(LocalPlatform.localWebSocketURL(channelID: channelId)),
            tokenProvider: { LocalPlatform.localToken },
            localSubscribe: LocalPlatform.subscribeLocalChannelEvents
        )
        let delivered = expectation(description: "feed 收到本地推送")
        feed.onMessages = { messages in
            if messages.contains(where: { $0.text == "推送测试" }) {
                delivered.fulfill()
            }
        }
        feed.start()
        // 等 feed 完成首拉并订阅本地总线后再发帖。
        try await Task.sleep(nanoseconds: 1_500_000_000)
        _ = try call(service, "agent_channel_post", postArgs(channelId, "推送测试"))
        await fulfillment(of: [delivered], timeout: 10)
        feed.stop()
    }

    func testPackAgentFromRegistryRolesReceivesDeliveryTask() throws {
        let service = newService()
        service.registerAgent(agentId: "dev1", agentName: "DevA")
        service.registerAgent(agentId: "rev1", agentName: "RevA")
        service.registerAgent(agentId: "pack1", agentName: "PackA", roles: ["pack"])
        let channelId = try makeChannel(service)
        let started = try call(service, "start_task", [
            "agent": "dev1",
            "reviewer": "rev1",
            "goal": "带递代码的交付",
            "project_id": "proj",
            "channel_id": channelId,
        ])
        let taskId = str(started["task"] as? [String: Any] ?? [:], "task_id")
        _ = try call(service, "claim_task", ["task_id": taskId],
                     callerId: "dev1", callerName: "DevA")
        let submitted = try call(service, "submit_result", [
            "task_id": taskId,
            "summary": "做完了",
            "branch": "feat/x",
        ], callerId: "dev1", callerName: "DevA")
        XCTAssertEqual(str(submitted, "state"), "reviewing")
        let packMine = try call(service, "list_my_tasks", [:],
                                callerId: "pack1", callerName: "PackA")
        let packTasks = (packMine["tasks"] as? [[String: Any]]) ?? []
        XCTAssertEqual(packTasks.count, 1)
        XCTAssertEqual(str(packTasks.first ?? [:], "kind"), "pack")
        let packId = str(packTasks.first ?? [:], "task_id")
        _ = try call(service, "claim_task", ["task_id": packId],
                     callerId: "pack1", callerName: "PackA")
        let attached = try call(service, "attach_delivery_diff", [
            "task_id": packId,
            "page": 1,
            "total_pages": 1,
            "text": "diff --git a/a b/a\n+++ b/a\n+hello\n",
        ], callerId: "pack1", callerName: "PackA")
        XCTAssertEqual((attached["complete"] as? Bool) ?? false, true)
        let revMine = try call(service, "list_my_tasks", [:],
                               callerId: "rev1", callerName: "RevA")
        let revTasks = (revMine["tasks"] as? [[String: Any]]) ?? []
        XCTAssertEqual(revTasks.count, 1)
        XCTAssertEqual(str(revTasks.first ?? [:], "kind"), "review")
    }

    func testTaskEventsWriteBackToBoundChannel() throws {
        let service = newService()
        service.registerAgent(agentId: "dev1", agentName: "DevA")
        let channelId = try makeChannel(service)
        let started = try call(service, "start_task", [
            "agent": "dev1",
            "goal": "写回测试任务",
            "project_id": "proj",
            "channel_id": channelId,
        ])
        let task = started["task"] as? [String: Any] ?? [:]
        let taskId = str(task, "task_id")
        XCTAssertFalse(taskId.isEmpty)
        _ = try call(service, "claim_task", ["task_id": taskId],
                     callerId: "dev1", callerName: "DevA")
        let messages = messagesOf(try call(service, "agent_channel_messages", [
            "channel_id": channelId,
        ]))
        let texts = messages.map { str($0, "text") }
        XCTAssertTrue(
            texts.contains { $0.contains("任务已派给 DevA") && $0.contains("[task:\(taskId)]") },
            "created 事件应写回绑定的频道")
        XCTAssertTrue(
            texts.contains { $0.contains("DevA 接手了") && $0.contains("[task:\(taskId)]") },
            "claimed 事件应写回绑定的频道")
        XCTAssertTrue(
            messages.filter { str($0, "text").contains("[task:\(taskId)]") }
                .allSatisfy { str($0, "agent_id") == LocalPlatform.appAgentID },
            "写回消息应以本机 App 身份发出")
    }

    func testTaskWithoutChannelWritesNothingBack() throws {
        let service = newService()
        service.registerAgent(agentId: "dev1", agentName: "DevA")
        let channelId = try makeChannel(service)
        _ = try call(service, "start_task", [
            "agent": "dev1",
            "goal": "不绑频道的任务",
            "project_id": "proj",
        ])
        let messages = messagesOf(try call(service, "agent_channel_messages", [
            "channel_id": channelId,
        ]))
        XCTAssertTrue(messages.allSatisfy { !str($0, "text").contains("[task:") })
    }
}
