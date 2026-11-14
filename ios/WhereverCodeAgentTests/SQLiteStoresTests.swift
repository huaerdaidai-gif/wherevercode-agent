import XCTest
@testable import WhereverCodeAgent

final class SQLiteStoresTests: XCTestCase {
    func testConversationAndTaskStorePersistence() throws {
        let db = try SQLiteDatabase(path: ":memory:")
        let convStore = try SQLiteConversationStore(db: db)
        let taskStore = try LocalTaskStore(db: db)

        let conv = try convStore.createConversation(brainId: "deepseek", title: "测试会话")
        try convStore.appendMessage(ConversationMessage(conversationId: conv.id, role: "user", content: "你好"))
        let msgs = try convStore.listMessages(conversationId: conv.id)
        XCTAssertEqual(msgs.count, 1)
        XCTAssertEqual(msgs.first?.content, "你好")

        let created = try taskStore.createTask(LocalTask(title: "验证 iOS 70 屏装配", state: "queued"))
        let working = try taskStore.transition(taskId: created.taskId, to: "working", note: "开始执行")
        XCTAssertEqual(working.state, "working")
        let events = try taskStore.listEvents(taskId: created.taskId)
        XCTAssertEqual(events.count, 2)
    }

    func testSecretRedactorMasksSensitiveTokens() {
        let raw = "Authorization: Bearer v5_secret_token_123456789 and sk-abcdefghijklmnop1234"
        let redacted = SecretRedactor.redact(raw)
        XCTAssertFalse(redacted.contains("v5_secret_token_123456789"))
        XCTAssertFalse(redacted.contains("sk-abcdefghijklmnop1234"))
        XCTAssertTrue(redacted.contains("[REDACTED_SECRET]"))
    }
}
