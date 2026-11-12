package wherevercode.agent.core.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5 项目添加（2026-11-11，老项目模型收口）：Project（逻辑身份）
 * 与 ProjectLocation（位置）分开存；位置引用已注册的项目；
 * 一个项目可以有多个家（主流模型：家的列表，2026-11-11 主流补齐，
 * 旧 one-home 规则已废止）；同一文件夹只能是一个项目的家（不复制）。
 *
 * 位置 id 须与老项目 `project_locations.py` 的 `_location_id`
 * 逐字节一致（`loc-` + sha256(`project_id\0host_id\0path`)[:32]，
 * 路径经 posix 规整）：期望值用 Python 算出后写死在这里。
 * 项目 id 须合老项目 `_PROJECT_ID`（小写字母/数字开头，后跟
 * 小写字母、数字、-，最长 64 字，没有下划线）。
 */
class ProjectStoreTest {

    private fun project(
        id: String = "wherevercode-agent",
        name: String = "wherevercode-agent",
        repoUrl: String? = "https://github.com/huaerdaidai-gif/wherevercode-agent",
        branch: String = "main",
        commit: String? = null,
    ) = Project(
        projectId = id,
        name = name,
        repoUrl = repoUrl,
        defaultBranch = branch,
        canonicalCommit = commit,
    )

    private fun location(
        projectId: String = "wherevercode-agent",
        hostId: String = "remote-device",
        path: String = "/workspace/example-project",
    ) = ProjectLocation.create(projectId, hostId, path)

    @Test
    fun `valid project passes validation`() {
        assertTrue(ProjectValidation.validateProject(project()).isEmpty())
    }

    @Test
    fun `blank name is rejected`() {
        assertTrue(ProjectValidation.validateProject(project(name = "  ")).isNotEmpty())
    }

    @Test
    fun `project id with underscore is rejected like old project`() {
        assertTrue(ProjectValidation.validateProject(project(id = "my_project")).isNotEmpty())
        assertTrue(ProjectValidation.validateProject(project(id = "My-Project")).isNotEmpty())
        assertTrue(ProjectValidation.validateProject(project(id = "a".repeat(65))).isNotEmpty())
        assertTrue(ProjectValidation.validateProject(project(id = "a".repeat(64))).isEmpty())
    }

    @Test
    fun `non-github repo url is rejected`() {
        assertTrue(
            ProjectValidation.validateProject(project(repoUrl = "https://example.com/a/b")).isNotEmpty(),
        )
    }

    @Test
    fun `hf space url is accepted as project git source`() {
        assertTrue(
            ProjectValidation.validateProject(
                project(repoUrl = "https://huggingface.co/spaces/owner/my-space"),
            ).isEmpty(),
        )
    }

    @Test
    fun `hf model repo is rejected as a project home`() {
        val errors = ProjectValidation.validateProject(
            project(repoUrl = "https://huggingface.co/owner/some-model"),
        )
        assertTrue(errors.isNotEmpty())
        assertTrue(errors.any { it.contains("模型仓库") })
    }

    @Test
    fun `hf dataset repo is rejected as a project home`() {
        val errors = ProjectValidation.validateProject(
            project(repoUrl = "https://huggingface.co/datasets/owner/some-data"),
        )
        assertTrue(errors.isNotEmpty())
        assertTrue(errors.any { it.contains("数据集") })
    }

    @Test
    fun `gitlab and gitee urls are accepted now that their host types landed`() {
        // HF 分支写这条测试时 GitLab/Gitee 还没接（“暂只支持 GitHub 与 Space”）；
        // update9 里 GitLab/Gitee host 已落地，validateProject 须放行三站地址。
        assertTrue(
            ProjectValidation.validateProject(
                project(repoUrl = "https://gitlab.com/group/project"),
            ).isEmpty(),
        )
        assertTrue(
            ProjectValidation.validateProject(
                project(repoUrl = "https://gitee.com/owner/repo"),
            ).isEmpty(),
        )
    }

    @Test
    fun `repo url with credentials is rejected`() {
        assertTrue(
            ProjectValidation.validateProject(
                project(repoUrl = "https://user:token@github.com/o/r"),
            ).isNotEmpty(),
        )
    }

    @Test
    fun `default branch follows old project git branch rules`() {
        assertTrue(ProjectValidation.validateProject(project(branch = "feat/x-1")).isEmpty())
        assertTrue(ProjectValidation.validateProject(project(branch = "../etc")).isNotEmpty())
        assertTrue(ProjectValidation.validateProject(project(branch = "a//b")).isNotEmpty())
        assertTrue(ProjectValidation.validateProject(project(branch = ".hidden")).isNotEmpty())
        assertTrue(ProjectValidation.validateProject(project(branch = "x.lock")).isNotEmpty())
        assertTrue(ProjectValidation.validateProject(project(branch = "")).isNotEmpty())
    }

    @Test
    fun `canonical commit must be 40 or 64 lowercase hex or null`() {
        assertTrue(ProjectValidation.validateProject(project(commit = "a".repeat(40))).isEmpty())
        assertTrue(ProjectValidation.validateProject(project(commit = "A".repeat(40))).isNotEmpty())
        assertTrue(ProjectValidation.validateProject(project(commit = "abc")).isNotEmpty())
        assertTrue(ProjectValidation.validateProject(project(commit = null)).isEmpty())
    }

    @Test
    fun `location id matches old project _location_id byte for byte`() {
        // Python: hashlib.sha256(b'wherevercode-agent\0remote-device\0/workspace/example-project')
        assertEquals(
            "loc-4b48bcee1b8950edd6579284b3e25c0c",
            projectLocationId("wherevercode-agent", "remote-device", "/workspace/example-project"),
        )
        // 末尾 "/" 经规整后 id 不变（老项目 Path.as_posix 同样去尾斜杠）。
        assertEquals(
            "loc-4b48bcee1b8950edd6579284b3e25c0c",
            projectLocationId("wherevercode-agent", "remote-device", "/workspace/example-project/"),
        )
        assertEquals(
            "loc-f157a4f8a75f15c96ebaa1eb2e87f8a5",
            projectLocationId("a", "b", "/"),
        )
    }

    @Test
    fun `location origin follows source type like old bootstrap`() {
        val remote = location()
        assertEquals(ProjectSourceType.REMOTE_DEVICE, remote.sourceType)
        assertEquals(ProjectOrigin.REMOTE, remote.origin)
        val local = ProjectLocation.create("p", "phone", "/data/x", ProjectSourceType.LOCAL)
        assertEquals(ProjectOrigin.LOCAL, local.origin)
    }

    @Test
    fun `location id mismatch is rejected`() {
        val bad = location().copy(locationId = "loc-00000000000000000000000000000000")
        assertTrue(ProjectValidation.validateLocation(bad).isNotEmpty())
    }

    @Test
    fun `relative home folder is rejected`() {
        assertTrue(
            ProjectValidation.validateLocation(location(path = "root/work/fenshen")).isNotEmpty(),
        )
    }

    @Test
    fun `slug is ascii lowercase and chinese name falls back`() {
        assertEquals("wherevercode-agent", slugProjectId("WhereverCode-Agent"))
        assertEquals("project", slugProjectId("深度求索"))
        assertEquals("my-project", slugProjectId("My Project!"))
    }

    @Test
    fun `registering a project creates metadata only with no location`() {
        val store: ProjectStore = InMemoryProjectStore()
        store.saveProject(project())
        assertEquals(1, store.listProjects().size)
        assertTrue(store.locationsForProject("wherevercode-agent").isEmpty())
        assertTrue(store.listLocations().isEmpty())
    }

    @Test
    fun `in-memory store round trips project and location`() {
        val store: ProjectStore = InMemoryProjectStore()
        store.saveProject(project())
        store.saveLocation(location())
        assertEquals("/workspace/example-project", store.locationsForProject("wherevercode-agent")[0].workspacePath)
        assertEquals("remote-device", store.locationsForHost("remote-device")[0].hostId)
        assertEquals(1, store.listLocations().size)

        store.saveProject(project(name = "wherevercode-agent（开发）"))
        assertEquals(1, store.listProjects().size)
        assertEquals("wherevercode-agent（开发）", store.getProject("wherevercode-agent")?.name)
        // 更新逻辑项目不丢位置。
        assertEquals(1, store.locationsForProject("wherevercode-agent").size)
    }

    @Test
    fun `location requires a registered project`() {
        val store: ProjectStore = InMemoryProjectStore()
        try {
            store.saveLocation(location())
            org.junit.Assert.fail("位置引用的项目尚未注册，应拒绝")
        } catch (_: IllegalArgumentException) {
            // 预期。
        }
        assertTrue(store.listLocations().isEmpty())
    }

    @Test
    fun `multiple homes per project - second home is allowed`() {
        // 主流模型（2026-11-11）：家的列表，一个项目可以再加一个家。
        val store: ProjectStore = InMemoryProjectStore()
        store.saveProject(project())
        store.saveLocation(location())
        store.saveLocation(location(path = "/opt/dsh-projects/wherevercode-agent"))
        assertEquals(2, store.locationsForProject("wherevercode-agent").size)
    }

    @Test
    fun `same folder cannot be home for two projects`() {
        val store: ProjectStore = InMemoryProjectStore()
        store.saveProject(project())
        store.saveProject(project(id = "other", name = "other"))
        store.saveLocation(location())
        try {
            store.saveLocation(location(projectId = "other"))
            org.junit.Assert.fail("同一文件夹已是别的项目的家，应拒绝")
        } catch (_: IllegalArgumentException) {
            // 预期。
        }
        assertEquals(1, store.listLocations().size)
        assertEquals(
            "wherevercode-agent",
            store.homeOccupiedBy("remote-device", "/workspace/example-project")?.projectId,
        )
    }

    @Test
    fun `trailing slash counts as same folder for occupancy`() {
        val store: ProjectStore = InMemoryProjectStore()
        store.saveProject(project())
        store.saveProject(project(id = "other", name = "other"))
        store.saveLocation(location())
        assertTrue(store.homeOccupiedBy("remote-device", "/workspace/example-project/") != null)
        try {
            store.saveLocation(location(projectId = "other", path = "/workspace/example-project/"))
            org.junit.Assert.fail("尾斜杠不同拼写仍是同一文件夹，应拒绝")
        } catch (_: IllegalArgumentException) {
            // 预期。
        }
    }

    @Test
    fun `same folder path on a different device is allowed for another project`() {
        val store: ProjectStore = InMemoryProjectStore()
        store.saveProject(project())
        store.saveProject(project(id = "other", name = "other"))
        store.saveLocation(location())
        store.saveLocation(location(projectId = "other", hostId = "Android-x"))
        assertEquals(2, store.listLocations().size)
    }

    @Test
    fun `remove location keeps the logical project`() {
        val store: ProjectStore = InMemoryProjectStore()
        store.saveProject(project())
        store.saveLocation(location())
        val id = projectLocationId("wherevercode-agent", "remote-device", "/workspace/example-project")
        store.removeLocation(id)
        assertTrue(store.locationsForProject("wherevercode-agent").isEmpty())
        // 逻辑项目还在：浏览器仍展示它（未指定家），以后可重新指定家。
        assertEquals("wherevercode-agent", store.getProject("wherevercode-agent")?.projectId)
        store.saveLocation(location())
        assertEquals(1, store.locationsForProject("wherevercode-agent").size)
    }

    @Test
    fun `delete project removes the project and its locations`() {
        val store: ProjectStore = InMemoryProjectStore()
        store.saveProject(project())
        store.saveLocation(location())
        store.deleteProject("wherevercode-agent")
        assertTrue(store.listProjects().isEmpty())
        assertTrue(store.listLocations().isEmpty())
    }

    @Test
    fun `rename keeps the project id and home and creation time`() {
        // 改名（2026-11-11，maintainer：增删改添都要有）：同 id 保存新名，
        // id、家、创建时间都不变。
        val store: ProjectStore = InMemoryProjectStore()
        store.saveProject(project())
        store.saveLocation(location())
        val createdAt = store.getProject("wherevercode-agent")!!.createdAtEpochSeconds
        store.saveProject(project(name = "新名字"))
        val renamed = store.getProject("wherevercode-agent")
        assertEquals("新名字", renamed?.name)
        assertEquals("wherevercode-agent", renamed?.projectId)
        assertEquals(createdAt, renamed?.createdAtEpochSeconds)
        assertEquals(1, store.locationsForProject("wherevercode-agent").size)
        assertEquals("/workspace/example-project", store.locationsForProject("wherevercode-agent")[0].workspacePath)
    }

    @Test
    fun `change home removes the old home then saves the new one`() {
        // 换家（2026-11-11，maintainer：增删改添都要有）：先移旧家再存新家。
        // （多家模型下也可以不移旧家、直接加新家并设为当前家；这里
        // 测的是“移旧家再存新家”这条老路仍可用。）
        val store: ProjectStore = InMemoryProjectStore()
        store.saveProject(project())
        store.saveLocation(location())
        store.removeLocation(projectLocationId("wherevercode-agent", "remote-device", "/workspace/example-project"))
        store.saveLocation(location(hostId = "Android-x", path = "/data/data/com.termux/files/home/agy"))
        val homes = store.locationsForProject("wherevercode-agent")
        assertEquals(1, homes.size)
        assertEquals("Android-x", homes[0].hostId)
        assertEquals("/data/data/com.termux/files/home/agy", homes[0].workspacePath)
        // 旧家已空出来，别的项目可以用它当家。
        assertNull(store.homeOccupiedBy("remote-device", "/workspace/example-project"))
    }

    @Test
    fun `change home fails cleanly when the new folder is another project home`() {
        // 换家失败时旧家必须还在（调用方恢复的前提：先移旧家再存新家，
        // 存失败要能把旧家存回来）。
        val store: ProjectStore = InMemoryProjectStore()
        store.saveProject(project())
        store.saveProject(project(id = "other", name = "other"))
        store.saveLocation(location())
        store.saveLocation(location(projectId = "other", hostId = "Android-x", path = "/data/home"))
        val oldId = projectLocationId("wherevercode-agent", "remote-device", "/workspace/example-project")
        store.removeLocation(oldId)
        try {
            store.saveLocation(location(hostId = "Android-x", path = "/data/home"))
            org.junit.Assert.fail("新家已被别的项目占用，应拒绝保存")
        } catch (_: IllegalArgumentException) {
            // 预期：调用方把旧家存回来。
        }
        store.saveLocation(location())
        assertEquals(1, store.locationsForProject("wherevercode-agent").size)
        assertEquals("/workspace/example-project", store.locationsForProject("wherevercode-agent")[0].workspacePath)
    }

    @Test
    fun `in-memory store rejects invalid project and location on save`() {
        val store: ProjectStore = InMemoryProjectStore()
        try {
            store.saveProject(project(id = "bad_id"))
            org.junit.Assert.fail("非法项目应拒绝保存")
        } catch (_: IllegalArgumentException) {
            // 预期。
        }
        store.saveProject(project())
        try {
            store.saveLocation(location(path = "relative/path"))
            org.junit.Assert.fail("非法位置应拒绝保存")
        } catch (_: IllegalArgumentException) {
            // 预期。
        }
        assertTrue(store.listLocations().isEmpty())
    }

    @Test
    fun `folder basename is used as default project name`() {
        assertEquals("fenshen", folderBasename("/workspace/example-project"))
        assertEquals("/", folderBasename("/"))
        assertFalse(folderBasename("/workspace/example-project").isBlank())
    }

    // -- GitHub 家（2026-11-11，GitHub 作家：家可以是 GitHub 仓库本身） --

    @Test
    fun `createGithub builds a github home location`() {
        val home = ProjectLocation.createGithub(
            "wherevercode-agent",
            "huaerdaidai-gif",
            "wherevercode-agent",
        )
        assertEquals(ProjectHomeKind.GITHUB, home.homeKind)
        assertEquals("github", home.hostId)
        assertEquals("huaerdaidai-gif/wherevercode-agent", home.workspacePath)
        assertEquals(ProjectSourceType.CLOUD, home.sourceType)
        assertEquals(ProjectOrigin.CLOUD, home.origin)
        assertEquals("huaerdaidai-gif/wherevercode-agent", home.githubRepo())
        assertTrue(ProjectValidation.validateLocation(home).isEmpty())
    }

    @Test
    fun `device home has no github repo`() {
        assertNull(location().githubRepo())
        assertEquals(ProjectHomeKind.DEVICE, location().homeKind)
    }

    @Test
    fun `github home with wrong host id is rejected`() {
        val home = ProjectLocation.createGithub("wherevercode-agent", "o", "r")
            .copy(hostId = "remote-device")
        assertTrue(ProjectValidation.validateLocation(home).isNotEmpty())
    }

    @Test
    fun `github home with bad repo path is rejected`() {
        val badPath = ProjectLocation.createGithub("wherevercode-agent", "o", "r")
            .copy(workspacePath = "/workspace/example-project")
        assertTrue(ProjectValidation.validateLocation(badPath).isNotEmpty())
        val badPath2 = ProjectLocation.createGithub("wherevercode-agent", "o", "r")
            .copy(workspacePath = "only-owner")
        assertTrue(ProjectValidation.validateLocation(badPath2).isNotEmpty())
    }

    @Test
    fun `github home with device source type is rejected`() {
        val home = ProjectLocation.createGithub("wherevercode-agent", "o", "r")
            .copy(sourceType = ProjectSourceType.REMOTE_DEVICE, origin = ProjectOrigin.REMOTE)
        assertTrue(ProjectValidation.validateLocation(home).isNotEmpty())
    }

    @Test
    fun `multiple homes - device home plus github home are both allowed`() {
        // 主流模型（2026-11-11）：设备家 + GitHub 家可以并存。
        val store: ProjectStore = InMemoryProjectStore()
        store.saveProject(project())
        store.saveLocation(location())
        store.saveLocation(
            ProjectLocation.createGithub("wherevercode-agent", "o", "r"),
        )
        assertEquals(2, store.locationsForProject("wherevercode-agent").size)
    }

    @Test
    fun `multiple homes - github home plus device home are both allowed`() {
        val store: ProjectStore = InMemoryProjectStore()
        store.saveProject(project())
        store.saveLocation(
            ProjectLocation.createGithub("wherevercode-agent", "o", "r"),
        )
        store.saveLocation(location())
        assertEquals(2, store.locationsForProject("wherevercode-agent").size)
    }

    @Test
    fun `active home defaults to first home and can be switched`() {
        // 主流模型（2026-11-11）：当前家默认第一个；切换只改当前家，不删家。
        val store: ProjectStore = InMemoryProjectStore()
        store.saveProject(project())
        val deviceHome = location()
        val githubHome = ProjectLocation.createGithub("wherevercode-agent", "o", "r")
        store.saveLocation(deviceHome)
        store.saveLocation(githubHome)
        assertEquals(deviceHome.locationId, store.getActiveHome("wherevercode-agent")?.locationId)
        store.setActiveHome("wherevercode-agent", githubHome.locationId)
        assertEquals(githubHome.locationId, store.getActiveHome("wherevercode-agent")?.locationId)
        // 切完家，两个家都还在。
        assertEquals(2, store.locationsForProject("wherevercode-agent").size)
    }

    @Test
    fun `projectWithHomes returns project plus homes plus active home`() {
        val store: ProjectStore = InMemoryProjectStore()
        store.saveProject(project())
        assertNull(store.projectWithHomes("missing"))
        store.saveLocation(location())
        val withHomes = store.projectWithHomes("wherevercode-agent")
        assertEquals("wherevercode-agent", withHomes?.project?.projectId)
        assertEquals(1, withHomes?.homes?.size)
        assertEquals("/workspace/example-project", withHomes?.activeHome?.workspacePath)
        assertNull(store.getActiveHome("missing"))
    }

    @Test
    fun `setActiveHome rejects a location that is not this project's home`() {
        val store: ProjectStore = InMemoryProjectStore()
        store.saveProject(project())
        store.saveProject(project(id = "other", name = "other"))
        store.saveLocation(location())
        store.saveLocation(location(projectId = "other", hostId = "Android-x"))
        val foreignId = projectLocationId("other", "Android-x", "/workspace/example-project")
        try {
            store.setActiveHome("wherevercode-agent", foreignId)
            org.junit.Assert.fail("别项目的家不能设为本项目的当前家，应拒绝")
        } catch (_: IllegalArgumentException) {
            // 预期。
        }
    }

    @Test
    fun `removing the active home falls back to the first remaining home`() {
        val store: ProjectStore = InMemoryProjectStore()
        store.saveProject(project())
        val deviceHome = location()
        val githubHome = ProjectLocation.createGithub("wherevercode-agent", "o", "r")
        store.saveLocation(deviceHome)
        store.saveLocation(githubHome)
        store.setActiveHome("wherevercode-agent", githubHome.locationId)
        store.removeLocation(githubHome.locationId)
        assertEquals(deviceHome.locationId, store.getActiveHome("wherevercode-agent")?.locationId)
        // 家删光了，当前家为 null。
        store.removeLocation(deviceHome.locationId)
        assertNull(store.getActiveHome("wherevercode-agent"))
    }

    @Test
    fun `same github repo cannot be home for two projects`() {
        val store: ProjectStore = InMemoryProjectStore()
        store.saveProject(project())
        store.saveProject(project(id = "other", name = "other"))
        store.saveLocation(
            ProjectLocation.createGithub("wherevercode-agent", "o", "r"),
        )
        try {
            store.saveLocation(ProjectLocation.createGithub("other", "o", "r"))
            org.junit.Assert.fail("同一 GitHub 仓库已是别的项目的家，应拒绝")
        } catch (_: IllegalArgumentException) {
            // 预期。
        }
        assertEquals(1, store.listLocations().size)
    }

    @Test
    fun `github home round trips in memory store`() {
        val store: ProjectStore = InMemoryProjectStore()
        store.saveProject(project())
        val home = ProjectLocation.createGithub(
            "wherevercode-agent",
            "huaerdaidai-gif",
            "wherevercode-agent",
        )
        store.saveLocation(home)
        val saved = store.locationsForProject("wherevercode-agent")[0]
        assertEquals(ProjectHomeKind.GITHUB, saved.homeKind)
        assertEquals("huaerdaidai-gif/wherevercode-agent", saved.workspacePath)
        assertEquals("github", saved.hostId)
        // 删了家还能重新指定 GitHub 家。
        store.removeLocation(saved.locationId)
        store.saveLocation(home)
        assertEquals(1, store.locationsForProject("wherevercode-agent").size)
    }

    @Test
    fun `parseGitHostRepoUrl accepts https links and rejects credentials`() {
        val github = parseGitHostRepoUrl("https://github.com/o/r")
        assertEquals(GitHost.GITHUB, github?.host)
        assertEquals("o/r", github?.namespacePath)
        assertEquals("o/r", parseGitHostRepoUrl("https://github.com/o/r.git")?.namespacePath)
        assertEquals("o/r", parseGitHostRepoUrl("https://github.com/o/r/")?.namespacePath)
        val gitlab = parseGitHostRepoUrl("https://gitlab.com/group/sub/my-proj")
        assertEquals(GitHost.GITLAB, gitlab?.host)
        assertEquals("group/sub/my-proj", gitlab?.namespacePath)
        assertEquals(GitHost.GITEE, parseGitHostRepoUrl("https://gitee.com/o/r")?.host)
        assertNull(parseGitHostRepoUrl("https://user:token@github.com/o/r"))
        assertNull(parseGitHostRepoUrl("https://example.com/o/r"))
        assertNull(parseGitHostRepoUrl("git@github.com:o/r.git"))
        assertNull(parseGitHostRepoUrl("https://github.com/only-owner"))
    }

    // -- 无家项目指定 GitHub 家（2026-11-11，来源选择器 GitHub 路径：
    // 没仓库地址也能进 GitHub，先把地址存回项目上再设家） --

    @Test
    fun `homeless project can be given a github home after saving repo url`() {
        val store: ProjectStore = InMemoryProjectStore()
        store.saveProject(project(repoUrl = null))
        assertTrue(store.locationsForProject("wherevercode-agent").isEmpty())
        val url = "https://github.com/huaerdaidai-gif/wherevercode-agent"
        val updated = store.getProject("wherevercode-agent")!!.copy(repoUrl = url)
        store.saveProject(updated)
        assertEquals(url, store.getProject("wherevercode-agent")?.repoUrl)
        store.saveLocation(
            ProjectLocation.createGithub(
                "wherevercode-agent",
                "huaerdaidai-gif",
                "wherevercode-agent",
            ),
        )
        val saved = store.locationsForProject("wherevercode-agent")[0]
        assertEquals(ProjectHomeKind.GITHUB, saved.homeKind)
        assertEquals("huaerdaidai-gif/wherevercode-agent", saved.workspacePath)
    }

    @Test
    fun `register with github url can set github home directly`() {
        val store: ProjectStore = InMemoryProjectStore()
        store.saveProject(project())
        store.saveLocation(
            ProjectLocation.createGithub(
                "wherevercode-agent",
                "huaerdaidai-gif",
                "wherevercode-agent",
            ),
        )
        val saved = store.locationsForProject("wherevercode-agent")[0]
        assertEquals(ProjectHomeKind.GITHUB, saved.homeKind)
        assertEquals("huaerdaidai-gif/wherevercode-agent", saved.githubRepo())
    }

    // -- 本机家（2026-11-11，来源选择器：本机文件夹是一级来源） --

    @Test
    fun `createLocal builds a local home location`() {
        val home = ProjectLocation.createLocal("wherevercode-agent", "my-project")
        assertEquals(ProjectHomeKind.LOCAL, home.homeKind)
        assertEquals("local", home.hostId)
        assertEquals("my-project", home.workspacePath)
        assertEquals(ProjectSourceType.LOCAL, home.sourceType)
        assertEquals(ProjectOrigin.LOCAL, home.origin)
        assertEquals("my-project", home.localFolder())
        assertNull(home.githubRepo())
        assertTrue(ProjectValidation.validateLocation(home).isEmpty())
    }

    @Test
    fun `device and github homes have no local folder`() {
        assertNull(location().localFolder())
        assertNull(ProjectLocation.createGithub("wherevercode-agent", "o", "r").localFolder())
    }

    @Test
    fun `local home with wrong host id is rejected`() {
        val home = ProjectLocation.createLocal("wherevercode-agent", "my-project")
            .copy(hostId = "remote-device")
        assertTrue(ProjectValidation.validateLocation(home).isNotEmpty())
    }

    @Test
    fun `local home with absolute or escaping path is rejected`() {
        assertTrue(
            ProjectValidation.validateLocation(
                ProjectLocation.createLocal("wherevercode-agent", "/workspace/example-project"),
            ).isNotEmpty(),
        )
        assertTrue(
            ProjectValidation.validateLocation(
                ProjectLocation.createLocal("wherevercode-agent", "../outside"),
            ).isNotEmpty(),
        )
        assertTrue(
            ProjectValidation.validateLocation(
                ProjectLocation.createLocal("wherevercode-agent", ""),
            ).isNotEmpty(),
        )
    }

    @Test
    fun `local home with device source type is rejected`() {
        val home = ProjectLocation.createLocal("wherevercode-agent", "my-project")
            .copy(sourceType = ProjectSourceType.REMOTE_DEVICE, origin = ProjectOrigin.REMOTE)
        assertTrue(ProjectValidation.validateLocation(home).isNotEmpty())
    }

    @Test
    fun `isValidLocalWorkspacePath accepts relative paths only`() {
        assertTrue(isValidLocalWorkspacePath("my-project"))
        assertTrue(isValidLocalWorkspacePath("a/b"))
        assertFalse(isValidLocalWorkspacePath(""))
        assertFalse(isValidLocalWorkspacePath("  "))
        assertFalse(isValidLocalWorkspacePath("/my-project"))
        assertFalse(isValidLocalWorkspacePath("../outside"))
        assertFalse(isValidLocalWorkspacePath("a/../b"))
        assertFalse(isValidLocalWorkspacePath("a//b"))
        assertFalse(isValidLocalWorkspacePath("a/./b"))
        assertFalse(isValidLocalWorkspacePath("a\\b"))
    }

    @Test
    fun `multiple homes - local home then github home are both kept`() {
        // 主流模型（2026-11-11）：多家允许，旧 one-home 拒绝规则已废。
        // 原测试（update6 前）断言第二家被拒，合包时按主流模型改写。
        val store: ProjectStore = InMemoryProjectStore()
        store.saveProject(project())
        val localHome = ProjectLocation.createLocal("wherevercode-agent", "my-project")
        store.saveLocation(localHome)
        val githubHome = ProjectLocation.createGithub("wherevercode-agent", "o", "r")
        store.saveLocation(githubHome)
        assertEquals(2, store.locationsForProject("wherevercode-agent").size)
        // 当前家默认第一个（本机家）；切到 GitHub 家后两个家都还在。
        assertEquals(ProjectHomeKind.LOCAL, store.getActiveHome("wherevercode-agent")?.homeKind)
        store.setActiveHome("wherevercode-agent", githubHome.locationId)
        assertEquals(ProjectHomeKind.GITHUB, store.getActiveHome("wherevercode-agent")?.homeKind)
        assertEquals(2, store.locationsForProject("wherevercode-agent").size)
    }

    @Test
    fun `multiple homes - github home then local home are both kept`() {
        // 主流模型（2026-11-11）：多家允许，旧 one-home 拒绝规则已废。
        val store: ProjectStore = InMemoryProjectStore()
        store.saveProject(project())
        val githubHome = ProjectLocation.createGithub("wherevercode-agent", "o", "r")
        store.saveLocation(githubHome)
        val localHome = ProjectLocation.createLocal("wherevercode-agent", "my-project")
        store.saveLocation(localHome)
        assertEquals(2, store.locationsForProject("wherevercode-agent").size)
        // 当前家默认第一个（GitHub 家）；切到本机家后两个家都还在。
        assertEquals(ProjectHomeKind.GITHUB, store.getActiveHome("wherevercode-agent")?.homeKind)
        store.setActiveHome("wherevercode-agent", localHome.locationId)
        assertEquals(ProjectHomeKind.LOCAL, store.getActiveHome("wherevercode-agent")?.homeKind)
        assertEquals(2, store.locationsForProject("wherevercode-agent").size)
    }

    @Test
    fun `local home round trips in memory store`() {
        val store: ProjectStore = InMemoryProjectStore()
        store.saveProject(project())
        val home = ProjectLocation.createLocal("wherevercode-agent", "my-project")
        store.saveLocation(home)
        val saved = store.locationsForProject("wherevercode-agent")[0]
        assertEquals(ProjectHomeKind.LOCAL, saved.homeKind)
        assertEquals("my-project", saved.workspacePath)
        assertEquals("local", saved.hostId)
        // 删了家还能重新指定本机家。
        store.removeLocation(saved.locationId)
        store.saveLocation(home)
        assertEquals(1, store.locationsForProject("wherevercode-agent").size)
    }
}
