package wherevercode.agent.brain

import java.io.BufferedReader
import java.io.File
import java.io.StringReader
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
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
 * git 家（2026-11-11）：BrainEngine 的 git 家接线。
 * 复用 BrainEngineTest 的 FakeAdapter / FakeApprovalGate / FakeTaskStore
 *（同一包的测试替身），fake transport 不联网。验证：聊天项目是 GitHub
 * 家时模型能看到 git 家工具并调通 read_github_file；不是 git 家时
 * 模型看不到 git 家工具；GitLab 子群组家同款走通。
 */
class GitHostEngineTest {

    private class FakeConnection(
        private val body: String,
        override val statusCode: Int,
    ) : GitHostConnection {
        override fun openBodyReader(): BufferedReader = BufferedReader(StringReader(body))
        override fun readErrorBody(): String? = null
        override fun close() = Unit
    }

    private class RoutingTransport(
        private val handler: (method: String, url: String, body: String?) -> Pair<Int, String>,
    ) : GitHostTransport {
        var lastHeaders: Map<String, String>? = null

        override fun get(url: String, headers: Map<String, String>): GitHostConnection {
            lastHeaders = headers
            val (status, body) = handler("GET", url, null)
            return FakeConnection(body, status)
        }

        override fun put(url: String, headers: Map<String, String>, requestBody: String): GitHostConnection {
            lastHeaders = headers
            val (status, body) = handler("PUT", url, requestBody)
            return FakeConnection(body, status)
        }

        override fun post(url: String, headers: Map<String, String>, requestBody: String): GitHostConnection {
            lastHeaders = headers
            val (status, body) = handler("POST", url, requestBody)
            return FakeConnection(body, status)
        }
    }

    private class MemoryCredentialStore(token: String? = "test-token") : GitHostCredentialStore {
        private val tokens = mutableMapOf<GitHost, String?>().apply {
            GitHost.ALL.forEach { this[it] = token }
        }

        override fun saveToken(host: GitHost, token: String) {
            tokens[host] = token
        }

        override fun loadToken(host: GitHost): String? = tokens[host]

        override fun clearToken(host: GitHost) {
            tokens.remove(host)
        }
    }

    private lateinit var tmpDir: File
    private lateinit var workspaceRoot: File
    private lateinit var conversations: ConversationStore
    private lateinit var tools: ToolRegistry
    private lateinit var eventLog: EventLog
    private val offeredToolNames = mutableListOf<List<String>>()

    private fun b64(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    private fun githubProject() = Project(
        projectId = "wherevercode-agent",
        name = "wherevercode-agent",
        repoUrl = "https://github.com/o/r",
        defaultBranch = "main",
    )

    private fun githubHome() = ProjectLocation.createGithub("wherevercode-agent", "o", "r")

    private fun gitlabProject() = Project(
        projectId = "my-proj",
        name = "my-proj",
        repoUrl = "https://gitlab.com/group/sub/my-proj",
        defaultBranch = "main",
    )

    private fun gitlabHome() =
        ProjectLocation.createGitHost("my-proj", GitHost.GITLAB, "group/sub/my-proj")

    @Before
    fun setUp() {
        tmpDir = Files.createTempDirectory("github-engine-test").toFile()
        workspaceRoot = File(tmpDir, "workspace").apply { mkdirs() }
        val taskRoot = File(tmpDir, "task-root").apply { mkdirs() }
        File(workspaceRoot, "README.md").writeText("hello from workspace", Charsets.UTF_8)
        val workspaceAdapter = FileWorkspaceAdapter(workspaceRoot, taskRoot)
        val deviceAdapter = JvmDeviceAdapter(listOf(workspaceRoot), workspaceAdapter)
        conversations = InMemoryConversationStore()
        eventLog = InMemoryEventLog()
        val transport = RoutingTransport { method, url, _ ->
            when {
                method == "GET" && url.contains("/contents/README.md?ref=main") ->
                    200 to """{"name":"README.md","path":"README.md","type":"file","sha":"aaa","size":10,"content":"${b64("hello github")}"}"""
                method == "GET" && url.contains("/repository/files/README.md?ref=main") ->
                    200 to """{"file_name":"README.md","file_path":"README.md","size":10,"encoding":"base64","content":"${b64("hello gitlab")}","blob_id":"aaa","commit_id":"c1"}"""
                else -> 404 to """{"message":"Not Found"}"""
            }
        }
        val port = GitHostProjectPort(MemoryCredentialStore()) { host ->
            GitHostApiClient(host, transport)
        }
        tools = ToolRegistry(
            deviceAdapter,
            workspaceAdapter,
            CommandLimits(),
            FakeTaskStore(),
            InMemoryAuditLog(),
            gitHostProjectPort = port,
        )
        BrainAdapterRegistry.clearForTests()
        BrainAdapterRegistry.registerConfig(
            BrainConfig(
                brainId = "fake-brain",
                kind = BrainKind.MODEL_API,
                adapterId = "fake-test-adapter",
                label = "Fake（测试替身）",
                baseUrl = null,
                model = "fake-model",
                authRef = null,
            ),
        )
    }

    @After
    fun tearDown() {
        BrainAdapterRegistry.clearForTests()
        tmpDir.deleteRecursively()
    }

    private fun engine(
        adapter: BrainAdapter,
        gate: ApprovalGate,
        gitHostContext: (() -> Pair<Project, ProjectLocation>?)? = null,
    ): BrainEngine {
        BrainAdapterRegistry.registerAdapter(adapter)
        return BrainEngine(
            adapters = BrainAdapterRegistry,
            conversations = conversations,
            contextManager = ContextManager(),
            memory = InMemoryMemoryStore(),
            tools = tools,
            eventLog = eventLog,
            approvalGate = gate,
            currentBrainId = { "fake-brain" },
            gitHostProjectContext = gitHostContext ?: { null },
        )
    }

    private fun readGithubScript(): (ChatRequest) -> Flow<BrainEvent> = { request ->
        offeredToolNames += request.tools.map { it.name }
        val hasToolResult = request.messages.any { it.role == "tool" }
        if (hasToolResult) {
            flowOf(BrainEvent.TextDelta("已读完 GitHub 文件"), BrainEvent.Done("stop"))
        } else {
            flowOf(
                BrainEvent.ToolCall("call-1", "read_github_file", mapOf("path" to "README.md")),
                BrainEvent.Done("stop"),
            )
        }
    }

    @Test
    fun `github home offers github tools and read_github_file runs`() = runTest {
        val conversation = conversations.createConversation("fake-brain", "读 GitHub 文件")
        val eng = engine(
            FakeAdapter(readGithubScript()),
            FakeApprovalGate(allow = true),
            gitHostContext = { githubProject() to githubHome() },
        )
        val events = eng.chat(conversation.id, "请读一下 GitHub 上的 README").toList()
        // 模型收到的工具里有 5 个 git 家工具。
        assertTrue(offeredToolNames.isNotEmpty())
        assertTrue(offeredToolNames.flatten().containsAll(ToolRegistry.GIT_HOST_TOOLS))
        // read_github_file 真跑了，结果回填进历史。
        assertTrue(events.any { it is BrainEvent.ToolCall && it.name == "read_github_file" })
        val result = events.filterIsInstance<BrainEvent.ToolResult>().firstOrNull()
        assertTrue(result != null && result.ok)
        val messages = conversations.listMessages(conversation.id)
        assertTrue(messages.any { it.role == "tool" && it.content.contains("hello github") })
        assertTrue(events.last() is BrainEvent.Done)
    }

    @Test
    fun `no github home offers no github tools`() = runTest {
        val conversation = conversations.createConversation("fake-brain", "读文件")
        val eng = engine(
            FakeAdapter(readGithubScript()),
            FakeApprovalGate(allow = true),
            gitHostContext = { null },
        )
        val events = eng.chat(conversation.id, "请读一下 GitHub 上的 README").toList()
        assertTrue(offeredToolNames.isNotEmpty())
        assertFalse(offeredToolNames.flatten().any { it in ToolRegistry.GIT_HOST_TOOLS })
        // 模型仍调了 read_github_file（脚本写死），但注册表如实拒绝、不执行。
        val result = events.filterIsInstance<BrainEvent.ToolResult>().firstOrNull()
        assertTrue(result != null && !result.ok)
        assertTrue(result!!.summary.contains("未配置 git 家项目"))
    }

    @Test
    fun `device home offers no github tools`() = runTest {
        val conversation = conversations.createConversation("fake-brain", "读文件")
        val eng = engine(
            FakeAdapter(readGithubScript()),
            FakeApprovalGate(allow = true),
            gitHostContext = {
                githubProject() to ProjectLocation.create(
                    "wherevercode-agent",
                    "remote-device",
                    "/workspace/example-project",
                )
            },
        )
        eng.chat(conversation.id, "请读一下 GitHub 上的 README").toList()
        assertTrue(offeredToolNames.isNotEmpty())
        assertFalse(offeredToolNames.flatten().any { it in ToolRegistry.GIT_HOST_TOOLS })
    }

    @Test
    fun `gitlab subgroup home offers git tools and read_github_file runs`() = runTest {
        val conversation = conversations.createConversation("fake-brain", "读 GitLab 文件")
        val eng = engine(
            FakeAdapter(readGithubScript()),
            FakeApprovalGate(allow = true),
            gitHostContext = { gitlabProject() to gitlabHome() },
        )
        val events = eng.chat(conversation.id, "请读一下 GitLab 上的 README").toList()
        assertTrue(offeredToolNames.isNotEmpty())
        assertTrue(offeredToolNames.flatten().containsAll(ToolRegistry.GIT_HOST_TOOLS))
        assertTrue(events.any { it is BrainEvent.ToolCall && it.name == "read_github_file" })
        val result = events.filterIsInstance<BrainEvent.ToolResult>().firstOrNull()
        assertTrue(result != null && result.ok)
        val messages = conversations.listMessages(conversation.id)
        assertTrue(messages.any { it.role == "tool" && it.content.contains("hello gitlab") })
        assertTrue(events.last() is BrainEvent.Done)
    }
}
