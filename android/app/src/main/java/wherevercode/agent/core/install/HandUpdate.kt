package wherevercode.agent.core.install

import wherevercode.agent.core.transport.RemoteHello

/**
 * Hand 自更新（2026-11-12，maintainer 定：App 更新后自动同步已连接设备的 Hand）。
 *
 * 背景：App 更新后，已连接设备的 Hand 还是旧版，App 发新东西旧 Hand
 * 会拒收（文件夹改法就是现成例子：App 发项目文件夹，旧 Hand 报
 * wrong_folder）。本文件是更新逻辑的纯 Kotlin 层：版本比对 + 更新
 * 状态机（stage → apply → poll hello 验版本 → 失败回滚）；不碰
 * Android UI、不碰网络——发消息与 hello 探针由调用方（HandClient /
 * MainActivity）以接口注入，方便单测。
 *
 * 版本定义：Hand 代码版本 = hand_server.py 内容 SHA-256 前 12 位，
 * 与 HandInstallBundle.versionOf() 同款定义（App 内置安装包的版本
 * 就是这么算的）。Hand 在 hello 回包里报 hand_version，App 比对。
 */

/** 远端 Hand 相对 App 内置安装包的版本状态。 */
enum class HandVersionStatus {
    /** 版本一致，不用更新。 */
    UP_TO_DATE,
    /** 远端落后，需要更新。 */
    OUTDATED,
    /** 不知道（旧版 Hand 没报 hand_version，或 App 没读到内置版本）。 */
    UNKNOWN,
}

/**
 * 版本比对：bundled = App 内置安装包的版本，remote = 远端 hello 报的
 * hand_version。任一为空都是 UNKNOWN——绝不把“不知道”当成最新、
 * 也不得当成落后（旧版 Hand 不报版本，只能走首次更新路径）。
 */
fun checkHandVersion(bundled: String, remote: String): HandVersionStatus {
    if (bundled.isBlank() || remote.isBlank()) return HandVersionStatus.UNKNOWN
    return if (bundled == remote) HandVersionStatus.UP_TO_DATE
    else HandVersionStatus.OUTDATED
}

/** 更新流程的每一步（UI 显示用）。 */
enum class HandUpdateStep {
    STAGING,
    APPLYING,
    VERIFYING,
    ROLLING_BACK,
    DONE,
}

/** 更新结果：只报告真实发生的，不编造。 */
sealed class HandUpdateOutcome {
    /** 新版起来了，hello 验到的版本与指纹都对。 */
    data class Updated(val newVersion: String) : HandUpdateOutcome()
    /** 失败了，但已回滚到旧版（旧版仍在跑，设备没断）。 */
    data class RolledBack(val reason: String, val oldVersion: String) : HandUpdateOutcome()
    /** 失败了，回滚也没成功——设备可能连不上，必须如实报给用户。 */
    data class Failed(val reason: String) : HandUpdateOutcome()
    /** 用户在确认框点了取消（什么都没发）。 */
    object Cancelled : HandUpdateOutcome()
}

/** Hand 管理调用的端口（HandClient 实现；单测用假实现）。 */
interface HandAdminPort {
    /** stage：把新文件发过去暂存（Hand 验 sha256 + py_compile）。 */
    suspend fun stageUpdate(deviceId: String, content: ByteArray, sha256: String, version: String): Result<String>

    /** apply：让 Hand 原子替换 live 文件并重启。 */
    suspend fun applyUpdate(deviceId: String): Result<String>

    /** rollback：让 Hand 恢复备份并重启。 */
    suspend fun rollbackUpdate(deviceId: String): Result<String>
}

/** hello 探针：连设备拿一次 hello（失败返回 null，不抛错）。 */
typealias HelloProbe = suspend (deviceId: String) -> RemoteHello?

/**
 * 更新状态机：stage → apply → 每 2 秒 poll hello、最多 60 秒 → 验
 * 版本 + device_id 不变 → Updated。apply 失败或 60 秒内验不过，
 * 发 rollback；rollback 成功 → RolledBack，rollback 也失败 → Failed。
 *
 * 旧版 Hand（updateSupported=false）根本没有 update_hand 动作，
 * 调用前由 UI 层挡住（提示走首次部署/一键安装），不进状态机。
 */
class HandUpdateRunner(
    private val admin: HandAdminPort,
    private val probeHello: HelloProbe,
    private val onStep: (HandUpdateStep) -> Unit = {},
) {
    suspend fun run(
        deviceId: String,
        expectedDeviceId: String,
        content: ByteArray,
        sha256: String,
        newVersion: String,
        oldVersion: String,
        /** 验版本超时（默认 60 秒；单测传小值）。 */
        verifyTimeoutMs: Long = 60_000,
        /** poll hello 间隔（默认 2 秒；单测传小值）。 */
        verifyIntervalMs: Long = 2_000,
    ): HandUpdateOutcome {
        onStep(HandUpdateStep.STAGING)
        val staged = admin.stageUpdate(deviceId, content, sha256, newVersion)
        if (staged.isFailure) {
            return HandUpdateOutcome.Failed("暂存失败：${staged.exceptionOrNull()?.message ?: "未知错误"}")
        }
        onStep(HandUpdateStep.APPLYING)
        val applied = admin.applyUpdate(deviceId)
        if (applied.isFailure) {
            // apply 没发出去/被拒：live 文件没动，不用回滚。
            return HandUpdateOutcome.Failed("应用失败（旧版未动）：${applied.exceptionOrNull()?.message ?: "未知错误"}")
        }
        onStep(HandUpdateStep.VERIFYING)
        val deadline = System.currentTimeMillis() + verifyTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            val hello = probeHello(deviceId)
            if (hello != null && hello.handVersion == newVersion &&
                hello.deviceId == expectedDeviceId
            ) {
                onStep(HandUpdateStep.DONE)
                return HandUpdateOutcome.Updated(newVersion)
            }
            kotlinx.coroutines.delay(verifyIntervalMs)
        }
        // 60 秒没验到新版：回滚。
        onStep(HandUpdateStep.ROLLING_BACK)
        val rolled = admin.rollbackUpdate(deviceId)
        return if (rolled.isSuccess) {
            HandUpdateOutcome.RolledBack(
                reason = "60 秒内没验到新版 $newVersion，已回滚",
                oldVersion = oldVersion,
            )
        } else {
            HandUpdateOutcome.Failed(
                "60 秒内没验到新版 $newVersion，回滚也失败（${rolled.exceptionOrNull()?.message ?: "未知错误"}）——设备可能连不上，请检查后手动处理",
            )
        }
    }
}

/** “自动同步 Hand”开关的存储（默认关；只在用户打开后才跳过确认框）。 */
interface HandSyncSettingsStore {
    fun isAutoSyncEnabled(): Boolean
    fun setAutoSyncEnabled(enabled: Boolean)
}
