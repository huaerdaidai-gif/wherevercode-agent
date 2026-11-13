package wherevercode.agent.core.team

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 频道成员本地屏蔽测试（2026-11-13）：屏蔽只影响本机显示，不影响频道、
 * 不通知对方；按 id 与显示名（不分大小写）双通道匹配；按频道分键；
 * 本机（local-app）永不屏蔽。
 */
@RunWith(RobolectricTestRunner::class)
class ChannelMuteStoreTest {

    private class MapKeyValueStore : ChannelKeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) {
            map[key] = value
        }
    }

    @Test
    fun `mute and unmute by id and name`() {
        val store = ChannelMuteStore(MapKeyValueStore())
        assertFalse(store.isMuted("chan-1", "codex", "Codex"))
        store.setMuted("chan-1", "codex", "Codex", true)
        assertTrue(store.isMuted("chan-1", "codex", "Codex"))
        // 显示名不分大小写也命中（中转回流按归属头里的发送者名匹配）。
        assertTrue(store.isMuted("chan-1", "some-other-id", "codex"))
        assertTrue(store.isMuted("chan-1", "some-other-id", "  CODEX  "))
        assertEquals(setOf("codex"), store.mutedAgentIds("chan-1"))
        assertEquals(setOf("Codex"), store.mutedNames("chan-1"))
        store.setMuted("chan-1", "codex", "Codex", false)
        assertFalse(store.isMuted("chan-1", "codex", "Codex"))
        assertTrue(store.mutedAgentIds("chan-1").isEmpty())
    }

    @Test
    fun `local-app is never muted`() {
        val store = ChannelMuteStore(MapKeyValueStore())
        store.setMuted("chan-1", "local-app", "本机 App", true)
        assertFalse(store.isMuted("chan-1", "local-app", "本机 App"))
    }

    @Test
    fun `mutes are isolated per channel`() {
        val store = ChannelMuteStore(MapKeyValueStore())
        store.setMuted("chan-1", "codex", "Codex", true)
        assertTrue(store.isMuted("chan-1", "codex", "Codex"))
        assertFalse(store.isMuted("chan-2", "codex", "Codex"))
        assertTrue(store.mutedAgentIds("chan-2").isEmpty())
    }

    @Test
    fun `blank ids and names are ignored`() {
        val store = ChannelMuteStore(MapKeyValueStore())
        store.setMuted("chan-1", "", "", true)
        assertTrue(store.mutedAgentIds("chan-1").isEmpty())
        assertFalse(store.isMuted("chan-1", "", ""))
        assertFalse(store.isMuted("chan-1", "codex", ""))
    }
}
