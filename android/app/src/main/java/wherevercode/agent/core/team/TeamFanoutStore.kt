package wherevercode.agent.core.team

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import java.util.UUID

/**
 * P3 只读扇出持久化（2026-11-11）：Room v10，两张新表，CREATE-only
 * 迁移（P0 起的规矩：MIGRATION_9_10 只建新表、不动旧表）。
 *
 * 两张表分工：
 * - `fanout_runs`：一次扇出一行：问题、关联任务（可空）、创建时间、
 *   结束时间。扇出不是 P0 任务——这里不记任务状态、不记认领、不记
 *   租约；它只是“问了谁、回了什么”的记录。
 * - `fanout_branches`：一个分支一行：状态 + 摘要 + 失败原因，
 *   append 后只 update 状态/摘要（DAO 有 update，无 delete）。
 *
 * 分支状态（字符串，与 [FanoutBranchStatus] 同名小写）：
 * pending → running → done | failed | timeout。
 */
enum class FanoutBranchStatus {
    PENDING, RUNNING, DONE, FAILED, TIMEOUT,
}

data class FanoutBranch(
    val branchId: String,
    val runId: String,
    val agentId: String,
    val deviceId: String?,
    val displayName: String,
    val status: FanoutBranchStatus = FanoutBranchStatus.PENDING,
    val summary: String? = null,
    val error: String? = null,
    val createdAtEpochSeconds: Long = 0,
    val finishedAtEpochSeconds: Long? = null,
)

data class FanoutRun(
    val runId: String,
    val question: String,
    val taskId: String? = null,
    val branches: List<FanoutBranch> = emptyList(),
    val createdAtEpochSeconds: Long = 0,
    val finishedAtEpochSeconds: Long? = null,
)

@Entity(
    tableName = "fanout_runs",
    indices = [Index("createdAtEpochSeconds")],
)
data class FanoutRunEntity(
    @PrimaryKey val runId: String,
    val question: String,
    val taskId: String? = null,
    val createdAtEpochSeconds: Long = 0,
    val finishedAtEpochSeconds: Long? = null,
)

@Entity(
    tableName = "fanout_branches",
    indices = [Index(value = ["runId", "createdAtEpochSeconds"])],
)
data class FanoutBranchEntity(
    @PrimaryKey val branchId: String,
    val runId: String,
    val agentId: String,
    val deviceId: String? = null,
    val displayName: String,
    val status: String = FanoutBranchStatus.PENDING.name,
    val summary: String? = null,
    val error: String? = null,
    val createdAtEpochSeconds: Long = 0,
    val finishedAtEpochSeconds: Long? = null,
)

@Dao
interface FanoutRunDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertRun(run: FanoutRunEntity)

    @Update
    fun updateRun(run: FanoutRunEntity)

    @Query("SELECT * FROM fanout_runs WHERE runId = :runId LIMIT 1")
    fun getRun(runId: String): FanoutRunEntity?

    @Query("SELECT * FROM fanout_runs ORDER BY createdAtEpochSeconds DESC LIMIT :limit")
    fun listRuns(limit: Int = 20): List<FanoutRunEntity>
}

@Dao
interface FanoutBranchDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertBranch(branch: FanoutBranchEntity)

    @Update
    fun updateBranch(branch: FanoutBranchEntity)

    @Query("SELECT * FROM fanout_branches WHERE runId = :runId ORDER BY createdAtEpochSeconds ASC")
    fun listBranches(runId: String): List<FanoutBranchEntity>
}

/** 扇出记录的读写口（Room 实现与内存实现见下；测试用内存实现）。 */
interface FanoutStore {
    fun insertRun(run: FanoutRun)
    fun finishRun(runId: String, finishedAtEpochSeconds: Long)
    fun insertBranch(branch: FanoutBranch)
    fun updateBranch(branch: FanoutBranch)
    fun getRun(runId: String): FanoutRun?
    fun listRuns(limit: Int = 20): List<FanoutRun>
}

class RoomFanoutStore(
    private val runDao: FanoutRunDao,
    private val branchDao: FanoutBranchDao,
) : FanoutStore {
    override fun insertRun(run: FanoutRun) {
        runDao.insertRun(
            FanoutRunEntity(
                runId = run.runId,
                question = run.question,
                taskId = run.taskId,
                createdAtEpochSeconds = run.createdAtEpochSeconds,
                finishedAtEpochSeconds = run.finishedAtEpochSeconds,
            ),
        )
        run.branches.forEach { insertBranch(it) }
    }

    override fun finishRun(runId: String, finishedAtEpochSeconds: Long) {
        val row = runDao.getRun(runId) ?: return
        runDao.updateRun(row.copy(finishedAtEpochSeconds = finishedAtEpochSeconds))
    }

    override fun insertBranch(branch: FanoutBranch) {
        branchDao.insertBranch(toEntity(branch))
    }

    override fun updateBranch(branch: FanoutBranch) {
        branchDao.updateBranch(toEntity(branch))
    }

    override fun getRun(runId: String): FanoutRun? {
        val row = runDao.getRun(runId) ?: return null
        return FanoutRun(
            runId = row.runId,
            question = row.question,
            taskId = row.taskId,
            branches = branchDao.listBranches(runId).map { toBranch(it) },
            createdAtEpochSeconds = row.createdAtEpochSeconds,
            finishedAtEpochSeconds = row.finishedAtEpochSeconds,
        )
    }

    override fun listRuns(limit: Int): List<FanoutRun> =
        runDao.listRuns(limit).map { row ->
            FanoutRun(
                runId = row.runId,
                question = row.question,
                taskId = row.taskId,
                branches = branchDao.listBranches(row.runId).map { toBranch(it) },
                createdAtEpochSeconds = row.createdAtEpochSeconds,
                finishedAtEpochSeconds = row.finishedAtEpochSeconds,
            )
        }

    private fun toEntity(branch: FanoutBranch): FanoutBranchEntity =
        FanoutBranchEntity(
            branchId = branch.branchId,
            runId = branch.runId,
            agentId = branch.agentId,
            deviceId = branch.deviceId,
            displayName = branch.displayName,
            status = branch.status.name,
            summary = branch.summary,
            error = branch.error,
            createdAtEpochSeconds = branch.createdAtEpochSeconds,
            finishedAtEpochSeconds = branch.finishedAtEpochSeconds,
        )

    private fun toBranch(entity: FanoutBranchEntity): FanoutBranch =
        FanoutBranch(
            branchId = entity.branchId,
            runId = entity.runId,
            agentId = entity.agentId,
            deviceId = entity.deviceId,
            displayName = entity.displayName,
            status = runCatching { FanoutBranchStatus.valueOf(entity.status) }
                .getOrDefault(FanoutBranchStatus.PENDING),
            summary = entity.summary,
            error = entity.error,
            createdAtEpochSeconds = entity.createdAtEpochSeconds,
            finishedAtEpochSeconds = entity.finishedAtEpochSeconds,
        )
}

/** 内存实现（测试用；与 Room 实现同一口径）。 */
class InMemoryFanoutStore : FanoutStore {
    private val runs = LinkedHashMap<String, FanoutRun>()
    private val branches = LinkedHashMap<String, FanoutBranch>()

    override fun insertRun(run: FanoutRun) {
        runs[run.runId] = run
        run.branches.forEach { branches[it.branchId] = it }
    }

    override fun finishRun(runId: String, finishedAtEpochSeconds: Long) {
        val run = runs[runId] ?: return
        runs[runId] = run.copy(finishedAtEpochSeconds = finishedAtEpochSeconds)
    }

    override fun insertBranch(branch: FanoutBranch) {
        branches[branch.branchId] = branch
    }

    override fun updateBranch(branch: FanoutBranch) {
        branches[branch.branchId] = branch
    }

    override fun getRun(runId: String): FanoutRun? {
        val run = runs[runId] ?: return null
        return run.copy(
            branches = branches.values.filter { it.runId == runId },
        )
    }

    override fun listRuns(limit: Int): List<FanoutRun> =
        runs.values.sortedByDescending { it.createdAtEpochSeconds }
            .take(limit)
            .map { run ->
                run.copy(branches = branches.values.filter { it.runId == run.runId })
            }

    /** 测试读：一次扇出的全部分支。 */
    fun listBranches(runId: String): List<FanoutBranch> =
        branches.values.filter { it.runId == runId }
}

fun newFanoutRunId(): String = UUID.randomUUID().toString()
fun newFanoutBranchId(): String = UUID.randomUUID().toString()
