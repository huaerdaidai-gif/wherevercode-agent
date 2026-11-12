package wherevercode.agent.core.device

import java.io.File
import wherevercode.agent.core.workspace.ChangeSet
import wherevercode.agent.core.workspace.GuardCommandResult
import wherevercode.agent.core.workspace.GuardWriteResult
import wherevercode.agent.core.workspace.KeepResult
import wherevercode.agent.core.workspace.ProtectionMode
import wherevercode.agent.core.workspace.RoundRef
import wherevercode.agent.core.workspace.UndoResult
import wherevercode.agent.core.workspace.WorkspacePort

/**
 * DeviceAdapter interface, ported from the old project's
 * `device_adapters.py` (blob 84147c0e, main fd6b43d2).
 *
 * Old -> new name mapping:
 * - allowed_roots -> allowedRoots
 * - is_path_allowed(path) -> isPathAllowed(path)
 * - spawn(command, env, cwd) -> spawn(command, env, cwd)
 * - stop_tree(process) -> stopTree(process)
 * - pause_process(pid) -> pauseProcess(pid)
 * - resume_process(pid) -> resumeProcess(pid)
 * - begin_round / finish_round / undo / keep / guard_write /
 *   guard_command / activate_for_write -> same names in camelCase,
 *   pure delegation to the workspace adapter
 * - device_adapter_for(platform, ...) -> deviceAdapterFor(...)
 *
 * Dependency note (flagged deviation, see docs/module-interfaces.json):
 * the stage-1 audit says core modules must not import each other, but
 * this interface's workspace delegation — which the stage-2 task itself
 * orders — needs the workspace round types. The import is limited to
 * `core.workspace` value types + the [WorkspacePort] interface (one-way,
 * device -> workspace, no cycle). All other coordination still goes
 * through brain/.
 *
 * Path checks are path-shaped on purpose (old docstring): they do not
 * assume the path is a file on this disk, so a later storage adapter can
 * answer the same question for a NAS / GitHub / cloud-drive location.
 * System roots are never allowed, even if a caller lists one: the anchor
 * itself and the filesystem root are not project folders.
 */
interface DeviceAdapter {
    val platform: String
    val allowedRoots: List<File>

    /** Folders this device may read and write for tasks. */
    fun roots(): List<File> = allowedRoots.toList()

    /** True when a path sits under an accessible root (never for "/" or an anchor itself). */
    fun isPathAllowed(path: String): Boolean

    /** Start one task process. Returns the running process. */
    fun spawn(command: List<String>, env: Map<String, String>, cwd: File): Process

    /** Stop a process (and, where possible, its tree). */
    fun stopTree(process: Process, includeDescendants: Boolean = false)

    fun pauseProcess(pid: Long)
    fun resumeProcess(pid: Long)

    // -- Workspace backup / undo: pure delegation to the workspace adapter. --
    fun beginRound(
        taskId: String,
        protection: ProtectionMode? = null,
        owner: String? = null,
    ): RoundRef
    fun finishRound(roundId: String): ChangeSet
    fun undo(roundId: String? = null, confirm: Boolean = true): UndoResult
    fun keep(roundId: String? = null, confirm: Boolean = true): KeepResult
    fun guardWrite(file: File): GuardWriteResult
    fun guardCommand(command: String): GuardCommandResult
    fun activateForWrite(): RoundRef
}

abstract class BaseDeviceAdapter(
    override val allowedRoots: List<File>,
    protected val workspaceAdapter: WorkspacePort?,
) : DeviceAdapter {

    override fun isPathAllowed(path: String): Boolean {
        return try {
            val target = File(path)
            val targetPath = target.path
            if (targetPath.isEmpty() || targetPath == "/") return false
            // The anchor itself is not a project folder.
            val anchor = File(targetPath).let {
                val absolute = it.absolutePath
                val root = File(absolute).toPath().root?.toString() ?: "/"
                root
            }
            if (targetPath == anchor) return false
            // 2026-11-13 安全加固：canonicalPath 解不开时不再回退到未规范化的
            // absolutePath（`..`、悬空链接会因此漏网）；真实路径不在任一根下即拒绝。
            for (root in allowedRoots) {
                if (wherevercode.agent.core.security.SafePaths.resolveInside(root, target) == null) continue
                val rootPath = root.canonicalPath
                val resolved = try {
                    target.canonicalPath
                } catch (_: Exception) {
                    target.absolutePath
                }
                if (resolved == rootPath || resolved.startsWith("$rootPath/")) return true
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    override fun spawn(command: List<String>, env: Map<String, String>, cwd: File): Process {
        val builder = ProcessBuilder(command)
        builder.directory(cwd)
        builder.environment().putAll(env)
        return builder.start()
    }

    /**
     * Android has no process-group signal semantics (no SIGTERM-to-group,
     * no SIGSTOP/SIGCONT on a process group): stop with destroy(), then
     * destroyForcibly(). Ported from `posix_stop_tree`'s intent, not its
     * mechanism — the old SIGTERM -> 5s -> SIGKILL sequence does not exist
     * on Android.
     */
    override fun stopTree(process: Process, includeDescendants: Boolean) {
        if (!process.isAlive) return
        process.destroy()
        try {
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroyForcibly()
        }
    }

    override fun pauseProcess(pid: Long) {
        throw UnsupportedOperationException(
            "task_pause_unsupported: Android has no process-group SIGSTOP semantics",
        )
    }

    override fun resumeProcess(pid: Long) {
        throw UnsupportedOperationException(
            "task_pause_unsupported: Android has no process-group SIGCONT semantics",
        )
    }

    private fun workspace(): WorkspacePort =
        workspaceAdapter ?: throw IllegalStateException("workspace_adapter_unavailable")

    override fun beginRound(taskId: String, protection: ProtectionMode?, owner: String?): RoundRef =
        workspace().beginRound(taskId, protection, owner)

    override fun finishRound(roundId: String): ChangeSet = workspace().finishRound(roundId)

    override fun undo(roundId: String?, confirm: Boolean): UndoResult =
        workspace().undo(roundId, confirm)

    override fun keep(roundId: String?, confirm: Boolean): KeepResult =
        workspace().keep(roundId, confirm)

    override fun guardWrite(file: File): GuardWriteResult = workspace().guardWrite(file)

    override fun guardCommand(command: String): GuardCommandResult =
        workspace().guardCommand(command)

    override fun activateForWrite(): RoundRef = workspace().activateForWrite()
}

/**
 * Pure-JVM device adapter (used by unit tests and the pure-JVM HandServer).
 * On the phone, [AndroidAppAdapter] is used instead.
 */
class JvmDeviceAdapter(
    allowedRoots: List<File>,
    workspaceAdapter: WorkspacePort? = null,
) : BaseDeviceAdapter(allowedRoots, workspaceAdapter) {
    override val platform: String = "jvm"
}

fun deviceAdapterFor(
    platform: String,
    allowedRoots: List<File> = emptyList(),
    workspaceAdapter: WorkspacePort? = null,
): DeviceAdapter = when (platform.lowercase()) {
    "android", "android-app" -> AndroidAppAdapter(allowedRoots, workspaceAdapter)
    else -> JvmDeviceAdapter(allowedRoots, workspaceAdapter)
}
