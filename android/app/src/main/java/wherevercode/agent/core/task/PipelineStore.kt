package wherevercode.agent.core.task

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import java.util.UUID
import org.json.JSONArray

/**
 * P2 流水线持久化（2026-11-11）：Room v9，两张新表，CREATE-only
 * 迁移（P0 起的规矩：MIGRATION_8_9 只建新表、不动旧表）。
 *
 * 两张表分工：
 * - `pipeline_states`：一任务一行，流水线开关 + 当前阶段 + plan
 *   （JSON）+ 返工轮次 + 编码/审查者。upsert（REPLACE）——阶段推进
 *   是覆盖写，审计不靠它。
 * - `pipeline_verdicts`：审查 verdict 历史，append-only（DAO 只有
 *   insert + query，**没有任何 update/delete 方法**，与 task_events
 *   同款结构保证；重复 verdict 追加成历史，最新一条为当前 verdict，
 *   老项目 task_review.py 的口径）。
 *
 * 返工轮次（`reworkRounds`）单独计数，存在 pipeline_states 行上：
 * 绝不复用 P0 的 `attempt`（那是租约认领次数，见 TaskStore）。
 *
 * 阶段推进的审计事件（plan 批准、打回、审查通过、返工达上限）与
 * 状态/verdict 写在同一个 Room 事务里（[RoomPipelineStore] 经
 * `db.taskEventDao()` 写 [TaskEvent]，kind 见 [TaskEventKind] 的
 * pipeline_*；内存实现把事件记在 [InMemoryPipelineStore.recordedEvents]
 * 里供测试读）。
 */
data class PipelineState(
    val taskId: String,
    val enabled: Boolean = true,
    val stage: String = PipelineStage.PLAN,
    val planStatus: String = PipelinePlanStatus.NONE,
    val planJson: String? = null,
    val reworkRounds: Int = 0,
    val coderAgentId: String? = null,
    val coderDeviceId: String? = null,
    val reviewerAgentId: String? = null,
    val reviewerDeviceId: String? = null,
    val createdAtEpochSeconds: Long = 0,
    val updatedAtEpochSeconds: Long = 0,
) {
    /** 已批准的 plan；未批准/未提交时 null。 */
    val approvedPlan: PlanArtifact?
        get() = if (planStatus != PipelinePlanStatus.APPROVED) {
            null
        } else {
            planJson?.let { runCatching { PlanArtifact.fromJson(it) }.getOrNull() }
        }

    /** 草稿 plan（含已批准的）；未提交时 null。 */
    val draftPlan: PlanArtifact?
        get() = planJson?.let { runCatching { PlanArtifact.fromJson(it) }.getOrNull() }
}

data class PipelineVerdictRecord(
    val verdictId: String,
    val taskId: String,
    /** 打回时所在的返工轮次（0 = 首轮审查）。 */
    val round: Int,
    /** [ReviewVerdict] 两值之一。 */
    val verdict: String,
    /** 审查者：agent/device id；机械门打回时为 "mechanical"。 */
    val reviewer: String,
    /** 打回时的具体、可检查的返工清单（空 = 通过或无清单）。 */
    val checklist: List<String> = emptyList(),
    /** 本次审查依据的机械检查结果（JSON，可空）。 */
    val mechanicalJson: String? = null,
    val createdAtEpochSeconds: Long = 0,
) {
    val mechanical: MechanicalCheckResult?
        get() = mechanicalJson?.let { MechanicalCheckResult.fromJson(it) }
}

@Entity(
    tableName = "pipeline_states",
    indices = [Index("updatedAtEpochSeconds")],
)
data class PipelineStateEntity(
    @PrimaryKey val taskId: String,
    val enabled: Int = 1,
    val stage: String,
    val planStatus: String,
    val planJson: String? = null,
    val reworkRounds: Int = 0,
    val coderAgentId: String? = null,
    val coderDeviceId: String? = null,
    val reviewerAgentId: String? = null,
    val reviewerDeviceId: String? = null,
    val createdAtEpochSeconds: Long = 0,
    val updatedAtEpochSeconds: Long = 0,
)

@Entity(
    tableName = "pipeline_verdicts",
    indices = [Index(value = ["taskId", "createdAtEpochSeconds"])],
)
data class PipelineVerdictEntity(
    @PrimaryKey val verdictId: String,
    val taskId: String,
    val round: Int,
    val verdict: String,
    val reviewer: String,
    val checklistJson: String = "[]",
    val mechanicalJson: String? = null,
    val createdAtEpochSeconds: Long = 0,
)

fun PipelineState.toEntity(): PipelineStateEntity =
    PipelineStateEntity(
        taskId = taskId,
        enabled = if (enabled) 1 else 0,
        stage = stage,
        planStatus = planStatus,
        planJson = planJson,
        reworkRounds = reworkRounds,
        coderAgentId = coderAgentId,
        coderDeviceId = coderDeviceId,
        reviewerAgentId = reviewerAgentId,
        reviewerDeviceId = reviewerDeviceId,
        createdAtEpochSeconds = createdAtEpochSeconds,
        updatedAtEpochSeconds = updatedAtEpochSeconds,
    )

fun PipelineStateEntity.toState(): PipelineState =
    PipelineState(
        taskId = taskId,
        enabled = enabled != 0,
        stage = stage,
        planStatus = planStatus,
        planJson = planJson,
        reworkRounds = reworkRounds,
        coderAgentId = coderAgentId,
        coderDeviceId = coderDeviceId,
        reviewerAgentId = reviewerAgentId,
        reviewerDeviceId = reviewerDeviceId,
        createdAtEpochSeconds = createdAtEpochSeconds,
        updatedAtEpochSeconds = updatedAtEpochSeconds,
    )

fun PipelineVerdictRecord.toEntity(): PipelineVerdictEntity =
    PipelineVerdictEntity(
        verdictId = verdictId,
        taskId = taskId,
        round = round,
        verdict = verdict,
        reviewer = reviewer,
        checklistJson = JSONArray(checklist).toString(),
        mechanicalJson = mechanicalJson,
        createdAtEpochSeconds = createdAtEpochSeconds,
    )

private fun stringList(json: String): List<String> =
    try {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotBlank() } }
    } catch (_: Exception) {
        emptyList()
    }

fun PipelineVerdictEntity.toRecord(): PipelineVerdictRecord =
    PipelineVerdictRecord(
        verdictId = verdictId,
        taskId = taskId,
        round = round,
        verdict = verdict,
        reviewer = reviewer,
        checklist = stringList(checklistJson),
        mechanicalJson = mechanicalJson,
        createdAtEpochSeconds = createdAtEpochSeconds,
    )

@Dao
interface PipelineStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: PipelineStateEntity)

    @Query("SELECT * FROM pipeline_states WHERE taskId = :taskId")
    fun get(taskId: String): PipelineStateEntity?
}

@Dao
interface PipelineVerdictDao {
    /**
     * ABORT, not REPLACE：verdict 是审计历史，verdictId 冲突说明
     * 调用方复用了 id——必须抛错，不可静默覆盖已落盘的记录。
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(entity: PipelineVerdictEntity)

    @Query(
        "SELECT * FROM pipeline_verdicts WHERE taskId = :taskId " +
            "ORDER BY createdAtEpochSeconds ASC, rowid ASC",
    )
    fun listByTask(taskId: String): List<PipelineVerdictEntity>

    @Query("SELECT COUNT(*) FROM pipeline_verdicts WHERE taskId = :taskId")
    fun countByTask(taskId: String): Int
}

/** 流水线读写口：执行器门禁、协调器与 UI 只经它读写，不碰 DAO。 */
interface PipelineStore {
    fun getState(taskId: String): PipelineState?
    /** 保存流水线态；[event] 非空时与状态写在同一事务里记审计事件。 */
    fun saveState(state: PipelineState, event: TaskEvent? = null)
    /** 追加一条 verdict（append-only）；[event] 非空时同一事务记审计事件。 */
    fun recordVerdict(verdict: PipelineVerdictRecord, event: TaskEvent? = null)
    /**
     * 阶段推进 + verdict 同一事务落盘（审查通过/打回用它：两边要么
     * 一起落、要么一起不落，不留“verdict 说通过、阶段还在 review”
     * 的半截状态）。
     */
    fun saveStateAndRecordVerdict(state: PipelineState, verdict: PipelineVerdictRecord, event: TaskEvent? = null)
    fun listVerdicts(taskId: String): List<PipelineVerdictRecord>
}

/** Room 流水线库：生产实现。 */
class RoomPipelineStore(private val db: TaskDatabase) : PipelineStore {
    override fun getState(taskId: String): PipelineState? =
        db.pipelineStateDao().get(taskId)?.toState()

    override fun saveState(state: PipelineState, event: TaskEvent?) {
        db.runInTransaction {
            db.pipelineStateDao().upsert(state.toEntity())
            event?.let { db.taskEventDao().insert(it.toEntity()) }
        }
    }

    override fun recordVerdict(verdict: PipelineVerdictRecord, event: TaskEvent?) {
        db.runInTransaction {
            db.pipelineVerdictDao().insert(verdict.toEntity())
            event?.let { db.taskEventDao().insert(it.toEntity()) }
        }
    }

    override fun saveStateAndRecordVerdict(state: PipelineState, verdict: PipelineVerdictRecord, event: TaskEvent?) {
        db.runInTransaction {
            db.pipelineStateDao().upsert(state.toEntity())
            db.pipelineVerdictDao().insert(verdict.toEntity())
            event?.let { db.taskEventDao().insert(it.toEntity()) }
        }
    }

    override fun listVerdicts(taskId: String): List<PipelineVerdictRecord> =
        db.pipelineVerdictDao().listByTask(taskId).map { it.toRecord() }
}

/** 内存流水线库：Room 打不开时的回退（与 InMemoryTaskEventStore 同款规矩）。 */
class InMemoryPipelineStore : PipelineStore {
    private val lock = Any()
    private val states = LinkedHashMap<String, PipelineState>()
    private val verdicts = ArrayList<PipelineVerdictRecord>()
    private val events = ArrayList<TaskEvent>()

    /** 仅供测试：内存版收到的审计事件（Room 版写进 task_events 表）。 */
    val recordedEvents: List<TaskEvent>
        get() = synchronized(lock) { events.toList() }

    override fun getState(taskId: String): PipelineState? =
        synchronized(lock) { states[taskId] }

    override fun saveState(state: PipelineState, event: TaskEvent?) {
        synchronized(lock) {
            states[state.taskId] = state
            event?.let { events += it }
        }
    }

    override fun recordVerdict(verdict: PipelineVerdictRecord, event: TaskEvent?) {
        synchronized(lock) {
            verdicts += verdict
            event?.let { events += it }
        }
    }

    override fun saveStateAndRecordVerdict(state: PipelineState, verdict: PipelineVerdictRecord, event: TaskEvent?) {
        synchronized(lock) {
            states[state.taskId] = state
            verdicts += verdict
            event?.let { events += it }
        }
    }

    override fun listVerdicts(taskId: String): List<PipelineVerdictRecord> =
        synchronized(lock) { verdicts.filter { it.taskId == taskId } }
}

/** 新 verdict 行的 id：调用方不传时由协调器生成（uuid）。 */
fun newPipelineVerdictId(): String = UUID.randomUUID().toString()
