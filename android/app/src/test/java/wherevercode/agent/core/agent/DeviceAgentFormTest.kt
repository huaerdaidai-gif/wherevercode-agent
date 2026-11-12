package wherevercode.agent.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.brain.BrainKind

/**
 * “设备上的 Agent”表单（V5，2026-11-11）。
 *
 * 回归覆盖：添加 Agent 表单此前只有云端 API / 订阅 / 本机 CLI 三类，
 * “本机 CLI”指手机沙箱，远程设备（Mac / VPS / 小米 12）上的 Agent
 * 没法加（maintainer 2026-11-11 03:24 截图）。本文件覆盖 DeviceAgentForm
 * 的拼装与拒绝口径，以及 deviceId 校验与 RemoteDeviceValidation
 * 的一致性（小米 12 的 Hand 服务端 id `Android-x` 含大写）。
 */
class DeviceAgentFormTest {

    private fun build(
        name: String = "Mac Codex",
        agentId: String = "mac-codex",
        devicesEmpty: Boolean = false,
        deviceId: String? = "mac",
        binary: String? = "codex",
        deviceFolder: String? = "/Users/example-user",
        adapterId: String? = null,
        existing: AgentRecord? = null,
    ) = DeviceAgentForm.buildRecord(
        name = name,
        agentId = agentId,
        devicesEmpty = devicesEmpty,
        deviceId = deviceId,
        binary = binary,
        deviceFolder = deviceFolder,
        adapterId = adapterId,
        capabilities = listOf("dev"),
        dispatchable = true,
        knownLimits = null,
        existing = existing,
    )

    private fun okRecord(
        name: String = "Mac Codex",
        agentId: String = "mac-codex",
        devicesEmpty: Boolean = false,
        deviceId: String? = "mac",
        binary: String? = "codex",
        deviceFolder: String? = "/Users/example-user",
        adapterId: String? = null,
        existing: AgentRecord? = null,
    ): AgentRecord {
        val result = build(name, agentId, devicesEmpty, deviceId, binary, deviceFolder, adapterId, existing)
        check(result is DeviceAgentForm.Result.Ok) { "expected Ok, got $result" }
        return result.record
    }

    @Test
    fun `valid device agent saves with deviceId and folder`() {
        val record = okRecord()
        assertEquals("mac", record.deviceId)
        assertEquals("/Users/example-user", record.deviceFolder)
        assertEquals(AgentKind.DEVICE, record.kind)
        assertEquals(AgentAccess.CLI, record.access)
        assertEquals(BrainKind.AGENT_RUNTIME, record.brainKind)
        assertEquals("codex", record.binary)
        // 适配器 id 未填时默认取二进制名（与加设备时发现的远端 Agent 同口径）。
        assertEquals("codex", record.adapterId)
        assertTrue(record.validate().isEmpty())
    }

    @Test
    fun `uppercase device id from hand server is accepted`() {
        // 小米 12 的 Hand 服务端 device-id 是 Android-x（含大写）；
        // AgentRecord.validate 的 deviceId 规则须与 RemoteDeviceValidation
        // 一致，否则这台设备上的 Agent 存不进去（saveAgent 再验一次会抛错）。
        val record = okRecord(
            name = "小米 12 agy",
            agentId = "xiaomi12-agy",
            deviceId = "Android-x",
            binary = "agy",
            deviceFolder = "/data/data/com.termux/files/home",
        )
        assertEquals("Android-x", record.deviceId)
        assertTrue(record.validate().isEmpty())
        val store = InMemoryAgentStore()
        store.saveAgent(record)
        assertEquals("Android-x", store.getAgent("xiaomi12-agy")?.deviceId)
        assertEquals("/data/data/com.termux/files/home", store.getAgent("xiaomi12-agy")?.deviceFolder)
    }

    @Test
    fun `no devices added says add a device first`() {
        val result = build(devicesEmpty = true, deviceId = null)
        val rejected = result as DeviceAgentForm.Result.Rejected
        assertTrue(rejected.message.contains("先去设备页添加远程设备"))
    }

    @Test
    fun `missing device binary and folder are rejected`() {
        val noDevice = build(deviceId = null) as DeviceAgentForm.Result.Rejected
        assertTrue(noDevice.message.contains("请先选择设备"))
        val noBinary = build(binary = "  ") as DeviceAgentForm.Result.Rejected
        assertTrue(noBinary.message.contains("二进制名"))
        val noFolder = build(deviceFolder = "") as DeviceAgentForm.Result.Rejected
        assertTrue(noFolder.message.contains("工作文件夹"))
    }

    @Test
    fun `edit keeps login state quota rule and last test`() {
        val existing = okRecord().copy(
            loginState = AgentLoginState.LOGGED_IN,
            quotaRule = "设备本地，不占云端额度",
            lastTestSummary = "远端可用",
        )
        val record = okRecord(
            name = "Mac Codex 改名",
            agentId = "mac-codex",
            existing = existing,
        )
        assertEquals(AgentLoginState.LOGGED_IN, record.loginState)
        assertEquals("设备本地，不占云端额度", record.quotaRule)
        assertEquals("远端可用", record.lastTestSummary)
        assertEquals("Mac Codex 改名", record.displayName)
    }

    @Test
    fun `edit keeps disabled state`() {
        // Agent 开关（2026-11-12）：编辑不重置开关——停用的改完仍是停用。
        val existing = okRecord().copy(enabled = false)
        val record = okRecord(
            name = "Mac Codex 改名",
            agentId = "mac-codex",
            existing = existing,
        )
        assertEquals(false, record.enabled)
        // 新建默认启用。
        assertEquals(true, okRecord().enabled)
    }

    @Test
    fun `inMemory store round trip keeps device binding`() {
        val store = InMemoryAgentStore()
        store.saveAgent(okRecord())
        val saved = store.getAgent("mac-codex")
        assertEquals("mac", saved?.deviceId)
        assertEquals("/Users/example-user", saved?.deviceFolder)
        assertEquals("codex", saved?.binary)
        assertEquals(AgentKind.DEVICE, saved?.kind)
        assertEquals(1, store.listAgents().size)
    }

    @Test
    fun `device id with dot is accepted like remote device ids`() {
        // RemoteDeviceValidation 允许点（如 hostname 风格的 id），
        // AgentRecord 的 deviceId 规则须同步，否则这类设备加不上 Agent。
        val record = okRecord(deviceId = "mac.local")
        assertEquals("mac.local", record.deviceId)
        assertTrue(record.validate().isEmpty())
    }
}
