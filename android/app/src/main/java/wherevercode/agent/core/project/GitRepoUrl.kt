package wherevercode.agent.core.project

/**
 * 通用 git 仓库地址解析（2026-11-11，项目来源调研第三步）。
 *
 * Hugging Face Space 就是 host 为 huggingface.co 的 Git URL：Space /
 * 模型 / 数据集仓库都是 `huggingface.co/{spaces,models,datasets}/owner/name`
 * 的 git 地址，不用 HF API、不用模型下载、不用列表；按路径形状规则
 * 解析即可（老项目 `project_file_access.py` 的 HF 分支同一形状，只学
 * 思路，源码自主写；HF 的说法经镜像核对过，见项目来源调研报告 §6）。
 *
 * 模型仓库是权重，不是代码：解析得出来，但
 * [ParsedGitRepoUrl.isEditableProjectHome] 为 false——本次不接模型仓库：
 * 没有 HF 下载能力，一个下不下来的数据家是死条目；等 snapshot 下载
 * 能力来了再定。数据集仓库同理。
 *
 * 纯函数、无 Android 依赖，单测覆盖。拒绝即返回 null，不抛异常、不
 * 静默改写地址（非 https、有凭据、带 query/fragment 一律不认）。
 */
enum class GitRepoKind {
    GITHUB,
    GITLAB,
    GITEE,
    HF_SPACE,
    HF_MODEL,
    HF_DATASET,
    GENERIC,
}

data class ParsedGitRepoUrl(
    val host: String,
    /** host 之后的完整路径（HF Space 如 "spaces/owner/name"，其余如 "owner/repo"）。 */
    val path: String,
    val kind: GitRepoKind,
) {
    /** 归一化仓库地址（https，无 .git 后缀）。只用来校验地址，不用来拉代码。 */
    val cloneUrl: String get() = "https://$host/$path"

    /** 路径段（HF Space 为 [spaces, owner, name]，其余为 [owner, repo] 或群组路径）。 */
    private val segments: List<String> get() = path.split("/")

    /** owner：HF 为第 2 段，GitLab 为除末段外的群组路径，其余为第 1 段。 */
    val owner: String
        get() = when (kind) {
            GitRepoKind.HF_SPACE, GitRepoKind.HF_MODEL, GitRepoKind.HF_DATASET -> segments.getOrNull(1).orEmpty()
            GitRepoKind.GITLAB -> segments.dropLast(1).joinToString("/")
            else -> segments.getOrNull(0).orEmpty()
        }

    /** 仓库名：末段。 */
    val repo: String get() = segments.lastOrNull().orEmpty()

    /** 代码仓库（可编辑）：GitHub/GitLab/Gitee/HF Space/通用 git。 */
    val isCodeRepo: Boolean get() = kind != GitRepoKind.HF_MODEL && kind != GitRepoKind.HF_DATASET

    /** 能当可编辑的项目家：只有代码仓库；模型/数据集仓库是权重/数据，不是家。 */
    val isEditableProjectHome: Boolean get() = isCodeRepo

    /** 展示用角标：Space / 模型仓库 / 数据集；其余为 null。 */
    val badge: String?
        get() = when (kind) {
            GitRepoKind.HF_SPACE -> "Space"
            GitRepoKind.HF_MODEL -> "模型仓库"
            GitRepoKind.HF_DATASET -> "数据集"
            else -> null
        }
}

private val PATH_SEGMENT_REGEX = Regex("^[A-Za-z0-9_.\\-]+$")
private val HF_TYPE_PREFIXES = setOf("spaces", "models", "datasets")

/**
 * 解析无凭据的 https git 仓库地址 → [ParsedGitRepoUrl]，非法返回 null。
 *
 * 接受：github.com/owner/repo、gitlab.com/群组/…/repo（多段群组）、
 * gitee.com/owner/repo、huggingface.co/spaces/owner/name（Space）、
 * huggingface.co/models/owner/name 与 huggingface.co/owner/name（模型简写）、
 * huggingface.co/datasets/owner/name、其它 https host 的 owner/repo。
 * 拒绝：非 https、有凭据（userinfo）、带端口、带 query/fragment、空段、
 * "." / ".." 段、HF 类型前缀不完整（huggingface.co/spaces/owner 只有
 * 2 段时不是模型简写，是缺一段的 Space 地址）。
 * 末尾 "/" 与 ".git" 后缀会被去掉（与 [parseGitHostRepoUrl] 同一口径）。
 */
fun parseGitRepoUrl(url: String): ParsedGitRepoUrl? {
    val trimmed = url.trim().trimEnd('/').removeSuffix(".git")
    if (!trimmed.startsWith("https://")) return null
    val rest = trimmed.removePrefix("https://")
    if (rest.isEmpty() || '?' in rest || '#' in rest) return null
    val slash = rest.indexOf('/')
    if (slash <= 0) return null
    val host = rest.substring(0, slash).lowercase()
    if (host.isEmpty() || '@' in host || ':' in host || ' ' in host) return null
    val path = rest.substring(slash + 1)
    val segments = path.split("/")
    if (segments.any { it.isEmpty() || it == "." || it == ".." || !PATH_SEGMENT_REGEX.matches(it) }) {
        return null
    }
    val kind = when (host) {
        "github.com" -> if (segments.size == 2) GitRepoKind.GITHUB else return null
        "gitlab.com" -> if (segments.size >= 2) GitRepoKind.GITLAB else return null
        "gitee.com" -> if (segments.size == 2) GitRepoKind.GITEE else return null
        "huggingface.co" -> when {
            segments.size == 3 && segments[0] == "spaces" -> GitRepoKind.HF_SPACE
            segments.size == 3 && segments[0] == "models" -> GitRepoKind.HF_MODEL
            segments.size == 3 && segments[0] == "datasets" -> GitRepoKind.HF_DATASET
            // 模型简写 huggingface.co/owner/model；类型前缀开头的 2 段
            // 不是简写（缺一段），不认。
            segments.size == 2 && segments[0] !in HF_TYPE_PREFIXES -> GitRepoKind.HF_MODEL
            else -> return null
        }
        else -> if (segments.size == 2) GitRepoKind.GENERIC else return null
    }
    return ParsedGitRepoUrl(host = host, path = path, kind = kind)
}

/**
 * 项目详情“仓库：”行的展示：Space 显示角标 + 完整路径（推送后在 HF
 * 上重建），模型/数据集仓库同理标明身份；其余显示原地址；非法地址
 * 原样返回（不改写用户填的东西）。
 */
fun gitRepoDisplayLabel(url: String): String {
    val parsed = parseGitRepoUrl(url) ?: return url
    return when (parsed.kind) {
        GitRepoKind.HF_SPACE -> "Space · ${parsed.path}（推送后在 HF 上重建）"
        GitRepoKind.HF_MODEL -> "模型仓库 · ${parsed.path}"
        GitRepoKind.HF_DATASET -> "数据集 · ${parsed.path}"
        else -> url
    }
}
