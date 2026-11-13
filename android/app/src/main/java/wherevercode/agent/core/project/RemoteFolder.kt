package wherevercode.agent.core.project

import org.json.JSONArray

/**
 * 远端文件夹条目（Hand list 回包的一项）。
 *
 * 服务端 `server/hand_server.py` 的 `_do_list` 回包：
 * `cross_device_result`，output 为 JSON 数组 `[{name, is_dir}]`。
 * 列出的是某 Agent 授权文件夹（或它下面的相对子路径）的内容；
 * 凭证的 folder 必须是该 Agent 在设备 auth.json 里的授权文件夹，
 * 子路径由 `_resolve_path` 限在 folder 内（`..` 逃出会被拒绝）。
 */
data class RemoteFolderEntry(val name: String, val isDir: Boolean)

data class ProjectFolderAuthorizationAck(val folder: String, val already: Boolean)

/**
 * Accept only the structured Hand grant acknowledgement bound to this exact
 * request. A generic successful frame is not evidence that access was added.
 */
fun parseProjectFolderAuthorizationAck(
    output: String,
    requestedFolder: String,
): ProjectFolderAuthorizationAck {
    val response = try {
        org.json.JSONObject(output)
    } catch (_: Exception) {
        throw IllegalArgumentException("Hand 未返回有效的项目目录授权回执")
    }
    if (response.opt("authorized") != true) {
        throw IllegalArgumentException("Hand 未确认项目目录已授权")
    }
    if (response.opt("requested_folder") != requestedFolder) {
        throw IllegalArgumentException("Hand 授权回执与请求目录不匹配")
    }
    val canonicalFolder = response.opt("folder") as? String
        ?: throw IllegalArgumentException("Hand 授权回执缺少规范目录")
    if (!isAbsoluteFolderPath(canonicalFolder)) {
        throw IllegalArgumentException("Hand 授权回执中的目录不是绝对路径")
    }
    val already = response.opt("already") as? Boolean
        ?: throw IllegalArgumentException("Hand 授权回执缺少授权状态")
    return ProjectFolderAuthorizationAck(canonicalFolder, already)
}

/**
 * 解析 Hand list 回包 output。空串 = 空文件夹；非法 JSON 抛
 * [IllegalArgumentException]（中文原因），调用方不得把半截结果
 * 当成已列出的文件夹。坏行跳过，不让一条坏记录毁掉整个列表。
 */
fun parseFolderListJson(output: String): List<RemoteFolderEntry> {
    val trimmed = output.trim()
    if (trimmed.isEmpty()) return emptyList()
    val array = try {
        JSONArray(trimmed)
    } catch (_: Exception) {
        throw IllegalArgumentException("远端文件夹列表不是合法 JSON：对方 Hand 版本可能不一致")
    }
    val out = mutableListOf<RemoteFolderEntry>()
    for (i in 0 until array.length()) {
        val obj = array.optJSONObject(i) ?: continue
        val name = obj.optString("name", "").trim()
        if (name.isEmpty()) continue
        out += RemoteFolderEntry(name = name, isDir = obj.optBoolean("is_dir", false))
    }
    return out
}

/** 文件夹 basename：`/workspace/example-project` → `example-project`；`/` → `/`。 */
fun folderBasename(path: String): String {
    val trimmed = path.trim().trimEnd('/')
    if (trimmed.isEmpty()) return "/"
    return trimmed.substringAfterLast('/')
}

/** 拼接文件夹路径：base + relative（relative 为空 = base 本身）。 */
fun joinFolderPath(base: String, relative: String): String {
    val b = base.trim().trimEnd('/')
    val r = relative.trim().trim('/')
    if (r.isEmpty()) return if (b.isEmpty()) "/" else b
    return "$b/$r"
}

/** 是否为绝对路径（以 / 开头）。 */
fun isAbsoluteFolderPath(path: String): Boolean = path.trim().startsWith("/")

/**
 * 把项目文件夹解析成它在某 Agent 授权文件夹下的相对路径：
 * - 等于 Agent 文件夹本身 → ""（列文件夹本身）；
 * - 在其子目录下 → 相对子路径（如 `sub/project`）；
 * - 不在其下 → null（该文件夹不在此 Agent 的授权范围内，Hand 会拒绝）。
 */
fun resolveProjectRelativePath(agentFolder: String, homeFolder: String): String? {
    val base = normalizeFolderPath(agentFolder)
    val home = normalizeFolderPath(homeFolder)
    if (home == base) return ""
    if (home.startsWith("$base/")) return home.removePrefix("$base/")
    return null
}
