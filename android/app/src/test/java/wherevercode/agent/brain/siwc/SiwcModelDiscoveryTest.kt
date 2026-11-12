package wherevercode.agent.brain.siwc

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * SiwcModelDiscovery tests (no network, fake transport only).
 *
 * Covers the official discovery contract (GET /v1/models with the same
 * SIWC token, visibility == "list"), the selection preference
 * (gpt-6.1-sol first, else first listed slug), and the failure mapping.
 * No test uses a real token or a real endpoint.
 */
class SiwcModelDiscoveryTest {

    private class FakeModelsTransport(
        private val bodies: List<String> = listOf("{\"models\":[]}"),
        private val statuses: List<Int> = listOf(200),
        private val ioException: IOException? = null,
    ) : SiwcModelsTransport {
        var calls = 0
        var lastUrl: String? = null
        var lastAccessToken: String? = null

        override fun get(url: String, accessToken: String): SiwcModelsResponse {
            calls++
            lastUrl = url
            lastAccessToken = accessToken
            ioException?.let { throw it }
            val status = statuses[minOf(calls - 1, statuses.size - 1)]
            val body = bodies[minOf(calls - 1, bodies.size - 1)]
            return SiwcModelsResponse(status, body)
        }
    }

    private fun catalogJson(vararg entries: Triple<String, String, String>): String {
        val items = entries.joinToString(",") { (slug, display, visibility) ->
            "{\"slug\":\"$slug\",\"display_name\":\"$display\",\"visibility\":\"$visibility\"}"
        }
        return "{\"models\":[$items]}"
    }

    // ---------- parseCatalog ----------

    @Test
    fun `parseCatalog keeps only visibility list slugs`() {
        val catalog = SiwcModelDiscovery.parseCatalog(
            catalogJson(
                Triple("gpt-6.1-sol", "GPT-6.1 Sol", "list"),
                Triple("gpt-6-luna", "GPT-6 Luna", "list"),
                Triple("gpt-5", "GPT-5", "hidden"),
                Triple("gpt-5-codex", "GPT-5 Codex", "retired"),
            ),
        )
        assertEquals(listOf("gpt-6.1-sol", "gpt-6-luna"), catalog.models)
    }

    @Test
    fun `parseCatalog drops entries without a visibility field`() {
        val catalog = SiwcModelDiscovery.parseCatalog(
            "{\"models\":[{\"slug\":\"gpt-6.1-sol\",\"display_name\":\"Sol\"}]}",
        )
        assertTrue(catalog.models.isEmpty())
    }

    @Test
    fun `parseCatalog on empty models returns an empty catalog`() {
        val catalog = SiwcModelDiscovery.parseCatalog("{\"models\":[]}")
        assertTrue(catalog.models.isEmpty())
    }

    @Test
    fun `parseCatalog on malformed JSON throws Transport`() {
        try {
            SiwcModelDiscovery.parseCatalog("not json")
            fail("malformed JSON must throw")
        } catch (e: BrainError.Transport) {
            assertTrue(e.message!!.contains("JSON"))
        }
    }

    @Test
    fun `parseCatalog without a models field throws Transport`() {
        try {
            SiwcModelDiscovery.parseCatalog("{\"data\":[]}")
            fail("missing models field must throw")
        } catch (e: BrainError.Transport) {
            assertTrue(e.message!!.contains("models"))
        }
    }

    // ---------- selectModel ----------

    @Test
    fun `selectModel prefers gpt-6_1-sol when listed`() {
        assertEquals(
            "gpt-6.1-sol",
            SiwcModelDiscovery.selectModel(
                SiwcModelCatalog(listOf("gpt-6-luna", "gpt-6.1-sol", "gpt-6-terra")),
            ),
        )
    }

    @Test
    fun `selectModel uses the first listed slug when gpt-6_1-sol is absent`() {
        assertEquals(
            "gpt-6-luna",
            SiwcModelDiscovery.selectModel(
                SiwcModelCatalog(listOf("gpt-6-luna", "gpt-6-terra")),
            ),
        )
    }

    @Test
    fun `selectModel returns null for an empty catalog and never invents a model`() {
        assertNull(SiwcModelDiscovery.selectModel(SiwcModelCatalog(emptyList())))
    }

    // ---------- discover ----------

    @Test
    fun `discover GETs the models endpoint with the Bearer token`() = runTest {
        val transport = FakeModelsTransport(
            bodies = listOf(catalogJson(Triple("gpt-6.1-sol", "Sol", "list"))),
        )
        val discovery = SiwcModelDiscovery(transport = transport)
        val catalog = discovery.discover("test-access-token")
        assertEquals(listOf("gpt-6.1-sol"), catalog.models)
        assertEquals(SiwcModelDiscovery.MODELS_URL, transport.lastUrl)
        assertEquals("https://api.openai.com/v1/models", transport.lastUrl)
        assertEquals("test-access-token", transport.lastAccessToken)
    }

    @Test
    fun `discover caches the catalog and invalidate refetches`() = runTest {
        val transport = FakeModelsTransport(
            bodies = listOf(
                catalogJson(Triple("gpt-6.1-sol", "Sol", "list")),
                catalogJson(Triple("gpt-6-luna", "Luna", "list")),
            ),
        )
        val discovery = SiwcModelDiscovery(transport = transport)
        assertEquals(listOf("gpt-6.1-sol"), discovery.discover("token").models)
        assertEquals(listOf("gpt-6.1-sol"), discovery.discover("token").models)
        assertEquals(1, transport.calls)
        discovery.invalidate()
        assertEquals(listOf("gpt-6-luna"), discovery.discover("token").models)
        assertEquals(2, transport.calls)
    }

    @Test
    fun `discover with an empty directory throws Unsupported`() = runTest {
        val discovery = SiwcModelDiscovery(
            transport = FakeModelsTransport(bodies = listOf("{\"models\":[]}")),
        )
        try {
            discovery.discover("token")
            fail("empty directory must throw")
        } catch (e: BrainError.Unsupported) {
            assertTrue(e.message!!.contains("目录为空"))
        }
    }

    @Test
    fun `discover with a blank token throws NotConfigured before any network use`() = runTest {
        val transport = FakeModelsTransport()
        val discovery = SiwcModelDiscovery(transport = transport)
        try {
            discovery.discover("   ")
            fail("blank token must throw")
        } catch (e: BrainError.NotConfigured) {
            assertEquals(0, transport.calls)
        }
    }

    @Test
    fun `discover maps 401 to AuthFailed`() = runTest {
        val discovery = SiwcModelDiscovery(
            transport = FakeModelsTransport(bodies = listOf(""), statuses = listOf(401)),
        )
        try {
            discovery.discover("token")
            fail("401 must throw")
        } catch (e: BrainError.AuthFailed) {
            assertTrue(e.message!!.contains("401"))
        }
    }

    @Test
    fun `discover maps 403 and 429 to QuotaExceeded`() = runTest {
        for (status in listOf(403, 429)) {
            val discovery = SiwcModelDiscovery(
                transport = FakeModelsTransport(bodies = listOf(""), statuses = listOf(status)),
            )
            try {
                discovery.discover("token")
                fail("HTTP $status must throw")
            } catch (e: BrainError.QuotaExceeded) {
                assertTrue(e.message!!.contains(status.toString()))
            }
        }
    }

    @Test
    fun `discover maps 500 to Transport`() = runTest {
        val discovery = SiwcModelDiscovery(
            transport = FakeModelsTransport(bodies = listOf(""), statuses = listOf(500)),
        )
        try {
            discovery.discover("token")
            fail("500 must throw")
        } catch (e: BrainError.Transport) {
            assertTrue(e.message!!.contains("500"))
        }
    }

    @Test
    fun `discover maps a network failure to Transport`() = runTest {
        val discovery = SiwcModelDiscovery(
            transport = FakeModelsTransport(ioException = IOException("Network unreachable")),
        )
        try {
            discovery.discover("token")
            fail("network failure must throw")
        } catch (e: BrainError.Transport) {
            assertTrue(e.message!!.contains("Network unreachable"))
        }
    }
}
