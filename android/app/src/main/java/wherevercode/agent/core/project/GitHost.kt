package wherevercode.agent.core.project

import java.net.URI

/**
 * git 托管站点的 id。写进 [ProjectLocation.hostId]、Keystore 授权键
 * （见 [GitHost.tokenRef]）的一部分；仓库地址解析也靠它分流。
 */
enum class GitHostId(val wire: String) {
    GITHUB("github"),
    GITLAB("gitlab"),
    GITEE("gitee"),
}

/**
 * 一个 git 托管站点（GitHub / GitLab / Gitee）的全部差别。
 *
 * GitHub 家那条路原来把这些值写死在客户端里；GitLab/Gitee 是
 * 同一条路上的新 host，只差三样：API 根地址、认证头、仓库地址
 * 形状。认证头是各站最容易写错的地方（2026-11-11 对官方文档
 * 逐项核过，见 DELIVERY）：
 * - GitHub：`Authorization: Bearer <token>`（PAT）
 * - GitLab：`PRIVATE-TOKEN: <token>`——**不是** Bearer，写错就 401
 * - Gitee：`Authorization: token <token>`（PAT；Bearer 是 Gitee
 *   MCP 网关的头，不是 v5 REST 的）
 *
 * token 由用户在 App 里自己填，存在本机 Keystore（见
 * [GitHostCredentialStore]），每个 host 各自一条，绝不发给 Hand、
 * 绝不进代码/文件/日志。私有仓库第一天就能用 token 进——不写
 * “私有仓库即将支持”这类占位文案。
 *
 * 自建 GitLab（自定义 base URL）是以后的一步：本次只接
 * gitlab.com，不做自定义地址。
 */
data class GitHost(
    val id: GitHostId,
    /** 界面上的名字：GitHub / GitLab / Gitee。 */
    val displayName: String,
    /** REST API 根地址，不带末尾斜杠。 */
    val apiBase: String,
    /** 网页根地址，不带末尾斜杠（仓库地址解析用）。 */
    val webBase: String,
    /** 该站授权在 Keystore 里的键（每个 host 各自一条）。 */
    val tokenRef: String,
    /** 建 token 的入口说明（界面文案，不含任何真实凭证）。 */
    val tokenHelp: String,
    /** 读/写该站仓库需要的 token 权限（界面文案）。 */
    val tokenScopeHint: String,
) {
    /** 该站的认证头（见类注释：三站各不相同，别写错）。 */
    fun authHeader(token: String): Pair<String, String> = when (id) {
        GitHostId.GITHUB -> "Authorization" to "Bearer $token"
        GitHostId.GITLAB -> "PRIVATE-TOKEN" to token
        GitHostId.GITEE -> "Authorization" to "token $token"
    }

    /** 命名空间路径 → 该站的仓库网页地址。 */
    fun webUrl(namespacePath: String): String = "$webBase/$namespacePath"

    companion object {
        val GITHUB: GitHost = GitHost(
            id = GitHostId.GITHUB,
            displayName = "GitHub",
            apiBase = "https://api.github.com",
            webBase = "https://github.com",
            tokenRef = "github:token",
            tokenHelp = "GitHub 网页：头像 → Settings → Developer settings → Personal access tokens，新建一个能读该仓库的 token（repo 权限），粘贴进来。",
            tokenScopeHint = "repo",
        )
        val GITLAB: GitHost = GitHost(
            id = GitHostId.GITLAB,
            displayName = "GitLab",
            apiBase = "https://gitlab.com/api/v4",
            webBase = "https://gitlab.com",
            tokenRef = "gitlab:token",
            tokenHelp = "GitLab 网页：头像 → Preferences → Access Tokens，新建一个 token（读仓库用 read_api，写文件/开合并请求用 api），粘贴进来。",
            tokenScopeHint = "read_api（读）/ api（写）",
        )
        val GITEE: GitHost = GitHost(
            id = GitHostId.GITEE,
            displayName = "Gitee",
            apiBase = "https://gitee.com/api/v5",
            webBase = "https://gitee.com",
            tokenRef = "gitee:token",
            tokenHelp = "Gitee 网页：头像 → 个人设置 → 安全设置 → 私人令牌，新建一个能读写仓库的 token（projects 权限），粘贴进来。",
            tokenScopeHint = "projects",
        )
        val ALL: List<GitHost> = listOf(GITHUB, GITLAB, GITEE)

        fun fromWire(wire: String): GitHost? = ALL.firstOrNull { it.id.wire == wire }

        fun fromWebHost(webHost: String): GitHost? =
            ALL.firstOrNull { it.webBase.removePrefix("https://") == webHost.lowercase() }
    }
}

/** git 托管仓库的坐标：站点 + 命名空间路径。 */
data class GitRepoCoordinates(
    val host: GitHost,
    /**
     * 命名空间路径：GitHub/Gitee 是 `owner/repo`（恰好两段）；
     * GitLab 允许多段（子群组）：`group/subgroup/project`。
     */
    val namespacePath: String,
) {
    /** 仓库名 = 路径最后一段（建项目、取 slug 用）。 */
    val repoName: String get() = namespacePath.substringAfterLast("/")
}

/** 一段合法的命名空间段：字母数字 _ . -（GitLab 群组/子群组/项目同此规则）。 */
private val NAMESPACE_SEGMENT_REGEX = Regex("[A-Za-z0-9_.-]+")

/**
 * 解析无凭据的 git 托管仓库 https 地址 → 坐标，非法返回 null。
 * - GitHub：https://github.com/owner/repo
 * - GitLab：https://gitlab.com/group/subgroup/project（子群组多段）
 * - Gitee：https://gitee.com/owner/repo
 * 末尾可带 `.git` 与 `/`；带凭据（userinfo）、带 query/fragment、
 * 非 https、未知站点、段数不对、段含非法字符的，一律拒绝。
 * 与 [ProjectStore] 的仓库正则同一口径。
 */
fun parseGitHostRepoUrl(url: String): GitRepoCoordinates? {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return null
    val uri = try {
        URI(trimmed)
    } catch (_: Exception) {
        return null
    }
    if (uri.scheme != "https") return null
    if (uri.userInfo != null) return null
    if (uri.query != null || uri.fragment != null) return null
    if (uri.port != -1 && uri.port != 443) return null
    val host = GitHost.fromWebHost(uri.host ?: return null) ?: return null
    val path = uri.path?.trim('/') ?: return null
    val withoutGit = path.removeSuffix(".git").trimEnd('/')
    val segments = withoutGit.split("/").filter { it.isNotEmpty() }
    // 三站的项目路径至少两段（命名空间/项目）；GitHub/Gitee 恰好
    // 两段，GitLab 子群组允许多段。
    if (segments.size < 2) return null
    if (host.id != GitHostId.GITLAB && segments.size != 2) return null
    if (segments.any { !NAMESPACE_SEGMENT_REGEX.matches(it) || it == "." || it == ".." }) {
        return null
    }
    return GitRepoCoordinates(host, segments.joinToString("/"))
}

/** 坐标 → 无凭据的仓库 https 地址。 */
fun gitHostRepoUrl(coordinates: GitRepoCoordinates): String =
    coordinates.host.webUrl(coordinates.namespacePath)
