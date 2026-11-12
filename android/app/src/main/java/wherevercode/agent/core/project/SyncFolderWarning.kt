package wherevercode.agent.core.project

/**
 * 网盘同步目录警告（2026-11-11，项目来源调研第三步）：
 * `.git` 落在网盘同步目录里会被同步损坏——只警告，不拦截。
 *
 * 判定是启发式的（路径段里出现网盘客户端的同步文件夹名），说的是
 * “看起来在同步目录里”，不是断言；命中了也只是多一句警告，用户
 * 照样可以把文件夹当作家。纯函数、无 Android 依赖，单测覆盖。
 */
private val SYNC_CLIENT_BY_TOKEN: List<Pair<String, String>> = listOf(
    "onedrive" to "OneDrive",
    "dropbox" to "Dropbox",
    "googledrive" to "Google Drive",
    "nutstore" to "坚果云",
    "cloudstorage" to "iCloud",
    "baidunetdisk" to "百度网盘",
    "坚果云" to "坚果云",
    "百度网盘" to "百度网盘",
)

/** 须整段相等才算的通用词（"drive" / "box" 这类单字太常见，不算）。 */
private val SYNC_EXACT_SEGMENTS: Map<String, String> = mapOf(
    "google drive" to "Google Drive",
    "my drive" to "Google Drive",
    "icloud drive" to "iCloud",
    "icloud" to "iCloud",
)

/** 路径是否在网盘同步目录里：返回网盘名，未命中返回 null。 */
fun detectSyncedFolder(path: String): String? {
    val segments = path.split("/").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    for (segment in segments) {
        SYNC_EXACT_SEGMENTS[segment]?.let { return it }
        for ((token, name) in SYNC_CLIENT_BY_TOKEN) {
            if (token in segment) return name
        }
    }
    return null
}

/** 同步警告文案（非阻塞）：命中返回一句警告，未命中返回 null。 */
fun syncFolderWarning(path: String): String? {
    val client = detectSyncedFolder(path) ?: return null
    return "注意：这个文件夹在${client}同步目录里，同步与 Agent 同时改代码可能损坏 .git；建议把代码放在不同步的文件夹，或用 GitHub 仓库当家。"
}
