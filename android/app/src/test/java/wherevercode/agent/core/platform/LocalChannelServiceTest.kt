package wherevercode.agent.core.platform

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import wherevercode.agent.core.agent.PlatformHttpClient

/**
 * 本地频道账本（频道三层一期·本地层）：语义与平台服务端对齐的
 * 关键路径——取消种子与空壳清理、发读游标、成员门禁、私聊规则、角色保护、
 * 二/三期操作（置顶/反应/撤回/已读）、effect_key 幂等、落盘恢复、
 * 进程内 MCP 信封与本地推送总线。
 */
class LocalChannelServiceTest {

    private var now = 1_000.0

    private fun newService(persistence: LocalChannelPersistence = InMemoryLocalChannelPersistence()) =
        LocalChannelService(persistence) { now }

    private fun call(
        service: LocalChannelService,
        tool: String,
        args: JSONObject = JSONObject(),
        callerId: String = LocalPlatform.APP_AGENT_ID,
        callerName: String = LocalPlatform.APP_AGENT_NAME,
    ): JSONObject = service.dispatch(tool, args, callerId, callerName)

    private fun postArgs(channelId: String, text: String): JSONObject = JSONObject().apply {
        put("channel_id", channelId)
        put("text", text)
    }

    /** 无种子时代的频道夹具：显式建一个频道（本机 App 为 owner），返回其 id。 */
    private fun makeChannel(service: LocalChannelService, name: String = "测试频道"): String =
        call(service, "agent_channel_create", JSONObject().put("name", name)).getString("id")

    private fun messagesOf(result: JSONObject): List<JSONObject> {
        val array = result.getJSONArray("messages")
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    @Test
    fun `fresh service starts empty without a seeded channel`() {
        val service = newService()
        val result = call(service, "agent_channel_list")
        assertEquals(0, result.getJSONArray("channels").length())
        assertEquals("", service.defaultChannelId())
    }

    @Test
    fun `legacy seeded empty channel is cleaned up once`() {
        val persistence = InMemoryLocalChannelPersistence()
        persistence.stored = legacySeededSnapshot(null)
        val service = newService(persistence)
        val result = call(service, "agent_channel_list")
        assertEquals(0, result.getJSONArray("channels").length())
        assertEquals("", service.defaultChannelId())
    }

    @Test
    fun `legacy seeded channel with messages is kept`() {
        val persistence = InMemoryLocalChannelPersistence()
        persistence.stored = legacySeededSnapshot("种子里的真消息")
        val service = newService(persistence)
        val result = call(service, "agent_channel_list")
        val channels = result.getJSONArray("channels")
        assertEquals(1, channels.length())
        assertEquals("本地频道", channels.getJSONObject(0).getString("name"))
        val listed = messagesOf(call(service, "agent_channel_messages",
            JSONObject().put("channel_id", channels.getJSONObject(0).getString("id"))))
        assertEquals(listOf("种子里的真消息"), listed.map { it.getString("text") })
    }

    @Test
    fun `create channel dedupes duplicate names`() {
        val service = newService()
        val first = call(service, "agent_channel_create", JSONObject().put("name", "本地频道"))
        val second = call(service, "agent_channel_create", JSONObject().put("name", "本地频道"))
        val third = call(service, "agent_channel_create", JSONObject().put("name", "本地频道"))
        assertEquals("本地频道", first.getString("name"))
        assertEquals("本地频道 2", second.getString("name"))
        assertEquals("本地频道 3", third.getString("name"))
    }

    /** 旧版本（种子时代）落盘快照的最小复刻：默认频道 + 本机 App owner + 可选一条消息。 */
    private fun legacySeededSnapshot(messageText: String?): String {
        val channelId = "seededchan000000000000000000001"
        val messages = JSONArray()
        if (messageText != null) {
            messages.put(JSONObject().apply {
                put("id", "seededmsg0000000000000000000001")
                put("channel_id", channelId)
                put("agent_id", LocalPlatform.APP_AGENT_ID)
                put("agent_name", LocalPlatform.APP_AGENT_NAME)
                put("text", messageText)
                put("created_at", 1_000.0)
                put("seq", 1)
                put("mentions_all", false)
                put("pinned", false)
                put("recalled", false)
            })
        }
        return JSONObject().apply {
            put("version", 1)
            put("default_channel_id", channelId)
            put("seq", 1)
            put("agents", JSONArray().put(JSONObject().apply {
                put("id", LocalPlatform.APP_AGENT_ID)
                put("name", LocalPlatform.APP_AGENT_NAME)
                put("roles", JSONArray())
            }))
            put("channels", JSONArray().put(JSONObject().apply {
                put("id", channelId)
                put("name", "本地频道")
                put("kind", "channel")
                put("archived", false)
                put("history_visible", true)
                put("created_at", 1_000.0)
            }))
            put("members", JSONArray().put(JSONObject().apply {
                put("channel_id", channelId)
                put("agent_id", LocalPlatform.APP_AGENT_ID)
                put("agent_name", LocalPlatform.APP_AGENT_NAME)
                put("role", "owner")
                put("joined_at", 1_000.0)
            }))
            put("messages", messages)
            put("reactions", JSONArray())
            put("read_markers", JSONArray())
            put("webhooks", JSONArray())
            put("effect_keys", JSONArray())
        }.toString()
    }

    @Test
    fun `post and read round trip keeps platform message shape`() {
        val service = newService()
        val channelId = makeChannel(service)
        val posted = call(service, "agent_channel_post", postArgs(channelId, "第一条"))
        assertEquals(32, posted.getString("id").length)
        assertEquals("第一条", posted.getString("text"))
        assertEquals(LocalPlatform.APP_AGENT_ID, posted.getString("agent_id"))
        val reply = call(service, "agent_channel_post", postArgs(channelId, "回复").apply {
            put("reply_to", posted.getString("id"))
            put("attachment_ref", "drive://file/abc.png")
        })
        assertEquals(posted.getString("id"), reply.getString("reply_to"))
        assertEquals("drive://file/abc.png", reply.getString("attachment_ref"))
        val listed = messagesOf(call(service, "agent_channel_messages", JSONObject().apply {
            put("channel_id", channelId)
            put("limit", 50)
        }))
        assertEquals(listOf("第一条", "回复"), listed.map { it.getString("text") })
    }

    @Test
    fun `non member cannot read and gate message mentions membership`() {
        val service = newService()
        service.registerAgent("agent-b", "Agent B")
        val channelId = makeChannel(service)
        call(service, "agent_channel_post", postArgs(channelId, "内部消息"))
        try {
            call(service, "agent_channel_messages", JSONObject().put("channel_id", channelId),
                callerId = "agent-b", callerName = "Agent B")
            fail("非成员读消息必须被门禁挡下")
        } catch (e: LocalServiceException) {
            assertTrue(e.message!!.contains("成员"))
        }
        // 群频道可自助加入，加入后可读。
        call(service, "agent_channel_join", JSONObject().put("channel_id", channelId),
            callerId = "agent-b", callerName = "Agent B")
        val listed = messagesOf(call(service, "agent_channel_messages",
            JSONObject().put("channel_id", channelId), callerId = "agent-b", callerName = "Agent B"))
        assertEquals(1, listed.size)
    }

    @Test
    fun `dm cannot be self joined and opens once per pair`() {
        val service = newService()
        service.registerAgent("agent-b", "Agent B")
        val dm = call(service, "agent_channel_open_dm", JSONObject().put("agent_id", "agent-b"))
        assertEquals("dm", dm.getString("kind"))
        val again = call(service, "agent_channel_open_dm", JSONObject().put("agent_id", "agent-b"))
        assertEquals(dm.getString("id"), again.getString("id"))
        try {
            call(service, "agent_channel_join", JSONObject().put("channel_id", dm.getString("id")),
                callerId = "agent-c", callerName = "Agent C")
            fail("私聊禁自行加入")
        } catch (e: LocalServiceException) {
            assertTrue(e.message!!.contains("私聊"))
        }
        try {
            call(service, "agent_channel_open_dm", JSONObject().put("agent_id", "ghost"))
            fail("未登记的 Agent 不能私聊")
        } catch (e: LocalServiceException) {
            assertTrue(e.message!!.contains("不存在"))
        }
    }

    @Test
    fun `pagination follows after and before cursors`() {
        val service = newService()
        val channelId = makeChannel(service)
        val ids = (1..30).map {
            now += 1
            call(service, "agent_channel_post", postArgs(channelId, "m$it")).getString("id")
        }
        val firstPage = messagesOf(call(service, "agent_channel_messages", JSONObject().apply {
            put("channel_id", channelId)
            put("limit", 20)
        }))
        assertEquals(ids.subList(10, 30), firstPage.map { it.getString("id") })
        val after = messagesOf(call(service, "agent_channel_messages", JSONObject().apply {
            put("channel_id", channelId)
            put("after_id", ids[9])
            put("limit", 50)
        }))
        assertEquals(ids.subList(10, 30), after.map { it.getString("id") })
        val before = messagesOf(call(service, "agent_channel_messages", JSONObject().apply {
            put("channel_id", channelId)
            put("before_id", ids[10])
            put("limit", 50)
        }))
        assertEquals(ids.subList(0, 10), before.map { it.getString("id") })
    }

    @Test
    fun `effect key dedupes repost`() {
        val service = newService()
        val channelId = makeChannel(service)
        val args = postArgs(channelId, "定时补发").apply { put("effect_key", "sched-1") }
        val first = call(service, "agent_channel_post", args)
        val second = call(service, "agent_channel_post", args)
        assertEquals(first.getString("id"), second.getString("id"))
        val listed = messagesOf(call(service, "agent_channel_messages",
            JSONObject().put("channel_id", channelId)))
        assertEquals(1, listed.size)
    }

    @Test
    fun `pin react recall and readers work end to end`() {
        val service = newService()
        service.registerAgent("agent-b", "Agent B")
        val channelId = makeChannel(service)
        call(service, "agent_channel_join", JSONObject().put("channel_id", channelId),
            callerId = "agent-b", callerName = "Agent B")
        val posted = call(service, "agent_channel_post", postArgs(channelId, "看这条"))
        val messageId = posted.getString("id")
        // 置顶后 pinned_only 只回这一条。
        call(service, "agent_channel_pin", JSONObject().apply {
            put("channel_id", channelId)
            put("message_id", messageId)
            put("pinned", true)
        })
        val pinned = messagesOf(call(service, "agent_channel_messages", JSONObject().apply {
            put("channel_id", channelId)
            put("pinned_only", true)
        }))
        assertEquals(listOf(messageId), pinned.map { it.getString("id") })
        assertTrue(pinned[0].getBoolean("pinned"))
        // 反应：B 点赞后 count=1、B 视角 mine=true；取消后消失。
        call(service, "agent_channel_react", JSONObject().apply {
            put("channel_id", channelId)
            put("message_id", messageId)
            put("emoji", "👍")
            put("on", true)
        }, callerId = "agent-b", callerName = "Agent B")
        val afterReact = messagesOf(call(service, "agent_channel_messages",
            JSONObject().put("channel_id", channelId),
            callerId = "agent-b", callerName = "Agent B")).single()
        val reaction = afterReact.getJSONArray("reactions").getJSONObject(0)
        assertEquals(1, reaction.getInt("count"))
        assertTrue(reaction.getBoolean("mine"))
        // B 标已读后，readers 里 B 是 read=true。
        call(service, "agent_channel_messages", JSONObject().apply {
            put("channel_id", channelId)
            put("mark_read", true)
        }, callerId = "agent-b", callerName = "Agent B")
        val readers = call(service, "agent_channel_readers", JSONObject().apply {
            put("channel_id", channelId)
            put("message_id", messageId)
        }).getJSONArray("readers")
        val bRow = (0 until readers.length()).map { readers.getJSONObject(it) }
            .first { it.getString("agent_id") == "agent-b" }
        assertTrue(bRow.getBoolean("read"))
        // 撤回：只能撤自己的；撤回后 text 清空、recalled=true。
        try {
            call(service, "agent_channel_recall", JSONObject().apply {
                put("channel_id", channelId)
                put("message_id", messageId)
            }, callerId = "agent-b", callerName = "Agent B")
            fail("不能撤回别人的消息")
        } catch (e: LocalServiceException) {
            assertTrue(e.message!!.contains("自己"))
        }
        val recalled = call(service, "agent_channel_recall", JSONObject().apply {
            put("channel_id", channelId)
            put("message_id", messageId)
        })
        assertTrue(recalled.getBoolean("recalled"))
        assertEquals("", recalled.getString("text"))
    }

    @Test
    fun `role changes are owner only and last owner is protected`() {
        val service = newService()
        service.registerAgent("agent-b", "Agent B")
        val channelId = makeChannel(service)
        call(service, "agent_channel_join", JSONObject().put("channel_id", channelId),
            callerId = "agent-b", callerName = "Agent B")
        try {
            call(service, "agent_channel_set_member_role", JSONObject().apply {
                put("channel_id", channelId)
                put("member_id", "agent-b")
                put("role", "coordinator")
            }, callerId = "agent-b", callerName = "Agent B")
            fail("非 owner 不能改角色")
        } catch (e: LocalServiceException) {
            assertTrue(e.message!!.contains("owner"))
        }
        val promoted = call(service, "agent_channel_set_member_role", JSONObject().apply {
            put("channel_id", channelId)
            put("member_id", "agent-b")
            put("role", "coordinator")
        })
        assertEquals("coordinator", promoted.getString("role"))
        try {
            call(service, "agent_channel_set_member_role", JSONObject().apply {
                put("channel_id", channelId)
                put("member_id", LocalPlatform.APP_AGENT_ID)
                put("role", "member")
            })
            fail("最后一个 owner 不能降级")
        } catch (e: LocalServiceException) {
            assertTrue(e.message!!.contains("owner"))
        }
    }

    @Test
    fun `mentions all only counts for privileged posters`() {
        val service = newService()
        service.registerAgent("agent-b", "Agent B")
        val channelId = makeChannel(service)
        val ownerPost = call(service, "agent_channel_post", postArgs(channelId, "@所有人 开会"))
        assertTrue(ownerPost.getBoolean("mentions_all"))
        call(service, "agent_channel_join", JSONObject().put("channel_id", channelId),
            callerId = "agent-b", callerName = "Agent B")
        val memberPost = call(service, "agent_channel_post", postArgs(channelId, "@所有人 跟一下"),
            callerId = "agent-b", callerName = "Agent B")
        assertFalse(memberPost.has("mentions_all"))
    }

    @Test
    fun `history hidden channel only shows messages after join`() {
        val service = newService()
        service.registerAgent("agent-b", "Agent B")
        val channel = call(service, "agent_channel_create", JSONObject().put("name", "保密频道"))
        val channelId = channel.getString("id")
        now = 2_000.0
        call(service, "agent_channel_post", postArgs(channelId, "入群前"))
        call(service, "agent_channel_set_history_visible", JSONObject().apply {
            put("channel_id", channelId)
            put("visible", false)
        })
        now = 3_000.0
        call(service, "agent_channel_add_member", JSONObject().apply {
            put("channel_id", channelId)
            put("agent_id", "agent-b")
        })
        now = 4_000.0
        call(service, "agent_channel_post", postArgs(channelId, "入群后"))
        val seen = messagesOf(call(service, "agent_channel_messages",
            JSONObject().put("channel_id", channelId),
            callerId = "agent-b", callerName = "Agent B"))
        assertEquals(listOf("入群后"), seen.map { it.getString("text") })
    }

    @Test
    fun `webhooks need privilege and secret shows once`() {
        val service = newService()
        service.registerAgent("agent-b", "Agent B")
        val channelId = makeChannel(service)
        call(service, "agent_channel_join", JSONObject().put("channel_id", channelId),
            callerId = "agent-b", callerName = "Agent B")
        try {
            call(service, "agent_channel_create_webhook", JSONObject().apply {
                put("channel_id", channelId)
                put("name", "WhereverCode App")
            }, callerId = "agent-b", callerName = "Agent B")
            fail("普通成员不能管 Webhook")
        } catch (e: LocalServiceException) {
            assertTrue(e.message!!.contains("owner"))
        }
        val created = call(service, "agent_channel_create_webhook", JSONObject().apply {
            put("channel_id", channelId)
            put("name", "WhereverCode App")
        })
        assertTrue(created.getString("secret").isNotBlank())
        val listed = call(service, "agent_channel_list_webhooks",
            JSONObject().put("channel_id", channelId))
        val hooks = listed.getJSONArray("webhooks")
        assertEquals(1, hooks.length())
        assertFalse(hooks.getJSONObject(0).has("secret"))
        call(service, "agent_channel_delete_webhook", JSONObject().apply {
            put("channel_id", channelId)
            put("webhook_id", created.getString("webhook_id"))
        })
        val after = call(service, "agent_channel_list_webhooks",
            JSONObject().put("channel_id", channelId))
        assertEquals(0, after.getJSONArray("webhooks").length())
    }

    @Test
    fun `state survives a rebuild over the same persistence`() {
        val persistence = InMemoryLocalChannelPersistence()
        val first = newService(persistence)
        val channelId = makeChannel(first)
        call(first, "agent_channel_post", postArgs(channelId, "落盘的消息"))
        val second = newService(persistence)
        val channels = call(second, "agent_channel_list").getJSONArray("channels")
        assertEquals(listOf(channelId), (0 until channels.length()).map { channels.getJSONObject(it).getString("id") })
        val listed = messagesOf(call(second, "agent_channel_messages",
            JSONObject().put("channel_id", channelId)))
        assertEquals(listOf("落盘的消息"), listed.map { it.getString("text") })
    }

    @Test
    fun `http client wraps tools call envelope and errors`() {
        val service = newService()
        val client = LocalFirstPlatformHttpClient(
            delegate = object : PlatformHttpClient {
                override fun postJson(url: String, bearerToken: String, bodyJson: String): String =
                    throw AssertionError("本地端点不许走网络委托")
                override fun getJson(url: String, bearerToken: String?): String =
                    throw AssertionError("本地端点不许走网络委托")
            },
            serviceProvider = { service },
        )
        val channelId = makeChannel(service)
        val body = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", "1")
            put("method", "tools/call")
            put("params", JSONObject().apply {
                put("name", "agent_channel_post")
                put("arguments", postArgs(channelId, "经信封发的"))
            })
        }.toString()
        val raw = client.postJson(
            "https://local.platform/api/open/v1/mcp",
            LocalPlatform.LOCAL_TOKEN,
            body,
        )
        val result = JSONObject(raw).getJSONObject("result")
        assertFalse(result.getBoolean("isError"))
        val text = result.getJSONArray("content").getJSONObject(0).getString("text")
        assertEquals("经信封发的", JSONObject(text).getString("text"))
        // 凭证不对时走 isError 信封（传输层会把文案原样抛给界面）。
        service.registerAgent("agent-b", "Agent B")
        val deniedBody = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", "2")
            put("method", "tools/call")
            put("params", JSONObject().apply {
                put("name", "agent_channel_messages")
                put("arguments", JSONObject().put("channel_id", channelId))
            })
        }.toString()
        val deniedRaw = client.postJson(
            "https://local.platform/api/open/v1/mcp",
            "wrong-token",
            deniedBody,
        )
        val denied = JSONObject(deniedRaw).getJSONObject("result")
        assertTrue(denied.getBoolean("isError"))
    }

    @Test
    fun `local ws connection delivers posted message events`() {
        val service = newService()
        val channelId = makeChannel(service)
        val connection = LocalChannelWsConnection(channelId) { service }
        val latch = CountDownLatch(1)
        val received = StringBuilder()
        val thread = Thread {
            connection.run { text ->
                synchronized(received) { received.append(text) }
                latch.countDown()
            }
        }
        thread.start()
        // 等订阅注册完成再发帖。
        val deadline = System.currentTimeMillis() + 5_000
        while (!connection.isOpen && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertTrue(connection.isOpen)
        call(service, "agent_channel_post", postArgs(channelId, "推送测试"))
        assertTrue("本地推送应在 5 秒内到达", latch.await(5, TimeUnit.SECONDS))
        val event = JSONObject(synchronized(received) { received.toString() })
        assertEquals("message", event.getString("type"))
        assertEquals("推送测试", event.getJSONObject("message").getString("text"))
        connection.close()
        thread.join(5_000)
        assertFalse(connection.isOpen)
    }

    @Test
    fun `task events write back to the bound channel`() {
        val service = newService()
        service.registerAgent("dev1", "DevA")
        val channelId = makeChannel(service)
        val started = call(service, "start_task", JSONObject().apply {
            put("agent", "dev1")
            put("goal", "写回测试任务")
            put("project_id", "proj")
            put("channel_id", channelId)
        })
        val taskId = started.getJSONObject("task").getString("task_id")
        call(
            service, "claim_task",
            JSONObject().put("task_id", taskId),
            callerId = "dev1", callerName = "DevA",
        )
        val messages = messagesOf(call(service, "agent_channel_messages", JSONObject().apply {
            put("channel_id", channelId)
        }))
        val texts = messages.map { it.getString("text") }
        assertTrue(
            "created 事件应写回绑定的频道",
            texts.any { it.contains("任务已派给 DevA") && it.contains("[task:$taskId]") },
        )
        assertTrue(
            "claimed 事件应写回绑定的频道",
            texts.any { it.contains("DevA 接手了") && it.contains("[task:$taskId]") },
        )
        assertTrue(
            "写回消息应以本机 App 身份发出",
            messages.filter { it.getString("text").contains("[task:$taskId]") }
                .all { it.getString("agent_id") == LocalPlatform.APP_AGENT_ID },
        )
    }

    @Test
    fun `pack agent from registry roles receives the delivery task`() {
        val service = LocalChannelService(InMemoryLocalChannelPersistence()) { now }
        service.registerAgent("dev1", "DevA")
        service.registerAgent("rev1", "RevA")
        service.registerAgent("pack1", "PackA", listOf("pack"))
        val channelId = makeChannel(service)
        val started = call(service, "start_task", JSONObject().apply {
            put("agent", "dev1")
            put("reviewer", "rev1")
            put("goal", "带递代码的交付")
            put("project_id", "proj")
            put("channel_id", channelId)
        })
        val taskId = started.getJSONObject("task").getString("task_id")
        call(service, "claim_task", JSONObject().put("task_id", taskId), "dev1", "DevA")
        val submitted = call(service, "submit_result", JSONObject().apply {
            put("task_id", taskId)
            put("summary", "做完了")
            put("branch", "feat/x")
        }, "dev1", "DevA")
        assertEquals("reviewing", submitted.getString("state"))
        // pack 子任务只在带 pack 角色的 Agent 的活单里。
        val packMine = call(service, "list_my_tasks", JSONObject(), "pack1", "PackA")
            .getJSONArray("tasks")
        assertEquals(1, packMine.length())
        val packId = packMine.getJSONObject(0).getString("task_id")
        assertEquals("pack", packMine.getJSONObject(0).getString("kind"))
        call(service, "claim_task", JSONObject().put("task_id", packId), "pack1", "PackA")
        val attached = call(service, "attach_delivery_diff", JSONObject().apply {
            put("task_id", packId)
            put("page", 1)
            put("total_pages", 1)
            put("text", "diff --git a/a b/a\n+++ b/a\n+hello\n")
        }, "pack1", "PackA")
        assertTrue(attached.getBoolean("complete"))
        // 递完自动开审查：rev1 的活单里出现审查子任务。
        val revMine = call(service, "list_my_tasks", JSONObject(), "rev1", "RevA")
            .getJSONArray("tasks")
        assertEquals(1, revMine.length())
        assertEquals("review", revMine.getJSONObject(0).getString("kind"))
    }

    @Test
    fun `task without a channel writes nothing back`() {
        val service = newService()
        service.registerAgent("dev1", "DevA")
        val channelId = makeChannel(service)
        call(service, "start_task", JSONObject().apply {
            put("agent", "dev1")
            put("goal", "不绑频道的任务")
            put("project_id", "proj")
        })
        val messages = messagesOf(call(service, "agent_channel_messages", JSONObject().apply {
            put("channel_id", channelId)
        }))
        assertTrue(messages.none { it.getString("text").contains("[task:") })
    }
}
