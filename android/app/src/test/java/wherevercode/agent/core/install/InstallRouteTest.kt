package wherevercode.agent.core.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 添加设备三条路的优先级（maintainer 2026-11-11 10:30 定）：
 * 路 1 局域网发现 + 配对（主流第一优先）→ 路 2 通用一键运行脚本 →
 * 路 3 V5 一键安装（App 直连 SSH）。界面按这个顺序排。
 */
class InstallRouteTest {

    @Test
    fun `route order is discovery then generic script then v5 ssh`() {
        val routes = listOf(
            InstallRoute.DISCOVERY,
            InstallRoute.GENERIC_SCRIPT,
            InstallRoute.V5_SSH,
        )
        assertEquals(InstallRoute.DISCOVERY, routes[0])
        assertEquals(InstallRoute.GENERIC_SCRIPT, routes[1])
        assertEquals(InstallRoute.V5_SSH, routes[2])
    }

    @Test
    fun `discovery route installs nothing`() {
        assertEquals(emptyList<InstallMethod>(), installMethodsFor(DeviceOs.MAC, InstallRoute.DISCOVERY))
        assertEquals(emptyList<InstallMethod>(), installMethodsFor(DeviceOs.WINDOWS, InstallRoute.DISCOVERY))
    }

    @Test
    fun `generic script route never uses app ssh on mac linux vps`() {
        for (os in listOf(DeviceOs.MAC, DeviceOs.LINUX, DeviceOs.VPS)) {
            val methods = installMethodsFor(os, InstallRoute.GENERIC_SCRIPT)
            assertEquals(InstallMethod.LOCAL_COMMAND, methods.first())
            assertFalse(methods.contains(InstallMethod.SSH))
            assertTrue(methods.contains(InstallMethod.EXISTING_HAND))
        }
    }

    @Test
    fun `v5 ssh route puts app ssh first on mac linux vps`() {
        for (os in listOf(DeviceOs.MAC, DeviceOs.LINUX, DeviceOs.VPS)) {
            val methods = installMethodsFor(os, InstallRoute.V5_SSH)
            assertEquals(InstallMethod.SSH, methods.first())
            assertFalse(methods.contains(InstallMethod.LOCAL_COMMAND))
            assertTrue(methods.contains(InstallMethod.EXISTING_HAND))
        }
    }

    @Test
    fun `generic script on windows is powershell not ssh`() {
        val methods = installMethodsFor(DeviceOs.WINDOWS, InstallRoute.GENERIC_SCRIPT)
        assertEquals(InstallMethod.POWERSHELL, methods.first())
        assertFalse(methods.contains(InstallMethod.SSH))
    }

    @Test
    fun `cloud has only adapter on both install routes`() {
        assertEquals(
            listOf(InstallMethod.ADAPTER),
            installMethodsFor(DeviceOs.CLOUD, InstallRoute.GENERIC_SCRIPT),
        )
        assertEquals(
            listOf(InstallMethod.ADAPTER),
            installMethodsFor(DeviceOs.CLOUD, InstallRoute.V5_SSH),
        )
    }
}
