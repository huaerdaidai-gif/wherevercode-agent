package wherevercode.agent.core.team

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import wherevercode.agent.core.agent.PlatformChannelMessage

/**
 * 频道成员权限门测试（2026-11-13）。
 * 服务端是最终裁决（未加入读不到、发不出）；这里是 App 侧同一规则的
 * 前置检查。名单未知时不断言（fail-open），名单已知且不在 → 拦
 * （fail-closed）。全是纯函数，无 IO。
 */
@RunWith(RobolectricTestRunner::class)
class ChannelAccessTest {

    private fun message(id: String, agentId: String) = PlatformChannelMessage(
        id = id,
        agentId = agentId,
        agentName = agentId,
        text = "在吗",
        createdAtSeconds = 100.0,
    )

    @Test
    fun `canRead and canPost require joined`() {
        assertTrue(ChannelAccess.canRead(true))
        assertTrue(ChannelAccess.canPost(true))
        assertFalse(ChannelAccess.canRead(false))
        assertFalse(ChannelAccess.canPost(false))
        assertNull(ChannelAccess.postBlockedReason(true))
        assertTrue(ChannelAccess.postBlockedReason(false)!!.contains("还没加入"))
    }

    @Test
    fun `isNonMember only asserts when the roster is known`() {
        val roster = setOf("a-claude", "a-muse")
        assertFalse(ChannelAccess.isNonMember("a-muse", roster))
        assertTrue(ChannelAccess.isNonMember("outsider", roster))
        // 名单未知（null 或空）不断言：宁可放行一条真消息，也不误拦。
        assertFalse(ChannelAccess.isNonMember("outsider", null))
        assertFalse(ChannelAccess.isNonMember("outsider", emptySet()))
        // 发送者身份不明也不拦。
        assertFalse(ChannelAccess.isNonMember("", roster))
    }

    @Test
    fun `filterReadable drops non-member messages only when the roster is known`() {
        val messages = listOf(
            message("m1", "a-muse"),
            message("m2", "outsider"),
            message("m3", ""),
        )
        val roster = setOf("a-muse")
        assertEquals(
            listOf("m1", "m3"),
            ChannelAccess.filterReadable(messages, roster).map { it.id },
        )
        assertEquals(messages, ChannelAccess.filterReadable(messages, null))
        assertEquals(messages, ChannelAccess.filterReadable(messages, emptySet()))
    }

    @Test
    fun `relayTargetAllowed opens when the roster is unknown`() {
        assertTrue(ChannelAccess.relayTargetAllowed("codex", null))
        assertTrue(ChannelAccess.relayTargetAllowed("codex", emptySet()))
        assertTrue(ChannelAccess.relayTargetAllowed("codex", setOf("codex")))
        assertFalse(ChannelAccess.relayTargetAllowed("codex", setOf("other")))
    }
}
