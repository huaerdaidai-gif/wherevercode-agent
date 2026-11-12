package wherevercode.agent.core.install

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import wherevercode.agent.ui.UiKit
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一键安装的 UI 控制器（App 内流程的“管理 → 添加设备 → 选系统 →
 * 安装/配对 → 授权 → 在线管理”中的“安装”一段）。
 *
 * 只负责对话框与后台执行；装好后交回 MainActivity 现有的
 * hello → 指纹 → 配对流程（onPairInstalled），不再自己造配对。
 * 凭据（密码/私钥）只放内存、用完即清，绝不持久化。
 */
class DeviceInstallController(
    private val activity: Activity,
    private val assetReader: AssetReader,
    private val sessionFactory: SshSessionFactory,
    /** 装好后：MainActivity 去 hello → 指纹 → 配对。 */
    private val onPairInstalled: (displayName: String, host: String, port: Int) -> Unit,
    /** 已装 Hand：打开现有的手动配对表单。 */
    private val onPairExisting: () -> Unit,
) {
    private var bundle: HandInstallBundle? = null

    /** 流程入口：先读安装包，再选安装路线（通用脚本在前、V5 一键安装在后）。 */
    fun start() {
        Thread {
            val loaded = try {
                HandInstallBundleLoader.load(assetReader)
            } catch (_: Exception) {
                null
            }
            activity.runOnUiThread {
                if (loaded == null) {
                    toast("安装包读不到（assets/install 缺文件），请重装 App。")
                    return@runOnUiThread
                }
                bundle = loaded
                showRouteSelector()
            }
        }.start()
    }

    /**
     * 安装路线选择（maintainer 2026-11-11 10:30 定：通用在前、独有在后）。
     * 路 1（局域网发现 + 配对）不在这个控制器里——它在 MainActivity
     * 的添加设备流程里先走，走到这里说明设备上还没 Hand、要装一个。
     */
    private fun showRouteSelector() {
        val routes = listOf(InstallRoute.GENERIC_SCRIPT, InstallRoute.V5_SSH)
        AlertDialog.Builder(activity)
            .setTitle("在新设备上安装 Hand：选方式")
            .setItems(
                routes.map { "${it.displayName}\n${it.note}" }.toTypedArray(),
            ) { _, which ->
                showOsSelector(routes[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showOsSelector(route: InstallRoute) {
        val options = DeviceOs.values()
        AlertDialog.Builder(activity)
            .setTitle("添加设备：选系统")
            .setItems(options.map { it.displayName }.toTypedArray()) { _, which ->
                showMethodSelector(options[which], route)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showMethodSelector(os: DeviceOs, route: InstallRoute) {
        val methods = installMethodsFor(os, route)
        AlertDialog.Builder(activity)
            .setTitle("添加设备：${os.displayName} 怎么装")
            .setItems(methods.map { it.displayName }.toTypedArray()) { _, which ->
                val method = methods[which]
                val b = bundle ?: return@setItems
                when (val step = InstallFlow.stepAfterMethod(os, method, b)) {
                    is InstallStep.SshForm -> showSshForm(step.os)
                    is InstallStep.ShowCommand -> showCommandDialog(step)
                    is InstallStep.Guidance -> {
                        if (method == InstallMethod.EXISTING_HAND) {
                            onPairExisting()
                        } else {
                            showGuidanceDialog(step)
                        }
                    }
                    else -> Unit
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showGuidanceDialog(step: InstallStep.Guidance) {
        AlertDialog.Builder(activity)
            .setTitle(step.method.displayName)
            .setMessage(step.text)
            .setPositiveButton("知道了", null)
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSshForm(os: DeviceOs) {
        val nameInput = EditText(activity).apply {
            hint = "显示名，如：我的 Mac"
            setSingleLine(true)
        }
        val hostInput = EditText(activity).apply {
            hint = "SSH 地址，如：198.51.100.10"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val portInput = EditText(activity).apply {
            setText("22")
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val userInput = EditText(activity).apply {
            hint = "SSH 用户名，如：test-user"
            setSingleLine(true)
        }
        val authGroup = RadioGroup(activity).apply { orientation = RadioGroup.HORIZONTAL }
        val passwordRadio = RadioButton(activity).apply { text = "密码"; isChecked = true }
        val keyRadio = RadioButton(activity).apply { text = "私钥粘贴" }
        authGroup.addView(passwordRadio)
        authGroup.addView(keyRadio)
        val passwordInput = EditText(activity).apply {
            hint = "SSH 密码（只用于本次安装，不保存）"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val keyInput = EditText(activity).apply {
            hint = "私钥内容（PEM/OpenSSH，粘贴；只用于本次安装，不保存）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            visibility = android.view.View.GONE
        }
        val passphraseInput = EditText(activity).apply {
            hint = "私钥口令（没有就空着）"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            visibility = android.view.View.GONE
        }
        authGroup.setOnCheckedChangeListener { _, checkedId ->
            val useKey = checkedId == keyRadio.id
            passwordInput.visibility = if (useKey) android.view.View.GONE else android.view.View.VISIBLE
            keyInput.visibility = if (useKey) android.view.View.VISIBLE else android.view.View.GONE
            passphraseInput.visibility = if (useKey) android.view.View.VISIBLE else android.view.View.GONE
        }
        val handPortInput = EditText(activity).apply {
            setText(InstallCommand.HAND_PORT.toString())
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val note = TextView(activity).apply {
            text = "SSH 只用于本次安装：密码/私钥只放内存、用完即清，不保存。首次连接会请你核对目标机的主机密钥指纹。"
            setPadding(0, dp(8), 0, 0)
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(nameInput)
            addView(hostInput)
            addView(portInput)
            addView(userInput)
            addView(authGroup)
            addView(passwordInput)
            addView(keyInput)
            addView(passphraseInput)
            addView(TextView(activity).apply { text = "Hand 端口（默认 18743）"; setPadding(0, dp(8), 0, 0) })
            addView(handPortInput)
            addView(note)
        }
        AlertDialog.Builder(activity)
            .setTitle("SSH 一键安装：${os.displayName}")
            .setView(container)
            .setPositiveButton("连接并安装") { _, _ ->
                val displayName = nameInput.text.toString().trim()
                val host = hostInput.text.toString().trim()
                val sshPort = portInput.text.toString().trim().toIntOrNull() ?: 22
                val username = userInput.text.toString().trim()
                val handPort = handPortInput.text.toString().trim().toIntOrNull()
                    ?: InstallCommand.HAND_PORT
                val useKey = keyRadio.isChecked
                val password = if (useKey) null else passwordInput.text.toString().toCharArray()
                val privateKey = if (useKey) {
                    keyInput.text.toString().trim().toByteArray(Charsets.UTF_8)
                        .takeIf { it.isNotEmpty() }
                } else {
                    null
                }
                val passphrase = if (useKey) {
                    passphraseInput.text.toString().toCharArray()
                        .takeIf { it.isNotEmpty() }
                } else {
                    null
                }
                val target = SshTarget(host = host, port = sshPort, username = username)
                val errors = target.validate().toMutableList()
                if (displayName.isEmpty()) errors += "显示名不能为空"
                if (!useKey && (password == null || password.isEmpty())) errors += "SSH 密码不能为空"
                if (useKey && privateKey == null) errors += "私钥内容不能为空"
                if (handPort !in 1..65535) errors += "Hand 端口必须在 1~65535 之间"
                if (errors.isNotEmpty()) {
                    toast(errors.joinToString("；"))
                    password?.fill('\u0000')
                    passphrase?.fill('\u0000')
                    return@setPositiveButton
                }
                runSshInstall(os, displayName, target, password, privateKey, passphrase, handPort)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun runSshInstall(
        os: DeviceOs,
        displayName: String,
        target: SshTarget,
        password: CharArray?,
        privateKey: ByteArray?,
        passphrase: CharArray?,
        handPort: Int,
    ) {
        val b = bundle ?: return
        val statusView = TextView(activity).apply {
            text = "准备安装…"
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val progressDialog = AlertDialog.Builder(activity)
            .setTitle("正在安装 Hand：${target.host}")
            .setView(statusView)
            .setNegativeButton("取消") { _, _ -> }
            .setCancelable(false)
            .show()
        val appendStatus: (String) -> Unit = { line ->
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    statusView.append("\n$line")
                }
            }
        }
        Thread {
            try {
                val outcome = SshInstaller.install(
                    factory = sessionFactory,
                    target = target,
                    password = password,
                    privateKey = privateKey,
                    privateKeyPassphrase = passphrase,
                    bundle = b,
                    port = handPort,
                    deviceId = null,
                    displayName = displayName,
                    onHostKey = { fingerprint -> confirmHostKey(target.host, fingerprint) },
                    onProgress = appendStatus,
                )
                activity.runOnUiThread {
                    try {
                        progressDialog.dismiss()
                    } catch (_: Exception) {
                    }
                    when (outcome) {
                        is InstallOutcome.Installed -> {
                            toast("Hand 已装好：${outcome.deviceId}，正在配对…")
                            onPairInstalled(displayName, outcome.host, outcome.port)
                        }
                        is InstallOutcome.Failed -> {
                            AlertDialog.Builder(activity)
                                .setTitle("安装失败")
                                .setMessage(outcome.message)
                                .setPositiveButton("重试") { _, _ -> showSshForm(os) }
                                .setNegativeButton("取消", null)
                                .show()
                        }
                    }
                }
            } finally {
                password?.fill('\u0000')
                passphrase?.fill('\u0000')
            }
        }.start()
    }

    /**
     * 主机密钥确认（TOFU）：后台线程调起，UI 线程弹对话框、等用户点，
     * 最多等 120 秒；用户拒绝或超时都返回 false（断开，不静默信任）。
     */
    private fun confirmHostKey(host: String, fingerprint: String): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return false
        val latch = CountDownLatch(1)
        val answer = AtomicBoolean(false)
        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                .setTitle("核对主机密钥")
                .setMessage(
                    "首次连 $host，主机密钥指纹：\n$fingerprint\n\n" +
                        "请在目标机上核对（ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub 等）一致后再确认。不一致不要点确认。",
                )
                .setPositiveButton("指纹一致，继续") { _, _ ->
                    answer.set(true)
                    latch.countDown()
                }
                .setNegativeButton("取消安装") { _, _ -> latch.countDown() }
                .setCancelable(false)
                .show()
        }
        return try {
            latch.await(120, TimeUnit.SECONDS) && answer.get()
        } catch (_: InterruptedException) {
            false
        }
    }

    private fun showCommandDialog(step: InstallStep.ShowCommand) {
        val commandView = TextView(activity).apply {
            text = step.command
            setTextIsSelectable(true)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val scroll = ScrollView(activity).apply { addView(commandView) }
        val noteView = TextView(activity).apply {
            text = step.note
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll)
            addView(noteView)
        }
        AlertDialog.Builder(activity)
            .setTitle(step.method.displayName)
            .setView(container)
            .setPositiveButton("复制命令") { _, _ ->
                copyText("安装命令", step.command)
            }
            .setNeutralButton("分享安装包") { _, _ -> shareBundle() }
            .setNegativeButton("我已装好，继续配对") { _, _ -> onPairExisting() }
            .show()
    }

    /** 把 4 个安装包文件写进 cache，FileProvider 分享出去。 */
    private fun shareBundle() {
        val b = bundle ?: return
        try {
            val dir = File(activity.cacheDir, "install")
            dir.mkdirs()
            val files = listOf(
                HandInstallBundleLoader.INSTALL_SH to b.installSh,
                HandInstallBundleLoader.INSTALL_PS1 to b.installPs1,
                HandInstallBundleLoader.UNINSTALL_SH to b.uninstallSh,
                HandInstallBundleLoader.HAND_SERVER_PY to b.handServerPy,
            )
            val uris = ArrayList<Uri>()
            for ((name, bytes) in files) {
                val file = File(dir, name)
                file.writeBytes(bytes)
                uris += FileProvider.getUriForFile(
                    activity,
                    "${activity.packageName}.fileprovider",
                    file,
                )
            }
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "application/octet-stream"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, "分享 V5 Hand 安装包"))
        } catch (e: Exception) {
            toast("分享安装包失败：${e.message}")
        }
    }

    private fun copyText(label: String, text: String) {
        try {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
            toast("已复制：$label")
        } catch (e: Exception) {
            toast("复制失败：${e.message}")
        }
    }

    private fun dp(value: Int): Int = UiKit.dp(activity, value)

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }
}
