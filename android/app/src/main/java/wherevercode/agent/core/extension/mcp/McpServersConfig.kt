package wherevercode.agent.core.extension.mcp

import org.json.JSONArray
import org.json.JSONObject
import wherevercode.agent.core.extension.ExtensionEntry
import wherevercode.agent.core.extension.ExtensionKind
import wherevercode.agent.core.extension.ExtensionRegistry

/**
 * MCP 服务器配置：主流的 `mcpServers` JSON 格式（SPEC §2.1、1.8 裁决）。
 *
 * 主流格式（Claude Desktop/Code、Cursor、Gemini CLI、Windsurf 全用）：
 * ```json
 * {"mcpServers": {
 *   "filesystem": {"command": "npx", "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"], "env": {"KEY": "value"}},
 *   "github": {"url": "https://api.githubcopilot.com/mcp", "headers": {"Authorization": "Bearer keystore:github-mcp"}}
 * }}
 * ```
 * - 根键必须是 `mcpServers`。VS Code 的 `servers` 是孤例，不认——
 *   解析时遇到它会报明确的错（请改成 `mcpServers`），不静默兼容。
 * - stdio：`command`（必填）+ `args`（数组，可选）+ `env`（键值对，
 *   可选）+ `cwd`（可选）。远程：`url`（必填）+ `headers`（键值对，
 *   可选）+ `transport`（可选：`streamable-http` 默认主流，
 *   `sse` 老版 SSE 会话模式；也认 Claude Code 的 `type` 键同值）。
 *   远程 URL 键用 `url`（不用 Gemini 的 `httpUrl`、Windsurf
 *   的 `serverUrl`）。
 * - 密钥不进配置明文：`env` / `headers` 里以 `keystore:` 开头的值是
 *   引用名（`keystore:<引用名>`），建客户端时由 [McpCredentialResolver]
 *   解出来；`env` 值里的 `${VAR}` 按进程环境变量展开。没有解析器时
 *   `keystore:` 引用报明确的错，不把引用名当明文用。
 *
 * 本文件只负责格式（解析/校验/导出/导进注册表），不碰 UI、不存密钥。
 * 导入：把 `mcpServers` 的每一项写进 [ExtensionRegistry]（kind=mcp，
 * id = 服务器名），与已有行同形；导出：把注册表里的 mcp 行写回
 * `mcpServers` JSON（密钥引用原样写回，绝不写明文）。
 */
class McpConfigException(message: String) : Exception(message)

/** 凭证引用解析（App 侧接 Keystore；测试用内存版）。 */
interface McpCredentialResolver {
    /** 解 `keystore:<ref>` 里的 ref；解不出返回 null。 */
    fun resolve(ref: String): String?
}

object NoopMcpCredentialResolver : McpCredentialResolver {
    override fun resolve(ref: String): String? = null
}

enum class McpServerTransport {
    STDIO,
    REMOTE,
}

/** 远程传输模式：Streamable HTTP 是主流，SSE 是老版兼容。 */
enum class McpRemoteTransport {
    STREAMABLE_HTTP,
    SSE,
}

data class McpServerConfigEntry(
    val name: String,
    val transport: McpServerTransport,
    val command: String = "",
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val cwd: String? = null,
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    /** 只对 REMOTE 有意义：默认 Streamable HTTP；`"transport": "sse"` 选老版 SSE。 */
    val remoteTransport: McpRemoteTransport = McpRemoteTransport.STREAMABLE_HTTP,
)

object McpServersConfig {

    const val ROOT_KEY: String = "mcpServers"

    /**
     * 解析 mcpServers JSON。返回“服务器名 → 配置”。形状不对时抛
     * [McpConfigException]（消息写清是哪个服务器、缺了什么）。
     */
    fun parse(json: String): Map<String, McpServerConfigEntry> {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw McpConfigException("mcpServers 配置不是合法 JSON：${e.message ?: "未知错误"}")
        }
        if (!root.has(ROOT_KEY)) {
            if (root.has("servers")) {
                throw McpConfigException(
                    "配置用的是 VS Code 的 servers 键，V5 只认主流的 mcpServers 键——请把根键改成 mcpServers",
                )
            }
            throw McpConfigException("配置里没有 mcpServers（根键应为 mcpServers）")
        }
        val servers = root.optJSONObject(ROOT_KEY)
            ?: throw McpConfigException("mcpServers 不是对象")
        val out = LinkedHashMap<String, McpServerConfigEntry>()
        val keys = servers.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val item = servers.optJSONObject(name)
                ?: throw McpConfigException("服务器“$name”不是对象")
            out[name] = parseEntry(name, item)
        }
        return out
    }

    private fun parseEntry(name: String, item: JSONObject): McpServerConfigEntry {
        val hasCommand = item.has("command")
        val hasUrl = item.has("url")
        if (hasCommand && hasUrl) {
            throw McpConfigException("服务器“$name”同时给了 command 和 url，只能二选一（stdio 或远程）")
        }
        if (hasCommand) {
            val command = item.optString("command", "").trim()
            if (command.isBlank()) throw McpConfigException("服务器“$name”的 command 为空")
            val args = item.optJSONArray("args")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it, "") }
            } ?: emptyList()
            val env = item.optJSONObject("env")?.let { obj ->
                val map = LinkedHashMap<String, String>()
                val envKeys = obj.keys()
                while (envKeys.hasNext()) {
                    val key = envKeys.next()
                    map[key] = obj.optString(key, "")
                }
                map
            } ?: emptyMap()
            val cwd = item.optString("cwd", "").takeIf { it.isNotBlank() }
            return McpServerConfigEntry(
                name = name,
                transport = McpServerTransport.STDIO,
                command = command,
                args = args,
                env = env,
                cwd = cwd,
            )
        }
        if (hasUrl) {
            val url = item.optString("url", "").trim()
            if (url.isBlank()) throw McpConfigException("服务器“$name”的 url 为空")
            try {
                java.net.URL(url)
            } catch (_: Exception) {
                throw McpConfigException("服务器“$name”的 url 不合法：$url")
            }
            // 远程传输模式：`transport`（也认 Claude Code 的 `type` 键），
            // 不填默认 Streamable HTTP（主流）；`sse` 选老版 SSE 会话模式。
            val modeRaw = item.optString("transport", item.optString("type", "")).trim().lowercase()
            val remoteTransport = when (modeRaw) {
                "", "http", "streamable-http", "streamable_http" -> McpRemoteTransport.STREAMABLE_HTTP
                "sse" -> McpRemoteTransport.SSE
                else -> throw McpConfigException(
                    "服务器“$name”的 transport 不认：$modeRaw（只认 sse / streamable-http）",
                )
            }
            val headers = item.optJSONObject("headers")?.let { obj ->
                val map = LinkedHashMap<String, String>()
                val headerKeys = obj.keys()
                while (headerKeys.hasNext()) {
                    val key = headerKeys.next()
                    map[key] = obj.optString(key, "")
                }
                map
            } ?: emptyMap()
            return McpServerConfigEntry(
                name = name,
                transport = McpServerTransport.REMOTE,
                url = url,
                headers = headers,
                remoteTransport = remoteTransport,
            )
        }
        throw McpConfigException("服务器“$name”既没有 command（stdio）也没有 url（远程）")
    }

    /** 把一组配置写回 mcpServers JSON（密钥引用原样写回，绝不写明文）。 */
    fun export(entries: Map<String, McpServerConfigEntry>): String {
        val servers = JSONObject()
        for ((name, entry) in entries) {
            val item = JSONObject()
            when (entry.transport) {
                McpServerTransport.STDIO -> {
                    item.put("command", entry.command)
                    if (entry.args.isNotEmpty()) {
                        item.put("args", JSONArray(entry.args))
                    }
                    if (entry.env.isNotEmpty()) {
                        item.put("env", JSONObject(entry.env))
                    }
                    if (!entry.cwd.isNullOrBlank()) item.put("cwd", entry.cwd)
                }
                McpServerTransport.REMOTE -> {
                    item.put("url", entry.url)
                    if (entry.remoteTransport == McpRemoteTransport.SSE) {
                        item.put("transport", "sse")
                    }
                    if (entry.headers.isNotEmpty()) {
                        item.put("headers", JSONObject(entry.headers))
                    }
                }
            }
            servers.put(name, item)
        }
        return JSONObject().put(ROOT_KEY, servers).toString(2)
    }

    /**
     * 把 mcpServers JSON 导进注册表（kind=mcp，id = 服务器名）。
     * 已有同名行时覆盖配置（启用开关保留原值）——此前调 `register`
     * 会因重名直接拒绝，导入同名等于没导，现按“有则更新、无则注册”。
     * 返回导入的服务器名。
     */
    fun importToRegistry(json: String, registry: ExtensionRegistry): List<String> {
        val entries = parse(json)
        val imported = mutableListOf<String>()
        for ((name, entry) in entries) {
            val extra = mutableMapOf<String, String>()
            when (entry.transport) {
                McpServerTransport.STDIO -> {
                    extra["transport"] = "stdio"
                    extra["command"] = entry.command
                    if (entry.args.isNotEmpty()) extra["argsJson"] = JSONArray(entry.args).toString()
                    if (entry.env.isNotEmpty()) extra["envJson"] = JSONObject(entry.env).toString()
                    if (!entry.cwd.isNullOrBlank()) extra["cwd"] = entry.cwd
                }
                McpServerTransport.REMOTE -> {
                    extra["transport"] = if (entry.remoteTransport == McpRemoteTransport.SSE) "sse" else "http"
                    extra["url"] = entry.url
                    if (entry.headers.isNotEmpty()) extra["headersJson"] = JSONObject(entry.headers).toString()
                }
            }
            val existing = registry.get(ExtensionKind.MCP, name)
            val enabled = existing?.enabled ?: true
            val row = ExtensionEntry(
                id = name,
                kind = ExtensionKind.MCP,
                displayName = name,
                source = "user",
                enabled = enabled,
                extra = extra,
            )
            if (existing == null) {
                registry.register(row)
            } else {
                registry.update(row)
            }
            imported += name
        }
        return imported
    }

    /** 把注册表里的 mcp 行导出成 mcpServers JSON（与 [export] 同形）。 */
    fun exportFromRegistry(registry: ExtensionRegistry): String {
        val entries = LinkedHashMap<String, McpServerConfigEntry>()
        for (entry in registry.all(ExtensionKind.MCP)) {
            val transport = entry.extra["transport"] ?: "stdio"
            if (transport == "http" || transport == "sse") {
                val headers = entry.extra["headersJson"]?.let { json ->
                    try {
                        JSONObject(json).let { obj ->
                            val map = LinkedHashMap<String, String>()
                            val keys = obj.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                map[key] = obj.optString(key, "")
                            }
                            map
                        }
                    } catch (_: Exception) {
                        emptyMap<String, String>()
                    }
                } ?: emptyMap()
                entries[entry.id] = McpServerConfigEntry(
                    name = entry.id,
                    transport = McpServerTransport.REMOTE,
                    url = entry.extra["url"].orEmpty(),
                    headers = headers,
                    remoteTransport = if (transport == "sse") McpRemoteTransport.SSE else McpRemoteTransport.STREAMABLE_HTTP,
                )
            } else {
                val args = entry.extra["argsJson"]?.let { json ->
                    try {
                        JSONArray(json).let { arr ->
                            (0 until arr.length()).map { arr.optString(it, "") }
                        }
                    } catch (_: Exception) {
                        emptyList()
                    }
                } ?: emptyList()
                val env = entry.extra["envJson"]?.let { json ->
                    try {
                        JSONObject(json).let { obj ->
                            val map = LinkedHashMap<String, String>()
                            val keys = obj.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                map[key] = obj.optString(key, "")
                            }
                            map
                        }
                    } catch (_: Exception) {
                        emptyMap<String, String>()
                    }
                } ?: emptyMap()
                entries[entry.id] = McpServerConfigEntry(
                    name = entry.id,
                    transport = McpServerTransport.STDIO,
                    command = entry.extra["command"].orEmpty(),
                    args = args,
                    env = env,
                    cwd = entry.extra["cwd"],
                )
            }
        }
        return export(entries)
    }

    /**
     * 解一个配置值：`keystore:<引用名>` 走 [McpCredentialResolver]；
     * `${VAR}` 按进程环境变量展开；其余原样返回。
     * keystore 引用解不出时抛 [McpException]（不把引用名当明文用）。
     */
    fun resolveValue(value: String, credentialResolver: McpCredentialResolver): String {
        var out = value
        if (out.startsWith("keystore:")) {
            val ref = out.removePrefix("keystore:")
            val resolved = credentialResolver.resolve(ref)
                ?: throw McpException("凭证引用解不出来：keystore:$ref（未配置凭证解析器或引用不存在）", "mcp_credential_unresolved")
            return resolved
        }
        val envPattern = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)\}""")
        out = envPattern.replace(out) { match ->
            val varName = match.groupValues[1]
            System.getenv(varName) ?: match.value
        }
        return out
    }
}
