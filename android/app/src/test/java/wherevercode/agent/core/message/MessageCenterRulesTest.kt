package wherevercode.agent.core.message

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.core.agent.PlatformChannelMessage
import wherevercode.agent.core.agent.PlatformChannelTransport

/**
 * 消息中心聚合规则 + 读取管道测试。
 *
 * 已读语义锁定（P3-MSG 工作包要求）：消息中心只用既有读接口读，且
 * 绝不发起 mark_read=true 的调用——only_unread 与服务端已读标记联动
 * （读到哪标到哪），mark_read=false 的拉取不推进服务端已读标记，
 * 频道页的未读状态不会被消息中心误消费。FakeTransport 逐调用记录
 * 参数，测试断言全部调用 markRead=false、未读区只来自 onlyUnread=true
 * 的那一页。
 */
private data class RecordedPoll(
    val channelId: String,
    val afterId: String?,
    val limit: Int,
    val onlyUnread: Boolean,
    val markRead: Boolean,
)

private class FakeTransport(
    private val pages: Map<String, Map<Boolean, List<PlatformChannelMessage>>>,
) : PlatformChannelTransport {
    val polls = mutableListOf<RecordedPoll>()

    override suspend fun postMessage(channelId: String, text: String): String {
        throw UnsupportedOperationException("消息中心绝不允许发送。")
    }

    override suspend fun pollMessages(
        channelId: String,
        afterId: String?,
        limit: Int,
        onlyUnread: Boolean,
        markRead: Boolean,
    ): List<PlatformChannelMessage> {
        polls += RecordedPoll(channelId, afterId, limit, onlyUnread, markRead)
        return pages[channelId]?.get(onlyUnread) ?: emptyList()
    }
}

private fun message(
    id: String,
    agentName: String = "协调者",
    text: String,
    at: Double,
) = PlatformChannelMessage(
    id = id,
    agentId = "local-app",
    agentName = agentName,
    text = text,
    createdAtSeconds = at,
)

class MessageCenterRulesTest {
    private val selfNames = listOf("本机 App", "协调者", "Codex")

    // ---- @我 判定 ----

    @Test
    fun `mention matches self alias prefix and embedded with boundaries`() {
        // 平台寻址惯例：正文前缀 @<Name>。
        assertEquals("协调者", MessageCenterRules.mentionAlias("@协调者 你看一下", selfNames))
        // 正文中段提及也算（转发/引述场景），只要不是词内延续。
        assertEquals("Codex", MessageCenterRules.mentionAlias("让 @Codex 看看", selfNames))
        // 最长别名优先：@本机 App 命中“本机 App”而不是“本机”。
        assertEquals("本机 App", MessageCenterRules.mentionAlias("@本机 App 收一下", selfNames))
    }

    @Test
    fun `mention does not match word continuations or other members`() {
        // “@我们”不是“@我”的词内延续（拉丁词边界规则同样防“@我们们”这类误报）。
        assertNull(MessageCenterRules.mentionAlias("@我们试试", listOf("我")))
        // 他人被 @ 不算 @我。
        assertNull(MessageCenterRules.mentionAlias("@GeminiCli 帮忙", selfNames))
        // 邮箱/词内 @ 不算提及。
        assertNull(MessageCenterRules.mentionAlias("abc@coord1ner 后缀相连", listOf("协调者")))
        // 无 @ 不算。
        assertNull(MessageCenterRules.mentionAlias("讨论里提到我", selfNames))
    }

    // ---- 任务回执判定（复用基线归属头解析，不另造协议） ----

    @Test
    fun `task receipt recognized from team attribution header`() {
        val receipt = MessageCenterRules.taskAttribution(
            "【协调者 · 任务 a1b2c3d4 · 修复登录】任务已完成，构建绿。",
        )
        assertNotNull(receipt)
        assertEquals("协调者", receipt!!.senderName)
        assertEquals("a1b2c3d4", receipt.taskId)
        assertEquals("修复登录", receipt.taskTitle)
        // 无任务的头不是回执。
        assertNull(MessageCenterRules.taskAttribution("【协调者】纯讨论一句话。"))
        assertNull(MessageCenterRules.taskAttribution("没有头的普通消息"))
    }

    // ---- 聚合 ----

    @Test
    fun `buildEntries classifies recent page into mentions and receipts and unread page into unread`() {
        val recent = listOf(
            message("m1", text = "@本机 App 帮我看看构建", at = 10.0),
            message("m2", text = "【协调者 · 任务 a1b2c3d4 · 修复登录】已修复", at = 12.0),
            message("m3", text = "普通讨论", at = 11.0),
            // 一条既是 @我 又是任务回执：归属头在前、提及在正文（真实中转
            // 回复的形态——头总在正文最前）。
            message("m4", text = "【协调者 · 任务 e5f6a7b8 · 重构】@Codex 开始重构", at = 13.0),
        )
        val unread = listOf(
            message("u1", text = "@本机 App 新消息", at = 20.0),
            message("m1", text = "@本机 App 帮我看看构建", at = 10.0), // 未读页与最新页可能重叠，按 id 去重
        )
        val snapshot = MessageCenterRules.buildEntries("ch1", "构建频道", recent, unread, selfNames)
        assertEquals(listOf("m4", "m1"), snapshot.mentions.map { it.messageId })
        assertEquals("本机 App", snapshot.mentions.last().matchedAlias)
        assertEquals(listOf("m4", "m2"), snapshot.taskReceipts.map { it.messageId })
        assertEquals("a1b2c3d4", snapshot.taskReceipts.last().taskId)
        assertEquals(listOf("u1", "m1"), snapshot.unread.map { it.messageId })
        // 排序：新→旧。
        assertTrue(snapshot.mentions.first().createdAtSeconds >= snapshot.mentions.last().createdAtSeconds)
        // 展示正文剥归属头。
        assertTrue(snapshot.taskReceipts.last().text.startsWith("已修复"))
    }

    @Test
    fun `merge dedupes by section and message id and caps each section`() {
        val entry = { id: String ->
            MessageCenterEntry(
                section = MessageCenterSection.UNREAD,
                channelId = "ch",
                channelName = "c",
                messageId = id,
                senderName = "s",
                text = "t",
                createdAtSeconds = id.toDouble(),
            )
        }
        val merged = MessageCenterRules.merge(
            listOf(
                MessageCenterSnapshot(unread = (1..25).map { entry(it.toString()) }),
                MessageCenterSnapshot(unread = listOf(entry("24"), entry("99"))),
            ),
        )
        assertEquals(20, merged.unread.size)
        assertEquals(99.0, merged.unread.first().createdAtSeconds, 0.0)
        // "24" 同时出现在两份输入里，输出只该有一条（去重）且留在前 20。
        assertEquals(1, merged.unread.count { it.messageId == "24" })
    }

    // ---- 已读语义（桩传输锁定） ----

    @Test
    fun `loader only reads never marks read and unread section comes only from only_unread pages`() = runTest {
        val fake = FakeTransport(
            mapOf(
                "ch1" to mapOf(
                    false to listOf(
                        message("r1", text = "@协调者 在吗", at = 5.0),
                        message("r2", text = "【协调者 · 任务 a1b2c3d4 · 派发】已收到", at = 6.0),
                    ),
                    true to listOf(message("u1", text = "只读回执不推已读", at = 7.0)),
                ),
            ),
        )
        val snapshot = MessageCenterLoader(fake).fetch(
            listOf(MessageCenterChannelSource("ch1", "测试频道")),
            selfNames,
        )
        // 逐频道两页：普通读 + only_unread 读。
        assertEquals(2, fake.polls.size)
        assertTrue(fake.polls.all { it.channelId == "ch1" })
        // 核心语义：绝不 mark_read=true；已读标记只属于频道页自己。
        assertTrue(fake.polls.none { it.markRead })
        assertEquals(setOf(false, true), fake.polls.map { it.onlyUnread }.toSet())
        assertEquals(20, fake.polls.first().limit)
        assertNull(fake.polls.first().afterId)
        // 三区各就各位。
        assertEquals(listOf("r1"), snapshot.mentions.map { it.messageId })
        assertEquals(listOf("r2"), snapshot.taskReceipts.map { it.messageId })
        assertEquals(listOf("u1"), snapshot.unread.map { it.messageId })
    }

    @Test
    fun `loader iterates every channel and never posts`() = runTest {
        val fake = FakeTransport(
            mapOf(
                "ch1" to mapOf(false to emptyList(), true to emptyList()),
                "ch2" to mapOf(false to emptyList(), true to emptyList()),
            ),
        )
        val snapshot = MessageCenterLoader(fake).fetch(
            listOf(
                MessageCenterChannelSource("ch1", "频道一"),
                MessageCenterChannelSource("ch2", "频道二"),
            ),
            selfNames,
        )
        assertEquals(
            listOf("ch1", "ch1", "ch2", "ch2"),
            fake.polls.map { it.channelId },
        )
        assertEquals(MessageCenterSnapshot(), snapshot)
    }
}
