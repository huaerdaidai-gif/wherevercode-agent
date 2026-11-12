package wherevercode.agent.brain

import java.io.BufferedReader
import java.io.File
import java.io.StringReader
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import wherevercode.agent.core.device.JvmDeviceAdapter
import wherevercode.agent.core.project.GitHost
import wherevercode.agent.core.project.GitHostApiClient
import wherevercode.agent.core.project.GitHostConnection
import wherevercode.agent.core.project.GitHostCredentialStore
import wherevercode.agent.core.project.GitHostProjectPort
import wherevercode.agent.core.project.GitHostTransport
import wherevercode.agent.core.project.Project
import wherevercode.agent.core.project.ProjectLocation
import wherevercode.agent.core.security.CommandLimits
import wherevercode.agent.core.security.InMemoryAuditLog
import wherevercode.agent.core.workspace.FileWorkspaceAdapter

/**
 * git 家（2026-11-11）：ToolRegistry 里的 git 家工具。
 * 真实 [GitHostApiClient] + 按 URL 路由的 fake transport + 内存 token 存储：
 * 不联网、不动真仓库。验证：工具只在 git 家时出现、读/写/推分支/
 * 开 PR（GitLab 叫合并请求）全走 Port、写类操作要审批、无家/无 token
 * 时如实拒绝；GitLab 子群组家与 Gitee 家同款走通。
 */
class GitHostToolsTest {

    private class FakeConnection(
        private val body: String,
        override val statusCode: Int,
    ) : GitHostConnection {
        override fun openBodyReader(): BufferedReader = BufferedReader(StringReader(body))
        override fun readErrorBody(): String? = body.takeIf { statusCode !in 200..299 }
        override fun close() = Unit
    }

    private class RoutingTransport(
        private val handler: (method: String, url: String, body: String?) -> Pair<Int, String>,
    ) : GitHostTransport {
        val calls = mutableListOf<Triple<String, String, String?>>()
        var lastHeaders: Map<String, String>? = null

        override fun get(url: String, headers: Map<String, String>): GitHostConnection {
            calls += Triple("GET", url, null)
            lastHeaders = headers
            val (status, body) = handler("GET", url, null)
            return FakeConnection(body, status)
        }

        override fun put(url: String, headers: Map<String, String>, requestBody: String): GitHostConnection {
            calls += Triple("PUT", url, requestBody)
            lastHeaders = headers
            val (status, body) = handler("PUT", url, requestBody)
            return FakeConnection(body, status)
        }

        override fun post(url: String, headers: Map<String, String>, requestBody: String): GitHostConnection {
            calls += Triple("POST", url, requestBody)
            lastHeaders = headers
            val (status, body) = handler("POST", url, requestBody)
            return FakeConnection(body, status)
        }
    }

    private class MemoryCredentialStore(token: String? = "test-token") : GitHostCredentialStore {
        private val tokens = mutableMapOf<GitHost, String?>().apply {
            GitHost.ALL.forEach { this[it] = token }
        }

        /** 快捷口径：读/写 GitHub 的 token；置空 = 三站 token 全空。 */
        var token: String?
            get() = tokens[GitHost.GITHUB]
            set(value) {
                GitHost.ALL.forEach { tokens[it] = value }
            }

        fun setToken(host: GitHost, value: String?) {
            tokens[host] = value
        }

        override fun saveToken(host: GitHost, token: String) {
            tokens[host] = token
        }

        override fun loadToken(host: GitHost): String? = tokens[host]

        override fun clearToken(host: GitHost) {
            tokens.remove(host)
        }
    }

    private object AllowGate : ApprovalGate {
        override suspend fun requestApproval(action: Action): Boolean = true
    }

    private lateinit var tmpDir: File
    private lateinit var workspaceRoot: File
    private lateinit var transport: RoutingTransport
    private lateinit var credentialStore: MemoryCredentialStore
    private lateinit var port: GitHostProjectPort

    private fun b64(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    private fun githubProject(branch: String = "main") = Project(
        projectId = "wherevercode-agent",
        name = "wherevercode-agent",
        repoUrl = "https://github.com/o/r",
        defaultBranch = branch,
    )

    private fun githubHome() = ProjectLocation.createGithub("wherevercode-agent", "o", "r")

    private fun gitlabProject(branch: String = "main") = Project(
        projectId = "my-proj",
        name = "my-proj",
        repoUrl = "https://gitlab.com/group/sub/my-proj",
        defaultBranch = branch,
    )

    private fun gitlabHome() =
        ProjectLocation.createGitHost("my-proj", GitHost.GITLAB, "group/sub/my-proj")

    private fun giteeProject(branch: String = "master") = Project(
        projectId = "gitee-proj",
        name = "gitee-proj",
        repoUrl = "https://gitee.com/o/r",
        defaultBranch = branch,
    )

    private fun giteeHome() =
        ProjectLocation.createGitHost("gitee-proj", GitHost.GITEE, "o/r")

    private fun gitlabContext() = context(project = gitlabProject(), location = gitlabHome())

    private fun deviceHome() =
        ProjectLocation.create("wherevercode-agent", "remote-device", "/workspace/example-project")

    @Before
    fun setUp() {
        tmpDir = Files.createTempDirectory("github-tools-test").toFile()
        workspaceRoot = File(tmpDir, "workspace").apply { mkdirs() }
        transport = RoutingTransport { method, url, body ->
            when {
                method == "GET" && url.contains("/contents/?ref=main") ->
                    200 to """[{"name":"README.md","path":"README.md","type":"file","sha":"aaa","size":10},{"name":"docs","path":"docs","type":"dir","sha":"bbb","size":0}]"""
                method == "GET" && url.contains("/contents/README.md?ref=main") ->
                    200 to """{"name":"README.md","path":"README.md","type":"file","sha":"aaa","size":10,"content":"${b64("hello github")}"}"""
                method == "GET" && url.contains("/contents/README.md?ref=fix/test") ->
                    404 to """{"message":"Not Found"}"""
                method == "GET" && url.contains("/git/ref/heads/main") ->
                    200 to """{"ref":"refs/heads/main","object":{"sha":"mainsha0000000000000000000000000000000001"}}"""
                method == "POST" && url.endsWith("/git/refs") ->
                    201 to """{"ref":"refs/heads/fix/test","object":{"sha":"mainsha0000000000000000000000000000000001"}}"""
                method == "PUT" && url.contains("/contents/README.md") ->
                    201 to """{"content":{"path":"README.md","sha":"newsha0000000000000000000000000000000002"},"commit":{"sha":"commitsha0000000000000000000000000000000003"}}"""
                method == "POST" && url.endsWith("/pulls") ->
                    201 to """{"number":7,"html_url":"https://github.com/o/r/pull/7","title":"Test PR"}"""
                method == "GET" && url.contains("/projects/group%2Fsub%2Fmy-proj/repository/tree") ->
                    200 to """[{"id":"aaa","name":"README.md","type":"blob","path":"README.md","mode":"100644"},{"id":"bbb","name":"docs","type":"tree","path":"docs","mode":"040000"}]"""
                method == "GET" && url.contains("/repository/files/README.md?ref=main") ->
                    200 to """{"file_name":"README.md","file_path":"README.md","size":10,"encoding":"base64","content":"${b64("hello gitlab")}","blob_id":"aaa","commit_id":"c1"}"""
                method == "GET" && url.contains("/repository/branches/main") ->
                    200 to """{"name":"main","commit":{"id":"mainsha0000000000000000000000000000000001"}}}"""
                method == "POST" && url.endsWith("/repository/branches") ->
                    201 to """{"name":"fix/test","commit":{"id":"mainsha0000000000000000000000000000000001"}}"""
                method == "PUT" && url.contains("/repository/files/README.md") ->
                    200 to """{"file_path":"README.md","branch":"fix/test"}"""
                method == "POST" && url.endsWith("/merge_requests") ->
                    201 to """{"iid":9,"web_url":"https://gitlab.com/group/sub/my-proj/-/merge_requests/9","title":"Test MR"}"""
                else -> 404 to """{"message":"Not Found"}"""
            }
        }
        credentialStore = MemoryCredentialStore()
        port = GitHostProjectPort(credentialStore) { host ->
            GitHostApiClient(host, transport)
        }
    }

    @After
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    private fun registry(gate: ApprovalGate = AllowGate, port: GitHostProjectPort? = this.port): ToolRegistry {
        val taskRoot = File(tmpDir, "task-root").apply { mkdirs() }
        val workspaceAdapter = FileWorkspaceAdapter(workspaceRoot, taskRoot)
        val deviceAdapter = JvmDeviceAdapter(listOf(workspaceRoot), workspaceAdapter)
        return ToolRegistry(
            deviceAdapter = deviceAdapter,
            workspaceAdapter = workspaceAdapter,
            limits = CommandLimits(),
            taskStore = FakeTaskStore(),
            auditLog = InMemoryAuditLog(),
            gitHostProjectPort = port,
        )
    }

    private fun context(gate: ApprovalGate = AllowGate, project: Project? = githubProject(), location: ProjectLocation? = githubHome()): ToolContext =
        ToolContext(
            taskId = "task-1",
            agentId = "brain",
            approvalGate = gate,
            workspaceRoot = workspaceRoot,
            deviceId = "local",
            folder = workspaceRoot.path,
            gitHostProject = project,
            gitHostLocation = location,
        )

    private fun action(tool: String, args: Map<String, Any?> = emptyMap()): Action =
        Action(actionId = "a1", tool = tool, args = args, idempotencyKey = "task-1:1")

    @Test
    fun `githubToolsFor returns five tools for a github home`() {
        val tools = registry().gitHostToolsFor(githubProject(), githubHome())
        assertEquals(ToolRegistry.GIT_HOST_TOOLS, tools.map { it.name })
    }

    @Test
    fun `gitHostToolsFor returns five tools for a gitlab subgroup home`() {
        val tools = registry().gitHostToolsFor(gitlabProject(), gitlabHome())
        assertEquals(ToolRegistry.GIT_HOST_TOOLS, tools.map { it.name })
    }

    @Test
    fun `gitHostToolsFor returns five tools for a gitee home`() {
        val tools = registry().gitHostToolsFor(giteeProject(), giteeHome())
        assertEquals(ToolRegistry.GIT_HOST_TOOLS, tools.map { it.name })
    }

    @Test
    fun `githubToolsFor empty without github home`() {
        val reg = registry()
        assertTrue(reg.gitHostToolsFor(null, null).isEmpty())
        assertTrue(reg.gitHostToolsFor(githubProject(), deviceHome()).isEmpty())
        assertTrue(reg.gitHostToolsFor(githubProject(), null).isEmpty())
        // Port 未接时也不提供。
        assertTrue(registry(port = null).gitHostToolsFor(githubProject(), githubHome()).isEmpty())
    }

    @Test
    fun `list_github_files lists repo root`() = runTest {
        val outcome = registry().execute(
            action("list_github_files", mapOf("path" to "")),
            context(),
        )
        assertTrue(outcome.ok)
        assertTrue(outcome.data!!.contains("README.md"))
        assertTrue(outcome.data!!.contains("[目录] docs/"))
        assertTrue(transport.calls.any { it.first == "GET" && it.second.contains("/contents/?ref=main") })
    }

    @Test
    fun `read_github_file returns decoded text`() = runTest {
        val outcome = registry().execute(
            action("read_github_file", mapOf("path" to "README.md")),
            context(),
        )
        assertTrue(outcome.ok)
        assertEquals("hello github", outcome.data)
    }

    @Test
    fun `write_github_file writes with approval`() = runTest {
        val outcome = registry().execute(
            action(
                "write_github_file",
                mapOf(
                    "path" to "README.md",
                    "content" to "new title",
                    "message" to "update readme",
                    "branch" to "fix/test",
                ),
            ),
            context(),
        )
        assertTrue(outcome.ok)
        assertTrue(outcome.summary.contains("commitsha00"))
        val put = transport.calls.first { it.first == "PUT" }
        assertTrue(put.second.contains("/contents/README.md"))
        assertTrue(put.third!!.contains("update readme"))
        assertTrue(put.third!!.contains("\"branch\":\"fix/test\""))
        assertTrue(put.third!!.contains(b64("new title")))
    }

    @Test
    fun `write_github_file denied without approval makes no call`() = runTest {
        val before = transport.calls.size
        val outcome = registry().execute(
            action(
                "write_github_file",
                mapOf("path" to "README.md", "content" to "x", "message" to "m"),
            ),
            context(gate = DenyApprovalGate),
        )
        assertFalse(outcome.ok)
        assertEquals("user_denied", outcome.errorCode)
        assertEquals(before, transport.calls.size)
    }

    @Test
    fun `create_github_branch creates from main head`() = runTest {
        val outcome = registry().execute(
            action("create_github_branch", mapOf("branch" to "fix/test")),
            context(),
        )
        assertTrue(outcome.ok)
        assertTrue(outcome.summary.contains("refs/heads/fix/test"))
        assertTrue(transport.calls.any { it.first == "GET" && it.second.contains("/git/ref/heads/main") })
        val post = transport.calls.first { it.first == "POST" && it.second.endsWith("/git/refs") }
        assertTrue(post.third!!.contains("refs/heads/fix/test"))
        assertTrue(post.third!!.contains("mainsha0000000000000000000000000000000001"))
    }

    @Test
    fun `create_github_branch denied without approval makes no call`() = runTest {
        val before = transport.calls.size
        val outcome = registry().execute(
            action("create_github_branch", mapOf("branch" to "fix/test")),
            context(gate = DenyApprovalGate),
        )
        assertFalse(outcome.ok)
        assertEquals("user_denied", outcome.errorCode)
        assertEquals(before, transport.calls.size)
    }

    @Test
    fun `create_github_pr opens PR`() = runTest {
        val outcome = registry().execute(
            action(
                "create_github_pr",
                mapOf("head" to "fix/test", "title" to "Test PR", "body" to "does things"),
            ),
            context(),
        )
        assertTrue(outcome.ok)
        assertTrue(outcome.summary.contains("#7"))
        assertEquals("https://github.com/o/r/pull/7", outcome.data)
        val post = transport.calls.first { it.first == "POST" && it.second.endsWith("/pulls") }
        assertTrue(post.third!!.contains("\"head\":\"fix/test\""))
        assertTrue(post.third!!.contains("\"base\":\"main\""))
    }

    @Test
    fun `github tool without github context fails honestly`() = runTest {
        val before = transport.calls.size
        val outcome = registry().execute(
            action("read_github_file", mapOf("path" to "README.md")),
            context(project = null, location = null),
        )
        assertFalse(outcome.ok)
        assertEquals("github_not_configured", outcome.errorCode)
        assertEquals(before, transport.calls.size)
    }

    @Test
    fun `write without token fails honestly and points to settings`() = runTest {
        credentialStore.token = null
        val outcome = registry().execute(
            action(
                "write_github_file",
                mapOf("path" to "README.md", "content" to "x", "message" to "m"),
            ),
            context(),
        )
        assertFalse(outcome.ok)
        assertTrue(outcome.summary.contains("设置"))
        assertTrue(outcome.summary.contains("GitHub 授权"))
    }

    @Test
    fun `read of missing file fails honestly`() = runTest {
        val outcome = registry().execute(
            action("read_github_file", mapOf("path" to "NOPE.md")),
            context(),
        )
        assertFalse(outcome.ok)
        assertEquals("github_failed", outcome.errorCode)
    }

    @Test
    fun `list_github_files lists gitlab subgroup root with PRIVATE-TOKEN`() = runTest {
        val outcome = registry().execute(
            action("list_github_files", mapOf("path" to "")),
            gitlabContext(),
        )
        assertTrue(outcome.ok)
        assertTrue(outcome.data!!.contains("README.md"))
        assertTrue(outcome.data!!.contains("[目录] docs/"))
        assertTrue(outcome.summary.contains("GitLab group/sub/my-proj@main"))
        assertEquals("test-token", transport.lastHeaders?.get("PRIVATE-TOKEN"))
        assertFalse(transport.lastHeaders?.containsKey("Authorization") == true)
    }

    @Test
    fun `read_github_file reads a gitlab file`() = runTest {
        val outcome = registry().execute(
            action("read_github_file", mapOf("path" to "README.md")),
            gitlabContext(),
        )
        assertTrue(outcome.ok)
        assertEquals("hello gitlab", outcome.data)
        assertTrue(outcome.summary.contains("GitLab group/sub/my-proj@main"))
    }

    @Test
    fun `create_github_pr opens a gitlab merge request`() = runTest {
        val outcome = registry().execute(
            action(
                "create_github_pr",
                mapOf("head" to "fix/test", "title" to "Test MR", "body" to "does things"),
            ),
            gitlabContext(),
        )
        assertTrue(outcome.ok)
        assertTrue(outcome.summary.contains("合并请求"))
        assertTrue(outcome.summary.contains("#9"))
        assertEquals("https://gitlab.com/group/sub/my-proj/-/merge_requests/9", outcome.data)
        val post = transport.calls.first { it.first == "POST" && it.second.endsWith("/merge_requests") }
        assertTrue(post.third!!.contains("\"source_branch\":\"fix/test\""))
        assertTrue(post.third!!.contains("\"target_branch\":\"main\""))
    }

    @Test
    fun `write without gitlab token fails honestly and points to settings`() = runTest {
        credentialStore.setToken(GitHost.GITLAB, null)
        val outcome = registry().execute(
            action(
                "write_github_file",
                mapOf("path" to "README.md", "content" to "x", "message" to "m"),
            ),
            gitlabContext(),
        )
        assertFalse(outcome.ok)
        assertTrue(outcome.summary.contains("设置"))
        assertTrue(outcome.summary.contains("GitLab 授权"))
    }
}
