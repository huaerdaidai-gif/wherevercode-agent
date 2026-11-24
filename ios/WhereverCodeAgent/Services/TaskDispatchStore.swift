// WhereverCode Agent — iOS
//
// 任务派工的内存状态（spec §1）。与本地频道账本同一持久化风格：
// 状态全在内存、每次变更后整表快照落盘（体量小，快照写足够；
// 格式与安卓 TaskDispatchStore 逐字段一致）。
//
// 集合语义：
// - tasks：task_id -> 任务（含 review/pack 子任务，parent_id 关联），
//   taskOrder 保持创建顺序（夹具快照与列表排序都按它）
// - events：全局账本（seq 单调递增），按 task_id 过滤读取
// - deliveryPages：work 任务 id -> (page -> 页)；pack 子任务递的页
//   也落在父 work 名下（基线口径）
// - idempotency：键 "task_id|epoch|action|attempt_id" -> 首次结果
//   的 JSON 文本（epoch 参与键，reopen 后旧 attempt 不再命中）
// - statusReports：agent_id -> 额度报告（report_status 落点）

import Foundation

/// 任务派工账本的持久化口：生产实现写 App 私有文件，测试用内存实现。
public protocol TaskDispatchPersistence {
    func load() -> String?
    func save(_ text: String)
}

/// 内存持久化（测试与无文件环境兜底；进程退出即丢，只用于这两处）。
public final class InMemoryTaskDispatchPersistence: TaskDispatchPersistence {
    public var stored: String?
    public init() {}
    public func load() -> String? { stored }
    public func save(_ text: String) { stored = text }
}

public final class TaskDispatchStore {
    public var tasks: [String: TaskRecord] = [:]
    public var taskOrder: [String] = []
    public var events: [TaskEventRecord] = []
    public var deliveryPages: [String: [Int: DeliveryPageRecord]] = [:]
    public var idempotency: [String: String] = [:]
    public var statusReports: [String: AgentStatusRecord] = [:]
    public var eventSeq: Int64 = 0

    private let persistence: TaskDispatchPersistence
    let clock: () -> Double

    public init(persistence: TaskDispatchPersistence, clock: @escaping () -> Double) {
        self.persistence = persistence
        self.clock = clock
        restore()
    }

    /// 创建顺序的任务列表（字典本身无序，顺序只认 taskOrder）。
    public var orderedTasks: [TaskRecord] { taskOrder.compactMap { tasks[$0] } }

    public func putTask(_ task: TaskRecord) {
        if tasks[task.taskId] == nil { taskOrder.append(task.taskId) }
        tasks[task.taskId] = task
    }

    /// 追加一条账本事件（seq 在这里统一铸造，调用方不自带）。
    public func appendEvent(taskId: String, at: Double, who: String?, kind: String, text: String) {
        eventSeq += 1
        events.append(TaskEventRecord(seq: eventSeq, taskId: taskId, at: at, who: who, kind: kind, text: text))
    }

    public func eventsOf(_ taskId: String) -> [TaskEventRecord] { events.filter { $0.taskId == taskId } }

    // ---- 快照 ------------------------------------------------------------

    public func persist() {
        persistence.save(snapshotText())
    }

    /// 当前内存状态的快照文本（服务层失败回滚用，与落盘格式同一份）。
    public func snapshotText() -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: snapshotObject()),
              let text = String(data: data, encoding: .utf8) else { return "{}" }
        return text
    }

    private func restore() {
        guard let text = persistence.load() else { return }
        restoreFromText(text)
    }

    /// 从快照文本整体恢复内存状态（回滚时事件序号一并回到快照点）。
    public func restoreFromText(_ text: String) {
        guard let data = text.data(using: .utf8),
              let parsed = try? JSONSerialization.jsonObject(with: data),
              let root = parsed as? [String: Any] else { return }
        tasks = [:]
        taskOrder = []
        for item in (root["tasks"] as? [[String: Any]]) ?? [] {
            if let record = Self.taskFromObject(item) {
                tasks[record.taskId] = record
                taskOrder.append(record.taskId)
            }
        }
        events = ((root["events"] as? [[String: Any]]) ?? []).map { e in
            TaskEventRecord(
                seq: (e["seq"] as? NSNumber)?.int64Value ?? 0,
                taskId: (e["task_id"] as? String) ?? "",
                at: (e["at"] as? NSNumber)?.doubleValue ?? 0,
                who: e["who"] as? String,
                kind: (e["kind"] as? String) ?? "",
                text: (e["text"] as? String) ?? ""
            )
        }
        eventSeq = (root["event_seq"] as? NSNumber)?.int64Value ?? Int64(events.count)
        deliveryPages = [:]
        for (taskId, pages) in (root["delivery_pages"] as? [String: [[String: Any]]]) ?? [:] {
            var map: [Int: DeliveryPageRecord] = [:]
            for p in pages {
                let record = DeliveryPageRecord(
                    page: (p["page"] as? NSNumber)?.intValue ?? 0,
                    totalPages: (p["total_pages"] as? NSNumber)?.intValue ?? 0,
                    text: (p["text"] as? String) ?? ""
                )
                map[record.page] = record
            }
            deliveryPages[taskId] = map
        }
        idempotency = (root["idempotency"] as? [String: String]) ?? [:]
        statusReports = [:]
        for (agentId, r) in (root["status_reports"] as? [String: [String: Any]]) ?? [:] {
            statusReports[agentId] = AgentStatusRecord(
                quotaText: r["quota_text"] as? String,
                quotaResetAt: r["quota_reset_at"] as? String,
                reportedAt: (r["reported_at"] as? NSNumber)?.doubleValue ?? 0
            )
        }
    }

    private func snapshotObject() -> [String: Any] {
        var root: [String: Any] = [:]
        root["version"] = 1
        root["event_seq"] = eventSeq
        root["tasks"] = orderedTasks.map { Self.taskToObject($0) }
        root["events"] = events.map { e -> [String: Any] in
            ["seq": e.seq, "task_id": e.taskId, "at": e.at,
             "who": e.who ?? NSNull(), "kind": e.kind, "text": e.text]
        }
        var pages: [String: Any] = [:]
        for (taskId, map) in deliveryPages {
            pages[taskId] = map.values.sorted { $0.page < $1.page }.map { p -> [String: Any] in
                ["page": p.page, "total_pages": p.totalPages, "text": p.text]
            }
        }
        root["delivery_pages"] = pages
        root["idempotency"] = idempotency
        var reports: [String: Any] = [:]
        for (agentId, report) in statusReports {
            reports[agentId] = [
                "quota_text": report.quotaText ?? NSNull(),
                "quota_reset_at": report.quotaResetAt ?? NSNull(),
                "reported_at": report.reportedAt,
            ] as [String: Any]
        }
        root["status_reports"] = reports
        return root
    }

    public static func taskToObject(_ task: TaskRecord) -> [String: Any] {
        func opt(_ value: Any?) -> Any { value ?? NSNull() }
        return [
            "task_id": task.taskId,
            "project_id": task.projectId,
            "kind": task.kind,
            "parent_id": opt(task.parentId),
            "assignee_id": task.assigneeId,
            "assignee_name": task.assigneeName,
            "reviewer_id": opt(task.reviewerId),
            "reviewer_name": opt(task.reviewerName),
            "title": task.title,
            "goal": task.goal,
            "state": task.state,
            "round": task.round,
            "max_rounds": task.maxRounds,
            "lease_until": opt(task.leaseUntil),
            "summary": opt(task.summary),
            "branch": opt(task.branch),
            "commit": opt(task.commitSha),
            "pr_url": opt(task.prUrl),
            "verdict": opt(task.verdict),
            "feedback": opt(task.feedback),
            "decision_reason": opt(task.decisionReason),
            "claimed_by_id": opt(task.claimedById),
            "claimed_by_name": opt(task.claimedByName),
            "reserved_for_id": opt(task.reservedForId),
            "reserved_until": opt(task.reservedUntil),
            "delivery_total_pages": task.deliveryTotalPages,
            "delivery_pages": task.deliveryPages,
            "delivery_truncated": task.deliveryTruncated,
            "delivery_files": opt(task.deliveryFiles),
            "version": task.version,
            "attempt": task.attempt,
            "attempt_id": opt(task.attemptId),
            "channel_id": opt(task.channelId),
            "epoch": task.epoch,
            "created_by": opt(task.createdBy),
            "deleted_at": opt(task.deletedAt),
            "deleted_by": opt(task.deletedBy),
            "created_at": task.createdAt,
            "updated_at": task.updatedAt,
        ]
    }

    public static func taskFromObject(_ o: [String: Any]) -> TaskRecord? {
        guard let taskId = o["task_id"] as? String else { return nil }
        func str(_ key: String) -> String? { o[key] as? String }
        func dbl(_ key: String) -> Double? { (o[key] as? NSNumber)?.doubleValue }
        func int(_ key: String, _ fallback: Int = 0) -> Int {
            (o[key] as? NSNumber)?.intValue ?? fallback
        }
        let record = TaskRecord(
            taskId: taskId,
            projectId: str("project_id") ?? "",
            kind: str("kind") ?? "work",
            parentId: str("parent_id"),
            assigneeId: str("assignee_id") ?? "",
            assigneeName: str("assignee_name") ?? "",
            reviewerId: str("reviewer_id"),
            reviewerName: str("reviewer_name"),
            title: str("title") ?? "",
            goal: str("goal") ?? "",
            state: str("state") ?? TaskDispatchSpec.queued,
            round: int("round"),
            maxRounds: int("max_rounds"),
            leaseUntil: dbl("lease_until"),
            summary: str("summary"),
            branch: str("branch"),
            commitSha: str("commit"),
            prUrl: str("pr_url"),
            verdict: str("verdict"),
            feedback: str("feedback"),
            decisionReason: str("decision_reason"),
            claimedById: str("claimed_by_id"),
            claimedByName: str("claimed_by_name"),
            reservedForId: str("reserved_for_id"),
            reservedUntil: dbl("reserved_until"),
            createdAt: dbl("created_at") ?? 0,
            updatedAt: dbl("updated_at") ?? 0
        )
        record.deliveryTotalPages = int("delivery_total_pages")
        record.deliveryPages = int("delivery_pages")
        record.deliveryTruncated = (o["delivery_truncated"] as? Bool) ?? false
        record.deliveryFiles = str("delivery_files")
        record.version = int("version", 1)
        record.attempt = int("attempt")
        record.attemptId = str("attempt_id")
        record.channelId = str("channel_id")
        record.epoch = int("epoch", 1)
        record.createdBy = str("created_by")
        record.deletedAt = dbl("deleted_at")
        record.deletedBy = str("deleted_by")
        return record
    }
}
