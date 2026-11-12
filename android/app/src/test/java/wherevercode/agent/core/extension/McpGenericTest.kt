package wherevercode.agent.core.extension.mcp

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import wherevercode.agent.core.extension.ExtensionEntry
import wherevercode.agent.core.extension.ExtensionKind
import wherevercode.agent.core.extension.ExtensionRegistry
import wherevercode.agent.core.extension.InMemoryExtensionStore

/**
 * MCP 通用化测试（分支 fix/v5-mcp-generic-20261112）。
 *
 * 覆盖本次补齐的标准能力：
 * - JSON-RPC 服务端请求（roots/list）解析与应答构造；
 * - API Key 认证头 + Bearer/X-Api-Key 选择；
 * - mcpServers 配置：SSE 传输解析/导出、导入同名覆盖且开关保留；
 * - 断线重连退避序列；
 * - OAuth 2.1 授权 URL 带 state + PKCE S256；
 * - Roots：initialize 声明 capabilities.roots、roots/list 应答、未知方法 -32601；
 * - 管理器断线自动重连（恢复 / 401 不重连 / 停用取消）；
 * - 老版 SSE 传输端到端（真实测试服务器）；
 * - Roots 端到端（Streamable HTTP + 测试服务器 roots 钩子）。
 */
class McpGenericTest {

    private val processes = mutableListOf<Process>()

    @Before
    fun setUp() {
        assertTrue("minimal MCP server script missing", McpTestSupport.serverScript().exists())
    }

    @After
    fun tearDown() {
        McpTestSupport.killAll(processes)
    }

    // ---- JSON-RPC 服务端请求 -------------------------------------------------

    @Test
    fun `server request parses with id and method`() {
        val parsed = McpJsonRpc.parseResponse(
            """{"jsonrpc":"2.0","id":9001,"method":"roots/list","params":{}}""",
        )
        assertTrue(parsed is McpJsonRpc.Response.ServerRequest)
        val req = parsed as McpJsonRpc.Response.ServerRequest
        assertEquals(9001L, req.id)
        assertEquals("roots/list", req.method)
    }

    @Test
    fun `response and error builders round trip`() {
        val ok = McpJsonRpc.response(5L, JSONObject().apply { put("roots", JSONArray()) }).toString()
        assertTrue(ok.contains("\"id\":5"))
        assertTrue(ok.contains("\"roots\":[]"))
        val err = McpJsonRpc.errorResponse(6L, -32601, "Method not found").toString()
        assertTrue(err.contains("\"id\":6"))
        assertTrue(err.contains("-32601"))
        val parsedErr = McpJsonRpc.parseResponse(err)
        assertTrue(parsedErr is McpJsonRpc.Response.Error)
        assertEquals(-32601, (parsedErr as McpJsonRpc.Response.Error).code)
    }

    // ---- 认证 ----------------------------------------------------------------

    @Test
    fun `api key provider sends configured header and never retries`() = runBlocking {
        val provider = ApiKeyAuthProvider("X-Api-Key", "k-1")
        assertEquals(mapOf("X-Api-Key" to "k-1"), provider.authHeaders())
        assertFalse(provider.handleUnauthorized(null))
    }

    @Test
    fun `auth provider for headers picks bearer first then api key`() = runBlocking {
        val bearer = authProviderForHeaders(mapOf("Authorization" to "bearer tok-1"))
        assertNotNull(bearer)
        assertEquals("Bearer tok-1", bearer!!.authHeaders()["Authorization"])
        val apiKey = authProviderForHeaders(mapOf("X-API-KEY" to "k-2"))
        assertNotNull(apiKey)
        assertEquals("k-2", apiKey!!.authHeaders()["X-API-KEY"])
        // Bearer 优先于 API Key。
        val both = authProviderForHeaders(
            mapOf("Authorization" to "Bearer tok-3", "X-Api-Key" to "k-3"),
        )
        assertEquals("Bearer tok-3", both!!.authHeaders()["Authorization"])
        // Basic 不认（只认 Bearer）。
        assertNull(authProviderForHeaders(mapOf("Authorization" to "Basic dXNlcjpwYXNz")))
        assertNull(authProviderForHeaders(emptyMap()))
    }

    // ---- 配置 ----------------------------------------------------------------

    @Test
    fun `sse transport parses from transport and type keys and exports back`() {
        val byTransport = McpServersConfig.parse(
            """{"mcpServers":{"srv":{"url":"http://127.0.0.1:9/sse","transport":"sse"}}}""",
        )
        assertEquals(McpRemoteTransport.SSE, byTransport["srv"]!!.remoteTransport)
        val byType = McpServersConfig.parse(
            """{"mcpServers":{"srv":{"url":"http://127.0.0.1:9/sse","type":"sse"}}}""",
        )
        assertEquals(McpRemoteTransport.SSE, byType["srv"]!!.remoteTransport)
        val default = McpServersConfig.parse(
            """{"mcpServers":{"srv":{"url":"http://127.0.0.1:9/mcp"}}}""",
        )
        assertEquals(McpRemoteTransport.STREAMABLE_HTTP, default["srv"]!!.remoteTransport)
        val exported = McpServersConfig.export(byTransport)
        // 导出再解析回环：SSE 传输不丢。
        assertEquals(McpRemoteTransport.SSE, McpServersConfig.parse(exported)["srv"]!!.remoteTransport)
        assertTrue(exported.contains("http://127.0.0.1:9/sse"))
    }

    @Test
    fun `import overwrites existing row and keeps enabled state`() {
        val registry = ExtensionRegistry(InMemoryExtensionStore())
        registry.register(
            ExtensionEntry(
                id = "srv",
                kind = ExtensionKind.MCP,
                displayName = "srv",
                source = "user",
                enabled = false,
                extra = mapOf("transport" to "http", "url" to "http://127.0.0.1:9/old"),
            ),
        )
        val imported = McpServersConfig.importToRegistry(
            """{"mcpServers":{"srv":{"url":"http://127.0.0.1:9/new","transport":"sse"}}}""",
            registry,
        )
        assertEquals(listOf("srv"), imported)
        val row = registry.get(ExtensionKind.MCP, "srv")!!
        assertEquals("http://127.0.0.1:9/new", row.extra["url"])
        assertEquals("sse", row.extra["transport"])
        // 开关保留原值（停用的导入后仍停用）。
        assertFalse(row.enabled)
    }

    // ---- 重连退避 ------------------------------------------------------------

    @Test
    fun `reconnect delays increase then stop`() {
        assertEquals(5_000L, ReconnectPolicy.nextDelay(0))
        assertEquals(15_000L, ReconnectPolicy.nextDelay(1))
        assertEquals(30_000L, ReconnectPolicy.nextDelay(2))
        assertEquals(60_000L, ReconnectPolicy.nextDelay(3))
        assertEquals(60_000L, ReconnectPolicy.nextDelay(4))
        assertNull(ReconnectPolicy.nextDelay(5))
        assertNull(ReconnectPolicy.nextDelay(99))
    }

    // ---- OAuth state + PKCE --------------------------------------------------

    @Test
    fun `oauth authorization url carries state and pkce s256`() = runBlocking {
        var capturedUrl: String? = null
        var capturedForm: Map<String, String>? = null
        val provider = OAuthMcpAuthProvider(
            serverUrl = "http://127.0.0.1:9/mcp",
            clientId = "wherevercode-agent",
            redirectUri = "http://127.0.0.1:1234/auth/callback",
            scopes = listOf("mcp"),
            tokenStore = InMemoryMcpTokenStore(),
            codeProvider = { url ->
                capturedUrl = url
                "code-1"
            },
            redirectUriProvider = { "http://127.0.0.1:1234/auth/callback" },
            httpGet = { url, _ ->
                when {
                    url.contains("oauth-protected-resource") ->
                        200 to """{"authorization_servers":["http://127.0.0.1:9"]}"""
                    url.contains("oauth-authorization-server") ->
                        200 to """{"issuer":"http://127.0.0.1:9","authorization_endpoint":"http://127.0.0.1:9/authorize","token_endpoint":"http://127.0.0.1:9/token"}"""
                    else -> 404 to "{}"
                }
            },
            httpPostForm = { _, form ->
                capturedForm = form
                200 to """{"access_token":"tok-x","refresh_token":"ref-x","expires_in":3600,"token_type":"Bearer"}"""
            },
        )
        assertTrue(provider.handleUnauthorized(null))
        val url = capturedUrl!!
        assertTrue(url.contains("state="))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A1234%2Fauth%2Fcallback"))
        // 换 token 用同一个 redirect_uri。
        assertEquals("http://127.0.0.1:1234/auth/callback", capturedForm?.get("redirect_uri"))
        assertEquals("Bearer tok-x", provider.authHeaders()["Authorization"])
    }

    // ---- Roots（fake 传输） ---------------------------------------------------

    private class FakeTransport(
        private val initializeResult: JSONObject = JSONObject().apply {
            put("protocolVersion", "2025-11-25")
            put("capabilities", JSONObject())
            put("serverInfo", JSONObject().apply { put("name", "fake"); put("version", "1") })
        },
    ) : McpTransport {
        var initializeParams: JSONObject? = null
        var lastRespondId: Long? = null
        var lastRespondResult: JSONObject? = null
        var lastRespondError: Triple<Long, Int, String>? = null
        private var serverRequestListener: ((McpJsonRpc.Response.ServerRequest) -> Unit)? = null

        override suspend fun start() {}
        override suspend fun request(method: String, params: JSONObject?, timeoutMillis: Long): Any? {
            if (method == "initialize") {
                initializeParams = params
                return initializeResult
            }
            throw McpException("no handler for $method", "mcp_error")
        }

        override suspend fun notify(method: String, params: JSONObject?) {}
        override suspend fun close() {}
        override fun setNotificationListener(listener: ((McpJsonRpc.McpNotification) -> Unit)?) {}
        override fun setServerRequestListener(listener: ((McpJsonRpc.Response.ServerRequest) -> Unit)?) {
            serverRequestListener = listener
        }

        override suspend fun respond(id: Long, result: JSONObject) {
            lastRespondId = id
            lastRespondResult = result
        }

        override suspend fun respondError(id: Long, code: Int, message: String) {
            lastRespondError = Triple(id, code, message)
        }

        fun simulateServerRequest(req: McpJsonRpc.Response.ServerRequest) {
            serverRequestListener?.invoke(req)
        }
    }

    private class PaginatedCatalogTransport : McpTransport {
        val cursors = mutableMapOf<String, MutableList<String?>>()
        var repeatToolCursor = false
        private var notificationListener: ((McpJsonRpc.McpNotification) -> Unit)? = null
        private val init = JSONObject().apply {
            put("protocolVersion", "2025-11-25")
            put("capabilities", JSONObject().apply {
                put("tools", JSONObject())
                put("resources", JSONObject().apply { put("subscribe", true) })
                put("prompts", JSONObject())
            })
            put("serverInfo", JSONObject().apply { put("name", "pages"); put("version", "1") })
        }

        override suspend fun start() {}
        override suspend fun request(method: String, params: JSONObject?, timeoutMillis: Long): Any? {
            if (method == "initialize") return init
            val cursor = params?.optString("cursor")?.takeIf { it.isNotBlank() }
            cursors.getOrPut(method) { mutableListOf() }.add(cursor)
            return when (method) {
                "tools/list" -> if (cursor == null) JSONObject().apply {
                    put("tools", JSONArray().put(JSONObject().apply {
                        put("name", "first")
                        put("annotations", JSONObject().put("readOnlyHint", true))
                        put("outputSchema", JSONObject().put("type", "object"))
                    }))
                    put("nextCursor", "tool-page-2")
                } else JSONObject().apply {
                    put("tools", JSONArray().put(JSONObject().put("name", "second")))
                    if (repeatToolCursor) put("nextCursor", "tool-page-2")
                }
                "resources/list" -> if (cursor == null) JSONObject().apply {
                    put("resources", JSONArray().put(JSONObject().apply {
                        put("uri", "file:///a"); put("name", "a")
                    }))
                    put("nextCursor", "resource-page-2")
                } else JSONObject().put("resources", JSONArray().put(JSONObject().apply {
                    put("uri", "file:///b"); put("name", "b")
                }))
                "resources/templates/list" -> JSONObject().put("resourceTemplates", JSONArray().put(JSONObject().apply {
                    put("uriTemplate", "file:///{path}"); put("name", "file")
                }))
                "prompts/list" -> if (cursor == null) JSONObject().apply {
                    put("prompts", JSONArray().put(JSONObject().put("name", "first-prompt")))
                    put("nextCursor", "prompt-page-2")
                } else JSONObject().put("prompts", JSONArray().put(JSONObject().put("name", "second-prompt")))
                "tools/call" -> {
                    val token = params?.optJSONObject("_meta")?.opt("progressToken")?.toString()
                    if (token != null) notificationListener?.invoke(
                        McpJsonRpc.McpNotification(
                            "notifications/progress",
                            JSONObject().put("progressToken", token).put("progress", 2).put("total", 3)
                                .put("message", "working"),
                        ),
                    )
                    JSONObject().apply {
                        put("content", JSONArray()
                            .put(JSONObject().put("type", "text").put("text", "done"))
                            .put(JSONObject().put("type", "image").put("data", "aW1n").put("mimeType", "image/png")))
                        put("structuredContent", JSONObject().put("ok", true))
                    }
                }
                "completion/complete" -> JSONObject().put("completion", JSONObject().apply {
                    put("values", JSONArray().put("suggestion")); put("total", 1); put("hasMore", false)
                })
                else -> throw McpException("unexpected $method", "mcp_error")
            }
        }

        override suspend fun notify(method: String, params: JSONObject?) {}
        override suspend fun close() {}
        override fun setNotificationListener(listener: ((McpJsonRpc.McpNotification) -> Unit)?) {
            notificationListener = listener
        }
        override fun setServerRequestListener(listener: ((McpJsonRpc.Response.ServerRequest) -> Unit)?) {}
        override suspend fun respond(id: Long, result: JSONObject) {}
        override suspend fun respondError(id: Long, code: Int, message: String) {}
    }

    @Test
    fun `catalog pagination and rich tool results are preserved`() = runBlocking {
        val transport = PaginatedCatalogTransport()
        val client = McpClient(transport)
        try {
            client.initialize()
            assertEquals(listOf("first", "second"), client.listTools().map { it.name })
            assertTrue(client.tools.first().annotations!!.getBoolean("readOnlyHint"))
            assertEquals("object", client.tools.first().outputSchema!!.getString("type"))
            assertEquals(listOf(null, "tool-page-2"), transport.cursors["tools/list"])
            assertEquals(listOf("file:///a", "file:///b"), client.listResources().map { it.uri })
            assertEquals(listOf(null, "resource-page-2"), transport.cursors["resources/list"])
            assertEquals("file:///{path}", client.listResourceTemplates().single().uriTemplate)
            assertEquals(listOf("first-prompt", "second-prompt"), client.listPrompts().map { it.name })
            val progressEvents = mutableListOf<McpProgress>()
            val result = client.callTool("first", emptyMap(), progress = progressEvents::add)
            assertEquals("done", result.text)
            assertEquals(2, result.content.size)
            assertEquals("image", result.content[1].getString("type"))
            assertTrue(result.structuredContent!!.getBoolean("ok"))
            assertEquals("working", progressEvents.single().message)
            assertEquals(2.0, progressEvents.single().progress, 0.0)
            val completions = client.complete(JSONObject().put("type", "ref/prompt").put("name", "greet"), "name", "ma")
            assertEquals(listOf("suggestion"), completions.values)
            assertEquals(1, completions.total)
        } finally {
            client.close()
        }
    }

    @Test
    fun `catalog pagination rejects a repeated cursor`() = runBlocking {
        val transport = PaginatedCatalogTransport().apply { repeatToolCursor = true }
        val client = McpClient(transport)
        try {
            client.initialize()
            try {
                client.listTools()
                error("repeated cursor must be rejected")
            } catch (e: McpException) {
                assertEquals("mcp_bad_pagination", e.code)
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `initialize declares roots capability when provider set`() = runBlocking {
        val withRoots = FakeTransport()
        val client = McpClient(
            withRoots,
            rootsProvider = StaticMcpRootsProvider(listOf(McpRoot("file:///tmp/w5", "w5"))),
        )
        try {
            client.initialize()
            val caps = withRoots.initializeParams!!.getJSONObject("capabilities")
            assertTrue(caps.has("roots"))
            assertTrue(caps.getJSONObject("roots").getBoolean("listChanged"))
        } finally {
            client.close()
        }
        val withoutRoots = FakeTransport()
        val plain = McpClient(withoutRoots)
        try {
            plain.initialize()
            val caps = withoutRoots.initializeParams!!.getJSONObject("capabilities")
            assertFalse(caps.has("roots"))
        } finally {
            plain.close()
        }
    }

    @Test
    fun `roots list request answered with configured roots`() = runBlocking {
        val transport = FakeTransport()
        val client = McpClient(
            transport,
            rootsProvider = StaticMcpRootsProvider(
                listOf(McpRoot("file:///tmp/w5", "w5"), McpRoot("file:///tmp/w6", "w6")),
            ),
        )
        try {
            client.initialize()
            client.handleServerRequest(McpJsonRpc.Response.ServerRequest(7L, "roots/list", null))
            assertTrue(McpTestSupport.waitUntil { transport.lastRespondResult != null })
            assertEquals(7L, transport.lastRespondId)
            val roots = transport.lastRespondResult!!.getJSONArray("roots")
            assertEquals(2, roots.length())
            assertEquals("file:///tmp/w5", roots.getJSONObject(0).getString("uri"))
            assertEquals("w5", roots.getJSONObject(0).getString("name"))
            // 未知方法回 -32601。
            client.handleServerRequest(McpJsonRpc.Response.ServerRequest(8L, "sampling/createMessage", null))
            assertTrue(McpTestSupport.waitUntil { transport.lastRespondError?.first == 8L })
            assertEquals(-32601, transport.lastRespondError!!.second)
        } finally {
            client.close()
        }
    }

    // ---- 管理器自动重连 -------------------------------------------------------

    private class FlakyTransport : McpTransport {
        var initializeCalls = 0
        var failCallTool: McpException? = null
        private val initResult = JSONObject().apply {
            put("protocolVersion", "2025-11-25")
            put("capabilities", JSONObject())
            put("serverInfo", JSONObject().apply { put("name", "flaky"); put("version", "1") })
        }

        override suspend fun start() {}
        override suspend fun request(method: String, params: JSONObject?, timeoutMillis: Long): Any? {
            return when (method) {
                "initialize" -> {
                    initializeCalls++
                    initResult
                }
                "tools/list" -> JSONObject().apply { put("tools", JSONArray()) }
                "tools/call" -> {
                    failCallTool?.let { throw it }
                    JSONObject().apply {
                        put("content", JSONArray().put(JSONObject().apply { put("type", "text"); put("text", "ok") }))
                        put("isError", false)
                    }
                }
                else -> throw McpException("unexpected $method", "mcp_error")
            }
        }

        override suspend fun notify(method: String, params: JSONObject?) {}
        override suspend fun close() {}
        override fun setNotificationListener(listener: ((McpJsonRpc.McpNotification) -> Unit)?) {}
        override fun setServerRequestListener(listener: ((McpJsonRpc.Response.ServerRequest) -> Unit)?) {}
        override suspend fun respond(id: Long, result: JSONObject) {}
        override suspend fun respondError(id: Long, code: Int, message: String) {}
    }

    private fun flakyRegistry(): ExtensionRegistry {
        val registry = ExtensionRegistry(InMemoryExtensionStore())
        registry.register(
            ExtensionEntry(
                id = "flaky",
                kind = ExtensionKind.MCP,
                displayName = "flaky",
                source = "user",
                enabled = true,
                extra = mapOf("transport" to "http", "url" to "http://127.0.0.1:9/mcp"),
            ),
        )
        return registry
    }

    @Test
    fun `transport failure schedules reconnect and recovers`() = runBlocking {
        val registry = flakyRegistry()
        val transport = FlakyTransport()
        val manager = McpServerManager(
            registry,
            clientFactory = { _ -> McpClient(transport) },
            reconnectDelays = listOf(50L, 50L, 50L),
        )
        try {
            manager.connect("flaky")
            assertTrue(manager.isConnected("flaky"))
            transport.failCallTool = McpException("dropped", "mcp_connect_failed")
            val result = manager.execute("mcp_flaky_echo", emptyMap())
            assertFalse(result.ok)
            assertTrue(manager.isReconnectPending("flaky"))
            transport.failCallTool = null
            assertTrue(McpTestSupport.waitUntil { manager.isConnected("flaky") })
            assertTrue(transport.initializeCalls >= 2)
        } finally {
            manager.closeAll()
        }
    }

    @Test
    fun `unauthorized failure does not schedule reconnect`() = runBlocking {
        val registry = flakyRegistry()
        val transport = FlakyTransport()
        val manager = McpServerManager(
            registry,
            clientFactory = { _ -> McpClient(transport) },
            reconnectDelays = listOf(50L, 50L),
        )
        try {
            manager.connect("flaky")
            transport.failCallTool = McpException("no token", "mcp_unauthorized")
            val result = manager.execute("mcp_flaky_echo", emptyMap())
            assertFalse(result.ok)
            delay(300)
            assertFalse(manager.isReconnectPending("flaky"))
            assertEquals(1, transport.initializeCalls)
        } finally {
            manager.closeAll()
        }
    }

    @Test
    fun `disabling the server cancels a pending reconnect`() = runBlocking {
        val registry = flakyRegistry()
        val transport = FlakyTransport()
        val manager = McpServerManager(
            registry,
            clientFactory = { _ -> McpClient(transport) },
            reconnectDelays = listOf(200L, 200L),
        )
        try {
            manager.connect("flaky")
            transport.failCallTool = McpException("dropped", "mcp_connect_failed")
            manager.execute("mcp_flaky_echo", emptyMap())
            assertTrue(McpTestSupport.waitUntil { manager.isReconnectPending("flaky") })
            registry.setEnabled(ExtensionKind.MCP, "flaky", false)
            delay(500)
            assertFalse(manager.isReconnectPending("flaky"))
            assertEquals(1, transport.initializeCalls)
        } finally {
            manager.closeAll()
        }
    }

    // ---- 端到端：老版 SSE / Roots ---------------------------------------------

    @Test
    fun `sse end to end initialize list and call`() = runBlocking {
        val port = McpTestSupport.startServer(
            McpTestSupport.serverScript(), processes, "--http", "0", "--sse",
        )
        val client = McpClient(SseMcpTransport("http://127.0.0.1:$port/sse"))
        try {
            val info = client.initialize()
            assertEquals("2025-11-25", info.protocolVersion)
            assertEquals(listOf("add", "echo"), client.listTools().map { it.name }.sorted())
            val result = client.callTool("echo", mapOf("text" to "hello-sse"))
            assertEquals("hello-sse", result.text)
        } finally {
            client.close()
        }
    }

    @Test
    fun `roots end to end over streamable http`() = runBlocking {
        val port = McpTestSupport.startServer(
            McpTestSupport.serverScript(), processes, "--http", "0", "--sse",
        )
        val client = McpClient(
            HttpMcpTransport("http://127.0.0.1:$port/"),
            rootsProvider = StaticMcpRootsProvider(listOf(McpRoot("file:///tmp/w5roots", "w5"))),
        )
        try {
            client.initialize()
            val asked = client.rawRequest("test/request-roots") as JSONObject
            assertTrue(asked.getBoolean("ok"))
            // 服务端把 roots/list 推到 GET 流，客户端应答后服务端存下；
            // 轮询取回，断言应答里就是客户端配的根目录。
            var response: JSONObject? = null
            val deadline = System.currentTimeMillis() + 5_000
            while (response == null && System.currentTimeMillis() < deadline) {
                val got = client.rawRequest("test/get-roots-response") as JSONObject
                if (!got.isNull("response")) response = got.getJSONObject("response")
                else delay(100)
            }
            assertNotNull(response)
            assertEquals(9001, response!!.getInt("id"))
            val roots = response.getJSONObject("result").getJSONArray("roots")
            assertEquals(1, roots.length())
            assertEquals("file:///tmp/w5roots", roots.getJSONObject(0).getString("uri"))
            assertEquals("w5", roots.getJSONObject(0).getString("name"))
        } finally {
            client.close()
        }
    }
}
