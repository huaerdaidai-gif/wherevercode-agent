// WhereverCode Agent — iOS MVP
//
// P3-MSG 消息中心测试：聚合规则 + 读取管道。
//
// 已读语义锁定（P3-MSG 工作包要求）：消息中心只用既有读接口读，且
// 绝不发起 mark_read=true 的调用——only_unread 与服务端已读标记联动
// （读到哪标到哪），mark_read=false 的拉取不推进服务端已读标记，频道页
// 的未读状态不会被消息中心误消费。StubTransport 逐调用记录参数，测试
// 断言全部调用 markRead=false、未读区只来自 onlyUnread=true 的那一页。
// 测试样例与 Android 端 core/message/MessageCenterRulesTest.kt 一一对照
// （双端同口径、同边界语义），修改判定规则时请两端同步改。

import XCTest
@testable import WhereverCodeAgent

private struct RecordedPoll: Equatable {
    let channelId: String
    let afterId: String?
    let limit: Int
    let onlyUnread: Bool
    let markRead: Bool
}

/// 桩传输：逐调用记录 pollMessages 参数，锁定已读语义。
private final class StubTransport: MessageCenterTransport {
    let pages: [String: [Bool: [ChannelMessage]]]
    private(set) var recordedPolls: [RecordedPoll] = []

    init(pages: [String: [Bool: [ChannelMessage]]]) {
        self.pages = pages
    }

    func pollMessages(
        channelID: String,
        afterID: String?,
        limit: Int,
        onlyUnread: Bool,
        markRead: Bool,
    ) async throws -> [ChannelMessage] {
        recordedPolls.append(RecordedPoll(
            channelId: channelID,
            afterId: afterID,
            limit: limit,
            onlyUnread: onlyUnread,
            markRead: markRead,
        ))
        return pages[channelID]?[onlyUnread] ?? []
    }
}

private func message(
    _ id: String,
    agentName: String = "协调者",
    text: String,
    at: Double,
) -> ChannelMessage {
    ChannelMessage(
        id: id,
        agentId: "local-app",
        agentName: agentName,
        text: text,
        createdAtSeconds: at,
    )
}

final class MessageCenterTests: XCTestCase {
    let selfNames = ["本机 App", "协调者", "Codex"]

    // MARK: - @我 判定

    func testMentionMatchesSelfAliasPrefixAndEmbedded() {
        // 平台寻址惯例：正文前缀 @<Name>（传输层无收件人字段）。
        XCTAssertEqual(
            MessageCenterRules.mentionAlias(in: "@协调者 你看一下", selfNames: selfNames),
            "协调者",
        )
        XCTAssertEqual(
            MessageCenterRules.mentionAlias(in: "让 @Codex 看看", selfNames: selfNames),
            "Codex",
        )
        // 最长别名优先：@本机 App 命中“本机 App”而不是“本机”。
        XCTAssertEqual(
            MessageCenterRules.mentionAlias(in: "@本机 App 收一下", selfNames: selfNames),
            "本机 App",
        )
    }

    func testMentionDoesNotMatchWordContinuationsOrOtherMembers() {
        // “@我们”不是“@我”。
        XCTAssertNil(MessageCenterRules.mentionAlias(in: "@我们试试", selfNames: ["我"]))
        // 他人被 @ 不算 @我。
        XCTAssertNil(MessageCenterRules.mentionAlias(in: "@GeminiCli 帮忙", selfNames: selfNames))
        // 邮箱/词内 @ 不算提及。
        XCTAssertNil(
            MessageCenterRules.mentionAlias(in: "abc@coord1ner 后缀相连", selfNames: ["协调者"]),
        )
        // 无 @ 不算。
        XCTAssertNil(MessageCenterRules.mentionAlias(in: "讨论里提到我", selfNames: selfNames))
    }

    // MARK: - 任务回执判定（与 Android 端 parseTeamAttribution 同协议）

    func testTaskReceiptRecognizedFromAttributionHeader() {
        let attribution = MessageCenterRules.teamAttribution(
            in: "【协调者 · 任务 a1b2c3d4 · 修复登录】任务已完成，构建绿。",
        )
        XCTAssertEqual(attribution?.senderName, "协调者")
        XCTAssertEqual(attribution?.taskId, "a1b2c3d4")
        XCTAssertEqual(attribution?.taskTitle, "修复登录")
        // 无任务的头不是回执。
        XCTAssertNil(MessageCenterRules.teamAttribution(in: "【协调者】纯讨论一句话。")?.taskId)
        XCTAssertNil(MessageCenterRules.teamAttribution(in: "没有头的普通消息"))
    }

    // MARK: - 聚合

    func testBuildEntriesClassifiesRecentAndUnreadPages() {
        let recent = [
            message("m1", text: "@本机 App 帮我看看构建", at: 10),
            message("m2", text: "【协调者 · 任务 a1b2c3d4 · 修复登录】已修复", at: 12),
            message("m3", text: "普通讨论", at: 11),
            // 一条既是 @我 又是任务回执：两个区各计一条。
            message("m4", text: "@Codex 【协调者 · 任务 e5f6a7b8 · 重构】开始重构", at: 13),
        ]
        let unread = [
            message("u1", text: "@本机 App 新消息", at: 20),
            message("m1", text: "@本机 App 帮我看看构建", at: 10), // 未读页与最新页可能重叠，按 id 去重
        ]
        let snapshot = MessageCenterRules.buildEntries(
            channelId: "ch1",
            channelName: "构建频道",
            recent: recent,
            unread: unread,
            selfNames: selfNames,
        )
        XCTAssertEqual(snapshot.mentions.map(\.messageId), ["m4", "m1"])
        XCTAssertEqual(snapshot.mentions.last?.matchedAlias, "本机 App")
        XCTAssertEqual(snapshot.taskReceipts.map(\.messageId), ["m4", "m2"])
        XCTAssertEqual(snapshot.taskReceipts.last?.taskId, "a1b2c3d4")
        XCTAssertEqual(snapshot.unread.map(\.messageId), ["u1", "m1"])
        XCTAssertGreaterThanOrEqual(
            snapshot.mentions.first!.createdAtSeconds,
            snapshot.mentions.last!.createdAtSeconds,
        )
        // 展示正文剥归属头。
        XCTAssertTrue(snapshot.taskReceipts.last!.text.hasPrefix("已修复"))
    }

    func testMergeDedupesBySectionAndMessageIdAndCapsEachSection() {
        func entry(_ id: String) -> MessageCenterEntry {
            MessageCenterEntry(
                section: .unread,
                channelId: "ch",
                channelName: "c",
                messageId: id,
                senderName: "s",
                text: "t",
                createdAtSeconds: Double(id)!,
            )
        }
        let merged = MessageCenterRules.merge([
            MessageCenterSnapshot(unread: (1...25).map { entry(String($0)) }),
            MessageCenterSnapshot(unread: [entry("5"), entry("99")]),
        ])
        XCTAssertEqual(merged.unread.count, 20)
        XCTAssertEqual(merged.unread.first?.createdAtSeconds, 99)
        XCTAssertEqual(merged.unread.filter { $0.messageId == "5" }.count, 1)
    }

    // MARK: - 已读语义（桩传输锁定）

    func testLoaderOnlyReadsNeverMarksReadAndUnreadSectionComesOnlyFromOnlyUnreadPages() async throws {
        let stub = StubTransport(pages: [
            "ch1": [
                false: [
                    message("r1", text: "@协调者 在吗", at: 5),
                    message("r2", text: "【协调者 · 任务 a1b2c3d4 · 派发】已收到", at: 6),
                ],
                true: [message("u1", text: "只读回执不推已读", at: 7)],
            ],
        ])
        let snapshot = try await MessageCenterLoader(transport: stub).fetch(
            channels: [MessageCenterChannelSource(channelId: "ch1", channelName: "测试频道")],
            selfNames: selfNames,
        )
        // 逐频道两页：普通读 + only_unread 读。
        XCTAssertEqual(stub.recordedPolls.count, 2)
        XCTAssertTrue(stub.recordedPolls.allSatisfy { $0.channelId == "ch1" })
        // 核心语义：绝不 mark_read=true；已读标记只属于频道页自己。
        XCTAssertTrue(stub.recordedPolls.allSatisfy { !$0.markRead })
        XCTAssertEqual(Set(stub.recordedPolls.map(\.onlyUnread)), Set([false, true]))
        XCTAssertEqual(stub.recordedPolls.first?.limit, 20)
        XCTAssertNil(stub.recordedPolls.first?.afterId)
        // 三区各就各位。
        XCTAssertEqual(snapshot.mentions.map(\.messageId), ["r1"])
        XCTAssertEqual(snapshot.taskReceipts.map(\.messageId), ["r2"])
        XCTAssertEqual(snapshot.unread.map(\.messageId), ["u1"])
    }

    func testLoaderIteratesEveryChannel() async throws {
        let stub = StubTransport(pages: [
            "ch1": [false: [], true: []],
            "ch2": [false: [], true: []],
        ])
        let snapshot = try await MessageCenterLoader(transport: stub).fetch(
            channels: [
                MessageCenterChannelSource(channelId: "ch1", channelName: "频道一"),
                MessageCenterChannelSource(channelId: "ch2", channelName: "频道二"),
            ],
            selfNames: selfNames,
        )
        XCTAssertEqual(stub.recordedPolls.map(\.channelId), ["ch1", "ch1", "ch2", "ch2"])
        XCTAssertEqual(snapshot, MessageCenterSnapshot())
    }

    // MARK: - 与 Android 端一致性的边界样例（双端口径对照）

    func testPlatformAddressingConventionStaysPrefixBased() {
        // 传输层无收件人字段，寻址靠正文前缀（ChannelTransport 头注释）：
        // 前缀与中段提及都算命中；非本机别名不算。
        XCTAssertEqual(
            MessageCenterRules.mentionAlias(in: "@本机 App 帮忙", selfNames: ["本机 App"]),
            "本机 App",
        )
        XCTAssertNil(
            MessageCenterRules.mentionAlias(in: "@ClaudeCode 你好", selfNames: ["本机 App"]),
        )
    }
}
