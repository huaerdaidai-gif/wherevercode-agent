package wherevercode.agent.core.agent

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PlatformConnectionStoreTest {
    private fun store(): PlatformConnectionStore {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(
            PlatformConnectionStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        ).edit().clear().commit()
        return PlatformConnectionStore(context)
    }

    @Test
    fun `app address wins then first platform agent and build default`() {
        val store = store()
        assertEquals("build.example", store.platformHost(null, "build.example"))
        assertEquals("agent.example", store.platformHost(" agent.example ", "build.example"))

        store.savePlatformHost(" app.example ")
        assertEquals("app.example", store.platformHost("agent.example", "build.example"))
    }

    @Test
    fun `address parser accepts HTTPS origins and rejects paths and credentials`() {
        assertEquals("v5.example", PlatformConnectionStore.hostFromAddress("https://v5.example/"))
        assertEquals("v5.example:8443", PlatformConnectionStore.hostFromAddress("v5.example:8443"))
        assertNull(PlatformConnectionStore.hostFromAddress("https://user@v5.example"))
        assertNull(PlatformConnectionStore.hostFromAddress("https://v5.example/api"))
        assertNull(PlatformConnectionStore.hostFromAddress("http://v5.example"))
    }
}
