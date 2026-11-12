package wherevercode.agent.core.project

/**
 * git 家的 Agent 调用入口：家是托管站上的仓库，Agent 经它干活。
 *
 * 这是聊天/任务分派调 git 家的唯一口：调用方只认接口，不认
 * [GitHostApiClient]。token 从本机 [GitHostCredentialStore] 取，
 * 每个 host 各自一条；没配就如实失败（不编造、不绕过、不向 Muse 要）。
 *
 * 读/写/推分支/开 PR（GitLab 叫合并请求）全走该站 API，不 git clone、
 * 不把项目文件拷到任何设备上。脑在 A、手在 B：大脑（A）发指令，
 * 本口是手（B）在托管站上执行的落点。
 *
 * 一个端口管三个站：[GitHostApiClient] 按家位置的 host 各建一个
 * （GitHub / GitLab / Gitee），token 也按 host 取。
 */
class GitHostProjectPort(
    private val credentialStore: GitHostCredentialStore,
    private val clientFactory: (GitHost) -> GitHostApiClient = { GitHostApiClient(it) },
) {
    private val clients = mutableMapOf<GitHostId, GitHostApiClient>()

    private fun clientFor(host: GitHost): GitHostApiClient =
        clients.getOrPut(host.id) { clientFactory(host) }

    /** 从一个家位置解析出 git 家坐标：非 git 家返回 null。 */
    fun resolveRepo(location: ProjectLocation): GitRepoCoordinates? {
        val host = location.gitHost() ?: return null
        if (location.workspacePath.isBlank()) return null
        return GitRepoCoordinates(host, location.workspacePath)
    }

    /**
     * 从项目 + 家位置解析出 git 家坐标 + 分支。
     * branch 优先用项目的默认分支（注册时填的）；没有位置或不是
     * git 家时返回 null。
     */
    fun resolveRepoRef(project: Project, location: ProjectLocation): GitRepoRef? {
        val coordinates = resolveRepo(location) ?: return null
        return GitRepoRef(coordinates, project.defaultBranch.ifBlank { "main" })
    }

    /** 核实这个 git 家真实存在，并取回它的默认分支（只读 GET）。 */
    suspend fun verifyHome(project: Project, location: ProjectLocation): Result<GitRepo> {
        val ref = resolveRepoRef(project, location)
            ?: return Result.failure(
                IllegalStateException("该项目不是 git 家：请先把它的家设为 GitHub / GitLab / Gitee 仓库。"),
            )
        val token = tokenOrFailure(ref.coordinates.host)
            ?: return Result.failure(tokenMissing(ref.coordinates.host))
        return clientFor(ref.coordinates.host)
            .getRepo(token, ref.coordinates.namespacePath)
    }

    /** 列仓库根目录（浏览用）。 */
    suspend fun listRoot(
        project: Project,
        location: ProjectLocation,
    ): Result<List<GitContentEntry>> {
        val ref = resolveRepoRef(project, location)
            ?: return Result.failure(
                IllegalStateException("该项目不是 git 家。"),
            )
        val token = tokenOrFailure(ref.coordinates.host)
            ?: return Result.failure(tokenMissing(ref.coordinates.host))
        return clientFor(ref.coordinates.host)
            .listContents(token, ref.coordinates.namespacePath, "", ref.branch)
    }

    /** 列仓库里一个目录（path 为空 = 根目录）。ref 为空 = 项目默认分支。 */
    suspend fun listFiles(
        project: Project,
        location: ProjectLocation,
        path: String = "",
        ref: String? = null,
    ): Result<List<GitContentEntry>> {
        val repoRef = resolveRepoRef(project, location)
            ?: return Result.failure(IllegalStateException("该项目不是 git 家。"))
        val token = tokenOrFailure(repoRef.coordinates.host)
            ?: return Result.failure(tokenMissing(repoRef.coordinates.host))
        return clientFor(repoRef.coordinates.host).listContents(
            token,
            repoRef.coordinates.namespacePath,
            path,
            ref ?: repoRef.branch,
        )
    }

    /** 读仓库里的一个文件。ref 为空 = 项目默认分支。 */
    suspend fun readFile(
        project: Project,
        location: ProjectLocation,
        path: String,
        ref: String? = null,
    ): Result<GitFile> {
        val repoRef = resolveRepoRef(project, location)
            ?: return Result.failure(IllegalStateException("该项目不是 git 家。"))
        val token = tokenOrFailure(repoRef.coordinates.host)
            ?: return Result.failure(tokenMissing(repoRef.coordinates.host))
        return clientFor(repoRef.coordinates.host).getFile(
            token,
            repoRef.coordinates.namespacePath,
            path,
            ref ?: repoRef.branch,
        )
    }

    /**
     * 写仓库里的一个文件（建或改，提交信息必填）。
     * 已有文件先读回当前 sha 再改（GitHub/Gitee 的 PUT 要求；
     * GitLab 的 PUT 用 last_commit_id）；不存在则新建。
     * branch 为空 = 项目默认分支。
     */
    suspend fun writeFile(
        project: Project,
        location: ProjectLocation,
        path: String,
        content: String,
        message: String,
        branch: String? = null,
    ): Result<GitFileWrite> {
        val repoRef = resolveRepoRef(project, location)
            ?: return Result.failure(IllegalStateException("该项目不是 git 家。"))
        val token = tokenOrFailure(repoRef.coordinates.host)
            ?: return Result.failure(tokenMissing(repoRef.coordinates.host))
        val targetBranch = branch?.ifBlank { repoRef.branch } ?: repoRef.branch
        val client = clientFor(repoRef.coordinates.host)
        val existing = client.getFile(
            token,
            repoRef.coordinates.namespacePath,
            path,
            targetBranch,
        )
        val sha = if (existing.isSuccess) existing.getOrNull()?.sha else null
        return client.createOrUpdateFile(
            token = token,
            namespacePath = repoRef.coordinates.namespacePath,
            path = path,
            content = content,
            message = message,
            branch = targetBranch,
            sha = sha,
        )
    }

    /** 从项目默认分支的头 commit 建一个新分支（推分支的第一步）。 */
    suspend fun createBranch(
        project: Project,
        location: ProjectLocation,
        branch: String,
    ): Result<String> {
        val repoRef = resolveRepoRef(project, location)
            ?: return Result.failure(IllegalStateException("该项目不是 git 家。"))
        val token = tokenOrFailure(repoRef.coordinates.host)
            ?: return Result.failure(tokenMissing(repoRef.coordinates.host))
        val client = clientFor(repoRef.coordinates.host)
        val head = client.getBranchHead(
            token,
            repoRef.coordinates.namespacePath,
            repoRef.branch,
        ).getOrElse { return Result.failure(it) }
        return client.createBranch(token, repoRef.coordinates.namespacePath, branch, head)
    }

    /** 开一个 PR（GitHub/Gitee）或合并请求（GitLab）：head 分支 → 项目默认分支。 */
    suspend fun createPullRequest(
        project: Project,
        location: ProjectLocation,
        head: String,
        title: String,
        body: String,
    ): Result<GitPullRequest> {
        val repoRef = resolveRepoRef(project, location)
            ?: return Result.failure(IllegalStateException("该项目不是 git 家。"))
        val token = tokenOrFailure(repoRef.coordinates.host)
            ?: return Result.failure(tokenMissing(repoRef.coordinates.host))
        return clientFor(repoRef.coordinates.host).createPullRequest(
            token = token,
            namespacePath = repoRef.coordinates.namespacePath,
            head = head,
            base = repoRef.branch,
            title = title,
            body = body,
        )
    }

    private fun tokenOrFailure(host: GitHost): String? = credentialStore.loadToken(host)

    private fun tokenMissing(host: GitHost): IllegalStateException =
        IllegalStateException(
            "未配置 ${host.displayName} 授权：请先在设置 → ${host.displayName} 授权填写你自己的 ${host.displayName} 授权（token），存在本机。",
        )
}

/** git 家的坐标：仓库 + 分支。 */
data class GitRepoRef(
    val coordinates: GitRepoCoordinates,
    val branch: String,
)
