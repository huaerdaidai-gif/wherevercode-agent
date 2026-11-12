package wherevercode.agent.core.task

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import org.json.JSONArray

/**
 * 任务拆分持久化（2026-11-11，大脑总管自动拆分）：Room v10，两张新表，
 * CREATE-only 迁移（P0 起的规矩：MIGRATION_9_10 只建新表、不动旧表）。
 *
 * 两张表分工：
 * - `decomposition_states`：一父任务一行，拆分状态 + 集成线分支 +
 *   拆分方案（JSON）+ 合并结果（JSON）。upsert（REPLACE）——状态推进
 *   是覆盖写，审计不靠它。
 * - `decomposition_subtasks`：子任务清单，append-only（DAO 只有
 *   insert + query，**没有任何 update/delete 方法**，与 task_events
 *   同款结构保证；子任务自己的状态只认 tasks 表那一行，不在这里
 *   再记一份——状态单源，不留两处打架的余地）。
 *
 * 状态推进的审计事件（提出/批准拆分、子任务创建/完成/失败、开始/
 *   完成/冲突合并、阻塞、取消）与状态写在同一个 Room 事务里
 *   （[RoomDecompositionStore] 经 `db.taskEventDao()` 写 [TaskEvent]，
 *   kind 见 [TaskEventKind] 的 decomposition_* / subtask_* / merge_*；
 *   内存实现把事件记在 [InMemoryDecompositionStore.recordedEvents]
 *   里供测试读）。
 */
data class DecompositionState(
    val taskId: String,
    val status: String = DecompositionStatus.PROPOSED,
    /** 集成线分支：`integration/<父任务 id>`，子任务分支合到这里。 */
    val integrationBranch: String,
    /** 合并基底分支（调用方供，如 main；合并任务的描述里也写明）。 */
    val baseBranch: String,
    /** 拆分方案 JSON（[TaskDecomposer.proposalToJson]）。 */
    val proposalJson: String? = null,
    /** 合并结果 JSON（[MergeResult] 往返；未合并时 null）。 */
    val mergeResultJson: String? = null,
    val createdAtEpochSeconds: Long = 0,
    val updatedAtEpochSeconds: Long = 0,
) {
    /** 已存的拆分方案；坏 JSON / 未存时 null。 */
    val proposal: DecompositionProposal.Splittable?
        get() = proposalJson?.let { TaskDecomposer.proposalFromJson(it) }

    /** 已存的合并结果；坏 JSON / 未合并时 null。 */
    val mergeResult: MergeResult?
        get() = mergeResultJson?.let { MergeResult.fromJson(it) }
}

/** 拆分状态（父任务一级的子状态，不改 P0 状态机）。 */
object DecompositionStatus {
    const val PROPOSED: String = "proposed"
    const val APPROVED: String = "approved"
    const val ACTIVE: String = "active"
    const val MERGING: String = "merging"
    const val MERGED: String = "merged"
    const val BLOCKED: String = "blocked"
    const val CANCELLED: String = "cancelled"

    fun labelOf(status: String): String = when (status) {
        PROPOSED -> "已提出拆分"
        APPROVED -> "已批准拆分"
        ACTIVE -> "子任务执行中"
        MERGING -> "合并中"
        MERGED -> "已合并"
        BLOCKED -> "阻塞待裁决"
        CANCELLED -> "已取消拆分"
        else -> status
    }
}

/** 一条子任务记录：只记拆分关系与范围，不记执行状态（状态只认 tasks 表）。 */
data class DecompositionSubtaskRecord(
    /** 子任务的任务 id（即 tasks 表那一行的 taskId）。 */
    val subtaskTaskId: String,
    val parentTaskId: String,
    val subtaskIndex: Int,
    val files: List<String> = emptyList(),
    /** 子任务分支：`task/<子任务 id>`（P0 TaskPacket 的分支规则）。 */
    val branch: String,
    val objective: String,
    val createdAtEpochSeconds: Long = 0,
    /** 模块标签（TaskDecomposer 的分组名，子任务标题里带它；持久化以便回读校验）。 */
    val moduleLabel: String = "",
)

@Entity(
    tableName = "decomposition_states",
    indices = [Index("updatedAtEpochSeconds")],
)
data class DecompositionStateEntity(
    @PrimaryKey val taskId: String,
    val status: String,
    val integrationBranch: String,
    val baseBranch: String,
    val proposalJson: String? = null,
    val mergeResultJson: String? = null,
    val createdAtEpochSeconds: Long = 0,
    val updatedAtEpochSeconds: Long = 0,
)

@Entity(
    tableName = "decomposition_subtasks",
    indices = [Index("parentTaskId")],
)
data class DecompositionSubtaskEntity(
    @PrimaryKey val subtaskTaskId: String,
    val parentTaskId: String,
    val subtaskIndex: Int,
    val filesJson: String = "[]",
    val branch: String,
    val objective: String,
    val createdAtEpochSeconds: Long = 0,
    val moduleLabel: String = "",
)

fun DecompositionState.toEntity(): DecompositionStateEntity =
    DecompositionStateEntity(
        taskId = taskId,
        status = status,
        integrationBranch = integrationBranch,
        baseBranch = baseBranch,
        proposalJson = proposalJson,
        mergeResultJson = mergeResultJson,
        createdAtEpochSeconds = createdAtEpochSeconds,
        updatedAtEpochSeconds = updatedAtEpochSeconds,
    )

fun DecompositionStateEntity.toState(): DecompositionState =
    DecompositionState(
        taskId = taskId,
        status = status,
        integrationBranch = integrationBranch,
        baseBranch = baseBranch,
        proposalJson = proposalJson,
        mergeResultJson = mergeResultJson,
        createdAtEpochSeconds = createdAtEpochSeconds,
        updatedAtEpochSeconds = updatedAtEpochSeconds,
    )

fun DecompositionSubtaskRecord.toEntity(): DecompositionSubtaskEntity =
    DecompositionSubtaskEntity(
        subtaskTaskId = subtaskTaskId,
        parentTaskId = parentTaskId,
        subtaskIndex = subtaskIndex,
        filesJson = JSONArray(files).toString(),
        branch = branch,
        objective = objective,
        createdAtEpochSeconds = createdAtEpochSeconds,
        moduleLabel = moduleLabel,
    )

private fun stringList(json: String): List<String> =
    try {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotBlank() } }
    } catch (_: Exception) {
        emptyList()
    }

fun DecompositionSubtaskEntity.toRecord(): DecompositionSubtaskRecord =
    DecompositionSubtaskRecord(
        subtaskTaskId = subtaskTaskId,
        parentTaskId = parentTaskId,
        subtaskIndex = subtaskIndex,
        files = stringList(filesJson),
        branch = branch,
        objective = objective,
        createdAtEpochSeconds = createdAtEpochSeconds,
        moduleLabel = moduleLabel,
    )

@Dao
interface DecompositionStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: DecompositionStateEntity)

    @Query("SELECT * FROM decomposition_states WHERE taskId = :taskId")
    fun get(taskId: String): DecompositionStateEntity?
}

@Dao
interface DecompositionSubtaskDao {
    /**
     * ABORT, not REPLACE：子任务清单是拆分记录，subtaskTaskId 冲突
     * 说明调用方复用了 id——必须抛错，不可静默覆盖已落盘的行。
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(entity: DecompositionSubtaskEntity)

    @Query(
        "SELECT * FROM decomposition_subtasks WHERE parentTaskId = :parentTaskId " +
            "ORDER BY subtaskIndex ASC",
    )
    fun listByParent(parentTaskId: String): List<DecompositionSubtaskEntity>
}

/** 拆分读写口：协调器与详情页只经它读写，不碰 DAO。 */
interface DecompositionStore {
    fun getState(taskId: String): DecompositionState?
    /** 保存拆分态；[event] 非空时与状态写在同一事务里记审计事件。 */
    fun saveState(state: DecompositionState, event: TaskEvent? = null)
    /** 追加一条子任务记录（append-only）；[event] 非空时同一事务记审计事件。 */
    fun insertSubtask(record: DecompositionSubtaskRecord, event: TaskEvent? = null)
    fun listSubtasks(parentTaskId: String): List<DecompositionSubtaskRecord>
}

/** Room 拆分库：生产实现。 */
class RoomDecompositionStore(private val db: TaskDatabase) : DecompositionStore {
    override fun getState(taskId: String): DecompositionState? =
        db.decompositionStateDao().get(taskId)?.toState()

    override fun saveState(state: DecompositionState, event: TaskEvent?) {
        db.runInTransaction {
            db.decompositionStateDao().upsert(state.toEntity())
            event?.let { db.taskEventDao().insert(it.toEntity()) }
        }
    }

    override fun insertSubtask(record: DecompositionSubtaskRecord, event: TaskEvent?) {
        db.runInTransaction {
            db.decompositionSubtaskDao().insert(record.toEntity())
            event?.let { db.taskEventDao().insert(it.toEntity()) }
        }
    }

    override fun listSubtasks(parentTaskId: String): List<DecompositionSubtaskRecord> =
        db.decompositionSubtaskDao().listByParent(parentTaskId).map { it.toRecord() }
}

/** 内存拆分库：Room 打不开时的回退（与 InMemoryPipelineStore 同款规矩）。 */
class InMemoryDecompositionStore : DecompositionStore {
    private val lock = Any()
    private val states = LinkedHashMap<String, DecompositionState>()
    private val subtasks = ArrayList<DecompositionSubtaskRecord>()
    private val events = ArrayList<TaskEvent>()

    /** 仅供测试：内存版收到的审计事件（Room 版写进 task_events 表）。 */
    val recordedEvents: List<TaskEvent>
        get() = synchronized(lock) { events.toList() }

    override fun getState(taskId: String): DecompositionState? =
        synchronized(lock) { states[taskId] }

    override fun saveState(state: DecompositionState, event: TaskEvent?) {
        synchronized(lock) {
            states[state.taskId] = state
            event?.let { events += it }
        }
    }

    override fun insertSubtask(record: DecompositionSubtaskRecord, event: TaskEvent?) {
        synchronized(lock) {
            subtasks += record
            event?.let { events += it }
        }
    }

    override fun listSubtasks(parentTaskId: String): List<DecompositionSubtaskRecord> =
        synchronized(lock) { subtasks.filter { it.parentTaskId == parentTaskId }.sortedBy { it.subtaskIndex } }
}
