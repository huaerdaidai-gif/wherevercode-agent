package wherevercode.agent.core.team

import android.content.Context
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import org.json.JSONArray
import wherevercode.agent.core.task.TaskDatabase

/**
 * 成员持久化（V5 Phase B C3 第二阶段，成员框架）。
 *
 * 四张表，都在 TaskDatabase（`wherevercode-tasks.db`）里，版本 5
 * 起（MIGRATION_4_5，CREATE-only；旧行一律不动）：
 * - team_members：成员行（[TeamMember] 全字段；授权项目 id 列表
 *   存 JSON 数组，与 TaskStore 的 dependenciesJson 同一做法）。
 * - member_project_grants：成员 ↔ 项目绑定（(memberId, projectId)
 *   复合主键；grantedBy 记实际授予方，不留 "local" 默认值冒充
 *   来源；解绑即删行，删行本身由 MemberManager 写审计）。
 * - member_returns：成员回传记录（任务 + attempt 复合主键；交付
 *   记录 8 键 + 日志/diff/测试 verdict 分开记——测试 verdict 不
 *   在 artifact 里，见 MemberTaskReturn）。
 * - member_task_controls：成员任务控制行（taskId 主键；paused 是
 *   独立布尔字段，不是状态；pendingGoal 是改目标后等用户输入的
 *   新目标）。
 *
 * Room 打不开时回退内存、不删库：[TeamMemberStores.open] 在探测
 * 查询抛错时返回 [InMemoryTeamMemberStore]，数据库文件原样保留
 * （不调 deleteDatabase、不清表），下次打开再试 Room。内存实现
 * 与 Room 实现同一接口，行为一致（校验、去重、授权检查都在
 * MemberManager 一层，不在存储层分叉）。
 */

// ---------------------------------------------------------------------------
// team_members
// ---------------------------------------------------------------------------

@Entity(tableName = "team_members")
data class TeamMemberEntity(
    @PrimaryKey val memberId: String,
    val agentId: String,
    val displayName: String,
    val role: String,
    val kind: String,
    val transport: String,
    val endpointDeviceId: String? = null,
    val endpointLabel: String? = null,
    val endpointNote: String? = null,
    val baseUrl: String? = null,
    val authKind: String,
    val authRef: String? = null,
    val quotaNote: String? = null,
    val quotaState: String,
    val driverAgentId: String? = null,
    val shapeStatus: String,
    val connectionState: String,
    val connectivity: String,
    val authorizedProjectIdsJson: String = "[]",
    val lastStateUpdatedAtEpochSeconds: Long? = null,
    val createdAtEpochSeconds: Long = 0,
    val updatedAtEpochSeconds: Long = 0,
)

fun TeamMember.toEntity(): TeamMemberEntity {
    val array = JSONArray()
    authorizedProjectIds.forEach { array.put(it) }
    return TeamMemberEntity(
        memberId = memberId,
        agentId = agentId,
        displayName = displayName,
        role = role.name,
        kind = kind.name,
        transport = transport.name,
        endpointDeviceId = endpointDeviceId,
        endpointLabel = endpointLabel,
        endpointNote = endpointNote,
        baseUrl = baseUrl,
        authKind = authKind.name,
        authRef = authRef,
        quotaNote = quotaNote,
        quotaState = quotaState.name,
        driverAgentId = driverAgentId,
        shapeStatus = shapeStatus.name,
        connectionState = connectionState.name,
        connectivity = connectivity.name,
        authorizedProjectIdsJson = array.toString(),
        lastStateUpdatedAtEpochSeconds = lastStateUpdatedAtEpochSeconds,
        createdAtEpochSeconds = createdAtEpochSeconds,
        updatedAtEpochSeconds = updatedAtEpochSeconds,
    )
}

fun TeamMemberEntity.toMember(): TeamMember {
    val projects = mutableListOf<String>()
    try {
        val array = JSONArray(authorizedProjectIdsJson)
        for (i in 0 until array.length()) {
            val id = array.optString(i, "")
            if (id.isNotEmpty()) projects += id
        }
    } catch (_: Exception) {
        // 坏 cell 不毁掉整行读：当作空授权，下次合法写入再修。
    }
    return TeamMember(
        memberId = memberId,
        agentId = agentId,
        displayName = displayName,
        role = runCatching { MemberRole.valueOf(role) }.getOrDefault(MemberRole.DEVELOPER),
        kind = runCatching { MemberKind.valueOf(kind) }.getOrDefault(MemberKind.DEVICE_CLI),
        transport = runCatching { MemberTransport.valueOf(transport) }
            .getOrDefault(MemberTransport.LOCAL_SANDBOX),
        endpointDeviceId = endpointDeviceId,
        endpointLabel = endpointLabel,
        endpointNote = endpointNote,
        baseUrl = baseUrl,
        authKind = runCatching { MemberAuthKind.valueOf(authKind) }
            .getOrDefault(MemberAuthKind.NONE),
        authRef = authRef,
        quotaNote = quotaNote,
        quotaState = runCatching { MemberQuotaState.valueOf(quotaState) }
            .getOrDefault(MemberQuotaState.UNKNOWN),
        driverAgentId = driverAgentId,
        shapeStatus = runCatching { MemberShapeStatus.valueOf(shapeStatus) }
            .getOrDefault(MemberShapeStatus.UNVERIFIED),
        connectionState = runCatching { MemberConnectionState.valueOf(connectionState) }
            .getOrDefault(MemberConnectionState.NOT_CONNECTED),
        connectivity = runCatching { MemberConnectivity.valueOf(connectivity) }
            .getOrDefault(MemberConnectivity.NOT_CHECKED),
        authorizedProjectIds = projects,
        lastStateUpdatedAtEpochSeconds = lastStateUpdatedAtEpochSeconds,
        createdAtEpochSeconds = createdAtEpochSeconds,
        updatedAtEpochSeconds = updatedAtEpochSeconds,
    )
}

@Dao
interface TeamMemberDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(member: TeamMemberEntity)

    @Query("SELECT * FROM team_members WHERE memberId = :memberId")
    fun getById(memberId: String): TeamMemberEntity?

    @Query("SELECT * FROM team_members ORDER BY memberId ASC")
    fun getAll(): List<TeamMemberEntity>

    @Query("DELETE FROM team_members WHERE memberId = :memberId")
    fun deleteById(memberId: String): Int

    @Query("SELECT COUNT(*) FROM team_members")
    fun countAll(): Int
}

// ---------------------------------------------------------------------------
// member_project_grants
// ---------------------------------------------------------------------------

@Entity(tableName = "member_project_grants", primaryKeys = ["memberId", "projectId"])
data class MemberProjectGrantEntity(
    val memberId: String,
    val projectId: String,
    val grantedAtEpochSeconds: Long,
    val grantedBy: String,
)

@Dao
interface MemberProjectGrantDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(grant: MemberProjectGrantEntity)

    @Query(
        "SELECT * FROM member_project_grants WHERE memberId = :memberId " +
            "ORDER BY projectId ASC",
    )
    fun listByMember(memberId: String): List<MemberProjectGrantEntity>

    @Query(
        "SELECT * FROM member_project_grants WHERE memberId = :memberId " +
            "AND projectId = :projectId",
    )
    fun get(memberId: String, projectId: String): MemberProjectGrantEntity?

    @Query(
        "DELETE FROM member_project_grants WHERE memberId = :memberId " +
            "AND projectId = :projectId",
    )
    fun delete(memberId: String, projectId: String): Int

    @Query("DELETE FROM member_project_grants WHERE memberId = :memberId")
    fun deleteByMember(memberId: String): Int
}

// ---------------------------------------------------------------------------
// member_returns：交付记录 8 键 + 日志/diff/测试 verdict 分开记
// ---------------------------------------------------------------------------

@Entity(tableName = "member_returns", primaryKeys = ["taskId", "attemptId"])
data class MemberReturnEntity(
    val taskId: String,
    val attemptId: String,
    val memberId: String,
    /** 交付记录 8 键（delivery_record.py 的 build_delivery_record 形状）。 */
    val branch: String? = null,
    val commit: String? = null,
    val prUrl: String? = null,
    val totalPages: Int? = null,
    val pagesReceived: Int? = null,
    val truncated: Boolean = false,
    val filesJson: String = "[]",
    val summary: String? = null,
    /** 日志行（与 artifact 分开记）。 */
    val logsJson: String = "[]",
    /** diff 摘要（与 artifact 分开记；完整 patch 不进本表）。 */
    val diffSummary: String? = null,
    /** 测试 verdict（PASSED / FAILED / NOT_RUN），不在 artifact 里。 */
    val testVerdict: String? = null,
    val testSummary: String? = null,
    val recordedAtEpochSeconds: Long,
) {
    /** 日志行（logsJson 的解析版；坏 cell 当作空清单）。 */
    fun logsOf(): List<String> {
        val out = mutableListOf<String>()
        try {
            val array = org.json.JSONArray(logsJson)
            for (i in 0 until array.length()) {
                val line = array.optString(i, "")
                if (line.isNotEmpty()) out += line
            }
        } catch (_: Exception) {
            // 坏 cell 当作空清单，不毁掉整行读。
        }
        return out
    }
}

@Dao
interface MemberReturnDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(record: MemberReturnEntity)

    @Query(
        "SELECT * FROM member_returns WHERE taskId = :taskId " +
            "AND attemptId = :attemptId",
    )
    fun get(taskId: String, attemptId: String): MemberReturnEntity?

    @Query(
        "SELECT * FROM member_returns WHERE taskId = :taskId " +
            "ORDER BY recordedAtEpochSeconds ASC",
    )
    fun listByTask(taskId: String): List<MemberReturnEntity>

    @Query("DELETE FROM member_returns WHERE taskId = :taskId")
    fun deleteByTask(taskId: String): Int
}

// ---------------------------------------------------------------------------
// member_task_controls：paused 是独立布尔字段，不是状态
// ---------------------------------------------------------------------------

@Entity(tableName = "member_task_controls")
data class MemberTaskControlEntity(
    @PrimaryKey val taskId: String,
    val memberId: String,
    val paused: Boolean = false,
    /** 改目标后等用户输入的新目标；未改目标时为空。 */
    val pendingGoal: String? = null,
    val updatedAtEpochSeconds: Long,
)

@Dao
interface MemberTaskControlDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(control: MemberTaskControlEntity)

    @Query("SELECT * FROM member_task_controls WHERE taskId = :taskId")
    fun getByTask(taskId: String): MemberTaskControlEntity?

    @Query("DELETE FROM member_task_controls WHERE taskId = :taskId")
    fun deleteByTask(taskId: String): Int
}

// ---------------------------------------------------------------------------
// Store 接口与实现
// ---------------------------------------------------------------------------

/** 成员存储口：Room 与内存实现同一接口。 */
interface TeamMemberStore {
    fun saveMember(member: TeamMember)
    fun getMember(memberId: String): TeamMember?
    fun listMembers(): List<TeamMember>
    fun deleteMember(memberId: String)

    fun grantProject(memberId: String, projectId: String, grantedBy: String, nowEpochSeconds: Long)
    fun revokeProject(memberId: String, projectId: String)
    fun listProjects(memberId: String): List<String>
    fun isAuthorized(memberId: String, projectId: String): Boolean

    fun saveReturn(record: MemberReturnEntity)
    fun getReturn(taskId: String, attemptId: String): MemberReturnEntity?
    fun listReturns(taskId: String): List<MemberReturnEntity>

    fun saveControl(control: MemberTaskControlEntity)
    fun getControl(taskId: String): MemberTaskControlEntity?
    fun deleteControl(taskId: String)
}

class RoomTeamMemberStore(private val db: TaskDatabase) : TeamMemberStore {
    private val memberDao: TeamMemberDao = db.teamMemberDao()
    private val grantDao: MemberProjectGrantDao = db.memberProjectGrantDao()
    private val returnDao: MemberReturnDao = db.memberReturnDao()
    private val controlDao: MemberTaskControlDao = db.memberTaskControlDao()
    private val lock = Any()

    override fun saveMember(member: TeamMember) {
        synchronized(lock) { memberDao.upsert(member.toEntity()) }
    }

    override fun getMember(memberId: String): TeamMember? =
        synchronized(lock) { memberDao.getById(memberId)?.toMember() }

    override fun listMembers(): List<TeamMember> =
        synchronized(lock) { memberDao.getAll().map { it.toMember() } }

    override fun deleteMember(memberId: String) {
        synchronized(lock) {
            grantDao.deleteByMember(memberId)
            memberDao.deleteById(memberId)
        }
    }

    override fun grantProject(
        memberId: String,
        projectId: String,
        grantedBy: String,
        nowEpochSeconds: Long,
    ) {
        synchronized(lock) {
            grantDao.upsert(
                MemberProjectGrantEntity(
                    memberId = memberId,
                    projectId = projectId,
                    grantedAtEpochSeconds = nowEpochSeconds,
                    grantedBy = grantedBy,
                ),
            )
            // 绑定记录与成员行上的授权列表保持一致：行是显示，表是权威。
            memberDao.getById(memberId)?.toMember()?.let { member ->
                if (projectId !in member.authorizedProjectIds) {
                    memberDao.upsert(
                        member.copy(authorizedProjectIds = member.authorizedProjectIds + projectId)
                            .toEntity(),
                    )
                }
            }
        }
    }

    override fun revokeProject(memberId: String, projectId: String) {
        synchronized(lock) {
            grantDao.delete(memberId, projectId)
            memberDao.getById(memberId)?.toMember()?.let { member ->
                if (projectId in member.authorizedProjectIds) {
                    memberDao.upsert(
                        member.copy(authorizedProjectIds = member.authorizedProjectIds - projectId)
                            .toEntity(),
                    )
                }
            }
        }
    }

    override fun listProjects(memberId: String): List<String> =
        synchronized(lock) { grantDao.listByMember(memberId).map { it.projectId } }

    override fun isAuthorized(memberId: String, projectId: String): Boolean =
        synchronized(lock) { grantDao.get(memberId, projectId) != null }

    override fun saveReturn(record: MemberReturnEntity) {
        synchronized(lock) { returnDao.insert(record) }
    }

    override fun getReturn(taskId: String, attemptId: String): MemberReturnEntity? =
        synchronized(lock) { returnDao.get(taskId, attemptId) }

    override fun listReturns(taskId: String): List<MemberReturnEntity> =
        synchronized(lock) { returnDao.listByTask(taskId) }

    override fun saveControl(control: MemberTaskControlEntity) {
        synchronized(lock) { controlDao.upsert(control) }
    }

    override fun getControl(taskId: String): MemberTaskControlEntity? =
        synchronized(lock) { controlDao.getByTask(taskId) }

    override fun deleteControl(taskId: String) {
        synchronized(lock) { controlDao.deleteByTask(taskId) }
    }
}

/** 内存实现（单测用；Room 打不开时的回退目标）。 */
class InMemoryTeamMemberStore : TeamMemberStore {
    private val lock = Any()
    private val members = LinkedHashMap<String, TeamMember>()
    private val grants = HashMap<String, MutableSet<String>>()
    private val returns = LinkedHashMap<Pair<String, String>, MemberReturnEntity>()
    private val controls = HashMap<String, MemberTaskControlEntity>()

    override fun saveMember(member: TeamMember) {
        synchronized(lock) { members[member.memberId] = member }
    }

    override fun getMember(memberId: String): TeamMember? =
        synchronized(lock) { members[memberId] }

    override fun listMembers(): List<TeamMember> =
        synchronized(lock) { members.values.sortedBy { it.memberId } }

    override fun deleteMember(memberId: String) {
        synchronized(lock) {
            members.remove(memberId)
            grants.remove(memberId)
        }
    }

    override fun grantProject(
        memberId: String,
        projectId: String,
        grantedBy: String,
        nowEpochSeconds: Long,
    ) {
        synchronized(lock) {
            grants.getOrPut(memberId) { mutableSetOf() } += projectId
            members[memberId]?.let { member ->
                if (projectId !in member.authorizedProjectIds) {
                    members[memberId] =
                        member.copy(authorizedProjectIds = member.authorizedProjectIds + projectId)
                }
            }
        }
    }

    override fun revokeProject(memberId: String, projectId: String) {
        synchronized(lock) {
            grants[memberId]?.remove(projectId)
            members[memberId]?.let { member ->
                members[memberId] =
                    member.copy(authorizedProjectIds = member.authorizedProjectIds - projectId)
            }
        }
    }

    override fun listProjects(memberId: String): List<String> =
        synchronized(lock) { grants[memberId]?.sorted() ?: emptyList() }

    override fun isAuthorized(memberId: String, projectId: String): Boolean =
        synchronized(lock) { grants[memberId]?.contains(projectId) == true }

    override fun saveReturn(record: MemberReturnEntity) {
        synchronized(lock) {
            val key = record.taskId to record.attemptId
            require(!returns.containsKey(key)) { "回传记录已存在：${record.taskId}/${record.attemptId}" }
            returns[key] = record
        }
    }

    override fun getReturn(taskId: String, attemptId: String): MemberReturnEntity? =
        synchronized(lock) { returns[taskId to attemptId] }

    override fun listReturns(taskId: String): List<MemberReturnEntity> =
        synchronized(lock) {
            returns.values.filter { it.taskId == taskId }
                .sortedBy { it.recordedAtEpochSeconds }
        }

    override fun saveControl(control: MemberTaskControlEntity) {
        synchronized(lock) { controls[control.taskId] = control }
    }

    override fun getControl(taskId: String): MemberTaskControlEntity? =
        synchronized(lock) { controls[taskId] }

    override fun deleteControl(taskId: String) {
        synchronized(lock) { controls.remove(taskId) }
    }
}

/**
 * 打开成员存储：先试 Room，失败回退内存。
 *
 * 探测查询抛错（文件损坏、迁移失败、磁盘不可用）时返回
 * [InMemoryTeamMemberStore]；数据库文件原样保留，不删除、不清表，
 * 下次打开再试 Room。调用方须在后台线程调用（Room 默认禁主线程
 * 访问；测试可用 [allowMainThreadQueries]）。
 */
object TeamMemberStores {
    /** SQLite 文件头（16 字节），见 open() 的文件头预检。 */
    private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.UTF_8)

    fun open(
        context: Context,
        dbName: String = "wherevercode-tasks.db",
        allowMainThreadQueries: Boolean = false,
    ): TeamMemberStore {
        // 先验文件头：Room 的打开是懒的，探针查询在 Robolectric
        // 下对坏文件未必抛错（CI 42720586070：25 字节垃圾文件，
        // countAll() 探针没抛错，误返回了 Room store）。SQLite 文件
        // 一定以 "SQLite format 3\0" 开头；非空文件头不对，就是
        // Room 打不开的坏库，直接回内存，不碰 Room、不删文件。
        // 空文件是合法的空库（Room 会初始化），不回退。
        val headerOk = runCatching {
            val file = context.getDatabasePath(dbName)
            if (!file.exists() || file.length() == 0L) true
            else {
                val head = ByteArray(16)
                file.inputStream().use { it.read(head) }
                head.contentEquals(SQLITE_HEADER)
            }
        }.getOrDefault(true)
        if (!headerOk) return InMemoryTeamMemberStore()
        val db = try {
            TaskDatabase.create(context, dbName, allowMainThreadQueries)
        } catch (_: Exception) {
            return InMemoryTeamMemberStore()
        }
        return try {
            // 探测查询：建库是懒的，第一次 DAO 访问才真的打开文件。
            db.teamMemberDao().countAll()
            RoomTeamMemberStore(db)
        } catch (_: Exception) {
            runCatching { db.close() }
            InMemoryTeamMemberStore()
        }
    }
}
