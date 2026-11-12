package wherevercode.agent.ui

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

data class WorkspaceEntry(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
)

data class WorkspaceListing(
    val entries: List<WorkspaceEntry> = emptyList(),
    val truncated: Boolean = false,
    val error: String? = null,
)

enum class WorkspaceReadKind {
    TEXT,
    BINARY,
    MISSING,
    OUTSIDE,
    ERROR,
}

data class WorkspaceRead(
    val kind: WorkspaceReadKind,
    val text: String = "",
    val sizeBytes: Long = 0,
    val truncated: Boolean = false,
    val error: String? = null,
)

/**
 * Read-only project browser over the app workspace. It is deliberately not
 * an editor: the only write path in this app is the Agent's `write_file` /
 * `run_command` tool, which goes through the approval gate and the
 * WorkspaceAdapter write-round protection. The UI must not pretend to have
 * an editor that does not exist.
 *
 * Pure Kotlin (java.io only): unit-testable with a temp directory.
 */
class WorkspaceBrowser(
    root: File,
    private val maxEntries: Int = 400,
    private val maxReadBytes: Int = 512 * 1024,
) {
    private val rootDir: File = try {
        root.canonicalFile
    } catch (_: Exception) {
        root.absoluteFile
    }

    val rootPath: String get() = rootDir.path

    fun exists(): Boolean = rootDir.isDirectory

    fun list(relativePath: String = ""): WorkspaceListing = list(relativePath, maxEntries)

    fun list(relativePath: String = "", limit: Int): WorkspaceListing {
        val dir = resolve(relativePath) ?: return WorkspaceListing(
            error = if (relativePath.isBlank()) "工作区不存在" else "路径不可访问或越界：$relativePath",
        )
        if (!dir.exists()) return WorkspaceListing(error = "目录不存在：${relativePath.ifBlank { "/" }}")
        if (!dir.isDirectory) return WorkspaceListing(error = "不是目录：$relativePath")
        if (!dir.canRead()) return WorkspaceListing(error = "目录不可读：$relativePath")
        val all = try {
            dir.listFiles()?.toList().orEmpty()
        } catch (e: Exception) {
            return WorkspaceListing(error = "读取目录失败：${e.message ?: "未知错误"}")
        }
        val sorted = all.sortedWith(
            compareByDescending<File> { it.isDirectory }
                .thenBy { it.name.lowercase() },
        )
        val entries = sorted.take(limit).mapNotNull { file ->
            val rel = relativeOf(file) ?: return@mapNotNull null
            WorkspaceEntry(
                name = file.name,
                relativePath = rel,
                isDirectory = file.isDirectory,
                sizeBytes = if (file.isFile) file.length() else 0L,
            )
        }
        return WorkspaceListing(entries = entries, truncated = sorted.size > limit)
    }

    /**
     * 文件树搜索（阶段 3，U2/批 4）：按文件名子串匹配（不区分大小写），
     * 递归整棵工作区；只返回文件，不返回目录；结果按路径排序、上限
     * [maxResults]。深度与条目数有上限，超大工作区不会无限制遍历。
     */
    fun search(query: String, maxResults: Int = 200): List<WorkspaceEntry> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        val out = mutableListOf<WorkspaceEntry>()
        var visited = 0
        fun walk(dir: File) {
            if (out.size >= maxResults || visited > 20_000) return
            val children = try {
                dir.listFiles()?.toList().orEmpty()
            } catch (_: Exception) {
                return
            }
            for (child in children.sortedBy { it.name.lowercase() }) {
                if (out.size >= maxResults || visited > 20_000) return
                visited++
                if (child.isDirectory) {
                    walk(child)
                } else if (child.name.lowercase().contains(needle)) {
                    val rel = relativeOf(child) ?: continue
                    out += WorkspaceEntry(child.name, rel, isDirectory = false, sizeBytes = child.length())
                }
            }
        }
        walk(rootDir)
        return out.sortedBy { it.relativePath }
    }

    fun read(relativePath: String): WorkspaceRead = read(relativePath, maxReadBytes)

    /**
     * 按字节上限读文件。大文件“加载更多”：首次只读前 512 KB
     * （[WorkspaceBrowser] 默认 maxReadBytes），用户点加载更多后再以
     * 2 MB 上限重读；超过上限仍截断并如实标注，绝不假装读完了全文。
     */
    fun read(relativePath: String, maxBytes: Int): WorkspaceRead {
        val file = resolve(relativePath)
            ?: return WorkspaceRead(kind = WorkspaceReadKind.OUTSIDE, error = "路径越界：$relativePath")
        if (!file.exists() || !file.isFile) {
            return WorkspaceRead(kind = WorkspaceReadKind.MISSING, error = "文件不存在：$relativePath")
        }
        val length = file.length()
        return try {
            val bytes = file.inputStream().use { input ->
                val buffer = ByteArray(minOf(length, maxBytes.toLong()).toInt())
                var offset = 0
                while (offset < buffer.size) {
                    val read = input.read(buffer, offset, buffer.size - offset)
                    if (read <= 0) break
                    offset += read
                }
                buffer.copyOf(offset)
            }
            if (bytes.any { it == 0.toByte() }) {
                return WorkspaceRead(
                    kind = WorkspaceReadKind.BINARY,
                    sizeBytes = length,
                    error = "二进制文件，未在聊天/代码视图中展开。",
                )
            }
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val text = try {
                decoder.decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: Exception) {
                // A truncation can split a multi-byte character; decode the
                // readable prefix leniently rather than calling the file binary.
                if (length > maxBytes) {
                    String(bytes, StandardCharsets.UTF_8)
                } else {
                    return WorkspaceRead(
                        kind = WorkspaceReadKind.BINARY,
                        sizeBytes = length,
                        error = "文件不是有效的 UTF-8 文本。",
                    )
                }
            }
            WorkspaceRead(
                kind = WorkspaceReadKind.TEXT,
                text = text,
                sizeBytes = length,
                truncated = length > maxBytes,
            )
        } catch (e: Exception) {
            WorkspaceRead(
                kind = WorkspaceReadKind.ERROR,
                sizeBytes = length,
                error = "读取文件失败：${e.message ?: "未知错误"}",
            )
        }
    }

    fun resolve(relativePath: String): File? {
        val normalized = relativePath.replace('\\', '/').trim()
        if ('\u0000' in normalized) return null
        // This browser only accepts workspace-relative paths. An absolute
        // path is rejected rather than silently re-rooted under the workspace.
        if (java.io.File(normalized).isAbsolute) return null
        val parts = normalized.split('/').filter { it.isNotEmpty() && it != "." }
        if (".." in parts) return null
        val candidate = if (parts.isEmpty()) rootDir else File(rootDir, parts.joinToString("/"))
        val canonical = try {
            candidate.canonicalFile
        } catch (_: Exception) {
            candidate.absoluteFile
        }
        val root = rootDir.path
        if (canonical.path != root && !canonical.path.startsWith("$root${File.separator}")) {
            return null
        }
        return canonical
    }

    private fun relativeOf(file: File): String? {
        val canonical = try {
            file.canonicalFile
        } catch (_: Exception) {
            file.absoluteFile
        }
        val root = rootDir.path
        if (canonical.path == root) return ""
        if (!canonical.path.startsWith("$root${File.separator}")) return null
        return canonical.path.substring(root.length + 1).replace(File.separatorChar, '/')
    }

    companion object {
        fun formatSize(bytes: Long): String = when {
            bytes < 1024 -> "${bytes} B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }
}
