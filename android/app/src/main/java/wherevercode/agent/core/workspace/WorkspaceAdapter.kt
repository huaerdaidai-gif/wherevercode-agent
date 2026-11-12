package wherevercode.agent.core.workspace

import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import org.json.JSONObject

/**
 * WorkspaceAdapter: write-round protection for one project folder.
 *
 * Ported from the old project's `execution_host_workspace_adapter.py`
 * (blob 2dfe9f1e, main fd6b43d2) + `execution_host_workspace_backup.py`
 * (MAX_TEXT_BYTES, resolve_workspace_path).
 *
 * Old -> new name mapping:
 * - WorkspaceAdapter -> WorkspaceAdapter
 * - begin_round(task_id) -> beginRound(taskId)
 * - activate_for_write() -> activateForWrite()
 * - guard_write(file_path) -> guardWrite(file)
 * - guard_command(command) -> guardCommand(command)
 * - finish_round(round_id) -> finishRound(roundId)
 * - undo(round_id, confirm=True) -> undo(roundId, confirm=true)
 * - keep(round_id, confirm=True) -> keep(roundId, confirm=true)
 * - RoundRef / ChangeSet / UndoResult -> same names
 * - protection "auto"/"hook"/"cow"/"copy"/"none" -> ProtectionMode
 *   HOOK/COW/COPY/NONE ("auto" is gone: on Android there is no reflink,
 *   so COW always falls back to COPY with COW_FALLBACK_NOTICE, and the
 *   caller sees the notice instead of silently getting copy)
 *
 * Round model (unchanged): begin_round (lock; snapshot is deferred to
 * guard_write for single files, and to guard_command as a manifest) ->
 * activate_for_write -> guard_write (single-file pre-backup) /
 * guard_command (whole-tree snapshot before running a command) ->
 * finish_round (ChangeSet) / undo (confirm=true required) / keep.
 * protection="none" refuses writes outright.
 *
 * Android differences (no flock / reflink guarantee):
 * - Lock: a lock file + FileChannel lock held for the round, plus a
 *   JVM-level lock in this adapter.
 * - Snapshot: "changed-file manifest + copy on demand" — a manifest of
 *   (path, size, sha256) is taken at begin_round / guard_command, and
 *   files are copied to the backup dir only when guard_write backs them
 *   up. A file a command changed WITHOUT a prior guard_write backup
 *   cannot be restored by undo; it is listed in UndoResult.notRestored
 *   with that reason, never silently claimed as restored.
 */
const val COW_FALLBACK_NOTICE: String =
    "COW_FALLBACK_NOTICE：本设备不支持 reflink，已回退到复制保护（copy）"

const val MAX_TEXT_BYTES: Int = 2 * 1024 * 1024 // ported: execution_host_workspace_backup.MAX_TEXT_BYTES

/**
 * 2026-11-09 写轮次泄漏修复：轮次目录标记文件。只有带标记（或 abandon
 * 标记）的目录才会在启动恢复时被清理；没有标记的目录视为用户数据，保留。
 */
const val ROUND_MARKER: String = "round.json"
const val ABANDON_MARKER: String = "round.abandoned.json"
const val LOCK_FILE: String = ".lock"

/** 本进程标识（不依赖 android.os，纯 JVM 测试可跑）：仅用于诊断标记。 */
val PROCESS_ID: String = java.util.UUID.randomUUID().toString()

enum class ProtectionMode {
    HOOK,
    COW,
    COPY,
    NONE,
}

enum class ChangeKind {
    A, // added
    M, // modified
    D, // deleted
}

data class FileManifest(val path: String, val sizeBytes: Long, val sha256: String)

data class RoundRef(
    val roundId: String,
    val taskId: String,
    val mode: ProtectionMode,
    val protection: ProtectionMode,
    val notice: String?,
    val baseline: Map<String, FileManifest>,
    val holder: String? = null,
    /**
     * 2026-11-09 写轮次泄漏修复：谁创建了这一轮（"brain-tool" / "hand" / null）。
     * 只有属主相同的执行者才允许回收遗留轮次——不同的执行者（例如另一个
     * hand 通道）永远不会互相踩锁。null = 旧调用点（未知属主），不得回收。
     */
    val owner: String? = null,
    /** 轮次开始时间（epoch 秒），供诊断与遗留轮次判定用。 */
    val startedAtEpochSeconds: Long = System.currentTimeMillis() / 1000,
)

data class ChangedFile(val path: String, val change: ChangeKind)

data class ChangeSet(val roundId: String, val files: List<ChangedFile>)

data class UndoResult(
    val roundId: String,
    val restored: List<String>,
    val deleted: List<String>,
    val notRestored: List<String>,
    val alreadyUndone: Boolean,
)

data class KeepResult(val roundId: String, val kept: Boolean)

data class GuardWriteResult(
    val protected: Boolean,
    val protection: ProtectionMode,
    val path: String,
    val backedUp: Boolean,
)

data class GuardCommandResult(
    val protected: Boolean,
    val protection: ProtectionMode,
    val command: String,
)

/**
 * 2026-11-09（App 重启后的遗留状态处理）：启动恢复报告。
 *
 * - removedRoundIds：上一进程遗留、带本模块标记的轮次目录，已清理；
 * - keptRoundIds：没有标记的目录（不认识的用户数据，绝不删除）；
 * - lockAvailable：能否拿到工作区锁（false = 另一个活着的写入者仍在，
 *   本次不做任何删除）；
 * - error：锁检查失败的原因（可读中文）。
 */
data class RoundRecoveryReport(
    val removedRoundIds: List<String> = emptyList(),
    val keptRoundIds: List<String> = emptyList(),
    val lockAvailable: Boolean = false,
    val error: String? = null,
)

class WorkspaceException(val code: String, message: String) : RuntimeException(message)

/** Minimal port the device adapter depends on (see DeviceAdapter.kt). */
interface WorkspacePort {
    fun beginRound(
        taskId: String,
        protection: ProtectionMode? = null,
        owner: String? = null,
    ): RoundRef
    fun activateForWrite(): RoundRef
    fun guardWrite(file: File): GuardWriteResult
    fun guardCommand(command: String): GuardCommandResult
    fun finishRound(roundId: String): ChangeSet
    fun undo(roundId: String? = null, confirm: Boolean = true): UndoResult
    fun keep(roundId: String? = null, confirm: Boolean = true): KeepResult
}

interface WorkspaceAdapter : WorkspacePort {
    fun activeRound(): RoundRef?
    fun status(): Map<String, Any?>
    fun roundDir(roundId: String? = null): File
    fun abandon()

    /**
     * 2026-11-09：只回收指定轮次。调用方必须先证明该轮次已无活着的执行者
     * （ToolRegistry 在自己的写锁内、且 owner 相同才调用）。未知/已结束的
     * 轮次返回 false，绝不误伤别人的轮次。
     */
    fun abandonRound(roundId: String, reason: String): Boolean

    /** 进程启动时清理上一进程遗留的轮次目录（只删本模块写入过标记的目录）。 */
    fun recoverStaleRounds(): RoundRecoveryReport

    /** Resolve *rawPath* to (relative posix path, absolute file) inside the workspace. */
    fun resolveWorkspacePath(rawPath: String): Pair<String, File>
}

class FileWorkspaceAdapter(
    workspace: File,
    taskRoot: File,
    backupRoot: File = File(taskRoot, "rounds"),
    private val defaultProtection: ProtectionMode = ProtectionMode.HOOK,
    /** 2026-11-09：已结束轮次保留上限（供 undo）；超出即淘汰最旧的备份目录。 */
    private val maxFinishedRounds: Int = 32,
) : WorkspaceAdapter {
    val workspaceRoot: File = workspace.canonicalFile
    val taskRootDir: File = taskRoot.canonicalFile
    private val backupRootDir: File = backupRoot.canonicalFile

    init {
        require(workspaceRoot.isDirectory) { "workspace 不存在：$workspaceRoot" }
        val workspacePath = workspaceRoot.path
        val taskPath = taskRootDir.path
        require(!(workspacePath == taskPath || workspacePath.startsWith("$taskPath/"))) {
            "workspace 不能在 task_root 里面"
        }
        require(!(backupRootDir.path == workspacePath || backupRootDir.path.startsWith("$workspacePath/"))) {
            "备份目录不能在 workspace 里面"
        }
    }

    private val lock = Any()

    private class RoundState(
        val ref: RoundRef,
        var finished: Boolean = false,
        var undone: Boolean = false,
        var kept: Boolean = false,
        val backedUp: MutableSet<String> = mutableSetOf(),
        var commandBaseline: Map<String, FileManifest>? = null,
        var lockChannel: FileChannel? = null,
        var fileLock: FileLock? = null,
        var changeSet: ChangeSet? = null,
    )

    private var active: RoundState? = null
    private val finishedRounds = LinkedHashMap<String, RoundState>()
    private var abandonedCount: Int = 0

    override fun beginRound(taskId: String, protection: ProtectionMode?, owner: String?): RoundRef {
        synchronized(lock) {
            val current = active
            if (current != null && !current.finished) {
                if (current.ref.taskId == taskId) return current.ref
                throw WorkspaceException(
                    "workspace_round_active",
                    "已有进行中的写轮次：${current.ref.roundId}（task=${current.ref.taskId}，" +
                        "owner=${current.ref.owner ?: "unknown"}）；请先结束该轮次",
                )
            }
            val requested = protection ?: defaultProtection
            val (selected, notice) = selectProtection(requested)
            val roundId = java.util.UUID.randomUUID().toString()
            val baseline = takeManifest()
            val ref = RoundRef(
                roundId = roundId,
                taskId = taskId,
                mode = selected,
                protection = selected,
                notice = notice,
                baseline = baseline,
                owner = owner,
            )
            val state = RoundState(ref)
            acquireLock(state)
            active = state
            writeRoundMarker(ref)
            return ref
        }
    }

    override fun activateForWrite(): RoundRef {
        synchronized(lock) {
            val current = active ?: throw WorkspaceException(
                "workspace_no_active_round",
                "没有进行中的写轮次：请先开始一轮再写",
            )
            if (current.ref.protection == ProtectionMode.NONE) {
                throw WorkspaceException(
                    "workspace_protection_unavailable",
                    "这台设备暂时不能安全地原地修改这个文件夹。",
                )
            }
            return current.ref
        }
    }

    override fun guardWrite(file: File): GuardWriteResult {
        synchronized(lock) {
            val current = active ?: throw WorkspaceException(
                "workspace_no_active_round",
                "没有进行中的写轮次：请先开始一轮再写",
            )
            if (current.ref.protection == ProtectionMode.NONE) {
                throw WorkspaceException(
                    "workspace_protection_unavailable",
                    "这台设备暂时不能安全地原地修改这个文件夹。",
                )
            }
            val (rel, target) = resolveWorkspacePath(file.path)
            if (rel.isEmpty()) {
                throw WorkspaceException("workspace_invalid_path", "根目录本身不是文件：${file.path}")
            }
            val backupFile = File(roundBackupDir(current.ref.roundId), rel)
            var backedUp = false
            if (rel !in current.backedUp && target.isFile) {
                backupFile.parentFile?.mkdirs()
                target.copyTo(backupFile, overwrite = true)
                current.backedUp += rel
                backedUp = true
            } else if (rel in current.backedUp) {
                backedUp = false
            }
            return GuardWriteResult(
                protected = current.ref.protection in setOf(ProtectionMode.COPY, ProtectionMode.COW),
                protection = current.ref.protection,
                path = rel,
                backedUp = backedUp,
            )
        }
    }

    override fun guardCommand(command: String): GuardCommandResult {
        synchronized(lock) {
            val current = active ?: throw WorkspaceException(
                "workspace_no_active_round",
                "没有进行中的写轮次：请先开始一轮再跑命令",
            )
            if (current.ref.protection == ProtectionMode.NONE) {
                throw WorkspaceException(
                    "workspace_protection_unavailable",
                    "这台设备暂时不能安全地原地修改这个文件夹。",
                )
            }
            // Whole-tree snapshot before the command: manifest now, so
            // finish_round can tell exactly what the command changed.
            current.commandBaseline = takeManifest()
            return GuardCommandResult(
                protected = current.ref.protection in setOf(ProtectionMode.COPY, ProtectionMode.COW),
                protection = current.ref.protection,
                command = command,
            )
        }
    }

    override fun finishRound(roundId: String): ChangeSet {
        synchronized(lock) {
            val state = active?.takeIf { it.ref.roundId == roundId }
                ?: finishedRounds[roundId]
                ?: throw WorkspaceException("workspace_unknown_round", "未知轮次：$roundId")
            // 2026-11-09 写轮次泄漏修复：重复 finish 是幂等的，绝不重新
            // 计算 diff——否则第二次调用会把“轮次结束后别人改的文件”
            // 也算进这一轮。
            if (state.finished) {
                return state.changeSet ?: ChangeSet(roundId, emptyList())
            }
            val baseline = state.commandBaseline ?: state.ref.baseline
            val current = takeManifest()
            val files = diffManifests(baseline, current)
            val changeSet = ChangeSet(roundId, files)
            state.finished = true
            state.changeSet = changeSet
            releaseLock(state)
            if (active?.ref?.roundId == roundId) {
                active = null
                rememberFinished(roundId, state)
            }
            return changeSet
        }
    }

    override fun undo(roundId: String?, confirm: Boolean): UndoResult {
        require(confirm) { "undo 需要 confirm=true" }
        synchronized(lock) {
            val id = roundId ?: active?.ref?.roundId ?: finishedRounds.keys.lastOrNull()
                ?: throw WorkspaceException("workspace_unknown_round", "没有可撤销的轮次")
            val state = finishedRounds[id] ?: active?.takeIf { it.ref.roundId == id }
                ?: throw WorkspaceException("workspace_unknown_round", "未知轮次：$id")
            if (state.undone) {
                return UndoResult(id, emptyList(), emptyList(), emptyList(), alreadyUndone = true)
            }
            val baseline = state.commandBaseline ?: state.ref.baseline
            val current = takeManifest()
            val changes = diffManifests(baseline, current)
            val restored = mutableListOf<String>()
            val deleted = mutableListOf<String>()
            val notRestored = mutableListOf<String>()
            val backupDir = roundBackupDir(id)
            for (change in changes) {
                when (change.change) {
                    ChangeKind.A -> {
                        val file = File(workspaceRoot, change.path)
                        if (file.exists() && file.delete()) deleted += change.path
                    }
                    ChangeKind.M -> {
                        val backup = File(backupDir, change.path)
                        if (backup.isFile) {
                            val target = File(workspaceRoot, change.path)
                            target.parentFile?.mkdirs()
                            backup.copyTo(target, overwrite = true)
                            restored += change.path
                        } else {
                            // Changed by a command without a prior
                            // guard_write backup: cannot be restored.
                            notRestored += change.path
                        }
                    }
                    ChangeKind.D -> {
                        val backup = File(backupDir, change.path)
                        if (backup.isFile) {
                            val target = File(workspaceRoot, change.path)
                            target.parentFile?.mkdirs()
                            backup.copyTo(target, overwrite = true)
                            restored += change.path
                        } else {
                            notRestored += change.path
                        }
                    }
                }
            }
            state.undone = true
            releaseLock(state)
            if (active?.ref?.roundId == id) active = null
            deleteRecursively(backupDir)
            return UndoResult(id, restored, deleted, notRestored, alreadyUndone = false)
        }
    }

    override fun keep(roundId: String?, confirm: Boolean): KeepResult {
        require(confirm) { "keep 需要 confirm=true" }
        synchronized(lock) {
            val id = roundId ?: active?.ref?.roundId ?: finishedRounds.keys.lastOrNull()
                ?: throw WorkspaceException("workspace_unknown_round", "没有可保留的轮次")
            val state = finishedRounds[id] ?: active?.takeIf { it.ref.roundId == id }
                ?: throw WorkspaceException("workspace_unknown_round", "未知轮次：$id")
            state.kept = true
            releaseLock(state)
            if (active?.ref?.roundId == id) active = null
            deleteRecursively(roundBackupDir(id))
            return KeepResult(id, kept = true)
        }
    }

    override fun activeRound(): RoundRef? = synchronized(lock) { active?.ref }

    override fun status(): Map<String, Any?> = synchronized(lock) {
        mapOf(
            "workspace" to workspaceRoot.path,
            "active_round" to active?.ref?.roundId,
            "active_owner" to active?.ref?.owner,
            "active_task" to active?.ref?.taskId,
            "active_since" to active?.ref?.startedAtEpochSeconds,
            "protection" to (active?.ref?.protection?.name ?: defaultProtection.name),
            "finished_rounds" to finishedRounds.size,
            "abandoned_rounds" to abandonedCount,
        )
    }

    override fun roundDir(roundId: String?): File {
        val id = roundId ?: active?.ref?.roundId
            ?: throw WorkspaceException("workspace_unknown_round", "没有进行中的轮次")
        return roundBackupDir(id)
    }

    override fun abandon() {
        synchronized(lock) {
            active?.let {
                releaseLock(it)
                deleteRecursively(roundBackupDir(it.ref.roundId))
            }
            active = null
        }
    }

    /**
     * 2026-11-09：只回收调用方明确点名、且仍是当前活动轮次的那一轮。
     * 不存在的轮次返回 false；绝不按 taskId 或“最旧”猜测回收。
     */
    override fun abandonRound(roundId: String, reason: String): Boolean {
        synchronized(lock) {
            val state = active?.takeIf { it.ref.roundId == roundId } ?: return false
            releaseLock(state)
            active = null
            abandonedCount += 1
            try {
                File(roundBackupDir(roundId), ABANDON_MARKER).writeText(
                    JSONObject()
                        .put("round_id", roundId)
                        .put("reason", reason)
                        .put("ts", System.currentTimeMillis() / 1000)
                        .toString(),
                    Charsets.UTF_8,
                )
            } catch (_: Exception) {
                // Diagnostic only; never let it block the reclaim.
            }
            return true
        }
    }

    /**
     * App 重启后的遗留状态处理。
     *
     * 轮次状态只存在于内存（进程重启即空），磁盘上的轮次目录只可能是
     * 上一进程中断后留下的。这里只在能拿到工作区锁（没有活着的写入者）
     * 时删除带本模块 [ROUND_MARKER] 的目录；没有标记的目录一律保留。
     */
    override fun recoverStaleRounds(): RoundRecoveryReport {
        synchronized(lock) {
            if (active != null) {
                return RoundRecoveryReport(
                    keptRoundIds = listOf(active!!.ref.roundId),
                    lockAvailable = true,
                    error = "本进程仍有活动轮次，跳过遗留清理",
                )
            }
            backupRootDir.mkdirs()
            val lockFile = File(backupRootDir, LOCK_FILE)
            val channel = try {
                FileChannel.open(
                    lockFile.toPath(),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                )
            } catch (e: Exception) {
                return RoundRecoveryReport(
                    lockAvailable = false,
                    error = "无法打开工作区锁文件：${e.message ?: "未知错误"}",
                )
            }
            val acquired = try {
                channel.tryLock()
            } catch (_: Exception) {
                null
            }
            if (acquired == null) {
                try {
                    channel.close()
                } catch (_: Exception) {
                }
                return RoundRecoveryReport(
                    lockAvailable = false,
                    error = "工作区正被另一写入者占用，未清理任何遗留轮次",
                )
            }
            val removed = mutableListOf<String>()
            val kept = mutableListOf<String>()
            try {
                backupRootDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
                    if (File(dir, ROUND_MARKER).isFile || File(dir, ABANDON_MARKER).isFile) {
                        deleteRecursively(dir)
                        removed += dir.name
                    } else {
                        kept += dir.name
                    }
                }
            } finally {
                try {
                    acquired.release()
                } catch (_: Exception) {
                }
                try {
                    channel.close()
                } catch (_: Exception) {
                }
            }
            return RoundRecoveryReport(
                removedRoundIds = removed.sorted(),
                keptRoundIds = kept.sorted(),
                lockAvailable = true,
            )
        }
    }

    private fun rememberFinished(roundId: String, state: RoundState) {
        finishedRounds[roundId] = state
        while (finishedRounds.size > maxFinishedRounds) {
            val oldest = finishedRounds.keys.first()
            val evicted = finishedRounds.remove(oldest) ?: continue
            if (!evicted.undone && !evicted.kept) {
                deleteRecursively(roundBackupDir(oldest))
            }
        }
    }

    private fun writeRoundMarker(ref: RoundRef) {
        try {
            val dir = roundBackupDir(ref.roundId).apply { mkdirs() }
            File(dir, ROUND_MARKER).writeText(
                JSONObject()
                    .put("round_id", ref.roundId)
                    .put("task_id", ref.taskId)
                    .put("owner", ref.owner ?: JSONObject.NULL)
                    .put("protection", ref.protection.name)
                    .put("pid", PROCESS_ID)
                    .put("started_at", ref.startedAtEpochSeconds)
                    .put("marker_version", 1)
                    .toString(),
                Charsets.UTF_8,
            )
        } catch (_: Exception) {
            // Best-effort: a missing marker only means "do not auto-delete".
        }
    }

    override fun resolveWorkspacePath(rawPath: String): Pair<String, File> {
        if (rawPath.isEmpty() || '\u0000' in rawPath) {
            throw WorkspaceException("workspace_invalid_path", "路径不合法：$rawPath")
        }
        val candidate = File(rawPath)
        val parts = rawPath.replace('\\', '/').split("/").filter { it.isNotEmpty() && it != "." }
        if (".." in parts) {
            throw WorkspaceException("workspace_outside_workspace", "路径超出工作区：$rawPath")
        }
        val target: File = if (candidate.isAbsolute) {
            val rootPath = workspaceRoot.path
            val canonical = try {
                candidate.canonicalPath
            } catch (_: Exception) {
                candidate.absolutePath
            }
            if (canonical != rootPath && !canonical.startsWith("$rootPath/")) {
                throw WorkspaceException("workspace_outside_workspace", "路径超出工作区：$rawPath")
            }
            File(canonical)
        } else {
            var current = workspaceRoot
            for (part in parts) current = File(current, part)
            // Resolve the existing parent chain so a symlink cannot
            // redirect the path outside the workspace.
            val parent = current.parentFile
            if (parent != null && parent.exists()) {
                val resolvedParent = try {
                    parent.canonicalPath
                } catch (_: Exception) {
                    parent.absolutePath
                }
                val rootPath = workspaceRoot.path
                if (resolvedParent != rootPath && !resolvedParent.startsWith("$rootPath/")) {
                    throw WorkspaceException("workspace_outside_workspace", "路径超出工作区：$rawPath")
                }
                File(resolvedParent, current.name)
            } else {
                current
            }
        }
        // 2026-11-09 V5 C1 W1 guardWrite 根因修复：rel 必须按目标文件相对
        // 工作区根目录算，不能按原始输入分段拼。绝对路径输入（如
        // /data/.../workspace/sub/a.txt）按输入分段拼会得到
        // "data/.../workspace/sub/a.txt"，备份落在
        // rounds/<id>/data/... 嵌套目录，而 undo() 按 manifest 的工作区
        // 相对路径（"sub/a.txt"）找备份，找不到就报 notRestored。
        // 相对路径输入的目标文件本来就是根下拼接，相对化后结果不变；
        // 工作区根目录本身相对化为空串，与此前行为一致（guardWrite
        // 仍以 workspace_invalid_path 拒绝把它当文件写）。
        val rel = if (target == workspaceRoot) {
            ""
        } else {
            target.relativeTo(workspaceRoot).path.replace('\\', '/')
        }
        return rel to target
    }

    // -- internals ----------------------------------------------------------

    private fun selectProtection(requested: ProtectionMode): Pair<ProtectionMode, String?> =
        when (requested) {
            ProtectionMode.COW -> ProtectionMode.COPY to COW_FALLBACK_NOTICE
            else -> requested to null
        }

    private fun roundBackupDir(roundId: String): File = File(backupRootDir, roundId)

    private fun acquireLock(state: RoundState) {
        backupRootDir.mkdirs()
        val lockFile = File(backupRootDir, LOCK_FILE)
        val channel = FileChannel.open(
            lockFile.toPath(),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE,
        )
        var acquired: FileLock? = null
        val deadline = System.currentTimeMillis() + 30_000
        while (acquired == null && System.currentTimeMillis() < deadline) {
            acquired = try {
                channel.tryLock()
            } catch (_: Exception) {
                null
            }
            if (acquired == null) Thread.sleep(100)
        }
        if (acquired == null) {
            try {
                channel.close()
            } catch (_: Exception) {
            }
            throw WorkspaceException("workspace_locked", "工作区正被另一轮次占用，请稍后重试")
        }
        state.lockChannel = channel
        state.fileLock = acquired
    }

    private fun releaseLock(state: RoundState) {
        try {
            state.fileLock?.release()
        } catch (_: Exception) {
        }
        try {
            state.lockChannel?.close()
        } catch (_: Exception) {
        }
        state.fileLock = null
        state.lockChannel = null
    }

    private fun takeManifest(): Map<String, FileManifest> {
        val out = LinkedHashMap<String, FileManifest>()
        if (!workspaceRoot.exists()) return out
        workspaceRoot.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val rel = file.relativeTo(workspaceRoot).path.replace('\\', '/')
                out[rel] = FileManifest(rel, file.length(), sha256(file))
            }
        return out
    }

    private fun diffManifests(
        before: Map<String, FileManifest>,
        after: Map<String, FileManifest>,
    ): List<ChangedFile> {
        val out = mutableListOf<ChangedFile>()
        for ((path, manifest) in after) {
            val old = before[path]
            if (old == null) out += ChangedFile(path, ChangeKind.A)
            else if (old.sha256 != manifest.sha256 || old.sizeBytes != manifest.sizeBytes) {
                out += ChangedFile(path, ChangeKind.M)
            }
        }
        for (path in before.keys) {
            if (path !in after) out += ChangedFile(path, ChangeKind.D)
        }
        return out.sortedBy { it.path }
    }

    private fun sha256(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            ""
        }
    }

    private fun deleteRecursively(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursively(it) }
        }
        file.delete()
    }
}
