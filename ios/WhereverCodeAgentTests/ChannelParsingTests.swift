// WhereverCode Agent — iOS MVP tests
//
// Channel parsing: WS event frames, message dictionaries and the
// tolerant decoders. Fixture values use example.invalid-style
// placeholders and invented ids only — no real addresses or tokens.

import Foundation
import XCTest
@testable import WhereverCodeAgent

final class ChannelParsingTests: XCTestCase {

    func testParseWsMessageEvent() {
        let json = """
        {"type":"message","message":{"id":"msg-1","agent_id":"agent-a","agent_name":"Agent A","text":"hello","reply_to":null,"created_at":1794500000.5}}
        """
        let message = parseChannelWsEvent(json)
        XCTAssertEqual(message?.id, "msg-1")
        XCTAssertEqual(message?.agentId, "agent-a")
        XCTAssertEqual(message?.agentName, "Agent A")
        XCTAssertEqual(message?.text, "hello")
        XCTAssertEqual(message?.createdAtSeconds, 1794500000.5)
    }

    func testParseWsEventIgnoresHelloPingAndUnknown() {
        XCTAssertNil(parseChannelWsEvent(#"{"type":"hello","message":null}"#))
        XCTAssertNil(parseChannelWsEvent(#"{"type":"ping"}"#))
        XCTAssertNil(parseChannelWsEvent(#"{"type":"other","message":{"id":"x"}}"#))
        XCTAssertNil(parseChannelWsEvent("not json at all"))
        XCTAssertNil(parseChannelWsEvent(""))
    }

    func testParseWsEventDropsEmptyId() {
        let json = """
        {"type":"message","message":{"id":"","agent_id":"a","agent_name":"A","text":"hi","created_at":1}}
        """
        XCTAssertNil(parseChannelWsEvent(json))
    }

    func testMessageListDecodingToleratesMissingFields() {
        let dict: [String: Any] = [
            "messages": [
                ["id": "m1", "agent_name": "Agent A", "text": "hi"],
                ["agent_name": "No Id", "text": "dropped"],
                ["id": "m2", "agent_id": "b", "text": "yo", "created_at": 42] as [String: Any],
            ]
        ]
        let messages = ChannelMessage.list(from: dict)
        XCTAssertEqual(messages.map(\.id), ["m1", "m2"])
        XCTAssertEqual(messages[0].agentId, "")
        XCTAssertEqual(messages[0].createdAtSeconds, 0)
        XCTAssertEqual(messages[1].createdAtSeconds, 42)
        XCTAssertEqual(messages[0].displaySender, "Agent A")
        XCTAssertEqual(messages[1].displaySender, "b")
    }

    func testChannelListDecoding() {
        let dict: [String: Any] = [
            "channels": [
                ["id": "ch-1", "name": "Team", "participants": ["Agent A", "Agent B"],
                 "last_message": ["text": "last one"] as [String: Any]] as [String: Any],
                ["id": "ch-2"],
            ]
        ]
        let channels = ChannelInfo.list(from: dict)
        XCTAssertEqual(channels.count, 2)
        XCTAssertEqual(channels[0].displayName, "Team")
        XCTAssertEqual(channels[0].participants, ["Agent A", "Agent B"])
        XCTAssertEqual(channels[0].lastMessageText, "last one")
        XCTAssertEqual(channels[1].displayName, "ch-2")
    }

    func testChannelListDecodingReadsArchivedFlag() {
        // Phase-1 channel management: archived parses tolerantly,
        // absent means not archived.
        let dict: [String: Any] = [
            "channels": [
                ["id": "ch-1", "name": "Old", "archived": true] as [String: Any],
                ["id": "ch-2", "name": "Live"] as [String: Any],
            ]
        ]
        let channels = ChannelInfo.list(from: dict)
        XCTAssertEqual(channels.count, 2)
        XCTAssertTrue(channels[0].isArchived)
        XCTAssertFalse(channels[1].isArchived)
    }

    func testTaskListDecodingIsTolerant() {
        let dict: [String: Any] = [
            "tasks": [
                ["id": "t-1", "title": "Do a thing", "status": "queued", "version": 3] as [String: Any],
                ["task_id": "t-2", "summary": "Fallback title", "state": "claimed"],
                ["title": "No id at all"],
            ]
        ]
        let tasks = AgentTask.list(from: dict)
        XCTAssertEqual(tasks.map(\.id), ["t-1", "t-2"])
        XCTAssertEqual(tasks[0].displayTitle, "Do a thing")
        XCTAssertEqual(tasks[0].status, "queued")
        XCTAssertEqual(tasks[0].version, 3)
        XCTAssertEqual(tasks[1].displayTitle, "Fallback title")
        XCTAssertEqual(tasks[1].status, "claimed")
        XCTAssertNil(tasks[1].version)
    }

    // MARK: - Phase-2/3 channel completion parsing & pure logic

    func testMessageDecodingReadsPhase2Fields() {
        let dict: [String: Any] = [
            "messages": [[
                "id": "m1", "agent_id": "a1", "agent_name": "甲", "text": "",
                "reply_to": "m0", "created_at": 42.0,
                "recalled": true, "pinned": true,
                "attachment_ref": "ref-1", "forward_of": "大厅 · 乙",
                "reactions": [["emoji": "👍", "count": 2, "mine": true]],
            ]],
        ]
        let messages = ChannelMessage.list(from: dict)
        XCTAssertEqual(messages.count, 1)
        let m = messages[0]
        XCTAssertTrue(m.recalled)
        XCTAssertTrue(m.pinned)
        XCTAssertEqual(m.replyTo, "m0")
        XCTAssertEqual(m.attachmentRef, "ref-1")
        XCTAssertEqual(m.forwardOf, "大厅 · 乙")
        XCTAssertEqual(m.reactions.count, 1)
        XCTAssertEqual(m.reactions.first?.count, 2)
        XCTAssertEqual(m.reactions.first?.mine, true)
        XCTAssertEqual(ChannelExtras.displayText(m), "此消息已撤回")
    }

    func testChannelListDecodingReadsKind() {
        let dict: [String: Any] = [
            "channels": [
                ["id": "c1", "name": "大厅", "kind": "channel"],
                ["id": "d1", "name": "甲", "kind": "dm"],
                ["id": "c2", "name": "旧频道"],
            ],
        ]
        let channels = ChannelInfo.list(from: dict)
        XCTAssertEqual(channels.count, 3)
        XCTAssertFalse(channels[0].isDM)
        XCTAssertTrue(channels[1].isDM)
        XCTAssertEqual(channels[2].kind, "channel")
    }

    func testChannelExtrasPureLogic() {
        let parent = ChannelMessage(id: "p", agentId: "a", agentName: "甲", text: "父消息", createdAtSeconds: 1)
        let child = ChannelMessage(id: "c", agentId: "b", agentName: "乙", text: "@甲 看下", replyTo: "p", createdAtSeconds: 2)
        let plain = ChannelMessage(id: "n", agentId: "b", agentName: "乙", text: "普通", createdAtSeconds: 3)
        XCTAssertEqual(
            ChannelExtras.replyQuote(in: [parent, child], for: child),
            "回复 甲：父消息")
        XCTAssertNil(ChannelExtras.replyQuote(in: [parent, child], for: plain))
        XCTAssertEqual(
            ChannelExtras.reactionText([
                ChannelReaction(emoji: "👍", count: 2, mine: true),
                ChannelReaction(emoji: "❤️", count: 0),
            ]),
            "👍 2")
        let center = ChannelExtras.buildMessageCenter(
            messages: [parent, child, plain], myNames: ["甲"], lastReadId: "p")
        XCTAssertEqual(center.unreadCount, 2)
        XCTAssertEqual(center.unreadMentionCount, 1)
        XCTAssertEqual(center.mentionMessages.map(\.id), ["c"])
        XCTAssertEqual(ChannelExtras.forwardOfLabel(sourceChannelName: "大厅", senderName: "乙"), "大厅 · 乙")
        XCTAssertEqual(ChannelExtras.buildEffectKey(nonce: "Ab-1_2!!"), "ck_Ab-1_2")
        let readers = ChannelReader.list(from: ["readers": [
            ["agent_id": "a1", "agent_name": "甲", "read": true],
            ["agent_id": "a2", "agent_name": "乙", "read": false],
        ]])
        XCTAssertEqual(ChannelExtras.readersText(readers), "已读：甲 · 未读：乙")
        let due = ChannelExtras.dueScheduled([
            ScheduledChannelPost(id: "s2", channelId: "c", text: "晚", dueAtSeconds: 200, effectKey: "ck_2"),
            ScheduledChannelPost(id: "s1", channelId: "c", text: "早", dueAtSeconds: 100, effectKey: "ck_1"),
        ], nowSeconds: 150)
        XCTAssertEqual(due.map(\.id), ["s1"])
    }

    func testLegacyCodablePayloadsStillDecode() throws {
        // JSON written before phase-2/3 lacks the new keys entirely.
        let messageJSON = #"{"id":"m1","agentId":"a1","agentName":"甲","text":"hi","createdAtSeconds":7}"#
        let message = try JSONDecoder().decode(ChannelMessage.self, from: Data(messageJSON.utf8))
        XCTAssertFalse(message.recalled)
        XCTAssertFalse(message.pinned)
        XCTAssertTrue(message.reactions.isEmpty)
        let channelJSON = #"{"id":"c1","name":"大厅","participants":[],"isArchived":false}"#
        let channel = try JSONDecoder().decode(ChannelInfo.self, from: Data(channelJSON.utf8))
        XCTAssertEqual(channel.kind, "channel")
    }

    func testMentionAllGate() {
        // @所有人只在 owner/协调者发时生效；其他人写的只当普通文本。
        let owner = ChannelMessage(id: "m1", agentId: "owner", agentName: "主管",
                                   text: "@所有人 请看一下", createdAtSeconds: 1)
        let plain = ChannelMessage(id: "m2", agentId: "a2", agentName: "乙",
                                   text: "@所有人 请看一下", createdAtSeconds: 2)
        let recalled = ChannelMessage(id: "m3", agentId: "owner", agentName: "主管",
                                      text: "@all 已撤回", createdAtSeconds: 3, recalled: true)
        XCTAssertTrue(ChannelExtras.mentionsAllText("@all hi"))
        XCTAssertTrue(ChannelExtras.mentionsAllText("@所有人 hi"))
        XCTAssertFalse(ChannelExtras.mentionsAllText("@甲 hi"))
        XCTAssertTrue(ChannelExtras.allMentionLive(owner, privilegedSenderIds: ["owner"]))
        XCTAssertFalse(ChannelExtras.allMentionLive(plain, privilegedSenderIds: ["owner"]))
        XCTAssertFalse(ChannelExtras.allMentionLive(recalled, privilegedSenderIds: ["owner"]))
        // 生效的 @所有人计入消息中心的 @我聚合。
        let center = ChannelExtras.buildMessageCenter(
            messages: [owner, plain], myNames: [], lastReadId: nil,
            privilegedSenderIds: ["owner"])
        XCTAssertEqual(center.mentionMessages.map(\.id), ["m1"])
    }
}
