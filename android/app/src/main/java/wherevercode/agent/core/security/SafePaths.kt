package wherevercode.agent.core.security

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * SafePaths（2026-11-13 安全加固）：把一个“可能指向授权目录外”的路径
 * 解析成真实路径，并判定它是否仍在授权根目录之内。
 *
 * 为什么不能只用 `File.canonicalPath`：
 * - 叶子是**悬空符号链接**（指向一个还不存在的目录外文件）时，
 *   canonicalize 解不开它，会回退成“父目录 + 原名”，看起来仍在根内；
 *   随后 `writeText` 跟随链接，把文件写到根目录之外。
 * - 中间某级目录是指向根外的符号链接、而它下面的目标还不存在时，
 *   只检查“已存在的父目录”会漏掉这一级。
 *
 * 本工具的规则（fail closed）：
 * 1. 拒绝 NUL、拒绝任何 `..` 段（不做词法折叠，避免 `a/../..` 之类绕过）。
 * 2. 从目标往上找到**第一个在磁盘上存在的祖先**（存在性用 NOFOLLOW，
 *    悬空链接也算“存在”）。若它是悬空链接 → 拒绝。
 * 3. 对该祖先取 `toRealPath()`（跟随所有链接），再拼回剩余的不存在部分，
 *    最后判断是否等于根或在根之下（根本身也取 realPath）。
 *
 * 仍然存在的边界（记录在交付说明里，不在这里假装解决）：检查与写入
 * 之间如果有进程把某级目录换成符号链接（TOCTOU），JVM 层没有 openat /
 * O_NOFOLLOW 可用；调用方在写前再解析一次并比对结果可以缩小窗口。
 */
object SafePaths {

    /** 根目录的真实路径；根不存在或解析失败时返回 null。 */
    fun realRoot(root: File): Path? = try {
        root.toPath().toRealPath()
    } catch (_: Exception) {
        null
    }

    /**
     * 把 [candidate]（绝对路径）解析成真实路径；悬空链接、`..`、NUL、
     * 解析失败都返回 null。返回的路径不保证存在（可能是待创建的新文件）。
     */
    fun realTarget(candidate: File): Path? {
        val raw = candidate.path
        if (raw.isEmpty() || '\u0000' in raw) return null
        if (raw.replace('\\', '/').split('/').any { it == ".." }) return null
        val absolute: Path = try {
            candidate.toPath().toAbsolutePath()
        } catch (_: Exception) {
            return null
        }
        var existing: Path? = absolute
        val missing = ArrayList<String>()
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            val name = existing.fileName ?: return null
            missing.add(0, name.toString())
            existing = existing.parent
        }
        if (existing == null) return null
        // 悬空链接：链接本身存在、目标不存在——跟随它写入会落到目标处。
        if (Files.isSymbolicLink(existing) && !Files.exists(existing)) return null
        val real = try {
            existing.toRealPath()
        } catch (_: Exception) {
            return null
        }
        var out = real
        for (part in missing) out = out.resolve(part)
        return out.normalize()
    }

    /** [target] 是否等于 [realRoot] 或位于其下（按路径段比较，不按字符串前缀）。 */
    fun isInside(target: Path, realRoot: Path): Boolean =
        target == realRoot || target.startsWith(realRoot)

    /**
     * 一步到位：[candidate] 解析后是否仍在 [root] 内。返回解析后的真实
     * 文件；在根外、悬空链接或无法解析时返回 null。
     */
    fun resolveInside(root: File, candidate: File): File? {
        val realRoot = realRoot(root) ?: return null
        val target = realTarget(candidate) ?: return null
        return if (isInside(target, realRoot)) target.toFile() else null
    }

    /**
     * 命令参数里“像路径且逃出根目录”的参数（用于只读命令的自动放行判定
     * 与远端 hand 的命令拒绝）。像路径 = 绝对路径、`~` 开头、含 `..` 段，
     * 或 `--opt=<上述之一>`。解析不了的一律算逃逸（fail closed）。
     */
    fun argsEscapingRoot(argv: List<String>, root: File, cwd: File = root): List<String> {
        val out = mutableListOf<String>()
        for (arg in argv.drop(1)) {
            val value = if (arg.startsWith("-") && '=' in arg) arg.substringAfter('=') else arg
            if (!looksLikePath(value)) continue
            if (value.startsWith("~")) {
                out += arg
                continue
            }
            if (value.replace('\\', '/').split('/').any { it == ".." }) {
                out += arg
                continue
            }
            val file = if (File(value).isAbsolute) File(value) else File(cwd, value)
            if (resolveInside(root, file) == null) out += arg
        }
        return out
    }

    /** 只看形状：绝对路径、`~` 开头、或含 `..` 段。 */
    fun looksLikePath(value: String): Boolean {
        if (value.isEmpty()) return false
        val normalized = value.replace('\\', '/')
        if (normalized.startsWith("/") || normalized.startsWith("~")) return true
        return normalized.split('/').any { it == ".." }
    }

    /** 无根可比时的保守判定：参数里有没有任何像路径、可能逃逸的值。 */
    fun hasPathLikeEscapeArgs(argv: List<String>): Boolean =
        argv.drop(1).any { arg ->
            val value = if (arg.startsWith("-") && '=' in arg) arg.substringAfter('=') else arg
            looksLikePath(value)
        }
}
