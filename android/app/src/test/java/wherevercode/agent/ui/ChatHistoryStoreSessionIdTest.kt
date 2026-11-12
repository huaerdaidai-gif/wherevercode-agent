package wherevercode.agent.ui

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 失败测试：默认会话 id 持久化（重启后默认对话能从 Room 恢复，而不是只靠快照）。
 *
 * `ChatHistoryStore` 除 50 轮快照外，还须记住当前默认会话的 conversationId，
 * 否则重启后不知道哪一行才是默认对话。Robolectric，CI 跑；零真实调用。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ChatHistoryStoreSessionIdTest {

    private fun store(): ChatHistoryStore {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(
            ChatHistoryStore.PREFS_NAME,
            Context.MODE_PRIVATE,
        ).edit().clear().commit()
        return ChatHistoryStore(context)
    }

    private fun reopenedStore(): ChatHistoryStore =
        ChatHistoryStore(RuntimeEnvironment.getApplication())

    @Test
    fun `current session id round-trips and clears`() {
        val s = store()
        assertNull(s.loadCurrentSessionId())
        s.saveCurrentSessionId("session-1")
        assertEquals("session-1", s.loadCurrentSessionId())
        // A fresh store instance on the same prefs sees the saved id.
        assertEquals("session-1", reopenedStore().loadCurrentSessionId())
        s.clearCurrentSessionId()
        assertNull(s.loadCurrentSessionId())
    }
}
