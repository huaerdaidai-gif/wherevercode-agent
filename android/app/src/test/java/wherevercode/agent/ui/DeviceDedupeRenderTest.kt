package wherevercode.agent.ui

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import wherevercode.agent.core.security.PairingRecord
import wherevercode.agent.core.security.PairingState
import wherevercode.agent.core.transport.RemoteDevice
import wherevercode.agent.core.transport.buildRemoteDeviceRows

/**
 * 设备页双记录合并（2026-11-19，197/199 真机复现）：同一台设备配对
 * 成功时同时落 RemoteDevice 与 PairingRecord 两条记录（deviceId 同
 * 为服务端 hello id），已添加设备区曾两条各出一卡、控制台却只数
 * 远程条目（角标 1 台、列表 2 卡）。合并口径：有远程记录的以远程卡
 * 为准（配对状态、承载 Agent 数、撤销配对都在这一张卡上），只有
 * 配对记录的才单独出配对卡；两类卡都显示承载 Agent 数。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DeviceDedupeRenderTest {

    private fun controller(): Pair<AgentScreenController, LinearLayout> {
        val context = RuntimeEnvironment.getApplication()
        val list = LinearLayout(context)
        val deviceInfo = LinearLayout(context)
        return AgentScreenController(context, list, deviceInfo) to list
    }

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

    private fun occurrences(text: String, needle: String): Int =
        text.split(needle).size - 1

    private fun mi12Device() = RemoteDevice(
        deviceId = "Android-x",
        displayName = "测试设备",
        host = "192.168.1.10",
        port = 18743,
        lastSeenEpochSeconds = 1794000000,
        lastServerStatus = "APPROVED",
    )

    private fun mi12Pairing() = PairingRecord(
        deviceId = "Android-x",
        displayName = "测试设备",
        fingerprintSha256 = "ab".repeat(32),
        state = PairingState.CONFIRMED,
        updatedAtEpochSeconds = 1L,
    )

    @Test
    fun `same device with pairing and remote record renders one card`() {
        val (controller, list) = controller()
        val rows = buildRemoteDeviceRows(
            listOf(mi12Device()),
            listOf(mi12Pairing()),
            agentCounts = mapOf("Android-x" to 2),
        )
        controller.renderDevices(
            emptyList(),
            listOf(mi12Pairing()),
            {}, {}, {}, {},
            rows,
            agentCounts = mapOf("Android-x" to 2),
            onDeleteRemote = {},
        ) { }
        val text = collectText(list)
        // 设备名只出现一次 = 只出一张卡（合并前配对卡与远程卡各一张）。
        assertEquals(1, occurrences(text, "测试设备"))
        // 合并卡上带承载数与配对卡原有的撤销入口，合并没丢功能。
        assertTrue(text.contains("承载：2 个 Agent"))
        assertTrue(text.contains("撤销配对"))
        assertTrue(text.contains("删除设备"))
    }

    @Test
    fun `pairing only device still renders its own card with zero agents`() {
        val (controller, list) = controller()
        controller.renderDevices(
            emptyList(),
            listOf(mi12Pairing()),
            {}, {}, {}, {},
        )
        val text = collectText(list)
        assertEquals(1, occurrences(text, "测试设备"))
        assertTrue(text.contains("承载：0 个 Agent"))
        assertTrue(text.contains("已配对"))
    }

    @Test
    fun `remote device without agents shows zero agent count`() {
        val (controller, list) = controller()
        val rows = buildRemoteDeviceRows(listOf(mi12Device()), listOf(mi12Pairing()))
        controller.renderDevices(
            emptyList(),
            listOf(mi12Pairing()),
            {}, {}, {}, {},
            rows,
            onDeleteRemote = {},
        ) { }
        val text = collectText(list)
        assertTrue(text.contains("承载：0 个 Agent"))
        assertFalse(text.contains("还没有添加设备"))
    }
}
