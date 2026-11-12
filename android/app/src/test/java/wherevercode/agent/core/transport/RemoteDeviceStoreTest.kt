package wherevercode.agent.core.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.core.security.PairingRecord
import wherevercode.agent.core.security.PairingState

/**
 * 阶段 B（V5 紧急开发）：手动添加的远程设备（远程设备 等）。
 *
 * 红测试：RemoteDevice / RemoteDeviceStore / InMemoryRemoteDeviceStore /
 * RemoteDeviceValidation 尚不存在。
 *
 * 规则：设备是用户手动添加的（host + port），不是 mDNS 发现的；
 * 只存地址与显示名，不存任何密钥、Token、指纹以外的秘密；
 * host 不接受带 scheme 的地址（http://… 是配置错误，要明说）。
 */
class RemoteDeviceStoreTest {

    private fun vps() = RemoteDevice(
        deviceId = "remote-host",
        displayName = "远程设备",
        host = "192.0.2.10",
        port = 18743,
    )

    @Test
    fun `valid remote device passes validation`() {
        assertTrue(RemoteDeviceValidation.validate(vps()).isEmpty())
    }

    @Test
    fun `host with scheme is rejected`() {
        val errors = RemoteDeviceValidation.validate(vps().copy(host = "https://192.0.2.10"))
        assertTrue(errors.any { it.contains("http") || it.contains("地址") })
    }

    @Test
    fun `blank host is rejected`() {
        assertTrue(RemoteDeviceValidation.validate(vps().copy(host = "  ")).isNotEmpty())
    }

    @Test
    fun `port out of range is rejected`() {
        assertTrue(RemoteDeviceValidation.validate(vps().copy(port = 0)).isNotEmpty())
        assertTrue(RemoteDeviceValidation.validate(vps().copy(port = 70000)).isNotEmpty())
    }

    @Test
    fun `bad device id shape is rejected`() {
        assertTrue(RemoteDeviceValidation.validate(vps().copy(deviceId = "America VPS")).isNotEmpty())
        assertTrue(RemoteDeviceValidation.validate(vps().copy(deviceId = "")).isNotEmpty())
    }

    @Test
    fun `server-reported id with uppercase is accepted`() {
        // V5 加设备修复（2026-11-11）：小米 12 的 Hand 服务默认
        // device-id 取自 hostname，如 Android-x；服务端报什么 id，
        // App 就原样采用（凭证须与服务端 id 严格一致），不能因为
        // 含大写字母就拒绝添加。
        assertTrue(RemoteDeviceValidation.validate(vps().copy(deviceId = "Android-x")).isEmpty())
    }

    @Test
    fun `server-reported id with dot is accepted`() {
        assertTrue(RemoteDeviceValidation.validate(vps().copy(deviceId = "xiaomi-12.local")).isEmpty())
    }

    @Test
    fun `buildRemoteDeviceRows maps pairing states`() {
        val devices = listOf(
            vps().copy(deviceId = "remote-device", displayName = "远程设备"),
            vps().copy(deviceId = "Android-x", displayName = "小米 12"),
            vps().copy(deviceId = "mac-host", displayName = "Mac"),
        )
        val pairings = listOf(
            PairingRecord("remote-device", "远程设备", "aa".repeat(32), state = PairingState.CONFIRMED, updatedAtEpochSeconds = 1L),
            PairingRecord("Android-x", "小米 12", "bb".repeat(32), state = PairingState.PENDING, updatedAtEpochSeconds = 1L),
        )
        val rows = buildRemoteDeviceRows(devices, pairings)
        assertEquals(3, rows.size)
        assertEquals("已配对", rows[0].stateLabel)
        assertTrue(rows[0].paired)
        assertEquals("待确认（不可用）", rows[1].stateLabel)
        assertFalse(rows[1].paired)
        assertEquals("未配对（不可用）", rows[2].stateLabel)
        assertFalse(rows[2].paired)
    }

    @Test
    fun `buildRemoteDeviceRows marks outdated hand version`() {
        // Hand 自更新（2026-11-12）：远端版本与内置安装包不一致 → 可更新；
        // 一致 → 最新；没报版本 → 不知道，不算可更新（按钮另按空白出）。
        val devices = listOf(
            vps().copy(deviceId = "remote-device", handVersion = "237ad2080ea3"),
            vps().copy(deviceId = "Android-x", handVersion = "aaaa00000000"),
            vps().copy(deviceId = "mac-host", handVersion = ""),
        )
        val rows = buildRemoteDeviceRows(devices, emptyList(), bundledHandVersion = "237ad2080ea3")
        assertEquals("237ad2080ea3", rows[0].handVersion)
        assertFalse(rows[0].handUpdateAvailable)
        assertTrue(rows[1].handUpdateAvailable)
        assertFalse(rows[2].handUpdateAvailable)
        // 调用方不传内置版本：谁也不算可更新（不编造）。
        val rowsNoBundle = buildRemoteDeviceRows(devices, emptyList())
        assertTrue(rowsNoBundle.none { it.handUpdateAvailable })
    }

    @Test
    fun `blank display name is rejected`() {
        assertTrue(RemoteDeviceValidation.validate(vps().copy(displayName = "")).isNotEmpty())
    }

    @Test
    fun `hostname is accepted not only ip`() {
        assertTrue(RemoteDeviceValidation.validate(vps().copy(host = "remote-host")).isEmpty())
    }

    @Test
    fun `in-memory store round trips save get list delete`() {
        val store: RemoteDeviceStore = InMemoryRemoteDeviceStore()
        assertTrue(store.listDevices().isEmpty())
        assertNull(store.getDevice("remote-host"))

        store.saveDevice(vps())
        assertEquals(vps().host, store.getDevice("remote-host")?.host)
        assertEquals(1, store.listDevices().size)

        store.saveDevice(vps().copy(displayName = "远程设备（开发）"))
        assertEquals(1, store.listDevices().size)
        assertEquals("远程设备（开发）", store.getDevice("remote-host")?.displayName)

        store.deleteDevice("remote-host")
        assertTrue(store.listDevices().isEmpty())
    }

    @Test
    fun `in-memory store rejects invalid device on save`() {
        val store: RemoteDeviceStore = InMemoryRemoteDeviceStore()
        try {
            store.saveDevice(vps().copy(port = 0))
            org.junit.Assert.fail("非法设备应拒绝保存")
        } catch (_: IllegalArgumentException) {
            // 预期。
        }
        assertTrue(store.listDevices().isEmpty())
    }

    @Test
    fun `public host defaults to blank and route is internal`() {
        val device = vps()
        assertEquals("", device.publicHost)
        assertFalse(device.hasPublicAddress())
        assertEquals("内网", device.routeLabel())
        val peer = device.toDiscoveredPeer(fingerprintSha256 = "cd".repeat(32))
        assertEquals("192.0.2.10", peer.host)
        assertNull(peer.fallbackHost)
        assertEquals(listOf("192.0.2.10"), peer.hostsInPriorityOrder())
    }

    @Test
    fun `public host is preferred peer with internal as fallback`() {
        val device = vps().copy(publicHost = "203.0.113.10")
        assertTrue(device.hasPublicAddress())
        assertEquals("公网优先", device.routeLabel())
        val peer = device.toDiscoveredPeer(fingerprintSha256 = "cd".repeat(32))
        assertEquals("203.0.113.10", peer.host)
        assertEquals("192.0.2.10", peer.fallbackHost)
        assertEquals(listOf("203.0.113.10", "192.0.2.10"), peer.hostsInPriorityOrder())
        assertEquals(18743, peer.port)
    }

    @Test
    fun `public host equal to internal host has no fallback`() {
        val device = vps().copy(publicHost = "192.0.2.10")
        val peer = device.toDiscoveredPeer(fingerprintSha256 = "cd".repeat(32))
        assertNull(peer.fallbackHost)
        assertEquals(listOf("192.0.2.10"), peer.hostsInPriorityOrder())
    }

    @Test
    fun `public host with scheme is rejected, blank is allowed`() {
        assertTrue(
            RemoteDeviceValidation.validate(vps().copy(publicHost = "https://203.0.113.10"))
                .any { it.contains("公网直连地址") },
        )
        assertTrue(
            RemoteDeviceValidation.validate(vps().copy(publicHost = "203.0.113.10/24")).isNotEmpty(),
        )
        assertTrue(RemoteDeviceValidation.validate(vps().copy(publicHost = "")).isEmpty())
        assertTrue(RemoteDeviceValidation.validate(vps().copy(publicHost = "203.0.113.10")).isEmpty())
    }

    @Test
    fun `in-memory store round trips public host`() {
        val store: RemoteDeviceStore = InMemoryRemoteDeviceStore()
        store.saveDevice(vps().copy(publicHost = "203.0.113.10"))
        val restored = store.getDevice("remote-host")
        assertEquals("203.0.113.10", restored?.publicHost)
        assertEquals("192.0.2.10", restored?.host)
    }

    @Test
    fun `buildRemoteDeviceRows carries route label`() {
        val rows = buildRemoteDeviceRows(
            listOf(vps().copy(publicHost = "203.0.113.10")),
            emptyList(),
        )
        assertEquals("公网优先", rows[0].routeLabel)
        val plain = buildRemoteDeviceRows(listOf(vps()), emptyList())
        assertEquals("内网", plain[0].routeLabel)
    }

    @Test
    fun `remote device converts to discovered peer with pairing fingerprint`() {
        val peer = vps().toDiscoveredPeer(fingerprintSha256 = "cd".repeat(32))
        assertEquals("remote-host", peer.deviceId)
        assertEquals("远程设备", peer.displayName)
        assertEquals("192.0.2.10", peer.host)
        assertEquals(18743, peer.port)
        assertEquals("cd".repeat(32), peer.fingerprintSha256)
        assertTrue(peer.capabilities.contains("hand"))
    }

    @Test
    fun `capabilities default to all off - fail closed`() {
        // 老项目规矩：注册本身不产生任何位置；没有能力记录的设备
        // 一律当作不能托管项目，加项目流程不会向它提供入口。
        val device = vps()
        assertFalse(device.capabilities.projectHost)
        assertFalse(device.capabilities.executionHost)
        assertTrue(device.protocolCapabilities.isEmpty())
    }

    @Test
    fun `hand server capabilities mark a device as project host`() {
        val device = vps().copy(
            capabilities = handServerCapabilities(),
            protocolCapabilities = handServerProtocolCapabilities(),
        )
        assertTrue(device.capabilities.projectHost)
        assertTrue(device.capabilities.workspaceRead)
        assertTrue(device.capabilities.workspaceWrite)
        assertFalse(device.capabilities.controlPlane)
        assertTrue(device.protocolCapabilities.contains("open_project_root"))
    }

    @Test
    fun `in-memory store round trips capabilities`() {
        val store: RemoteDeviceStore = InMemoryRemoteDeviceStore()
        val device = vps().copy(
            capabilities = handServerCapabilities(),
            protocolCapabilities = handServerProtocolCapabilities(),
        )
        store.saveDevice(device)
        val restored = store.getDevice("remote-host")
        assertEquals(handServerCapabilities(), restored?.capabilities)
        assertEquals(handServerProtocolCapabilities(), restored?.protocolCapabilities)
    }

    @Test
    fun `rows carry last seen capability count and server status`() {
        val devices = listOf(
            vps().copy(
                deviceId = "remote-device",
                displayName = "远程设备",
                lastSeenEpochSeconds = 1794000000,
                lastServerStatus = "APPROVED",
                capabilities = handServerCapabilities(),
            ),
            vps().copy(deviceId = "Android-x", displayName = "小米 12"),
        )
        val pairings = listOf(
            PairingRecord("remote-device", "远程设备", "aa".repeat(32), state = PairingState.CONFIRMED, updatedAtEpochSeconds = 1L),
        )
        val rows = buildRemoteDeviceRows(devices, pairings)
        assertEquals(1794000000L, rows[0].lastSeenEpochSeconds)
        assertEquals(5, rows[0].capabilityCount)
        assertEquals("APPROVED", rows[0].serverStatus)
        assertTrue(rows[0].capabilityNames.contains("托管项目"))
        assertEquals(0L, rows[1].lastSeenEpochSeconds)
        assertEquals(0, rows[1].capabilityCount)
        assertEquals("", rows[1].serverStatus)
    }

    @Test
    fun `in-memory store round trips last seen and server status`() {
        val store: RemoteDeviceStore = InMemoryRemoteDeviceStore()
        val device = vps().copy(lastSeenEpochSeconds = 1794000000, lastServerStatus = "PENDING")
        store.saveDevice(device)
        val restored = store.getDevice("remote-host")
        assertEquals(1794000000L, restored?.lastSeenEpochSeconds)
        assertEquals("PENDING", restored?.lastServerStatus)
    }

    @Test
    fun `last seen defaults to never online`() {
        assertEquals(0L, vps().lastSeenEpochSeconds)
        assertEquals("", vps().lastServerStatus)
    }
}
