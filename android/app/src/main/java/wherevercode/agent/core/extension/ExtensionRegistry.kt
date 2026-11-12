package wherevercode.agent.core.extension

import android.content.Context
import org.json.JSONObject

/**
 * App 侧统一扩展注册表（V5 MCP + 插件，第一版，2026-11-11）。
 *
 * 与 Hand 侧同一套身份：七类扩展（adapter / connector / plugin /
 * mcp / wakeup / protocol / brain），同一 id 在两边指同一扩展
 * （Hand 侧见 hand_server.py EXTENSION_KINDS 与 BUILTIN_EXTENSIONS，
 * 2026-11-11 在远程设备 上实测核对；Mac SSH 当天不通，只核了 VPS）。
 *
 * 注册表 = 内置行（本文件 [builtinEntries]，纯数据，与 Hand 内置行
 * 对齐）+ 用户自定义行（[ExtensionStore]，SharedPreferences 里一份
 * JSON，与 Hand state-dir 下 extensions.json 同形：{version,
 * extensions: {kind: {id: entry}}}）。用户行可覆盖内置行的启用开关
 * 与状态；内置行本身不删除。
 *
 * 只有 MCP 服务器与插件两类在本版有“活”的行为（连接 / 工具调用）；
 * 其余五类先只登记身份与状态（与 Hand 对齐），不伪装已实现——
 * 屏幕上只读展示，不提供假操作。
 */
enum class ExtensionKind(val id: String, val displayName: String) {
    ADAPTER("adapter", "Agent 适配器"),
    CONNECTOR("connector", "连接器"),
    PLUGIN("plugin", "插件"),
    MCP("mcp", "MCP 服务"),
    WAKEUP("wakeup", "唤醒机制"),
    PROTOCOL("protocol", "通信协议"),
    BRAIN("brain", "推理后端"),
    ;

    companion object {
        fun fromId(id: String): ExtensionKind? = values().firstOrNull { it.id == id }
    }
}

data class ExtensionEntry(
    val id: String,
    val kind: ExtensionKind,
    val displayName: String,
    /** 一句话实现说明（Hand 侧 implementation 字段的 App 侧对应物）。 */
    val implementation: String = "",
    /** 状态原文：live / unknown / failed / design-only / app-configured / mechanism-live / hand-only。 */
    val status: String = "unknown",
    val enabled: Boolean = true,
    /** builtin | user。 */
    val source: String = "builtin",
    val version: String? = null,
    /** 机器配置：MCP 服务器的 transport / command / url 等；插件的工具清单不放这里（工具在代码里）。 */
    val extra: Map<String, String> = emptyMap(),
    val note: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("kind", kind.id)
        put("display_name", displayName)
        put("implementation", implementation)
        put("status", status)
        put("enabled", enabled)
        put("source", source)
        version?.let { put("version", it) }
        put("extra", JSONObject(extra))
        note?.let { put("note", it) }
    }

    companion object {
        fun fromJson(json: JSONObject): ExtensionEntry? {
            val kind = ExtensionKind.fromId(json.optString("kind", "")) ?: return null
            val id = json.optString("id", "")
            if (id.isBlank()) return null
            val extra = mutableMapOf<String, String>()
            val extraJson = json.optJSONObject("extra")
            if (extraJson != null) {
                val keys = extraJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    extra[key] = extraJson.optString(key, "")
                }
            }
            return ExtensionEntry(
                id = id,
                kind = kind,
                displayName = json.optString("display_name", id),
                implementation = json.optString("implementation", ""),
                status = json.optString("status", "unknown"),
                enabled = json.optBoolean("enabled", true),
                source = json.optString("source", "user"),
                version = json.optString("version", "").takeIf { it.isNotEmpty() },
                extra = extra,
                note = json.optString("note", "").takeIf { it.isNotEmpty() },
            )
        }
    }
}

/** 用户自定义行的持久化后端。内置行不经过这里（在代码里）。 */
interface ExtensionStore {
    fun loadOverlay(): Map<String, Map<String, ExtensionEntry>>
    fun saveOverlay(entries: Map<String, Map<String, ExtensionEntry>>)
}

class SharedPreferencesExtensionStore(context: Context) : ExtensionStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun loadOverlay(): Map<String, Map<String, ExtensionEntry>> {
        val raw = prefs.getString(KEY_JSON, null) ?: return emptyMap()
        return try {
            val root = JSONObject(raw)
            if (root.optInt("version", 0) != REGISTRY_VERSION) return emptyMap()
            val out = mutableMapOf<String, MutableMap<String, ExtensionEntry>>()
            val kinds = root.optJSONObject("extensions") ?: return emptyMap()
            val kindKeys = kinds.keys()
            while (kindKeys.hasNext()) {
                val kindId = kindKeys.next()
                val items = kinds.optJSONObject(kindId) ?: continue
                val itemKeys = items.keys()
                while (itemKeys.hasNext()) {
                    val itemId = itemKeys.next()
                    val entry = ExtensionEntry.fromJson(items.optJSONObject(itemId) ?: continue)
                        ?: continue
                    out.getOrPut(kindId) { mutableMapOf() }[itemId] = entry
                }
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    override fun saveOverlay(entries: Map<String, Map<String, ExtensionEntry>>) {
        val root = JSONObject().apply { put("version", REGISTRY_VERSION) }
        val kinds = JSONObject()
        for ((kindId, items) in entries) {
            val itemsJson = JSONObject()
            for ((itemId, entry) in items) itemsJson.put(itemId, entry.toJson())
            kinds.put(kindId, itemsJson)
        }
        root.put("extensions", kinds)
        prefs.edit().putString(KEY_JSON, root.toString()).apply()
    }

    companion object {
        const val PREFS_NAME: String = "wherevercode_extensions"
        const val KEY_JSON: String = "extensions_json_v1"
        const val REGISTRY_VERSION: Int = 1
    }
}

/** 内存后端（单元测试用，不碰磁盘）。 */
class InMemoryExtensionStore(
    initial: Map<String, Map<String, ExtensionEntry>> = emptyMap(),
) : ExtensionStore {
    private var data: Map<String, Map<String, ExtensionEntry>> = initial
    override fun loadOverlay(): Map<String, Map<String, ExtensionEntry>> = data
    override fun saveOverlay(entries: Map<String, Map<String, ExtensionEntry>>) {
        data = entries
    }
}

class ExtensionRegistry(private val store: ExtensionStore) {
    /** 用户自定义行（内存镜像，写操作后立即落盘）。 */
    private val overlay: MutableMap<String, MutableMap<String, ExtensionEntry>> =
        store.loadOverlay().mapValues { it.value.toMutableMap() }.toMutableMap()

    /** 全部行：内置行先行，用户行同 (kind, id) 覆盖。 */
    fun all(kind: ExtensionKind? = null): List<ExtensionEntry> {
        val merged = LinkedHashMap<String, ExtensionEntry>()
        for ((kindId, items) in builtinEntries()) {
            if (kind != null && kind.id != kindId) continue
            for ((id, entry) in items) merged["$kindId/$id"] = entry
        }
        for ((kindId, items) in overlay) {
            if (kind != null && kind.id != kindId) continue
            for ((id, entry) in items) merged["$kindId/$id"] = entry
        }
        return merged.values.toList()
    }

    fun get(kind: ExtensionKind, id: String): ExtensionEntry? =
        all(kind).firstOrNull { it.id == id }

    fun countsByKind(): Map<ExtensionKind, Int> =
        ExtensionKind.values().associateWith { all(it).size }

    /**
     * 注册一个用户行。同一类里 id 已存在（内置或用户）时拒绝，返回 false。
     * 注册成功即落盘。
     */
    fun register(entry: ExtensionEntry): Boolean {
        if (entry.source != "user") return false
        if (get(entry.kind, entry.id) != null) return false
        overlay.getOrPut(entry.kind.id) { mutableMapOf() }[entry.id] = entry
        persist()
        return true
    }

    /** 改启用开关（内置行与用户行都可改；改动以用户行形式落盘）。 */
    fun setEnabled(kind: ExtensionKind, id: String, enabled: Boolean): Boolean {
        val current = get(kind, id) ?: return false
        if (current.enabled == enabled) return true
        putOverlay(current.copy(enabled = enabled))
        return true
    }

    /** 更新一行的全部字段（状态、备注、配置等），以用户行形式落盘。 */
    fun update(entry: ExtensionEntry): Boolean {
        if (get(entry.kind, entry.id) == null) return false
        putOverlay(entry)
        return true
    }

    /** 只删用户行；内置行删不掉，返回 false。 */
    fun remove(kind: ExtensionKind, id: String): Boolean {
        val current = get(kind, id) ?: return false
        if (current.source != "user") return false
        overlay[kind.id]?.remove(id)
        persist()
        return true
    }

    private fun putOverlay(entry: ExtensionEntry) {
        overlay.getOrPut(entry.kind.id) { mutableMapOf() }[entry.id] = entry
        persist()
    }

    private fun persist() {
        store.saveOverlay(overlay)
    }

    companion object {
        const val REGISTRY_VERSION: Int = 1

        private fun entry(
            id: String,
            kind: ExtensionKind,
            displayName: String,
            implementation: String = "",
            status: String = "unknown",
            enabled: Boolean = true,
            version: String? = null,
            extra: Map<String, String> = emptyMap(),
            note: String? = null,
        ) = ExtensionEntry(
            id = id, kind = kind, displayName = displayName,
            implementation = implementation, status = status, enabled = enabled,
            source = "builtin", version = version, extra = extra, note = note,
        )

        /**
         * 内置行：与 Hand 侧 BUILTIN_EXTENSIONS 同一套 id 与分类
         * （2026-11-11 VPS 实测：`hand_server.py extensions` 输出）。
         * displayName 与 implementation 取自 Hand 侧同一行的对应字段，
         * 只作登记用，不含 Hand 的执行代码。
         */
        fun builtinEntries(): Map<String, Map<String, ExtensionEntry>> = mapOf(
            "adapter" to mapOf(
                "wherevercode-agent" to entry(
                    "wherevercode-agent", ExtensionKind.ADAPTER, "本机 · WhereverCode",
                    "App 自身：BrainEngine + TaskScheduler + ChatTaskDispatcher（App 内）",
                    status = "live",
                ),
                "codex" to entry(
                    "codex", ExtensionKind.ADAPTER, "Codex",
                    "CLI：codex exec --skip-git-repo-check -s workspace-write {prompt}",
                    status = "live",
                ),
                "codebuddy" to entry(
                    "codebuddy", ExtensionKind.ADAPTER, "CodeBuddy",
                    "CLI：codebuddy -p -y {prompt}",
                    status = "live",
                ),
                "example-agent" to entry(
                    "example-agent", ExtensionKind.ADAPTER, "Example Agent",
                    "CLI：grok -p {prompt} --always-approve --sandbox workspace --output-format plain",
                    status = "live",
                ),
                "gemini" to entry(
                    "gemini", ExtensionKind.ADAPTER, "Gemini",
                    "CLI：gemini（设备上已登录的 CLI）",
                    status = "live",
                ),
                "agy" to entry(
                    "agy", ExtensionKind.ADAPTER, "Antigravity",
                    "CLI：agy（设备上已登录的 CLI）",
                    status = "live",
                ),
            ),
            "connector" to mapOf(
                "hand-tcp-transport" to entry(
                    "hand-tcp-transport", ExtensionKind.CONNECTOR, "Hand 直连传输",
                    "hand_server.py 本进程：TCP 18743 + 密封信封（X25519+HKDF 会话，AES-256-GCM，v2 任务凭证）",
                    status = "live",
                ),
                "tailscale" to entry(
                    "tailscale", ExtensionKind.CONNECTOR, "Tailscale 组网",
                    "Tailnet：VPS 192.0.2.10，Mac 198.51.100.10；Hand 经该网络可达",
                    status = "live",
                ),
            ),
            "plugin" to mapOf(
                "healthcheck" to entry(
                    "healthcheck", ExtensionKind.PLUGIN, "健康检查",
                    "run_healthcheck()（Hand 内）；CLI：hand_server.py plugin run healthcheck",
                    status = "live",
                    note = "在 Hand 设备上运行，App 内不执行；同一身份，不同宿主。",
                ),
                "local-utils" to entry(
                    "local-utils", ExtensionKind.PLUGIN, "本地小工具",
                    "App 内置示例插件：local_time（本地时间）/ calc（四则运算），真实执行",
                    status = "live", version = "1",
                    note = "App 内置插件，随 App 一起提供；停用后大脑不再看到它的工具。",
                ),
            ),
            "mcp" to mapOf(
                "hand-mcp-stdio" to entry(
                    "hand-mcp-stdio", ExtensionKind.MCP, "Hand MCP 服务（stdio）",
                    "mcp_serve()（Hand 内）：stdio JSON-RPC 2.0，协议 2024-11-05，工具 list/read/write/run；run 走通用 runner，文件夹须在授权表里",
                    status = "live",
                    extra = mapOf(
                        "transport" to "stdio",
                        "command" to "python3 hand_server.py mcp",
                    ),
                    note = "Hand 设备上的 MCP 服务。手机直连需经 Hand 传输桥接（下一步）；本版先以本机 stdio / 远程 HTTP 两种传输接 MCP 服务器，状态以实际连接为准。",
                ),
            ),
            "wakeup" to mapOf(
                "webhook" to entry(
                    "webhook", ExtensionKind.WAKEUP, "Webhook 唤醒",
                    "webhook_deliver()（Hand 内）：出站 POST，Standard Webhooks HMAC 签名，https-only",
                    status = "mechanism-live",
                ),
                "polling" to entry(
                    "polling", ExtensionKind.WAKEUP, "轮询唤醒",
                    "App HandClient hello 轮询 + WhereverCode 队列 10 分钟轮询",
                    status = "live",
                ),
            ),
            "protocol" to mapOf(
                "mcp" to entry(
                    "mcp", ExtensionKind.PROTOCOL, "MCP（通信协议）",
                    "JSON-RPC 2.0，protocolVersion 2024-11-05；由 hand-mcp-stdio 提供；工具 list/read/write/run",
                    status = "live",
                ),
                "direct-transport" to entry(
                    "direct-transport", ExtensionKind.PROTOCOL, "Hand 直连协议",
                    "hand_server.py 本进程的密封信封协议：wherevercode_hello 握手 + cross_device_tool_call 信封",
                    status = "live",
                ),
                "wacf" to entry(
                    "wacf", ExtensionKind.PROTOCOL, "WACF 自有总线",
                    "WACF 设计（2026-11-08，U1–U12 已定：控制 + 消息账本）；Hand 内暂无实现代码",
                    status = "design-only",
                ),
            ),
            "brain" to mapOf(
                "openai-compatible" to entry(
                    "openai-compatible", ExtensionKind.BRAIN, "OpenAI 兼容模型",
                    "App BrainConfig：OpenAI 兼容 /v1/models；Key 在 BrainKeyStore",
                    status = "app-configured",
                ),
                "cloudflare" to entry(
                    "cloudflare", ExtensionKind.BRAIN, "Cloudflare",
                    "App BrainConfig：Cloudflare Workers AI；Key 在 BrainKeyStore",
                    status = "app-configured",
                ),
                "deepseek" to entry(
                    "deepseek", ExtensionKind.BRAIN, "DeepSeek",
                    "App BrainConfig：DeepSeek 模型 API；Key 在 BrainKeyStore",
                    status = "app-configured",
                ),
                "chatgpt-membership" to entry(
                    "chatgpt-membership", ExtensionKind.BRAIN, "ChatGPT 会员",
                    "App BrainConfig：SIWC 会员额度大脑",
                    status = "app-configured",
                ),
                "custom-model-api" to entry(
                    "custom-model-api", ExtensionKind.BRAIN, "自定义模型 API",
                    "App BrainConfig：用户自建 OpenAI 兼容大脑",
                    status = "app-configured",
                ),
                "codex-membership" to entry(
                    "codex-membership", ExtensionKind.BRAIN, "Codex 会员",
                    "CLI 会员额度后端（Hand 发现派生）",
                    status = "app-configured",
                ),
                "codebuddy-membership" to entry(
                    "codebuddy-membership", ExtensionKind.BRAIN, "CodeBuddy 会员",
                    "CLI 会员额度后端（Hand 发现派生）",
                    status = "app-configured",
                ),
                "example-agent-membership" to entry(
                    "example-agent-membership", ExtensionKind.BRAIN, "Example Agent 会员",
                    "CLI 会员额度后端（Hand 发现派生）",
                    status = "app-configured",
                ),
            ),
        )
    }
}
