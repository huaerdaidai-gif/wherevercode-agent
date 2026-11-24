// WhereverCode Agent — iOS
//
// 任务派工对拍夹具执行器（W2）：把 contracts/task-dispatch/fixtures/
// 逐份喂给本机 TaskDispatchService，按 spec §8 的口径核对——
// result 子集比对、error 与 state_after 精确比对、时间戳 "<ts>"
// 通配、任务 id 按创建顺序归一为 TASK_n。与安卓执行器同口径。
//
// 夹具按落地包逐步启用（enabled）：未启用的整份跳过（XCTSkip），
// 不是通过——W15 前必须全部启用。

import Foundation
import XCTest

@testable import WhereverCodeAgent

final class TaskDispatchFixtureTests: XCTestCase {
    private static let baseTs: Double = 1_700_000_000

    /// 已启用的夹具（按 W 包推进逐步加满，W15 前清空跳过）。
    private static let enabled: Set<String> = [
        "a01-create-and-list",
        "a02-claim-flow",
        "a03-fencing",
        "a04-progress-heartbeat-release",
        "a05-submit-evidence-gate",
        "a06-submit-review-approve",
        "a07-rework-rounds",
        "a08-decide-cancel",
        "a09-lease-expiry",
        "a10-pack-flow",
        "a11-pack-timeout-skip",
        "a12-submit-state-guards",
        "a13-report-status",
        "a14-release-review",
        "a15-reservation-expiry",
        "b01-update-task",
        "b02-withdraw-claimed",
        "b02-withdraw-queued",
        "b02-withdraw-reviewing",
        "b03-reopen-task",
        "b06-role-exposure",
        "c01-adversarial-delivery",
        "c02-adversarial-basics",
        "c03-adversarial-pages",
    ]

    /// 录制时的显示名（recorder 口径）：directory 与 callerName 都用它。
    private static let displayNames: [String: String] = [
        "boss": "Boss",
        "dev1": "DevA",
        "dev2": "DevB",
        "rev1": "RevA",
        "pack1": "PackA",
    ]

    private func fixturesDir() -> URL {
        var url = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let candidate = url.appendingPathComponent("contracts/task-dispatch/fixtures")
            var isDir: ObjCBool = false
            if FileManager.default.fileExists(atPath: candidate.path, isDirectory: &isDir), isDir.boolValue {
                return candidate
            }
            url = url.deletingLastPathComponent()
        }
        XCTFail("找不到 contracts/task-dispatch/fixtures（#file=\(#filePath)）")
        return URL(fileURLWithPath: "/nonexistent")
    }

    private func loadFixtures() -> [[String: Any]] {
        let dir = fixturesDir()
        let files = (try? FileManager.default.contentsOfDirectory(atPath: dir.path))?
            .filter { $0.hasSuffix(".json") }.sorted() ?? []
        return files.compactMap { name in
            guard let data = try? Data(contentsOf: dir.appendingPathComponent(name)),
                  let parsed = try? JSONSerialization.jsonObject(with: data),
                  let object = parsed as? [String: Any] else { return nil }
            return object
        }
    }

    func testEnabledFixturesPass() throws {
        let fixtures = loadFixtures()
        XCTAssertFalse(fixtures.isEmpty, "夹具目录为空")
        var ran = 0
        var failures: [String] = []
        for fixture in fixtures {
            guard let name = fixture["name"] as? String, Self.enabled.contains(name) else { continue }
            ran += 1
            do {
                try runFixture(fixture)
            } catch let error as FixtureFailure {
                failures.append("\(name): \(error.message)")
            }
        }
        XCTAssertGreaterThan(ran, 0, "没有启用任何夹具")
        if !failures.isEmpty {
            XCTFail("夹具失败 \(failures.count) 份:\n" + failures.joined(separator: "\n"))
        }
    }

    func testDisabledFixturesAreSkippedNotPassed() throws {
        let total = loadFixtures().count
        try XCTSkipIf(Self.enabled.count >= total, "全部夹具已启用")
        throw XCTSkip("仍有 \(total - Self.enabled.count) 份夹具未启用（按 W 包逐步开启）")
    }

    private struct FixtureFailure: Error { let message: String }

    private func check(_ condition: Bool, _ message: String) throws {
        if !condition { throw FixtureFailure(message: message) }
    }

    // ---- 执行 ------------------------------------------------------------

    private func runFixture(_ fixture: [String: Any]) throws {
        let seed = fixture["seed"] as? [String: Any] ?? [:]
        var scopes: [String: String] = [:]
        var names: [String: String] = [:]
        var rolesById: [String: [String]] = [:]
        var seedOrder: [String] = []
        for agent in (seed["agents"] as? [[String: Any]]) ?? [] {
            guard let id = agent["id"] as? String else { continue }
            names[id] = Self.displayNames[id] ?? id
            seedOrder.append(id)
            let scopeList = (agent["scopes"] as? [Any])?.compactMap { $0 as? String } ?? []
            scopes[id] = scopeList.contains("owner")
                ? TaskDispatchService.scopeOwner : TaskDispatchService.scopeAgent
            rolesById[id] = (agent["roles"] as? [Any])?.compactMap { $0 as? String } ?? []
        }
        struct FixtureRoster: TaskAgentRoster {
            let rolesById: [String: [String]]
            let seedOrder: [String]
            let names: [String: String]
            func agentRoles(_ agentId: String) -> [String]? { rolesById[agentId] }
            func firstAgent(withRole role: String) -> (id: String, name: String)? {
                guard let id = seedOrder.first(where: { rolesById[$0]?.contains(role) == true })
                else { return nil }
                return (id: id, name: names[id] ?? id)
            }
        }
        var now = Self.baseTs
        let store = TaskDispatchStore(persistence: InMemoryTaskDispatchPersistence(), clock: { now })
        let service = TaskDispatchService(
            store: store,
            directory: { id in names[id] },
            bridge: nil,
            clock: { now },
            leaseSeconds: (seed["lease_seconds"] as? NSNumber)?.doubleValue ?? TaskDispatchSpec.leaseSeconds,
            roster: FixtureRoster(rolesById: rolesById, seedOrder: seedOrder, names: names)
        )
        let recMap = recorderIdMap(fixture)
        let steps = (fixture["steps"] as? [[String: Any]]) ?? []
        for (index, step) in steps.enumerated() {
            if let advance = (step["advance_clock_seconds"] as? NSNumber)?.doubleValue {
                // 纯推进时钟步：没有调用，只核对推进后的状态快照
                // （清扫是懒触发的，推进本身不改账本）。
                now += advance
                let advanceIdMap = buildIdMap(store)
                if let mismatch = exactMismatch(normalizeNumbers(step["state_after"]),
                                                snapshotState(store, idMap: advanceIdMap),
                                                path: "state_after") {
                    throw FixtureFailure(message: "step \(index + 1) (advance_clock) \(mismatch)")
                }
                continue
            }
            let call = step["call"] as? [String: Any] ?? [:]
            let actor = call["actor"] as? String ?? ""
            let tool = call["tool"] as? String ?? ""
            let rawArgs = call["args"] as? [String: Any] ?? [:]
            let args = substituteArgs(rawArgs, store: store) as? [String: Any] ?? [:]
            var result: [String: Any]?
            var thrown: TaskDispatchError?
            do {
                if tool == "tools/list" {
                    result = ["tools": TaskDispatchService.toolNames(callerScope: scopes[actor] ?? "agent")]
                } else if tool == "ui_delete_task" {
                    result = try service.uiDeleteTask(args: args, callerId: actor)
                } else {
                    result = try service.dispatch(tool: tool, args: args, callerId: actor,
                                                  callerName: names[actor] ?? actor,
                                                  callerScope: scopes[actor] ?? "agent")
                }
            } catch let error as TaskDispatchError {
                thrown = error
            }
            let idMap = buildIdMap(store)
            let expect = step["expect"] as? [String: Any] ?? [:]
            let whereabouts = "step \(index + 1) (\(actor) \(tool))"
            if let expectedError = expect["error"] as? [String: Any] {
                try check(thrown != nil, "\(whereabouts) 应报错 \(expectedError["code"] ?? "?")，实际成功: \(String(describing: result))")
                try check((expectedError["status"] as? NSNumber)?.intValue == thrown!.status,
                          "\(whereabouts) error.status 不符：期望 \(expectedError["status"] ?? "?")，实际 \(thrown!.status)")
                try check(expectedError["code"] as? String == thrown!.code,
                          "\(whereabouts) error.code 不符：期望 \(expectedError["code"] ?? "?")，实际 \(thrown!.code)")
                try check(expectedError["message"] as? String == thrown!.message,
                          "\(whereabouts) error.message 不符：期望 \(expectedError["message"] ?? "?")，实际 \(thrown!.message)")
            } else {
                try check(thrown == nil, "\(whereabouts) 应成功，实际报错 \(thrown?.code ?? "?"): \(thrown?.message ?? "")")
                let expectedResult = normalize(normalizeNumbers(expect["result"]), idMap: recMap)
                let actualResult = normalize(result, idMap: idMap)
                if let mismatch = subsetMismatch(expectedResult, actualResult, path: "result") {
                    throw FixtureFailure(message: "\(whereabouts) \(mismatch)")
                }
            }
            let expectedState = normalizeNumbers(step["state_after"])
            let actualState = snapshotState(store, idMap: idMap)
            if let mismatch = exactMismatch(expectedState, actualState, path: "state_after") {
                throw FixtureFailure(message: "\(whereabouts) \(mismatch)")
            }
        }
    }

    private func buildIdMap(_ store: TaskDispatchStore) -> [String: String] {
        var map: [String: String] = [:]
        for (index, taskId) in store.taskOrder.enumerated() {
            map[taskId] = "TASK_\(index + 1)"
        }
        return map
    }

    private func snapshotState(_ store: TaskDispatchStore, idMap: [String: String]) -> [Any] {
        store.orderedTasks.map { task -> [String: Any] in
            [
                "ref": idMap[task.taskId] ?? task.taskId,
                "kind": task.kind,
                "parent": task.parentId.map { idMap[$0] ?? $0 } ?? NSNull(),
                "state": task.state,
                "round": task.round,
                "version": task.version,
                "assignee_id": task.assigneeId,
                "claimed_by_id": task.claimedById ?? NSNull(),
                "reserved_for_id": task.reservedForId ?? NSNull(),
                "reviewer_id": task.reviewerId ?? NSNull(),
                "decision_reason": task.decisionReason ?? NSNull(),
                "verdict": task.verdict ?? NSNull(),
                "delivery_pages": task.deliveryPages,
                "delivery_total_pages": task.deliveryTotalPages,
                "events": store.eventsOf(task.taskId).map { $0.kind },
                "channel_id": task.channelId ?? NSNull(),
                "epoch": task.epoch,
                "deleted": task.deletedAt != nil,
            ]
        }
    }

    /// 期望侧的录制 id 映射：扫全部步骤期望结果里恰好 32 位十六进制的
    /// 字符串（任务 id 的形状；commit SHA 是 40 位或短哈希，不会误伤），
    /// 按首次出现顺序编号 TASK_n —— 录制时任务的创建顺序即首次出现顺序。
    private func recorderIdMap(_ fixture: [String: Any]) -> [String: String] {
        var map: [String: String] = [:]
        func visit(_ value: Any?) {
            guard let value else { return }
            if let dict = value as? [String: Any] {
                for v in dict.values { visit(v) }
            } else if let list = value as? [Any] {
                for v in list { visit(v) }
            } else if let string = value as? String {
                let candidate = string.hasPrefix("v5:") ? String(string.dropFirst(3)) : string
                if candidate.count == 32,
                   candidate.allSatisfy({ "0123456789abcdef".contains($0) }),
                   map[candidate] == nil {
                    map[candidate] = "TASK_\(map.count + 1)"
                }
            }
        }
        for step in (fixture["steps"] as? [[String: Any]]) ?? [] {
            if let expect = step["expect"] as? [String: Any], expect["result"] != nil {
                visit(expect["result"])
            }
        }
        return map
    }

    /// 调用参数里的 TASK_n 占位换成账本里的真 id（按创建顺序）。
    private func substituteArgs(_ value: Any?, store: TaskDispatchStore) -> Any? {
        guard let value else { return nil }
        if value is NSNull { return NSNull() }
        if let dict = value as? [String: Any] {
            var out: [String: Any] = [:]
            for (k, v) in dict { out[k] = substituteArgs(v, store: store) ?? NSNull() }
            return out
        }
        if let list = value as? [Any] {
            return list.map { substituteArgs($0, store: store) ?? NSNull() }
        }
        if let string = value as? String {
            if string.hasPrefix("TASK_") {
                let rest = String(string.dropFirst(5))
                if !rest.isEmpty, rest.allSatisfy({ $0.isNumber }),
                   let n = Int(rest), n >= 1, n <= store.taskOrder.count {
                    return store.taskOrder[n - 1]
                }
            }
            return string
        }
        return value
    }

    // ---- 归一与比对 --------------------------------------------------------

    /// JSON 数字统一成 Double；Bool 保持 Bool（NSNumber 两者都装，要分开）。
    private func normalizeNumbers(_ value: Any?) -> Any? {
        guard let value else { return nil }
        if value is NSNull { return NSNull() }
        if let dict = value as? [String: Any] {
            var out: [String: Any] = [:]
            for (k, v) in dict { out[k] = normalizeNumbers(v) ?? NSNull() }
            return out
        }
        if let list = value as? [Any] {
            return list.map { normalizeNumbers($0) ?? NSNull() }
        }
        if let number = value as? NSNumber {
            if CFGetTypeID(number) == CFBooleanGetTypeID() { return number.boolValue }
            return number.doubleValue
        }
        return value
    }

    private func normalize(_ value: Any?, idMap: [String: String]) -> Any? {
        guard let value else { return nil }
        if value is NSNull { return NSNull() }
        if let dict = value as? [String: Any] {
            var out: [String: Any] = [:]
            for (k, v) in dict { out[k] = normalize(v, idMap: idMap) ?? NSNull() }
            return out
        }
        if let list = value as? [Any] {
            return list.map { normalize($0, idMap: idMap) ?? NSNull() }
        }
        if let string = value as? String {
            if let mapped = idMap[string] { return mapped }
            if string.hasPrefix("v5:"), let mapped = idMap[String(string.dropFirst(3))] {
                return "v5:" + mapped
            }
            return string
        }
        if let number = value as? NSNumber {
            if CFGetTypeID(number) == CFBooleanGetTypeID() { return number.boolValue }
            return number.doubleValue
        }
        return value
    }

    private func isNull(_ value: Any?) -> Bool {
        value == nil || value is NSNull
    }

    private func scalarEquals(_ expected: Any?, _ actual: Any?) -> Bool {
        if isNull(expected) || isNull(actual) { return isNull(expected) && isNull(actual) }
        if let e = expected as? Bool, let a = actual as? Bool { return e == a }
        if let e = expected as? NSNumber, let a = actual as? NSNumber {
            return e.doubleValue == a.doubleValue
        }
        if let e = expected as? Double, let a = actual as? Double { return e == a }
        if let e = expected as? String, let a = actual as? String { return e == a }
        return false
    }

    private func subsetMismatch(_ expected: Any?, _ actual: Any?, path: String) -> String? {
        if let e = expected as? String, e == "<ts>" { return nil }
        if let e = expected as? [String: Any] {
            guard let a = actual as? [String: Any] else { return "\(path) 应为对象，实际 \(String(describing: actual))" }
            for (key, v) in e {
                if !a.keys.contains(key) {
                    if isNull(v) { continue }
                    return "\(path).\(key) 缺失（实际键 \(a.keys.sorted())）"
                }
                if let sub = subsetMismatch(v, a[key], path: "\(path).\(key)") { return sub }
            }
            return nil
        }
        if let e = expected as? [Any] {
            guard let a = actual as? [Any] else { return "\(path) 应为数组，实际 \(String(describing: actual))" }
            if e.count != a.count { return "\(path) 长度不符：期望 \(e.count)，实际 \(a.count)" }
            for i in e.indices {
                if let sub = subsetMismatch(e[i], a[i], path: "\(path)[\(i)]") { return sub }
            }
            return nil
        }
        return scalarEquals(expected, actual) ? nil
            : "\(path) 不符：期望 \(String(describing: expected))，实际 \(String(describing: actual))"
    }

    private func exactMismatch(_ expected: Any?, _ actual: Any?, path: String) -> String? {
        if let e = expected as? [String: Any] {
            guard let a = actual as? [String: Any] else { return "\(path) 应为对象，实际 \(String(describing: actual))" }
            for (key, v) in e {
                if let sub = exactMismatch(v, a[key], path: "\(path).\(key)") { return sub }
            }
            return nil
        }
        if let e = expected as? [Any] {
            guard let a = actual as? [Any] else { return "\(path) 应为数组，实际 \(String(describing: actual))" }
            if e.count != a.count { return "\(path) 长度不符：期望 \(e.count)，实际 \(a.count)" }
            for i in e.indices {
                if let sub = exactMismatch(e[i], a[i], path: "\(path)[\(i)]") { return sub }
            }
            return nil
        }
        return scalarEquals(expected, actual) ? nil
            : "\(path) 不符：期望 \(String(describing: expected))，实际 \(String(describing: actual))"
    }
}
