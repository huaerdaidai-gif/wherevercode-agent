package wherevercode.agent.brain

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.core.task.TaskDatabase

/**
 * Room tests (Robolectric — the DAOs need an Android Context).
 * Run in CI (`./gradlew testDebugUnitTest`); they cannot run on a plain
 * JVM without Robolectric, so locally they only need to compile.
 *
 * Covers the V5 Phase B C1 (W2) conversation tables: DAO round-trip
 * (including ordering) + [RoomConversationStore] behaviour parity with
 * [InMemoryConversationStore] (same error contract, same limit
 * semantics, same tool-call JSON round-trip).
 */
@RunWith(RobolectricTestRunner::class)
class ConversationRoomStoreTest {
    private lateinit var db: TaskDatabase
    private lateinit var store: RoomConversationStore

    @Before
    fun setUp() {
        db = TaskDatabase.inMemory(RuntimeEnvironment.getApplication())
        store = RoomConversationStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `create get and list round-trip`() {
        val first = store.createConversation("brain-a", "闲聊")
        val second = store.createConversation(null, "无大脑会话")
        assertEquals(first, store.getConversation(first.id))
        assertEquals("闲聊", store.getConversation(first.id)!!.title)
        assertEquals("brain-a", store.getConversation(first.id)!!.brainId)
        assertNull(store.getConversation(second.id)!!.brainId)
        assertEquals(
            setOf(first.id, second.id),
            store.listConversations().map { it.id }.toSet(),
        )
        assertNull(store.getConversation("missing"))
    }

    @Test
    fun `dao list orders by createdAt descending`() {
        val dao = db.conversationDao()
        dao.insert(ConversationEntity("c1", "b", "旧", 100))
        dao.insert(ConversationEntity("c2", "b", "新", 300))
        dao.insert(ConversationEntity("c3", "b", "中", 200))
        assertEquals(listOf("c2", "c3", "c1"), dao.listAll().map { it.id })
    }

    @Test
    fun `updateConversationBrain switches brain and keeps history`() {
        val conversation = store.createConversation("brain-a", "闲聊")
        val message = Message(
            id = "m1",
            conversationId = conversation.id,
            role = "user",
            content = "你好",
            source = "user",
            createdAtEpochSeconds = 1_700_000_000L,
        )
        store.addMessage(message)
        store.updateConversationBrain(conversation.id, "brain-b")
        assertEquals("brain-b", store.getConversation(conversation.id)!!.brainId)
        assertEquals("你好", store.listMessages(conversation.id).single().content)
        // Unknown id is a no-op, matching the in-memory store.
        store.updateConversationBrain("missing", "brain-b")
    }

    @Test
    fun `messages round-trip with tool calls toolCallId and taskId`() {
        val conversation = store.createConversation("brain-a", "任务讨论")
        val assistant = Message(
            id = "m-assistant",
            conversationId = conversation.id,
            role = "assistant",
            content = "",
            source = "deepseek",
            createdAtEpochSeconds = 1_700_000_000L,
            taskId = "t1",
            toolCalls = listOf(
                ChatToolCall("call-1", "write_file", "{\"path\":\"a.txt\"}"),
            ),
        )
        val tool = Message(
            id = "m-tool",
            conversationId = conversation.id,
            role = "tool",
            content = "ok",
            source = "deepseek",
            createdAtEpochSeconds = 1_700_000_001L,
            taskId = "t1",
            toolCallId = "call-1",
        )
        store.addMessage(assistant)
        store.addMessage(tool)
        val back = store.listMessages(conversation.id)
        assertEquals(listOf("m-assistant", "m-tool"), back.map { it.id })
        assertEquals(
            listOf(ChatToolCall("call-1", "write_file", "{\"path\":\"a.txt\"}")),
            back[0].toolCalls,
        )
        assertEquals("call-1", back[1].toolCallId)
        assertEquals("t1", back[0].taskId)
        // Task binding survives the round-trip and is findable by task.
        val byTask = store.listMessagesForTask("t1")
        assertEquals(listOf("m-assistant", "m-tool"), byTask.map { it.id })
        assertTrue(store.listMessagesForTask("t2").isEmpty())
    }

    @Test
    fun `listMessages limit returns the last N in order`() {
        val conversation = store.createConversation("brain-a", "闲聊")
        repeat(5) { i ->
            store.addMessage(
                Message(
                    id = "m$i",
                    conversationId = conversation.id,
                    role = "user",
                    content = "msg $i",
                    source = "user",
                    createdAtEpochSeconds = 1_700_000_000L + i,
                ),
            )
        }
        assertEquals(
            listOf("msg 3", "msg 4"),
            store.listMessages(conversation.id, limit = 2).map { it.content },
        )
        assertEquals(5, store.listMessages(conversation.id).size)
        assertTrue(store.listMessages("missing").isEmpty())
    }

    @Test
    fun `addMessage to an unknown conversation throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            store.addMessage(
                Message(
                    id = "m1",
                    conversationId = "missing",
                    role = "user",
                    content = "你好",
                    source = "user",
                    createdAtEpochSeconds = 1_700_000_000L,
                ),
            )
        }
    }

    @Test
    fun `deleteConversation removes the conversation and its messages`() {
        val conversation = store.createConversation("brain-a", "闲聊")
        store.addMessage(
            Message(
                id = "m1",
                conversationId = conversation.id,
                role = "user",
                content = "你好",
                source = "user",
                createdAtEpochSeconds = 1_700_000_000L,
            ),
        )
        assertTrue(store.deleteConversation(conversation.id))
        assertNull(store.getConversation(conversation.id))
        assertTrue(store.listMessages(conversation.id).isEmpty())
        assertFalse(store.deleteConversation(conversation.id))
    }
}
