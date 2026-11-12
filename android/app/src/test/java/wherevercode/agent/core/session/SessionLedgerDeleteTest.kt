package wherevercode.agent.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 失败测试（maintainer 2026-11-10 07:46 “无法删除”）：会话删除必须删干净——
 * 会话行、消息行、投递行一起消失；删不存在的会话返回 false；重复删返回 false。
 * 全部假 DAO（LedgerHarness），零真实调用。
 */
class SessionLedgerDeleteTest {

    @Test
    fun `deleteSession removes the session its messages and its deliveries`() {
        val h = LedgerHarness()
        val session = h.privateSession()
        val first = h.ledger.record(
            IncomingMessage(
                conversationId = session.sessionId,
                senderId = "user",
                content = "做一个计算器小程序",
            ),
        )
        val second = h.ledger.record(
            IncomingMessage(
                conversationId = session.sessionId,
                senderId = "user",
                content = "再加一个开方功能",
            ),
        )
        assertTrue(first.ok && second.ok)

        assertTrue(h.ledger.deleteSession(session.sessionId))

        assertNull(h.ledger.getSession(session.sessionId))
        assertTrue(h.ledger.listMessages(session.sessionId).isEmpty())
        assertTrue(h.ledger.listSessions("user").none { it.sessionId == session.sessionId })
        assertTrue(h.deliveryDao.listByMessage(first.message!!.messageId).isEmpty())
        assertTrue(h.deliveryDao.listByMessage(second.message!!.messageId).isEmpty())
        assertEquals(0, h.messageDao.countByConversation(session.sessionId))
    }

    @Test
    fun `deleteSession on an unknown session returns false and deletes nothing`() {
        val h = LedgerHarness()
        val session = h.privateSession()
        h.ledger.record(
            IncomingMessage(
                conversationId = session.sessionId,
                senderId = "user",
                content = "做一个计算器小程序",
            ),
        )

        assertFalse(h.ledger.deleteSession("no-such-session"))
        assertEquals(1, h.ledger.listSessions("user").size)

        // 重复删：第二次返回 false。
        assertTrue(h.ledger.deleteSession(session.sessionId))
        assertFalse(h.ledger.deleteSession(session.sessionId))
    }
}
