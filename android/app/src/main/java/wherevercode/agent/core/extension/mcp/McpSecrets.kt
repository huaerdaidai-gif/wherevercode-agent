package wherevercode.agent.core.extension.mcp

import org.json.JSONObject
import wherevercode.agent.core.agent.AgentSecretStore
import wherevercode.agent.core.extension.ExtensionKind
import wherevercode.agent.core.extension.ExtensionStore
import wherevercode.agent.core.extension.ExtensionEntry

/**
 * MCP 令牌保护（2026-11-13 安全加固）。
 *
 * 问题：用户在 MCP 屏或 `mcpServers` 导入里填的
 * `Authorization: Bearer <令牌>`、`X-Api-Key`、`env.GITHUB_TOKEN` 等，
 * 此前原样写进 `wherevercode_extensions` 这份**未加密**的
 * SharedPreferences（extra.headersJson / extra.envJson），导出时也原样
 * 带出明文；而 App 里根本没有 [McpCredentialResolver] 的实现，
 * `keystore:` 引用写了也解不出来——用户只能填明文。
 *
 * 修法（不动传输层）：
 * - [McpSecretSealer]：把 MCP 行里“像密钥”的请求头 / 环境变量值存进
 *   [AgentSecretStore]（生产是 Keystore 包装的 EncryptedSharedPreferences，
 *   与各大脑、平台凭证同一套），行里只留 `keystore:<引用名>`。
 * - [SecretSealingExtensionStore]：包住注册表的持久化后端，**落盘前**封存；
 *   读取时发现旧版明文会就地迁移（封存后写回）。
 * - [SecretStoreMcpCredentialResolver]：建客户端时把 `keystore:` 引用解回来。
 * - 删除服务器时由 [McpSecretSealer.deleteSecretsFor] 按同一命名规则
 *   同步删掉该服务器的凭据（R3 C-2），不留残留条目。
 *
 * 封存失败（Keystore 不可用）时保留原值、不丢配置——与修复前行为一致，
 * 不会更糟；调用方可经 [McpSecretSealer.lastFailures] 得知。
 * 值永远不进日志、异常消息或返回摘要。
 */
object McpSecretPolicy {
    const val REF_PREFIX: String = "keystore:"

    private val SECRET_HEADER_EXACT: Set<String> = setOf(
        "authorization", "proxy-authorization", "cookie", "x-api-key", "api-key", "apikey",
    )
    private val SECRET_NAME_FRAGMENTS: List<String> = listOf(
        "token", "secret", "password", "passwd", "apikey", "api-key", "api_key",
        "access-key", "access_key", "private-key", "private_key", "credential", "auth", "session",
    )

    /** 请求头名是否承载凭证。 */
    fun isSecretHeader(name: String): Boolean {
        val lower = name.trim().lowercase()
        if (lower in SECRET_HEADER_EXACT) return true
        return SECRET_NAME_FRAGMENTS.any { it in lower }
    }

    /** 环境变量名是否承载凭证（GITHUB_TOKEN / OPENAI_API_KEY / *_PAT / *_SECRET …）。 */
    fun isSecretEnv(name: String): Boolean {
        val lower = name.trim().lowercase()
        if (SECRET_NAME_FRAGMENTS.any { it in lower }) return true
        if (lower.endsWith("_key") || lower == "key") return true
        if (lower.endsWith("_pat") || lower == "pat") return true
        return false
    }

    /** 值已经是引用 / 纯环境变量占位 / 空，不需要封存。 */
    fun isAlreadySafe(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return true
        if (trimmed.startsWith(REF_PREFIX)) return true
        if (Regex("""^\$\{[A-Za-z_][A-Za-z0-9_]*\}$""").matches(trimmed)) return true
        return false
    }

    fun headerRef(serverId: String, header: String): String = "mcp:$serverId:header:${header.lowercase()}"
    fun envRef(serverId: String, env: String): String = "mcp:$serverId:env:$env"

    /**
     * 某个引用名是否属于这台服务器自己的命名空间（封存生成的引用都在
     * 此空间内）。带尾随冒号判定：服务器 `gh` 不会命中 `gh2` 的引用。
     * 用户手填的命名空间外引用（如 `keystore:github-mcp`）不属于任何
     * 一台服务器，删除服务器时不动它（可能是多处共用的手管凭证）。
     */
    fun isOwnRef(serverId: String, ref: String): Boolean = ref.startsWith("mcp:$serverId:")
}

class McpSecretSealer(private val secrets: AgentSecretStore) {
    /** 最近一次封存失败的 (serverId, 字段名) —— 只有名字，没有值。 */
    @Volatile
    var lastFailures: List<String> = emptyList()
        private set

    /** 最近一次删凭据失败的引用名 —— 只有名字，没有值。 */
    @Volatile
    var lastDeleteFailures: List<String> = emptyList()
        private set

    /** 这一行里是否还有未封存的明文凭证。 */
    fun hasPlaintextSecrets(entry: ExtensionEntry): Boolean {
        if (entry.kind != ExtensionKind.MCP) return false
        val headers = parseMap(entry.extra["headersJson"])
        if (headers.any { (k, v) -> McpSecretPolicy.isSecretHeader(k) && !McpSecretPolicy.isAlreadySafe(v) }) return true
        val env = parseMap(entry.extra["envJson"])
        return env.any { (k, v) -> McpSecretPolicy.isSecretEnv(k) && !McpSecretPolicy.isAlreadySafe(v) }
    }

    /** 封存一行：明文凭证进 [secrets]，行里换成 `keystore:` 引用。非 MCP 行原样返回。 */
    fun seal(entry: ExtensionEntry): ExtensionEntry {
        if (entry.kind != ExtensionKind.MCP) return entry
        val failures = mutableListOf<String>()
        val extra = entry.extra.toMutableMap()
        entry.extra["headersJson"]?.let { json ->
            val sealed = sealMap(parseMap(json), failures, entry.id) { name ->
                if (McpSecretPolicy.isSecretHeader(name)) McpSecretPolicy.headerRef(entry.id, name) else null
            }
            if (sealed != null) extra["headersJson"] = JSONObject(sealed).toString()
        }
        entry.extra["envJson"]?.let { json ->
            val sealed = sealMap(parseMap(json), failures, entry.id) { name ->
                if (McpSecretPolicy.isSecretEnv(name)) McpSecretPolicy.envRef(entry.id, name) else null
            }
            if (sealed != null) extra["envJson"] = JSONObject(sealed).toString()
        }
        lastFailures = failures
        return if (extra == entry.extra) entry else entry.copy(extra = extra)
    }

    /**
     * 这一行（删除前捕获的条目）在密钥库里的全部关联凭据引用名。
     *
     * [AgentSecretStore] 没有枚举接口，能删的集合只能从条目本身推：
     * - 按命名规则现算：secret 请求头 → [McpSecretPolicy.headerRef]、
     *   secret 环境变量 → [McpSecretPolicy.envRef]（封存时正是这样
     *   生成的；值仍是明文/封存失败时库里本就没有，删了也是空操作）；
     * - 条目值里实际写着的 `keystore:` 引用（整值或 `Bearer keystore:…`
     *   形态都认），但只收属于本服务器命名空间的——命名空间外的
     *   手填引用与别台服务器的引用一律不碰，防误删共用/他人凭证。
     */
    fun refsFor(entry: ExtensionEntry): Set<String> {
        if (entry.kind != ExtensionKind.MCP) return emptySet()
        val refs = LinkedHashSet<String>()
        val headers = parseMap(entry.extra["headersJson"])
        for ((name, value) in headers) {
            if (McpSecretPolicy.isSecretHeader(name)) {
                refs += McpSecretPolicy.headerRef(entry.id, name)
            }
            collectOwnRefs(entry.id, value, refs)
        }
        val env = parseMap(entry.extra["envJson"])
        for ((name, value) in env) {
            if (McpSecretPolicy.isSecretEnv(name)) {
                refs += McpSecretPolicy.envRef(entry.id, name)
            }
            collectOwnRefs(entry.id, value, refs)
        }
        return refs
    }

    /** 从一个配置值里抽出属于本服务器命名空间的 `keystore:` 引用。 */
    private fun collectOwnRefs(serverId: String, value: String, out: MutableSet<String>) {
        var from = 0
        while (true) {
            val idx = value.indexOf(McpSecretPolicy.REF_PREFIX, from)
            if (idx < 0) return
            val rest = value.substring(idx + McpSecretPolicy.REF_PREFIX.length)
            val ref = rest.takeWhile { !it.isWhitespace() }
            if (ref.isNotEmpty() && McpSecretPolicy.isOwnRef(serverId, ref)) out += ref
            from = idx + McpSecretPolicy.REF_PREFIX.length
        }
    }

    /**
     * 删除一台服务器的全部关联凭据（删除服务器时调用，R3 C-2）。
     *
     * 幂等：引用不存在时底层删除是空操作；重复调用不崩。单个引用
     * 删除失败（Keystore 不可用）不中断其余引用、不抛异常——失败的
     * 引用名记入 [lastDeleteFailures] 并作为返回值交给调用方提示
     * 用户（只有名字，没有值）。非 MCP 行直接空操作。
     */
    fun deleteSecretsFor(entry: ExtensionEntry): List<String> {
        val failures = mutableListOf<String>()
        for (ref in refsFor(entry)) {
            try {
                secrets.delete(ref)
            } catch (_: Exception) {
                failures += ref
            }
        }
        lastDeleteFailures = failures
        return failures
    }

    /** 返回封存后的 map；没有任何改动时返回 null。 */
    private fun sealMap(
        values: Map<String, String>,
        failures: MutableList<String>,
        serverId: String,
        refFor: (String) -> String?,
    ): Map<String, String>? {
        var changed = false
        val out = LinkedHashMap<String, String>()
        for ((name, value) in values) {
            val ref = refFor(name)
            if (ref == null || McpSecretPolicy.isAlreadySafe(value)) {
                out[name] = value
                continue
            }
            try {
                secrets.put(ref, value)
                out[name] = McpSecretPolicy.REF_PREFIX + ref
                changed = true
            } catch (_: Exception) {
                // Keystore 不可用：保留原值（不丢配置），只记名字。
                failures += "$serverId/$name"
                out[name] = value
            }
        }
        return if (changed) out else null
    }

    private fun parseMap(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            val map = LinkedHashMap<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = obj.optString(key, "")
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }
}

/** 落盘前封存凭证的注册表后端包装；读到旧版明文时就地迁移。 */
class SecretSealingExtensionStore(
    private val delegate: ExtensionStore,
    private val sealer: McpSecretSealer,
) : ExtensionStore {
    override fun loadOverlay(): Map<String, Map<String, ExtensionEntry>> {
        val loaded = delegate.loadOverlay()
        val needsMigration = loaded.values.any { items -> items.values.any { sealer.hasPlaintextSecrets(it) } }
        if (!needsMigration) return loaded
        val sealed = sealAll(loaded)
        try {
            delegate.saveOverlay(sealed)
        } catch (_: Exception) {
            // 迁移写回失败：下次启动再试；本次仍返回封存后的视图。
        }
        return sealed
    }

    override fun saveOverlay(entries: Map<String, Map<String, ExtensionEntry>>) {
        delegate.saveOverlay(sealAll(entries))
    }

    private fun sealAll(entries: Map<String, Map<String, ExtensionEntry>>): Map<String, Map<String, ExtensionEntry>> =
        entries.mapValues { (_, items) -> items.mapValues { (_, entry) -> sealer.seal(entry) } }
}

/** `keystore:<ref>` → [AgentSecretStore] 里的值。 */
class SecretStoreMcpCredentialResolver(private val secrets: AgentSecretStore) : McpCredentialResolver {
    override fun resolve(ref: String): String? = try {
        secrets.get(ref)?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }
}

/** 第一次用到时才建底层密钥库（Keystore 初始化失败只影响这次调用，不影响启动）。 */
class LazyAgentSecretStore(private val factory: () -> AgentSecretStore) : AgentSecretStore {
    private val delegate: AgentSecretStore by lazy(factory)
    override fun put(ref: String, value: String) = delegate.put(ref, value)
    override fun get(ref: String): String? = delegate.get(ref)
    override fun delete(ref: String) = delegate.delete(ref)
    override fun contains(ref: String): Boolean = delegate.contains(ref)
}
