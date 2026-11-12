package wherevercode.agent.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationStoreTest {

    @Test
    fun `Message without taskId stays compatible - taskId defaults to null`() {
        val store = InMemoryConversationStore()
        val conversation = store.createConversation("fake-brain", "闲聊")
        val message = Message(
            id = "msg-1",
            conversationId = conversation.id,
            role = "user",
            content = "你好",
            source = "user",
            createdAtEpochSeconds = 1_700_000_000L,
        )
        assertNull(message.taskId)
        store.addMessage(message)
        val back = store.listMessages(conversation.id).single()
        assertEquals("你好", back.content)
        assertNull(back.taskId)
        assertTrue(store.listMessagesForTask("t1").isEmpty())
    }

    @Test
    fun `binding a taskId afterwards keeps the text and makes the message findable by task`() {
        val store = InMemoryConversationStore()
        val conversation = store.createConversation("fake-brain", "任务讨论")
        val message = Message(
            id = "msg-2",
            conversationId = conversation.id,
            role = "user",
            content = "这个任务先暂停一下",
            source = "user",
            createdAtEpochSeconds = 1_700_000_000L,
            taskId = "t1",
        )
        store.addMessage(message)
        val found = store.listMessagesForTask("t1")
        assertEquals(1, found.size)
        assertEquals("这个任务先暂停一下", found.single().content)
        assertEquals("msg-2", found.single().id)
        assertTrue(store.listMessagesForTask("t2").isEmpty())
    }
}
