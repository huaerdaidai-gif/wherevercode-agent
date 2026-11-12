package wherevercode.agent.core.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通用 git 仓库地址解析（2026-11-11，项目来源调研第三步）：
 * Hugging Face Space 就是 host 为 huggingface.co 的 Git URL，按
 * huggingface.co/{spaces,models,datasets}/owner/name 的路径形状解析；
 * 模型/数据集仓库解析得出来，但绝不能当可编辑的项目家。
 */
class GitRepoUrlTest {

    @Test
    fun `github url parses to owner and repo`() {
        val parsed = parseGitRepoUrl("https://github.com/huaerdaidai-gif/wherevercode-agent")
        assertEquals(GitRepoKind.GITHUB, parsed?.kind)
        assertEquals("huaerdaidai-gif", parsed?.owner)
        assertEquals("wherevercode-agent", parsed?.repo)
        assertTrue(parsed?.isEditableProjectHome == true)
        assertNull(parsed?.badge)
        assertEquals(
            "https://github.com/huaerdaidai-gif/wherevercode-agent",
            parsed?.cloneUrl,
        )
    }

    @Test
    fun `github url tolerates dot git suffix and trailing slash`() {
        val parsed = parseGitRepoUrl("https://github.com/owner/repo.git/")
        assertEquals(GitRepoKind.GITHUB, parsed?.kind)
        assertEquals("owner/repo", parsed?.path)
    }

    @Test
    fun `gitlab multi segment group parses`() {
        val parsed = parseGitRepoUrl("https://gitlab.com/group/subgroup/project")
        assertEquals(GitRepoKind.GITLAB, parsed?.kind)
        assertEquals("group/subgroup", parsed?.owner)
        assertEquals("project", parsed?.repo)
        assertTrue(parsed?.isEditableProjectHome == true)
    }

    @Test
    fun `gitee url parses`() {
        val parsed = parseGitRepoUrl("https://gitee.com/owner/repo")
        assertEquals(GitRepoKind.GITEE, parsed?.kind)
        assertEquals("owner", parsed?.owner)
        assertEquals("repo", parsed?.repo)
    }

    @Test
    fun `hf space url parses with space badge`() {
        val parsed = parseGitRepoUrl("https://huggingface.co/spaces/owner/my-space")
        assertEquals(GitRepoKind.HF_SPACE, parsed?.kind)
        assertEquals("owner", parsed?.owner)
        assertEquals("my-space", parsed?.repo)
        assertEquals("spaces/owner/my-space", parsed?.path)
        assertTrue(parsed?.isCodeRepo == true)
        assertTrue(parsed?.isEditableProjectHome == true)
        assertEquals("Space", parsed?.badge)
        assertEquals(
            "Space · spaces/owner/my-space（推送后在 HF 上重建）",
            gitRepoDisplayLabel("https://huggingface.co/spaces/owner/my-space"),
        )
    }

    @Test
    fun `hf space url tolerates dot git suffix`() {
        val parsed = parseGitRepoUrl("https://huggingface.co/spaces/owner/my-space.git")
        assertEquals(GitRepoKind.HF_SPACE, parsed?.kind)
        assertEquals("spaces/owner/my-space", parsed?.path)
    }

    @Test
    fun `hf model shorthand is a model repo not an editable home`() {
        val parsed = parseGitRepoUrl("https://huggingface.co/owner/some-model")
        assertEquals(GitRepoKind.HF_MODEL, parsed?.kind)
        assertEquals("模型仓库", parsed?.badge)
        assertTrue(parsed?.isCodeRepo == false)
        assertFalse(parsed?.isEditableProjectHome == true)
    }

    @Test
    fun `hf models prefix is a model repo`() {
        val parsed = parseGitRepoUrl("https://huggingface.co/models/owner/some-model")
        assertEquals(GitRepoKind.HF_MODEL, parsed?.kind)
        assertFalse(parsed?.isEditableProjectHome == true)
    }

    @Test
    fun `hf datasets prefix is a dataset not an editable home`() {
        val parsed = parseGitRepoUrl("https://huggingface.co/datasets/owner/some-data")
        assertEquals(GitRepoKind.HF_DATASET, parsed?.kind)
        assertEquals("数据集", parsed?.badge)
        assertFalse(parsed?.isEditableProjectHome == true)
    }

    @Test
    fun `hf type prefix with only two segments is not a model shorthand`() {
        // huggingface.co/spaces/owner 缺一段：不是模型简写，是缺一段的 Space 地址。
        assertNull(parseGitRepoUrl("https://huggingface.co/spaces/owner"))
        assertNull(parseGitRepoUrl("https://huggingface.co/models/owner"))
    }

    @Test
    fun `hf wrong segment counts are rejected`() {
        assertNull(parseGitRepoUrl("https://huggingface.co/spaces/owner/name/extra"))
        assertNull(parseGitRepoUrl("https://huggingface.co/owner"))
        assertNull(parseGitRepoUrl("https://huggingface.co/"))
    }

    @Test
    fun `generic https host with owner and repo parses`() {
        val parsed = parseGitRepoUrl("https://git.example.com/owner/repo")
        assertEquals(GitRepoKind.GENERIC, parsed?.kind)
        assertTrue(parsed?.isEditableProjectHome == true)
    }

    @Test
    fun `generic host with wrong segment count is rejected`() {
        assertNull(parseGitRepoUrl("https://git.example.com/owner"))
        assertNull(parseGitRepoUrl("https://git.example.com/a/b/c"))
    }

    @Test
    fun `non https userinfo query fragment and ports are rejected`() {
        assertNull(parseGitRepoUrl("http://github.com/owner/repo"))
        assertNull(parseGitRepoUrl("https://user:token@github.com/owner/repo"))
        assertNull(parseGitRepoUrl("https://github.com/owner/repo?x=1"))
        assertNull(parseGitRepoUrl("https://github.com/owner/repo#main"))
        assertNull(parseGitRepoUrl("https://github.com:443/owner/repo"))
        assertNull(parseGitRepoUrl("https://github.com/owner/repo/../etc"))
        assertNull(parseGitRepoUrl("not a url"))
        assertNull(parseGitRepoUrl(""))
    }

    @Test
    fun `git host parser accepts github and gitlab, rejects hf space`() {
        // parseGithubRepoUrl 已随 GithubApiClient 一起被 GitHost 取代（update9）：
        // 同样的“只认三站 git 家、HF Space 不冒充”口径，现由 parseGitHostRepoUrl 承担。
        val github = parseGitHostRepoUrl("https://github.com/owner/repo")
        assertEquals(GitHost.GITHUB, github?.host)
        assertEquals("owner/repo", github?.namespacePath)
        assertNull(parseGitHostRepoUrl("https://huggingface.co/spaces/owner/my-space"))
        val gitlab = parseGitHostRepoUrl("https://gitlab.com/group/project")
        assertEquals(GitHost.GITLAB, gitlab?.host)
        assertEquals("group/project", gitlab?.namespacePath)
    }
}
