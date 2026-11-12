package wherevercode.agent.core.project

import java.io.BufferedReader
import java.io.StringReader
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * git 家调用入口的测试。真实 [GitHostApiClient] + 按 URL 路由的
 * fake transport + 内存 token 存储：不联网、不动真仓库。
 *
 * 每站一组：GitHub（旧行为原样保留）、GitLab（子群组多段路径、
 * PRIVATE-TOKEN 头、合并请求）、Gitee（token 头）。
 */
class GitHostProjectPortTest {

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

    private class MemoryCredentialStore : GitHostCredentialStore {
        private val tokens = mutableMapOf<GitHost, String?>()

        override fun saveToken(host: GitHost, token: String) {
            tokens[host] = token
        }

        override fun loadToken(host: GitHost): String? = tokens[host]

        override fun clearToken(host: GitHost) {
            tokens.remove(host)
        }
    }

    private fun b64(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    private fun githubProject(branch: String = "main") = Project(
        projectId = "wherevercode-agent",
        name = "wherevercode-agent",
        repoUrl = "https://github.com/o/r",
        defaultBranch = branch,
    )

    private fun githubHome() =
        ProjectLocation.createGithub("wherevercode-agent", "o", "r")

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

    private fun deviceHome() =
        ProjectLocation.create("wherevercode-agent", "remote-device", "/workspace/example-project")

    private fun port(
        tokens: Map<GitHost, String?> = mapOf(GitHost.GITHUB to "tok"),
        handler: (method: String, url: String, body: String?) -> Pair<Int, String>,
    ): Pair<GitHostProjectPort, RoutingTransport> {
        val transport = RoutingTransport(handler)
        val store = MemoryCredentialStore()
        tokens.forEach { (host, token) -> token?.let { store.saveToken(host, it) } }
        val port = GitHostProjectPort(store) { host -> GitHostApiClient(host, transport) }
        return port to transport
    }

    @Test
    fun `resolveRepo reads coordinates from a github home`() {
        val (port, _) = port { _, _, _ -> 200 to "{}" }
        val coordinates = port.resolveRepo(githubHome())
        assertEquals(GitHost.GITHUB, coordinates?.host)
        assertEquals("o/r", coordinates?.namespacePath)
        assertNull(port.resolveRepo(deviceHome()))
    }

    @Test
    fun `resolveRepo reads multi segment gitlab home`() {
        val (port, _) = port { _, _, _ -> 200 to "{}" }
        val coordinates = port.resolveRepo(gitlabHome())
        assertEquals(GitHost.GITLAB, coordinates?.host)
        assertEquals("group/sub/my-proj", coordinates?.namespacePath)
        assertEquals("my-proj", coordinates?.repoName)
        val gitee = port.resolveRepo(giteeHome())
        assertEquals(GitHost.GITEE, gitee?.host)
        assertEquals("o/r", gitee?.namespacePath)
    }

    @Test
    fun `resolveRepoRef uses the project default branch`() {
        val (port, _) = port { _, _, _ -> 200 to "{}" }
        val ref = port.resolveRepoRef(githubProject("dev"), githubHome())
        assertEquals(GitHost.GITHUB, ref?.coordinates?.host)
        assertEquals("o/r", ref?.coordinates?.namespacePath)
        assertEquals("dev", ref?.branch)
        assertNull(port.resolveRepoRef(githubProject(), deviceHome()))
    }

    @Test
    fun `verifyHome returns the repo`() = runTest {
        val (port, transport) = port { method, url, _ ->
            if (method == "GET" && url == "https://api.github.com/repos/o/r") {
                200 to """{"full_name":"o/r","owner":{"login":"o"},"name":"r","default_branch":"main","private":false,"html_url":"https://github.com/o/r"}"""
            } else {
                404 to """{"message":"Not Found"}"""
            }
        }
        val repo = port.verifyHome(githubProject(), githubHome()).getOrThrow()
        assertEquals("main", repo.defaultBranch)
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun `verifyHome without a token fails before any http call`() = runTest {
        val (port, transport) = port(tokens = emptyMap()) { _, _, _ -> 200 to "{}" }
        val result = port.verifyHome(githubProject(), githubHome())
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("未配置 GitHub 授权") == true)
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun `verifyHome on a device home fails`() = runTest {
        val (port, transport) = port { _, _, _ -> 200 to "{}" }
        val result = port.verifyHome(githubProject(), deviceHome())
        assertTrue(result.isFailure)
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun `verifyHome gitlab uses PRIVATE-TOKEN and private repo works`() = runTest {
        val (port, transport) = port(
            tokens = mapOf(GitHost.GITLAB to "gl-token"),
        ) { method, url, _ ->
            if (method == "GET" && url == "https://gitlab.com/api/v4/projects/group%2Fsub%2Fmy-proj") {
                200 to """{"id":1,"path_with_namespace":"group/sub/my-proj","name":"my-proj","default_branch":"main","visibility":"private","web_url":"https://gitlab.com/group/sub/my-proj"}"""
            } else {
                404 to """{"message":"Not Found"}"""
            }
        }
        val repo = port.verifyHome(gitlabProject(), gitlabHome()).getOrThrow()
        assertTrue(repo.isPrivate)
        assertEquals("main", repo.defaultBranch)
        assertEquals("gl-token", transport.lastHeaders?.get("PRIVATE-TOKEN"))
    }

    @Test
    fun `verifyHome gitlab without a gitlab token fails even when github token exists`() = runTest {
        val (port, transport) = port(
            tokens = mapOf(GitHost.GITHUB to "gh-token"),
        ) { _, _, _ -> 200 to "{}" }
        val result = port.verifyHome(gitlabProject(), gitlabHome())
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("未配置 GitLab 授权") == true)
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun `readFile returns decoded text`() = runTest {
        val (port, _) = port { method, url, _ ->
            if (method == "GET" && url.contains("/contents/a.txt")) {
                200 to """{"name":"a.txt","path":"a.txt","type":"file","sha":"abc","size":5,"content":"${b64("hello")}\n","encoding":"base64"}"""
            } else {
                404 to """{"message":"Not Found"}"""
            }
        }
        val file = port.readFile(githubProject(), githubHome(), "a.txt").getOrThrow()
        assertEquals("hello", file.text)
        assertEquals("abc", file.sha)
    }

    @Test
    fun `readFile gitlab uses files endpoint`() = runTest {
        val (port, _) = port(
            tokens = mapOf(GitHost.GITLAB to "gl-token"),
        ) { method, url, _ ->
            if (method == "GET" && url.contains("/repository/files/a.txt?ref=main")) {
                200 to """{"file_name":"a.txt","file_path":"a.txt","size":5,"encoding":"base64","content":"${b64("hello gitlab")}\n","blob_id":"abc","commit_id":"c1"}"""
            } else {
                404 to """{"message":"Not Found"}"""
            }
        }
        val file = port.readFile(gitlabProject(), gitlabHome(), "a.txt").getOrThrow()
        assertEquals("hello gitlab", file.text)
        assertEquals("abc", file.sha)
    }

    @Test
    fun `writeFile creates a new file without sha when it does not exist`() = runTest {
        val (port, transport) = port { method, url, body ->
            when {
                method == "GET" && url.contains("/contents/a.txt") ->
                    404 to """{"message":"Not Found"}"""
                method == "PUT" && url.contains("/contents/a.txt") ->
                    201 to """{"content":{"path":"a.txt","sha":"newsha"},"commit":{"sha":"c1"}}"""
                else -> 404 to """{"message":"Not Found"}"""
            }
        }
        val write = port.writeFile(
            githubProject(), githubHome(), "a.txt", "hello", "add a.txt",
        ).getOrThrow()
        assertEquals("newsha", write.sha)
        val put = transport.calls.first { it.first == "PUT" }
        assertTrue(put.third?.contains("\"sha\"") == false)
    }

    @Test
    fun `writeFile updates with the current sha when the file exists`() = runTest {
        val (port, transport) = port { method, url, body ->
            when {
                method == "GET" && url.contains("/contents/a.txt") ->
                    200 to """{"name":"a.txt","path":"a.txt","type":"file","sha":"oldsha","size":5,"content":"${b64("old")}\n","encoding":"base64"}"""
                method == "PUT" && url.contains("/contents/a.txt") ->
                    200 to """{"content":{"path":"a.txt","sha":"newsha"},"commit":{"sha":"c2"}}"""
                else -> 404 to """{"message":"Not Found"}"""
            }
        }
        val write = port.writeFile(
            githubProject(), githubHome(), "a.txt", "new", "update a.txt",
        ).getOrThrow()
        assertEquals("newsha", write.sha)
        val put = transport.calls.first { it.first == "PUT" }
        val body = org.json.JSONObject(put.third ?: "")
        assertEquals("oldsha", body.getString("sha"))
        assertEquals("main", body.getString("branch"))
    }

    @Test
    fun `createBranch reads the default head then creates the branch`() = runTest {
        val (port, transport) = port { method, url, body ->
            when {
                method == "GET" && url.endsWith("/git/ref/heads/main") ->
                    200 to """{"ref":"refs/heads/main","object":{"sha":"headsha","type":"commit"}}"""
                method == "POST" && url.endsWith("/git/refs") ->
                    201 to """{"ref":"refs/heads/fix/x","object":{"sha":"headsha","type":"commit"}}"""
                else -> 404 to """{"message":"Not Found"}"""
            }
        }
        val ref = port.createBranch(githubProject(), githubHome(), "fix/x").getOrThrow()
        assertEquals("refs/heads/fix/x", ref)
        assertEquals(2, transport.calls.size)
        assertEquals("GET", transport.calls[0].first)
        assertEquals("POST", transport.calls[1].first)
        val body = org.json.JSONObject(transport.calls[1].third ?: "")
        assertEquals("headsha", body.getString("sha"))
    }

    @Test
    fun `createPullRequest targets the project default branch`() = runTest {
        val (port, transport) = port { method, url, body ->
            if (method == "POST" && url.endsWith("/pulls")) {
                201 to """{"number":3,"html_url":"https://github.com/o/r/pull/3","title":"t"}"""
            } else {
                404 to """{"message":"Not Found"}"""
            }
        }
        val pr = port.createPullRequest(
            githubProject("dev"), githubHome(), "fix/x", "t", "b",
        ).getOrThrow()
        assertEquals(3, pr.number)
        val body = org.json.JSONObject(transport.calls[0].third ?: "")
        assertEquals("fix/x", body.getString("head"))
        assertEquals("dev", body.getString("base"))
    }

    @Test
    fun `createPullRequest gitlab opens a merge request`() = runTest {
        val (port, transport) = port(
            tokens = mapOf(GitHost.GITLAB to "gl-token"),
        ) { method, url, body ->
            when {
                method == "GET" && url.contains("/repository/branches/main") ->
                    200 to """{"name":"main","commit":{"id":"headsha"}}"""
                method == "POST" && url.endsWith("/merge_requests") ->
                    201 to """{"iid":9,"web_url":"https://gitlab.com/group/sub/my-proj/-/merge_requests/9","title":"t"}"""
                else -> 404 to """{"message":"Not Found"}"""
            }
        }
        val pr = port.createPullRequest(
            gitlabProject(), gitlabHome(), "fix/x", "t", "b",
        ).getOrThrow()
        assertEquals(9, pr.number)
        assertEquals(
            "https://gitlab.com/group/sub/my-proj/-/merge_requests/9",
            pr.htmlUrl,
        )
        val body = org.json.JSONObject(transport.calls[0].third ?: "")
        assertEquals("fix/x", body.getString("source_branch"))
        assertEquals("main", body.getString("target_branch"))
    }

    @Test
    fun `listRoot lists the repository root`() = runTest {
        val (port, _) = port { method, url, _ ->
            if (method == "GET" && url.contains("/contents/")) {
                200 to """[{"name":"README.md","path":"README.md","type":"file","sha":"a","size":1}]"""
            } else {
                404 to """{"message":"Not Found"}"""
            }
        }
        val entries = port.listRoot(githubProject(), githubHome()).getOrThrow()
        assertEquals(1, entries.size)
        assertEquals("README.md", entries[0].name)
    }

    @Test
    fun `listRoot gitlab lists via tree`() = runTest {
        val (port, _) = port(
            tokens = mapOf(GitHost.GITLAB to "gl-token"),
        ) { method, url, _ ->
            if (method == "GET" && url.contains("/repository/tree?ref=main")) {
                200 to """[{"id":"a","name":"README.md","type":"blob","path":"README.md","mode":"100644"}]"""
            } else {
                404 to """{"message":"Not Found"}"""
            }
        }
        val entries = port.listRoot(gitlabProject(), gitlabHome()).getOrThrow()
        assertEquals(1, entries.size)
        assertEquals("README.md", entries[0].name)
        assertEquals("file", entries[0].type)
    }

    @Test
    fun `listRoot gitee lists via contents`() = runTest {
        val (port, _) = port(
            tokens = mapOf(GitHost.GITEE to "ge-token"),
        ) { method, url, _ ->
            if (method == "GET" && url.contains("/contents/?ref=master")) {
                200 to """[{"name":"README.md","path":"README.md","type":"file","sha":"a","size":1}]"""
            } else {
                404 to """{"message":"Not Found"}"""
            }
        }
        val entries = port.listRoot(giteeProject(), giteeHome()).getOrThrow()
        assertEquals(1, entries.size)
        assertEquals("README.md", entries[0].name)
    }
}
