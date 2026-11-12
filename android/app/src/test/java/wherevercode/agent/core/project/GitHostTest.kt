package wherevercode.agent.core.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * git 托管站点抽象的测试：仓库地址解析（GitHub / GitLab 子群组多段
 * 路径 / Gitee）、三站各不相同的认证头、私有仓库走 token 的前提。
 * 不联网、不碰真实凭证（测试里只用假 token 字符串）。
 */
class GitHostTest {

    @Test
    fun `parse github url`() {
        val parsed = parseGitHostRepoUrl("https://github.com/o/r")
        assertEquals(GitHost.GITHUB, parsed?.host)
        assertEquals("o/r", parsed?.namespacePath)
        assertEquals("r", parsed?.repoName)
    }

    @Test
    fun `parse github url with git suffix and trailing slash`() {
        val parsed = parseGitHostRepoUrl("https://github.com/o/r.git/")
        assertEquals(GitHost.GITHUB, parsed?.host)
        assertEquals("o/r", parsed?.namespacePath)
    }

    @Test
    fun `parse gitlab subgroup url allows multiple segments`() {
        val parsed = parseGitHostRepoUrl("https://gitlab.com/group/subgroup/my-proj")
        assertEquals(GitHost.GITLAB, parsed?.host)
        assertEquals("group/subgroup/my-proj", parsed?.namespacePath)
        assertEquals("my-proj", parsed?.repoName)
    }

    @Test
    fun `parse gitlab two segment url`() {
        val parsed = parseGitHostRepoUrl("https://gitlab.com/group/my-proj")
        assertEquals(GitHost.GITLAB, parsed?.host)
        assertEquals("group/my-proj", parsed?.namespacePath)
    }

    @Test
    fun `parse gitee url`() {
        val parsed = parseGitHostRepoUrl("https://gitee.com/o/r")
        assertEquals(GitHost.GITEE, parsed?.host)
        assertEquals("o/r", parsed?.namespacePath)
        assertEquals("r", parsed?.repoName)
    }

    @Test
    fun `parse rejects non https unknown host and credentials in url`() {
        assertNull(parseGitHostRepoUrl("http://github.com/o/r"))
        assertNull(parseGitHostRepoUrl("https://bitbucket.org/o/r"))
        assertNull(parseGitHostRepoUrl("https://token@github.com/o/r"))
        assertNull(parseGitHostRepoUrl("https://github.com/o/r?x=1"))
        assertNull(parseGitHostRepoUrl("https://github.com/o/r#readme"))
        assertNull(parseGitHostRepoUrl(""))
        assertNull(parseGitHostRepoUrl("not a url"))
    }

    @Test
    fun `parse rejects wrong segment counts`() {
        // 三站的项目路径至少两段；GitHub/Gitee 恰好两段。
        assertNull(parseGitHostRepoUrl("https://github.com/o"))
        assertNull(parseGitHostRepoUrl("https://github.com/o/r/extra"))
        assertNull(parseGitHostRepoUrl("https://gitee.com/o/r/extra"))
        assertNull(parseGitHostRepoUrl("https://gitlab.com/onlyone"))
        assertNull(parseGitHostRepoUrl("https://github.com/o/r/../x"))
    }

    @Test
    fun `github auth header is bearer`() {
        val (name, value) = GitHost.GITHUB.authHeader("tok")
        assertEquals("Authorization", name)
        assertEquals("Bearer tok", value)
    }

    @Test
    fun `gitlab auth header is PRIVATE-TOKEN not bearer`() {
        val headers = mapOf(GitHost.GITLAB.authHeader("tok"))
        assertEquals("tok", headers["PRIVATE-TOKEN"])
        assertFalse(headers.containsKey("Authorization"))
    }

    @Test
    fun `gitee auth header is token`() {
        val (name, value) = GitHost.GITEE.authHeader("tok")
        assertEquals("Authorization", name)
        assertEquals("token tok", value)
    }

    @Test
    fun `each host has its own api base web base and token ref`() {
        assertEquals("https://api.github.com", GitHost.GITHUB.apiBase)
        assertEquals("https://gitlab.com/api/v4", GitHost.GITLAB.apiBase)
        assertEquals("https://gitee.com/api/v5", GitHost.GITEE.apiBase)
        assertEquals("github:token", GitHost.GITHUB.tokenRef)
        assertEquals("gitlab:token", GitHost.GITLAB.tokenRef)
        assertEquals("gitee:token", GitHost.GITEE.tokenRef)
        assertTrue(GitHost.ALL.map { it.tokenRef }.toSet().size == 3)
    }

    @Test
    fun `fromWire and fromWebHost round trip`() {
        assertEquals(GitHost.GITLAB, GitHost.fromWire("gitlab"))
        assertEquals(GitHost.GITEE, GitHost.fromWebHost("gitee.com"))
        assertNull(GitHost.fromWire("bitbucket"))
        assertNull(GitHost.fromWebHost("bitbucket.org"))
    }

    @Test
    fun `webUrl builds the repo page url`() {
        assertEquals(
            "https://gitlab.com/group/sub/my-proj",
            GitHost.GITLAB.webUrl("group/sub/my-proj"),
        )
        assertEquals("https://gitee.com/o/r", GitHost.GITEE.webUrl("o/r"))
    }

    @Test
    fun `gitHostRepoUrl builds the address back`() {
        val coordinates = parseGitHostRepoUrl("https://gitlab.com/group/sub/my-proj")!!
        assertEquals("https://gitlab.com/group/sub/my-proj", gitHostRepoUrl(coordinates))
    }
}
