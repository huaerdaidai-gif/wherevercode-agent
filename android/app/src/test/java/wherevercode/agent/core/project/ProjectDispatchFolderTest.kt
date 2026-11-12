package wherevercode.agent.core.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.brain.BrainKind
import wherevercode.agent.core.agent.AgentAccess
import wherevercode.agent.core.agent.AgentKind
import wherevercode.agent.core.agent.AgentRecord
import wherevercode.agent.core.security.PairingRecord
import wherevercode.agent.core.security.PairingState
import wherevercode.agent.core.task.TaskPacket
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.team.resolveRemoteTargetFromRecords
import wherevercode.agent.core.transport.RemoteDevice

/**
 * V5 跨设备开发（2026-11-12）：任务/项目文件夹解析单测。
 *
 * 真机用例（MAP §2.3）：项目 wherevercode-agent 在 Mac
 * （deviceId "mac"）上有 DEVICE 家检出
 * `/Users/example-user/Projects/wherevercode-agent`，而 Mac 上 Codex
 * Agent 自己的文件夹是 `/Users/example-user/v5-codex-work`——派活的
 * cwd 必须是前者，不再是后者。
 *
 * 新口径（2026-11-12，D7）：项目家是 git 家且目标设备上无 DEVICE
 * 检出时不可派（[DispatchFolderResult.NotDispatchable]），不回落、
 * 不派到空目录；目标设备上有任何 DEVICE 检出时仍派（哪怕当前家
 * 已被切到 git 家，代码仍在设备上）。
 */
class ProjectDispatchFolderTest {

    private fun storeWith(
        projectId: String,
        projectName: String,
        vararg locations: ProjectLocation,
    ): InMemoryProjectStore {
        val store = InMemoryProjectStore()
        store.saveProject(Project(projectId = projectId, name = projectName))
        locations.forEach { store.saveLocation(it) }
        return store
    }

    private fun codexMac(folder: String? = "/Users/example-user/v5-codex-work") =
        AgentRecord(
            agentId = "codex-mac",
            displayName = "Codex Mac",
            kind = AgentKind.DEVICE,
            access = AgentAccess.CLI,
            brainKind = BrainKind.AGENT_RUNTIME,
            adapterId = "codex",
            binary = "codex",
            deviceId = "mac",
            deviceFolder = folder,
        )

    private fun task(projectId: String?) =
        TaskRecord(taskId = "task-1", projectId = projectId, state = "queued")

    private fun resolvedFolder(result: DispatchFolderResult?): DispatchFolder? =
        (result as? DispatchFolderResult.Resolved)?.folder

    private fun assertResolved(result: DispatchFolderResult?, folder: String, source: DispatchFolderSource) {
        val resolved = resolvedFolder(result)
        assertEquals(folder, resolved?.folder)
        assertEquals(source, resolved?.source)
    }

    @Test
    fun `DEVICE home on the target device wins over the agent folder`() {
        val store = storeWith(
            "wherevercode-agent",
            "WhereverCode Agent",
            ProjectLocation.create(
                "wherevercode-agent",
                "mac",
                "/Users/example-user/Projects/wherevercode-agent",
            ),
        )
        assertResolved(
            resolveDispatchFolder(store, task("wherevercode-agent"), codexMac()),
            "/Users/example-user/Projects/wherevercode-agent",
            DispatchFolderSource.PROJECT,
        )
    }

    @Test
    fun `git home with no DEVICE checkout on the target device is not dispatchable`() {
        val store = storeWith(
            "wherevercode-agent",
            "WhereverCode Agent",
            ProjectLocation.createGithub("wherevercode-agent", "huaerdaidai-gif", "wherevercode-agent"),
        )
        val result = resolveDispatchFolder(store, task("wherevercode-agent"), codexMac())
        assertTrue("git 家且无 DEVICE 检出时应为 NotDispatchable", result is DispatchFolderResult.NotDispatchable)
        val reason = (result as DispatchFolderResult.NotDispatchable).reason
        assertTrue(reason.contains("WhereverCode Agent"))
        assertTrue(reason.contains("mac"))
        assertTrue(reason.contains("代码不在设备上"))
        assertTrue("原因须写清 git 家地址", reason.contains("huaerdaidai-gif/wherevercode-agent"))
        assertTrue("不可派时 dispatchBlockedReason 应返回同一原因", dispatchBlockedReason(store, task("wherevercode-agent"), codexMac()) == reason)
    }

    @Test
    fun `git home with a DEVICE checkout on another device is still not dispatchable`() {
        val store = storeWith(
            "wherevercode-agent",
            "WhereverCode Agent",
            ProjectLocation.createGithub("wherevercode-agent", "huaerdaidai-gif", "wherevercode-agent"),
            ProjectLocation.create(
                "wherevercode-agent",
                "remote-device",
                "/workspace/wherevercode-agent",
            ),
        )
        val result = resolveDispatchFolder(store, task("wherevercode-agent"), codexMac())
        assertTrue(result is DispatchFolderResult.NotDispatchable)
    }

    @Test
    fun `git active home but a DEVICE checkout still on the target device dispatches to it`() {
        // 当前家是 git 家（先存，先取为当前家），但目标设备上仍有
        // DEVICE 家检出：代码在设备上，不拦。
        val store = storeWith(
            "wherevercode-agent",
            "WhereverCode Agent",
            ProjectLocation.createGithub("wherevercode-agent", "huaerdaidai-gif", "wherevercode-agent"),
            ProjectLocation.create(
                "wherevercode-agent",
                "mac",
                "/Users/example-user/Projects/wherevercode-agent",
            ),
        )
        assertResolved(
            resolveDispatchFolder(store, task("wherevercode-agent"), codexMac()),
            "/Users/example-user/Projects/wherevercode-agent",
            DispatchFolderSource.PROJECT,
        )
        assertNull(dispatchBlockedReason(store, task("wherevercode-agent"), codexMac()))
    }

    @Test
    fun `unbound task falls back to the agent folder`() {
        val store = storeWith("wherevercode-agent", "WhereverCode Agent")
        assertResolved(
            resolveDispatchFolder(store, task(null), codexMac()),
            "/Users/example-user/v5-codex-work",
            DispatchFolderSource.AGENT_FALLBACK,
        )
        assertNull(dispatchBlockedReason(store, task(null), codexMac()))
    }

    @Test
    fun `DEVICE home on another device does not apply`() {
        val store = storeWith(
            "wherevercode-agent",
            "WhereverCode Agent",
            ProjectLocation.create(
                "wherevercode-agent",
                "mac",
                "/Users/example-user/Projects/wherevercode-agent",
            ),
        )
        val agentOnVps = codexMac().copy(deviceId = "remote-device")
        assertResolved(
            resolveDispatchFolder(store, task("wherevercode-agent"), agentOnVps),
            "/Users/example-user/v5-codex-work",
            DispatchFolderSource.AGENT_FALLBACK,
        )
    }

    @Test
    fun `no project home and no agent folder resolves to null`() {
        val store = storeWith("wherevercode-agent", "WhereverCode Agent")
        assertNull(resolveDispatchFolder(store, task(null), codexMac(folder = null)))
        assertNull(resolveDispatchFolder(store, task(null), codexMac(folder = "  ")))
    }

    @Test
    fun `project folder does not need the agent folder to exist`() {
        val store = storeWith(
            "wherevercode-agent",
            "WhereverCode Agent",
            ProjectLocation.create(
                "wherevercode-agent",
                "mac",
                "/Users/example-user/Projects/wherevercode-agent",
            ),
        )
        assertResolved(
            resolveDispatchFolder(store, task("wherevercode-agent"), codexMac(folder = null)),
            "/Users/example-user/Projects/wherevercode-agent",
            DispatchFolderSource.PROJECT,
        )
    }

    @Test
    fun `LOCAL home is not a folder on the target device`() {
        val store = storeWith(
            "wherevercode-agent",
            "WhereverCode Agent",
            ProjectLocation.createLocal("wherevercode-agent", "wherevercode-agent"),
        )
        assertResolved(
            resolveDispatchFolder(store, task("wherevercode-agent"), codexMac()),
            "/Users/example-user/v5-codex-work",
            DispatchFolderSource.AGENT_FALLBACK,
        )
    }

    @Test
    fun `remote target carries the project folder and its source`() {
        val fingerprint = "bb".repeat(32)
        val device = RemoteDevice(
            deviceId = "mac",
            displayName = "Mac",
            host = "192.168.1.4",
            port = 18743,
            addedAtEpochSeconds = 1_794_334_408L,
        )
        val pairing = PairingRecord(
            deviceId = "mac",
            displayName = "Mac",
            fingerprintSha256 = fingerprint,
            peerPublicKey = ByteArray(32) { 7 },
            state = PairingState.CONFIRMED,
            updatedAtEpochSeconds = 1_794_334_408L,
        )
        val store = storeWith(
            "wherevercode-agent",
            "WhereverCode Agent",
            ProjectLocation.create(
                "wherevercode-agent",
                "mac",
                "/Users/example-user/Projects/wherevercode-agent",
            ),
        )
        val override = resolvedFolder(resolveDispatchFolder(store, task("wherevercode-agent"), codexMac()))
        val target = resolveRemoteTargetFromRecords(codexMac(), device, pairing, override)
        assertEquals("/Users/example-user/Projects/wherevercode-agent", target?.folder)
        assertEquals(DispatchFolderSource.PROJECT, target?.folderSource)

        val fallback = resolveRemoteTargetFromRecords(codexMac(), device, pairing)
        assertEquals("/Users/example-user/v5-codex-work", fallback?.folder)
        assertEquals(DispatchFolderSource.AGENT_FALLBACK, fallback?.folderSource)
    }

    @Test
    fun `fallback reason names the project device and agent folder`() {
        val reason = folderFallbackReason(
            "WhereverCode Agent",
            "mac",
            "/Users/example-user/v5-codex-work",
        )
        assertTrue(reason.contains("WhereverCode Agent"))
        assertTrue(reason.contains("mac"))
        assertTrue(reason.contains("/Users/example-user/v5-codex-work"))
    }

    @Test
    fun `fallback reason for an unbound task says so instead of inventing a project`() {
        val reason = folderFallbackReason(null, "mac", "/Users/example-user/v5-codex-work")
        assertTrue(reason.contains("未绑定项目"))
        assertTrue(reason.contains("mac"))
        assertTrue(reason.contains("/Users/example-user/v5-codex-work"))
    }

    @Test
    fun `resolved reason names the project device and checkout folder`() {
        val reason = folderResolvedReason(
            "WhereverCode Agent",
            "mac",
            "/Users/example-user/Projects/wherevercode-agent",
        )
        assertTrue(reason.contains("WhereverCode Agent"))
        assertTrue(reason.contains("mac"))
        assertTrue(reason.contains("/Users/example-user/Projects/wherevercode-agent"))
        assertTrue(reason.contains("使用检出文件夹"))
    }

    @Test
    fun `task packet carries the working folder without a version bump`() {
        val packet = TaskPacket.fromTask(
            task("wherevercode-agent"),
            folder = "/Users/example-user/Projects/wherevercode-agent",
            folderSource = "project",
        )
        assertEquals(1, packet.version)
        assertEquals("/Users/example-user/Projects/wherevercode-agent", packet.folder)
        val restored = TaskPacket.fromJson(packet.toJson())
        assertEquals(1, restored.version)
        assertEquals("/Users/example-user/Projects/wherevercode-agent", restored.folder)
        assertEquals("project", restored.folderSource)
        assertTrue(restored.toPromptText().contains("工作目录：/Users/example-user/Projects/wherevercode-agent"))
        assertTrue(restored.toPromptText().contains("项目检出"))
    }

    @Test
    fun `task packet without a folder renders no working directory line`() {
        val packet = TaskPacket.fromTask(task(null))
        assertEquals(1, packet.version)
        assertNull(packet.folder)
        assertTrue(!packet.toPromptText().contains("工作目录"))
    }
}
