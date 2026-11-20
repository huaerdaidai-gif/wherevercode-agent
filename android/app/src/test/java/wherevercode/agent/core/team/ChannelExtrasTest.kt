package wherevercode.agent.core.team

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.core.agent.ChannelReaction
import wherevercode.agent.core.agent.ChannelReader
import wherevercode.agent.core.agent.ChannelSummary
import wherevercode.agent.core.agent.PlatformChannelMessage

class ChannelExtrasTest {

    private fun msg(
        id: String,
        text: String = "正文",
        at: Double = 0.0,
        replyTo: String? = null,
        recalled: Boolean = false,
        pinned: Boolean = false,
        reactions: List<ChannelReaction> = emptyList(),
    ) = PlatformChannelMessage(
        id = id,
        agentId = "a-$id",
        agentName = "成员$id",
        text = text,
        createdAtSeconds = at,
        replyTo = replyTo,
        recalled = recalled,
        pinned = pinned,
        reactions = reactions,
    )

    @Test
    fun `reaction text aggregates counts and skips zero`() {
        val text = ChannelExtras.reactionText(
            listOf(
                ChannelReaction("👍", 2, mine = true),
                ChannelReaction("❤️", 0),
                ChannelReaction("🎉", 1),
            ),
        )
        assertEquals("👍 2 · 🎉 1", text)
        assertEquals("", ChannelExtras.reactionText(emptyList()))
        assertTrue(ChannelExtras.myReaction(listOf(ChannelReaction("👍", 1, mine = true)), "👍"))
        assertFalse(ChannelExtras.myReaction(listOf(ChannelReaction("👍", 1)), "👍"))
    }

    @Test
    fun `pinned filter keeps order and drops recalled`() {
        val messages = listOf(
            msg("1", pinned = true),
            msg("2"),
            msg("3", pinned = true, recalled = true),
            msg("4", pinned = true),
        )
        assertEquals(listOf("1", "4"), ChannelExtras.pinnedMessages(messages).map { it.id })
    }

    @Test
    fun `recalled display text is the placeholder`() {
        assertEquals("此消息已撤回", ChannelExtras.displayText(msg("1", text = "", recalled = true)))
        assertEquals("正文", ChannelExtras.displayText(msg("2")))
    }

    @Test
    fun `reply quote finds parent excerpt and misses orphan`() {
        val messages = listOf(
            msg("p", text = "父消息正文很长很长很长很长很长很长很长很长很长很长很长"),
            msg("c", replyTo = "p"),
            msg("o", replyTo = "missing"),
        )
        val quote = ChannelExtras.replyQuote(messages, messages[1])
        assertTrue(quote!!.startsWith("回复 成员p：父消息正文"))
        assertNull(ChannelExtras.replyQuote(messages, messages[2]))
        assertNull(ChannelExtras.replyQuote(messages, messages[0]))
    }

    @Test
    fun `reply thread order nests replies under roots in time order`() {
        val messages = listOf(
            msg("r2", at = 30.0),
            msg("c1", at = 20.0, replyTo = "r1"),
            msg("r1", at = 10.0),
            msg("c2", at = 25.0, replyTo = "c1"),
            msg("orphan", at = 40.0, replyTo = "gone"),
        )
        val ordered = ChannelExtras.replyThreadOrder(messages)
        assertEquals(
            listOf("r1" to 0, "c1" to 1, "c2" to 2, "r2" to 0, "orphan" to 0),
            ordered.map { it.first.id to it.second },
        )
    }

    @Test
    fun `forward label and mentions`() {
        assertEquals("大厅 · 张三", ChannelExtras.forwardOfLabel("大厅", "张三"))
        assertEquals("频道 · 未知", ChannelExtras.forwardOfLabel("", ""))
        assertTrue(ChannelExtras.mentionsAny(msg("1", text = "请 @张三 看一下"), listOf("张三")))
        assertFalse(ChannelExtras.mentionsAny(msg("1", text = "请 @张三 看一下"), listOf("李四")))
        assertFalse(
            ChannelExtras.mentionsAny(msg("1", text = "", recalled = true), listOf("张三")),
        )
    }

    @Test
    fun `message center counts unread and unread mentions`() {
        val messages = listOf(
            msg("1", text = "早"),
            msg("2", text = "@我 看下"),
            msg("3", text = "好的"),
            msg("4", text = "@我 再看下"),
        )
        val center = ChannelExtras.buildMessageCenter(messages, listOf("我"), lastReadId = "2")
        assertEquals(2, center.unreadCount)
        assertEquals(1, center.unreadMentionCount)
        assertEquals(listOf("2", "4"), center.mentionMessages.map { it.id })
        val fresh = ChannelExtras.buildMessageCenter(messages, listOf("我"), lastReadId = null)
        assertEquals(0, fresh.unreadCount)
    }

    @Test
    fun `readers text separates read and unread`() {
        val text = ChannelExtras.readersText(
            listOf(
                ChannelReader("a1", "甲", read = true),
                ChannelReader("a2", "乙", read = false),
            ),
        )
        assertEquals("已读：甲 · 未读：乙", text)
        assertEquals("暂无成员已读信息。", ChannelExtras.readersText(emptyList()))
    }

    @Test
    fun `dm split and effect key and scheduled due`() {
        val channels = listOf(
            ChannelSummary(id = "c1", name = "大厅", kind = "channel"),
            ChannelSummary(id = "d1", name = "甲", kind = "dm"),
        )
        assertEquals(listOf("c1"), ChannelExtras.groupChannels(channels).map { it.id })
        assertEquals(listOf("d1"), ChannelExtras.dmChannels(channels).map { it.id })
        assertEquals("ck_Ab-1_2", ChannelExtras.buildEffectKey("Ab-1_2!!"))
        val items = listOf(
            ScheduledChannelPost("s2", "c1", "晚", 200.0, "ck_2"),
            ScheduledChannelPost("s1", "c1", "早", 100.0, "ck_1"),
            ScheduledChannelPost("s3", "c1", "未来", 999.0, "ck_3"),
        )
        assertEquals(
            listOf("s1", "s2"),
            ChannelExtras.dueScheduled(items, nowSeconds = 250.0).map { it.id },
        )
        assertEquals("约 10 分钟后", ChannelExtras.scheduledDelayText(600))
    }

    @Test
    fun `evidence grade labels and precedence mirror awm`() {
        assertEquals("E3 已确认生效", ChannelExtras.evidenceGradeLabel("E3"))
        assertEquals("H 人工接力", ChannelExtras.evidenceGradeLabel("H"))
        assertEquals("", ChannelExtras.evidenceGradeLabel("E9"))
        assertEquals("E3", ChannelExtras.highestEvidenceGrade(listOf("E1", "E3", "H")))
        assertEquals("H", ChannelExtras.highestEvidenceGrade(listOf("E0", "H", "E2")))
        assertEquals("E0", ChannelExtras.highestEvidenceGrade(listOf("E0")))
        assertNull(ChannelExtras.highestEvidenceGrade(emptyList()))
    }

    @Test
    fun `at all only counts when a privileged sender posts it`() {
        val coordinator = msg("1", text = "请 @所有人 看一下")
        val member = msg("2", text = "请 @all 看一下")
        assertTrue(ChannelExtras.mentionsAll(coordinator))
        assertTrue(ChannelExtras.mentionsAll(member))
        assertFalse(ChannelExtras.mentionsAll(msg("3", text = "普通消息")))
        assertFalse(ChannelExtras.mentionsAll(msg("4", text = "@所有人", recalled = true)))
        val privileged = setOf("local-app", "a-1")
        assertTrue(ChannelExtras.allMentionLive(coordinator, privileged))
        assertFalse(ChannelExtras.allMentionLive(member, privileged))
        // 生效的 @所有人计入消息中心 @我（哪怕正文没 @我的名字）。
        val center = ChannelExtras.buildMessageCenter(
            listOf(coordinator, member), myNames = listOf("我"),
            lastReadId = null, privilegedSenderIds = privileged,
        )
        assertEquals(listOf("1"), center.mentionMessages.map { it.id })
    }

    @Test
    fun `single effect key is reused across retry`() {
        val seenKeys = mutableListOf<String>()
        var calls = 0
        val result = ChannelExtras.sendWithSameEffectKey("ck_once") { key ->
            seenKeys += key
            calls++
            if (calls == 1) throw IllegalStateException("timeout") else "m1"
        }
        assertEquals("m1", result)
        assertEquals(listOf("ck_once", "ck_once"), seenKeys)
        // 全部尝试失败时抛最后一次的异常，且键始终是同一个。
        val failedKeys = mutableListOf<String>()
        try {
            ChannelExtras.sendWithSameEffectKey("ck_fail") { key ->
                failedKeys += key
                throw IllegalStateException("down")
            }
            assertTrue("应抛异常", false)
        } catch (e: IllegalStateException) {
            assertEquals("down", e.message)
        }
        assertEquals(listOf("ck_fail", "ck_fail"), failedKeys)
    }

    @Test
    fun `team message mapping carries phase2 fields`() {
        val raw = PlatformChannelMessage(
            id = "m1", agentId = "a1", agentName = "甲", text = "",
            createdAtSeconds = 5.0, replyTo = "m0", recalled = true, pinned = true,
            reactions = listOf(ChannelReaction("👍", 3)),
            attachmentRef = "ref-9", forwardOf = "大厅 · 乙",
        )
        val view = teamMessageFromChannel(raw)
        assertEquals("此消息已撤回", view.text)
        assertEquals("m0", view.replyToId)
        assertTrue(view.pinned)
        assertEquals("ref-9", view.attachmentRef)
        assertEquals("大厅 · 乙", view.forwardOf)
        assertEquals(3, view.reactions.single().count)
    }
}
