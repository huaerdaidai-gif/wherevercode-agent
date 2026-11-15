package wherevercode.agent.brain

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PersistentMemoryStoreTest {
    private lateinit var prefs: SharedPreferences
    @Before fun setup() {
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences("memory-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }
    private fun fact(value: String = "使用中文") = MemoryEntry("fact-id", MemoryKind.FACT, "偏好", value, "user:android", 123L, false)
    @Test fun `reopening preserves exact provenance and all memory kinds`() {
        val store = PersistentMemoryStore(prefs)
        assertTrue(store.addFact(fact(), true))
        assertTrue(store.addTaskSummary("task-one", "实际结果", true))
        assertTrue(store.addDeviceAuthorization("device-one", "设备备注"))
        val reopened = PersistentMemoryStore(prefs)
        assertEquals(fact().copy(confirmedByUser = true), reopened.get(MemoryKind.FACT, "偏好"))
        for (kind in MemoryKind.values()) assertEquals(store.list(kind), reopened.list(kind))
        assertTrue(reopened.remove(MemoryKind.FACT, "偏好"))
        assertNull(PersistentMemoryStore(prefs).get(MemoryKind.FACT, "偏好"))
    }
    @Test fun `unconfirmed secrets and unsettled summaries do not touch disk`() {
        val store = PersistentMemoryStore(prefs)
        assertFalse(store.addFact(fact(), false))
        assertFalse(store.addFact(fact("api_key=sk-abcdefghijklmnopqrstuvwxyz1234567890"), true))
        assertFalse(store.addFact(fact().copy(kind = MemoryKind.DEVICE_AUTHORIZATION), true))
        assertFalse(store.addTaskSummary("task-one", "尚未结束", false))
        assertFalse(prefs.contains("entries_v1"))
    }
    @Test fun `corrupt persisted data is never silently overwritten`() {
        prefs.edit().putString("entries_v1", "broken").commit()
        val store = PersistentMemoryStore(prefs)
        assertThrows(Exception::class.java) { store.list(MemoryKind.FACT) }
        assertThrows(Exception::class.java) { store.addFact(fact(), true) }
        assertEquals("broken", prefs.getString("entries_v1", null))
    }
    @Test fun `failed commit does not change published memory`() {
        var failWrites = false
        val wrapped = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val editor = prefs.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor { editor.putString(key, value); return this }
                    override fun commit(): Boolean = if (failWrites) false else editor.commit()
                }
            }
        }
        val store = PersistentMemoryStore(wrapped)
        assertTrue(store.addFact(fact(), true))
        failWrites = true
        assertThrows(IllegalStateException::class.java) { store.addFact(fact("新值"), true) }
        assertEquals("使用中文", store.get(MemoryKind.FACT, "偏好")!!.value)
        assertEquals("使用中文", PersistentMemoryStore(prefs).get(MemoryKind.FACT, "偏好")!!.value)
    }
}
