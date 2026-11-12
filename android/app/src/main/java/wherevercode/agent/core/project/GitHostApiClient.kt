package wherevercode.agent.core.project

import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * git 托管站点的 REST API 客户端（git 家：家就是托管站上的仓库本身）。
 *
 * 一个站点一个实例（[host] 定了 API 根地址与认证头）。Agent 经它
 * 干活：读文件、改文件、推分支、开 PR / 合并请求，全走该站 API。
 * 不 git clone、不把项目文件拷到任何设备上。
 *
 * 三站端点（2026-11-11 对官方文档逐项核过，见 DELIVERY）：
 * - GitHub（docs.github.com，REST API v3）：
 *   GET /repos/{o}/{r}；GET /repos/{o}/{r}/contents/{path}?ref=
 *   （读文件 base64 或列目录）；PUT /repos/{o}/{r}/contents/{path}
 *   （建/改文件）；GET /repos/{o}/{r}/git/ref/heads/{branch}；
 *   POST /repos/{o}/{r}/git/refs；POST /repos/{o}/{r}/pulls。
 * - GitLab（docs.gitlab.com，REST API v4；项目 id 用 URL 编码后
 *   的完整命名空间路径，如 group%2Fsubgroup%2Fproject）：
 *   GET /projects/{id}；GET /projects/{id}/repository/tree
 *   （?path=&ref=，不存在的路径回 404）；GET /projects/{id}/
 *   repository/files/{file_path}?ref=（file_path 须把斜杠编成 %2F
 *   的单个段）；POST/PUT /projects/{id}/repository/files/{file_path}
 *   （建/改，content 为 base64 时 encoding="base64"）；
 *   GET /projects/{id}/repository/branches/{branch}；
 *   POST /projects/{id}/repository/branches；POST /projects/{id}/
 *   merge_requests。
 * - Gitee（gitee.com/api/v5）：
 *   GET /repos/{o}/{r}；GET /repos/{o}/{r}/contents/{path}?ref=
 *   （目录回数组，文件回单对象 base64）；POST/PUT /repos/{o}/{r}/
 *   contents/{path}（建/改，更新须带 sha）；GET /repos/{o}/{r}/
 *   branches/{branch}；POST /repos/{o}/{r}/branches；
 *   POST /repos/{o}/{r}/pulls。
 *
 * 安全：token 由调用方每次传入，只出现在该站的认证请求头里
 * （见 [GitHost.authHeader]：GitHub Bearer、GitLab PRIVATE-TOKEN、
 * Gitee `token`）。本类不存 token、不打 token 进日志、不把 token
 * 放进错误文案。仓库地址只接受无凭据的 https 链接（见
 * [parseGitHostRepoUrl]）。
 */
class GitHostApiClient(
    val host: GitHost,
    private val transport: GitHostTransport = HttpsGitHostTransport(host),
) {

    private val apiBase: String get() = host.apiBase

    /** 仓库是否存在、默认分支是什么。只读 GET，不改任何东西。 */
    suspend fun getRepo(token: String, namespacePath: String): Result<GitRepo> =
        withContext(Dispatchers.IO) {
            requireToken(token)?.let { return@withContext Result.failure(it) }
            val connection = try {
                transport.get(repoUrl(host, namespacePath), headers(token))
            } catch (e: IOException) {
                return@withContext Result.failure(transportFailure(e))
            }
            try {
                if (connection.statusCode != 200) {
                    return@withContext Result.failure(mapHttpError(connection))
                }
                val root = JSONObject(connection.openBodyReader().use { it.readText() })
                Result.success(parseRepo(root, namespacePath))
            } catch (e: Exception) {
                Result.failure(
                    IllegalStateException("解析 ${host.displayName} 仓库信息失败：${e.message}"),
                )
            } finally {
                closeQuietly(connection)
            }
        }

    /** 列出仓库里一个目录（path 为空 = 根目录）。ref 为分支名或 commit。 */
    suspend fun listContents(
        token: String,
        namespacePath: String,
        path: String,
        ref: String,
    ): Result<List<GitContentEntry>> = withContext(Dispatchers.IO) {
        requireToken(token)?.let { return@withContext Result.failure(it) }
        val url = when (host.id) {
            GitHostId.GITLAB -> treeUrl(host, namespacePath, path, ref)
            else -> contentsUrl(host, namespacePath, path) + "?ref=" + encodeSegment(ref)
        }
        val connection = try {
            transport.get(url, headers(token))
        } catch (e: IOException) {
            return@withContext Result.failure(transportFailure(e))
        }
        try {
            if (connection.statusCode != 200) {
                return@withContext Result.failure(mapHttpError(connection))
            }
            val text = connection.openBodyReader().use { it.readText() }.trim()
            val entries = mutableListOf<GitContentEntry>()
            if (text.startsWith("[")) {
                val array = JSONArray(text)
                for (i in 0 until array.length()) {
                    val entry = if (host.id == GitHostId.GITLAB) {
                        parseTreeEntry(array.optJSONObject(i))
                    } else {
                        parseContentEntry(array.optJSONObject(i))
                    }
                    entry?.let { entries += it }
                }
            } else {
                // 单个文件：包成一条，调用方按 type 区分。
                parseContentEntry(JSONObject(text))?.let { entries += it }
            }
            Result.success(entries)
        } catch (e: Exception) {
            Result.failure(
                IllegalStateException("解析 ${host.displayName} 目录失败：${e.message}"),
            )
        } finally {
            closeQuietly(connection)
        }
    }

    /** 读一个文件（base64 解码成文本）。ref 为分支名或 commit。 */
    suspend fun getFile(
        token: String,
        namespacePath: String,
        path: String,
        ref: String,
    ): Result<GitFile> = withContext(Dispatchers.IO) {
        requireToken(token)?.let { return@withContext Result.failure(it) }
        val url = contentsUrl(host, namespacePath, path) + "?ref=" + encodeSegment(ref)
        val connection = try {
            transport.get(url, headers(token))
        } catch (e: IOException) {
            return@withContext Result.failure(transportFailure(e))
        }
        try {
            if (connection.statusCode != 200) {
                return@withContext Result.failure(mapHttpError(connection))
            }
            val root = JSONObject(connection.openBodyReader().use { it.readText() })
            if (host.id == GitHostId.GITLAB) {
                // GitLab files 端点没有 type 字段；不是 base64 编码
                // 或没有内容时按读失败处理（目录会直接回 400）。
                if (root.optString("encoding", "") != "base64") {
                    return@withContext Result.failure(
                        IllegalStateException("“$path”不是文件（是目录或不存在）：请填仓库里的文件路径。"),
                    )
                }
                val decoded = decodeBase64(root.optString("content", ""))
                    ?: return@withContext Result.failure(
                        IllegalStateException("${host.displayName} 返回的文件内容不是有效的 base64。"),
                    )
                Result.success(
                    GitFile(
                        path = root.optString("file_path", path),
                        sha = root.optString("blob_id", ""),
                        text = decoded,
                        size = root.optLong("size", 0),
                    ),
                )
            } else {
                if (root.optString("type", "") != "file") {
                    return@withContext Result.failure(
                        IllegalStateException("“$path”不是文件（是目录或不存在）：请填仓库里的文件路径。"),
                    )
                }
                val decoded = decodeBase64(root.optString("content", ""))
                    ?: return@withContext Result.failure(
                        IllegalStateException("${host.displayName} 返回的文件内容不是有效的 base64。"),
                    )
                Result.success(
                    GitFile(
                        path = root.optString("path", path),
                        sha = root.optString("sha", ""),
                        text = decoded,
                        size = root.optLong("size", 0),
                    ),
                )
            }
        } catch (e: Exception) {
            Result.failure(
                IllegalStateException("读取 ${host.displayName} 文件失败：${e.message}"),
            )
        } finally {
            closeQuietly(connection)
        }
    }

    /** 取一个分支的头 commit sha。 */
    suspend fun getBranchHead(
        token: String,
        namespacePath: String,
        branch: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        requireToken(token)?.let { return@withContext Result.failure(it) }
        val url = when (host.id) {
            GitHostId.GITHUB ->
                "${repoUrl(host, namespacePath)}/git/ref/heads/${encodeSegment(branch)}"
            GitHostId.GITLAB ->
                "${repoUrl(host, namespacePath)}/repository/branches/${encodeSegment(branch)}"
            GitHostId.GITEE ->
                "${repoUrl(host, namespacePath)}/branches/${encodeSegment(branch)}"
        }
        val connection = try {
            transport.get(url, headers(token))
        } catch (e: IOException) {
            return@withContext Result.failure(transportFailure(e))
        }
        try {
            if (connection.statusCode != 200) {
                return@withContext Result.failure(mapHttpError(connection))
            }
            val root = JSONObject(connection.openBodyReader().use { it.readText() })
            val sha = when (host.id) {
                GitHostId.GITHUB -> root.optJSONObject("object")?.optString("sha", "").orEmpty()
                GitHostId.GITLAB -> root.optJSONObject("commit")?.optString("id", "").orEmpty()
                GitHostId.GITEE -> root.optJSONObject("commit")?.optString("sha", "").orEmpty()
            }
            if (sha.isBlank()) {
                Result.failure(
                    IllegalStateException("${host.displayName} 没有返回分支“$branch”的 commit。"),
                )
            } else {
                Result.success(sha)
            }
        } catch (e: Exception) {
            Result.failure(IllegalStateException("读取分支失败：${e.message}"))
        } finally {
            closeQuietly(connection)
        }
    }

    /** 从 fromSha 建一个新分支。已存在时各站如实报错（GitHub 422 / GitLab 400 / Gitee 422）。 */
    suspend fun createBranch(
        token: String,
        namespacePath: String,
        branch: String,
        fromSha: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        requireToken(token)?.let { return@withContext Result.failure(it) }
        if (!isValidGitBranch(branch)) {
            return@withContext Result.failure(
                IllegalStateException("分支名“$branch”不是合法的 Git 分支名。"),
            )
        }
        val (url, body) = when (host.id) {
            GitHostId.GITHUB -> repoUrl(host, namespacePath) + "/git/refs" to JSONObject()
                .put("ref", "refs/heads/$branch")
                .put("sha", fromSha)
            GitHostId.GITLAB -> repoUrl(host, namespacePath) + "/repository/branches" to JSONObject()
                .put("branch", branch)
                .put("ref", fromSha)
            GitHostId.GITEE -> repoUrl(host, namespacePath) + "/branches" to JSONObject()
                .put("refs", fromSha)
                .put("branch_name", branch)
        }
        val connection = try {
            transport.post(url, headers(token), body.toString())
        } catch (e: IOException) {
            return@withContext Result.failure(transportFailure(e))
        }
        try {
            if (connection.statusCode != 201) {
                return@withContext Result.failure(mapHttpError(connection))
            }
            val root = JSONObject(connection.openBodyReader().use { it.readText() })
            val created = when (host.id) {
                GitHostId.GITHUB -> root.optString("ref", "refs/heads/$branch")
                else -> root.optString("name", branch)
            }
            Result.success(created)
        } catch (e: Exception) {
            Result.failure(IllegalStateException("创建分支失败：${e.message}"))
        } finally {
            closeQuietly(connection)
        }
    }

    /**
     * 建一个文件或改一个已有的文件。
     * - GitHub：PUT contents（改已有文件必须传当前 sha；[sha] 为空 = 新建）。
     * - GitLab：POST 新建 / PUT 改（改时 [sha] 传作 last_commit_id，
     *   为空 = 不带；content 为 base64 时 encoding="base64"）。
     * - Gitee：POST 新建 / PUT 改（改时必须传当前 sha）。
     * branch 指定写到哪个分支。回新文件 sha 与 commit sha
     * （GitLab 建/改文件接口不回 commit sha，该字段为空，如实不编）。
     */
    suspend fun createOrUpdateFile(
        token: String,
        namespacePath: String,
        path: String,
        content: String,
        message: String,
        branch: String,
        sha: String? = null,
    ): Result<GitFileWrite> = withContext(Dispatchers.IO) {
        requireToken(token)?.let { return@withContext Result.failure(it) }
        if (path.isBlank() || path.startsWith("/")) {
            return@withContext Result.failure(
                IllegalStateException("文件路径须是仓库内的相对路径，如 README.md。"),
            )
        }
        if (message.isBlank()) {
            return@withContext Result.failure(IllegalStateException("提交信息不能为空。"))
        }
        val url = contentsUrl(host, namespacePath, path)
        val connection = try {
            when (host.id) {
                GitHostId.GITHUB -> {
                    val body = JSONObject()
                        .put("message", message)
                        .put("content", encodeBase64(content))
                        .put("branch", branch)
                    if (!sha.isNullOrBlank()) body.put("sha", sha)
                    transport.put(url, headers(token), body.toString())
                }
                GitHostId.GITLAB -> {
                    val body = JSONObject()
                        .put("branch", branch)
                        .put("commit_message", message)
                        .put("content", encodeBase64(content))
                        .put("encoding", "base64")
                    if (sha.isNullOrBlank()) {
                        transport.post(url, headers(token), body.toString())
                    } else {
                        body.put("last_commit_id", sha)
                        transport.put(url, headers(token), body.toString())
                    }
                }
                GitHostId.GITEE -> {
                    val body = JSONObject()
                        .put("content", encodeBase64(content))
                        .put("message", message)
                        .put("branch", branch)
                    if (sha.isNullOrBlank()) {
                        transport.post(url, headers(token), body.toString())
                    } else {
                        body.put("sha", sha)
                        transport.put(url, headers(token), body.toString())
                    }
                }
            }
        } catch (e: IOException) {
            return@withContext Result.failure(transportFailure(e))
        }
        try {
            if (connection.statusCode != 200 && connection.statusCode != 201) {
                return@withContext Result.failure(mapHttpError(connection))
            }
            val root = JSONObject(connection.openBodyReader().use { it.readText() })
            if (host.id == GitHostId.GITLAB) {
                // GitLab 建/改文件接口只回 file_path + branch，不回 sha。
                Result.success(
                    GitFileWrite(
                        path = root.optString("file_path", path),
                        sha = "",
                        commitSha = "",
                    ),
                )
            } else {
                val contentObj = root.optJSONObject("content")
                val commitObj = root.optJSONObject("commit")
                Result.success(
                    GitFileWrite(
                        path = contentObj?.optString("path", path) ?: path,
                        sha = contentObj?.optString("sha", "").orEmpty(),
                        commitSha = commitObj?.optString("sha", "").orEmpty(),
                    ),
                )
            }
        } catch (e: Exception) {
            Result.failure(
                IllegalStateException("写 ${host.displayName} 文件失败：${e.message}"),
            )
        } finally {
            closeQuietly(connection)
        }
    }

    /**
     * 开一个 PR（GitHub/Gitee）或合并请求（GitLab）：
     * head 分支 → base 分支。
     */
    suspend fun createPullRequest(
        token: String,
        namespacePath: String,
        head: String,
        base: String,
        title: String,
        body: String,
    ): Result<GitPullRequest> = withContext(Dispatchers.IO) {
        requireToken(token)?.let { return@withContext Result.failure(it) }
        if (title.isBlank()) {
            return@withContext Result.failure(IllegalStateException("PR 标题不能为空。"))
        }
        val (url, payload) = when (host.id) {
            GitHostId.GITLAB -> repoUrl(host, namespacePath) + "/merge_requests" to JSONObject()
                .put("title", title)
                .put("source_branch", head)
                .put("target_branch", base)
                .put("description", body)
            else -> repoUrl(host, namespacePath) + "/pulls" to JSONObject()
                .put("title", title)
                .put("head", head)
                .put("base", base)
                .put("body", body)
        }
        val connection = try {
            transport.post(url, headers(token), payload.toString())
        } catch (e: IOException) {
            return@withContext Result.failure(transportFailure(e))
        }
        try {
            if (connection.statusCode != 201) {
                return@withContext Result.failure(mapHttpError(connection))
            }
            val root = JSONObject(connection.openBodyReader().use { it.readText() })
            val number = if (host.id == GitHostId.GITLAB) {
                root.optInt("iid", 0)
            } else {
                root.optInt("number", 0)
            }
            val htmlUrl = if (host.id == GitHostId.GITLAB) {
                root.optString("web_url", "")
            } else {
                root.optString("html_url", "")
            }
            Result.success(
                GitPullRequest(
                    number = number,
                    htmlUrl = htmlUrl,
                    title = root.optString("title", title),
                ),
            )
        } catch (e: Exception) {
            Result.failure(IllegalStateException("开 PR 失败：${e.message}"))
        } finally {
            closeQuietly(connection)
        }
    }

    private fun headers(token: String): Map<String, String> {
        val (name, value) = host.authHeader(token)
        return mapOf(name to value)
    }

    private fun requireToken(token: String): IllegalStateException? =
        if (token.isBlank()) {
            IllegalStateException(
                "未配置 ${host.displayName} 授权：请先在设置 → ${host.displayName} 授权填写你自己的 ${host.displayName} 授权（token），存在本机。",
            )
        } else {
            null
        }

    private fun transportFailure(e: IOException): IllegalStateException {
        val reason = if (e is SocketTimeoutException) "超时" else "网络异常"
        return IllegalStateException("连接 ${host.displayName} 失败（$reason）：${e.message}")
    }

    private fun mapHttpError(connection: GitHostConnection): IllegalStateException {
        val detail = parseErrorBody(connection.readErrorBody())
        val name = host.displayName
        return when (connection.statusCode) {
            -1 -> IllegalStateException("连接 $name 失败或超时（未收到 HTTP 响应）。$detail")
            401 -> IllegalStateException("$name 授权失败（401）：授权无效或已过期，请重新填写。$detail")
            403 -> IllegalStateException("$name 拒绝（403）：授权没有该仓库的权限，或已触发限流。$detail")
            404 -> IllegalStateException("$name 找不到（404）：仓库、分支或文件不存在，或授权无权访问。$detail")
            409 -> IllegalStateException("$name 冲突（409）：仓库为空或正在创建。$detail")
            422 -> IllegalStateException("$name 校验失败（422）：$detail")
            in 500..599 -> IllegalStateException("$name 服务暂不可用（HTTP ${connection.statusCode}）。$detail")
            else -> IllegalStateException("$name 请求失败（HTTP ${connection.statusCode}）。$detail")
        }
    }

    private fun parseErrorBody(body: String?): String {
        if (body.isNullOrBlank()) return ""
        return try {
            val root = JSONObject(body)
            // GitHub/Gitee 用 message；GitLab 的 message 有时在 error 字段。
            val message = root.optString("message", "").takeIf { it.isNotBlank() }
                ?: root.optString("error", "").takeIf { it.isNotBlank() }
            if (message.isNullOrBlank()) "" else "（$message）"
        } catch (_: Exception) {
            body.take(300)
        }
    }

    /** 三站 getRepo 回包的差别只在字段名，归一成同一个 [GitRepo]。 */
    private fun parseRepo(root: JSONObject, namespacePath: String): GitRepo {
        return when (host.id) {
            GitHostId.GITLAB -> {
                val fullName = root.optString("path_with_namespace", namespacePath)
                GitRepo(
                    fullName = fullName,
                    owner = fullName.substringBeforeLast("/"),
                    name = root.optString("name", namespacePath.substringAfterLast("/")),
                    defaultBranch = root.optString("default_branch", "main"),
                    isPrivate = root.optString("visibility", "") == "private",
                    htmlUrl = root.optString("web_url", ""),
                )
            }
            else -> GitRepo(
                fullName = root.optString("full_name", namespacePath),
                owner = root.optJSONObject("owner")?.optString(
                    "login",
                    namespacePath.substringBefore("/"),
                ) ?: namespacePath.substringBefore("/"),
                name = root.optString("name", namespacePath.substringAfterLast("/")),
                defaultBranch = root.optString("default_branch", "main"),
                isPrivate = root.optBoolean("private", false),
                htmlUrl = root.optString("html_url", ""),
            )
        }
    }

    private fun parseContentEntry(obj: JSONObject?): GitContentEntry? {
        if (obj == null) return null
        return GitContentEntry(
            name = obj.optString("name", ""),
            path = obj.optString("path", ""),
            type = obj.optString("type", ""),
            sha = obj.optString("sha", ""),
            size = obj.optLong("size", 0),
        )
    }

    /** GitLab tree 条目：type 是 "blob"（文件）或 "tree"（目录），sha 在 id 里。 */
    private fun parseTreeEntry(obj: JSONObject?): GitContentEntry? {
        if (obj == null) return null
        val type = when (obj.optString("type", "")) {
            "blob" -> "file"
            "tree" -> "dir"
            else -> obj.optString("type", "")
        }
        return GitContentEntry(
            name = obj.optString("name", ""),
            path = obj.optString("path", ""),
            type = type,
            sha = obj.optString("id", ""),
            size = 0,
        )
    }

    private fun closeQuietly(connection: GitHostConnection) {
        try {
            connection.close()
        } catch (_: Exception) {
            // ignore
        }
    }

    companion object {
        /** 仓库根 URL：GitHub/Gitee 是 /repos/{ns}，GitLab 是 /projects/{URL 编码后的完整路径}。 */
        internal fun repoUrl(host: GitHost, namespacePath: String): String =
            when (host.id) {
                GitHostId.GITLAB ->
                    "${host.apiBase}/projects/${encodeSegment(namespacePath)}"
                else ->
                    "${host.apiBase}/repos/$namespacePath"
            }

        /**
         * 读/写单个文件的 URL：GitHub/Gitee 是 contents 路径（每段
         * 单独编码，斜杠保留）；GitLab 是 repository/files 路径
         * （整个文件路径编成一个段，斜杠变 %2F）。
         */
        internal fun contentsUrl(host: GitHost, namespacePath: String, path: String): String =
            when (host.id) {
                GitHostId.GITLAB -> {
                    val encoded = encodeSegment(path)
                    "${repoUrl(host, namespacePath)}/repository/files/$encoded"
                }
                else -> {
                    val encoded = path.split("/")
                        .filter { it.isNotEmpty() }
                        .joinToString("/") { encodeSegment(it) }
                    "${repoUrl(host, namespacePath)}/contents/$encoded"
                }
            }

        /** GitLab 列目录的 tree URL（path 为空 = 根目录，不带 path 参数）。 */
        internal fun treeUrl(host: GitHost, namespacePath: String, path: String, ref: String): String {
            val base = "${repoUrl(host, namespacePath)}/repository/tree?ref=${encodeSegment(ref)}&per_page=100"
            return if (path.isBlank()) base else "$base&path=${encodeSegment(path)}"
        }

        /** URL 路径段编码（空格编成 %20，不是 URLEncoder 默认的 +；斜杠也编成 %2F）。 */
        internal fun encodeSegment(segment: String): String =
            URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")

        internal fun encodeBase64(text: String): String =
            Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

        internal fun decodeBase64(content: String): String? = try {
            val cleaned = content.replace("\\s".toRegex(), "")
            if (cleaned.isEmpty()) "" else String(
                Base64.getMimeDecoder().decode(cleaned),
                Charsets.UTF_8,
            )
        } catch (_: Exception) {
            null
        }
    }
}

/** 仓库信息（GET 仓库接口）。 */
data class GitRepo(
    val fullName: String,
    val owner: String,
    val name: String,
    val defaultBranch: String,
    val isPrivate: Boolean,
    val htmlUrl: String,
)

/** 目录/文件条目。type 为 "file" 或 "dir"。 */
data class GitContentEntry(
    val name: String,
    val path: String,
    val type: String,
    val sha: String,
    val size: Long,
)

/** 读到的文件（base64 已解码）。 */
data class GitFile(
    val path: String,
    val sha: String,
    val text: String,
    val size: Long,
)

/** 写文件的结果。 */
data class GitFileWrite(
    val path: String,
    val sha: String,
    val commitSha: String,
)

/** 开 PR / 合并请求的结果（GitLab 的 number 是 iid）。 */
data class GitPullRequest(
    val number: Int,
    val htmlUrl: String,
    val title: String,
)

/**
 * 可注入的 HTTP 传输。生产用 [HttpsGitHostTransport]；测试用 fake。
 * 认证只经 headers 传入（见 [GitHost.authHeader]），传输实现不得
 * 存储或记录它，也不得自己拼认证头。
 */
interface GitHostTransport {
    @Throws(IOException::class)
    fun get(url: String, headers: Map<String, String>): GitHostConnection

    @Throws(IOException::class)
    fun put(url: String, headers: Map<String, String>, requestBody: String): GitHostConnection

    @Throws(IOException::class)
    fun post(url: String, headers: Map<String, String>, requestBody: String): GitHostConnection
}

interface GitHostConnection : Closeable {
    val statusCode: Int
    fun openBodyReader(): BufferedReader
    fun readErrorBody(): String?
}

/** HttpsURLConnection 实现；不引入额外 HTTP 依赖。 */
class HttpsGitHostTransport(
    private val host: GitHost,
    private val connectTimeoutMs: Int = 30_000,
    private val readTimeoutMs: Int = 120_000,
) : GitHostTransport {

    override fun get(url: String, headers: Map<String, String>): GitHostConnection =
        open(url, headers, "GET", null)

    override fun put(url: String, headers: Map<String, String>, requestBody: String): GitHostConnection =
        open(url, headers, "PUT", requestBody)

    override fun post(url: String, headers: Map<String, String>, requestBody: String): GitHostConnection =
        open(url, headers, "POST", requestBody)

    private fun open(
        url: String,
        headers: Map<String, String>,
        method: String,
        requestBody: String?,
    ): GitHostConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty(
            "Accept",
            if (host.id == GitHostId.GITHUB) "application/vnd.github+json" else "application/json",
        )
        if (host.id == GitHostId.GITHUB) {
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        connection.setRequestProperty("User-Agent", "wherevercode-agent")
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        if (requestBody != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
        }
        return HttpsGitHostConnection(connection)
    }
}

private class HttpsGitHostConnection(
    private val connection: HttpURLConnection,
) : GitHostConnection {

    override val statusCode: Int
        get() = try {
            connection.responseCode
        } catch (_: IOException) {
            -1
        }

    override fun openBodyReader(): BufferedReader =
        BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8))

    override fun readErrorBody(): String? = try {
        connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    override fun close() {
        try {
            connection.disconnect()
        } catch (_: Exception) {
            // ignore
        }
    }
}
