package wherevercode.agent.core.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.brain.BrainKind
import wherevercode.agent.core.agent.AgentAccess
import wherevercode.agent.core.agent.AgentKind
import wherevercode.agent.core.agent.AgentRecord
import wherevercode.agent.core.security.PairingRecord
import wherevercode.agent.core.security.PairingState
import wherevercode.agent.core.transport.HostCapabilities
import wherevercode.agent.core.transport.HostProtocolCapabilities
import wherevercode.agent.core.transport.RemoteDevice
import wherevercode.agent.core.transport.handServerCapabilities
import wherevercode.agent.core.transport.handServerProtocolCapabilities

/**
 * V5 项目添加（2026-11-11，老项目模型收口）：Host 能力位与设备上的
 * 项目列表（host project listing，Project Browser 消费的就是它）。
 */
class HostProjectTest {

    private fun agent(
        agentId: String,
        displayName: String,
        deviceId: String,
        folder: String?,
    ) = AgentRecord(
        agentId = agentId,
        displayName = displayName,
        kind = AgentKind.DEVICE,
        access = AgentAccess.CLI,
        brainKind = BrainKind.AGENT_RUNTIME,
        adapterId = agentId,
        deviceId = deviceId,
        deviceFolder = folder,
    )

    @Test
    fun `capabilities default to all off - fail closed`() {
        val caps = HostCapabilities()
        assertFalse(caps.projectHost)
        assertFalse(caps.executionHost)
        assertFalse(caps.workspaceRead)
        assertFalse(caps.workspaceWrite)
        assertFalse(caps.taskExecution)
        assertFalse(caps.controlPlane)
    }

    @Test
    fun `hand server capabilities match what the server actually serves`() {
        val caps = handServerCapabilities()
        assertTrue(caps.projectHost)
        assertTrue(caps.executionHost)
        assertTrue(caps.workspaceRead)
        assertTrue(caps.workspaceWrite)
        assertTrue(caps.taskExecution)
        // 不是控制面，不建项目，不发 collab，不拉 git 基线。
        assertFalse(caps.controlPlane)
        assertFalse(caps.projectCreate)
        assertFalse(caps.collabPublish)
        assertFalse(caps.gitFetchBase)
    }

    @Test
    fun `hand server protocol capabilities only claim what exists`() {
        val protocol = handServerProtocolCapabilities()
        assertTrue(protocol.contains("open_project_root"))
        assertTrue(protocol.contains("task_pause_resume"))
        assertTrue(protocol.contains("task_steer"))
        assertTrue(protocol.contains("assistant_stream"))
        assertFalse(protocol.contains("roots_hot_reload"))
        assertFalse(protocol.contains("conversation_resume"))
        assertTrue(protocol.all { it in HostProtocolCapabilities.ALL })
    }

    @Test
    fun `protocol capability validation drops unknown names`() {
        val valid = HostProtocolCapabilities.valid(listOf("open_project_root", "bogus", "task_steer"))
        assertEquals(setOf("open_project_root", "task_steer"), valid)
    }

    @Test
    fun `capabilities round trip through json`() {
        val caps = handServerCapabilities()
        val restored = HostCapabilities.fromJson(caps.toJson())
        assertEquals(caps, restored)
        assertEquals(HostCapabilities(), HostCapabilities.fromJson(null))
    }

    @Test
    fun `remote folder picker puts current device home first and marks it`() {
        // 2026-11-12 实证：wherevercode-agent 的 DEVICE 家已是 Mac
        // /Users/example-user/Projects/wherevercode-agent，弹框却只列三个 Agent
        // 文件夹。当前家必须置顶、标出，不再被 Agent 文件夹淹没。
        val listing = remoteFolderPickerListing(
            "mac",
            listOf(
                agent("claude", "Claude", "mac", "/Users/example-user/v5-claude-work"),
                agent("codex", "Codex", "mac", "/Users/example-user/v5-codex-work"),
                agent("opencode", "OpenCode", "mac", "/Users/example-user/v5-opencode-work"),
            ),
            currentHomeFolder = "/Users/example-user/Projects/wherevercode-agent",
        )
        assertEquals(4, listing.entries.size)
        val home = listing.entries[0]
        assertEquals("/Users/example-user/Projects/wherevercode-agent", home.folder)
        assertTrue(home.isCurrentHome)
        assertTrue(home.isProjectHome)
        // 其余是 Agent 文件夹，按显示名排序，不带家标记。
        assertEquals(listOf("Claude", "Codex", "OpenCode"), listing.entries.drop(1).map { it.displayName })
        assertTrue(listing.entries.drop(1).none { it.isCurrentHome || it.isProjectHome })
    }

    @Test
    fun `remote folder picker lists other own homes after current home`() {
        val listing = remoteFolderPickerListing(
            "mac",
            listOf(agent("codex", "Codex", "mac", "/Users/example-user/v5-codex-work")),
            currentHomeFolder = "/Users/example-user/Projects/wherevercode-agent",
            otherHomeFolders = listOf("/Users/example-user/Projects/other-checkout", "/Users/example-user/Projects/aaa-checkout"),
        )
        assertEquals(
            listOf(
                "/Users/example-user/Projects/wherevercode-agent",
                "/Users/example-user/Projects/aaa-checkout",
                "/Users/example-user/Projects/other-checkout",
                "/Users/example-user/v5-codex-work",
            ),
            listing.entries.map { it.folder },
        )
        assertTrue(listing.entries[0].isCurrentHome)
        assertTrue(listing.entries[1].isProjectHome)
        assertTrue(listing.entries[2].isProjectHome)
        assertFalse(listing.entries[1].isCurrentHome)
        assertFalse(listing.entries[3].isProjectHome)
    }

    @Test
    fun `remote folder picker without a home is just the agent folders`() {
        // 新建项目：没有 DEVICE 家，候选集与老 hostProjectListing 一致。
        val agents = listOf(
            agent("codex", "Codex", "mac", "/Users/example-user/v5-codex-work"),
            agent("claude", "Claude", "mac", "/Users/example-user/v5-claude-work"),
        )
        val listing = remoteFolderPickerListing("mac", agents, currentHomeFolder = null)
        assertEquals(
            hostProjectListing("mac", agents).entries.map { it.folder },
            listing.entries.map { it.folder },
        )
        assertTrue(listing.entries.none { it.isCurrentHome || it.isProjectHome })
    }

    @Test
    fun `remote folder picker dedupes an agent folder equal to the home`() {
        // Agent 文件夹与项目家是同一路径时只出现一次，算作家、不算 Agent 文件夹。
        val listing = remoteFolderPickerListing(
            "mac",
            listOf(
                agent("codex", "Codex", "mac", "/Users/example-user/Projects/wherevercode-agent/"),
                agent("claude", "Claude", "mac", "/Users/example-user/v5-claude-work"),
            ),
            currentHomeFolder = "/Users/example-user/Projects/wherevercode-agent",
        )
        assertEquals(2, listing.entries.size)
        assertEquals("/Users/example-user/Projects/wherevercode-agent", listing.entries[0].folder)
        assertTrue(listing.entries[0].isCurrentHome)
        assertEquals("Claude", listing.entries[1].displayName)
    }

    @Test
    fun `remote folder picker is empty when no home and no agent folders`() {
        val listing = remoteFolderPickerListing("mac", emptyList(), currentHomeFolder = null)
        assertTrue(listing.entries.isEmpty())
    }

    @Test
    fun `host project listing is built from hello agent folders`() {
        val listing = hostProjectListing(
            "remote-device",
            listOf(
                agent("example-agent", "Example Agent", "remote-device", "/workspace/example-project"),
                agent("codex", "Codex", "remote-device", "/home/example-user/.codex"),
                agent("manus", "Manus", "remote-device", null),
                agent("agy", "agy", "Android-x", "/data/data/com.termux/files/home"),
            ),
        )
        assertEquals("remote-device", listing.hostId)
        assertEquals(2, listing.entries.size)
        // 按显示名排序：Codex 在前，Example Agent 在后。
        assertEquals("codex", listing.entries[0].agentId)
        assertEquals("/home/example-user/.codex", listing.entries[0].folder)
        assertEquals("example-agent", listing.entries[1].agentId)
        assertEquals("/workspace/example-project", listing.entries[1].folder)
        // 没有文件夹的 Agent 不进列表；别台设备的 Agent 也不进。
        assertTrue(listing.entries.none { it.agentId == "manus" })
        assertTrue(listing.entries.none { it.agentId == "agy" })
    }

    @Test
    fun `host project listing is empty when the host reports no folders`() {
        val listing = hostProjectListing("remote-device", emptyList())
        assertTrue(listing.entries.isEmpty())
    }

    @Test
    fun `device project sources are built from routable agent records`() {
        // 项目页“设备上的项目来源”一节（V5，2026-11-11）：已配对设备上
        // 各 Agent 的工作文件夹，点开即浏览，不用先注册项目。
        val devices = listOf(
            RemoteDevice(deviceId = "remote-device", displayName = "远程设备", host = "192.0.2.10", port = 18743),
            RemoteDevice(deviceId = "mac", displayName = "Mac", host = "192.168.1.4", port = 18743),
        )
        val sources = deviceProjectSources(
            devices,
            listOf(
                agent("example-agent", "Example Agent", "remote-device", "/workspace/example-project"),
                agent("codex", "Codex", "mac", "/Users/example-user"),
                agent("manus", "Manus", "remote-device", null),
            ),
        )
        assertEquals(2, sources.size)
        // 按设备显示名、再按 Agent 显示名排序：Mac 的 Codex 在前。
        assertEquals("mac", sources[0].deviceId)
        assertEquals("Codex", sources[0].agentDisplayName)
        assertEquals("/Users/example-user", sources[0].folder)
        assertEquals("remote-device", sources[1].deviceId)
        assertEquals("Example Agent", sources[1].agentDisplayName)
        assertEquals("/workspace/example-project", sources[1].folder)
        // 没有文件夹的 Agent 不进列表。
        assertTrue(sources.none { it.agentId == "manus" })
    }

    @Test
    fun `device project sources fall back to device id for unknown devices`() {
        val sources = deviceProjectSources(
            emptyList(),
            listOf(agent("example-agent", "Example Agent", "remote-device", "/workspace/example-project")),
        )
        assertEquals(1, sources.size)
        assertEquals("remote-device", sources[0].deviceDisplayName)
    }

    @Test
    fun `project host usable devices - legacy record without capability bits but routable is usable`() {
        // 2026-11-11 真机根因（K70）：三台已配对设备的存储记录都没有
        // capabilities 键，projectHost 缺省 false；只看静态位会弹错
        // “还没有能托管项目的已配对设备”。能路由就是活证据。
        val legacy = RemoteDevice(deviceId = "remote-device", displayName = "远程设备", host = "192.0.2.10", port = 18743)
        assertFalse(legacy.capabilities.projectHost)
        val usable = projectHostUsableDevices(
            listOf(legacy),
            setOf("remote-device"),
        )
        assertEquals(listOf(legacy), usable)
    }

    @Test
    fun `project host usable devices - capability bit without routable folder is not usable`() {
        // 有 projectHost 位但没有可路由的 Agent 文件夹：进了流程也选不
        // 出家文件夹，不进列表。
        val capable = RemoteDevice(
            deviceId = "mac", displayName = "Mac", host = "192.168.1.4", port = 18743,
            capabilities = handServerCapabilities(),
        )
        assertTrue(capable.capabilities.projectHost)
        assertTrue(projectHostUsableDevices(listOf(capable), emptySet()).isEmpty())
    }

    @Test
    fun `project host usable devices - neither bit nor routable is not usable`() {
        val plain = RemoteDevice(deviceId = "x", displayName = "X", host = "192.168.1.9", port = 18743)
        assertTrue(projectHostUsableDevices(listOf(plain), emptySet()).isEmpty())
        assertTrue(projectHostUsableDevices(emptyList(), setOf("remote-device")).isEmpty())
    }

    @Test
    fun `project host usable devices - mixed devices keep only routable ones`() {
        val devices = listOf(
            RemoteDevice(deviceId = "remote-device", displayName = "远程设备", host = "192.0.2.10", port = 18743),
            RemoteDevice(deviceId = "mac", displayName = "Mac", host = "192.168.1.4", port = 18743),
            RemoteDevice(deviceId = "Android-x", displayName = "xiaomi12", host = "192.168.1.7", port = 18743),
        )
        val usable = projectHostUsableDevices(devices, setOf("remote-device", "Android-x"))
        assertEquals(listOf("remote-device", "Android-x"), usable.map { it.deviceId })
    }

    @Test
    fun `device project sources exclude non device agents`() {
        val local = AgentRecord(
            agentId = "local-codex",
            displayName = "Local Codex",
            kind = AgentKind.CLOUD,
            access = AgentAccess.CLI,
            brainKind = BrainKind.AGENT_RUNTIME,
            adapterId = "codex",
            binary = "codex",
            deviceId = "remote-device",
            deviceFolder = "/workspace/codex",
        )
        val sources = deviceProjectSources(emptyList(), listOf(local))
        assertTrue(sources.isEmpty())
    }

    // ---- 项目页清理（2026-11-11）：来源可用性只灰显、不删除 ----

    private fun pairingRecord(deviceId: String, state: PairingState, peerKey: Boolean = true) =
        PairingRecord(
            deviceId = deviceId,
            displayName = deviceId,
            fingerprintSha256 = "ab".repeat(32),
            peerPublicKey = if (peerKey) byteArrayOf(1, 2, 3) else null,
            state = state,
            updatedAtEpochSeconds = 1L,
        )

    @Test
    fun `device source availability - confirmed with key is available`() {
        val device = RemoteDevice(deviceId = "mac", displayName = "Mac", host = "192.168.1.4", port = 18743)
        val (available, reason) = deviceSourceAvailability(device, pairingRecord("mac", PairingState.CONFIRMED))
        assertTrue(available)
        assertEquals(null, reason)
    }

    @Test
    fun `device source availability - pairing states map to the device list labels`() {
        // 与设备列表 buildRemoteDeviceRows 同一口径：CONFIRMED 以外一律不可用。
        val device = RemoteDevice(deviceId = "mac", displayName = "Mac", host = "192.168.1.4", port = 18743)
        assertEquals(false to "未配对（不可用）", deviceSourceAvailability(device, null))
        assertEquals(false to "待确认（不可用）", deviceSourceAvailability(device, pairingRecord("mac", PairingState.PENDING)))
        assertEquals(false to "已拒绝（不可用）", deviceSourceAvailability(device, pairingRecord("mac", PairingState.REJECTED)))
        assertEquals(false to "已撤销（不可用）", deviceSourceAvailability(device, pairingRecord("mac", PairingState.REVOKED)))
    }

    @Test
    fun `device source availability - confirmed without peer key cannot route`() {
        val device = RemoteDevice(deviceId = "mac", displayName = "Mac", host = "192.168.1.4", port = 18743)
        assertEquals(
            false to "配对未完成（不可用）",
            deviceSourceAvailability(device, pairingRecord("mac", PairingState.CONFIRMED, peerKey = false)),
        )
    }

    @Test
    fun `device source availability - unknown device is unavailable`() {
        assertEquals(false to "设备未添加（不可用）", deviceSourceAvailability(null, pairingRecord("mac", PairingState.CONFIRMED)))
    }

    @Test
    fun `device project sources keep unavailable sources greyed instead of dropping them`() {
        val devices = listOf(
            RemoteDevice(deviceId = "remote-device", displayName = "远程设备", host = "192.0.2.10", port = 18743),
            RemoteDevice(deviceId = "mac", displayName = "Mac", host = "192.168.1.4", port = 18743),
        )
        val sources = deviceProjectSources(
            devices,
            listOf(
                agent("example-agent", "Example Agent", "remote-device", "/workspace/example-project"),
                agent("codex", "Codex", "mac", "/Users/example-user"),
            ),
        ) { deviceId ->
            deviceSourceAvailability(
                devices.firstOrNull { it.deviceId == deviceId },
                if (deviceId == "remote-device") pairingRecord(deviceId, PairingState.CONFIRMED) else null,
            )
        }
        assertEquals(2, sources.size)
        val vps = sources.first { it.deviceId == "remote-device" }
        assertTrue(vps.available)
        assertEquals(null, vps.unavailableReason)
        val mac = sources.first { it.deviceId == "mac" }
        assertFalse(mac.available)
        assertEquals("未配对（不可用）", mac.unavailableReason)
    }

    @Test
    fun `device project sources default to all available for old callers`() {
        val sources = deviceProjectSources(
            listOf(RemoteDevice(deviceId = "mac", displayName = "Mac", host = "192.168.1.4", port = 18743)),
            listOf(agent("codex", "Codex", "mac", "/Users/example-user")),
        )
        assertEquals(1, sources.size)
        assertTrue(sources[0].available)
        assertEquals(null, sources[0].unavailableReason)
    }
}
