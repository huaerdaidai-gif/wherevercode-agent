package wherevercode.agent.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.brain.BrainKind
import wherevercode.agent.core.transport.RemoteAgentInfo

/**
 * 添加设备时的 Agent 自动发现（V5，2026-11-11；maintainer 设计纠正）。
 *
 * 回归覆盖：设备一加上，hello 列表里的 Agent 应自动建成记录；手工
 * 已加的按 deviceId + agentId 去重、保留不动；不可用/无文件夹的跳过；
 * 同 agentId 属别台现存设备时不覆盖（AgentStore 全局按 agentId 存）；
 * 旧记录没绑设备或绑的设备已删时改绑到本次扫描的设备（2026-11-19
 * 绑定生命周期缺口修复），而不是永远重名跳过、承载数永远少计。
 */
class DeviceAgentDiscoveryTest {

    private fun hello(
        agentId: String,
        displayName: String,
        folder: String,
        available: Boolean = true,
    ) = RemoteAgentInfo(
        agentId = agentId,
        displayName = displayName,
        kind = "agent",
        capabilities = listOf("dev"),
        folder = folder,
        available = available,
    )

    private fun manual(
        agentId: String,
        deviceId: String?,
        folder: String = "/workspace/x",
    ) = AgentRecord(
        agentId = agentId,
        displayName = "$agentId（手工）",
        kind = AgentKind.DEVICE,
        access = AgentAccess.CLI,
        brainKind = BrainKind.AGENT_RUNTIME,
        adapterId = agentId,
        binary = agentId,
        deviceId = deviceId,
        deviceFolder = folder,
        lastTestSummary = "远端可用",
        loginState = AgentLoginState.LOGGED_IN,
    )

    @Test
    fun `hello agents become device records`() {
        val (created, result) = DeviceAgentDiscovery.discover(
            "remote-device",
            listOf(
                hello("example-agent", "Example Agent", "/workspace/example-project"),
                hello("codebuddy", "CodeBuddy", "/workspace/codebuddy"),
            ),
            emptyList(),
            setOf("remote-device"),
        )
        assertEquals(listOf("example-agent", "codebuddy"), result.created)
        assertTrue(result.alreadyPresent.isEmpty())
        assertTrue(result.skippedUnavailable.isEmpty())
        assertTrue(result.skippedCollision.isEmpty())
        assertTrue(result.rebound.isEmpty())
        assertEquals(2, created.size)
        val grok = created.first { it.agentId == "example-agent" }
        assertEquals("Example Agent", grok.displayName)
        assertEquals(AgentKind.DEVICE, grok.kind)
        assertEquals(AgentAccess.CLI, grok.access)
        assertEquals(BrainKind.AGENT_RUNTIME, grok.brainKind)
        assertEquals("remote-device", grok.deviceId)
        assertEquals("/workspace/example-project", grok.deviceFolder)
        assertEquals(listOf("dev"), grok.capabilities)
        assertEquals(DeviceAgentDiscovery.AUTO_DISCOVERED_HINT, grok.addHint)
        assertEquals(AgentLoginState.UNKNOWN, grok.loginState)
    }

    @Test
    fun `created records pass validation`() {
        val (created, _) = DeviceAgentDiscovery.discover(
            "remote-device",
            listOf(hello("example-agent", "Example Agent", "/workspace/example-project")),
            emptyList(),
            setOf("remote-device"),
        )
        assertEquals(1, created.size)
        assertTrue(created[0].validate().isEmpty())
        val store = InMemoryAgentStore()
        created.forEach { store.saveAgent(it) }
        assertEquals("remote-device", store.getAgent("example-agent")?.deviceId)
        assertEquals("/workspace/example-project", store.getAgent("example-agent")?.deviceFolder)
    }

    @Test
    fun `manually added agent on the same device is kept untouched`() {
        // maintainer 手工加过 example-agent（带测试结果与登录态）：再发现一次
        // 不重建、不覆盖，手工填的东西原样保留。
        val existing = manual("example-agent", "remote-device")
        val (created, result) = DeviceAgentDiscovery.discover(
            "remote-device",
            listOf(
                hello("example-agent", "Example Agent", "/workspace/example-project"),
                hello("codebuddy", "CodeBuddy", "/workspace/codebuddy"),
            ),
            listOf(existing),
            setOf("remote-device"),
        )
        assertEquals(listOf("example-agent"), result.alreadyPresent)
        assertEquals(listOf("codebuddy"), result.created)
        assertTrue(result.rebound.isEmpty())
        assertEquals(1, created.size)
        assertEquals("codebuddy", created[0].agentId)
    }

    @Test
    fun `unavailable or folderless agents are skipped`() {
        val (_, result) = DeviceAgentDiscovery.discover(
            "remote-device",
            listOf(
                hello("example-agent", "Example Agent", "/workspace/example-project"),
                hello("broken", "Broken", "/workspace/broken", available = false),
                hello("nofolder", "No Folder", ""),
            ),
            emptyList(),
            setOf("remote-device"),
        )
        assertEquals(listOf("example-agent"), result.created)
        assertEquals(listOf("broken", "nofolder"), result.skippedUnavailable)
    }

    @Test
    fun `same agent id on another device is a collision not an overwrite`() {
        // VPS 与 Mac 上都有 codex：AgentStore 全局按 agentId 存，建第二条
        // 会覆盖第一条——跳过、不毁掉另一台设备的 Agent。前提是被绑的
        // 设备仍在当前设备列表里（knownDeviceIds 含 remote-device）。
        val existing = manual("codex", "remote-device", "/workspace/codex")
        val (created, result) = DeviceAgentDiscovery.discover(
            "mac",
            listOf(hello("codex", "Codex", "/Users/example-user")),
            listOf(existing),
            setOf("remote-device", "mac"),
        )
        assertTrue(created.isEmpty())
        assertEquals(listOf("codex"), result.skippedCollision)
        assertTrue(result.alreadyPresent.isEmpty())
        assertTrue(result.rebound.isEmpty())
    }

    @Test
    fun `agent bound to a deleted device is rebound to the scanned device`() {
        // 设备删过又重加（或 Agent 先被已删设备发现占用）：旧记录绑的
        // deviceId 已不在设备列表里——改绑到本次扫描的设备，承载数
        // 从此数得上；除绑定与文件夹外，手工字段原样保留。
        val existing = manual("codex", "gone-device", "/workspace/old-codex")
        val (toSave, result) = DeviceAgentDiscovery.discover(
            "xiaomi12",
            listOf(hello("codex", "Codex", "/data/codex")),
            listOf(existing),
            setOf("xiaomi12"),
        )
        assertTrue(result.created.isEmpty())
        assertTrue(result.skippedCollision.isEmpty())
        assertEquals(listOf("codex"), result.rebound)
        assertEquals(1, toSave.size)
        val rebound = toSave[0]
        assertEquals("xiaomi12", rebound.deviceId)
        assertEquals("/data/codex", rebound.deviceFolder)
        assertEquals("codex（手工）", rebound.displayName)
        assertEquals("远端可用", rebound.lastTestSummary)
        assertEquals(AgentLoginState.LOGGED_IN, rebound.loginState)
        assertTrue(rebound.validate().isEmpty())
        val store = InMemoryAgentStore()
        store.saveAgent(existing)
        toSave.forEach { store.saveAgent(it) }
        assertEquals("xiaomi12", store.getAgent("codex")?.deviceId)
    }

    @Test
    fun `agent with no device binding is rebound to the scanned device`() {
        // 手工加的记录没绑设备（deviceId 为 null）：在设备上被发现时
        // 改绑到该设备，而不是当成重名冲突永远跳过。
        val existing = manual("codex", null, "/workspace/codex")
        val (toSave, result) = DeviceAgentDiscovery.discover(
            "xiaomi12",
            listOf(hello("codex", "Codex", "/data/codex")),
            listOf(existing),
            setOf("xiaomi12"),
        )
        assertEquals(listOf("codex"), result.rebound)
        assertTrue(result.skippedCollision.isEmpty())
        assertEquals(1, toSave.size)
        assertEquals("xiaomi12", toSave[0].deviceId)
        assertEquals("/data/codex", toSave[0].deviceFolder)
    }

    @Test
    fun `empty hello discovers nothing`() {
        val (created, result) = DeviceAgentDiscovery.discover(
            "remote-device",
            emptyList(),
            emptyList(),
            setOf("remote-device"),
        )
        assertTrue(created.isEmpty())
        assertTrue(result.created.isEmpty())
        assertEquals("没有发现可用 Agent", DeviceAgentDiscovery.summarize(result))
    }

    @Test
    fun `summarize is honest about counts`() {
        val result = DeviceAgentDiscoveryResult(
            created = listOf("codebuddy"),
            alreadyPresent = listOf("example-agent"),
            skippedUnavailable = listOf("broken"),
            skippedCollision = listOf("codex"),
            rebound = listOf("agy"),
        )
        assertEquals(
            "新建 1 个 Agent，1 个改绑到本设备，1 个已在，1 个不可用已跳过，1 个与别台设备重名已跳过",
            DeviceAgentDiscovery.summarize(result),
        )
    }
}
