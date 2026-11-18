// WhereverCode Agent — iOS MVP tests
//
// Feed state machine and dedup: the reconnect policy from the
// Android ChannelFeed, verified as pure transitions without a socket.

import Foundation
import XCTest
@testable import WhereverCodeAgent

final class ChannelFeedTests: XCTestCase {

    func testStateMachineHappyPath() {
        var machine = FeedStateMachine()
        XCTAssertEqual(machine.state, .stopped)
        machine.apply(.started)
        XCTAssertEqual(machine.state, .connecting)
        machine.apply(.wsOpened)
        XCTAssertEqual(machine.state, .live)
        machine.apply(.wsClosed)
        XCTAssertEqual(machine.state, .polling)
        machine.apply(.reconnectDue)
        XCTAssertEqual(machine.state, .connecting)
        machine.apply(.wsOpened)
        XCTAssertEqual(machine.state, .live)
        machine.apply(.stopped)
        XCTAssertEqual(machine.state, .stopped)
    }

    func testStateMachineStartIsIdempotent() {
        var machine = FeedStateMachine()
        machine.apply(.started)
        machine.apply(.wsOpened)
        machine.apply(.started)
        XCTAssertEqual(machine.state, .live)
    }

    func testStateMachineCloseBeforeOpenFallsBackToPolling() {
        var machine = FeedStateMachine()
        machine.apply(.started)
        machine.apply(.wsClosed)
        XCTAssertEqual(machine.state, .polling)
    }

    func testStateMachineStopWinsFromAnyState() {
        for initial in [ChannelConnectionState.connecting, .live, .polling] {
            var machine = FeedStateMachine()
            machine.apply(.started)
            if initial == .live { machine.apply(.wsOpened) }
            if initial == .polling { machine.apply(.wsClosed) }
            XCTAssertEqual(machine.state, initial)
            machine.apply(.stopped)
            XCTAssertEqual(machine.state, .stopped)
        }
    }

    func testDeduperDropsRepeatsAndKeepsOrder() {
        var deduper = MessageDeduper()
        let first = ChannelMessage(id: "a", agentId: "", agentName: "A", text: "1")
        let second = ChannelMessage(id: "b", agentId: "", agentName: "B", text: "2")
        XCTAssertEqual(deduper.filterFresh([first, second]).map(\.id), ["a", "b"])
        XCTAssertEqual(deduper.filterFresh([first, second]), [])
        let third = ChannelMessage(id: "c", agentId: "", agentName: "C", text: "3")
        XCTAssertEqual(deduper.filterFresh([second, third]).map(\.id), ["c"])
        XCTAssertEqual(deduper.seenCount, 3)
    }

    func testDeduperCapsAt1000() {
        var deduper = MessageDeduper()
        let batch = (0..<1005).map {
            ChannelMessage(id: "m\($0)", agentId: "", agentName: "", text: "")
        }
        let fresh = deduper.filterFresh(batch)
        XCTAssertEqual(fresh.count, 1005)
        XCTAssertEqual(deduper.seenCount, 1000)
        // The oldest ids were evicted, so they count as fresh again.
        let oldest = ChannelMessage(id: "m0", agentId: "", agentName: "", text: "")
        XCTAssertEqual(deduper.filterFresh([oldest]).map(\.id), ["m0"])
        // A recent id is still remembered.
        let recent = ChannelMessage(id: "m1004", agentId: "", agentName: "", text: "")
        XCTAssertEqual(deduper.filterFresh([recent]), [])
    }

    func testConnectionStateLabels() {
        XCTAssertEqual(ChannelConnectionState.live.label, "实时")
        XCTAssertEqual(ChannelConnectionState.polling.label, "轮询兜底")
        XCTAssertEqual(ChannelConnectionState.connecting.label, "连接中")
        XCTAssertEqual(ChannelConnectionState.stopped.label, "未连接")
    }

    func testReservedFeaturesAlwaysThrow() {
        for feature in ReservedFeature.allCases {
            XCTAssertThrowsError(try CapabilityGate.require(feature)) { error in
                XCTAssertEqual(error as? FeatureError, .notImplemented(feature))
            }
        }
    }
}
