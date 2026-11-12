package wherevercode.agent.core.task

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import org.json.JSONArray
import wherevercode.agent.brain.Skill
import wherevercode.agent.brain.SkillStore

/**
 * 技能持久化（V5，2026-11-11）：Room 表 `skills`，挂在共享
 * [TaskDatabase]（`wherevercode-tasks.db`）上，version 7。
 *
 * 一行一条技能。列表字段（触发词 / 步骤 / 工具 / 坑 / 证据任务）
 * 以 JSON 数组存 TEXT 列（与 TaskStore 的 dependenciesJson 同款
 * org.json.JSONArray 写法，无新依赖）。
 *
 * 迁移 MIGRATION_6_7 只建新表、不动旧表（C1 起的 CREATE-only 规矩）。
 */
@Entity(
    tableName = "skills",
    indices = [
        Index("projectId"),
        Index("updatedAtEpochSeconds"),
    ],
)
data class SkillEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val name: String,
    val triggersJson: String = "[]",
    val summary: String = "",
    val stepsJson: String = "[]",
    val toolsJson: String = "[]",
    val pitfallsJson: String = "[]",
    val evidenceTaskIdsJson: String = "[]",
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val lastUsedEpochSeconds: Long = 0,
    val createdAtEpochSeconds: Long = 0,
    val updatedAtEpochSeconds: Long = 0,
)

@Dao
interface SkillDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: SkillEntity)

    @Query("SELECT * FROM skills WHERE id = :id")
    fun get(id: String): SkillEntity?

    @Query("DELETE FROM skills WHERE id = :id")
    fun delete(id: String)

    @Query(
        "SELECT * FROM skills " +
            "WHERE projectId = :projectId OR projectId = '' " +
            "ORDER BY updatedAtEpochSeconds DESC",
    )
    fun listForProject(projectId: String): List<SkillEntity>
}

fun Skill.toEntity(): SkillEntity =
    SkillEntity(
        id = id,
        projectId = projectId,
        name = name,
        triggersJson = JSONArray(triggers).toString(),
        summary = summary,
        stepsJson = JSONArray(steps).toString(),
        toolsJson = JSONArray(tools).toString(),
        pitfallsJson = JSONArray(pitfalls).toString(),
        evidenceTaskIdsJson = JSONArray(evidenceTaskIds).toString(),
        successCount = successCount,
        failureCount = failureCount,
        lastUsedEpochSeconds = lastUsedEpochSeconds,
        createdAtEpochSeconds = createdAtEpochSeconds,
        updatedAtEpochSeconds = updatedAtEpochSeconds,
    )

private fun stringList(json: String): List<String> =
    try {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotBlank() } }
    } catch (_: Exception) {
        emptyList()
    }

fun SkillEntity.toSkill(): Skill =
    Skill(
        id = id,
        projectId = projectId,
        name = name,
        triggers = stringList(triggersJson),
        summary = summary,
        steps = stringList(stepsJson),
        tools = stringList(toolsJson),
        pitfalls = stringList(pitfallsJson),
        evidenceTaskIds = stringList(evidenceTaskIdsJson),
        successCount = successCount,
        failureCount = failureCount,
        lastUsedEpochSeconds = lastUsedEpochSeconds,
        createdAtEpochSeconds = createdAtEpochSeconds,
        updatedAtEpochSeconds = updatedAtEpochSeconds,
    )

/** Room 技能库：生产实现（[SkillStore] 的 Room 版）。 */
class RoomSkillStore(private val db: TaskDatabase) : SkillStore {
    override fun upsert(skill: Skill) {
        db.skillDao().upsert(skill.toEntity())
    }

    override fun get(id: String): Skill? = db.skillDao().get(id)?.toSkill()

    override fun remove(id: String) {
        db.skillDao().delete(id)
    }

    override fun listForProject(projectId: String): List<Skill> =
        db.skillDao().listForProject(projectId).map { it.toSkill() }
}
