package wherevercode.agent.ui

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import wherevercode.agent.core.security.PairingRecord
import wherevercode.agent.core.security.PairingState
import wherevercode.agent.core.transport.DiscoveredPeer
import wherevercode.agent.core.transport.RemoteDevice
import wherevercode.agent.core.transport.buildRemoteDeviceRows

/**
 * V5 C2（批 2，第 5 步）：设备“未接入”标记。
 * Agent 页设备区段里，已发现未配对的设备与未确认/已拒绝的配对记录
 * 一律缀“（不可用）”；已配对的不缀。绝不伪造在线态。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DeviceUnavailableLabelTest {

    private fun controller(): Pair<AgentScreenController, LinearLayout> {
        val context = RuntimeEnvironment.getApplication()
        val list = LinearLayout(context)
        val deviceInfo = LinearLayout(context)
        // 控制器不再接诊断输出（2026-11-11 移除沙箱扫描与诊断区），只要三参。
        return AgentScreenController(context, list, deviceInfo) to list
    }

    private fun peer() = DiscoveredPeer(
        deviceId = "dev-k70",
        displayName = "K70",
        host = "192.168.1.3",
        port = 18080,
        fingerprintSha256 = "ab".repeat(32),
        capabilities = listOf("hand"),
    )

    private fun record(state: PairingState) = PairingRecord(
        deviceId = "dev-mi12",
        displayName = "小米12",
        fingerprintSha256 = "cd".repeat(32),
        state = state,
        reason = if (state == PairingState.REJECTED) "用户拒绝" else null,
        updatedAtEpochSeconds = 1L,
    )

    private fun collectText(view: View): String {
        val sb = StringBuilder()
        fun walk(v: View) {
            if (v is TextView) sb.append(v.text.toString()).append('\n')
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(view)
        return sb.toString()
    }

    @Test
    fun `discovered unpaired peer is marked unavailable`() {
        val (controller, list) = controller()
        controller.renderDevices(listOf(peer()), emptyList(), {}, {}, {}, {})
        val text = collectText(list)
        assertTrue(text.contains("未配对（不可用）"))
    }

    @Test
    fun `pending and rejected pairings are marked unavailable, confirmed is not`() {
        val (controller, list) = controller()
        controller.renderDevices(
            emptyList(),
            listOf(record(PairingState.PENDING), record(PairingState.REJECTED)),
            {}, {}, {}, {},
        )
        val text = collectText(list)
        assertTrue(text.contains("待确认（不可用）"))
        assertTrue(text.contains("已拒绝（不可用）"))
        assertFalse(text.contains("已配对（不可用）"))
    }

    @Test
    fun `confirmed pairing shows paired without unavailable suffix`() {
        val (controller, list) = controller()
        controller.renderDevices(emptyList(), listOf(record(PairingState.CONFIRMED)), {}, {}, {}, {})
        val text = collectText(list)
        assertTrue(text.contains("配对：已完成"))
        assertTrue(text.contains("当前在线：未检测"))
        assertFalse(text.contains("（不可用）"))
    }

    @Test
    fun `remote devices are listed with pairing state and add entry`() {
        // V5 加设备修复（2026-11-11）：设备页此前根本不传远程设备，
        // 加完也看不见。现单列“远程设备”区段 + 添加入口。
        val (controller, list) = controller()
        val rows = buildRemoteDeviceRows(
            listOf(
                RemoteDevice("remote-device", "远程设备", "192.0.2.10", 18743),
                RemoteDevice("Android-x", "小米 12", "192.168.1.7", 18743),
            ),
            listOf(record(PairingState.CONFIRMED).copy(deviceId = "remote-device", displayName = "远程设备")),
        )
        var addClicked = false
        controller.renderDevices(emptyList(), emptyList(), {}, {}, {}, {}, rows) { addClicked = true }
        val text = collectText(list)
        assertTrue(text.contains("远程设备"))
        assertTrue(text.contains("远程设备"))
        assertTrue(text.contains("小米 12"))
        // V5 公网直连（2026-11-11）：列表只标走线（内网）与端口，
        // 不贴完整 IP。
        assertTrue(text.contains("内网"))
        assertTrue(text.contains("端口 18743"))
        assertFalse(text.contains("192.0.2.10"))
        assertFalse(text.contains("192.168.1.7"))
        assertTrue(text.contains("配对：已完成"))
        assertTrue(text.contains("未配对（不可用）"))
        assertTrue(text.contains("添加远程设备"))
        // 添加入口可点。
        val button = findTextView(list, "添加远程设备")
        assertTrue(button != null)
        button!!.performClick()
        assertTrue(addClicked)
    }

    @Test
    fun `paired remote device shows rediscover button and add entry still works`() {
        // V5 自动发现（2026-11-11）：已配对的远程设备卡片出“重新发现
        // Agent”按钮；onAddRemote 必须是最后一个函数参数，否则本测试的
        // 尾随 lambda 会绑到 onRediscover 上，添加入口直接消失（回归）。
        val (controller, list) = controller()
        val rows = buildRemoteDeviceRows(
            listOf(RemoteDevice("remote-device", "远程设备", "192.0.2.10", 18743)),
            listOf(record(PairingState.CONFIRMED).copy(deviceId = "remote-device", displayName = "远程设备")),
        )
        var rediscovered: String? = null
        var addClicked = false
        controller.renderDevices(
            emptyList(), emptyList(), {}, {}, {}, {}, rows,
            onRediscover = { device -> rediscovered = device.deviceId },
        ) { addClicked = true }
        val text = collectText(list)
        assertTrue(text.contains("重新发现 Agent"))
        assertTrue(text.contains("添加远程设备"))
        val rediscoverButton = findTextView(list, "重新发现 Agent")
        assertTrue(rediscoverButton != null)
        rediscoverButton!!.performClick()
        assertTrue(rediscovered == "remote-device")
        val addButton = findTextView(list, "添加远程设备")
        assertTrue(addButton != null)
        addButton!!.performClick()
        assertTrue(addClicked)
    }

    @Test
    fun `public address device shows public route marker and edit address button`() {
        // V5 公网直连（2026-11-11）：填了公网直连地址的设备卡片标
        // “公网优先”且不贴完整 IP；点“编辑地址”调 onEditRemote。
        val (controller, list) = controller()
        val rows = buildRemoteDeviceRows(
            listOf(
                RemoteDevice(
                    deviceId = "remote-device",
                    displayName = "远程设备",
                    host = "192.0.2.10",
                    publicHost = "203.0.113.10",
                    port = 18743,
                ),
            ),
            listOf(record(PairingState.CONFIRMED).copy(deviceId = "remote-device", displayName = "远程设备")),
        )
        assertTrue(rows[0].routeLabel == "公网优先")
        var edited: String? = null
        controller.renderDevices(
            emptyList(), emptyList(), {}, {}, {}, {}, rows,
            onRediscover = {},
            onEditRemote = { device -> edited = device.deviceId },
        )
        val text = collectText(list)
        assertTrue(text.contains("公网优先"))
        assertFalse(text.contains("203.0.113.10"))
        assertFalse(text.contains("192.0.2.10"))
        assertTrue(text.contains("编辑地址"))
        val button = findTextView(list, "编辑地址")
        assertTrue(button != null)
        button!!.performClick()
        assertTrue(edited == "remote-device")
    }

    private fun findTextView(view: View, substring: String): TextView? {
        if (view is TextView && view.text.toString().contains(substring)) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findTextView(view.getChildAt(i), substring)?.let { return it }
            }
        }
        return null
    }
}
