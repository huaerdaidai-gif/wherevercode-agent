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
}
