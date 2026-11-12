package wherevercode.agent.ui

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 聊天历史恢复（阶段 3，U1）：快照编解码、SharedPreferences 往返、
 * 坏快照容错、50 轮上限、默认会话恢复且不重复恢复。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ChatHistoryRestoreTest {

    private fun store(): ChatHistoryStore {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(
            ChatHistoryStore.PREFS_NAME,
            Context.MODE_PRIVATE,
        ).edit().clear().commit()
        return ChatHistoryStore(context)
    }

    private fun turn(id: String, user: String, assistant: String) = ChatTurnUi(
        id = id,
        userText = user,
        assistantText = assistant,
        reasoning = "",
        protocolRedacted = false,
        toolCalls = emptyList(),
        notes = emptyList(),
        status = TurnStatus.DONE,
        error = null,
        revision = 1,
    )

    @Test
    fun `codec round trip preserves text status and error`() {
        val turns = listOf(
            turn("turn-1", "你好", "你好呀"),
            turn("turn-2", "再问", "").copy(status = TurnStatus.ERROR, error = "deepseek_http_500：服务不可用"),
        )
        val decoded = ChatHistorySnapshot.decode(ChatHistorySnapshot.encode(turns))

        assertEquals(2, decoded.size)
        assertEquals("你好", decoded[0].userText)
        assertEquals("你好呀", decoded[0].assistantText)
        assertEquals("DONE", decoded[0].status)
        assertEquals("ERROR", decoded[1].status)
        assertEquals("deepseek_http_500：服务不可用", decoded[1].error)
    }

    @Test
    fun `bad snapshot decodes to empty instead of crashing`() {
        assertTrue(ChatHistorySnapshot.decode("not json at all").isEmpty())
        assertTrue(ChatHistorySnapshot.decode("").isEmpty())
    }

    @Test
    fun `store round trip through SharedPreferences`() {
        val store = store()
        store.save(listOf(turn("turn-1", "问题", "答案")))

        val loaded = store.load()

        assertEquals(1, loaded.size)
        assertEquals("问题", loaded.single().userText)
        assertEquals("答案", loaded.single().assistantText)
    }

    @Test
    fun `store keeps only the last 50 turns`() {
        val store = store()
        val turns = (1..60).map { turn("turn-$it", "问$it", "答$it") }
        store.save(turns)

        val loaded = store.load()

        assertEquals(50, loaded.size)
        assertEquals("问11", loaded.first().userText)
        assertEquals("问60", loaded.last().userText)
    }

    @Test
    fun `clear removes the snapshot`() {
        val store = store()
        store.save(listOf(turn("turn-1", "问", "答")))
        store.clear()
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `viewModel restores default turns once and never overwrites existing history`() {
        val viewModel = ChatViewModel()
        assertTrue(viewModel.isDefaultSession())
        viewModel.restoreDefaultTurns(
            listOf(SavedTurn("你好", "你好呀", "DONE", null)),
        )

        val state = viewModel.stateNow()
        assertEquals(1, state.turns.size)
        assertEquals("你好", state.turns.single().userText)
        assertEquals("你好呀", state.turns.single().assistantText)
        assertEquals(TurnStatus.DONE, state.turns.single().status)

        // 已有历史时再次恢复不追加、不覆盖。
        viewModel.restoreDefaultTurns(
            listOf(SavedTurn("后来", "后来呀", "DONE", null)),
        )
        assertEquals(1, viewModel.stateNow().turns.size)
        assertEquals("你好", viewModel.stateNow().turns.single().userText)
    }
}
