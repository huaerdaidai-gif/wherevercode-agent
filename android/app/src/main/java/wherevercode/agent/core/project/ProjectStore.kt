package wherevercode.agent.core.project

import android.content.Context
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Project / ProjectLocation：老项目 `project_locations.py` +
 * `docs/PROJECT_HOST_BINDING_V1.md` 的模型，源码自主写，只学思路与协议。
 *
 * 三条记录分开存：
 * - Project（逻辑身份）：id、名、可选 canonical GitHub 来源
 *   （repo_url / default_branch / canonical_commit）、origin。
 *   注册只建元数据（path 为 null），不克隆、不拉目录、不联网。
 *   Project Browser 即使没有 ProjectLocation 也展示逻辑项目。
 * - Host（设备，见 `core/transport/RemoteDevice` + `HostCapabilities`）：
 *   能力位决定它能不能托管项目、执行任务；注册本身不产生任何位置。
 * - ProjectLocation（位置）：引用已注册的 Project
 *   （project_id + host_id + workspace_path），位置 id 复用老项目
 *   `_location_id` 形状。Workspace 路径只存在位置记录里；
 *   项目浏览器消费的是“设备上的项目列表”（host project listing，
 *   见 [HostProjectListing]），而不是 App 自己猜出来的。
 *   家有三种（maintainer 2026-11-11 05:32 定前两种，2026-11-11 来源选择器
 *   加第三种）：设备文件夹家（代码在家设备上，Hand 就地干活）、git
 *   家（家就是 GitHub / GitLab / Gitee 上的仓库本身，Agent 经该站
 *   API 干活）与本机文件夹家（代码在本机工作区下的文件夹里，App
 *   就地浏览）。
 *
 * 主流补齐（2026-11-11）：项目记录是 `{id, name, homes: [...]}`——
 * 家是挂在项目上的列表，不是项目只认一个家（通用规范 §4.7 裁决：
 * 主流做法 + 一个家不够用，本机 + 远程 + GitHub 并存）。一个项目
 * 可以有多个家（设备文件夹、GitHub 仓库、本机文件夹各一），其中
 * 一个是当前家（active home，浏览时用它；切换当前家不丢任务与
 * 对话——任务与对话按 projectId / taskId / sessionId 存，与家无关）。
 * 仍守的规矩：一个文件夹只能是一个项目的家（不复制）；可移植身份
 * 是仓库地址 + 分支（Project.repoUrl + defaultBranch）。
 * （旧规则“一个项目只有一个家”已废止：它是老项目单家模型的收紧，
 * 主流是家的列表；文件夹占用检查仍在 [ProjectStore.saveLocation] 里。）
 *
 *
 * 访问只走该项目设备上的 Hand（list/read/write/run），绝不把项目
 * 拷到别处（老项目 `cross_device_hand.py`）。
 *
 * 本类只做存储与校验：校验由调用方在保存前做，本类保存时也再验
 * 一次，非法记录拒绝写入；文件夹占用检查也在保存时执行。
 * 密钥值一律不进这里，只存项目元数据。
 */

/** 项目来源类型（老项目 SOURCE_TYPES）。V5 里远端设备一律 remote_device，本机 local。 */
enum class ProjectSourceType(val wire: String) {
    LOCAL("local"),
    CLOUD("cloud"),
    LAN("lan"),
    REMOTE_DEVICE("remote_device");

    companion object {
        fun fromWire(wire: String?): ProjectSourceType? =
            entries.firstOrNull { it.wire == wire }
    }
}

/** 文件实际在哪（老项目 PROJECT_ORIGINS）。remote_device 的位置 origin 一律 remote。 */
enum class ProjectOrigin(val wire: String) {
    LOCAL("local"),
    LAN("lan"),
    CLOUD("cloud"),
    REMOTE("remote");

    companion object {
        fun fromWire(wire: String?): ProjectOrigin? =
            entries.firstOrNull { it.wire == wire }

        /** 由来源类型推导 origin（老项目 bootstrap_host 的做法）。 */
        fun forSourceType(sourceType: ProjectSourceType): ProjectOrigin = when (sourceType) {
            ProjectSourceType.LOCAL -> LOCAL
            ProjectSourceType.LAN -> LAN
            ProjectSourceType.CLOUD -> CLOUD
            ProjectSourceType.REMOTE_DEVICE -> REMOTE
        }
    }
}

/** 位置可用态（老项目 location availability）。 */
enum class LocationAvailability(val wire: String) {
    AVAILABLE("available"),
    UNAVAILABLE("unavailable");

    companion object {
        fun fromWire(wire: String?): LocationAvailability =
            entries.firstOrNull { it.wire == wire } ?: AVAILABLE
    }
}

/**
 * 家的种类（maintainer 2026-11-11 05:32：家可以是 GitHub）。
 * - DEVICE：老项目模型，代码在家设备的文件夹上，Hand 就地读写/运行。
 * - GITHUB / GITLAB / GITEE：git 家，代码的家就是托管站上的仓库本身
 *   （Mac 等设备上的检出只是本地副本，不是家）；Agent 经该站 API
 *   读/改/推分支/开 PR（GitLab 叫合并请求），不克隆、不把项目文件
 *   拷到任何设备上。GitLab 的命名空间路径允许多段（子群组）。
 * - LOCAL：本机文件夹家，代码在本机工作区（filesDir/workspace）下的
 *   相对文件夹里；App 就地浏览/读写，不走 Hand、不复制到别处。
 * 家并存，一个项目可以有多个家（主流模型：家的列表，其中一个是当前家）。
 */
enum class ProjectHomeKind(val wire: String) {
    DEVICE("device"),
    GITHUB("github"),
    GITLAB("gitlab"),
    GITEE("gitee"),
    LOCAL("local");

    companion object {
        fun fromWire(wire: String?): ProjectHomeKind =
            entries.firstOrNull { it.wire == wire } ?: DEVICE
    }
}

/**
 * Project：逻辑身份，与任何 Host 检出无关。
 * （老项目 `docs/PROJECT_HOST_BINDING_V1.md` 的四个元数据字段。）
 */
data class Project(
    /** 项目 id：ASCII slug，由项目名生成（见 [slugProjectId]）。 */
    val projectId: String,
    /** 显示名（可含中文，老项目 catalog 要求非空、≤120 字）。 */
    val name: String,
    /** 可选 canonical git 来源（无凭据的 https 链接；GitHub / GitLab /
     * Gitee 或 Hugging Face Space；注册只建元数据）。模型/数据集仓库
     * 是权重/数据，不能当作项目。 */
    val repoUrl: String? = null,
    /** 默认分支（默认 main；GitHub 项目缺省时解析为 main）。 */
    val defaultBranch: String = "main",
    /** canonical commit：40/64 位小写 hex，或 null（未验证）。App 不验证，不编造。 */
    val canonicalCommit: String? = null,
    val origin: ProjectOrigin = ProjectOrigin.REMOTE,
    val createdAtEpochSeconds: Long = 0L,
)

/**
 * 项目记录（主流模型）：逻辑身份 + 家的列表。
 * 对应通用规范 §4.3：项目记录是 `{id, name, homes: [...]}`，
 * 可移植身份是仓库地址 + 分支（[Project.repoUrl] + [Project.defaultBranch]）。
 * [activeHome] 是当前家（浏览时用它）；没有家时为 null。
 * 切换当前家不丢任务与对话（任务与对话按 projectId / taskId / sessionId 存）。
 */
data class ProjectWithHomes(
    val project: Project,
    val homes: List<ProjectLocation>,
    val activeHome: ProjectLocation?,
)

/**
 * 当前家的 git 家判定（2026-11-12，派活按当前家路由）。
 *
 * 只有当前家是 git 家（GitHub / GitLab / Gitee）时才返回
 * （项目，当前家）；当前家是设备家 / 本机家、或项目没有家时
 * 返回 null。调用方（派活分流、脑轮次 git 家上下文）凭它决定
 * 是否走大脑 git API 路——不再只看“项目有没有 git 家”
 * （旧口径取家的列表第一项：项目有 GitHub 家时 git 家分支
 * 就触发，不管当前家是不是 DEVICE 家；2026-11-12 09:46
 * 实证：当前家是 Mac DEVICE 家，任务仍走了大脑路，大脑在
 * 手机本机没有 GitHub 扩展工具、没有 git 可用，没做成却记
 * completed）。
 */
fun activeGitHome(projectWithHomes: ProjectWithHomes): Pair<Project, ProjectLocation>? {
    val active = projectWithHomes.activeHome ?: return null
    if (active.gitHost() == null) return null
    return projectWithHomes.project to active
}
/**
 * ProjectLocation：项目在家（设备 + 文件夹）。
 * 引用已注册的 Project；位置 id 由 (project_id, host_id, path) 派生，
 * 同一三元组永远是同一位置（老项目 `_location_id`）。
 */
data class ProjectLocation(
    /** 位置 id：老项目 `_location_id` 形状（见 [projectLocationId]）。 */
    val locationId: String,
    val projectId: String,
    /** 家设备 id（[wherevercode.agent.core.transport.RemoteDevice.deviceId]）。
     * git 家时为站点的 wire（"github" / "gitlab" / "gitee"，不是设备）。 */
    val hostId: String,
    /** 家文件夹：设备上的绝对路径（已规整，去掉末尾 "/"）。
     * git 家时为命名空间路径（GitHub/Gitee 是 "owner/repo"，GitLab
     * 允许多段 "group/subgroup/project"，仓库本身就是家）。 */
    val workspacePath: String,
    val sourceType: ProjectSourceType = ProjectSourceType.REMOTE_DEVICE,
    val origin: ProjectOrigin = ProjectOrigin.REMOTE,
    val availability: LocationAvailability = LocationAvailability.AVAILABLE,
    val lastSeenEpochSeconds: Long = 0L,
    /** 家的种类（DEVICE = 设备文件夹，GITHUB/GITLAB/GITEE = git 家，LOCAL = 本机文件夹）。 */
    val homeKind: ProjectHomeKind = ProjectHomeKind.DEVICE,
) {
    companion object {
        /** GitHub 家的 hostId 哨兵：不是设备 id，只是表明家在 GitHub。 */
        const val GITHUB_HOME_HOST_ID: String = "github"

        /** GitLab 家的 hostId 哨兵：不是设备 id，只是表明家在 GitLab。 */
        const val GITLAB_HOME_HOST_ID: String = "gitlab"

        /** Gitee 家的 hostId 哨兵：不是设备 id，只是表明家在 Gitee。 */
        const val GITEE_HOME_HOST_ID: String = "gitee"

        /** 本机家的 hostId 哨兵：不是设备 id，只是表明家在本机工作区。 */
        const val LOCAL_HOME_HOST_ID: String = "local"

        /** 建一个位置：id 由三元组派生，origin 由来源类型推导。 */
        fun create(
            projectId: String,
            hostId: String,
            workspacePath: String,
            sourceType: ProjectSourceType = ProjectSourceType.REMOTE_DEVICE,
        ): ProjectLocation = ProjectLocation(
            locationId = projectLocationId(projectId, hostId, workspacePath),
            projectId = projectId,
            hostId = hostId,
            workspacePath = normalizeFolderPath(workspacePath),
            sourceType = sourceType,
            origin = ProjectOrigin.forSourceType(sourceType),
        )

        /** 建一个 git 家位置：家就是托管站上的仓库本身。 */
        fun createGitHost(
            projectId: String,
            host: GitHost,
            namespacePath: String,
        ): ProjectLocation {
            return ProjectLocation(
                locationId = projectLocationId(projectId, host.id.wire, namespacePath),
                projectId = projectId,
                hostId = host.id.wire,
                workspacePath = namespacePath,
                sourceType = ProjectSourceType.CLOUD,
                origin = ProjectOrigin.CLOUD,
                homeKind = when (host.id) {
                    GitHostId.GITHUB -> ProjectHomeKind.GITHUB
                    GitHostId.GITLAB -> ProjectHomeKind.GITLAB
                    GitHostId.GITEE -> ProjectHomeKind.GITEE
                },
            )
        }

        /** 建一个 GitHub 家位置：家就是 GitHub 上的仓库本身。 */
        fun createGithub(
            projectId: String,
            owner: String,
            repo: String,
        ): ProjectLocation = createGitHost(projectId, GitHost.GITHUB, "$owner/$repo")

        /** 建一个本机家位置：家是本机工作区下的相对文件夹。 */
        fun createLocal(
            projectId: String,
            relativePath: String,
        ): ProjectLocation {
            val workspacePath = normalizeFolderPath(relativePath)
            return ProjectLocation(
                locationId = projectLocationId(projectId, LOCAL_HOME_HOST_ID, workspacePath),
                projectId = projectId,
                hostId = LOCAL_HOME_HOST_ID,
                workspacePath = workspacePath,
                sourceType = ProjectSourceType.LOCAL,
                origin = ProjectOrigin.LOCAL,
                homeKind = ProjectHomeKind.LOCAL,
            )
        }
    }

    /** git 家的托管站点（GitHub / GitLab / Gitee）；设备家/本地家返回 null。 */
    fun gitHost(): GitHost? {
        if (homeKind != ProjectHomeKind.GITHUB &&
            homeKind != ProjectHomeKind.GITLAB &&
            homeKind != ProjectHomeKind.GITEE
        ) {
            return null
        }
        return GitHost.fromWire(hostId)?.takeIf { it.id.wire == homeKind.wire }
    }

    /** git 家的命名空间路径（"owner/repo"，GitLab 允许多段）；非 git 家返回 null。 */
    fun gitRepo(): String? =
        if (gitHost() != null) workspacePath else null

    /** GitHub 家的仓库坐标 "owner/repo"；设备家返回 null。 */
    fun githubRepo(): String? =
        if (homeKind == ProjectHomeKind.GITHUB) workspacePath else null

    /** 本机家的相对文件夹；非本机家返回 null。 */
    fun localFolder(): String? =
        if (homeKind == ProjectHomeKind.LOCAL) workspacePath else null
}

/** 老项目 `_location_id` 的 Kotlin 版：材料以 NUL 分隔，路径用 posix 形。 */
fun projectLocationId(projectId: String, hostId: String, workspacePath: String): String {
    val material = "$projectId\u0000$hostId\u0000${normalizeFolderPath(workspacePath)}"
        .toByteArray(Charsets.UTF_8)
    val digest = MessageDigest.getInstance("SHA-256").digest(material)
    return "loc-" + digest.joinToString("") { "%02x".format(it) }.take(32)
}

/** 文件夹规整：去首尾空白、去掉末尾 "/"（根 "/" 本身保留）。 */
fun normalizeFolderPath(folder: String): String {
    val trimmed = folder.trim()
    if (trimmed == "/") return "/"
    return trimmed.trimEnd('/')
}

/**
 * 本机家文件夹是否合法：须是本机工作区下的相对路径（不以 / 开头，
 * 不含 ..、.、空段、反斜杠与 NUL），如 "my-project" 或 "a/b"。
 * 绝对路径与跳出工作区的写法一律拒绝。
 */
fun isValidLocalWorkspacePath(path: String): Boolean {
    if (path.isBlank()) return false
    if (path.startsWith("/")) return false
    if ('\\' in path || '\u0000' in path) return false
    val parts = path.split("/")
    if (parts.any { it.isBlank() || it == "." || it == ".." }) return false
    return true
}

/** 项目名 → ASCII slug（与加 Agent 的 slugAgentId 同一做法）：中文名回退 "project"。 */
fun slugProjectId(name: String): String {    val slug = name.lowercase()
        .map { ch -> if (ch.isLetterOrDigit() && ch.code < 128) ch else '-' }
        .joinToString("")
        .replace(Regex("-+"), "-")
        .trim('-')
    return slug.ifEmpty { "project" }
}

/**
 * 与仓库同名的文件夹候选（纯函数，单测覆盖）：
 * 粘 git 仓库地址后找设备上已有的检出，只认“文件夹名 == 仓库名”，
 * 不猜、不拉代码。是不是该仓库的检出，调用方再核（本机看 .git，
 * 远程设备由用户在确认单上认）。
 */
fun checkoutCandidates(repoName: String, folders: List<String>): List<String> =
    folders.filter { folderBasename(it) == repoName }

/** 老项目 `_valid_git_branch` 的 Kotlin 版（空串 = detached HEAD，同样合法）。 */
fun isValidGitBranch(branch: String): Boolean {
    if (branch.length > 256) return false
    if (branch.isEmpty()) return true
    if (branch == "@" || branch.startsWith("/") || branch.endsWith("/") || branch.endsWith(".")) {
        return false
    }
    if (branch.any { it.code < 32 || it.code == 127 || it in " ~^:?*[\\" }) return false
    if (".." in branch || "@{" in branch || "//" in branch) return false
    for (component in branch.split("/")) {
        if (component.startsWith(".") || component.endsWith(".lock")) return false
    }
    return true
}

object ProjectValidation {
    /**
     * 老项目 `_PROJECT_ID`：小写字母/数字开头，后跟小写字母、数字、-，
     * 最长 64 字；**没有下划线**（与加设备时服务端 id 的放宽不同，
     * 项目 id 是 App 自己生成的 slug，只走这一套）。
     */
    private val PROJECT_ID_REGEX = Regex("^[a-z0-9][a-z0-9-]{0,63}$")
    /**
     * git 家的 workspacePath 形状：命名空间路径，每段只含字母数字 _ . -。
     * GitHub/Gitee 恰好两段（owner/repo）；GitLab 允许多段（子群组）。
     */
    private val GIT_NAMESPACE_PATH_REGEX =
        Regex("^[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)+$")
    private val COMMIT_REGEX = Regex("^[0-9a-f]{40}$|^[0-9a-f]{64}$")

    fun validateProject(project: Project): List<String> {
        val errors = mutableListOf<String>()
        if (project.name.isBlank()) errors += "项目名不能为空"
        if (project.name.length > 120) errors += "项目名不能超过 120 字"
        if (!PROJECT_ID_REGEX.matches(project.projectId)) {
            errors += "项目 id（${project.projectId}）不符合要求：只允许小写字母、数字、-，最长 64 字"
        }
        val repo = project.repoUrl?.trim().orEmpty()
        if (repo.isNotEmpty()) {
            // 仓库地址：GitHub / GitLab / Gitee 是可当作 git 家的地址；
            // Hugging Face Space 是可记为项目来源的代码仓库（要当作家须
            // 先在某台设备上检出）；模型/数据集仓库是权重/数据，不能当作项目。
            if (parseGitHostRepoUrl(repo) == null) {
                when (val parsed = parseGitRepoUrl(repo)) {
                    null -> errors += "仓库地址须为无凭据的 GitHub / GitLab / Gitee https 链接，如 https://github.com/owner/repo"
                    else -> when (parsed.kind) {
                        GitRepoKind.HF_SPACE -> Unit // Space 地址可记为来源，不报错
                        GitRepoKind.HF_MODEL -> errors += "这是 Hugging Face 模型仓库（权重），不能当作项目；要当作家请用 Space 代码仓库"
                        GitRepoKind.HF_DATASET -> errors += "这是 Hugging Face 数据集仓库，不能当作项目；要当作家请用 Space 代码仓库"
                        else -> errors += "仓库地址须为无凭据的 GitHub / GitLab / Gitee https 链接，如 https://github.com/owner/repo"
                    }
                }
            }
        }
        if (project.defaultBranch.isBlank()) {
            errors += "默认分支不能为空"
        } else if (!isValidGitBranch(project.defaultBranch)) {
            errors += "默认分支（${project.defaultBranch}）不是合法的 Git 分支名"
        }
        val commit = project.canonicalCommit?.trim().orEmpty()
        if (commit.isNotEmpty() && !COMMIT_REGEX.matches(commit)) {
            errors += "canonical commit 须为 40 或 64 位小写 hex，或留空（未验证）"
        }
        return errors
    }

    fun validateLocation(location: ProjectLocation): List<String> {
        val errors = mutableListOf<String>()
        if (!PROJECT_ID_REGEX.matches(location.projectId)) {
            errors += "项目 id（${location.projectId}）不符合要求：只允许小写字母、数字、-，最长 64 字"
        }
        if (location.homeKind == ProjectHomeKind.GITHUB ||
            location.homeKind == ProjectHomeKind.GITLAB ||
            location.homeKind == ProjectHomeKind.GITEE
        ) {
            // git 家：家就是托管站上的仓库本身。
            val host = location.gitHost()
            if (host == null) {
                errors += "git 家的 hostId 须为 github / gitlab / gitee"
            } else {
                val segments = location.workspacePath.split("/")
                val validSegments =
                    GIT_NAMESPACE_PATH_REGEX.matches(location.workspacePath) &&
                        segments.none { it == "." || it == ".." }
                val validCount =
                    if (host.id == GitHostId.GITLAB) segments.size >= 2 else segments.size == 2
                if (!validSegments || !validCount) {
                    errors += "${host.displayName} 家的路径须为命名空间/项目，如 group/subgroup/project"
                }
            }
            if (location.sourceType != ProjectSourceType.CLOUD) {
                errors += "git 家的来源类型须为 cloud"
            }
        } else if (location.homeKind == ProjectHomeKind.LOCAL) {
            // 本机家：家是本机工作区下的相对文件夹，不走设备、不走网络。
            if (location.hostId != ProjectLocation.LOCAL_HOME_HOST_ID) {
                errors += "本机家的 hostId 须为 local"
            }
            if (!isValidLocalWorkspacePath(location.workspacePath)) {
                errors += "本机家文件夹须为本机工作区下的相对路径，如 my-project，不能以 / 开头、不能含 .."
            }
            if (location.sourceType != ProjectSourceType.LOCAL) {
                errors += "本机家的来源类型须为 local"
            }
        } else {
            if (location.hostId.isBlank()) errors += "家设备不能为空"
            if (location.workspacePath.isBlank()) {
                errors += "家文件夹不能为空"
            } else if (!location.workspacePath.startsWith("/")) {
                errors += "家文件夹必须是设备上的绝对路径（以 / 开头），如 /workspace/example-project"
            }
        }
        if (location.locationId != projectLocationId(
                location.projectId,
                location.hostId,
                location.workspacePath,
            )
        ) {
            errors += "位置 id 与（项目、设备、文件夹）不一致：位置 id 由三元组派生，不能手写"
        }
        if (location.origin != ProjectOrigin.forSourceType(location.sourceType)) {
            errors += "origin 须由来源类型推导"
        }
        return errors
    }
}

interface ProjectStore {
    // -- Project（逻辑身份） --
    fun listProjects(): List<Project>
    fun getProject(projectId: String): Project?
    /**
     * 注册/更新逻辑项目（只建元数据，不产生位置；老项目“creating with
     * repo_url creates metadata only”）。校验失败抛 [IllegalArgumentException]。
     */
    fun saveProject(project: Project)

    /**
     * 删除项目 + 它的全部位置（老项目 `remove_project_locations`）。
     * 只删 App 里的记录，不删设备上的文件夹。
     */
    fun deleteProject(projectId: String)

    // -- ProjectLocation（位置） --
    fun listLocations(): List<ProjectLocation>
    fun getLocation(locationId: String): ProjectLocation?
    fun locationsForProject(projectId: String): List<ProjectLocation>
    fun locationsForHost(hostId: String): List<ProjectLocation>

    /**
     * 项目记录（主流模型）：逻辑身份 + 家的列表 + 当前家。
     * [ProjectWithHomes.homes] 是该项目的全部家；[ProjectWithHomes.activeHome]
     * 是当前家（浏览时用它；未设过时取第一个家；没有家时为 null）。
     * 项目不存在时返回 null。
     */
    fun projectWithHomes(projectId: String): ProjectWithHomes?

    /** 该项目的当前家（浏览时用它）；没有家时返回 null。 */
    fun getActiveHome(projectId: String): ProjectLocation?

    /**
     * 切换当前家（只改“当前是哪个家”，不删任何家、不动任务与对话）。
     * locationId 须属于该项目，否则抛 [IllegalArgumentException]。
     */
    fun setActiveHome(projectId: String, locationId: String)

    /**
     * 保存位置。一个项目可以有多个家（主流模型，见 [ProjectWithHomes]）；
     * 同一文件夹只能是一个项目的家（占用检查仍在，见 [homeOccupiedBy]）。
     * 校验失败或文件夹已被别的项目占用时抛 [IllegalArgumentException]（中文原因）。
     * 同 id = 更新可用态/最后出现时间；换当前家请用 [setActiveHome]，不用删位置。
     */
    fun saveLocation(location: ProjectLocation)

    /**
     * 只移除位置（项目本身保留，浏览器仍展示逻辑项目；
     * 老项目 `remove_location`）。不删设备上的文件夹。
     */
    fun removeLocation(locationId: String)

    /** 该 (hostId, workspacePath) 是否已被别的项目占为家（返回占用的位置）。 */
    fun homeOccupiedBy(hostId: String, workspacePath: String, exceptProjectId: String? = null): ProjectLocation?
}

class SharedPreferencesProjectStore(context: Context) : ProjectStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun listProjects(): List<Project> {
        val json = prefs.getString(KEY_PROJECTS, null) ?: return emptyList()
        val out = mutableListOf<Project>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                parseProject(array.optJSONObject(i))?.let { out += it }
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return out
    }

    override fun getProject(projectId: String): Project? =
        listProjects().firstOrNull { it.projectId == projectId }

    override fun saveProject(project: Project) {
        val errors = ProjectValidation.validateProject(project)
        require(errors.isEmpty()) { "项目非法：${errors.joinToString("；")}" }
        val now = System.currentTimeMillis() / 1000
        val existing = getProject(project.projectId)
        val stamped = project.copy(
            createdAtEpochSeconds = existing?.createdAtEpochSeconds
                ?.takeIf { it != 0L }
                ?: project.createdAtEpochSeconds.takeIf { it != 0L } ?: now,
        )
        val projects = listProjects().filter { it.projectId != project.projectId } + stamped
        persistProjects(projects)
    }

    override fun deleteProject(projectId: String) {
        persistProjects(listProjects().filter { it.projectId != projectId })
        persistLocations(listLocations().filter { it.projectId != projectId })
        val active = loadActiveHomes().toMutableMap()
        active.remove(projectId)
        persistActiveHomes(active)
    }

    override fun listLocations(): List<ProjectLocation> {
        val json = prefs.getString(KEY_LOCATIONS, null) ?: return emptyList()
        val out = mutableListOf<ProjectLocation>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                parseLocation(array.optJSONObject(i))?.let { out += it }
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return out
    }

    override fun getLocation(locationId: String): ProjectLocation? =
        listLocations().firstOrNull { it.locationId == locationId }

    override fun locationsForProject(projectId: String): List<ProjectLocation> =
        listLocations().filter { it.projectId == projectId }

    override fun locationsForHost(hostId: String): List<ProjectLocation> =
        listLocations().filter { it.hostId == hostId }

    override fun projectWithHomes(projectId: String): ProjectWithHomes? {
        val project = getProject(projectId) ?: return null
        val homes = locationsForProject(projectId)
        return ProjectWithHomes(project, homes, activeHomeOf(homes, projectId))
    }

    override fun getActiveHome(projectId: String): ProjectLocation? {
        val homes = locationsForProject(projectId)
        if (homes.isEmpty()) return null
        return activeHomeOf(homes, projectId)
    }

    override fun setActiveHome(projectId: String, locationId: String) {
        val homes = locationsForProject(projectId)
        require(homes.any { it.locationId == locationId }) {
            "该位置（$locationId）不是项目（$projectId）的家，无法设为当前家。"
        }
        val active = loadActiveHomes().toMutableMap()
        active[projectId] = locationId
        persistActiveHomes(active)
    }

    /** 当前家：已设过的取记录的；未设过的取第一个家；没有家时为 null。 */
    private fun activeHomeOf(homes: List<ProjectLocation>, projectId: String): ProjectLocation? {
        if (homes.isEmpty()) return null
        val activeId = loadActiveHomes()[projectId]
        return homes.firstOrNull { it.locationId == activeId } ?: homes.first()
    }

    private fun loadActiveHomes(): Map<String, String> {
        val json = prefs.getString(KEY_ACTIVE_HOMES, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(json)
            val out = LinkedHashMap<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val projectId = keys.next()
                val locationId = obj.optString(projectId, "")
                if (projectId.isNotBlank() && locationId.isNotBlank()) out[projectId] = locationId
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun persistActiveHomes(active: Map<String, String>) {
        val obj = JSONObject()
        for ((projectId, locationId) in active) {
            if (projectId.isNotBlank() && locationId.isNotBlank()) obj.put(projectId, locationId)
        }
        prefs.edit().putString(KEY_ACTIVE_HOMES, obj.toString()).apply()
    }

    override fun saveLocation(location: ProjectLocation) {
        val normalized = location.copy(workspacePath = normalizeFolderPath(location.workspacePath))
        // 位置 id 由三元组派生：规整路径后 id 不变才合法。
        val stamped = normalized.copy(
            locationId = projectLocationId(
                normalized.projectId,
                normalized.hostId,
                normalized.workspacePath,
            ),
        )
        val errors = ProjectValidation.validateLocation(stamped)
        require(errors.isEmpty()) { "位置非法：${errors.joinToString("；")}" }
        if (getProject(stamped.projectId) == null) {
            throw IllegalArgumentException(
                "位置引用的项目（${stamped.projectId}）尚未注册：请先注册逻辑项目，再指定它的家。",
            )
        }
        val existing = getLocation(stamped.locationId)
        if (existing == null) {
            // 多家允许（主流模型）：项目可以再加一个家，不再以 one-home 拒绝。
            // 仍守占用检查：同一文件夹只能是一个项目的家（不复制）。
            val occupier = homeOccupiedBy(
                stamped.hostId,
                stamped.workspacePath,
                exceptProjectId = stamped.projectId,
            )
            if (occupier != null) {
                throw IllegalArgumentException(
                    "该文件夹已是项目“${occupier.projectId}”的家" +
                        "（${occupier.hostId} · ${occupier.workspacePath}），" +
                        "一个项目文件夹只能属于一个项目，不会复制到别处。",
                )
            }
        }
        val now = System.currentTimeMillis() / 1000
        val saved = stamped.copy(
            lastSeenEpochSeconds = stamped.lastSeenEpochSeconds.takeIf { it != 0L } ?: now,
        )
        val locations = listLocations().filter { it.locationId != stamped.locationId } + saved
        persistLocations(locations)
    }

    override fun removeLocation(locationId: String) {
        persistLocations(listLocations().filter { it.locationId != locationId })
        // 被删的家若是某项目的当前家，清掉该项目的当前家记录
        // （下次取当前家时自动回落到第一个家）。
        val active = loadActiveHomes().toMutableMap()
        var changed = false
        for ((projectId, activeId) in active.toMap()) {
            if (activeId == locationId) {
                active.remove(projectId)
                changed = true
            }
        }
        if (changed) persistActiveHomes(active)
    }

    override fun homeOccupiedBy(
        hostId: String,
        workspacePath: String,
        exceptProjectId: String?,
    ): ProjectLocation? {
        val folder = normalizeFolderPath(workspacePath)
        return listLocations().firstOrNull {
            it.projectId != exceptProjectId &&
                it.hostId == hostId &&
                normalizeFolderPath(it.workspacePath) == folder
        }
    }

    private fun persistProjects(projects: List<Project>) {
        val array = JSONArray()
        for (project in projects) {
            val obj = JSONObject()
            obj.put("projectId", project.projectId)
            obj.put("name", project.name)
            obj.put("repoUrl", project.repoUrl)
            obj.put("defaultBranch", project.defaultBranch)
            obj.put("canonicalCommit", project.canonicalCommit)
            obj.put("origin", project.origin.wire)
            obj.put("createdAtEpochSeconds", project.createdAtEpochSeconds)
            array.put(obj)
        }
        prefs.edit().putString(KEY_PROJECTS, array.toString()).apply()
    }

    private fun persistLocations(locations: List<ProjectLocation>) {
        val array = JSONArray()
        for (location in locations) {
            val obj = JSONObject()
            obj.put("locationId", location.locationId)
            obj.put("projectId", location.projectId)
            obj.put("hostId", location.hostId)
            obj.put("workspacePath", location.workspacePath)
            obj.put("sourceType", location.sourceType.wire)
            obj.put("origin", location.origin.wire)
            obj.put("availability", location.availability.wire)
            obj.put("homeKind", location.homeKind.wire)
            obj.put("lastSeenEpochSeconds", location.lastSeenEpochSeconds)
            array.put(obj)
        }
        prefs.edit().putString(KEY_LOCATIONS, array.toString()).apply()
    }

    private fun parseProject(obj: JSONObject?): Project? {
        if (obj == null) return null
        return try {
            Project(
                projectId = obj.optString("projectId", ""),
                name = obj.optString("name", ""),
                repoUrl = if (obj.isNull("repoUrl")) null else obj.optString("repoUrl", ""),
                defaultBranch = obj.optString("defaultBranch", "main"),
                canonicalCommit = if (obj.isNull("canonicalCommit")) null
                else obj.optString("canonicalCommit", ""),
                origin = ProjectOrigin.fromWire(obj.optString("origin", "remote"))
                    ?: ProjectOrigin.REMOTE,
                createdAtEpochSeconds = obj.optLong("createdAtEpochSeconds", 0),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseLocation(obj: JSONObject?): ProjectLocation? {
        if (obj == null) return null
        return try {
            val sourceType = ProjectSourceType.fromWire(obj.optString("sourceType", "remote_device"))
                ?: ProjectSourceType.REMOTE_DEVICE
            ProjectLocation(
                locationId = obj.optString("locationId", ""),
                projectId = obj.optString("projectId", ""),
                hostId = obj.optString("hostId", ""),
                workspacePath = obj.optString("workspacePath", ""),
                sourceType = sourceType,
                origin = ProjectOrigin.fromWire(obj.optString("origin", ""))
                    ?: ProjectOrigin.forSourceType(sourceType),
                availability = LocationAvailability.fromWire(obj.optString("availability", "available")),
                lastSeenEpochSeconds = obj.optLong("lastSeenEpochSeconds", 0),
                // 老数据没有 homeKind 字段，缺省为 DEVICE（设备文件夹家）。
                homeKind = ProjectHomeKind.fromWire(obj.optString("homeKind", "device")),
            )
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val PREFS_NAME: String = "wherevercode_projects"
        const val KEY_PROJECTS: String = "projects_json"
        const val KEY_LOCATIONS: String = "locations_json"

        /**
         * 当前家映射（projectId → locationId 的 JSON 对象）。
         * 未设过当前家的项目取家的列表里的第一个；没有家时为 null。
         */
        const val KEY_ACTIVE_HOMES: String = "active_homes_json"
    }
}

/** 内存实现（单测用；与 SharedPreferences 版同一接口与同一校验）。 */
class InMemoryProjectStore : ProjectStore {
    private val lock = Any()
    private val projects = LinkedHashMap<String, Project>()
    private val locations = LinkedHashMap<String, ProjectLocation>()
    /** 当前家映射（projectId → locationId）。 */
    private val activeHomes = LinkedHashMap<String, String>()

    override fun listProjects(): List<Project> = synchronized(lock) { projects.values.toList() }

    override fun getProject(projectId: String): Project? = synchronized(lock) { projects[projectId] }

    override fun saveProject(project: Project) {
        val errors = ProjectValidation.validateProject(project)
        require(errors.isEmpty()) { "项目非法：${errors.joinToString("；")}" }
        synchronized(lock) {
            val now = System.currentTimeMillis() / 1000
            val existing = projects[project.projectId]
            projects[project.projectId] = project.copy(
                createdAtEpochSeconds = existing?.createdAtEpochSeconds
                    ?.takeIf { it != 0L }
                    ?: project.createdAtEpochSeconds.takeIf { it != 0L } ?: now,
            )
        }
    }

    override fun deleteProject(projectId: String) {
        synchronized(lock) {
            projects.remove(projectId)
            locations.entries.removeIf { it.value.projectId == projectId }
            activeHomes.remove(projectId)
        }
    }

    override fun listLocations(): List<ProjectLocation> =
        synchronized(lock) { locations.values.toList() }

    override fun getLocation(locationId: String): ProjectLocation? =
        synchronized(lock) { locations[locationId] }

    override fun locationsForProject(projectId: String): List<ProjectLocation> =
        synchronized(lock) { locations.values.filter { it.projectId == projectId } }

    override fun locationsForHost(hostId: String): List<ProjectLocation> =
        synchronized(lock) { locations.values.filter { it.hostId == hostId } }

    override fun projectWithHomes(projectId: String): ProjectWithHomes? = synchronized(lock) {
        val project = projects[projectId] ?: return@synchronized null
        val homes = locations.values.filter { it.projectId == projectId }
        ProjectWithHomes(project, homes, activeHomeOfLocked(homes, projectId))
    }

    override fun getActiveHome(projectId: String): ProjectLocation? = synchronized(lock) {
        val homes = locations.values.filter { it.projectId == projectId }
        if (homes.isEmpty()) null else activeHomeOfLocked(homes, projectId)
    }

    override fun setActiveHome(projectId: String, locationId: String) {
        synchronized(lock) {
            require(locations.values.any { it.projectId == projectId && it.locationId == locationId }) {
                "该位置（$locationId）不是项目（$projectId）的家，无法设为当前家。"
            }
            activeHomes[projectId] = locationId
        }
    }

    /** 当前家：已设过的取记录的；未设过的取第一个家；没有家时为 null（调用方须持锁）。 */
    private fun activeHomeOfLocked(
        homes: List<ProjectLocation>,
        projectId: String,
    ): ProjectLocation? {
        if (homes.isEmpty()) return null
        val activeId = activeHomes[projectId]
        return homes.firstOrNull { it.locationId == activeId } ?: homes.first()
    }

    override fun saveLocation(location: ProjectLocation) {
        val normalized = location.copy(workspacePath = normalizeFolderPath(location.workspacePath))
        val stamped = normalized.copy(
            locationId = projectLocationId(
                normalized.projectId,
                normalized.hostId,
                normalized.workspacePath,
            ),
        )
        val errors = ProjectValidation.validateLocation(stamped)
        require(errors.isEmpty()) { "位置非法：${errors.joinToString("；")}" }
        synchronized(lock) {
            if (!projects.containsKey(stamped.projectId)) {
                throw IllegalArgumentException(
                    "位置引用的项目（${stamped.projectId}）尚未注册：请先注册逻辑项目，再指定它的家。",
                )
            }
            if (!locations.containsKey(stamped.locationId)) {
                // 多家允许（主流模型）：项目可以再加一个家，不再以 one-home 拒绝。
                // 仍守占用检查：同一文件夹只能是一个项目的家（不复制）。
                val occupier = locations.values.firstOrNull {
                    it.projectId != stamped.projectId &&
                        it.hostId == stamped.hostId &&
                        normalizeFolderPath(it.workspacePath) == stamped.workspacePath
                }
                if (occupier != null) {
                    throw IllegalArgumentException(
                        "该文件夹已是项目“${occupier.projectId}”的家，一个项目文件夹只能属于一个项目。",
                    )
                }
            }
            val now = System.currentTimeMillis() / 1000
            locations[stamped.locationId] = stamped.copy(
                lastSeenEpochSeconds = stamped.lastSeenEpochSeconds.takeIf { it != 0L } ?: now,
            )
        }
    }

    override fun removeLocation(locationId: String) {
        synchronized(lock) {
            locations.remove(locationId)
            // 被删的家若是某项目的当前家，清掉该项目的当前家记录
            // （下次取当前家时自动回落到第一个家）。
            val it = activeHomes.entries.iterator()
            while (it.hasNext()) {
                if (it.next().value == locationId) it.remove()
            }
        }
    }

    override fun homeOccupiedBy(
        hostId: String,
        workspacePath: String,
        exceptProjectId: String?,
    ): ProjectLocation? = synchronized(lock) {
        val folder = normalizeFolderPath(workspacePath)
        locations.values.firstOrNull {
            it.projectId != exceptProjectId &&
                it.hostId == hostId &&
                normalizeFolderPath(it.workspacePath) == folder
        }
    }
}
