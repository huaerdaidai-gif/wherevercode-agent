package wherevercode.agent.core.project

import java.io.BufferedReader
import java.io.StringReader
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * git 托管站点 API 客户端的线级测试。全部 HTTP 都是 fake transport：
 * 不联网、不花 token、不动真仓库。
 *
 * 每站各一组：GitHub（旧行为原样保留）、GitLab（PRIVATE-TOKEN 头、
 * tree/files/branches/merge_requests、子群组多段路径编成一个段）、
 * Gitee（`Authorization: token` 头、contents/branches/pulls）。
 */
class GitHostApiClientTest {

    private class FakeConnection(
        private val body: String,
        override val statusCode: Int,
        private val errorBody: String? = null,
    ) : GitHostConnection {
        override fun openBodyReader(): BufferedReader = BufferedReader(StringReader(body))
        override fun readErrorBody(): String? = errorBody
        override fun close() = Unit
    }

    private class FakeTransport(
        var body: String = "{}",
        var status: Int = 200,
        var errorBody: String? = null,
    ) : GitHostTransport {
        var lastMethod: String? = null
        var lastUrl: String? = null
        var lastHeaders: Map<String, String>? = null
        var lastBody: String? = null

        override fun get(url: String, headers: Map<String, String>): GitHostConnection {
            lastMethod = "GET"
            lastUrl = url
            lastHeaders = headers
            return FakeConnection(body, status, errorBody)
        }

        override fun put(url: String, headers: Map<String, String>, requestBody: String): GitHostConnection {
            lastMethod = "PUT"
            lastUrl = url
            lastHeaders = headers
            lastBody = requestBody
            return FakeConnection(body, status, errorBody)
        }

        override fun post(url: String, headers: Map<String, String>, requestBody: String): GitHostConnection {
            lastMethod = "POST"
            lastUrl = url
            lastHeaders = headers
            lastBody = requestBody
            return FakeConnection(body, status, errorBody)
        }
    }

    private fun b64(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    // ---- GitHub（旧行为原样保留） ----

    @Test
    fun `github getRepo parses repo and default branch`() = runTest {
        val transport = FakeTransport(
            body = """{"full_name":"o/r","owner":{"login":"o"},"name":"r","default_branch":"main","private":true,"html_url":"https://github.com/o/r"}""",
        )
        val client = GitHostApiClient(GitHost.GITHUB, transport)
        val repo = client.getRepo("tok", "o/r").getOrThrow()
        assertEquals("o/r", repo.fullName)
        assertEquals("main", repo.defaultBranch)
        assertTrue(repo.isPrivate)
        assertEquals("https://api.github.com/repos/o/r", transport.lastUrl)
        assertEquals("Bearer tok", transport.lastHeaders?.get("Authorization"))
    }

    @Test
    fun `github blank token fails without any http call`() = runTest {
        val transport = FakeTransport()
        val client = GitHostApiClient(GitHost.GITHUB, transport)
        val result = client.getRepo("", "o/r")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("未配置 GitHub 授权") == true)
        assertEquals(null, transport.lastUrl)
    }

    @Test
    fun `github getRepo 404 says not found`() = runTest {
        val transport = FakeTransport(
            status = 404,
            body = "",
            errorBody = """{"message":"Not Found"}""",
        )
        val client = GitHostApiClient(GitHost.GITHUB, transport)
        val result = client.getRepo("tok", "o/r")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("404") == true)
    }

    @Test
    fun `github getRepo 401 says auth failed`() = runTest {
        val transport = FakeTransport(
            status = 401,
            body = "",
            errorBody = """{"message":"Bad credentials"}""",
        )
        val client = GitHostApiClient(GitHost.GITHUB, transport)
        val result = client.getRepo("tok", "o/r")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("401") == true)
    }

    @Test
    fun `github listContents parses a directory array`() = runTest {
        val transport = FakeTransport(
            body = """[{"name":"README.md","path":"README.md","type":"file","sha":"aaa","size":12},{"name":"src","path":"src","type":"dir","sha":"bbb","size":0}]""",
        )
        val client = GitHostApiClient(GitHost.GITHUB, transport)
        val entries = client.listContents("tok", "o/r", "", "main").getOrThrow()
        assertEquals(2, entries.size)
        assertEquals("README.md", entries[0].name)
        assertEquals("file", entries[0].type)
        assertEquals("dir", entries[1].type)
        assertTrue(transport.lastUrl?.contains("ref=main") == true)
    }

    @Test
    fun `github listContents with a file path returns one entry`() = runTest {
        val transport = FakeTransport(
            body = """{"name":"README.md","path":"README.md","type":"file","sha":"aaa","size":12}""",
        )
        val client = GitHostApiClient(GitHost.GITHUB, transport)
        val entries = client.listContents("tok", "o/r", "README.md", "main").getOrThrow()
        assertEquals(1, entries.size)
        assertEquals("README.md", entries[0].name)
    }

    @Test
    fun `github getFile decodes base64 content`() = runTest {
        val transport = FakeTransport(
            body = """{"name":"a.txt","path":"a.txt","type":"file","sha":"abc","size":5,"content":"${b64("hello")}\n","encoding":"base64"}""",
        )
        val client = GitHostApiClient(GitHost.GITHUB, transport)
        val file = client.getFile("tok", "o/r", "a.txt", "main").getOrThrow()
        assertEquals("hello", file.text)
        assertEquals("abc", file.sha)
        assertEquals("a.txt", file.path)
    }

    @Test
    fun `github getFile on a directory fails`() = runTest {
        val transport = FakeTransport(
            body = """{"name":"src","path":"src","type":"dir","sha":"bbb","size":0}""",
        )
        val client = GitHostApiClient(GitHost.GITHUB, transport)
        val result = client.getFile("tok", "o/r", "src", "main")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("不是文件") == true)
    }

    @Test
    fun `github getBranchHead returns the commit sha`() = runTest {
        val transport = FakeTransport(
            body = """{"ref":"refs/heads/main","object":{"sha":"deadbeef","type":"commit"}}""",
        )
        val client = GitHostApiClient(GitHost.GITHUB, transport)
        assertEquals("deadbeef", client.getBranchHead("tok", "o/r", "main").getOrThrow())
        assertTrue(transport.lastUrl?.endsWith("/git/ref/heads/main") == true)
    }

    @Test
    fun `github createBranch posts ref and sha`() = runTest {
        val transport = FakeTransport(
            status = 201,
            body = """{"ref":"refs/heads/fix/x","object":{"sha":"deadbeef","type":"commit"}}""",
        )
        val client = GitHostApiClient(GitHost.GITHUB, transport)
        val ref = client.createBranch("tok", "o/r", "fix/x", "deadbeef").getOrThrow()
        assertEquals("refs/heads/fix/x", ref)
        assertEquals("POST", transport.lastMethod)
        assertTrue(transport.lastUrl?.endsWith("/git/refs") == true)
        val body = org.json.JSONObject(transport.lastBody ?: "")
        assertEquals("refs/heads/fix/x", body.getString("ref"))
        assertEquals("deadbeef", body.getString("sha"))
    }

    @Test
    fun `github createBranch rejects an invalid branch name without http`() = runTest {
        val transport = FakeTransport()
        val client = GitHostApiClient(GitHost.GITHUB, transport)
        val result = client.createBranch("tok", "o/r", "../bad", "deadbeef")
        assertTrue(result.isFailure)
        assertEquals(null, transport.lastUrl)
    }

    @Test
    fun `github createOrUpdateFile puts base64 content with message and branch`() = runTest {
        val transport = FakeTransport(
            status = 201,
            body = """{"content":{"path":"a.txt","sha":"newsha"},"commit":{"sha":"commitsha"}}""",
        )
        val client = GitHostApiClient(GitHost.GITHUB, transport)
        val write = client.createOrUpdateFile(
            token = "tok",
            namespacePath = "o/r",
            path = "a.txt",
            content = "hello",
            message = "add a.txt",
            branch = "fix/x",
            sha = "oldsha",
        ).getOrThrow()
        assertEquals("newsha", write.sha)
        assertEquals("commitsha", write.commitSha)
        assertEquals("PUT", transport.lastMethod)
        val body = org.json.JSONObject(transport.lastBody ?: "")
        assertEquals("add a.txt", body.getString("message"))
        assertEquals(b64("hello"), body.getString("content"))
        assertEquals("fix/x", body.getString("branch"))
        assertEquals("oldsha", body.getString("sha"))
    }

    @Test
    fun `github createOrUpdateFile without sha is a create`() = runTest {
        val transport = FakeTransport(
            status = 201,
            body = """{"content":{"path":"a.txt","sha":"newsha"},"commit":{"sha":"commitsha"}}""",
        )
        val client = GitHostApiClient(GitHost.GITHUB, transport)
        client.createOrUpdateFile("tok", "o/r", "a.txt", "hello", "add", "main", null).getOrThrow()
        val body = org.json.JSONObject(transport.lastBody ?: "")
        assertFalse(body.has("sha"))
    }

    @Test
    fun `github createPullRequest posts head base and title`() = runTest {
        val transport = FakeTransport(
            status = 201,
            body = """{"number":7,"html_url":"https://github.com/o/r/pull/7","title":"fix"}""",
        )
        val client = GitHostApiClient(GitHost.GITHUB, transport)
        val pr = client.createPullRequest("tok", "o/r", "fix/x", "main", "fix", "body").getOrThrow()
        assertEquals(7, pr.number)
        assertEquals("https://github.com/o/r/pull/7", pr.htmlUrl)
        assertEquals("POST", transport.lastMethod)
        assertTrue(transport.lastUrl?.endsWith("/pulls") == true)
        val body = org.json.JSONObject(transport.lastBody ?: "")
        assertEquals("fix/x", body.getString("head"))
        assertEquals("main", body.getString("base"))
        assertEquals("fix", body.getString("title"))
    }

    @Test
    fun `github contents url encodes path segments`() {
        val url = GitHostApiClient.contentsUrl(GitHost.GITHUB, "o/r", "my dir/a b.txt")
        assertEquals(
            "https://api.github.com/repos/o/r/contents/my%20dir/a%20b.txt",
            url,
        )
    }

    // ---- GitLab ----

    @Test
    fun `gitlab getRepo uses url encoded namespace path and PRIVATE-TOKEN`() = runTest {
        val transport = FakeTransport(
            body = """{"id":1,"path_with_namespace":"group/sub/my-proj","name":"my-proj","namespace":{"path":"group","full_path":"group/sub"},"default_branch":"main","visibility":"private","web_url":"https://gitlab.com/group/sub/my-proj"}""",
        )
        val client = GitHostApiClient(GitHost.GITLAB, transport)
        val repo = client.getRepo("tok", "group/sub/my-proj").getOrThrow()
        assertEquals("group/sub/my-proj", repo.fullName)
        assertEquals("group/sub", repo.owner)
        assertEquals("my-proj", repo.name)
        assertEquals("main", repo.defaultBranch)
        assertTrue(repo.isPrivate)
        assertEquals(
            "https://gitlab.com/api/v4/projects/group%2Fsub%2Fmy-proj",
            transport.lastUrl,
        )
        assertEquals("tok", transport.lastHeaders?.get("PRIVATE-TOKEN"))
        assertFalse(transport.lastHeaders?.containsKey("Authorization") == true)
    }

    @Test
    fun `gitlab blank token fails without any http call`() = runTest {
        val transport = FakeTransport()
        val client = GitHostApiClient(GitHost.GITLAB, transport)
        val result = client.getRepo("", "group/my-proj")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("未配置 GitLab 授权") == true)
        assertEquals(null, transport.lastUrl)
    }

    @Test
    fun `gitlab listContents uses tree and maps blob and tree`() = runTest {
        val transport = FakeTransport(
            body = """[{"id":"aaa","name":"README.md","type":"blob","path":"README.md","mode":"100644"},{"id":"bbb","name":"src","type":"tree","path":"src","mode":"040000"}]""",
        )
        val client = GitHostApiClient(GitHost.GITLAB, transport)
        val entries = client.listContents("tok", "group/my-proj", "", "main").getOrThrow()
        assertEquals(2, entries.size)
        assertEquals("README.md", entries[0].name)
        assertEquals("file", entries[0].type)
        assertEquals("aaa", entries[0].sha)
        assertEquals("dir", entries[1].type)
        assertTrue(transport.lastUrl?.contains("/repository/tree?ref=main") == true)
        assertEquals("tok", transport.lastHeaders?.get("PRIVATE-TOKEN"))
    }

    @Test
    fun `gitlab listContents with a path passes it as query`() = runTest {
        val transport = FakeTransport(body = "[]")
        val client = GitHostApiClient(GitHost.GITLAB, transport)
        client.listContents("tok", "group/my-proj", "src", "main").getOrThrow()
        assertTrue(transport.lastUrl?.contains("path=src") == true)
    }

    @Test
    fun `gitlab getFile encodes the file path as one segment`() = runTest {
        val transport = FakeTransport(
            body = """{"file_name":"a.txt","file_path":"dir/a.txt","size":5,"encoding":"base64","content":"${b64("hello")}\n","ref":"main","blob_id":"abc","commit_id":"c1"}""",
        )
        val client = GitHostApiClient(GitHost.GITLAB, transport)
        val file = client.getFile("tok", "group/my-proj", "dir/a.txt", "main").getOrThrow()
        assertEquals("hello", file.text)
        assertEquals("abc", file.sha)
        assertEquals("dir/a.txt", file.path)
        assertTrue(
            transport.lastUrl?.contains("/repository/files/dir%2Fa.txt?ref=main") == true,
        )
    }

    @Test
    fun `gitlab getBranchHead reads commit id`() = runTest {
        val transport = FakeTransport(
            body = """{"name":"main","commit":{"id":"deadbeef"}}""",
        )
        val client = GitHostApiClient(GitHost.GITLAB, transport)
        assertEquals("deadbeef", client.getBranchHead("tok", "group/my-proj", "main").getOrThrow())
        assertTrue(transport.lastUrl?.endsWith("/repository/branches/main") == true)
    }

    @Test
    fun `gitlab createBranch posts branch and ref`() = runTest {
        val transport = FakeTransport(
            status = 201,
            body = """{"name":"fix/x","commit":{"id":"deadbeef"}}""",
        )
        val client = GitHostApiClient(GitHost.GITLAB, transport)
        val created = client.createBranch("tok", "group/my-proj", "fix/x", "deadbeef").getOrThrow()
        assertEquals("fix/x", created)
        assertEquals("POST", transport.lastMethod)
        assertTrue(transport.lastUrl?.endsWith("/repository/branches") == true)
        val body = org.json.JSONObject(transport.lastBody ?: "")
        assertEquals("fix/x", body.getString("branch"))
        assertEquals("deadbeef", body.getString("ref"))
    }

    @Test
    fun `gitlab createOrUpdateFile creates with POST and no last commit id`() = runTest {
        val transport = FakeTransport(
            status = 201,
            body = """{"file_path":"a.txt","branch":"main"}""",
        )
        val client = GitHostApiClient(GitHost.GITLAB, transport)
        val write = client.createOrUpdateFile("tok", "group/my-proj", "a.txt", "hello", "add", "main", null).getOrThrow()
        assertEquals("a.txt", write.path)
        assertEquals("POST", transport.lastMethod)
        val body = org.json.JSONObject(transport.lastBody ?: "")
        assertEquals("base64", body.getString("encoding"))
        assertEquals(b64("hello"), body.getString("content"))
        assertFalse(body.has("last_commit_id"))
    }

    @Test
    fun `gitlab createOrUpdateFile updates with PUT and last commit id`() = runTest {
        val transport = FakeTransport(
            status = 200,
            body = """{"file_path":"a.txt","branch":"main"}""",
        )
        val client = GitHostApiClient(GitHost.GITLAB, transport)
        client.createOrUpdateFile("tok", "group/my-proj", "a.txt", "new", "update", "main", "oldsha").getOrThrow()
        assertEquals("PUT", transport.lastMethod)
        val body = org.json.JSONObject(transport.lastBody ?: "")
        assertEquals("oldsha", body.getString("last_commit_id"))
        assertEquals("update", body.getString("commit_message"))
    }

    @Test
    fun `gitlab createPullRequest posts a merge request and reads iid`() = runTest {
        val transport = FakeTransport(
            status = 201,
            body = """{"iid":3,"web_url":"https://gitlab.com/group/my-proj/-/merge_requests/3","title":"fix"}""",
        )
        val client = GitHostApiClient(GitHost.GITLAB, transport)
        val pr = client.createPullRequest("tok", "group/my-proj", "fix/x", "main", "fix", "body").getOrThrow()
        assertEquals(3, pr.number)
        assertEquals("https://gitlab.com/group/my-proj/-/merge_requests/3", pr.htmlUrl)
        assertEquals("POST", transport.lastMethod)
        assertTrue(transport.lastUrl?.endsWith("/merge_requests") == true)
        val body = org.json.JSONObject(transport.lastBody ?: "")
        assertEquals("fix/x", body.getString("source_branch"))
        assertEquals("main", body.getString("target_branch"))
        assertEquals("fix", body.getString("title"))
    }

    // ---- Gitee ----

    @Test
    fun `gitee getRepo uses token header not bearer`() = runTest {
        val transport = FakeTransport(
            body = """{"full_name":"o/r","owner":{"login":"o"},"name":"r","default_branch":"master","private":true,"html_url":"https://gitee.com/o/r"}""",
        )
        val client = GitHostApiClient(GitHost.GITEE, transport)
        val repo = client.getRepo("tok", "o/r").getOrThrow()
        assertEquals("o/r", repo.fullName)
        assertEquals("master", repo.defaultBranch)
        assertTrue(repo.isPrivate)
        assertEquals("https://gitee.com/api/v5/repos/o/r", transport.lastUrl)
        assertEquals("token tok", transport.lastHeaders?.get("Authorization"))
    }

    @Test
    fun `gitee listContents and getFile use contents api`() = runTest {
        val transport = FakeTransport(
            body = """[{"name":"README.md","path":"README.md","type":"file","sha":"aaa","size":12}]""",
        )
        val client = GitHostApiClient(GitHost.GITEE, transport)
        val entries = client.listContents("tok", "o/r", "", "master").getOrThrow()
        assertEquals(1, entries.size)
        assertEquals("README.md", entries[0].name)

        transport.body = """{"name":"a.txt","path":"a.txt","type":"file","sha":"abc","size":5,"content":"${b64("hello")}\n","encoding":"base64"}"""
        val file = client.getFile("tok", "o/r", "a.txt", "master").getOrThrow()
        assertEquals("hello", file.text)
        assertTrue(transport.lastUrl?.contains("/contents/a.txt?ref=master") == true)
    }

    @Test
    fun `gitee getBranchHead reads commit sha`() = runTest {
        val transport = FakeTransport(
            body = """{"name":"master","commit":{"sha":"deadbeef"}}""",
        )
        val client = GitHostApiClient(GitHost.GITEE, transport)
        assertEquals("deadbeef", client.getBranchHead("tok", "o/r", "master").getOrThrow())
        assertTrue(transport.lastUrl?.endsWith("/branches/master") == true)
    }

    @Test
    fun `gitee createBranch posts refs and branch name`() = runTest {
        val transport = FakeTransport(
            status = 201,
            body = """{"name":"fix/x","commit":{"sha":"deadbeef"}}""",
        )
        val client = GitHostApiClient(GitHost.GITEE, transport)
        val created = client.createBranch("tok", "o/r", "fix/x", "deadbeef").getOrThrow()
        assertEquals("fix/x", created)
        val body = org.json.JSONObject(transport.lastBody ?: "")
        assertEquals("deadbeef", body.getString("refs"))
        assertEquals("fix/x", body.getString("branch_name"))
    }

    @Test
    fun `gitee createOrUpdateFile creates with POST and updates with PUT and sha`() = runTest {
        val transport = FakeTransport(
            status = 201,
            body = """{"content":{"path":"a.txt","sha":"newsha"},"commit":{"sha":"commitsha"}}""",
        )
        val client = GitHostApiClient(GitHost.GITEE, transport)
        client.createOrUpdateFile("tok", "o/r", "a.txt", "hello", "add", "master", null).getOrThrow()
        assertEquals("POST", transport.lastMethod)
        assertFalse(org.json.JSONObject(transport.lastBody ?: "").has("sha"))

        transport.status = 200
        client.createOrUpdateFile("tok", "o/r", "a.txt", "new", "update", "master", "oldsha").getOrThrow()
        assertEquals("PUT", transport.lastMethod)
        assertEquals("oldsha", org.json.JSONObject(transport.lastBody ?: "").getString("sha"))
    }

    @Test
    fun `gitee createPullRequest posts pulls`() = runTest {
        val transport = FakeTransport(
            status = 201,
            body = """{"number":5,"html_url":"https://gitee.com/o/r/pulls/5","title":"fix"}""",
        )
        val client = GitHostApiClient(GitHost.GITEE, transport)
        val pr = client.createPullRequest("tok", "o/r", "fix/x", "master", "fix", "body").getOrThrow()
        assertEquals(5, pr.number)
        assertEquals("https://gitee.com/o/r/pulls/5", pr.htmlUrl)
        val body = org.json.JSONObject(transport.lastBody ?: "")
        assertEquals("fix/x", body.getString("head"))
        assertEquals("master", body.getString("base"))
    }
}
