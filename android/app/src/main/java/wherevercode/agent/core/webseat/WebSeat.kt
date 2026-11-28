// WhereverCode Agent — Android
//
// 网页版 Agent 席位（chat 模式）：与 iOS 同构（方案便签 №21，
// 2026-11-28 Funk 定标）。席位住在 App 内嵌浏览器里，用户在官网
// 页面里登录自己的账号（密码只输在官网页面，App 不经手），App
// 打开时席位在线、被 @ 即应声；App 关了席位休息，界面如实标明。
//
// 本文件：站点目录、席位记录与存储、触发闸（纯函数）、Claude 驱
// 动的页面脚本、回复分片。频道轮询与回帖在 WebSeatEngine.kt。
//
// 触发纪律与 Mac 上的网页腿桥一致（Funk 2026-11-28 立规）：席位
// 之间可以交流，但必须带 @——@ 相当于按门铃，谁按都算数；不带 @
// 的消息只有机主发的能触发（私聊与话题内跟进），其他席位不带 @
// 的发言永不触发，杜绝机器人互接话。
//
// 与 iOS 的平台差：Android WebView 没有按席位隔离的浏览器数据
// 存储，登录态是 App 内全局共享的 Cookie——v1 只有一个站点一个
// 席位，不构成问题；多席位同站时如实共用登录，不假装隔离。

package wherevercode.agent.core.webseat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import wherevercode.agent.core.agent.AgentSecretStore

/** 一个网页席位站点。驱动按站点写死一小段页面脚本；加站点 = 加一条目录 + 一份脚本。 */
data class WebSeatSite(
    val driverId: String,
    val shortName: String,
    val displayName: String,
    val homeUrl: String,
    /** 频道里能叫到这个席位的 @ 写法。 */
    val mentionHandles: List<String>,
    /** 席位在平台上发帖用的 Agent 编号（平台上须已存在；令牌由 App 用机主凭证签发）。 */
    val platformAgentId: String,
) {
    companion object {
        val CLAUDE = WebSeatSite(
            driverId = "claude-web",
            shortName = "Claude",
            displayName = "Claude（网页版）",
            homeUrl = "https://claude.ai/new",
            mentionHandles = listOf(
                "@Claude Chat", "@claude-chat", "@Claude",
                "@Claude（网页版）", "@Claude(网页版)", "@所有人",
            ),
            platformAgentId = "claude-web",
        )

        val ALL: List<WebSeatSite> = listOf(CLAUDE)

        fun site(driverId: String): WebSeatSite? = ALL.firstOrNull { it.driverId == driverId }

        /** 其他已知席位的 @ 写法（触发闸判「叫的是别人」用），与 iOS 同一份名单。 */
        val KNOWN_HANDLES: List<String> = listOf(
            "@GPT Chat", "@gpt-chat", "@ChatGPT", "@GPT",
            "@Gemini Chat", "@gemini-chat", "@Gemini",
            "@Grok Chat", "@grok-chat", "@Grok",
            "@Claude Chat", "@claude-chat", "@Claude",
            "@Codex", "@Muse", "@Kimi", "@AGY", "@Manus", "@LeChat",
            "@千问", "@Qwen", "@豆包", "@Kiro", "@Cursor",
        )
    }

    fun otherHandles(): List<String> {
        val own = mentionHandles.toSet()
        return KNOWN_HANDLES.filter { it !in own && it != "@所有人" }
    }
}

/** 一条席位记录。令牌不进记录，只存本机 Keystore（见 [WebSeatStore]）。 */
data class WebSeatRecord(
    val seatId: String,
    val driverId: String,
    val displayName: String,
    val platformAgentId: String,
    var channelIds: List<String> = emptyList(),
    var enabled: Boolean = true,
    var createdAtEpochSeconds: Long = 0,
) {
    val site: WebSeatSite? get() = WebSeatSite.site(driverId)

    fun toJson(): JSONObject = JSONObject().apply {
        put("seatId", seatId)
        put("driverId", driverId)
        put("displayName", displayName)
        put("platformAgentId", platformAgentId)
        put("channelIds", JSONArray(channelIds))
        put("enabled", enabled)
        put("createdAtEpochSeconds", createdAtEpochSeconds)
    }

    companion object {
        fun fromJson(obj: JSONObject): WebSeatRecord {
            val channels = mutableListOf<String>()
            val arr = obj.optJSONArray("channelIds")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotBlank() }?.let { channels.add(it) }
                }
            }
            return WebSeatRecord(
                seatId = obj.optString("seatId"),
                driverId = obj.optString("driverId"),
                displayName = obj.optString("displayName"),
                platformAgentId = obj.optString("platformAgentId"),
                channelIds = channels,
                enabled = obj.optBoolean("enabled", true),
                createdAtEpochSeconds = obj.optLong("createdAtEpochSeconds", 0),
            )
        }
    }
}

/**
 * 席位存储：记录是一份 JSON 名单（SharedPreferences）；令牌只进
 * [AgentSecretStore]（本机 Keystore），一席一条，与 iOS 同口径。
 */
class WebSeatStore(
    context: Context,
    secretsProvider: () -> AgentSecretStore,
) {
    // 令牌仓库懒构造：背后的 BrainKeyStore 构造即碰 AndroidKeyStore，
    // 绝不能在 Activity 启动/恢复路径上触发（无 Keystore 的环境会直接抛）。
    private val secrets: AgentSecretStore by lazy { secretsProvider() }
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun listSeats(): List<WebSeatRecord> {
        val raw = prefs.getString(KEY_RECORDS, null) ?: return emptyList()
        val arr = try {
            JSONArray(raw)
        } catch (e: Exception) {
            return emptyList()
        }
        val out = mutableListOf<WebSeatRecord>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { out.add(WebSeatRecord.fromJson(it)) }
        }
        return out
    }

    fun saveSeat(record: WebSeatRecord) {
        if (record.createdAtEpochSeconds == 0L) {
            record.createdAtEpochSeconds = System.currentTimeMillis() / 1000
        }
        val seats = listSeats().toMutableList()
        val index = seats.indexOfFirst { it.seatId == record.seatId }
        if (index >= 0) seats[index] = record else seats.add(record)
        persist(seats)
    }

    fun deleteSeat(seatId: String) {
        persist(listSeats().filter { it.seatId != seatId })
        secrets.delete(tokenRef(seatId))
    }

    private fun persist(seats: List<WebSeatRecord>) {
        val arr = JSONArray()
        seats.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_RECORDS, arr.toString()).apply()
    }

    // MARK: 令牌（Keystore only）

    fun saveToken(value: String, seatId: String) = secrets.put(tokenRef(seatId), value)

    fun loadToken(seatId: String): String? = secrets.get(tokenRef(seatId))?.takeIf { it.isNotBlank() }

    fun hasToken(seatId: String): Boolean = secrets.contains(tokenRef(seatId))

    companion object {
        private const val PREFS_NAME = "webseats"
        private const val KEY_RECORDS = "webseats.records.v1"
        fun tokenRef(seatId: String): String = "webseat:$seatId:token"
    }
}

/** 触发闸（纯函数，与 iOS WebSeatGate 同逻辑、同样可单测）。 */
object WebSeatGate {
    /**
     * 门铃规则（Funk 2026-11-28）：带 @ 本席的消息谁发都触发；
     * 不带 @ 的只认机主——机主私聊必答、机主在话题内跟进续话
     * （除非他只 @ 了别人）；其他席位不带 @ 的发言永不触发。
     */
    fun shouldRespond(
        senderId: String,
        ownerAgentId: String,
        seatAgentId: String,
        text: String,
        threadRoot: String?,
        isDM: Boolean,
        handles: List<String>,
        otherHandles: List<String>,
    ): Boolean {
        if (senderId == seatAgentId) return false
        val mentioned = handles.any { text.contains(it) }
        if (mentioned) return true
        if (senderId != ownerAgentId) return false
        if (isDM) return true
        val otherOnly = otherHandles.any { text.contains(it) }
        return threadRoot != null && !otherOnly
    }

    /** 提问前剥掉叫本席的 @ 写法（与 Mac 桥同口径）。 */
    fun stripMentions(text: String, handles: List<String>): String {
        var out = text
        for (handle in handles) out = out.replace(handle, "")
        return out.trim()
    }
}

/** 回复分片：平台单条上限 4000 字，留余量按 3900 切（与 iOS 同逻辑）。 */
fun chunkWebSeatText(text: String, limit: Int = 3900): List<String> {
    if (text.length <= limit) return listOf(text)
    val chunks = mutableListOf<String>()
    var rest = text
    while (rest.length > limit) {
        val window = rest.substring(0, limit)
        val newline = window.lastIndexOf('\n')
        if (newline > 0) {
            // 在换行处切并丢掉换行符；硬切时一个字都不许丢。
            chunks.add(rest.substring(0, newline))
            rest = rest.substring(newline + 1)
        } else {
            chunks.add(window)
            rest = rest.substring(limit)
        }
    }
    if (rest.isNotEmpty()) chunks.add(rest)
    return chunks
}

/**
 * claude.ai 页面契约：从 Mac 的 claude-chat 桥已验证驱动（v2.2）
 * 移植——输入框是 `div[contenteditable="true"]`，消息行带
 * `data-testid="assistant-message"`，回复正文在新出现的那行
 * assistant 行的 `.standard-markdown` 块里。
 */
object ClaudeSeatScripts {
    const val PROBE: String =
        """(() => { const ce = !!document.querySelector('div[contenteditable="true"]'); """ +
            """const msgs = document.querySelectorAll('[data-testid="assistant-message"], [data-testid="user-message"]').length; """ +
            """return JSON.stringify({href: location.href, composer: ce, msgs: msgs}); })()"""

    const val ASSISTANT_COUNT: String =
        """document.querySelectorAll('[data-testid="assistant-message"]').length"""

    fun insert(question: String): String {
        val json = JSONArray(listOf(question)).toString()
        val literal = json.substring(1, json.length - 1)
        return """(() => { const ce = document.querySelector('div[contenteditable="true"]'); """ +
            """if (!ce) return 'no-ce'; ce.focus(); """ +
            """const ok = document.execCommand('insertText', false, $literal); """ +
            """return ok ? 'ok' : 'insert-failed'; })()"""
    }

    const val SEND: String =
        """(() => { const b = Array.from(document.querySelectorAll('button'))""" +
            """.find(x => /发送消息|send message/i.test(x.getAttribute('aria-label') || '')); """ +
            """if (b && !b.disabled) { b.click(); return 'clicked'; } return 'no-send-btn'; })()"""

    const val SEND_BY_ENTER: String =
        """(() => { const ce = document.querySelector('div[contenteditable="true"]'); """ +
            """if (!ce) return 'no-ce'; ce.focus(); """ +
            """ce.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true})); """ +
            """return 'enter'; })()"""

    /**
     * 读「最后一行 assistant 回复」：返回行数与最后一行正文。
     * 按下标定位在页面过渡期会错位（新对话导航未落定时行数会瞬时
     * 变化），改以最后一行正文与发送前快照比对判定新回复。
     */
    const val EXTRACT_LAST: String =
        """(() => { const stop = Array.from(document.querySelectorAll('button'))""" +
            """.some(x => /stop|停止/i.test(x.getAttribute('aria-label') || '')); """ +
            """const asst = document.querySelectorAll('[data-testid="assistant-message"]'); """ +
            """let last = ''; """ +
            """if (asst.length > 0) { const row = asst[asst.length - 1]; """ +
            """const md = row.querySelector('[class*="standard-markdown"]') || row.querySelector('.prose'); """ +
            """last = ((md ? md.innerText : '') || '').trim(); } """ +
            """return JSON.stringify({stop: stop, count: asst.length, text: last, href: location.href}); })()"""

    /** /new 页有输入框且没被弹去登录页，就算已登录（与 iOS 同判据）。 */
    fun probeLooksLoggedIn(href: String, composer: Boolean): Boolean =
        composer && !href.contains("/login") && !href.contains("auth")
}
