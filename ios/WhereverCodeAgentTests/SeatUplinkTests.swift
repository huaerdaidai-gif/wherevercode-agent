// WhereverCode Agent — iOS tests
//
// 频道三期席位上行：帧编解码、seq 幂等、断线补发队列、
// 派单落本地（同一个 task_id）以及重连后按序重放。

import Foundation
import XCTest
@testable import WhereverCodeAgent

final class SeatUplinkTests: XCTestCase {

    private func jsonObject(_ text: String) throws -> [String: Any] {
        let parsed = try JSONSerialization.jsonObject(with: Data(text.utf8))
        return try XCTUnwrap(parsed as? [String: Any])
    }

    private func newService() -> LocalChannelService {
        let service = LocalChannelService(persistence: InMemoryLocalChannelPersistence())
        // 237 起取消默认频道种子：夹具显式建唯一频道——派单落点即它、
        // seat_register 的频道名单即 [它]（断言仍走 defaultChannelId()）。
        _ = try? service.dispatch(
            tool: "agent_channel_create", args: ["name": "本地频道"],
            callerId: LocalPlatform.appAgentID, callerName: LocalPlatform.appAgentName)
        return service
    }

    /// 237 起无默认频道：夹具唯一频道的 id（经频道列表读回）。
    private func soleChannelId(_ service: LocalChannelService) -> String {
        let listed = try? service.dispatch(
            tool: "agent_channel_list", args: [:],
            callerId: LocalPlatform.appAgentID, callerName: LocalPlatform.appAgentName)
        let channels = listed?["channels"] as? [[String: Any]]
        return channels?.first?["id"] as? String ?? ""
    }

    // MARK: - 帧编解码

    func testFrameCodecRoundTripsSeatRegisterDispatchAckAndEvent() throws {
        let register = try SeatFrameCodec.encode(.seatRegister(
            appInstanceId: "inst-1", agentId: "local-app",
            channels: ["ch-a", "ch-b"], platform: "ios", appVersion: "0.5.1"))
        let registerObject = try jsonObject(register)
        XCTAssertEqual(registerObject["type"] as? String, "seat_register")
        XCTAssertEqual(registerObject["app_instance_id"] as? String, "inst-1")
        XCTAssertEqual(registerObject["agent_id"] as? String, "local-app")
        XCTAssertEqual(registerObject["platform"] as? String, "ios")
        XCTAssertEqual(registerObject["app_version"] as? String, "0.5.1")
        XCTAssertEqual(registerObject["channels"] as? [String], ["ch-a", "ch-b"])
        guard case .seatRegister(let appInstanceId, let agentId, let channels, let platform, let appVersion) =
                try SeatFrameCodec.decode(register) else {
            return XCTFail("seat_register 解码类型不对")
        }
        XCTAssertEqual(appInstanceId, "inst-1")
        XCTAssertEqual(agentId, "local-app")
        XCTAssertEqual(channels, ["ch-a", "ch-b"])
        XCTAssertEqual(platform, "ios")
        XCTAssertEqual(appVersion, "0.5.1")

        let task: [String: Any] = [
            "task_id": "cloud-1",
            "title": "修灯",
            "goal": "把灯修好",
            "state": "dispatched",
            "channel_id": "cloud-channel",
        ]
        let dispatch = try SeatFrameCodec.encode(.taskDispatch(task: task))
        guard case .taskDispatch(let decodedTask) = try SeatFrameCodec.decode(dispatch) else {
            return XCTFail("task_dispatch 解码类型不对")
        }
        XCTAssertEqual(decodedTask["task_id"] as? String, "cloud-1")
        XCTAssertEqual(decodedTask["goal"] as? String, "把灯修好")

        let ack = try SeatFrameCodec.encode(.taskAck(taskId: "cloud-1"))
        guard case .taskAck(let taskId) = try SeatFrameCodec.decode(ack) else {
            return XCTFail("task_ack 解码类型不对")
        }
        XCTAssertEqual(taskId, "cloud-1")

        let event = try SeatFrameCodec.encode(.taskEvent(
            taskId: "cloud-1", seq: 7, kind: "claimed", actor: "本机 App",
            at: 1_700_000_000, payload: ["text": "接手了"]))
        let eventObject = try jsonObject(event)
        XCTAssertEqual(eventObject["type"] as? String, "task_event")
        XCTAssertEqual(eventObject["seq"] as? Int, 7)
        XCTAssertNil(eventObject["token"])
        guard case .taskEvent(let id, let seq, let kind, let actor, let at, let payload) =
                try SeatFrameCodec.decode(event) else {
            return XCTFail("task_event 解码类型不对")
        }
        XCTAssertEqual(id, "cloud-1")
        XCTAssertEqual(seq, 7)
        XCTAssertEqual(kind, "claimed")
        XCTAssertEqual(actor, "本机 App")
        XCTAssertEqual(at, 1_700_000_000)
        XCTAssertEqual(payload["text"] as? String, "接手了")

        let eventAck = try SeatFrameCodec.encode(.taskEventAck(taskId: "cloud-1", seq: 7))
        guard case .taskEventAck(let ackId, let ackSeq) = try SeatFrameCodec.decode(eventAck) else {
            return XCTFail("task_event_ack 解码类型不对")
        }
        XCTAssertEqual(ackId, "cloud-1")
        XCTAssertEqual(ackSeq, 7)
    }

    func testFrameCodecRejectsMalformedAndIgnoresUnknownType() {
        XCTAssertThrowsError(try SeatFrameCodec.decode(""))
        XCTAssertThrowsError(try SeatFrameCodec.decode("[]"))
        XCTAssertThrowsError(try SeatFrameCodec.decode("{\"task_id\":\"x\"}"))
        XCTAssertThrowsError(try SeatFrameCodec.decode("{\"type\":\"task_dispatch\"}"))
        XCTAssertThrowsError(try SeatFrameCodec.decode("{\"type\":\"task_dispatch\",\"task\":[]}"))
        XCTAssertThrowsError(try SeatFrameCodec.decode("{\"type\":\"task_ack\"}"))
        XCTAssertThrowsError(try SeatFrameCodec.decode(
            "{\"type\":\"task_event\",\"task_id\":\"t\",\"kind\":\"claimed\",\"actor\":\"a\",\"at\":1}"))
        XCTAssertThrowsError(try SeatFrameCodec.decode(
            "{\"type\":\"seat_register\",\"app_instance_id\":\"i\",\"agent_id\":\"a\",\"platform\":\"ios\",\"app_version\":\"1\",\"channels\":[1]}"))
        guard case .ignored(let type) = try? SeatFrameCodec.decode("{\"type\":\"hello\"}") else {
            return XCTFail("未知 type 应忽略而不是抛错")
        }
        XCTAssertEqual(type, "hello")
    }

    func testProductionSeatURLConstant() {
        XCTAssertEqual(
            SeatUplinkSpec.productionURL.absoluteString,
            "wss://v5.cococolo.xyz/api/open/v1/seat")
    }

    func testSeatURLOverrideBeatsDerivedPlatformAddress() {
        let suite = "seat-uplink-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let settings = AppSettings(
            platformBaseURLString: "https://v5.cococolo.xyz/api/open/v1/mcp",
            channelID: "c")
        XCTAssertEqual(
            settings.seatWebSocketURL(defaults: defaults)?.absoluteString,
            "wss://v5.cococolo.xyz/api/open/v1/seat")
        let lan = AppSettings(platformBaseURLString: "http://127.0.0.1:8765", channelID: "c")
        XCTAssertEqual(
            lan.seatWebSocketURL(defaults: defaults)?.absoluteString,
            "ws://127.0.0.1:8765/api/open/v1/seat")
        defaults.set("ws://127.0.0.1:9/api/open/v1/seat", forKey: SeatUplinkSpec.seatURLOverrideKey)
        XCTAssertEqual(
            settings.seatWebSocketURL(defaults: defaults)?.absoluteString,
            "ws://127.0.0.1:9/api/open/v1/seat")
        XCTAssertNil(AppSettings(platformBaseURLString: "not a url", channelID: "c")
            .seatWebSocketURL(defaults: UserDefaults(suiteName: suite + "-b")!))
    }

    // MARK: - seq 与幂等

    func testSeqIsMonotonicAndIdempotencyKeyDoesNotMintAnother() {
        let queue = UplinkReplayQueue()
        let first = queue.enqueue(
            idempotencyKey: "claim-1", taskId: "t1", kind: "claimed",
            actor: "本机 App", at: 10, payload: ["text": "接手"])
        let second = queue.enqueue(
            idempotencyKey: "progress-1", taskId: "t1", kind: "progress",
            actor: "本机 App", at: 11, payload: ["text": "一半"])
        let replayed = queue.enqueue(
            idempotencyKey: "claim-1", taskId: "t1", kind: "claimed",
            actor: "别人", at: 99, payload: ["text": "不该出现"])
        XCTAssertEqual(first.seq, 1)
        XCTAssertEqual(second.seq, 2)
        XCTAssertEqual(replayed.seq, 1)
        XCTAssertEqual(replayed.actor, "本机 App")
        XCTAssertEqual(queue.pendingCount, 2)
        XCTAssertEqual(queue.replay().map(\.seq), [1, 2])
    }

    func testAcceptIsIdempotentOnTaskIdAndSeqAndReplaysInOrder() {
        let queue = UplinkReplayQueue()
        XCTAssertTrue(queue.accept(
            seq: 5, taskId: "t", kind: "submitted", actor: "a", at: 3,
            payload: ["text": "交了"], idempotencyKey: "t|5"))
        XCTAssertTrue(queue.accept(
            seq: 2, taskId: "t", kind: "claimed", actor: "a", at: 1,
            payload: ["text": "领了"], idempotencyKey: "t|2"))
        XCTAssertFalse(queue.accept(
            seq: 5, taskId: "t", kind: "submitted", actor: "a", at: 3,
            payload: ["text": "交了"], idempotencyKey: "t|5"))
        XCTAssertFalse(queue.accept(
            seq: 5, taskId: "other", kind: "claimed", actor: "a", at: 1,
            payload: [:], idempotencyKey: "other|5"))
        XCTAssertEqual(queue.replay().map(\.seq), [2, 5])
        let minted = queue.enqueue(
            idempotencyKey: "fresh", taskId: "t", kind: "progress",
            actor: "a", at: 4, payload: ["text": "续"])
        XCTAssertGreaterThan(minted.seq, 5)
        queue.acknowledge(taskId: "t", seq: 2)
        queue.acknowledge(taskId: "t", seq: 2)
        queue.acknowledge(taskId: "nope", seq: 5)
        XCTAssertEqual(queue.replay().map(\.kind), ["submitted", "progress"])
        XCTAssertFalse(queue.replay().contains { $0.seq == 2 })
    }

    func testReplayQueueSurvivesPersistenceAndDoesNotRequeueAcked() {
        let store = InMemoryUplinkQueuePersistence()
        let queue = UplinkReplayQueue(persistence: store)
        let kept = queue.enqueue(
            idempotencyKey: "keep", taskId: "t", kind: "progress",
            actor: "a", at: 2, payload: ["text": "还在"])
        let done = queue.enqueue(
            idempotencyKey: "done", taskId: "t", kind: "claimed",
            actor: "a", at: 1, payload: ["text": "领过"])
        queue.acknowledge(taskId: done.taskId, seq: done.seq)
        let restored = UplinkReplayQueue(persistence: store)
        XCTAssertEqual(restored.replay().map(\.seq), [kept.seq])
        XCTAssertEqual(restored.replay().first?.kind, "progress")
        let again = restored.enqueue(
            idempotencyKey: "done", taskId: "t", kind: "claimed",
            actor: "a", at: 1, payload: ["text": "领过"])
        XCTAssertEqual(again.seq, done.seq)
        XCTAssertEqual(restored.pendingCount, 1)
        XCTAssertEqual(
            SeatUplinkBackoff.delay(attempt: 0), 1)
        XCTAssertEqual(SeatUplinkBackoff.delay(attempt: 1), 2)
        XCTAssertEqual(SeatUplinkBackoff.delay(attempt: 2), 4)
        XCTAssertEqual(SeatUplinkBackoff.delay(attempt: 5), 32)
        XCTAssertEqual(SeatUplinkBackoff.delay(attempt: 6), 60)
        XCTAssertEqual(SeatUplinkBackoff.delay(attempt: 12), 60)
    }

    // MARK: - 客户端：登记、心跳、退避、落单、补发

    func testConnectSendsSeatRegisterWithoutTokenAndPingsEvery30Seconds() throws {
        let service = newService()
        let harness = Harness(service: service)
        harness.client.start()
        XCTAssertEqual(harness.socket.connectCount, 1)
        XCTAssertEqual(harness.socket.lastToken, "test-token")
        XCTAssertEqual(
            harness.socket.lastURL?.absoluteString,
            "ws://127.0.0.1:9/api/open/v1/seat")
        let register = try jsonObject(try XCTUnwrap(harness.socket.sent.first))
        XCTAssertEqual(register["type"] as? String, "seat_register")
        XCTAssertEqual(register["platform"] as? String, "ios")
        XCTAssertEqual(register["agent_id"] as? String, LocalPlatform.appAgentID)
        XCTAssertEqual(register["channels"] as? [String], [soleChannelId(service)])
        XCTAssertNil(register["token"])
        XCTAssertFalse(harness.socket.sent.joined().contains("test-token"))
        harness.scheduler.advance(by: 29)
        XCTAssertEqual(harness.socket.pings, 0)
        harness.scheduler.advance(by: 1)
        XCTAssertEqual(harness.socket.pings, 1)
        harness.scheduler.advance(by: 30)
        XCTAssertEqual(harness.socket.pings, 2)
        XCTAssertEqual(harness.socket.sent.count, 1)
    }

    func testReconnectBackoffGrowsFromOneSecondToSixtyAndResetsAfterOpen() {
        let harness = Harness(service: newService())
        harness.socket.autoOpen = false
        harness.client.start()
        XCTAssertEqual(harness.socket.connectCount, 1)
        let expected: [TimeInterval] = [1, 2, 4, 8, 16, 32, 60, 60]
        for delay in expected {
            harness.socket.drop()
            XCTAssertEqual(harness.scheduler.lastDelay, delay)
            harness.scheduler.advance(by: delay)
        }
        XCTAssertEqual(harness.socket.connectCount, 1 + expected.count)
        harness.socket.open()
        harness.socket.drop()
        XCTAssertEqual(harness.scheduler.lastDelay, 1)
    }

    func testDispatchLandsWithSameTaskIdThenAckAndIgnoresDuplicate() throws {
        let service = newService()
        let harness = Harness(service: service)
        harness.client.start()
        let cloudId = "c5335253a0c94edcb2107828311b7664"
        harness.socket.receive("""
        {"type":"task_dispatch","task":{"task_id":"\(cloudId)","title":"三期 iOS","goal":"做上行","state":"dispatched","project_id":"phase3-ios","channel_id":"cloud-chan","max_rounds":3}}
        """)
        let task = try taskView(service, cloudId)
        XCTAssertEqual(task["task_id"] as? String, cloudId)
        XCTAssertEqual(task["state"] as? String, "queued")
        XCTAssertEqual(task["assignee_id"] as? String, LocalPlatform.appAgentID)
        XCTAssertEqual(task["channel_id"] as? String, soleChannelId(service))
        XCTAssertNotEqual(task["channel_id"] as? String, "cloud-chan")
        let acks = try harness.frames().filter { $0["type"] as? String == "task_ack" }
        XCTAssertEqual(acks.count, 1)
        XCTAssertEqual(acks.first?["task_id"] as? String, cloudId)
        XCTAssertTrue(try harness.frames().filter { $0["type"] as? String == "task_event" }.isEmpty)

        harness.socket.receive("""
        {"type":"task_dispatch","task":{"task_id":"\(cloudId)","title":"另一份","goal":"别覆盖","state":"claimed"}}
        """)
        let again = try taskView(service, cloudId)
        XCTAssertEqual(again["title"] as? String, "三期 iOS")
        XCTAssertEqual(again["state"] as? String, "queued")
        XCTAssertEqual(try harness.frames().filter { $0["type"] as? String == "task_ack" }.count, 2)

        let before = harness.socket.sent.count
        harness.socket.receive("{\"type\":\"task_dispatch\",\"task\":{\"goal\":\"没有 id\"}}")
        harness.socket.receive("not-json")
        XCTAssertEqual(harness.socket.sent.count, before)
        XCTAssertThrowsError(try service.ingestCloudDispatch(task: ["goal": "没有 id"]))
    }

    func testLongGoalIsTruncatedAndStateStaysQueued() throws {
        let service = newService()
        let goal = String(repeating: "字", count: TaskDispatchSpec.maxGoalChars + 20)
        let receipt = try service.ingestCloudDispatch(task: [
            "task_id": "long-1",
            "goal": goal,
            "state": "dispatched",
        ])
        XCTAssertTrue(receipt.created)
        let task = try taskView(service, "long-1")
        XCTAssertEqual((task["goal"] as? String)?.count, TaskDispatchSpec.maxGoalChars)
        XCTAssertEqual(task["state"] as? String, "queued")
        let again = try service.ingestCloudDispatch(task: ["task_id": "long-1", "goal": "短"])
        XCTAssertFalse(again.created)
        XCTAssertEqual(try taskView(service, "long-1")["goal"] as? String, task["goal"] as? String)
    }

    func testOfflineEventsReplayInOrderUntilAcked() throws {
        let service = newService()
        let harness = Harness(service: service)
        harness.client.start()
        let cloudId = "task-replay-1"
        harness.socket.receive("""
        {"type":"task_dispatch","task":{"task_id":"\(cloudId)","goal":"回流","title":"回流"}}
        """)
        harness.socket.drop()
        let sentWhileDown = harness.socket.sent.count
        _ = try service.dispatch(
            tool: "claim_task", args: ["task_id": cloudId],
            callerId: LocalPlatform.appAgentID, callerName: LocalPlatform.appAgentName)
        _ = try service.dispatch(
            tool: "report_progress", args: ["task_id": cloudId, "text": "做到一半"],
            callerId: LocalPlatform.appAgentID, callerName: LocalPlatform.appAgentName)
        XCTAssertEqual(harness.socket.sent.count, sentWhileDown)
        XCTAssertEqual(harness.queue.pendingCount, 2)
        let queued = harness.queue.replay()
        harness.client.recordLedgerEvents(queued.map {
            LedgerUplinkEvent(seq: $0.seq, taskId: $0.taskId, kind: $0.kind,
                              actor: $0.actor, at: $0.at, text: ($0.payload["text"] as? String) ?? "")
        })
        XCTAssertEqual(harness.queue.pendingCount, 2)

        harness.scheduler.advance(by: 1)
        var events = try eventFrames(harness, after: sentWhileDown)
        XCTAssertEqual(events.map { $0["kind"] as? String }, ["claimed", "progress"])
        let seqs = events.compactMap { jsonInt64($0["seq"]) }
        XCTAssertEqual(seqs.count, 2)
        XCTAssertLessThan(seqs[0], seqs[1])
        XCTAssertEqual(events.map { $0["task_id"] as? String }, [cloudId, cloudId])
        XCTAssertNotNil((events[0]["payload"] as? [String: Any])?["text"] as? String)

        harness.socket.drop()
        let mid = harness.socket.sent.count
        harness.scheduler.advance(by: 1)
        events = try eventFrames(harness, after: mid)
        XCTAssertEqual(events.map { $0["kind"] as? String }, ["claimed", "progress"])

        let firstSeq = seqs[0]
        harness.socket.receive("""
        {"type":"task_event_ack","task_id":"\(cloudId)","seq":\(firstSeq)}
        """)
        XCTAssertEqual(harness.queue.pendingCount, 1)
        harness.socket.drop()
        let late = harness.socket.sent.count
        harness.scheduler.advance(by: 1)
        events = try eventFrames(harness, after: late)
        XCTAssertEqual(events.map { $0["kind"] as? String }, ["progress"])
        XCTAssertEqual(jsonInt64(events[0]["seq"]), seqs[1])
    }

    private func taskView(_ service: LocalChannelService, _ taskId: String) throws -> [String: Any] {
        let result = try service.dispatch(
            tool: "get_task", args: ["task_id": taskId],
            callerId: LocalPlatform.appAgentID, callerName: LocalPlatform.appAgentName)
        return try XCTUnwrap(result["task"] as? [String: Any])
    }

    private func eventFrames(_ harness: Harness, after index: Int) throws -> [[String: Any]] {
        let tail = harness.socket.sent.dropFirst(max(0, index))
        return try tail.map { try jsonObject($0) }.filter { $0["type"] as? String == "task_event" }
    }
}

private final class FakeSeatSocket: SeatUplinkSocket {
    var onOpen: (() -> Void)?
    var onText: ((String) -> Void)?
    var onDisconnect: (() -> Void)?
    var sent: [String] = []
    var pings = 0
    var connectCount = 0
    var lastToken = ""
    var lastURL: URL?
    var autoOpen = true

    func connect(url: URL, bearerToken: String) {
        connectCount += 1
        lastToken = bearerToken
        lastURL = url
        if autoOpen { onOpen?() }
    }

    func send(_ text: String) { sent.append(text) }
    func ping() { pings += 1 }
    func disconnect() {}
    func receive(_ text: String) { onText?(text) }
    func drop() { onDisconnect?() }
    func open() { onOpen?() }
}

private final class ManualSeatScheduler: SeatUplinkScheduling {
    struct Item {
        let id: Int
        let due: TimeInterval
        let work: () -> Void
    }

    var now: TimeInterval = 0
    var lastDelay: TimeInterval = -1
    private var items: [Item] = []
    private var nextId = 0

    func schedule(after seconds: TimeInterval, _ work: @escaping () -> Void) -> Any {
        lastDelay = seconds
        nextId += 1
        let id = nextId
        items.append(Item(id: id, due: now + seconds, work: work))
        return id
    }

    func cancel(_ token: Any) {
        guard let id = token as? Int else { return }
        items.removeAll { $0.id == id }
    }

    func advance(by delta: TimeInterval) {
        let target = now + delta
        while true {
            guard let index = items.indices.min(by: { items[$0].due < items[$1].due }) else { break }
            let item = items[index]
            if item.due > target + 0.000_000_1 { break }
            now = item.due
            items.remove(at: index)
            item.work()
        }
        now = target
    }
}

private final class Harness {
    let socket = FakeSeatSocket()
    let scheduler = ManualSeatScheduler()
    let queue = UplinkReplayQueue()
    let client: SeatUplinkClient

    func frames() throws -> [[String: Any]] {
        try socket.sent.map { text in
            let parsed = try JSONSerialization.jsonObject(with: Data(text.utf8))
            return try XCTUnwrap(parsed as? [String: Any])
        }
    }

    init(service: LocalChannelService) {
        let url = URL(string: "ws://127.0.0.1:9/api/open/v1/seat")!
        client = SeatUplinkClient(
            config: SeatUplinkConfig(
                url: url,
                bearerToken: "test-token",
                appInstanceId: "instance-test",
                appVersion: "0.5.1",
                channelIds: { [weak service] in service?.localChannelIds() ?? [] }),
            socket: socket,
            queue: queue,
            scheduler: scheduler)
        service.taskUplink = client
        client.onTaskDispatch = { [weak service] task in
            guard let service else { return nil }
            return (try? service.ingestCloudDispatch(task: task))?.taskId
        }
    }
}
