package wherevercode.agent.core.device

import java.io.File
import wherevercode.agent.core.workspace.WorkspacePort

/**
 * AndroidAppAdapter: the app-sandbox device adapter (stage 2, new).
 *
 * Differences from the old project's Mac/Linux/Termux subclasses
 * (audit section 2, #2):
 * - Android has no process-group signal semantics: stop is
 *   Process.destroy() -> destroyForcibly() (see BaseDeviceAdapter),
 *   and pause/resume are unsupported (they throw).
 * - The Termux home (/data/data/com.termux/files/home) must NEVER be
 *   used as an app root: the app sandbox and Termux run under different
 *   UIDs and cannot see each other's files. A Termux root passed here is
 *   refused outright. Install agents inside the app sandbox instead.
 * - Default roots come from the app's own directories only (filesDir,
 *   filesDir/bin, nativeLibraryDir), passed in by the caller; nothing
 *   outside the sandbox is assumed.
 */
class AndroidAppAdapter(
    allowedRoots: List<File>,
    workspaceAdapter: WorkspacePort? = null,
    /** Extra PATH entries for processes spawned in the sandbox. */
    val extraPathDirs: List<File> = emptyList(),
) : BaseDeviceAdapter(validateRoots(allowedRoots), workspaceAdapter) {
    override val platform: String = "android-app"

    companion object {
        const val TERMUX_HOME_PREFIX: String = "/data/data/com.termux"

        fun validateRoots(roots: List<File>): List<File> {
            for (root in roots) {
                val path = try {
                    root.canonicalPath
                } catch (_: Exception) {
                    root.absolutePath
                }
                require(!path.startsWith("$TERMUX_HOME_PREFIX/") && path != TERMUX_HOME_PREFIX) {
                    "Termux 根不得当作 App 根：App 沙箱与 Termux 互不可见（$path）"
                }
            }
            return roots
        }
    }

    override fun spawn(command: List<String>, env: Map<String, String>, cwd: File): Process {
        val mergedEnv = env.toMutableMap()
        if (extraPathDirs.isNotEmpty()) {
            val oldPath = mergedEnv["PATH"] ?: ""
            val additions = extraPathDirs.map { it.absolutePath }
                .filter { it !in oldPath.split(":") }
            mergedEnv["PATH"] = (additions + listOf(oldPath)).filter { it.isNotEmpty() }.joinToString(":")
        }
        return super.spawn(command, mergedEnv, cwd)
    }
}
