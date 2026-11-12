package wherevercode.agent.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.security.MessageDigest
import wherevercode.agent.core.install.HandAdminPort
import wherevercode.agent.core.install.HandSyncSettingsStore
import wherevercode.agent.core.install.HandUpdateOutcome
import wherevercode.agent.core.install.HandUpdateRunner
import wherevercode.agent.core.install.HandUpdateStep
import wherevercode.agent.core.install.HandVersionStatus
import wherevercode.agent.core.install.checkHandVersion
import wherevercode.agent.core.install.HandInstallBundle
import wherevercode.agent.core.transport.DiscoveredPeer
import wherevercode.agent.core.mcp.HandAdminResult
import wherevercode.agent.core.mcp.HandClient
import wherevercode.agent.core.transport.RemoteDevice
import wherevercode.agent.core.transport.RemoteHello

/**
 * Hand 自更新的 UI 层（2026-11-12，maintainer 定：App 更新后自动同步已连接
 * 设备的 Hand）。
 *
 * 入口：设备卡上的“更新 Hand”按钮（AgentScreenController）与设置页
 * 的“Hand 同步”分节（MainActivity）。流程：
 * 读内置安装包 → 比对版本 → 已是最新就报一声；旧版（没报版本）
 * 不支持自更新，提示走首次部署（一键安装）；落后就先确认（除非
 * “自动同步 Hand”开关开着）→ stage → apply → 每 2 秒 poll hello
 * 验版本（最多 60 秒）→ 失败自动回滚。结果如实报，不编造。
 *
 * 本控制器不自己连网：管理调用走 [HandClient]，hello 探针与设备
 * 记录持久化由 MainActivity 注入（它已有现成的 fetchHello 与
 * RemoteDeviceStore）。
 */
class HandUpdateController(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val settingsStore: HandSyncSettingsStore,
    /** 读 App 内置安装包（assets/install/hand_server.py 等 4 个文件）。 */
    private val loadBundle: () -> HandInstallBundle?,
    /** 设备 → 对端（配对记录里的公钥/地址），没有返回 null。 */
    private val peerFor: (RemoteDevice) -> DiscoveredPeer?,
    /**
     * 对端 → HandClient（按对端构造，与 MainActivity.wsHandClient 同款；
     * 管理调用不带任务凭证，但建会话仍需对端公钥）。没有返回 null。
     */
    private val handClientFor: (DiscoveredPeer) -> HandClient?,
    /** 连设备拿一次 hello；失败返回 null（不抛错）。 */
    private val probeHello: suspend (RemoteDevice) -> RemoteHello?,
    /** 更新成功后把新版本写回设备记录（RemoteDeviceStore.saveDevice）。 */
    private val persistHandVersion: (RemoteDevice, String) -> Unit,
    /** 旧版 Hand（不支持自更新）时，跳去首次部署（一键安装），可空。 */
    private val onRequestFirstInstall: ((RemoteDevice) -> Unit)? = null,
) {
    /** 设备卡“更新 Hand”按钮的入口。 */
    fun updateDevice(device: RemoteDevice) {
        val bundle = loadBundle()
            ?: return toast("内置安装包读不到，无法更新 Hand（请重装 App 后再试）")
        when (checkHandVersion(bundle.version, device.handVersion)) {
            HandVersionStatus.UP_TO_DATE -> {
                toast("${device.displayName} 的 Hand 已是最新（${bundle.version}）")
            }
            HandVersionStatus.UNKNOWN -> showOldHandDialog(device)
            HandVersionStatus.OUTDATED -> {
                if (settingsStore.isAutoSyncEnabled()) {
                    runUpdate(device, bundle)
                } else {
                    showConfirmDialog(device, bundle)
                }
            }
        }
    }

    /** 设置页“检查全部设备更新”：只检查、不自动更新，报汇总。 */
    fun checkAllDevices(devices: List<RemoteDevice>) {
        val bundle = loadBundle()
            ?: return toast("内置安装包读不到，无法检查（请重装 App 后再试）")
        scope.launch(Dispatchers.Default) {
            var upToDate = 0
            var outdated = 0
            var unknown = 0
            var unreachable = 0
            for (device in devices) {
                val hello = probeHello(device)
                if (hello == null) {
                    unreachable++
                    continue
                }
                persistHandVersion(device, hello.handVersion)
                when (checkHandVersion(bundle.version, hello.handVersion)) {
                    HandVersionStatus.UP_TO_DATE -> upToDate++
                    HandVersionStatus.OUTDATED -> outdated++
                    HandVersionStatus.UNKNOWN -> unknown++
                }
            }
            val summary = "检查完成：${devices.size} 台设备，" +
                "$upToDate 台最新，$outdated 台可更新，$unknown 台旧版（不支持自更新），$unreachable 台连不上"
            withContext(Dispatchers.Main) { toast(summary) }
        }
    }

    private fun showOldHandDialog(device: RemoteDevice) {
        AlertDialog.Builder(activity)
            .setTitle("Hand 版本未知")
            .setMessage(
                "${device.displayName} 的 Hand 是旧版，不支持自更新。\n\n" +
                    "首次部署请走一键安装（按这台设备的现有方式装新版 Hand），" +
                    "装好新版之后，以后 App 更新就能自动同步了。",
            )
            .setPositiveButton("去一键安装") { _, _ ->
                onRequestFirstInstall?.invoke(device)
                    ?: toast("请在设备页对 ${device.displayName} 用一键安装装新版 Hand")
            }
            .setNegativeButton("知道了", null)
            .show()
    }

    private fun showConfirmDialog(device: RemoteDevice, bundle: HandInstallBundle) {
        AlertDialog.Builder(activity)
            .setTitle("更新 Hand？")
            .setMessage(
                "设备：${device.displayName}\n" +
                    "旧版本：${device.handVersion.ifBlank { "未知" }} → 新版本：${bundle.version}\n\n" +
                    "会先备份旧版 Hand，更新失败自动回滚，设备不会断连。\n" +
                    "（想以后不用每次确认，去设置页打开“自动同步 Hand”。）",
            )
            .setPositiveButton("更新") { _, _ -> runUpdate(device, bundle) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun runUpdate(device: RemoteDevice, bundle: HandInstallBundle) {
        val peer = peerFor(device)
            ?: return toast("找不到 ${device.displayName} 的配对记录，无法更新（请重新配对）")
        val handClient = handClientFor(peer)
            ?: return toast("建 ${device.displayName} 的会话失败，无法更新（请重新配对）")
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(bundle.handServerPy)
            .joinToString("") { "%02x".format(it) }
        val statusView = TextView(activity).apply {
            setPadding(48, 32, 48, 32)
            text = "正在暂存新版 Hand…"
        }
        val progress = AlertDialog.Builder(activity)
            .setTitle("正在更新 ${device.displayName} 的 Hand")
            .setView(statusView)
            .setCancelable(false)
            .show()
        val admin = object : HandAdminPort {
            override suspend fun stageUpdate(
                deviceId: String,
                content: ByteArray,
                sha256: String,
                version: String,
            ): Result<String> = adminCall(
                handClient,
                peer,
                mapOf(
                    "phase" to "stage",
                    "content_b64" to Base64.encodeToString(content, Base64.NO_WRAP),
                    "sha256" to sha256,
                    "version" to version,
                ),
            )

            override suspend fun applyUpdate(deviceId: String): Result<String> =
                adminCall(handClient, peer, mapOf("phase" to "apply"))

            override suspend fun rollbackUpdate(deviceId: String): Result<String> =
                adminCall(handClient, peer, mapOf("phase" to "rollback"))
        }
        val runner = HandUpdateRunner(admin, probeHello = { probeHello(device) }) { step ->
            activity.runOnUiThread {
                statusView.text = when (step) {
                    HandUpdateStep.STAGING -> "正在暂存新版 Hand…"
                    HandUpdateStep.APPLYING -> "正在替换并重启 Hand…"
                    HandUpdateStep.VERIFYING -> "新版重启中，正在验证版本…"
                    HandUpdateStep.ROLLING_BACK -> "验证失败，正在回滚到旧版…"
                    HandUpdateStep.DONE -> "已完成"
                }
            }
        }
        scope.launch(Dispatchers.Default) {
            val outcome = runner.run(
                deviceId = device.deviceId,
                expectedDeviceId = device.deviceId,
                content = bundle.handServerPy,
                sha256 = sha256,
                newVersion = bundle.version,
                oldVersion = device.handVersion,
            )
            withContext(Dispatchers.Main) {
                progress.dismiss()
                when (outcome) {
                    is HandUpdateOutcome.Updated -> {
                        persistHandVersion(device, outcome.newVersion)
                        showResultDialog(
                            "更新成功",
                            "${device.displayName} 的 Hand 已更新到 ${outcome.newVersion}（旧版已备份）。",
                        )
                    }
                    is HandUpdateOutcome.RolledBack -> {
                        showResultDialog(
                            "更新失败，已回滚",
                            "${outcome.reason}。旧版 ${outcome.oldVersion} 仍在运行，设备没断连。",
                        )
                    }
                    is HandUpdateOutcome.Failed -> {
                        showResultDialog("更新失败", outcome.reason)
                    }
                    HandUpdateOutcome.Cancelled -> {
                        // 不会走到这里（取消只在确认框，runUpdate 之前）。
                    }
                }
            }
        }
    }

    private suspend fun adminCall(
        handClient: HandClient,
        peer: DiscoveredPeer,
        args: Map<String, String>,
    ): Result<String> {
        return when (val result = handClient.requestHandAdmin(peer, "update_hand", args)) {
            is HandAdminResult.Ok -> Result.success(result.args["version"] ?: "")
            is HandAdminResult.Failed -> Result.failure(Exception(result.reason))
        }
    }

    private fun showResultDialog(title: String, message: String) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }
}

/** “自动同步 Hand”开关的 SharedPreferences 实现（默认关）。 */
class SharedPrefsHandSyncSettingsStore(
    context: Context,
) : HandSyncSettingsStore {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun isAutoSyncEnabled(): Boolean =
        prefs.getBoolean(KEY_AUTO_SYNC, false)

    override fun setAutoSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SYNC, enabled).apply()
    }

    companion object {
        const val PREFS_NAME: String = "wherevercode_hand_sync"
        const val KEY_AUTO_SYNC: String = "auto_sync_hand"
    }
}
