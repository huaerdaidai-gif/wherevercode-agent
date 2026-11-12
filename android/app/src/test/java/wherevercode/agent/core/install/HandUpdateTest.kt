package wherevercode.agent.core.install

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.core.transport.RemoteHello

/**
 * Hand 自更新测试（2026-11-12，JUnit4 纯 JVM 测试）。
 *
 * 只测纯逻辑：版本比对与更新状态机（stage → apply → poll hello 验
 * 版本 → 失败回滚）。不碰网络、不碰 Hand、不碰 Android。
 * 验版本超时/间隔用小值传参（默认 60 秒 / 2 秒只在生产用）。
 */
class HandUpdateTest {

    @Test
    fun `same version is up to date`() {
        assertEquals(HandVersionStatus.UP_TO_DATE, checkHandVersion("237ad2080ea3", "237ad2080ea3"))
    }

    @Test
    fun `different version is outdated`() {
        assertEquals(HandVersionStatus.OUTDATED, checkHandVersion("237ad2080ea3", "aaaa00000000"))
    }

    @Test
    fun `blank remote version is unknown not outdated`() {
        // 旧版 Hand 不报版本：不知道，不得当成落后（也不能当成最新）。
        assertEquals(HandVersionStatus.UNKNOWN, checkHandVersion("237ad2080ea3", ""))
    }

    @Test
    fun `blank bundled version is unknown`() {
        assertEquals(HandVersionStatus.UNKNOWN, checkHandVersion("", "237ad2080ea3"))
    }

    private class FakeAdmin(
        var stageFails: Boolean = false,
        var applyFails: Boolean = false,
        var rollbackFails: Boolean = false,
    ) : HandAdminPort {
        val calls = mutableListOf<String>()

        override suspend fun stageUpdate(
            deviceId: String,
            content: ByteArray,
            sha256: String,
            version: String,
        ): Result<String> {
            calls += "stage:$version"
            return if (stageFails) Result.failure(Exception("sha 不一致")) else Result.success(version)
        }

        override suspend fun applyUpdate(deviceId: String): Result<String> {
            calls += "apply"
            return if (applyFails) Result.failure(Exception("连接断了")) else Result.success("")
        }

        override suspend fun rollbackUpdate(deviceId: String): Result<String> {
            calls += "rollback"
            return if (rollbackFails) Result.failure(Exception("备份找不到了")) else Result.success("")
        }
    }

    private fun hello(deviceId: String, version: String) = RemoteHello(
        deviceId = deviceId,
        displayName = "Mac",
        publicKey = ByteArray(32),
        agents = emptyList(),
        handVersion = version,
    )

    private fun run(
        admin: FakeAdmin,
        probeVersions: List<String>,
        expectedDeviceId: String = "mac",
    ): HandUpdateOutcome {
        var probeCount = 0
        val runner = HandUpdateRunner(
            admin = admin,
            probeHello = {
                val version = probeVersions.getOrNull(probeCount) ?: probeVersions.last()
                probeCount++
                hello("mac", version)
            },
        )
        return runBlocking {
            runner.run(
                deviceId = "mac",
                expectedDeviceId = expectedDeviceId,
                content = "new hand".toByteArray(),
                sha256 = "0".repeat(64),
                newVersion = "237ad2080ea3",
                oldVersion = "aaaa00000000",
                verifyTimeoutMs = 300,
                verifyIntervalMs = 50,
            )
        }
    }

    @Test
    fun `update succeeds when hello reports new version`() {
        val admin = FakeAdmin()
        // 第一次探还是旧版，第二次新版起来了。
        val outcome = run(admin, listOf("aaaa00000000", "237ad2080ea3"))
        assertTrue(outcome is HandUpdateOutcome.Updated)
        assertEquals("237ad2080ea3", (outcome as HandUpdateOutcome.Updated).newVersion)
        assertEquals(listOf("stage:237ad2080ea3", "apply"), admin.calls)
    }

    @Test
    fun `stage failure stops before apply`() {
        val admin = FakeAdmin(stageFails = true)
        val outcome = run(admin, listOf("237ad2080ea3"))
        assertTrue(outcome is HandUpdateOutcome.Failed)
        assertEquals(listOf("stage:237ad2080ea3"), admin.calls)
    }

    @Test
    fun `apply failure stops without rollback`() {
        // apply 没发出去/被拒：live 文件没动，不用回滚。
        val admin = FakeAdmin(applyFails = true)
        val outcome = run(admin, listOf("237ad2080ea3"))
        assertTrue(outcome is HandUpdateOutcome.Failed)
        assertEquals(listOf("stage:237ad2080ea3", "apply"), admin.calls)
    }

    @Test
    fun `verify timeout rolls back to old version`() {
        val admin = FakeAdmin()
        val outcome = run(admin, listOf("aaaa00000000"))
        assertTrue(outcome is HandUpdateOutcome.RolledBack)
        val rolled = outcome as HandUpdateOutcome.RolledBack
        assertEquals("aaaa00000000", rolled.oldVersion)
        assertEquals(listOf("stage:237ad2080ea3", "apply", "rollback"), admin.calls)
    }

    @Test
    fun `verify timeout with failed rollback is failed`() {
        val admin = FakeAdmin(rollbackFails = true)
        val outcome = run(admin, listOf("aaaa00000000"))
        assertTrue(outcome is HandUpdateOutcome.Failed)
        assertTrue((outcome as HandUpdateOutcome.Failed).reason.contains("回滚也失败"))
    }

    @Test
    fun `hello with wrong device id does not count as verified`() {
        // 验版本必须同时验 device_id：别的设备报了新版也不能算这台好了。
        val admin = FakeAdmin()
        val outcome = run(admin, listOf("237ad2080ea3"), expectedDeviceId = "other-device")
        assertTrue(outcome is HandUpdateOutcome.RolledBack)
    }
}
