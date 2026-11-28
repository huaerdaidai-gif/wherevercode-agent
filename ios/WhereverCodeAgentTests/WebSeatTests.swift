// WhereverCode Agent — iOS tests
//
// Web seat (chat mode): trigger gate discipline, mention
// stripping, reply chunking, login probe heuristic and the Claude
// page-script contract. Pure logic only; no WebView, no network.

import Foundation
import XCTest
@testable import WhereverCodeAgent

final class WebSeatTests: XCTestCase {

    private let claude = WebSeatSite.claude

    private func gate(
        sender: String, text: String, root: String? = nil, isDM: Bool = false
    ) -> Bool {
        WebSeatGate.shouldRespond(
            senderId: sender,
            ownerAgentId: "app",
            seatAgentId: "claude-web",
            text: text,
            threadRoot: root,
            isDM: isDM,
            handles: claude.mentionHandles,
            otherHandles: claude.otherHandles())
    }

    // MARK: Gate — @whoever is @'d answers; bots never trigger

    func testOwnerMentionTriggers() {
        XCTAssertTrue(gate(sender: "app", text: "@Claude Chat 在吗"))
        XCTAssertTrue(gate(sender: "app", text: "@所有人 都看看"))
    }

    func testBotMentionTriggersDoorbell() {
        // Seats may talk to each other — with an @ as the doorbell.
        XCTAssertTrue(gate(sender: "gpt-chat", text: "@Claude Chat 你怎么看"))
        XCTAssertTrue(gate(sender: "agy", text: "@Claude Chat 回一下"))
        XCTAssertTrue(gate(sender: "gemini", text: "@所有人 都看一下"))
    }

    func testBotPlainMessageNeverTriggers() {
        XCTAssertFalse(gate(sender: "gpt-chat", text: "我先说一句"))
        XCTAssertFalse(gate(sender: "agy", text: "补充一个数据"))
    }

    func testSeatNeverAnswersItself() {
        XCTAssertFalse(gate(sender: "claude-web", text: "@Claude Chat 自问自答"))
    }

    func testOwnerThreadReplyContinuesWithoutMention() {
        XCTAssertTrue(gate(sender: "app", text: "那第二个问题呢", root: "root1"))
    }

    func testBotThreadReplyNeverContinues() {
        XCTAssertFalse(gate(sender: "gpt-chat", text: "我补充一句", root: "root1"))
        XCTAssertFalse(gate(sender: "gemini", text: "同上", root: "root1"))
    }

    func testOwnerThreadReplyAddressedElsewhereDoesNotTrigger() {
        XCTAssertFalse(gate(sender: "app", text: "@GPT Chat 你来说", root: "root1"))
    }

    func testOwnerTopLevelWithoutMentionDoesNotTrigger() {
        XCTAssertFalse(gate(sender: "app", text: "随便聊聊"))
    }

    func testDMFromOwnerAlwaysTriggers() {
        XCTAssertTrue(gate(sender: "app", text: "在吗", isDM: true))
    }

    func testDMFromBotNeverTriggers() {
        XCTAssertFalse(gate(sender: "gpt-chat", text: "在吗", isDM: true))
    }

    // MARK: Strip mentions

    func testStripMentions() {
        let out = WebSeatGate.stripMentions(
            "@Claude Chat 帮我看看 @所有人", handles: claude.mentionHandles)
        XCTAssertEqual(out, "帮我看看")
    }

    // MARK: Chunking

    func testChunkShortTextUnchanged() {
        XCTAssertEqual(WebSeatEngine.chunkText("短消息"), ["短消息"])
    }

    func testChunkLongTextSplitsWithinLimit() {
        let text = String(repeating: "a", count: 8000)
        let chunks = WebSeatEngine.chunkText(text)
        XCTAssertEqual(chunks.count, 3)
        XCTAssertTrue(chunks.allSatisfy { $0.count <= 3900 })
        XCTAssertEqual(chunks.joined().count, 8000)
    }

    func testChunkPrefersNewlineBoundary() {
        let text = String(repeating: "x", count: 3890) + "\n" + String(repeating: "y", count: 100)
        let chunks = WebSeatEngine.chunkText(text)
        XCTAssertEqual(chunks.count, 2)
        XCTAssertEqual(chunks[0].count, 3890)
    }

    // MARK: Login probe heuristic

    func testProbeLooksLoggedIn() {
        XCTAssertTrue(ClaudeSeatScripts.probeLooksLoggedIn(
            href: "https://claude.ai/new", composer: true))
        XCTAssertFalse(ClaudeSeatScripts.probeLooksLoggedIn(
            href: "https://claude.ai/login", composer: true))
        XCTAssertFalse(ClaudeSeatScripts.probeLooksLoggedIn(
            href: "https://claude.ai/new", composer: false))
    }

    // MARK: Script contract

    func testInsertScriptEscapesQuestion() {
        let js = ClaudeSeatScripts.insert(question: "他说\"你好\"\n第二行")
        XCTAssertTrue(js.contains("insertText"))
        XCTAssertFalse(js.contains("他说\"你好\""))
        XCTAssertTrue(js.contains("\\u") || js.contains("\\n") || js.contains("\\\""))
    }

    func testExtractScriptTargetsNewRow() {
        let js = ClaudeSeatScripts.extract(beforeCount: 3)
        XCTAssertTrue(js.contains("asst[3]"))
        XCTAssertTrue(js.contains("standard-markdown"))
    }

    // MARK: Record coding

    func testRecordCodableRoundTrip() throws {
        var record = WebSeatRecord(
            seatId: "claude-web-ab12", site: .claude, dataStoreId: "UUID-1")
        record.channelIds = ["chan-1"]
        let data = try JSONEncoder().encode(record)
        let decoded = try JSONDecoder().decode(WebSeatRecord.self, from: data)
        XCTAssertEqual(decoded, record)
        XCTAssertEqual(decoded.site?.driverId, "claude-web")
    }
}
