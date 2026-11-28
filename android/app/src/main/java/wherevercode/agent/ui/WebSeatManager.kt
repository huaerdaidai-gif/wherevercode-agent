// WhereverCode Agent — Android
//
// 网页版 Agent 管理界面（与 iOS WebSeatViews 同构）：Agent 页的
// 「网页版 Agent」分节入口 → 管理页（已添加的席位 + 添加席位）
// → 席位浏览器页（完成 / 检测登录 + 内嵌 WebView，用户在官网
// 页面里登录，密码不经 App 手）。
//
// WebView 托管：每个席位一张 WebView，平时挂在一个 1×1 的隐形
// 容器里保活（引擎提问用），打开浏览器页时临时搬进对话框，关
// 闭时搬回——同一张 WebView、同一份登录态。

package wherevercode.agent.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import wherevercode.agent.R
import wherevercode.agent.core.agent.PlatformChannelMcpTransport
import wherevercode.agent.core.webseat.WebSeatEngine
import wherevercode.agent.core.webseat.WebSeatRecord
import wherevercode.agent.core.webseat.WebSeatSite
import wherevercode.agent.core.webseat.WebSeatStatus
import wherevercode.agent.core.webseat.WebSeatStore
import wherevercode.agent.core.webseat.WebSeatWebViewHost

/** 席位 WebView 的隐形保活容器（挂在 Activity 的 decorView 上）。 */
class WebSeatHost(private val activity: Activity) : WebSeatWebViewHost {
    private val container: FrameLayout by lazy {
        FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(1, 1)
            alpha = 0.01f
        }.also { host ->
            (activity.window.decorView as? ViewGroup)?.addView(host)
        }
    }

    override fun createWebView(): WebView = WebView(activity)

    override fun attachBackground(webView: WebView) {
        detach(webView)
        container.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    override fun detach(webView: WebView) {
        (webView.parent as? ViewGroup)?.removeView(webView)
    }
}

class WebSeatManager(
    private val activity: Activity,
    private val engine: WebSeatEngine,
    private val store: WebSeatStore,
    private val host: WebSeatHost,
    /** 机主自己的平台传输（拉频道名单用）；未配置平台时为 null。 */
    private val appTransportProvider: () -> PlatformChannelMcpTransport?,
) {
    private var dialog: Dialog? = null
    private var content: LinearLayout? = null

    init {
        engine.onStatusesChanged = {
            activity.runOnUiThread { render() }
        }
    }

    fun show() {
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(activity).apply { addView(root) }
        content = root
        val d = Dialog(activity)
        d.setContentView(scroll)
        d.setOnDismissListener {
            dialog = null
            content = null
        }
        dialog = d
        render()
        d.show()
        d.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        )
    }

    private fun render() {
        val root = content ?: return
        root.removeAllViews()
        root.setPadding(dp(16), dp(16), dp(16), dp(16))
        root.addView(
            UiKit.text(activity, "网页版 Agent", 22f, R.color.text_primary, bold = true),
        )
        root.addView(sectionTitle("已添加的席位"))
        val seats = store.listSeats()
        if (seats.isEmpty()) {
            root.addView(noteCard("还没有网页版 Agent。在下面添加一个，然后在打开的网页里登录你的账号。"))
        } else {
            seats.forEach { seat -> root.addView(seatCard(seat)) }
        }
        root.addView(
            UiKit.text(
                activity,
                "席位只在 App 打开时在线；关掉 App 就休息中，不会冒充常驻。",
                12f, R.color.text_secondary,
            ).apply { setPadding(0, dp(6), 0, 0) },
        )
        root.addView(sectionTitle("添加席位"))
        for (site in WebSeatSite.ALL) {
            val existing = seats.firstOrNull { it.driverId == site.driverId }
            if (existing != null) {
                val row = UiKit.horizontal(activity).apply {
                    setBackgroundResource(R.drawable.bg_card)
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                }
                row.addView(
                    UiKit.text(activity, site.displayName, 15f, R.color.text_primary),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                row.addView(UiKit.text(activity, "已添加", 13f, R.color.text_secondary))
                root.addView(row)
            } else {
                val add = UiKit.text(
                    activity, "＋ 添加 ${site.displayName}", 15f, R.color.accent, bold = true,
                ).apply {
                    setBackgroundResource(R.drawable.bg_card)
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    setOnClickListener { addSeat(site) }
                }
                root.addView(add)
            }
        }
        root.addView(
            UiKit.text(
                activity,
                "登录在官网页面里完成，密码只输在网页里，不经过 App。添加后席位会加入你现有的全部频道，可在「服务的频道」里改。",
                12f, R.color.text_secondary,
            ).apply { setPadding(0, dp(6), 0, 0) },
        )
    }

    private fun seatCard(seat: WebSeatRecord): LinearLayout {
        val card = UiKit.vertical(activity).apply {
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }
        val header = UiKit.horizontal(activity)
        header.addView(
            UiKit.text(activity, seat.displayName, 16f, R.color.text_primary, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(
            UiKit.text(activity, statusLabel(seat), 13f, R.color.text_secondary),
        )
        card.addView(header)
        val actions = UiKit.horizontal(activity).apply { setPadding(0, dp(8), 0, 0) }
        actions.addView(actionButton("打开 / 登录") { openBrowser(seat) })
        actions.addView(actionButton("重新检测") {
            engine.refreshLogin(seat)
            toast("正在重新检测登录状态…")
        })
        actions.addView(actionButton("服务的频道") { pickChannels(seat) })
        actions.addView(actionButton("删除") { confirmDelete(seat) })
        card.addView(actions)
        val toggleRow = UiKit.horizontal(activity).apply { setPadding(0, dp(6), 0, 0) }
        toggleRow.addView(
            UiKit.text(activity, "启用", 14f, R.color.text_primary),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val toggle = Switch(activity).apply {
            isChecked = seat.enabled
            setOnCheckedChangeListener { _, checked -> engine.setEnabled(seat, checked) }
        }
        toggleRow.addView(toggle)
        card.addView(toggleRow)
        return card
    }

    private fun statusLabel(seat: WebSeatRecord): String {
        if (!seat.enabled) return WebSeatStatus.OFF.label
        if (!store.hasToken(seat.seatId)) return WebSeatStatus.NEEDS_TOKEN.label
        return engine.statuses[seat.seatId]?.label ?: WebSeatStatus.RESTING.label
    }

    private fun addSeat(site: WebSeatSite) {
        toast("正在添加席位并签发令牌…")
        Thread {
            val channelIds = try {
                appTransportProvider()?.listChannels()
                    ?.filter { !it.archived }
                    ?.map { it.id }
                    ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            val (record, error) = engine.addSeat(site, channelIds)
            activity.runOnUiThread {
                render()
                if (error != null) {
                    toast(error)
                } else {
                    openBrowser(record)
                }
            }
        }.start()
    }

    /** 席位浏览器页：把席位 WebView 搬进对话框，用户在官网登录。 */
    private fun openBrowser(seat: WebSeatRecord) {
        val session = engine.sessionFor(seat)
        val webView = session.webViewOnMain()
        host.detach(webView)
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val bar = UiKit.horizontal(activity).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        lateinit var browserDialog: Dialog
        bar.addView(actionButton("完成") {
            host.attachBackground(webView)
            browserDialog.dismiss()
            engine.refreshLogin(seat)
        })
        bar.addView(
            UiKit.text(activity, seat.displayName, 16f, R.color.text_primary, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        bar.addView(actionButton("检测登录") {
            engine.refreshLogin(seat)
            toast("正在检测登录状态…")
        })
        root.addView(bar)
        root.addView(
            webView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            ),
        )
        browserDialog = Dialog(activity)
        browserDialog.setContentView(root)
        browserDialog.setOnDismissListener { host.attachBackground(webView) }
        if (webView.url.isNullOrBlank()) session.loadHome()
        browserDialog.show()
        browserDialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        )
    }

    private fun pickChannels(seat: WebSeatRecord) {
        Thread {
            val channels = try {
                appTransportProvider()?.listChannels()?.filter { !it.archived } ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            activity.runOnUiThread {
                if (channels.isEmpty()) {
                    toast("还没有可加入的频道（先连好平台、建好频道）。")
                    return@runOnUiThread
                }
                val names = channels.map { it.name.ifBlank { it.id } }.toTypedArray()
                val checked = BooleanArray(channels.size) { seat.channelIds.contains(channels[it].id) }
                AlertDialog.Builder(activity)
                    .setTitle("服务的频道")
                    .setMultiChoiceItems(names, checked) { _, which, on -> checked[which] = on }
                    .setPositiveButton("保存") { _, _ ->
                        val ids = channels.filterIndexed { i, _ -> checked[i] }.map { it.id }
                        engine.setChannels(seat, ids)
                        render()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }.start()
    }

    private fun confirmDelete(seat: WebSeatRecord) {
        AlertDialog.Builder(activity)
            .setTitle("删除 ${seat.displayName}？")
            .setMessage("席位记录与它的平台令牌会一并删除；网页登录态是 App 全局共享的，不会因此清除。")
            .setPositiveButton("删除") { _, _ ->
                engine.deleteSeat(seat)
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun sectionTitle(text: String): TextView =
        UiKit.text(activity, text, 13f, R.color.text_primary, bold = true).apply {
            setPadding(0, dp(14), 0, dp(6))
        }

    private fun noteCard(text: String): TextView =
        UiKit.text(activity, text, 14f, R.color.text_secondary).apply {
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

    private fun actionButton(label: String, onClick: () -> Unit): TextView =
        UiKit.text(activity, label, 12f, R.color.accent, bold = true).apply {
            setBackgroundResource(R.drawable.bg_chip)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(8) }
        }

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int = UiKit.dp(activity, value)
}
