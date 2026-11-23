// WhereverCode Agent — iOS tests
//
// 内网成员令牌注册表（频道三层二期·内网层）：与安卓
// LanTokenRegistryTest 同一验收清单逐条对应——签发形状、鉴权往返、
// 撤销即刻生效、落盘恢复口径。断言口径与安卓一致，不放宽。

import Foundation
import XCTest
@testable import WhereverCodeAgent

final class LanTokenRegistryTests: XCTestCase {

    func testIssueShapeAndAuthenticateRoundtrip() {
        let registry = LanTokenRegistry()
        let entry = registry.issue(agentName: "  Mac  ")
        XCTAssertEqual(entry.agentName, "Mac")
        XCTAssertTrue(entry.agentId.hasPrefix("lan-"))
        XCTAssertEqual(entry.agentId.count, 10)
        XCTAssertEqual(entry.token.count, 32)
        XCTAssertTrue(entry.token.allSatisfy { $0.isHexDigit })
        XCTAssertEqual(registry.authenticate(token: entry.token)?.agentId, entry.agentId)
        XCTAssertEqual(registry.authenticate(token: " \(entry.token) ")?.agentName, "Mac")
        XCTAssertNil(registry.authenticate(token: "deadbeef"))
        XCTAssertNil(registry.authenticate(token: nil))
        XCTAssertNil(registry.authenticate(token: "  "))
        XCTAssertEqual(registry.list().count, 1)
    }

    func testBlankNameFallsBackToDefault() {
        let registry = LanTokenRegistry()
        XCTAssertEqual(registry.issue(agentName: "   ").agentName, "内网成员")
    }

    func testRevokeIsImmediateAndReportsExistence() {
        let registry = LanTokenRegistry()
        let entry = registry.issue(agentName: "Phone")
        XCTAssertTrue(registry.revoke(token: entry.token))
        XCTAssertNil(registry.authenticate(token: entry.token))
        XCTAssertFalse(registry.revoke(token: entry.token))
        XCTAssertTrue(registry.list().isEmpty)
    }

    func testPersistenceRestoreRoundtripAndSkipsBlankRows() {
        var saved: String?
        let first = LanTokenRegistry(load: { nil }, save: { saved = $0 })
        let kept = first.issue(agentName: "Kept")
        first.issue(agentName: "AlsoKept")
        let restored = LanTokenRegistry(load: { saved }, save: { _ in })
        XCTAssertEqual(restored.list().count, 2)
        XCTAssertEqual(restored.authenticate(token: kept.token)?.agentName, "Kept")

        let dirty = """
        {"entries":[
          {"token":"","agent_id":"lan-x","agent_name":"NoToken","created_at_ms":1},
          {"token":"abc","agent_id":"","agent_name":"NoId","created_at_ms":1},
          {"token":"def","agent_id":"lan-ok","agent_name":"","created_at_ms":5}
        ]}
        """
        let cleaned = LanTokenRegistry(load: { dirty }, save: { _ in })
        XCTAssertEqual(cleaned.list().count, 1)
        XCTAssertEqual(cleaned.list().first?.agentName, "lan-ok")
        XCTAssertEqual(cleaned.list().first?.createdAtMs, 5)
    }
}
