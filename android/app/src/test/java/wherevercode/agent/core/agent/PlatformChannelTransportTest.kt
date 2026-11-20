package wherevercode.agent.core.agent

import kotlinx.coroutines.runBlocking
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 平台频道传输（V5，2026-11-11）：MCP `tools/call` 信封形状与
 * `custom.wherevercode` 连接器一致；参数口径按 2026-11-11 实测
 * `tools/list` 的 inputSchema（`agent_channel_post` 只有
 * channel_id/text/reply_to，没有收件方字段）。
 * 假 HTTP 客户端，不发真实调用。
 */
class PlatformChannelTransportTest {

    @Test
    fun `network errors have actionable messages and do not reveal raw causes`() {
        assertTrue(
            UrlConnectionPlatformHttpClient.networkFailureMessage(
                UnknownHostException("secret-host"),
            )!!.contains("无法解析平台地址"),
        )
        assertTrue(
            UrlConnectionPlatformHttpClient.networkFailureMessage(
                SocketTimeoutException("read timed out"),
            )!!.contains("连接平台超时"),
        )
        assertTrue(
            UrlConnectionPlatformHttpClient.networkFailureMessage(
                SSLPeerUnverifiedException("hostname mismatch"),
            )!!.contains("主机名校验失败"),
        )
        val expired = java.io.IOException("handshake", CertificateExpiredException("certificate expired"))
        assertTrue(
            UrlConnectionPlatformHttpClient.networkFailureMessage(expired)!!
                .contains("证书已过期"),
        )
        val pinMismatch = java.io.IOException(
            "handshake", CertificateException("The configured platform endpoint certificate pin did not match."),
        )
        assertTrue(
            UrlConnectionPlatformHttpClient.networkFailureMessage(pinMismatch)!!
                .contains("证书指纹与本地配置不一致"),
        )
        assertTrue(
            UrlConnectionPlatformHttpClient.networkFailureMessage(SSLHandshakeException("handshake failed"))!!
                .contains("TLS 握手失败"),
        )
        assertEquals(
            null,
            UrlConnectionPlatformHttpClient.networkFailureMessage(java.io.IOException("HTTP 401")),
        )
    }

    private fun envelope(toolJson: String): String = JSONObject().apply {
        put("jsonrpc", "2.0")
        put("id", "1")
        put("result", JSONObject().apply {
            put("isError", false)
            put("content", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", toolJson)
                })
            })
        })
    }.toString()

    private class ScriptedHttp(
        private val replies: Map<String, String>,
    ) : PlatformHttpClient {
        data class Call(val url: String, val bearerToken: String, val bodyJson: String)
        val calls = mutableListOf<Call>()
        override fun postJson(url: String, bearerToken: String, bodyJson: String): String {
            calls += Call(url, bearerToken, bodyJson)
            val name = JSONObject(bodyJson)
                .optJSONObject("params")?.optString("name", "").orEmpty()
            return replies[name] ?: throw IllegalStateException("no scripted reply for $name")
        }
    }

    private fun transport(http: PlatformHttpClient) =
        PlatformChannelMcpTransport(
            tokenProvider = { "test-token-not-real" },
            httpClient = http,
            endpoint = "https://example.invalid/api/open/v1/mcp",
        )

    @Test
    fun `create channel sends the management tool name and name argument`() {
        val http = ScriptedHttp(
            mapOf("agent_channel_create" to envelope("""{"ok":true,"id":"chan-9"}""")),
        )
        val result = transport(http).createChannel("项目讨论")
        assertEquals("chan-9", result.optString("id"))
        val body = JSONObject(http.calls.single().bodyJson)
        assertEquals(
            "agent_channel_create",
            body.optJSONObject("params")?.optString("name"),
        )
        assertEquals(
            "项目讨论",
            body.optJSONObject("params")?.optJSONObject("arguments")?.optString("name"),
        )
    }

    @Test
    fun `member add and remove send channel and agent ids`() {
        val http = ScriptedHttp(
            mapOf(
                "agent_channel_add_member" to envelope("""{"ok":true}"""),
                "agent_channel_remove_member" to envelope("""{"ok":true}"""),
            ),
        )
        val t = transport(http)
        t.addChannelMember("chan-1", "agent-7")
        t.removeChannelMember("chan-1", "agent-7")
        val names = http.calls.map {
            JSONObject(it.bodyJson).optJSONObject("params")?.optString("name")
        }
        assertEquals(listOf("agent_channel_add_member", "agent_channel_remove_member"), names)
        val args = JSONObject(http.calls[0].bodyJson)
            .optJSONObject("params")?.optJSONObject("arguments")
        assertEquals("chan-1", args?.optString("channel_id"))
        assertEquals("agent-7", args?.optString("agent_id"))
    }

    @Test
    fun `rename archive and delete send their tool names`() {
        val http = ScriptedHttp(
            mapOf(
                "agent_channel_rename" to envelope("""{"ok":true}"""),
                "agent_channel_set_archived" to envelope("""{"ok":true}"""),
                "agent_channel_delete" to envelope("""{"ok":true}"""),
            ),
        )
        val t = transport(http)
        t.renameChannel("chan-1", "新名字")
        t.setChannelArchived("chan-1", true)
        t.deleteChannel("chan-1")
        val names = http.calls.map {
            JSONObject(it.bodyJson).optJSONObject("params")?.optString("name")
        }
        assertEquals(
            listOf("agent_channel_rename", "agent_channel_set_archived", "agent_channel_delete"),
            names,
        )
        val archiveArgs = JSONObject(http.calls[1].bodyJson)
            .optJSONObject("params")?.optJSONObject("arguments")
        assertEquals(true, archiveArgs?.optBoolean("archived"))
    }

    @Test
    fun `delete preserves server owner authorization rejection`() {
        val http = ScriptedHttp(
            mapOf("agent_channel_delete" to JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", "1")
                put("result", JSONObject().apply {
                    put("isError", true)
                    put("content", org.json.JSONArray().apply {
                        put(JSONObject().apply { put("type", "text"); put("text", "仅频道 owner 可删除") })
                    })
                })
            }.toString()),
        )
        try {
            transport(http).deleteChannel("chan-1")
            throw AssertionError("expected IOException")
        } catch (e: java.io.IOException) {
            assertTrue(e.message.orEmpty().contains("owner"))
        }
        assertEquals("agent_channel_delete", JSONObject(http.calls.single().bodyJson)
            .optJSONObject("params")?.optString("name"))
    }

    @Test
    fun `member removal preserves server creator protection rejection`() {
        val http = ScriptedHttp(
            mapOf("agent_channel_remove_member" to JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", "1")
                put("result", JSONObject().apply {
                    put("isError", true)
                    put("content", org.json.JSONArray().apply {
                        put(JSONObject().apply { put("type", "text"); put("text", "频道创建者不可移除") })
                    })
                })
            }.toString()),
        )
        try {
            transport(http).removeChannelMember("chan-1", "creator-agent")
            throw AssertionError("expected IOException")
        } catch (e: java.io.IOException) {
            assertTrue(e.message.orEmpty().contains("创建者不可移除"))
        }
    }

    @Test
    fun `post sends channel id and text and returns the posted id`() = runBlocking {
        val http = ScriptedHttp(
            mapOf("agent_channel_post" to envelope("""{"ok":true,"id":"m123"}""")),
        )
        val id = transport(http).postMessage("chan-1", "@example-agent 在吗")

        assertEquals("m123", id)
        assertEquals(1, http.calls.size)
        assertEquals("https://example.invalid/api/open/v1/mcp", http.calls[0].url)
        assertEquals("test-token-not-real", http.calls[0].bearerToken)
        val body = JSONObject(http.calls[0].bodyJson)
        assertEquals("tools/call", body.optString("method"))
        val args = body.optJSONObject("params")?.optJSONObject("arguments")
        assertEquals("chan-1", args?.optString("channel_id"))
        assertEquals("@example-agent 在吗", args?.optString("text"))
        // 收件方只在正文里（平台 API 没有收件方字段），绝不在参数里编一个。
        assertTrue(!args!!.has("agent_id"))
        assertTrue(!args.has("target"))
    }

    @Test
    fun `explicit endpoint is used instead of the default`() = runBlocking {
        val http = ScriptedHttp(
            mapOf("agent_channel_post" to envelope("""{"ok":true,"id":"m9"}""")),
        )
        val transport = PlatformChannelMcpTransport(
            tokenProvider = { "test-token-not-real" },
            httpClient = http,
            endpoint = "https://us.example/api/open/v1/mcp",
        )
        transport.postMessage("chan-1", "@example-agent 在吗")

        assertEquals(1, http.calls.size)
        assertEquals("https://us.example/api/open/v1/mcp", http.calls[0].url)
    }

    @Test
    fun `poll parses messages with agent names`() = runBlocking {
        val http = ScriptedHttp(
            mapOf(
                "agent_channel_messages" to envelope(
                    """{"ok":true,"messages":[
                      {"id":"m1","agent_id":"a1","agent_name":"example agent","text":"在的。","created_at":1794300000.0},
                      {"id":"m2","agent_id":"a2","agent_name":"muse","text":"收到","created_at":1794300001.0}
                    ]}""",
                ),
            ),
        )
        val messages = transport(http).pollMessages("chan-1", "m0", 20)

        assertEquals(2, messages.size)
        assertEquals("example agent", messages[0].agentName)
        assertEquals("在的。", messages[0].text)
        val args = JSONObject(http.calls[0].bodyJson)
            .optJSONObject("params")?.optJSONObject("arguments")
        assertEquals("m0", args?.optString("after_id"))
        assertEquals(20, args?.optInt("limit"))
    }

    @Test
    fun `platform error is reported not swallowed`() = runBlocking {
        val http = ScriptedHttp(
            mapOf(
                "agent_channel_post" to JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("id", "1")
                    put("result", JSONObject().apply {
                        put("isError", true)
                        put("content", org.json.JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", "channel not joined")
                            })
                        })
                    })
                }.toString(),
            ),
        )
        try {
            transport(http).postMessage("chan-1", "hi")
            throw AssertionError("expected IOException")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("channel not joined"))
        }
    }

    @Test
    fun `http error message prefers the server text in the envelope`() {
        val body = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", "1")
            put("result", JSONObject().apply {
                put("isError", true)
                put("content", org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", "Agent 不存在或已停用")
                    })
                })
            })
        }.toString()

        assertEquals(
            "平台返回 HTTP 404：Agent 不存在或已停用",
            UrlConnectionPlatformHttpClient.httpErrorMessage(404, body),
        )
    }

    @Test
    fun `http error message falls back to status only without a usable body`() {
        assertEquals(
            "平台返回 HTTP 401",
            UrlConnectionPlatformHttpClient.httpErrorMessage(401, "not json"),
        )
        assertEquals(
            "平台返回 HTTP 500",
            UrlConnectionPlatformHttpClient.httpErrorMessage(500, ""),
        )
        assertEquals(
            "平台返回 HTTP 500",
            UrlConnectionPlatformHttpClient.httpErrorMessage(500, "{}"),
        )
    }

    @Test
    fun `missing token fails before any http call`() = runBlocking {
        val http = ScriptedHttp(emptyMap())
        val noToken = PlatformChannelMcpTransport(tokenProvider = { null }, httpClient = http)
        try {
            noToken.postMessage("chan-1", "hi")
            throw AssertionError("expected IOException")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("未配置平台 Agent 凭证"))
        }
        assertTrue(http.calls.isEmpty())
    }


    @Test
    fun `poll with onlyUnread and markRead sends both flags`() = runBlocking {
        val http = ScriptedHttp(
            mapOf("agent_channel_messages" to envelope("""{"ok":true,"messages":[]}""")),
        )
        val messages = transport(http).pollMessages("chan-1", null, 20, onlyUnread = true, markRead = true)

        assertEquals(0, messages.size)
        val args = JSONObject(http.calls[0].bodyJson)
            .optJSONObject("params")?.optJSONObject("arguments")
        assertEquals(true, args?.optBoolean("only_unread"))
        assertEquals(true, args?.optBoolean("mark_read"))
    }

    @Test
    fun `poll without flags sends neither flag`() = runBlocking {
        val http = ScriptedHttp(
            mapOf("agent_channel_messages" to envelope("""{"ok":true,"messages":[]}""")),
        )
        transport(http).pollMessages("chan-1", null, 20)

        val args = JSONObject(http.calls[0].bodyJson)
            .optJSONObject("params")?.optJSONObject("arguments")
        assertTrue(!args!!.has("only_unread"))
        assertTrue(!args.has("mark_read"))
    }

    @Test
    fun `unauthorized member cannot read the channel`() = runBlocking {
        val http = object : PlatformHttpClient {
            override fun postJson(url: String, bearerToken: String, bodyJson: String): String {
                throw java.io.IOException("平台返回 HTTP 403：未加入频道")
            }
        }
        try {
            transport(http).pollMessages("chan-1", null, 20, onlyUnread = true)
            throw AssertionError("expected IOException")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("403"))
        }
    }
    @Test
    fun `callTool posts a generic tools call and returns the tool json`() = runBlocking {
        val http = ScriptedHttp(
            mapOf(
                "issue_agent_token" to envelope(
                    """{"token":"v5_test-token-not-real","token_id":"v5tok_test","agent_id":"chatgpt","name":"App 生成"}""",
                ),
            ),
        )
        val result = transport(http).callTool(
            "issue_agent_token",
            JSONObject().apply {
                put("agent_id", "chatgpt")
                put("name", "App 生成")
            },
        )

        assertEquals("v5_test-token-not-real", result.optString("token"))
        assertEquals("v5tok_test", result.optString("token_id"))
        assertEquals(1, http.calls.size)
        val body = JSONObject(http.calls[0].bodyJson)
        assertEquals("tools/call", body.optString("method"))
        assertEquals(
            "issue_agent_token",
            body.optJSONObject("params")?.optString("name"),
        )
        assertEquals(
            "chatgpt",
            body.optJSONObject("params")?.optJSONObject("arguments")?.optString("agent_id"),
        )
    }

    @Test
    fun `recall delete pin react send their tool names and arguments`() {
        val http = ScriptedHttp(
            mapOf(
                "agent_channel_recall" to envelope("""{"ok":true}"""),
                "agent_channel_delete_message" to envelope("""{"ok":true}"""),
                "agent_channel_pin" to envelope("""{"ok":true}"""),
                "agent_channel_react" to envelope("""{"ok":true}"""),
            ),
        )
        val t = transport(http)
        t.recallMessage("chan-1", "m1")
        t.deleteMessage("chan-1", "m2")
        t.setMessagePinned("chan-1", "m3", true)
        t.reactMessage("chan-1", "m4", "👍", true)
        val names = http.calls.map {
            JSONObject(it.bodyJson).optJSONObject("params")?.optString("name")
        }
        assertEquals(
            listOf(
                "agent_channel_recall", "agent_channel_delete_message",
                "agent_channel_pin", "agent_channel_react",
            ),
            names,
        )
        val pinArgs = JSONObject(http.calls[2].bodyJson)
            .optJSONObject("params")?.optJSONObject("arguments")
        assertEquals(true, pinArgs?.optBoolean("pinned"))
        val reactArgs = JSONObject(http.calls[3].bodyJson)
            .optJSONObject("params")?.optJSONObject("arguments")
        assertEquals("👍", reactArgs?.optString("emoji"))
        assertEquals(true, reactArgs?.optBoolean("on"))
    }

    @Test
    fun `post ex sends effect key attachment and forward fields`() {
        val http = ScriptedHttp(
            mapOf("agent_channel_post" to envelope("""{"id":"m9"}""")),
        )
        val id = transport(http).postMessageEx(
            "chan-1", "正文", replyTo = "m1", effectKey = "ck_abc",
            attachmentRef = "drive://file/1", forwardOf = "频道甲 · 乙",
        )
        assertEquals("m9", id)
        val args = JSONObject(http.calls.single().bodyJson)
            .optJSONObject("params")?.optJSONObject("arguments")
        assertEquals("ck_abc", args?.optString("effect_key"))
        assertEquals("drive://file/1", args?.optString("attachment_ref"))
        assertEquals("频道甲 · 乙", args?.optString("forward_of"))
        assertEquals("m1", args?.optString("reply_to"))
    }

    @Test
    fun `poll parses phase2 fields`() = runBlocking {
        val http = ScriptedHttp(
            mapOf(
                "agent_channel_messages" to envelope(
                    """{"messages":[{"id":"m1","agent_id":"a1","agent_name":"甲","text":"","created_at":12.5,"reply_to":"m0","recalled":true,"pinned":true,"attachment_ref":"ref-1","forward_of":"频道甲 · 乙","reactions":[{"emoji":"👍","count":2,"mine":true}]}]}""",
                ),
            ),
        )
        val messages = transport(http).pollMessages("chan-1", null)
        val m = messages.single()
        assertEquals(true, m.recalled)
        assertEquals(true, m.pinned)
        assertEquals("m0", m.replyTo)
        assertEquals("ref-1", m.attachmentRef)
        assertEquals("频道甲 · 乙", m.forwardOf)
        assertEquals(1, m.reactions.size)
        assertEquals(2, m.reactions[0].count)
        assertEquals(true, m.reactions[0].mine)
    }

    @Test
    fun `search sends q and readers parse`() {
        val http = ScriptedHttp(
            mapOf(
                "agent_channel_messages" to envelope("""{"messages":[]}"""),
                "agent_channel_readers" to envelope(
                    """{"readers":[{"agent_id":"a1","agent_name":"甲","read":true},{"agent_id":"a2","agent_name":"乙","read":false}]}""",
                ),
            ),
        )
        val t = transport(http)
        t.searchMessages("chan-1", "关键词")
        val args = JSONObject(http.calls[0].bodyJson)
            .optJSONObject("params")?.optJSONObject("arguments")
        assertEquals("关键词", args?.optString("q"))
        val readers = t.channelReaders("chan-1", "m1")
        assertEquals(2, readers.size)
        assertEquals(true, readers[0].read)
        assertEquals(false, readers[1].read)
    }

    @Test
    fun `list channels parses kind and archived and open dm sends agent id`() {
        val http = ScriptedHttp(
            mapOf(
                "agent_channel_list" to envelope(
                    """{"channels":[{"id":"c1","name":"大厅","kind":"channel","archived":false,"participants":[{"agent_id":"a1","agent_name":"甲"}]},{"id":"d1","name":"甲","kind":"dm","archived":true,"participants":[]}]}""",
                ),
                "agent_channel_open_dm" to envelope("""{"id":"d1","kind":"dm"}"""),
            ),
        )
        val t = transport(http)
        val channels = t.listChannels()
        assertEquals(2, channels.size)
        assertEquals("dm", channels[1].kind)
        assertEquals(true, channels[1].archived)
        assertEquals(listOf("a1"), channels[0].participantIds)
        val dm = t.openDm("a9")
        assertEquals("d1", dm.optString("id"))
        val dmArgs = JSONObject(http.calls[1].bodyJson)
            .optJSONObject("params")?.optJSONObject("arguments")
        assertEquals("a9", dmArgs?.optString("agent_id"))
    }
}
