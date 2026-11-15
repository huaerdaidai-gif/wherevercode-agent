import XCTest
@testable import WhereverCodeAgent

final class TaskStateMachineTests: XCTestCase {
    func testTenCanonicalStatesAndPauseAsBoolean() {
        let expected = [
            "queued", "assigned", "working", "blocked", "review",
            "rework", "requeued", "completed", "failed", "cancelled"
        ]
        XCTAssertEqual(TaskStates.canonicalStates, expected)
        XCTAssertFalse(TaskStates.canonicalStates.contains("paused"), "pause 必须作为独立布尔标志位，不得成为第 11 个主状态")
    }

    func testValidAndInvalidTransitions() throws {
        XCTAssertNoThrow(try TaskStates.validateTransition(from: "queued", to: "assigned"))
        XCTAssertNoThrow(try TaskStates.validateTransition(from: "working", to: "review"))
        XCTAssertNoThrow(try TaskStates.validateTransition(from: "review", to: "rework"))
        XCTAssertNoThrow(try TaskStates.validateTransition(from: "review", to: "completed"))
        XCTAssertThrowsError(try TaskStates.validateTransition(from: "completed", to: "working"))
    }

    func testPausedRawStateMapsToWorkingWithIsPausedTrue() throws {
        let mapped = try TaskStates.mapToCanonical(system: "v5", rawState: "paused")
        XCTAssertEqual(mapped.state, "working")
        XCTAssertTrue(mapped.isPaused)
        XCTAssertTrue(mapped.needsAttention)
    }
}
