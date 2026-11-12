package wherevercode.agent.core.task

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID
import org.json.JSONArray
import wherevercode.agent.brain.ConversationDao
import wherevercode.agent.brain.ConversationEntity
import wherevercode.agent.brain.ConversationMessageDao
import wherevercode.agent.brain.ConversationMessageEntity
import wherevercode.agent.core.control.ControlAuditDao
import wherevercode.agent.core.control.ControlAuditEntity
import wherevercode.agent.core.control.ControlCounterEntity
import wherevercode.agent.core.control.ControlDao
import wherevercode.agent.core.control.ControlOperationEntity
import wherevercode.agent.core.control.ControlStateEntity
import wherevercode.agent.core.security.DeviceGrantEntity
import wherevercode.agent.core.security.GrantDao
import wherevercode.agent.core.security.GrantEntity
import wherevercode.agent.core.security.PairingDao
import wherevercode.agent.core.security.PairingEntity
import wherevercode.agent.core.security.RevocationDao
import wherevercode.agent.core.security.RevokedCredentialEntity
import wherevercode.agent.core.security.RevokedHostEntity
import wherevercode.agent.core.security.RevokedTaskEntity
import wherevercode.agent.core.session.SessionDao
import wherevercode.agent.core.session.SessionDeliveryDao
import wherevercode.agent.core.session.SessionDeliveryEntity
import wherevercode.agent.core.session.SessionEntity
import wherevercode.agent.core.session.SessionMessageDao
import wherevercode.agent.core.session.SessionMessageEntity
import wherevercode.agent.ui.DrawerPrefsDao
import wherevercode.agent.ui.DrawerPrefsEntity
import wherevercode.agent.core.team.FanoutBranchDao
import wherevercode.agent.core.team.FanoutBranchEntity
import wherevercode.agent.core.team.FanoutRunDao
import wherevercode.agent.core.team.FanoutRunEntity
import wherevercode.agent.core.team.MemberProjectGrantDao
import wherevercode.agent.core.team.MemberProjectGrantEntity
import wherevercode.agent.core.team.MemberReturnDao
import wherevercode.agent.core.team.MemberReturnEntity
import wherevercode.agent.core.team.MemberTaskControlDao
import wherevercode.agent.core.team.MemberTaskControlEntity
import wherevercode.agent.core.team.TeamMemberDao
import wherevercode.agent.core.team.TeamMemberEntity

/**
 * TaskStore: Room/SQLite implementation (stage 2).
 *
 * Borrowed from the old project (audit section 2, #7) only as rules:
 * (a) optimistic-lock version, (b) dependency-graph acyclic check,
 * (c) lease expiry回收. Storage is Room/SQLite, not the old server's
 * JSON files. Field names follow RECORD_FIELDS (see TaskContract).
 *
 * Rules enforced here, not just in callers:
 * - update MUST carry expected_version; a mismatch raises StaleTaskUpdate.
 * - every state change MUST pass TaskStates.checkTransition; an illegal
 *   transition raises and nothing is written.
 * - claim lease is 600s (P0: 10 minutes, not the old 90s); [expireLeases]
 *   returns expired tasks to `requeued` (attempt >= 3 goes to `failed`,
 *   the dead letter) and flags needs_attention so the loss is visible,
 *   never silent. Every state change also writes a [TaskEvent] row in
 *   the same transaction (audit trail, see TaskEventLog.kt).
 * - [expireLeases] only touches dispatched tasks (`sessionId IS NULL`):
 *   chat-turn mirrors always carry a sessionId and their lease is never
 *   swept, so a long turn is never yanked back to queued mid-execution.
 *
 * Callers must call this store off the main thread: Room forbids
 * main-thread database access and these methods are synchronous.
 */
@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val taskId: String,
    val projectId: String? = null,
    val sessionId: String? = null,
    val title: String = "",
    val description: String? = null,
    val state: String = "queued",
    val assignedAgentId: String? = null,
    val parentTaskId: String? = null,
    val dependenciesJson: String = "[]",
    val priority: Int = 0,
    val createdAt: Long = 0,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val updatedAt: Long = 0,
    val result: String? = null,
    val error: String? = null,
    val version: Long = 1,
    val brainId: String? = null,
    val needsAttention: Boolean = false,
    val leaseOwner: String? = null,
    val leaseExpiresAt: Long? = null,
    /** C3 second stage: the team member holding this task. */
    val memberId: String? = null,
    /** C3 second stage: claim attempt count (old `attempt`, starts 0). */
    val attempt: Int = 0,
    /** C3 second stage: this attempt's id (old `attempt_id`, uuid hex). */
    val attemptId: String? = null,
    /** M2 control counters (snapshot taken at claim time; only ControlPlane bumps them). */
    val goalVersion: Long = 1,
    val authEpoch: Long = 1,
    /** Selected Hand route; the grant reference is not itself a credential. */
    val handDeviceId: String? = null,
    val handAuthorizationId: String? = null,
    val handFolder: String? = null,
)

@Dao
interface TaskDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(entity: TaskEntity)

    @Query("SELECT * FROM tasks WHERE taskId = :taskId")
    fun getById(taskId: String): TaskEntity?

    @Query("SELECT * FROM tasks")
    fun getAll(): List<TaskEntity>

    @Query(
        "SELECT * FROM tasks WHERE " +
            "(:projectId IS NULL OR projectId = :projectId) AND " +
            "(:sessionId IS NULL OR sessionId = :sessionId)",
    )
    fun listFiltered(projectId: String?, sessionId: String?): List<TaskEntity>

    @Query(
        "UPDATE tasks SET title = :title, description = :description, state = :state, " +
            "assignedAgentId = :assignedAgentId, parentTaskId = :parentTaskId, " +
            "dependenciesJson = :dependenciesJson, priority = :priority, " +
            "startedAt = :startedAt, finishedAt = :finishedAt, updatedAt = :updatedAt, " +
            "result = :result, error = :error, version = version + 1, " +
            "brainId = :brainId, needsAttention = :needsAttention, " +
            "leaseOwner = :leaseOwner, leaseExpiresAt = :leaseExpiresAt, " +
            "memberId = :memberId, attempt = :attempt, attemptId = :attemptId, " +
            "goalVersion = :goalVersion, authEpoch = :authEpoch, " +
            "handDeviceId = :handDeviceId, handAuthorizationId = :handAuthorizationId, " +
            "handFolder = :handFolder " +
            "WHERE taskId = :taskId AND version = :expectedVersion",
    )
    fun updateAll(
        taskId: String,
        title: String,
        description: String?,
        state: String,
        assignedAgentId: String?,
        parentTaskId: String?,
        dependenciesJson: String,
        priority: Int,
        startedAt: Long?,
        finishedAt: Long?,
        updatedAt: Long,
        result: String?,
        error: String?,
        brainId: String?,
        needsAttention: Boolean,
        leaseOwner: String?,
        leaseExpiresAt: Long?,
        memberId: String?,
        attempt: Int,
        attemptId: String?,
        expectedVersion: Long,
        goalVersion: Long,
        authEpoch: Long,
        handDeviceId: String?,
        handAuthorizationId: String?,
        handFolder: String?,
    ): Int

    /**
     * ControlPlane-only counter sync (M2): persist a bumped
     * goalVersion/authEpoch on the task row after ControlPlane bumped
     * the authoritative counters in control_counters. Does NOT bump
     * `version`: the counters are not a task edit.
     */
    @Query(
        "UPDATE tasks SET goalVersion = :goalVersion, authEpoch = :authEpoch " +
            "WHERE taskId = :taskId",
    )
    fun updateCounters(taskId: String, goalVersion: Long, authEpoch: Long): Int

    /**
     * Expired leases, excluding tasks whose control state is PAUSED or
     * STOPPING (same database, anti-join on control_state, U5): a
     * paused/stopping task is never silently returned to queued.
     *
     * P0: only dispatched tasks (`sessionId IS NULL`). Chat-turn mirrors
     * (TurnTaskRecorder) always carry a sessionId and take a claim lease
     * at turn start without renewing it — their lease must never trigger
     * a re-queue, or a long turn would be yanked back mid-execution.
     */
    @Query(
        "SELECT * FROM tasks WHERE leaseExpiresAt IS NOT NULL " +
            "AND leaseExpiresAt <= :now AND state IN ('assigned', 'working') " +
            "AND sessionId IS NULL " +
            "AND NOT EXISTS (SELECT 1 FROM control_state WHERE control_state.taskId = tasks.taskId " +
            "AND control_state.status IN ('PAUSED', 'STOPPING'))",
    )
    fun expiredLeases(now: Long): List<TaskEntity>

    /** Expired leases of PAUSED/STOPPING tasks: flagged, never re-queued (see [expireLeases]). */
    @Query(
        "SELECT * FROM tasks WHERE leaseExpiresAt IS NOT NULL " +
            "AND leaseExpiresAt <= :now AND state IN ('assigned', 'working') " +
            "AND sessionId IS NULL " +
            "AND EXISTS (SELECT 1 FROM control_state WHERE control_state.taskId = tasks.taskId " +
            "AND control_state.status IN ('PAUSED', 'STOPPING'))",
    )
    fun expiredLeasesPaused(now: Long): List<TaskEntity>

    @Query("DELETE FROM tasks WHERE taskId = :taskId")
    fun deleteById(taskId: String): Int
}

@Database(
    entities = [
        TaskEntity::class,
        ControlCounterEntity::class,
        ControlStateEntity::class,
        ControlAuditEntity::class,
        ControlOperationEntity::class,
        GrantEntity::class,
        DeviceGrantEntity::class,
        RevokedCredentialEntity::class,
        RevokedTaskEntity::class,
        RevokedHostEntity::class,
        // M2 merge: session entities (S2) registered here — the
        // TaskDatabase is the composition root, so core/task imports
        // entity/DAO classes from core/control, core/security and
        // core/session for the @Database entities list + DAO accessors
        // only (no business-logic dependency; see module-interfaces.json
        // exceptions). The same exception already covers control/security.
        SessionEntity::class,
        SessionMessageEntity::class,
        SessionDeliveryEntity::class,
        // V5 Phase B C1 (W2): conversation history, task steps and
        // pairing records — previously in-memory only, lost on restart
        // (PROJECT.md §2.2). New tables only; the migration is
        // CREATE-only, see MIGRATION_2_3 below.
        ConversationEntity::class,
        ConversationMessageEntity::class,
        TaskStepEntity::class,
        PairingEntity::class,
        // V5 Phase B C2 (batch 2): drawer customisation — one single-row
        // table, same shared database, same CREATE-only rule as C1.
        DrawerPrefsEntity::class,
        // V5 Phase B C3 (second stage, member framework): team members,
        // member ↔ project grants, member returns and member task
        // controls — four new tables, same CREATE-only rule.
        TeamMemberEntity::class,
        MemberProjectGrantEntity::class,
        MemberReturnEntity::class,
        MemberTaskControlEntity::class,
        // V5 技能记忆（2026-11-11）：项目长期技能，一行一条，
        // 见 core/task/SkillDatabase.kt；迁移只建新表，见 MIGRATION_6_7。
        SkillEntity::class,
        // P0 耐久任务引擎（2026-11-11）：任务事件日志（审计轨迹），
        // 见 core/task/TaskEventLog.kt；迁移只建新表，见 MIGRATION_7_8。
        TaskEventEntity::class,
        // P2 规划→编码→干净上下文审查流水线（2026-11-11）：流水线态
        // 与审查 verdict 历史，见 core/task/PipelineStore.kt；迁移只
        // 建新表，见 MIGRATION_8_9。
        PipelineStateEntity::class,
        PipelineVerdictEntity::class,
        // P3 只读扇出（2026-11-11）：一次扇出一行 + 一个分支一行，
        // 见 core/team/TeamFanoutStore.kt；迁移只建新表，见 MIGRATION_9_10。
        FanoutRunEntity::class,
        FanoutBranchEntity::class,
        // 任务拆分（2026-11-11，大脑总管自动拆分）：拆分态与子任务
        // 清单，见 core/task/DecompositionStore.kt；迁移只建新表，
        // 见 MIGRATION_9_10。
        DecompositionStateEntity::class,
        DecompositionSubtaskEntity::class,
    ],
    version = 11,
    exportSchema = false,
)
abstract class TaskDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun controlDao(): ControlDao
    abstract fun controlAuditDao(): ControlAuditDao
    abstract fun grantDao(): GrantDao
    abstract fun revocationDao(): RevocationDao
    abstract fun sessionDao(): SessionDao
    abstract fun sessionMessageDao(): SessionMessageDao
    abstract fun sessionDeliveryDao(): SessionDeliveryDao
    abstract fun conversationDao(): ConversationDao
    abstract fun conversationMessageDao(): ConversationMessageDao
    abstract fun taskStepDao(): TaskStepDao
    abstract fun pairingDao(): PairingDao
    abstract fun drawerPrefsDao(): DrawerPrefsDao
    abstract fun teamMemberDao(): TeamMemberDao
    abstract fun memberProjectGrantDao(): MemberProjectGrantDao
    abstract fun memberReturnDao(): MemberReturnDao
    abstract fun memberTaskControlDao(): MemberTaskControlDao
    abstract fun skillDao(): SkillDao
    abstract fun taskEventDao(): TaskEventDao
    abstract fun pipelineStateDao(): PipelineStateDao
    abstract fun pipelineVerdictDao(): PipelineVerdictDao
    abstract fun fanoutRunDao(): FanoutRunDao
    abstract fun fanoutBranchDao(): FanoutBranchDao
    abstract fun decompositionStateDao(): DecompositionStateDao
    abstract fun decompositionSubtaskDao(): DecompositionSubtaskDao

    companion object {
        /**
         * Migration 1 -> 2 (M2): keep every existing task row; old rows
         * get goalVersion/authEpoch = 1 (U3 initial value). Creates the
         * control tables, the grants table + device_grant row, the
         * revocation tables, and the session tables (S2, same database,
         * U5: one transaction covers control / task / message / audit).
         *
         * Schema note (Room 2.6.1 validation): the new tables are
         * created WITHOUT SQL DEFAULT clauses — the entities declare no
         * @ColumnInfo(defaultValue), and Room ignores database-side
         * defaults when the entity declares none, so the migrated
         * schema matches the entities exactly. The two ALTER TABLE
         * statements DO need DEFAULT 1 (SQLite requires a non-null
         * default when adding a NOT NULL column to a table with rows).
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN goalVersion INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE tasks ADD COLUMN authEpoch INTEGER NOT NULL DEFAULT 1")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS control_counters (" +
                        "taskId TEXT NOT NULL PRIMARY KEY, " +
                        "goalVersion INTEGER NOT NULL DEFAULT 1, " +
                        "authEpoch INTEGER NOT NULL DEFAULT 1)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS control_state (" +
                        "taskId TEXT NOT NULL PRIMARY KEY, " +
                        "status TEXT NOT NULL DEFAULT 'RUNNING', " +
                        "cancelPending INTEGER NOT NULL DEFAULT 0, " +
                        "stopRequestedAt INTEGER, " +
                        "dispatchFrozen INTEGER NOT NULL DEFAULT 0, " +
                        "updatedAt INTEGER NOT NULL DEFAULT 0)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS control_audit_log (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "taskId TEXT NOT NULL, actor TEXT NOT NULL, action TEXT NOT NULL, " +
                        "goalVersion INTEGER NOT NULL, epoch INTEGER NOT NULL, " +
                        "operationId TEXT NOT NULL, ok INTEGER NOT NULL, " +
                        "errorCode TEXT, reason TEXT, ts INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS control_operation_ids (" +
                        "operationId TEXT NOT NULL PRIMARY KEY, taskId TEXT NOT NULL, " +
                        "action TEXT NOT NULL, ok INTEGER NOT NULL, confirmed INTEGER NOT NULL, " +
                        "status TEXT NOT NULL, errorCode TEXT, reason TEXT, " +
                        "recordedAt INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS grants (" +
                        "agentId TEXT NOT NULL, folder TEXT NOT NULL, " +
                        "grantedAt TEXT NOT NULL, grantedBy TEXT NOT NULL DEFAULT 'local', " +
                        "PRIMARY KEY(agentId, folder))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS device_grant (" +
                        "id INTEGER NOT NULL PRIMARY KEY, " +
                        "grantedAt TEXT NOT NULL, grantedBy TEXT NOT NULL DEFAULT 'local')",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS revoked_credentials (" +
                        "credentialId TEXT NOT NULL PRIMARY KEY, revokedAt INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS revoked_tasks (" +
                        "taskId TEXT NOT NULL PRIMARY KEY, revokedAt INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS revoked_hosts (" +
                        "hostId TEXT NOT NULL PRIMARY KEY, revokedAt INTEGER NOT NULL)",
                )
                // Session tables (S2, merge coordinator): no DEFAULT
                // clauses — see the schema note above.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS sessions (" +
                        "sessionId TEXT NOT NULL PRIMARY KEY, " +
                        "name TEXT NOT NULL, kind TEXT NOT NULL, " +
                        "ownerId TEXT NOT NULL, membersJson TEXT NOT NULL, " +
                        "taskId TEXT, goalId TEXT, attemptId TEXT, " +
                        "createdAtEpochSeconds INTEGER NOT NULL, " +
                        "updatedAtEpochSeconds INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_ownerId ON sessions (ownerId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_taskId ON sessions (taskId)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS session_messages (" +
                        "messageId TEXT NOT NULL PRIMARY KEY, " +
                        "conversationId TEXT NOT NULL, senderId TEXT NOT NULL, " +
                        "recipientId TEXT, taskId TEXT, attemptId TEXT, " +
                        "messageType TEXT NOT NULL, causationId TEXT, " +
                        "idempotencyKey TEXT NOT NULL, operationId TEXT, " +
                        "deliveryState TEXT NOT NULL, " +
                        "authVersion INTEGER NOT NULL, goalVersion INTEGER NOT NULL, " +
                        "ttlSeconds INTEGER, content TEXT NOT NULL, " +
                        "createdAtEpochSeconds INTEGER NOT NULL, " +
                        "auditActor TEXT, auditAction TEXT, " +
                        "auditEpochSeconds INTEGER, auditOk INTEGER, " +
                        "auditErrorCode TEXT)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_session_messages_conversationId " +
                        "ON session_messages (conversationId)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_session_messages_taskId " +
                        "ON session_messages (taskId)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS session_deliveries (" +
                        "messageId TEXT NOT NULL, memberId TEXT NOT NULL, " +
                        "deliveryState TEXT NOT NULL, " +
                        "updatedAtEpochSeconds INTEGER NOT NULL, " +
                        "PRIMARY KEY(messageId, memberId))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_session_deliveries_memberId " +
                        "ON session_deliveries (memberId)",
                )
            }
        }

        /**
         * Migration 2 -> 3 (V5 Phase B C1, W2): keep every existing row
         * (tasks, sessions, control, grants, revocation — nothing is
         * touched here); only CREATE the four new tables for
         * conversation history, task steps and pairing records, which
         * were in-memory only until now.
         *
         * Schema note (same rule as MIGRATION_1_2): the new tables are
         * created WITHOUT SQL DEFAULT clauses — the entities declare no
         * @ColumnInfo(defaultValue), and Room ignores database-side
         * defaults when the entity declares none, so the migrated
         * schema matches the entities exactly. CREATE TABLE (unlike
         * ALTER TABLE ADD COLUMN) needs no default for NOT NULL columns.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS conversations (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "brainId TEXT, " +
                        "title TEXT NOT NULL, " +
                        "createdAtEpochSeconds INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_conversations_createdAtEpochSeconds " +
                        "ON conversations (createdAtEpochSeconds)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS conversation_messages (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "conversationId TEXT NOT NULL, " +
                        "role TEXT NOT NULL, " +
                        "content TEXT NOT NULL, " +
                        "source TEXT NOT NULL, " +
                        "createdAtEpochSeconds INTEGER NOT NULL, " +
                        "taskId TEXT, " +
                        "toolCallsJson TEXT NOT NULL, " +
                        "toolCallId TEXT)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_conversation_messages_conversationId " +
                        "ON conversation_messages (conversationId)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_conversation_messages_taskId " +
                        "ON conversation_messages (taskId)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS task_steps (" +
                        "stepId TEXT NOT NULL PRIMARY KEY, " +
                        "taskId TEXT NOT NULL, " +
                        "toolCallId TEXT NOT NULL, " +
                        "toolName TEXT NOT NULL, " +
                        "argsSummary TEXT NOT NULL, " +
                        "status TEXT NOT NULL, " +
                        "resultSummary TEXT, " +
                        "startedAtEpochSeconds INTEGER NOT NULL, " +
                        "finishedAtEpochSeconds INTEGER)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_task_steps_taskId " +
                        "ON task_steps (taskId)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS pairings (" +
                        "deviceId TEXT NOT NULL PRIMARY KEY, " +
                        "displayName TEXT NOT NULL, " +
                        "fingerprintSha256 TEXT NOT NULL, " +
                        "peerPublicKey BLOB, " +
                        "state TEXT NOT NULL, " +
                        "reason TEXT, " +
                        "updatedAtEpochSeconds INTEGER NOT NULL)",
                )
            }
        }

        /**
         * Migration 3 -> 4 (V5 Phase B C2, batch 2): keep every existing
         * row (tasks, sessions, control, grants, revocation,
         * conversations, task steps, pairings — nothing is touched
         * here); only CREATE the one new drawer_prefs table for the
         * drawer customisation that was previously not persisted at all.
         *
         * Schema note (same rule as MIGRATION_2_3): the new table is
         * created WITHOUT SQL DEFAULT clauses — the entity declares no
         * @ColumnInfo(defaultValue), and Room ignores database-side
         * defaults when the entity declares none, so the migrated
         * schema matches the entity exactly.
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS drawer_prefs (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "orderJson TEXT NOT NULL, " +
                        "hiddenJson TEXT NOT NULL, " +
                        "favoritesJson TEXT NOT NULL, " +
                        "nestedJson TEXT NOT NULL, " +
                        "moreExpanded INTEGER NOT NULL)",
                )
            }
        }

        /**
         * Migration 4 -> 5 (V5 Phase B C3, second stage, member
         * framework): keep every existing row (tasks, sessions,
         * control, grants, revocation, conversations, task steps,
         * pairings, drawer prefs — nothing is touched here); only
         * CREATE the four new member tables (team_members,
         * member_project_grants, member_returns,
         * member_task_controls).
         *
         * Schema note (same rule as MIGRATION_2_3): the new tables
         * are created WITHOUT SQL DEFAULT clauses — the entities
         * declare no @ColumnInfo(defaultValue), and Room ignores
         * database-side defaults when the entity declares none, so
         * the migrated schema matches the entities exactly.
         */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS team_members (" +
                        "memberId TEXT NOT NULL PRIMARY KEY, " +
                        "agentId TEXT NOT NULL, " +
                        "displayName TEXT NOT NULL, " +
                        "role TEXT NOT NULL, " +
                        "kind TEXT NOT NULL, " +
                        "transport TEXT NOT NULL, " +
                        "endpointDeviceId TEXT, " +
                        "endpointLabel TEXT, " +
                        "endpointNote TEXT, " +
                        "baseUrl TEXT, " +
                        "authKind TEXT NOT NULL, " +
                        "authRef TEXT, " +
                        "quotaNote TEXT, " +
                        "shapeStatus TEXT NOT NULL, " +
                        "connectionState TEXT NOT NULL, " +
                        "connectivity TEXT NOT NULL, " +
                        "authorizedProjectIdsJson TEXT NOT NULL, " +
                        "lastStateUpdatedAtEpochSeconds INTEGER, " +
                        "createdAtEpochSeconds INTEGER NOT NULL, " +
                        "updatedAtEpochSeconds INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS member_project_grants (" +
                        "memberId TEXT NOT NULL, " +
                        "projectId TEXT NOT NULL, " +
                        "grantedAtEpochSeconds INTEGER NOT NULL, " +
                        "grantedBy TEXT NOT NULL, " +
                        "PRIMARY KEY(memberId, projectId))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS member_returns (" +
                        "taskId TEXT NOT NULL, " +
                        "attemptId TEXT NOT NULL, " +
                        "memberId TEXT NOT NULL, " +
                        "branch TEXT, " +
                        // commit 是 SQLite 保留字，手写 SQL 必须加引号
                        //（Room 自生成的建表语句会给标识符加反引号，
                        // 所以内存库能过、手写迁移会炸：near "commit"）。
                        "\"commit\" TEXT, " +
                        "prUrl TEXT, " +
                        "totalPages INTEGER, " +
                        "pagesReceived INTEGER, " +
                        "truncated INTEGER NOT NULL, " +
                        "filesJson TEXT NOT NULL, " +
                        "summary TEXT, " +
                        "logsJson TEXT NOT NULL, " +
                        "diffSummary TEXT, " +
                        "testVerdict TEXT, " +
                        "testSummary TEXT, " +
                        "recordedAtEpochSeconds INTEGER NOT NULL, " +
                        "PRIMARY KEY(taskId, attemptId))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS member_task_controls (" +
                        "taskId TEXT NOT NULL PRIMARY KEY, " +
                        "memberId TEXT NOT NULL, " +
                        "paused INTEGER NOT NULL, " +
                        "pendingGoal TEXT, " +
                        "updatedAtEpochSeconds INTEGER NOT NULL)",
                )
            }
        }

        /**
         * Migration 5 -> 6 (V5 Phase B C3, second stage, member
         * framework, part 2): tasks 表加三列成员认领字段（memberId /
         * attempt / attemptId）。旧行：memberId/attemptId 为空，
         * attempt 为 0（从未被成员认领过）。ALTER TABLE 加 NOT NULL
         * 列须带 DEFAULT（SQLite 要求），见 MIGRATION_1_2 的说明。
         */
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN memberId TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN attempt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN attemptId TEXT")
                // 成员表加两列（C3 第二阶段 §12–§14）：免费额度状态位与
                // 桌面 App 驱动 agent。旧行：quotaState 为 UNKNOWN（未核
                // 实），driverAgentId 为空。ALTER 加 NOT NULL 列须带
                // DEFAULT（SQLite 要求），见 MIGRATION_1_2 的说明。
                db.execSQL(
                    "ALTER TABLE team_members ADD COLUMN quotaState TEXT NOT NULL DEFAULT 'UNKNOWN'",
                )
                db.execSQL("ALTER TABLE team_members ADD COLUMN driverAgentId TEXT")
            }
        }

        /**
         * Migration 6 -> 7 (V5 技能记忆，2026-11-11)：只建一张新表
         * `skills`（一行一条技能，见 SkillDatabase.kt），不动任何旧
         * 表。列与 SkillEntity 一一对应；索引 projectId（取用时按
         * 项目列）与 updatedAtEpochSeconds（按更新时间列）。
         */
        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS skills (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "projectId TEXT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "triggersJson TEXT NOT NULL DEFAULT '[]', " +
                        "summary TEXT NOT NULL DEFAULT '', " +
                        "stepsJson TEXT NOT NULL DEFAULT '[]', " +
                        "toolsJson TEXT NOT NULL DEFAULT '[]', " +
                        "pitfallsJson TEXT NOT NULL DEFAULT '[]', " +
                        "evidenceTaskIdsJson TEXT NOT NULL DEFAULT '[]', " +
                        "successCount INTEGER NOT NULL DEFAULT 0, " +
                        "failureCount INTEGER NOT NULL DEFAULT 0, " +
                        "lastUsedEpochSeconds INTEGER NOT NULL DEFAULT 0, " +
                        "createdAtEpochSeconds INTEGER NOT NULL DEFAULT 0, " +
                        "updatedAtEpochSeconds INTEGER NOT NULL DEFAULT 0)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_skills_projectId ON skills (projectId)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_skills_updatedAtEpochSeconds " +
                        "ON skills (updatedAtEpochSeconds)",
                )
            }
        }

        /**
         * Migration 7 -> 8 (P0 耐久任务引擎，2026-11-11)：只建一张新表
         * `task_events`（任务事件日志/审计轨迹，见 TaskEventLog.kt），
         * 不动任何旧表。列与 TaskEventEntity 一一对应；索引
         * (taskId, createdAtEpochSeconds)（按任务读审计轨迹）。
         * 索引名须与 Room 生成的一致：
         * `index_task_events_taskId_createdAtEpochSeconds`。
         */
        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS task_events (" +
                        "eventId TEXT NOT NULL PRIMARY KEY, " +
                        "taskId TEXT NOT NULL, " +
                        "attemptId TEXT, " +
                        "kind TEXT NOT NULL, " +
                        "fromState TEXT, " +
                        "toState TEXT NOT NULL, " +
                        "actor TEXT, " +
                        "reason TEXT, " +
                        "createdAtEpochSeconds INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_task_events_taskId_createdAtEpochSeconds " +
                        "ON task_events (taskId, createdAtEpochSeconds)",
                )
            }
        }

        /**
         * Migration 8 -> 9 (P2 规划→编码→干净上下文审查流水线，
         * 2026-11-11)：只建两张新表 `pipeline_states`（一任务一行：
         * 开关/阶段/plan JSON/返工轮次/编码与审查者）与
         * `pipeline_verdicts`（审查 verdict 历史，append-only），
         * 不动任何旧表。列与 PipelineStateEntity /
         * PipelineVerdictEntity 一一对应；索引名须与 Room 生成的一致：
         * `index_pipeline_states_updatedAtEpochSeconds`、
         * `index_pipeline_verdicts_taskId_createdAtEpochSeconds`。
         */
        val MIGRATION_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS pipeline_states (" +
                        "taskId TEXT NOT NULL PRIMARY KEY, " +
                        "enabled INTEGER NOT NULL, " +
                        "stage TEXT NOT NULL, " +
                        "planStatus TEXT NOT NULL, " +
                        "planJson TEXT, " +
                        "reworkRounds INTEGER NOT NULL, " +
                        "coderAgentId TEXT, " +
                        "coderDeviceId TEXT, " +
                        "reviewerAgentId TEXT, " +
                        "reviewerDeviceId TEXT, " +
                        "createdAtEpochSeconds INTEGER NOT NULL, " +
                        "updatedAtEpochSeconds INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_pipeline_states_updatedAtEpochSeconds " +
                        "ON pipeline_states (updatedAtEpochSeconds)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS pipeline_verdicts (" +
                        "verdictId TEXT NOT NULL PRIMARY KEY, " +
                        "taskId TEXT NOT NULL, " +
                        "round INTEGER NOT NULL, " +
                        "verdict TEXT NOT NULL, " +
                        "reviewer TEXT NOT NULL, " +
                        "checklistJson TEXT NOT NULL, " +
                        "mechanicalJson TEXT, " +
                        "createdAtEpochSeconds INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_pipeline_verdicts_taskId_createdAtEpochSeconds " +
                        "ON pipeline_verdicts (taskId, createdAtEpochSeconds)",
                )
            }
        }

        /**
         * Migration 9 -> 10 (P3 只读扇出 + 任务拆分，2026-11-11）：
         * 只建四张新表，不动任何旧表：`fanout_runs`（一次扇出一行）与
         * `fanout_branches`（一个分支一行）；`decomposition_states`
         * （一父任务一行：拆分状态 / 集成线 / 基底分支 / 方案 JSON /
         * 合并结果 JSON）与 `decomposition_subtasks`（子任务清单）。
         * 列与 FanoutRunEntity / FanoutBranchEntity /
         * DecompositionStateEntity / DecompositionSubtaskEntity 一一对应；
         * 索引名须与 Room 生成的一致：
         * `index_fanout_runs_createdAtEpochSeconds`、
         * `index_fanout_branches_runId_createdAtEpochSeconds`、
         * `index_decomposition_states_updatedAtEpochSeconds`、
         * `index_decomposition_subtasks_parentTaskId`。
         * （合包注：P3 与拆分两路各写了一个 MIGRATION_9_10，update10
         * 合包时已并进这一个迁移，版本仍为 10。）
         */
        val MIGRATION_9_10: Migration = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS fanout_runs (" +
                        "runId TEXT NOT NULL PRIMARY KEY, " +
                        "question TEXT NOT NULL, " +
                        "taskId TEXT, " +
                        "createdAtEpochSeconds INTEGER NOT NULL, " +
                        "finishedAtEpochSeconds INTEGER)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_fanout_runs_createdAtEpochSeconds " +
                        "ON fanout_runs (createdAtEpochSeconds)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS fanout_branches (" +
                        "branchId TEXT NOT NULL PRIMARY KEY, " +
                        "runId TEXT NOT NULL, " +
                        "agentId TEXT NOT NULL, " +
                        "deviceId TEXT, " +
                        "displayName TEXT NOT NULL, " +
                        "status TEXT NOT NULL, " +
                        "summary TEXT, " +
                        "error TEXT, " +
                        "createdAtEpochSeconds INTEGER NOT NULL, " +
                        "finishedAtEpochSeconds INTEGER)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_fanout_branches_runId_createdAtEpochSeconds " +
                        "ON fanout_branches (runId, createdAtEpochSeconds)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS decomposition_states (" +
                        "taskId TEXT NOT NULL PRIMARY KEY, " +
                        "status TEXT NOT NULL, " +
                        "integrationBranch TEXT NOT NULL, " +
                        "baseBranch TEXT NOT NULL, " +
                        "proposalJson TEXT, " +
                        "mergeResultJson TEXT, " +
                        "createdAtEpochSeconds INTEGER NOT NULL, " +
                        "updatedAtEpochSeconds INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_decomposition_states_updatedAtEpochSeconds " +
                        "ON decomposition_states (updatedAtEpochSeconds)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS decomposition_subtasks (" +
                        "subtaskTaskId TEXT NOT NULL PRIMARY KEY, " +
                        "parentTaskId TEXT NOT NULL, " +
                        "subtaskIndex INTEGER NOT NULL, " +
                        "filesJson TEXT NOT NULL, " +
                        "branch TEXT NOT NULL, " +
                        "objective TEXT NOT NULL, " +
                        "createdAtEpochSeconds INTEGER NOT NULL, " +
                        "moduleLabel TEXT NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_decomposition_subtasks_parentTaskId " +
                        "ON decomposition_subtasks (parentTaskId)",
                )
            }
        }

        /** P1: persist the selected Hand route alongside its App task. */
        val MIGRATION_10_11: Migration = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN handDeviceId TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN handAuthorizationId TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN handFolder TEXT")
            }
        }

        /**
         * File-based database. Main-thread queries stay forbidden by
         * default (production callers must use a background thread);
         * tests may opt in with [allowMainThreadQueries] because
         * Robolectric runs them on the main thread.
         */
        fun create(
            context: Context,
            name: String = "wherevercode-tasks.db",
            allowMainThreadQueries: Boolean = false,
        ): TaskDatabase =
            Room.databaseBuilder(context, TaskDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
                .apply { if (allowMainThreadQueries) allowMainThreadQueries() }
                .build()

        fun inMemory(context: Context): TaskDatabase =
            Room.inMemoryDatabaseBuilder(context, TaskDatabase::class.java)
                // Unit tests (Robolectric) run DAO calls on the test thread,
                // which Room treats as the main thread. Test-only builder:
                // the file-based create() keeps the default (no main-thread
                // access), production callers must use a background thread.
                .allowMainThreadQueries()
                .build()
    }
}

/**
 * ControlPlane-only port for persisting bumped counters on the task row
 * (M2). Lives in core/task so core/task never imports core/control
 * (plan §1.3: control -> task only); implemented by [RoomTaskStore].
 */
interface TaskControlCounterStore {
    fun updateControlCounters(taskId: String, goalVersion: Long, authEpoch: Long): TaskRecord?
}

class RoomTaskStore(private val db: TaskDatabase) : TaskStore, TaskControlCounterStore {
    companion object {
        /**
         * 认领租约：600 秒（10 分钟，P0）。老项目是 90 秒；V5 真实任务
         * （一次大脑轮次、一次远端 CLI 调用）常以分钟计，90 秒不够一轮。
         * 心跳约 1/3 租约（180 秒，见两台执行器）。
         */
        const val LEASE_SECONDS: Long = 600

        /** 重试预算：租约过期回队最多 3 次认领，第 3 次过期即入死信（failed）。 */
        const val MAX_ATTEMPTS: Int = 3

        /** 租约回收的 actor（事件日志用）。 */
        const val LEASE_SWEEPER_ACTOR: String = "system:lease-sweeper"
    }

    private val dao: TaskDao = db.taskDao()
    private val eventDao: TaskEventDao = db.taskEventDao()
    private val lock = Any()

    override fun createTask(record: TaskRecord): TaskRecord {
        TaskStates.checkTransition(null, record.state)
        if (record.taskId.isBlank()) throw IllegalArgumentException("task_id 不能为空")
        val routeFields = listOf(record.handDeviceId, record.handAuthorizationId, record.handFolder)
        if (routeFields.any { it != null } && routeFields.any { it.isNullOrBlank() }) {
            throw IllegalArgumentException("Hand 目标、授权引用和授权目录必须同时提供")
        }
        // 锁顺序（2026-11-12 第 5 轮死锁教训）：先 synchronized(lock) 再开
        // 事务。反过来（事务里再取锁）与 getTask/listTasks（先锁后查库）
        // 构成死锁：内存库连接池只有 1 个连接，getTask 拿着锁等连接、
        // 事务拿着连接等锁，两边永远等不到对方。
        return synchronized(lock) {
            db.runInTransaction<TaskRecord> {
                if (dao.getById(record.taskId) != null) throw TaskAlreadyExists(record.taskId)
                validateDependencies(record)
                val now = nowSeconds()
                val entity = toEntity(record).copy(
                    createdAt = if (record.createdAt == 0L) now else record.createdAt,
                    updatedAt = if (record.updatedAt == 0L) now else record.updatedAt,
                    version = 1,
                )
                dao.insert(entity)
                val created = toRecord(entity)
                eventDao.insert(
                    TaskEvent(
                        eventId = UUID.randomUUID().toString(),
                        taskId = created.taskId,
                        attemptId = created.attemptId,
                        kind = TaskEventKind.CREATED,
                        fromState = null,
                        toState = created.state,
                        actor = null,
                        reason = null,
                        createdAtEpochSeconds = created.createdAt,
                    ).toEntity(),
                )
                created
            }
        }
    }

    override fun listTasks(projectId: String?, sessionId: String?): List<TaskRecord> =
        synchronized(lock) { dao.listFiltered(projectId, sessionId).map { toRecord(it) } }

    override fun getTask(taskId: String): TaskRecord? =
        synchronized(lock) { dao.getById(taskId)?.let { toRecord(it) } }

    override fun updateTask(
        taskId: String,
        changes: Map<String, Any?>,
        expectedVersion: Long,
        expectedGoalVersion: Long,
        expectedAuthEpoch: Long,
    ): TaskRecord? {
        // Only mutable fields + local extensions may change; scheduler-owned
        // fields in `changes` are dropped, never applied. Control-owned
        // fields (goal_version / auth_epoch) are dropped here too: only
        // ControlPlane bumps them, via [updateControlCounters].
        val allowed = TaskContract.MUTABLE_FIELDS + TaskContract.LOCAL_FIELDS
        val safe = changes.filterKeys { it in allowed && it !in TaskContract.CONTROLLED_FIELDS }
        // P0：状态写与事件写在同一个 Room 事务里——事件插入失败时
        // 整个事务回滚、updateTask 抛错，绝不“状态已落、事件悄悄没了”
        //（老项目 §5.4 的同款静默丢）。只在状态真的变了时记事件；
        // 续租、改标题这类非状态写不记。
        // 锁顺序同 createTask：先 synchronized(lock) 再开事务（见上）。
        return synchronized(lock) {
            db.runInTransaction<TaskRecord?> {
                val current = dao.getById(taskId) ?: return@runInTransaction null
                if (current.version != expectedVersion) {
                    throw StaleTaskUpdate(taskId, expectedVersion, current.version)
                }
            // M2: an old-version write is rejected and nothing is written.
            if (current.goalVersion != expectedGoalVersion) {
                throw ControlVersionStale(taskId, ControlVersionStale.GOAL_VERSION, expectedGoalVersion, current.goalVersion)
            }
            if (current.authEpoch != expectedAuthEpoch) {
                throw ControlVersionStale(taskId, ControlVersionStale.AUTH_EPOCH, expectedAuthEpoch, current.authEpoch)
            }
            var next = toRecord(current)
            val oldState = next.state
            val now = nowSeconds()
            for ((key, value) in safe) {
                next = when (key) {
                    "title" -> next.copy(title = value as? String ?: next.title)
                    "description" -> next.copy(description = value as? String)
                    "state" -> {
                        val target = value as? String
                            ?: throw IllegalArgumentException("state 须为字符串")
                        TaskStates.checkTransition(next.state, target)
                        next.copy(state = target)
                    }
                    "assigned_agent_id" -> next.copy(assignedAgentId = value as? String)
                    "parent_task_id" -> next.copy(parentTaskId = value as? String)
                    "dependencies" -> {
                        @Suppress("UNCHECKED_CAST")
                        val deps = (value as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                        next.copy(dependencies = deps)
                    }
                    "priority" -> next.copy(priority = (value as? Number)?.toInt() ?: next.priority)
                    "result" -> next.copy(result = value as? String)
                    "error" -> next.copy(error = value as? String)
                    "brain_id" -> next.copy(brainId = value as? String)
                    "needs_attention" -> next.copy(needsAttention = value as? Boolean ?: next.needsAttention)
                    "lease_owner" -> next.copy(leaseOwner = value as? String)
                    "lease_expires_at" -> next.copy(leaseExpiresAt = (value as? Number)?.toLong())
                    "member_id" -> next.copy(memberId = value as? String)
                    "attempt" -> next.copy(attempt = (value as? Number)?.toInt() ?: next.attempt)
                    "attempt_id" -> next.copy(attemptId = value as? String)
                    else -> next
                }
            }
            // Scheduler-owned timestamps follow the state machine.
            if (next.state == "working" && next.startedAt == null) {
                next = next.copy(startedAt = now)
            }
            if (TaskStates.isTerminal(next.state) && next.finishedAt == null) {
                next = next.copy(finishedAt = now)
            }
            if (next.dependencies != current.let { toRecord(it).dependencies }) {
                validateDependencies(next)
            }
            next = next.copy(updatedAt = now)
            val entity = toEntity(next)
            val updated = dao.updateAll(
                taskId = entity.taskId,
                title = entity.title,
                description = entity.description,
                state = entity.state,
                assignedAgentId = entity.assignedAgentId,
                parentTaskId = entity.parentTaskId,
                dependenciesJson = entity.dependenciesJson,
                priority = entity.priority,
                startedAt = entity.startedAt,
                finishedAt = entity.finishedAt,
                updatedAt = entity.updatedAt,
                result = entity.result,
                error = entity.error,
                brainId = entity.brainId,
                needsAttention = entity.needsAttention,
                leaseOwner = entity.leaseOwner,
                leaseExpiresAt = entity.leaseExpiresAt,
                memberId = entity.memberId,
                attempt = entity.attempt,
                attemptId = entity.attemptId,
                expectedVersion = expectedVersion,
                goalVersion = entity.goalVersion,
                authEpoch = entity.authEpoch,
                handDeviceId = entity.handDeviceId,
                handAuthorizationId = entity.handAuthorizationId,
                handFolder = entity.handFolder,
            )
            if (updated == 0) throw StaleTaskUpdate(taskId, expectedVersion, current.version)
            val written = toRecord(entity.copy(version = current.version + 1))
            if (written.state != oldState) {
                eventDao.insert(
                    TaskEvent(
                        eventId = UUID.randomUUID().toString(),
                        taskId = taskId,
                        attemptId = written.attemptId,
                        kind = TaskEventKind.forState(written.state),
                        fromState = oldState,
                        toState = written.state,
                        actor = null,
                        reason = null,
                        createdAtEpochSeconds = now,
                    ).toEntity(),
                )
            }
            written
        }
    }
    }

    /**
     * 记一条非状态转移的审计事件（V5 跨设备开发，2026-11-12，A5）：
     * 派活文件夹回落不改任务态，单独落一条事件行。锁顺序同
     * createTask/updateTask：先 synchronized(lock) 再开事务。
     * 任务不存在时什么都不做，不抛错（泵的回落记录绝不能杀泵循环）。
     */
    override fun recordTaskEvent(
        taskId: String,
        kind: String,
        reason: String?,
        actor: String?,
    ) {
        synchronized(lock) {
            db.runInTransaction {
                val current = dao.getById(taskId) ?: return@runInTransaction
                val written = toRecord(current)
                eventDao.insert(
                    TaskEvent(
                        eventId = UUID.randomUUID().toString(),
                        taskId = taskId,
                        attemptId = written.attemptId,
                        kind = kind,
                        fromState = null,
                        toState = written.state,
                        actor = actor,
                        reason = reason,
                        createdAtEpochSeconds = nowSeconds(),
                    ).toEntity(),
                )
            }
        }
    }

    override fun updateControlCounters(taskId: String, goalVersion: Long, authEpoch: Long): TaskRecord? {
        synchronized(lock) {
            if (dao.updateCounters(taskId, goalVersion, authEpoch) == 0) return null
            return dao.getById(taskId)?.let { toRecord(it) }
        }
    }

    /**
     * Delete a task and every task-scoped row that belongs to it, in
     * one transaction: the task row, its steps, its control rows
     * (state / counters / operation ids / audit log), its member
     * returns and member task controls — plus the P0 `task_events`
     * and P2 `pipeline_verdicts` rows when those tables exist.
     *
     * Deliberately NOT deleted: conversation messages and sessions
     * (chat history stays, even when it references this taskId),
     * skills (a skill's evidence list may name this task as past
     * evidence), and revocation rows (security records, not task
     * content). Deleting regardless of state: callers stop a running
     * task first, but a stop that races a finish must not leave a
     * ghost row — and a later write-back for a deleted task is
     * refused by [updateTask]'s null check, never resurrected.
     */
    override fun deleteTask(taskId: String): Boolean {
        if (taskId.isBlank()) return false
        var removed = false
        synchronized(lock) {
            db.runInTransaction {
                db.taskStepDao().deleteByTask(taskId)
                db.controlDao().deleteCounter(taskId)
                db.controlDao().deleteState(taskId)
                db.controlDao().deleteOperationsByTask(taskId)
                db.controlAuditDao().deleteByTask(taskId)
                db.memberReturnDao().deleteByTask(taskId)
                db.memberTaskControlDao().deleteByTask(taskId)
                deleteFromTaskTable("task_events", taskId)
                deleteFromTaskTable("pipeline_verdicts", taskId)
                removed = dao.deleteById(taskId) > 0
            }
        }
        return removed
    }

    /**
     * Delete the [taskId] rows of [table], only when the table exists
     * and has a task-id column (`taskId` or `task_id`). Raw SQL on
     * purpose: the tables are added by sibling branches (P0
     * `task_events`, P2 `pipeline_verdicts`) and must not be a
     * compile-time dependency of this branch — the table + column
     * guards make the same code correct on this branch (tables
     * absent, no-op) and on the merged tree (tables present, cleaned).
     * Must be called inside [RoomDatabase.runInTransaction].
     */
    private fun deleteFromTaskTable(table: String, taskId: String) {
        val writable = db.openHelper.writableDatabase
        val tableExists = writable.query(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf<Any?>(table),
        ).use { it.moveToFirst() }
        if (!tableExists) return
        val columns = writable.query("PRAGMA table_info($table)").use { cursor ->
            val names = mutableListOf<String>()
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (nameIndex >= 0) cursor.getString(nameIndex)?.let { names += it }
            }
            names
        }
        val taskColumn = listOf("taskId", "task_id").firstOrNull { it in columns } ?: return
        writable.execSQL("DELETE FROM $table WHERE $taskColumn = ?", arrayOf<Any?>(taskId))
    }

    override fun expireLeases(nowEpochSeconds: Long): List<String> {
        // Lease expiry is the scheduler-owned recovery transition:
        // assigned/working -> requeued (attempt kept, claim released),
        // or -> failed when the retry budget is spent (attempt >=
        // MAX_ATTEMPTS: the dead letter). Both edges are legal in
        // TaskStates.TRANSITIONS, so the recovery no longer bypasses
        // the transition table — checkTransition runs before the write
        // (P0: the old code wrote "queued" directly, bypassing it).
        // The recovery is flagged with needs_attention so the loss is
        // visible, never silent, and each expiry writes two event rows
        // (lease_expired + the target-state event) in the same
        // transaction as the state change. Version check still applies.
        //
        // M2: the DAO query already anti-joins control_state, so PAUSED /
        // STOPPING tasks never reach this loop, and it already filters
        // sessionId IS NULL, so chat-turn mirrors (which take a claim
        // lease but never renew it) are never re-queued. Their expired
        // leases are only flagged needs_attention below — never returned
        // to queued.
        val expired = synchronized(lock) { dao.expiredLeases(nowEpochSeconds) }
        val ids = mutableListOf<String>()
        for (entity in expired) {
            // NOTE: no `continue` inside synchronized() — break/continue in
            // inline lambdas is an experimental feature in Kotlin 1.9, so
            // the get and the update each take the lock separately. The
            // version check in updateAll still guards the race: a concurrent
            // update makes `updated == 0` and the id is simply not added.
            // P0: the get + update + event inserts for one task run in a
            // single transaction (state and audit trail commit together).
            // 锁顺序同 createTask：先 synchronized(lock) 再开事务（见上）。
            synchronized(lock) {
                db.runInTransaction {
                    val current = dao.getById(entity.taskId) ?: return@runInTransaction
                    if (current.leaseExpiresAt == null || current.leaseExpiresAt > nowEpochSeconds) return@runInTransaction
                    // 重试预算：attempt 计数保留在行上（下次认领 attempt+1，
                    // 老 attempt 的回传/事件一律按 attemptId 拒绝，见
                    // MemberReturnReceiver）；耗尽即入死信，不再回队。
                    val deadLetter = current.attempt >= MAX_ATTEMPTS
                    val targetState = if (deadLetter) "failed" else "requeued"
                    TaskStates.checkTransition(current.state, targetState)
                    val now = nowSeconds()
                    val reason = if (deadLetter) {
                        "租约过期 ${current.attempt} 次未续租，已入死信（不再自动重试）"
                    } else {
                        "租约过期未续租，回队重派（第 ${current.attempt} 次认领）"
                    }
                    val updated = dao.updateAll(
                        taskId = current.taskId,
                        title = current.title,
                        description = current.description,
                        state = targetState,
                        assignedAgentId = null,
                        parentTaskId = current.parentTaskId,
                        dependenciesJson = current.dependenciesJson,
                        priority = current.priority,
                        startedAt = current.startedAt,
                        finishedAt = if (deadLetter) now else current.finishedAt,
                        updatedAt = now,
                        result = current.result,
                        error = if (deadLetter) reason else current.error,
                        brainId = current.brainId,
                        needsAttention = true,
                        leaseOwner = null,
                        leaseExpiresAt = null,
                        // 租约过期回队/死信：认领解除（memberId/attemptId
                        // 清空），attempt 计数保留。
                        memberId = null,
                        attempt = current.attempt,
                        attemptId = null,
                        expectedVersion = current.version,
                        goalVersion = current.goalVersion,
                        authEpoch = current.authEpoch,
                        handDeviceId = current.handDeviceId,
                        handAuthorizationId = current.handAuthorizationId,
                        handFolder = current.handFolder,
                    )
                    if (updated == 1) {
                        eventDao.insert(
                            TaskEvent(
                                eventId = UUID.randomUUID().toString(),
                                taskId = current.taskId,
                                attemptId = current.attemptId,
                                kind = TaskEventKind.LEASE_EXPIRED,
                                fromState = current.state,
                                toState = targetState,
                                actor = LEASE_SWEEPER_ACTOR,
                                reason = reason,
                                createdAtEpochSeconds = now,
                            ).toEntity(),
                        )
                        eventDao.insert(
                            TaskEvent(
                                eventId = UUID.randomUUID().toString(),
                                taskId = current.taskId,
                                attemptId = null,
                                kind = TaskEventKind.forState(targetState),
                                fromState = current.state,
                                toState = targetState,
                                actor = LEASE_SWEEPER_ACTOR,
                                reason = reason,
                                createdAtEpochSeconds = now,
                            ).toEntity(),
                        )
                        ids += current.taskId
                    }
                }
            }
        }
        // M2: PAUSED/STOPPING tasks with an expired lease are only flagged
        // needs_attention (the lease and state are left untouched, the id
        // is NOT returned): the user owns the paused task, the scheduler
        // must not silently re-queue it. P0: the expiry is still written
        // to the audit log — but only once, when needs_attention flips;
        // the lease stays expired forever, so one event per sweep would
        // spam the log without bound.
        val pausedExpired = synchronized(lock) { dao.expiredLeasesPaused(nowEpochSeconds) }
        for (entity in pausedExpired) {
            // 锁顺序同 createTask：先 synchronized(lock) 再开事务（见上）。
            synchronized(lock) {
                db.runInTransaction {
                    val current = dao.getById(entity.taskId) ?: return@runInTransaction
                    if (current.leaseExpiresAt == null || current.leaseExpiresAt > nowEpochSeconds) return@runInTransaction
                    if (current.needsAttention) return@runInTransaction
                    val now = nowSeconds()
                    dao.updateAll(
                        taskId = current.taskId,
                        title = current.title,
                        description = current.description,
                        state = current.state,
                        assignedAgentId = current.assignedAgentId,
                        parentTaskId = current.parentTaskId,
                        dependenciesJson = current.dependenciesJson,
                        priority = current.priority,
                        startedAt = current.startedAt,
                        finishedAt = current.finishedAt,
                        updatedAt = now,
                        result = current.result,
                        error = current.error,
                        brainId = current.brainId,
                        needsAttention = true,
                        leaseOwner = current.leaseOwner,
                        leaseExpiresAt = current.leaseExpiresAt,
                        memberId = current.memberId,
                        attempt = current.attempt,
                        attemptId = current.attemptId,
                        expectedVersion = current.version,
                        goalVersion = current.goalVersion,
                        authEpoch = current.authEpoch,
                        handDeviceId = current.handDeviceId,
                        handAuthorizationId = current.handAuthorizationId,
                        handFolder = current.handFolder,
                    )
                    eventDao.insert(
                        TaskEvent(
                            eventId = UUID.randomUUID().toString(),
                            taskId = current.taskId,
                            attemptId = current.attemptId,
                            kind = TaskEventKind.LEASE_EXPIRED,
                            fromState = current.state,
                            toState = current.state,
                            actor = LEASE_SWEEPER_ACTOR,
                            reason = "租约过期（已暂停，仅标记，不回队）",
                            createdAtEpochSeconds = now,
                        ).toEntity(),
                    )
                }
            }
        }
        return ids
    }

    /**
     * Dependency check: every dependency must exist, and the graph must be
     * acyclic. The record being written contributes its PROPOSED edges;
     * every other task contributes its stored edges, so a change that
     * would close a cycle through the record itself is caught (the old
     * walk only followed stored edges and missed exactly that case:
     * stored b -> a plus proposed a -> b looked acyclic).
     */
    private fun validateDependencies(record: TaskRecord) {
        if (record.taskId in record.dependencies) {
            throw TaskDependencyError("任务不能依赖自己：${record.taskId}")
        }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(id: String) {
            if (id in visiting) throw TaskDependencyError("依赖图有环：$id")
            if (id in visited) return
            visiting += id
            val deps = if (id == record.taskId) {
                record.dependencies
            } else {
                dao.getById(id)?.let { toRecord(it).dependencies }
                    ?: throw TaskDependencyError("依赖的任务不存在：$id")
            }
            for (depId in deps) {
                if (depId == id) throw TaskDependencyError("任务不能依赖自己：$depId")
                visit(depId)
            }
            visiting -= id
            visited += id
        }
        visit(record.taskId)
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    private fun toEntity(record: TaskRecord): TaskEntity {
        val deps = JSONArray()
        record.dependencies.forEach { deps.put(it) }
        return TaskEntity(
            taskId = record.taskId,
            projectId = record.projectId,
            sessionId = record.sessionId,
            title = record.title,
            description = record.description,
            state = record.state,
            assignedAgentId = record.assignedAgentId,
            parentTaskId = record.parentTaskId,
            dependenciesJson = deps.toString(),
            priority = record.priority,
            createdAt = record.createdAt,
            startedAt = record.startedAt,
            finishedAt = record.finishedAt,
            updatedAt = record.updatedAt,
            result = record.result,
            error = record.error,
            version = record.version,
            brainId = record.brainId,
            needsAttention = record.needsAttention,
            leaseOwner = record.leaseOwner,
            leaseExpiresAt = record.leaseExpiresAt,
            memberId = record.memberId,
            attempt = record.attempt,
            attemptId = record.attemptId,
            goalVersion = record.goalVersion,
            authEpoch = record.authEpoch,
            handDeviceId = record.handDeviceId,
            handAuthorizationId = record.handAuthorizationId,
            handFolder = record.handFolder,
        )
    }

    private fun toRecord(entity: TaskEntity): TaskRecord {
        val deps = mutableListOf<String>()
        try {
            val array = JSONArray(entity.dependenciesJson)
            for (i in 0 until array.length()) deps += array.optString(i, "")
        } catch (_: Exception) {
            // A corrupt dependencies cell never breaks reads; treat as empty
            // and let the next validated write repair it.
        }
        return TaskRecord(
            taskId = entity.taskId,
            projectId = entity.projectId,
            sessionId = entity.sessionId,
            title = entity.title,
            description = entity.description,
            state = entity.state,
            assignedAgentId = entity.assignedAgentId,
            parentTaskId = entity.parentTaskId,
            dependencies = deps.filter { it.isNotEmpty() },
            priority = entity.priority,
            createdAt = entity.createdAt,
            startedAt = entity.startedAt,
            finishedAt = entity.finishedAt,
            updatedAt = entity.updatedAt,
            result = entity.result,
            error = entity.error,
            version = entity.version,
            brainId = entity.brainId,
            needsAttention = entity.needsAttention,
            leaseOwner = entity.leaseOwner,
            leaseExpiresAt = entity.leaseExpiresAt,
            memberId = entity.memberId,
            attempt = entity.attempt,
            attemptId = entity.attemptId,
            goalVersion = entity.goalVersion,
            authEpoch = entity.authEpoch,
            handDeviceId = entity.handDeviceId,
            handAuthorizationId = entity.handAuthorizationId,
            handFolder = entity.handFolder,
        )
    }
}
