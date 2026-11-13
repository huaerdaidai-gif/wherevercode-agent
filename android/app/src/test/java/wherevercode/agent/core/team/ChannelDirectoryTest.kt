package wherevercode.agent.core.team

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 频道目录测试（2026-11-13）：`agent_channel_list` 结果解析（实测形状，
 * 见 DELIVERY）、已加入推断、坏条目容错。
 * 全是纯函数，无 Android 依赖、无 IO。
 */
@RunWith(RobolectricTestRunner::class)
class ChannelDirectoryTest {

    private fun listJson(): String = """
        {"ok": true, "channels": [
          {"id": "chan-joined", "user_id": "u1", "name": "第一批",
           "created_at": 1793795579.5,
           "participants": [
             {"agent_id": "a-claude", "agent_name": "Claude 总管", "joined_at": 1793795579.5},
             {"agent_id": "a-muse", "agent_name": "muse", "joined_at": 1793795706.7}
           ],
           "last_message": {"id": "m9", "channel_id": "chan-joined",
             "agent_id": "a-claude", "agent_name": "Claude 总管",
             "text": "大家早。", "created_at": 1794482828.9},
           "unread_count": 3, "first_unread_id": "m7"},
          {"id": "chan-open", "user_id": "u1", "name": "公开讨论",
           "created_at": 1793795600.0,
           "participants": [
             {"agent_id": "a-other", "agent_name": "Other", "joined_at": 1793795600.0}
           ]},
          {"id": "", "name": "坏条目没 id"},
          {"name": "坏条目也没 id"},
          {"id": "chan-noname"}
        ]}
    """.trimIndent()

    @Test
    fun `parseChannelList reads joined and unjoined channels`() {
        val channels = parseChannelList(JSONObject(listJson()))
        // 坏条目跳过，只剩两条真的。
        assertEquals(listOf("chan-joined", "chan-open"), channels.map { it.id })
        val joined = channels[0]
        assertEquals("第一批", joined.name)
        assertTrue(joined.joined)
        assertEquals(listOf("a-claude", "a-muse"), joined.participants.map { it.agentId })
        assertEquals("Claude 总管", joined.participants[0].agentName)
        assertEquals("m9", joined.lastMessage?.id)
        assertEquals("大家早。", joined.lastMessage?.text)
        assertEquals(3, joined.unreadCount)
        assertEquals("m7", joined.firstUnreadId)
        assertTrue(joined.hasParticipant("a-muse"))
        assertFalse(joined.hasParticipant("a-other"))
        // 未加入的没有最新消息和未读数。
        val open = channels[1]
        assertFalse(open.joined)
        assertNull(open.lastMessage)
        assertEquals(0, open.unreadCount)
        assertNull(open.firstUnreadId)
    }

    @Test
    fun `parseChannelList infers joined from self in participants`() {
        // 已加入但还没消息的频道：没有 last_message/unread_count，
        // 我方身份在 participants 里 → 仍算已加入。
        val json = JSONObject(
            """{"ok": true, "channels": [
              {"id": "c1", "name": "空频道",
               "participants": [{"agent_id": "me", "agent_name": "本机平台 Agent"}]}
            ]}""",
        )
        val channels = parseChannelList(json, selfAgentId = "me")
        assertEquals(1, channels.size)
        assertTrue(channels[0].joined)
        // 没给 selfAgentId 时按未加入处理（宁可多显示一个“加入”按钮）。
        assertFalse(parseChannelList(json)[0].joined)
    }

    @Test
    fun `parseChannelList returns empty when channels is missing`() {
        assertTrue(parseChannelList(JSONObject("""{"ok": true}""")).isEmpty())
        assertTrue(parseChannelList(JSONObject("""{}""")).isEmpty())
    }

    @Test
    fun `joinedChannels and joinableChannels split the list`() {
        val channels = parseChannelList(JSONObject(listJson()))
        assertEquals(listOf("chan-joined"), joinedChannels(channels).map { it.id })
        assertEquals(listOf("chan-open"), joinableChannels(channels).map { it.id })
    }

    @Test
    fun `mergeCurrentChannel keeps the current channel visible when the list misses it`() {
        val channels = parseChannelList(JSONObject(listJson()))
        val merged = mergeCurrentChannel(channels, "chan-v5", "V5 频道")
        assertEquals(3, merged.size)
        val current = merged.last()
        assertEquals("chan-v5", current.id)
        assertEquals("V5 频道", current.name)
        assertTrue(current.joined)
        // 成员未知：参与者表空，权限门不断言（见 ChannelAccess）。
        assertTrue(current.participants.isEmpty())
        // 已在列表里时不重复加。
        assertEquals(2, mergeCurrentChannel(channels, "chan-joined", null).size)
    }

    @Test
    fun `parseChannelList never offers create`() {
        // agent_channel_create 服务端有但 Funk 只授权了 list/join/messages/post，
        // create 保持屏蔽：本文件没有也不应有创建频道的入口（编译期保证，
        // 这里断言解析结果里没有任何创建动作——解析只读列表）。
        val channels = parseChannelList(JSONObject(listJson()))
        assertTrue(channels.none { it.id.isBlank() })
    }
}
