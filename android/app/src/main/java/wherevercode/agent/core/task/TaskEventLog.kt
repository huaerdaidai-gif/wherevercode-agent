package wherevercode.agent.core.task

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 任务事件日志（P0 耐久任务引擎，2026-11-11）：审计轨迹。
 *
 * 一条事件 = 一次任务状态转移（或一次租约事件）的持久记录：
 * 谁在何时把任务从哪态推到哪态、租约何时过期回队/入死信。
 * 控制台 LOGS 模块读它作审计轨迹（不只是动态 feed）；任务详情页
 * 读它作“状态轨迹”。
 *
 * 三条硬规矩（对应老项目对照报告的 3 处须先修 bug）：
 * 1. append-only：DAO 只有 insert + query，**没有任何 update /
 *    delete 方法**——审计轨迹写了就不能改、不能删（结构保证，
 *    不是约定）。
 * 2. 与状态写同一事务：[RoomTaskStore] 在 `updateTask` /
 *    `createTask` / `expireLeases` 里把事件插入与状态更新包进
 *    同一个 `runInTransaction`；插入失败 → 整个事务回滚 + 调用方
 *    看得见抛错，绝不“状态已落、事件悄悄没了”（老项目 §5.4）。
 * 3. 终态全记：事件按每次转移写，不限终态；completed / failed /
 *    cancelled 是普通转移，无特殊分支可丢（老项目 §5.1）。
 * 另：只做 append-only INSERT（单条 O(1)），读按索引分页；没有
 * “读全表重写/截断重排”的逻辑，不设自动清理（老项目 §5.6 O(n²)
 * 的反面；保留策略以后再定，不在本分支编一个）。
 *
 * 事件不带聊天记录：reason 只写转移原因（租约过期、打回返工等），
 * 不贴任务目标全文、不贴任何秘密。
 */
data class TaskEvent(
    val eventId: String,
    val taskId: String,
    val attemptId: String?,
    val kind: String,
    val fromState: String?,
    val toState: String,
    val actor: String?,
    val reason: String?,
    val createdAtEpochSeconds: Long,
)

object TaskEventKind {
    const val CREATED: String = "created"
    const val CLAIMED: String = "claimed"
    const val STARTED: String = "started"
    const val BLOCKED: String = "blocked"
    const val REVIEW: String = "review"
    const val REWORK_REQUESTED: String = "rework_requested"
    const val REQUEUED: String = "requeued"
    const val COMPLETED: String = "completed"
    const val FAILED: String = "failed"
    const val CANCELLED: String = "cancelled"
    const val RELEASED: String = "released"
    const val LEASE_EXPIRED: String = "lease_expired"
    /** P2 流水线阶段事件（状态转移之外的审计点；toState 取当时任务态）。 */
    const val PIPELINE_ENABLED: String = "pipeline_enabled"
    const val PIPELINE_PLAN_SUBMITTED: String = "pipeline_plan_submitted"
    const val PIPELINE_PLAN_APPROVED: String = "pipeline_plan_approved"
    const val PIPELINE_REWORK_REQUESTED: String = "pipeline_rework_requested"
    const val PIPELINE_REVIEW_APPROVED: String = "pipeline_review_approved"
    const val PIPELINE_REWORK_EXHAUSTED: String = "pipeline_rework_exhausted"
    /** P2 自动执行（runner，2026-11-11）：规划/审查的起止与失败审计点。 */
    const val PIPELINE_PLAN_STARTED: String = "pipeline_plan_started"
    const val PIPELINE_PLAN_FAILED: String = "pipeline_plan_failed"
    const val PIPELINE_REVIEW_STARTED: String = "pipeline_review_started"
    const val PIPELINE_REVIEW_FAILED: String = "pipeline_review_failed"
    /** 任务拆分事件（写在父任务的事件流里；toState 取当时父任务态）。 */
    const val DECOMPOSITION_PROPOSED: String = "decomposition_proposed"
    const val DECOMPOSITION_APPROVED: String = "decomposition_approved"
    const val DECOMPOSITION_BLOCKED: String = "decomposition_blocked"
    const val DECOMPOSITION_CANCELLED: String = "decomposition_cancelled"
    const val SUBTASK_CREATED: String = "subtask_created"
    const val SUBTASK_COMPLETED: String = "subtask_completed"
    const val SUBTASK_FAILED: String = "subtask_failed"
    const val MERGE_STARTED: String = "merge_started"
    const val MERGE_COMPLETED: String = "merge_completed"
    const val MERGE_CONFLICT: String = "merge_conflict"

    /**
     * V5 跨设备开发（2026-11-12，A5）：派活文件夹回落事件。项目在
     * 目标设备上无检出链接、执行回落到 Agent 文件夹时记一条（只到
     * 事件档，不加任务详情“工作目录”行）；reason 写清项目名、目标
     * 设备与回落到的文件夹，绝不静默回落。
     */
    const val FOLDER_FALLBACK: String = "folder_fallback"

    /**
     * V5 跨设备开发（2026-11-12，D4 新口径）：项目文件夹命中事件。
     * 与 [FOLDER_FALLBACK] 同款审计点——任务详情“工作目录”行从事件
     * 反查，两类事件都要有，缺一类详情行就看不到一半任务的目录。
     */
    const val FOLDER_RESOLVED: String = "folder_resolved"

    /**
     * V5 跨设备开发（2026-11-12，D7 新口径）：任务不可派事件。项目
     * 家是 git 家且目标设备上无 DEVICE 检出时，泵不认领、不派活，
     * 任务留 queued + needs_attention；原因写进任务行 error 与本事件。
     */
    const val NOT_DISPATCHABLE: String = "not_dispatchable"

    /** 目标态 → 事件 kind（状态转移的统一映射）。 */
    fun forState(toState: String): String = when (toState) {
        "assigned" -> CLAIMED
        "working" -> STARTED
        "blocked" -> BLOCKED
        "review" -> REVIEW
        "rework" -> REWORK_REQUESTED
        "requeued" -> REQUEUED
        "completed" -> COMPLETED
        "failed" -> FAILED
        "cancelled" -> CANCELLED
        "queued" -> RELEASED
        else -> toState
    }

    /** 中文显示（控制台/详情页的唯一事实来源）。 */
    fun labelOf(kind: String): String = when (kind) {
        CREATED -> "创建"
        CLAIMED -> "认领"
        STARTED -> "开始执行"
        BLOCKED -> "阻塞"
        REVIEW -> "待审核"
        REWORK_REQUESTED -> "打回返工"
        REQUEUED -> "租约过期回队"
        COMPLETED -> "已完成"
        FAILED -> "失败"
        CANCELLED -> "已取消"
        RELEASED -> "释放回队"
        LEASE_EXPIRED -> "租约过期"
        PIPELINE_ENABLED -> "启用流水线"
        PIPELINE_PLAN_SUBMITTED -> "提交 plan"
        PIPELINE_PLAN_APPROVED -> "批准 plan"
        PIPELINE_REWORK_REQUESTED -> "打回返工（流水线）"
        PIPELINE_REVIEW_APPROVED -> "审查通过（流水线）"
        PIPELINE_REWORK_EXHAUSTED -> "返工达上限转人工"
        PIPELINE_PLAN_STARTED -> "规划者开始起草"
        PIPELINE_PLAN_FAILED -> "规划草稿失败"
        PIPELINE_REVIEW_STARTED -> "审查开始"
        PIPELINE_REVIEW_FAILED -> "审查失败转人工"
        DECOMPOSITION_PROPOSED -> "提出拆分"
        DECOMPOSITION_APPROVED -> "批准拆分"
        DECOMPOSITION_BLOCKED -> "拆分阻塞"
        DECOMPOSITION_CANCELLED -> "取消拆分"
        SUBTASK_CREATED -> "创建子任务"
        SUBTASK_COMPLETED -> "子任务完成"
        SUBTASK_FAILED -> "子任务失败"
        MERGE_STARTED -> "开始合并"
        MERGE_COMPLETED -> "合并完成"
        MERGE_CONFLICT -> "合并冲突待裁决"
        FOLDER_FALLBACK -> "文件夹回落"
        FOLDER_RESOLVED -> "工作目录（项目检出）"
        NOT_DISPATCHABLE -> "未派活"
        else -> kind
    }
}

@Entity(
    tableName = "task_events",
    indices = [Index(value = ["taskId", "createdAtEpochSeconds"])],
)
data class TaskEventEntity(
    @PrimaryKey val eventId: String,
    val taskId: String,
    val attemptId: String? = null,
    val kind: String,
    val fromState: String? = null,
    val toState: String,
    val actor: String? = null,
    val reason: String? = null,
    val createdAtEpochSeconds: Long = 0,
)

fun TaskEvent.toEntity(): TaskEventEntity =
    TaskEventEntity(
        eventId = eventId,
        taskId = taskId,
        attemptId = attemptId,
        kind = kind,
        fromState = fromState,
        toState = toState,
        actor = actor,
        reason = reason,
        createdAtEpochSeconds = createdAtEpochSeconds,
    )

/**
 * 任务本次执行的工作目录（V5 跨设备开发，2026-11-12，D4 新口径 (b)：
 * 任务详情加“工作目录”行，从事件反查，不新增 Room 字段/迁移）。
 *
 * 只认两类事件的 reason 原文（写侧模板固定，见
 * [wherevercode.agent.core.project.folderResolvedReason] /
 * [wherevercode.agent.core.project.folderFallbackReason]）：
 * - [TaskEventKind.FOLDER_RESOLVED]：`…使用检出文件夹 <folder> 执行` → 项目检出；
 * - [TaskEventKind.FOLDER_FALLBACK]：`…使用 Agent 文件夹 <folder> 执行` → Agent 文件夹回落。
 * 取时间最晚的一条（事件按时间升序，取最后一个命中的）；两类事件
 * 都没有时返回 null（详情行不显示，不编一个）。
 */
data class WorkingDirectory(
    val folder: String,
    val isProjectCheckout: Boolean,
)

fun workingDirectoryFromEvents(events: List<TaskEvent>): WorkingDirectory? {
    var found: WorkingDirectory? = null
    for (event in events) {
        val reason = event.reason ?: continue
        val resolved = "使用检出文件夹 ".let { marker ->
            reason.substringAfter(marker, "").substringBefore(" 执行", "").takeIf { it.isNotBlank() }
        }
        val fallback = "使用 Agent 文件夹 ".let { marker ->
            reason.substringAfter(marker, "").substringBefore(" 执行", "").takeIf { it.isNotBlank() }
        }
        when (event.kind) {
            TaskEventKind.FOLDER_RESOLVED -> if (resolved != null) {
                found = WorkingDirectory(resolved, isProjectCheckout = true)
            }
            TaskEventKind.FOLDER_FALLBACK -> if (fallback != null) {
                found = WorkingDirectory(fallback, isProjectCheckout = false)
            }
            else -> Unit
        }
    }
    return found
}

fun TaskEventEntity.toEvent(): TaskEvent =
    TaskEvent(
        eventId = eventId,
        taskId = taskId,
        attemptId = attemptId,
        kind = kind,
        fromState = fromState,
        toState = toState,
        actor = actor,
        reason = reason,
        createdAtEpochSeconds = createdAtEpochSeconds,
    )

@Dao
interface TaskEventDao {
    /**
     * ABORT, not REPLACE: 事件是审计轨迹，eventId 冲突说明调用方
     * 复用了 id——必须抛错，不可静默覆盖已落盘的审计行。
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(event: TaskEventEntity)

    @Query("SELECT COUNT(*) FROM task_events")
    fun countAll(): Int

    /** 某任务的审计轨迹：按时间升序（审计顺序），rowid 作同秒 tiebreak。 */
    @Query(
        "SELECT * FROM task_events WHERE taskId = :taskId " +
            "ORDER BY createdAtEpochSeconds ASC, rowid ASC LIMIT :limit",
    )
    fun listByTask(taskId: String, limit: Int): List<TaskEventEntity>

    /** 全局最新事件：最新在前，供控制台审计区。 */
    @Query(
        "SELECT * FROM task_events " +
            "ORDER BY createdAtEpochSeconds DESC, rowid DESC LIMIT :limit",
    )
    fun recent(limit: Int): List<TaskEventEntity>
}

/** 任务事件读口：控制台与任务详情页只经它读，不碰 DAO。 */
interface TaskEventStore {
    fun listEvents(taskId: String, limit: Int = 200): List<TaskEvent>
    fun recentEvents(limit: Int = 40): List<TaskEvent>
}

/** Room 任务事件库：生产实现。写只发生在 [RoomTaskStore] 的事务内。 */
class RoomTaskEventStore(private val db: TaskDatabase) : TaskEventStore {
    override fun listEvents(taskId: String, limit: Int): List<TaskEvent> =
        db.taskEventDao().listByTask(taskId, limit).map { it.toEvent() }

    override fun recentEvents(limit: Int): List<TaskEvent> =
        db.taskEventDao().recent(limit).map { it.toEvent() }
}

/** 内存任务事件库：Room 打不开时的回退（与 InMemoryTaskStepStore 同款规矩）。 */
class InMemoryTaskEventStore : TaskEventStore {
    private val lock = Any()
    private val events = ArrayList<TaskEvent>()

    /** 仅供测试与回退路径：Room 版的写在 RoomTaskStore 事务内，不走这里。 */
    fun record(event: TaskEvent) {
        synchronized(lock) { events += event }
    }

    override fun listEvents(taskId: String, limit: Int): List<TaskEvent> =
        synchronized(lock) {
            events.filter { it.taskId == taskId }.take(limit)
        }

    override fun recentEvents(limit: Int): List<TaskEvent> =
        synchronized(lock) { events.takeLast(limit).reversed() }
}
