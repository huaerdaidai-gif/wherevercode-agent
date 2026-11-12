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
}

class McpSecretSealer(private val secrets: AgentSecretStore) {
    /** 最近一次封存失败的 (serverId, 字段名) —— 只有名字，没有值。 */
    @Volatile
    var lastFailures: List<String> = emptyList()
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
