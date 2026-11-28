// WhereverCode Agent — Android
//
// 网页席位引擎（与 iOS WebSeatEngine 同构）：App 在前台时按席位
// 轮询它所在的频道（15 秒兜底节奏），命中触发闸的消息交给席位
// 的 WebView 会话提问，回复贴回原话题。App 退到后台引擎停转，
// 席位状态如实变「休息中」——不冒充常驻（方案便签 №21 定版）。
//
// WebView 操作全在主线程；平台 HTTP 是阻塞调用，走 IO 线程。

package wherevercode.agent.core.webseat

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import wherevercode.agent.core.agent.IssueTokenResult
import wherevercode.agent.core.agent.PlatformChannelMcpTransport
import wherevercode.agent.core.agent.PlatformTokenIssuer
import kotlin.coroutines.resume

enum class WebSeatStatus(val label: String) {
    OFF("已停用"),
    NEEDS_TOKEN("未签发令牌"),
    NEEDS_LOGIN("未登录"),
    ONLINE("在线"),
    RESTING("休息中"),
}

class WebSeatException(message: String) : Exception(message)

/** UI 层提供 WebView 的出处与后台挂载点（引擎不直接碰界面树）。 */
interface WebSeatWebViewHost {
    fun createWebView(): WebView

    /** 把席位 WebView 挂到一个不可见但存活的容器里（前台保活）。 */
    fun attachBackground(webView: WebView)

    /** 把席位 WebView 从当前父容器摘下（打开登录页前调用）。 */
    fun detach(webView: WebView)
}

/**
 * 一个席位的浏览器会话：持有它的 WebView，提问流程与 Mac 桥已
 * 验证的 v2.2 纪律一致——打字前先校验页面确实是目标对话（新对
 * 话须消息列表为空、续话须网址对上，连过两次探针），回复只认
 * 发送后新出现的那行 assistant 行。
 */
class WebSeatSession(
    val seat: WebSeatRecord,
    private val host: WebSeatWebViewHost,
) {
    // WebView 只能在主线程创建：懒创建，首次用到时在主线程建并挂保活容器。
    private var webViewRef: WebView? = null

    /** 必须在主线程调用：取（或建）本席位的 WebView。 */
    fun webViewOnMain(): WebView {
        webViewRef?.let { return it }
        val created = host.createWebView()
        @SuppressLint("SetJavaScriptEnabled")
        created.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }
        created.webViewClient = WebViewClient()
        webViewRef = created
        host.attachBackground(created)
        return created
    }

    /** 已创建的 WebView（不触发创建；删除席位摘除用）。 */
    fun existingWebView(): WebView? = webViewRef

    fun loadHome() {
        webViewOnMain().loadUrl(seat.site?.homeUrl ?: "https://claude.ai/new")
    }

    private suspend fun evalString(script: String): String? {
        val raw = withContext(Dispatchers.Main) {
            val view = webViewOnMain()
            suspendCancellableCoroutine<String?> { cont ->
                view.evaluateJavascript(script) { value ->
                    if (cont.isActive) cont.resume(value)
                }
            }
        } ?: return null
        if (raw == "null") return null
        return try {
            JSONTokener(raw).nextValue() as? String ?: raw
        } catch (e: Exception) {
            raw
        }
    }

    private suspend fun evalInt(script: String): Int {
        val raw = withContext(Dispatchers.Main) {
            val view = webViewOnMain()
            suspendCancellableCoroutine<String?> { cont ->
                view.evaluateJavascript(script) { value ->
                    if (cont.isActive) cont.resume(value)
                }
            }
        }
        return raw?.trim('"')?.toIntOrNull() ?: 0
    }

    data class Probe(val href: String, val composer: Boolean, val messageCount: Int)

    suspend fun probe(): Probe? {
        val raw = evalString(ClaudeSeatScripts.PROBE) ?: return null
        val obj = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return null
        }
        return Probe(
            href = obj.optString("href", ""),
            composer = obj.optBoolean("composer", false),
            messageCount = obj.optInt("msgs", 0),
        )
    }

    /** 当前浏览器登录态是否已登录 claude.ai（与 iOS 同判据）。 */
    suspend fun probeLogin(): Boolean {
        withContext(Dispatchers.Main) { webViewOnMain().loadUrl("https://claude.ai/new") }
        repeat(10) {
            delay(2000)
            val st = probe() ?: return@repeat
            if (st.href.contains("/login")) return false
            if (ClaudeSeatScripts.probeLooksLoggedIn(st.href, st.composer)) return true
        }
        return false
    }

    private suspend fun verifyPage(target: String): Boolean {
        var streak = 0
        repeat(20) {
            delay(2000)
            val st = probe()
            if (st == null || !st.composer) {
                streak = 0
                return@repeat
            }
            val ok = if (target == "new") st.messageCount == 0 else st.href.startsWith(target)
            if (ok) {
                streak += 1
                if (streak >= 2) return true
            } else {
                streak = 0
            }
        }
        return false
    }

    /**
     * 问一个问题。[target] 为 "new" 开新对话，或已有对话网址续话。
     * 返回回复正文与它所属对话的网址。
     */
    suspend fun ask(question: String, target: String): Pair<String, String> {
        val want = if (target == "new") "https://claude.ai/new" else target
        withContext(Dispatchers.Main) { webViewOnMain().loadUrl(want) }
        if (!verifyPage(target)) {
            withContext(Dispatchers.Main) { webViewOnMain().loadUrl(want) }
            if (!verifyPage(target)) throw WebSeatException("页面未就绪（新对话校验未通过）。")
        }
        val before = evalInt(ClaudeSeatScripts.ASSISTANT_COUNT)
        val inserted = evalString(ClaudeSeatScripts.insert(question))
        if (inserted != "ok") throw WebSeatException("输入问题失败（${inserted ?: "nil"}）。")
        delay(1000)
        val sent = evalString(ClaudeSeatScripts.SEND)
        if (sent != "clicked") {
            evalString(ClaudeSeatScripts.SEND_BY_ENTER)
        }
        var lastText = ""
        var stable = 0
        var href = want
        repeat(80) {
            delay(3000)
            val raw = evalString(ClaudeSeatScripts.extract(before)) ?: return@repeat
            val obj = try {
                JSONObject(raw)
            } catch (e: Exception) {
                return@repeat
            }
            val text = obj.optString("text", "")
            val stop = obj.optBoolean("stop", false)
            val h = obj.optString("href", "")
            if (h.isNotEmpty()) href = h
            if (text.isNotEmpty() && text == lastText && !stop) {
                stable += 1
                if (stable >= 2) return Pair(text, href)
            } else {
                stable = 0
            }
            if (text.isNotEmpty()) lastText = text
        }
        if (lastText.isNotEmpty()) return Pair(lastText, href)
        throw WebSeatException("等待回复超时。")
    }
}

/**
 * 席位引擎：前台轮询 + 触发闸 + 提问 + 回帖。状态变化经
 * [onStatusesChanged] 回调（任意线程触发，UI 层自行切主线程）。
 */
class WebSeatEngine(
    private val store: WebSeatStore,
    private val host: WebSeatWebViewHost,
    /** 平台端点（随当前平台地址现取）。 */
    private val endpointProvider: () -> String,
    /** 机主自己的平台传输（签发席位令牌用）；平台未配置时为 null。 */
    private val appTransportProvider: () -> PlatformChannelMcpTransport?,
    /** 机主在平台上的 Agent 编号（App 本体固定为 app）。 */
    private val ownerAgentId: String = "app",
) {
    @Volatile
    var running: Boolean = false
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = mutableMapOf<String, WebSeatSession>()
    private val loops = mutableMapOf<String, Job>()
    private val runtimes = mutableMapOf<String, SeatRuntime>()

    val statuses = mutableMapOf<String, WebSeatStatus>()
    var onStatusesChanged: (() -> Unit)? = null

    private class SeatRuntime {
        val cursors = mutableMapOf<String, String?>()
        val threads = mutableMapOf<String, String>()
        val msgRoot = mutableMapOf<String, String>()
        val attempts = mutableMapOf<String, Int>()
        var dmChannels: Set<String> = emptySet()
    }

    fun sessionFor(seat: WebSeatRecord): WebSeatSession =
        sessions.getOrPut(seat.seatId) { WebSeatSession(seat, host) }

    fun seats(): List<WebSeatRecord> = store.listSeats()

    // MARK: 生命周期

    fun start() {
        if (running) return
        running = true
        for (seat in seats()) {
            refreshStatus(seat)
            if (seat.enabled && store.hasToken(seat.seatId)) startLoop(seat)
        }
        notifyChanged()
    }

    fun stop() {
        running = false
        loops.values.forEach { it.cancel() }
        loops.clear()
        for (seat in seats()) {
            if (seat.enabled) {
                statuses[seat.seatId] =
                    if (store.hasToken(seat.seatId)) WebSeatStatus.RESTING else WebSeatStatus.NEEDS_TOKEN
            }
        }
        notifyChanged()
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    private fun refreshStatus(seat: WebSeatRecord) {
        statuses[seat.seatId] = when {
            !seat.enabled -> WebSeatStatus.OFF
            !store.hasToken(seat.seatId) -> WebSeatStatus.NEEDS_TOKEN
            !running -> WebSeatStatus.RESTING
            else -> statuses[seat.seatId] ?: WebSeatStatus.RESTING
        }
    }

    private fun notifyChanged() {
        onStatusesChanged?.invoke()
    }

    /** 重新探测登录态并刷新状态（管理页「重新检测」用）。 */
    fun refreshLogin(seat: WebSeatRecord) {
        if (!seat.enabled || !store.hasToken(seat.seatId)) {
            refreshStatus(seat)
            notifyChanged()
            return
        }
        scope.launch {
            val ok = sessionFor(seat).probeLogin()
            statuses[seat.seatId] = if (ok) WebSeatStatus.ONLINE else WebSeatStatus.NEEDS_LOGIN
            notifyChanged()
        }
    }

    // MARK: 席位管理

    /**
     * 新建席位并用机主凭证签发它的平台令牌。返回记录与可读错误
     * （null 表示成功）。须在后台线程调用（签发是阻塞 HTTP）。
     */
    fun addSeat(site: WebSeatSite, channelIds: List<String>): Pair<WebSeatRecord, String?> {
        val suffix = java.util.UUID.randomUUID().toString().substring(0, 4).lowercase()
        val record = WebSeatRecord(
            seatId = "${site.driverId}-$suffix",
            driverId = site.driverId,
            displayName = site.displayName,
            platformAgentId = site.platformAgentId,
            channelIds = channelIds,
        )
        store.saveSeat(record)
        val error = provision(record)
        refreshStatus(record)
        if (running && error == null) startLoop(record)
        notifyChanged()
        return Pair(record, error)
    }

    /** 签发（或重签）席位令牌：明文只过内存，直存 Keystore。 */
    fun provision(seat: WebSeatRecord): String? {
        val appTransport = appTransportProvider()
            ?: return "未连接平台：先在设置里连好平台，再添加席位。"
        val issuer = PlatformTokenIssuer { name, args -> appTransport.callTool(name, args) }
        return when (val result = issuer.issueToken(seat.platformAgentId, "App 网页席位")) {
            is IssueTokenResult.Issued -> {
                store.saveToken(result.token, seat.seatId)
                null
            }
            is IssueTokenResult.Failed -> result.message
        }
    }

    fun deleteSeat(seat: WebSeatRecord) {
        loops.remove(seat.seatId)?.cancel()
        runtimes.remove(seat.seatId)
        sessions.remove(seat.seatId)?.existingWebView()?.let { host.detach(it) }
        statuses.remove(seat.seatId)
        store.deleteSeat(seat.seatId)
        notifyChanged()
    }

    fun setEnabled(seat: WebSeatRecord, enabled: Boolean) {
        seat.enabled = enabled
        store.saveSeat(seat)
        if (enabled) {
            refreshStatus(seat)
            if (running && store.hasToken(seat.seatId)) startLoop(seat)
        } else {
            loops.remove(seat.seatId)?.cancel()
            statuses[seat.seatId] = WebSeatStatus.OFF
        }
        notifyChanged()
    }

    fun setChannels(seat: WebSeatRecord, channelIds: List<String>) {
        seat.channelIds = channelIds
        store.saveSeat(seat)
        if (running && seat.enabled && store.hasToken(seat.seatId)) {
            loops.remove(seat.seatId)?.cancel()
            startLoop(seat)
        }
        notifyChanged()
    }

    // MARK: 轮询

    private fun transportFor(seat: WebSeatRecord): PlatformChannelMcpTransport =
        PlatformChannelMcpTransport(
            tokenProvider = { store.loadToken(seat.seatId) },
            endpoint = endpointProvider(),
        )

    private fun startLoop(seat: WebSeatRecord) {
        if (loops.containsKey(seat.seatId)) return
        val runtime = runtimes.getOrPut(seat.seatId) { SeatRuntime() }
        loops[seat.seatId] = scope.launch { runSeat(seat, runtime) }
    }

    private suspend fun runSeat(seat: WebSeatRecord, runtime: SeatRuntime) {
        val site = seat.site ?: return
        val transport = transportFor(seat)
        // 加入要看的频道与私聊；游标从最新一条起，绝不重放历史。
        var watched = seat.channelIds.toMutableList()
        try {
            val channels = transport.listChannels()
            runtime.dmChannels = channels
                .filter { it.kind == "dm" && it.participantIds.contains(ownerAgentId) }
                .map { it.id }
                .toSet()
            for (dm in runtime.dmChannels) {
                if (dm !in watched) watched.add(dm)
            }
        } catch (e: Exception) {
            // 列表拿不到时只看已配置频道，下一轮再补。
        }
        for (channelId in watched) {
            try {
                transport.callTool(
                    "agent_channel_join",
                    JSONObject().put("channel_id", channelId),
                )
            } catch (e: Exception) {
                // 加入失败不致命：发帖会自动入频，读不到再下一轮重试。
            }
            if (!runtime.cursors.containsKey(channelId)) {
                val page = try {
                    transport.pollMessages(
                        channelId = channelId, afterId = null, limit = 50,
                        onlyUnread = false, markRead = false,
                    )
                } catch (e: Exception) {
                    emptyList()
                }
                runtime.cursors[channelId] = page.lastOrNull()?.id
            }
        }
        val loggedIn = sessionFor(seat).probeLogin()
        statuses[seat.seatId] = if (loggedIn) WebSeatStatus.ONLINE else WebSeatStatus.NEEDS_LOGIN
        notifyChanged()
        while (running && loops[seat.seatId]?.isActive == true) {
            for (channelId in watched) {
                val fresh = try {
                    transport.pollMessages(
                        channelId = channelId, afterId = runtime.cursors[channelId],
                        limit = 50, onlyUnread = false, markRead = false,
                    )
                } catch (e: Exception) {
                    emptyList()
                }
                for (message in fresh) {
                    handle(seat, site, runtime, transport, channelId, message)
                    runtime.cursors[channelId] = message.id
                }
            }
            delay(15_000)
        }
    }

    private suspend fun handle(
        seat: WebSeatRecord,
        site: WebSeatSite,
        runtime: SeatRuntime,
        transport: PlatformChannelMcpTransport,
        channelId: String,
        message: wherevercode.agent.core.agent.PlatformChannelMessage,
    ) {
        val root = message.replyTo?.let { runtime.msgRoot[it] }
        val isDM = runtime.dmChannels.contains(channelId)
        val hit = WebSeatGate.shouldRespond(
            senderId = message.agentId,
            ownerAgentId = ownerAgentId,
            seatAgentId = seat.platformAgentId,
            text = message.text,
            threadRoot = root,
            isDM = isDM,
            handles = site.mentionHandles,
            otherHandles = site.otherHandles(),
        )
        if (root != null && message.agentId == ownerAgentId) {
            runtime.msgRoot[message.id] = root
        }
        if (!hit) return
        val effectiveRoot = root ?: (message.replyTo ?: message.id)
        val stripped = WebSeatGate.stripMentions(message.text, site.mentionHandles)
        val question = stripped.ifEmpty { message.text }
        runtime.attempts[message.id] = (runtime.attempts[message.id] ?: 0) + 1
        try {
            val (reply, href) = sessionFor(seat).ask(
                question = question,
                target = runtime.threads[effectiveRoot] ?: "new",
            )
            val chunks = chunkWebSeatText(reply)
            chunks.forEachIndexed { index, chunk ->
                val postedId = transport.postMessageEx(
                    channelId = channelId,
                    text = chunk,
                    replyTo = message.id,
                    effectKey = "webseat-${seat.seatId}-${message.id}-$index",
                )
                if (postedId.isNotEmpty()) runtime.msgRoot[postedId] = effectiveRoot
            }
            if (href.isNotEmpty()) runtime.threads[effectiveRoot] = href
            runtime.msgRoot[message.id] = effectiveRoot
            runtime.attempts.remove(message.id)
            statuses[seat.seatId] = WebSeatStatus.ONLINE
            notifyChanged()
        } catch (e: Exception) {
            if ((runtime.attempts[message.id] ?: 0) >= 3) {
                try {
                    transport.postMessageEx(
                        channelId = channelId,
                        text = "网页版 ${site.shortName} 未能完成回复：暂时不可用，请稍后再试。",
                        replyTo = message.id,
                        effectKey = "webseat-fail-${seat.seatId}-${message.id}",
                    )
                } catch (ignored: Exception) {
                    // 失败通知也发不出去时不再重试，避免刷屏。
                }
                runtime.attempts.remove(message.id)
            }
        }
    }
}
