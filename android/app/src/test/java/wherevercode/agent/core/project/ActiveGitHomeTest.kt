package wherevercode.agent.core.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 派活按当前家路由（2026-11-12，09:46 实证）：
 * 只有当前家是 git 家时才走大脑 git API 路；当前家是设备家/
 * 本机家时不走大脑路，直走远端通道。
 *
 * 纯函数 [activeGitHome] 的判定，不碰 MainActivity（Activity
 * 本身不在单测里）；MainActivity.resolveChatGitHostProject
 * 只是把它包一层（取 projectWithHomes 再调它）。
 */
class ActiveGitHomeTest {

    private val project = Project(
        projectId = "wherevercode-agent-2",
        name = "wherevercode-agent",
        repoUrl = "https://github.com/huaerdaidai-gif/wherevercode-agent",
    )
    private val githubHome = ProjectLocation.createGithub(
        "wherevercode-agent-2",
        "huaerdaidai-gif",
        "wherevercode-agent",
    )
    private val deviceHome = ProjectLocation.create(
        "wherevercode-agent-2",
        "mac",
        "/Users/example-user/Projects/wherevercode-agent",
    )

    @Test
    fun `github home present but device home active returns null`() {
        // 2026-11-12 09:46 实证（任务 040e9317）：项目有 GitHub 家 +
        // DEVICE 家（Mac，当前家），旧口径取家的列表第一项（GitHub 家）
        // 就走了大脑路，大脑在手机本机没有 GitHub 工具、没有 git，
        // 没做成却记 completed。新口径：当前家是 DEVICE 家 → null。
        val withHomes = ProjectWithHomes(
            project,
            listOf(githubHome, deviceHome),
            deviceHome,
        )
        assertNull(activeGitHome(withHomes))
    }

    @Test
    fun `github home active returns the pair`() {
        val withHomes = ProjectWithHomes(
            project,
            listOf(githubHome, deviceHome),
            githubHome,
        )
        assertEquals(project to githubHome, activeGitHome(withHomes))
    }

    @Test
    fun `device home active with no git home at all returns null`() {
        val withHomes = ProjectWithHomes(project, listOf(deviceHome), deviceHome)
        assertNull(activeGitHome(withHomes))
    }

    @Test
    fun `no homes returns null`() {
        val withHomes = ProjectWithHomes(project, emptyList(), null)
        assertNull(activeGitHome(withHomes))
    }

    @Test
    fun `local home active returns null`() {
        val localHome = ProjectLocation.createLocal("wherevercode-agent-2", "agent-test")
        val withHomes = ProjectWithHomes(
            project,
            listOf(githubHome, localHome),
            localHome,
        )
        assertNull(activeGitHome(withHomes))
    }

    @Test
    fun `gitlab home active returns the pair`() {
        val gitlabHome = ProjectLocation.createGitHost(
            "wherevercode-agent-2",
            GitHost.GITLAB,
            "group/subgroup/project",
        )
        val withHomes = ProjectWithHomes(
            project,
            listOf(gitlabHome, deviceHome),
            gitlabHome,
        )
        assertEquals(project to gitlabHome, activeGitHome(withHomes))
    }

    @Test
    fun `active home falls back to first home when never set`() {
        // 未设过当前家时 activeHome 就是家的列表第一项（store 的口径）：
        // 第一项是 GitHub 家就返回它，第一项是 DEVICE 家就返回 null。
        val githubFirst = ProjectWithHomes(project, listOf(githubHome, deviceHome), githubHome)
        assertEquals(project to githubHome, activeGitHome(githubFirst))
        val deviceFirst = ProjectWithHomes(project, listOf(deviceHome, githubHome), deviceHome)
        assertNull(activeGitHome(deviceFirst))
    }
}
