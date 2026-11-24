package wherevercode.agent.core.platform

/**
 * 任务派工（contracts/task-dispatch/spec.md v1）的数据模型与常量。
 *
 * 语义基线 = 线上 v5_platform.py：字段、状态名、常量逐项对齐，
 * 对拍夹具在 contracts/task-dispatch/fixtures/。本文件只放模型，
 * 行为在 [TaskDispatchService] / [TaskDispatchStore]。
 */

/** 任务派工错误：status/code/message 三元组与基线 V5Error 对齐。 */
class TaskDispatchException(
    message: String,
    val status: Int = 409,
    val code: String = "v5_error",
) : Exception(message)

object TaskDispatchSpec {
    const val LEASE_SECONDS = 7200.0
    const val PACK_TIMEOUT_SECONDS = 1800.0
    const val MAX_GOAL_CHARS = 20_000
    const val MAX_SUMMARY_CHARS = 8_000
    const val MAX_PROGRESS_CHARS = 2_000
    const val MAX_PAGE_CHARS = 65_536
    const val MAX_DELIVERY_BYTES = 2 * 1024 * 1024
    const val MAX_DELIVERY_PAGES = 200
    const val MAX_TITLE_CHARS = 100
    const val MAX_QUOTA_TEXT_CHARS = 500
    const val MAX_QUOTA_RESET_CHARS = 120
    const val DEFAULT_MAX_ROUNDS = 3

    const val QUEUED = "queued"
    const val CLAIMED = "claimed"
    const val REVIEWING = "reviewing"
    const val APPROVED = "approved"
    const val NEEDS_DECISION = "needs_decision"
    const val DONE = "done"
    const val CANCELLED = "cancelled"

    val STATE_LABELS = mapOf(
        QUEUED to "等待接手",
        CLAIMED to "进行中",
        REVIEWING to "待审核",
        APPROVED to "审查通过",
        NEEDS_DECISION to "等你决定",
        DONE to "完成",
        CANCELLED to "已取消",
    )

    val TERMINAL_STATES = setOf(APPROVED, DONE, CANCELLED)
    val TASK_KINDS = setOf("work", "review", "pack")
}

data class TaskRecord(
    val taskId: String,
    var projectId: String,
    var kind: String,
    var parentId: String?,
    var assigneeId: String,
    var assigneeName: String,
    var reviewerId: String?,
    var reviewerName: String?,
    var title: String,
    var goal: String,
    var state: String,
    var round: Int,
    var maxRounds: Int,
    var leaseUntil: Double?,
    var summary: String?,
    var branch: String?,
    var commitSha: String?,
    var prUrl: String?,
    var verdict: String?,
    var feedback: String?,
    var decisionReason: String?,
    var claimedById: String?,
    var claimedByName: String?,
    var reservedForId: String?,
    var reservedUntil: Double?,
    var deliveryTotalPages: Int = 0,
    var deliveryPages: Int = 0,
    var deliveryTruncated: Boolean = false,
    var deliveryFiles: String? = null,
    var version: Int = 1,
    var attempt: Int = 0,
    var attemptId: String? = null,
    var channelId: String? = null,
    var epoch: Int = 1,
    var createdBy: String? = null,
    var deletedAt: Double? = null,
    var deletedBy: String? = null,
    val createdAt: Double,
    var updatedAt: Double,
)

data class TaskEventRecord(
    val seq: Long,
    val taskId: String,
    val at: Double,
    val who: String?,
    val kind: String,
    val text: String,
)

data class DeliveryPageRecord(
    val page: Int,
    val totalPages: Int,
    val text: String,
)

data class AgentStatusRecord(
    var quotaText: String?,
    var quotaResetAt: String?,
    var reportedAt: Double,
)
