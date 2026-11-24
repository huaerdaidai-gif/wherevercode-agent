// WhereverCode Agent — iOS
//
// 任务派工（contracts/task-dispatch/spec.md v1）的数据模型与常量。
// 语义基线 = 线上 v5_platform.py：字段、状态名、常量逐项对齐，
// 对拍夹具在 contracts/task-dispatch/fixtures/。本文件只放模型，
// 行为在 TaskDispatchService / TaskDispatchStore。

import Foundation

/// 任务派工错误：status/code/message 三元组与基线 V5Error 对齐。
public struct TaskDispatchError: Error, Equatable {
    public let status: Int
    public let code: String
    public let message: String
    public init(_ message: String, status: Int = 409, code: String = "v5_error") {
        self.message = message
        self.status = status
        self.code = code
    }
}

public enum TaskDispatchSpec {
    public static let leaseSeconds: Double = 7200
    public static let packTimeoutSeconds: Double = 1800
    public static let maxGoalChars = 20_000
    public static let maxSummaryChars = 8_000
    public static let maxProgressChars = 2_000
    public static let maxPageChars = 65_536
    public static let maxDeliveryBytes = 2 * 1024 * 1024
    public static let maxDeliveryPages = 200
    public static let maxTitleChars = 100
    public static let maxQuotaTextChars = 500
    public static let maxQuotaResetChars = 120
    public static let defaultMaxRounds = 3

    public static let queued = "queued"
    public static let claimed = "claimed"
    public static let reviewing = "reviewing"
    public static let approved = "approved"
    public static let needsDecision = "needs_decision"
    public static let done = "done"
    public static let cancelled = "cancelled"

    public static let stateLabels: [String: String] = [
        queued: "等待接手",
        claimed: "进行中",
        reviewing: "待审核",
        approved: "审查通过",
        needsDecision: "等你决定",
        done: "完成",
        cancelled: "已取消",
    ]

    public static let terminalStates: Set<String> = [approved, done, cancelled]
    public static let taskKinds: Set<String> = ["work", "review", "pack"]
}

public final class TaskRecord {
    public let taskId: String
    public var projectId: String
    public var kind: String
    public var parentId: String?
    public var assigneeId: String
    public var assigneeName: String
    public var reviewerId: String?
    public var reviewerName: String?
    public var title: String
    public var goal: String
    public var state: String
    public var round: Int
    public var maxRounds: Int
    public var leaseUntil: Double?
    public var summary: String?
    public var branch: String?
    public var commitSha: String?
    public var prUrl: String?
    public var verdict: String?
    public var feedback: String?
    public var decisionReason: String?
    public var claimedById: String?
    public var claimedByName: String?
    public var reservedForId: String?
    public var reservedUntil: Double?
    public var deliveryTotalPages: Int = 0
    public var deliveryPages: Int = 0
    public var deliveryTruncated: Bool = false
    public var deliveryFiles: String?
    public var version: Int = 1
    public var attempt: Int = 0
    public var attemptId: String?
    public var channelId: String?
    public var epoch: Int = 1
    public var createdBy: String?
    public var deletedAt: Double?
    public var deletedBy: String?
    public let createdAt: Double
    public var updatedAt: Double

    public init(taskId: String, projectId: String, kind: String, parentId: String?,
                assigneeId: String, assigneeName: String, reviewerId: String?,
                reviewerName: String?, title: String, goal: String, state: String,
                round: Int, maxRounds: Int, leaseUntil: Double?, summary: String?,
                branch: String?, commitSha: String?, prUrl: String?, verdict: String?,
                feedback: String?, decisionReason: String?, claimedById: String?,
                claimedByName: String?, reservedForId: String?, reservedUntil: Double?,
                createdAt: Double, updatedAt: Double) {
        self.taskId = taskId
        self.projectId = projectId
        self.kind = kind
        self.parentId = parentId
        self.assigneeId = assigneeId
        self.assigneeName = assigneeName
        self.reviewerId = reviewerId
        self.reviewerName = reviewerName
        self.title = title
        self.goal = goal
        self.state = state
        self.round = round
        self.maxRounds = maxRounds
        self.leaseUntil = leaseUntil
        self.summary = summary
        self.branch = branch
        self.commitSha = commitSha
        self.prUrl = prUrl
        self.verdict = verdict
        self.feedback = feedback
        self.decisionReason = decisionReason
        self.claimedById = claimedById
        self.claimedByName = claimedByName
        self.reservedForId = reservedForId
        self.reservedUntil = reservedUntil
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }
}

public struct TaskEventRecord {
    public let seq: Int64
    public let taskId: String
    public let at: Double
    public let who: String?
    public let kind: String
    public let text: String
}

public struct DeliveryPageRecord {
    public let page: Int
    public let totalPages: Int
    public let text: String
}

public struct AgentStatusRecord {
    public var quotaText: String?
    public var quotaResetAt: String?
    public var reportedAt: Double
}
