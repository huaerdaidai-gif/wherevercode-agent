package wherevercode.agent.ui

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.runBlocking
import wherevercode.agent.R
import wherevercode.agent.brain.oauth.UrlConnectionOAuthHttpTransport
import wherevercode.agent.update.AppUpdateChecker
import wherevercode.agent.update.AppUpdateError
import wherevercode.agent.update.AppUpdateInstaller
import wherevercode.agent.update.DriveFileInfo
import wherevercode.agent.update.DriveUpdateSource
import wherevercode.agent.update.GoogleDriveAuth
import wherevercode.agent.update.UpdateDecision
import wherevercode.agent.update.UrlConnectionDriveHttpPort
import wherevercode.agent.update.formatBytes
import wherevercode.agent.update.shortChecksum
import java.io.File

/**
 * 设置页“关于”分节里的“应用更新”控制器。
 *
 * 一键更新的界面部分：状态行 + 检查更新 / 下载并安装 / 连接（断开）
 * Google Drive / 设置客户端 ID。逻辑在
 * [AppUpdateChecker] / [GoogleDriveAuth] / [DriveUpdateSource] 里，
 * 本类只做接线（后台线程 + UI 回调），照 SIWC 登录的线程模式
 * （Thread + runBlocking，后台跑、UI 线程更新）。
 *
 * 状态机（状态行永远说实话）：
 * - 未配客户端 ID → 告诉用户去 Google Cloud Console 建（桌面应用）
 *   并填进来；检查/下载按钮不可用。
 * - 未登录 → “连接 Google Drive”登录（只读权限，仅读更新包文件）。
 * - 已登录 → 可检查；有新版才出现“下载并安装”；下载完校验 SHA-256
 *   通过才调安装器；系统没开“允许安装未知应用”时不静默失败，给
 *   “去开启”按钮带用户去开，回来再点安装（已下载的包不再重下）。
 * - 设置页打开时，若已登录且上次检查超过 5 分钟，自动检查一次。
 *
 * 客户端 ID 是公开值（不是密钥），存普通 SharedPreferences；客户
 * 端密钥（client_secret）存在本机 Keystore（见 GoogleDriveAuth）。
 * 密钥必须填：Google 的桌面应用客户端在令牌端点强制要求提供
 * client_secret，即使走了 PKCE——2026-11-11 真机实测，不带密钥换
 * 码必报 `invalid_request: client_secret is missing.`。Drive token
 * 存 Keystore（见 GoogleDriveAuth）。
 */
class AppUpdateSettings(
    private val activity: Activity,
    private val checker: AppUpdateChecker,
    private val auth: GoogleDriveAuth,
    private val note: (String) -> Unit,
    private val openAuthUrl: (String) -> Unit,
    private val prefs: SharedPreferences,
) {
    private lateinit var statusView: TextView
    private lateinit var buttonCheckUpdate: TextView
    private lateinit var buttonDownloadUpdate: TextView
    private lateinit var buttonDriveSignIn: TextView
    private lateinit var buttonDriveSignOut: TextView
    private lateinit var driveClientIdRow: TextView
    private lateinit var driveClientSecretRow: TextView

    @Volatile
    private var busy: Boolean = false

    @Volatile
    private var driveSigningIn: Boolean = false

    /** 本轮检查发现的远端文件（有新版时才非空）。 */
    private var pendingRemote: DriveFileInfo? = null

    /** 已下载并校验通过、待安装的包（安装权限没开时留着，回来直接装）。 */
    private var pendingInstallFile: File? = null

    /** 安装被系统开关挡住时为 true：下载按钮临时变“去开启安装权限”。 */
    private var installBlocked: Boolean = false

    private var lastCheckMillis: Long = 0L

    /** 客户端 ID（公开值；空表示未配置）。 */
    fun clientId(): String = prefs.getString(KEY_CLIENT_ID, null).orEmpty().trim()

    /** 绑定设置页的更新区（bindSettings 里调一次）。 */
    fun bind(root: View) {
        statusView = root.findViewById(R.id.appUpdateStatus)
        buttonCheckUpdate = root.findViewById(R.id.buttonCheckUpdate)
        buttonDownloadUpdate = root.findViewById(R.id.buttonDownloadUpdate)
        buttonDriveSignIn = root.findViewById(R.id.buttonDriveSignIn)
        buttonDriveSignOut = root.findViewById(R.id.buttonDriveSignOut)
        driveClientIdRow = root.findViewById(R.id.driveClientIdRow)
        driveClientSecretRow = root.findViewById(R.id.driveClientSecretRow)
        buttonCheckUpdate.setOnClickListener { checkUpdate() }
        buttonDownloadUpdate.setOnClickListener { onDownloadButton() }
        buttonDriveSignIn.setOnClickListener { onDriveSignInButton() }
        buttonDriveSignOut.setOnClickListener { signOutDrive() }
        driveClientIdRow.setOnClickListener { editClientId() }
        driveClientSecretRow.setOnClickListener { editClientSecret() }
        refreshStatus()
    }

    /** 设置页每次打开时调：已登录且上次检查超过 5 分钟就自动检查一次。 */
    fun onSettingsShown() {
        if (busy) return
        if (clientId().isBlank()) return
        if (!auth.isSignedIn()) return
        if (System.currentTimeMillis() - lastCheckMillis < AUTO_CHECK_INTERVAL_MILLIS) return
        checkUpdate()
    }

    /** 下载按钮：三种动作——去开启安装权限 / 直接装已下载的包 / 下载。 */
    private fun onDownloadButton() {
        if (busy) return
        if (installBlocked && pendingInstallFile != null) {
            installBlocked = false
            activity.startActivity(
                AppUpdateInstaller.unknownSourcesIntent(activity.packageName),
            )
            note("在系统设置里打开“允许安装未知应用”，回来再点“安装更新包”。")
            refreshStatus()
            return
        }
        val pending = pendingInstallFile
        if (pending != null && pending.exists()) {
            installPending(pending)
            return
        }
        downloadAndInstall()
    }

    /** 登录按钮：登录中点按是取消（与 SIWC 的“取消登录”一致）。 */
    private fun onDriveSignInButton() {
        if (driveSigningIn) {
            auth.cancelSignIn()
            return
        }
        signInDrive()
    }

    /** 手动检查更新。 */
    fun checkUpdate() {
        if (busy) return
        val clientId = clientId()
        if (clientId.isBlank()) {
            setStatus("未设置 Google OAuth 客户端 ID：点“设置客户端 ID”，按说明在 Google Cloud Console 建一个（类型：桌面应用），把客户端 ID 填进来；再点“设置客户端密钥”把同个客户端的密钥（以 GOCSPX- 开头）填进来，只存本机 Keystore。")
            return
        }
        busy = true
        setStatus("正在检查更新…")
        refreshButtons()
        Thread {
            try {
                val decision = runBlocking { checker.check(clientId) }
                lastCheckMillis = System.currentTimeMillis()
                activity.runOnUiThread {
                    busy = false
                    when (decision) {
                        is UpdateDecision.UpToDate -> {
                            pendingRemote = null
                            setStatus(
                                "已是最新版。Drive 上的包与本机一致" +
                                    "（SHA-256 ${shortChecksum(decision.remote.sha256Checksum ?: "")}，" +
                                    "${formatBytes(decision.remote.sizeBytes)}，" +
                                    "更新于 ${decision.remote.modifiedTime}）。",
                            )
                        }
                        is UpdateDecision.UpdateAvailable -> {
                            pendingRemote = decision.remote
                            setStatus(
                                "有新版：${decision.remote.name}，" +
                                    "${formatBytes(decision.remote.sizeBytes)}，" +
                                    "Drive 更新于 ${decision.remote.modifiedTime}。点“下载并安装”。",
                            )
                        }
                        is UpdateDecision.CannotTell -> {
                            pendingRemote = null
                            setStatus("检查到了 Drive 上的包，但没有校验和、大小也与本机相同，无法确认是不是同一份。")
                        }
                    }
                    refreshButtons()
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    busy = false
                    setStatus(updateErrorMessage(e))
                    refreshButtons()
                }
            }
        }.start()
    }

    /** 下载并校验，通过后调安装器（或因系统开关被挡而留待安装）。 */
    private fun downloadAndInstall() {
        val clientId = clientId()
        if (clientId.isBlank()) {
            setStatus("未设置 Google OAuth 客户端 ID：先点“设置客户端 ID”填入，才能下载更新。")
            return
        }
        val remote = pendingRemote
        if (remote == null) {
            // 没先检查过：先检查，有新版再下（checkUpdate 完不自动下，等用户再点一次）。
            note("先点“检查更新”，有新版再点“下载并安装”。")
            checkUpdate()
            return
        }
        busy = true
        var lastShownMb = -1L
        setStatus("正在下载… 0 / ${formatBytes(remote.sizeBytes)}")
        refreshButtons()
        Thread {
            try {
                val file = runBlocking {
                    checker.downloadAndVerify(clientId) { downloaded, total ->
                        val mb = downloaded / (1024L * 1024L)
                        if (mb != lastShownMb) {
                            lastShownMb = mb
                            val totalText = if (total > 0) formatBytes(total) else formatBytes(remote.sizeBytes)
                            val text = "正在下载… ${formatBytes(downloaded)} / $totalText"
                            activity.runOnUiThread { statusView.text = text }
                        }
                    }
                }
                activity.runOnUiThread {
                    busy = false
                    pendingInstallFile = file
                    installBlocked = false
                    setStatus("已下载并校验通过（SHA-256 与 Drive 一致），正在打开安装…")
                    note("更新包已下载并校验通过，正在打开安装。")
                    refreshButtons()
                    installPending(file)
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    busy = false
                    pendingInstallFile = null
                    setStatus(updateErrorMessage(e))
                    refreshButtons()
                }
            }
        }.start()
    }

    /** 装已下载并校验过的包；系统开关没开时留着文件、给“去开启”按钮。 */
    private fun installPending(file: File) {
        if (!file.exists()) {
            pendingInstallFile = null
            setStatus("已下载的更新包找不到了，请重新点“下载并安装”。")
            refreshButtons()
            return
        }
        if (!AppUpdateInstaller.canInstallPackages(activity.packageManager)) {
            installBlocked = true
            setStatus("系统未允许本 App 安装应用：点“去开启安装权限”，在设置里打开“允许安装未知应用”，回来再点“安装更新包”。已下载的包还留着，不用重下。")
            refreshButtons()
            return
        }
        installBlocked = false
        activity.startActivity(AppUpdateInstaller.installApkIntent(activity, file))
    }

    /** 连接 Google Drive（Custom Tab 登录）。 */
    private fun signInDrive() {
        val clientId = clientId()
        if (clientId.isBlank()) {
            setStatus("未设置 Google OAuth 客户端 ID：先点“设置客户端 ID”填入，才能连接 Google Drive。")
            return
        }
        // Google 的桌面应用客户端换码强制要求 client_secret：没配就
        // 不发请求，直接告诉用户去填（发了也是 invalid_request）。
        if (!auth.isClientSecretConfigured()) {
            setStatus("未设置 Google OAuth 客户端密钥：Google 换取令牌要求提供密钥，点“设置客户端密钥”填入（Google Cloud Console 里该桌面应用客户端的密钥，以 GOCSPX- 开头），只存本机 Keystore。")
            return
        }
        if (busy) return
        busy = true
        driveSigningIn = true
        setStatus("正在打开 Google 登录…")
        refreshButtons()
        Thread {
            try {
                val request = runBlocking { auth.beginSignIn(clientId) }
                activity.runOnUiThread { openAuthUrl(request.authorizationUrl) }
                val tokens = runBlocking { auth.completeSignIn(request) }
                activity.runOnUiThread {
                    busy = false
                    driveSigningIn = false
                    val who = tokens.accountLabel ?: "已连接"
                    setStatus("已连接 Google Drive（$who）。点“检查更新”。")
                    note("已连接 Google Drive（$who）。")
                    refreshButtons()
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    busy = false
                    driveSigningIn = false
                    setStatus(updateErrorMessage(e))
                    refreshButtons()
                }
            }
        }.start()
    }

    /** 断开 Google Drive（服务端作废 token + 清本地）。 */
    private fun signOutDrive() {
        if (busy) return
        busy = true
        setStatus("正在断开 Google Drive…")
        refreshButtons()
        Thread {
            try {
                runBlocking { auth.signOut() }
            } catch (_: Exception) {
            }
            activity.runOnUiThread {
                busy = false
                pendingRemote = null
                pendingInstallFile = null
                setStatus("已断开 Google Drive。")
                refreshButtons()
            }
        }.start()
    }

    /** 填 / 改 Google OAuth 客户端 ID（公开值；格式不对不保存）。 */
    private fun editClientId() {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(clientId())
            hint = "xxx.apps.googleusercontent.com"
        }
        AlertDialog.Builder(activity)
            .setTitle("Google OAuth 客户端 ID")
            .setMessage(
                "在 Google Cloud Console 建一个 OAuth 客户端 ID（类型：桌面应用，" +
                    "并在该项目里启用 Google Drive API），把客户端 ID 填进来。\n" +
                    "客户端 ID 是公开值；同个客户端的密钥要另外填进“设置客户端密钥”" +
                    "（Google 换取令牌强制要求提供密钥，不填连不上）。",
            )
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isEmpty()) {
                    prefs.edit().remove(KEY_CLIENT_ID).apply()
                    refreshStatus()
                    return@setPositiveButton
                }
                if (!value.endsWith(".apps.googleusercontent.com")) {
                    setStatus("客户端 ID 格式不对：应以 .apps.googleusercontent.com 结尾（不是项目 ID，也不是客户端密钥）。")
                    return@setPositiveButton
                }
                prefs.edit().putString(KEY_CLIENT_ID, value).apply()
                pendingRemote = null
                refreshStatus()
                note("已保存 Google OAuth 客户端 ID。")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 填 / 改 / 清 Google OAuth 客户端密钥（只存本机 Keystore，界面
     * 上永不显示密钥值，只显示“已配 / 未配”）。
     *
     * 为什么必须填：Google 的桌面应用客户端在令牌端点强制要求提供
     * client_secret（即使走了 PKCE），不带密钥换码必报
     * `invalid_request: client_secret is missing.`——2026-11-11
     * 真机就是死在这里。密钥在 Cloud Console 里该桌面应用客户端
     * 的详情页（以 GOCSPX- 开头）。
     */
    private fun editClientSecret() {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "GOCSPX-..."
        }
        AlertDialog.Builder(activity)
            .setTitle("Google OAuth 客户端密钥")
            .setMessage(
                "Google 换取令牌强制要求提供客户端密钥（即使走了 PKCE），" +
                    "不填连不上。\n" +
                    "在 Google Cloud Console 里打开同一个桌面应用客户端，" +
                    "把它的客户端密钥（以 GOCSPX- 开头）粘进来。\n" +
                    "密钥只存本机 Keystore，界面上永不显示；清空输入框保存即清除。",
            )
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isEmpty()) {
                    try {
                        auth.clearClientSecret()
                    } catch (_: Exception) {
                    }
                    refreshStatus()
                    note("已清除 Google OAuth 客户端密钥。")
                    return@setPositiveButton
                }
                if (!value.startsWith("GOCSPX-")) {
                    setStatus("客户端密钥格式不对：桌面应用客户端的密钥以 GOCSPX- 开头（不是客户端 ID，也不是项目 ID）。")
                    return@setPositiveButton
                }
                try {
                    auth.saveClientSecret(value)
                } catch (e: Exception) {
                    setStatus("保存客户端密钥失败（${e.message ?: "本机安全存储不可用"}）。")
                    return@setPositiveButton
                }
                refreshStatus()
                note("已保存 Google OAuth 客户端密钥（只存本机）。")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 按当前状态刷新状态行与各按钮的可见性/文案。 */
    private fun refreshStatus() {
        val id = clientId()
        val signedIn = auth.isSignedIn()
        val email = auth.loadTokens()?.accountLabel
        when {
            id.isBlank() -> {
                setStatus("应用更新未配置：点“设置客户端 ID”，按说明在 Google Cloud Console 建一个 OAuth 客户端 ID（类型：桌面应用），把客户端 ID 填进来；再点“设置客户端密钥”把同个客户端的密钥（以 GOCSPX- 开头）填进来，只存本机 Keystore。")
            }
            !signedIn -> {
                setStatus("未连接 Google Drive。点“连接 Google Drive”用你的 Google 账号登录（只读权限，仅读这个更新包文件）。")
            }
            else -> {
                if (pendingRemote == null && statusView.text.isNullOrBlank()) {
                    setStatus("已连接 Google Drive（${email ?: "已登录"}）。点“检查更新”。")
                }
            }
        }
        driveClientIdRow.text = if (id.isBlank()) {
            "Google OAuth 客户端 ID：未设置（点按填写）"
        } else {
            "Google OAuth 客户端 ID：${id.take(24)}…（点按修改）"
        }
        driveClientSecretRow.text = if (auth.isClientSecretConfigured()) {
            "Google OAuth 客户端密钥：已配（点按修改）"
        } else {
            "Google OAuth 客户端密钥：未设置（点按填写）"
        }
        refreshButtons()
    }

    private fun refreshButtons() {
        val id = clientId()
        val signedIn = auth.isSignedIn()
        buttonCheckUpdate.isEnabled = !busy && id.isNotBlank() && signedIn
        buttonDownloadUpdate.visibility =
            if (pendingRemote != null || pendingInstallFile != null) View.VISIBLE else View.GONE
        buttonDownloadUpdate.isEnabled = !busy && id.isNotBlank() && signedIn
        buttonDownloadUpdate.text = when {
            installBlocked && pendingInstallFile != null -> "去开启安装权限"
            pendingInstallFile != null -> "安装更新包"
            else -> "下载并安装"
        }
        buttonDriveSignIn.visibility = if (signedIn) View.GONE else View.VISIBLE
        buttonDriveSignIn.isEnabled = !busy && id.isNotBlank()
        buttonDriveSignIn.text = if (driveSigningIn) "取消登录" else "连接 Google Drive"
        buttonDriveSignOut.visibility = if (signedIn) View.VISIBLE else View.GONE
        buttonDriveSignOut.isEnabled = !busy
    }

    private fun setStatus(text: String) {
        statusView.text = text
    }

    private fun updateErrorMessage(e: Exception): String {
        return when (e) {
            is AppUpdateError -> e.message ?: "检查更新失败。"
            else -> "检查更新失败（${e.message ?: "未知错误"}），可稍后重试。"
        }
    }

    companion object {
        /** 客户端 ID 的 SharedPreferences 文件名（公开值，不是密钥）。 */
        const val PREFS_NAME = "wherevercode_app_update"
        const val KEY_CLIENT_ID = "google_oauth_client_id"

        /** 设置页打开后自动检查的最小间隔（5 分钟）。 */
        const val AUTO_CHECK_INTERVAL_MILLIS = 5 * 60 * 1000L

        /** Production wiring。 */
        fun create(
            activity: Activity,
            note: (String) -> Unit,
            openAuthUrl: (String) -> Unit,
        ): AppUpdateSettings {
            val appContext = activity.applicationContext
            val auth = GoogleDriveAuth.create(
                appContext,
                UrlConnectionOAuthHttpTransport(),
            )
            val checker = AppUpdateChecker(
                auth = auth,
                source = DriveUpdateSource(UrlConnectionDriveHttpPort()),
                appContext = appContext,
            )
            return AppUpdateSettings(
                activity = activity,
                checker = checker,
                auth = auth,
                note = note,
                openAuthUrl = openAuthUrl,
                prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            )
        }
    }
}
