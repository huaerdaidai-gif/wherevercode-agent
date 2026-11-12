package wherevercode.agent.brain.siwc

import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Real SiwcBrainAdapter tests (auth + Responses + model discovery, no network).
 *
 * All doubles are TEST DOUBLES: a fake HTTP transport returns canned SSE,
 * a fake models transport returns a canned GET /v1/models directory, and
 * the auth manager runs against an in-memory token store with pre-seeded
 * tokens (no real OpenAI call, no real account). No test double is ever
 * presented as a real model call. No test asserts a hardcoded default
 * model: the old "gpt-5" fallback was removed (M3 blocker fix, 2026-11-08).
 */
class SiwcBrainAdapterTest {

    private val futureExpiry = System.currentTimeMillis() + 3_600_000L

    private fun tokens() = SiwcTokens(
        accessToken = "test-access-token",
        refreshToken = "test-refresh-token",
        idToken = null,
        clientId = "oaiapp_test",
        hostId = "urn:uuid:11111111-2222-4333-8444-555555555555",
        accessTokenExpiresAtMillis = futureExpiry,
        accountLabel = "test",
    )

    private class MemoryTokenStore(var stored: SiwcTokens? = null) : SiwcTokenStore {
        override fun saveTokens(tokens: SiwcTokens) {
            stored = tokens
        }

        override fun loadTokens(): SiwcTokens? = stored

        override fun clearTokens() {
            stored = null
        }
    }

    private class MemorySecretStore : SiwcSecretStore {
        val map = LinkedHashMap<String, String>()
        override fun put(ref: String, value: String) {
            map[ref] = value
        }

        override fun get(ref: String): String? = map[ref]
        override fun delete(ref: String) {
            map.remove(ref)
        }
    }

    private class FixedHostIdStore : HostIdStore {
        override fun getOrCreateHostId(): String =
            "urn:uuid:11111111-2222-4333-8444-555555555555"
    }

    /** HTTP transport that must never be called (tokens are pre-seeded fresh). */
    private class NoNetworkTransport : SiwcHttpTransport {
        override fun postForm(url: String, params: Map<String, String>): SiwcHttpResponse =
            throw AssertionError("unexpected HTTP call to $url")

        override fun get(url: String): SiwcHttpResponse =
            throw AssertionError("unexpected HTTP GET to $url")
    }

    private fun authManager(
        store: SiwcTokenStore,
        http: SiwcHttpTransport = NoNetworkTransport(),
    ) = SiwcAuthManager(
        tokenStore = store,
        hostIdStore = FixedHostIdStore(),
        secrets = MemorySecretStore(),
        http = http,
    )

    private class FakeResponsesConnection(
        override val statusCode: Int,
        private val body: String,
        private val errorBody: String? = null,
    ) : ResponsesConnection {
        override fun openBodyReader(): BufferedReader = BufferedReader(StringReader(body))
        override fun readErrorBody(): String? = errorBody
        override fun close() {}
    }

    private class FakeResponsesTransport(
        var statusCode: Int = 200,
        var sseBody: String = "",
        var errorBody: String? = null,
    ) : ResponsesTransport {
        var lastUrl: String? = null
        var lastAccessToken: String? = null
        var lastRequestBody: String? = null
        var calls = 0

        override fun open(url: String, accessToken: String, requestBody: String): ResponsesConnection {
            calls++
            lastUrl = url
            lastAccessToken = accessToken
            lastRequestBody = requestBody
            if (statusCode != 200) {
                return FakeResponsesConnection(statusCode, "", errorBody)
            }
            return FakeResponsesConnection(statusCode, sseBody)
        }
    }

    /** Returns the scripted (status, body, errorBody) per call; repeats the last entry. */
    private class SequencedResponsesTransport(
        private val script: List<Triple<Int, String, String?>>,
    ) : ResponsesTransport {
        var calls = 0
        val lastRequestBodies = mutableListOf<String>()

        override fun open(url: String, accessToken: String, requestBody: String): ResponsesConnection {
            val (status, body, errorBody) = script[minOf(calls, script.size - 1)]
            calls++
            lastRequestBodies += requestBody
            return FakeResponsesConnection(status, body, errorBody)
        }
    }

    /** Model-directory transport that must never be called (explicit model / auth fails first). */
    private class NoModelsTransport : SiwcModelsTransport {
        override fun get(url: String, accessToken: String): SiwcModelsResponse =
            throw AssertionError("unexpected model discovery call to $url")
    }

    /**
     * Fake GET /v1/models transport. [bodies]/[statuses] are consumed per
     * call (last entry repeats); [ioException] is thrown instead of
     * returning a response.
     */
    private class FakeModelsTransport(
        private val bodies: List<String> = listOf(catalogJson("gpt-6.1-sol")),
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

    private class MemoryModelStore(var stored: String? = null) : SiwcModelStore {
        val saves = mutableListOf<String>()
        override fun loadModel(): String? = stored
        override fun saveModel(model: String) {
            stored = model
            saves += model
        }
    }

    private fun sseText(vararg lines: String): String =
        lines.joinToString("\n") + "\n"

    private val simpleSse = sseText(
        "event: response.output_text.delta",
        "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Hello \"}",
        "",
        "event: response.output_text.delta",
        "data: {\"type\":\"response.output_text.delta\",\"delta\":\"world\"}",
        "",
        "event: response.completed",
        "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"total_tokens\":15}}}",
        "",
    )

    private fun adapter(
        store: SiwcTokenStore,
        transport: ResponsesTransport,
        http: SiwcHttpTransport = NoNetworkTransport(),
        modelsTransport: SiwcModelsTransport = NoModelsTransport(),
        modelStore: SiwcModelStore? = null,
    ): SiwcBrainAdapter = SiwcBrainAdapter(
        tokenStore = store,
        authManager = authManager(store, http),
        responsesClient = ResponsesApiClient(transport = transport),
        modelDiscovery = SiwcModelDiscovery(transport = modelsTransport),
        modelStore = modelStore,
    )

    @Test
    fun `streamInfer emits real text and completed with server usage`() = runTest {
        val store = MemoryTokenStore(tokens())
        val transport = FakeResponsesTransport(sseBody = simpleSse)
        val events = adapter(store, transport).streamInfer(
            BrainRequest(
                messages = listOf(BrainMessage(BrainRole.USER, "hi")),
                instructions = "Be brief.",
                model = "gpt-6.1-sol",
            ),
        ).toList()
        assertEquals(
            listOf(
                BrainStreamEvent.Delta("Hello "),
                BrainStreamEvent.Delta("world"),
                BrainStreamEvent.Completed(
                    BrainResponse(
                        text = "Hello world",
                        model = "gpt-6.1-sol",
                        usage = BrainUsage(10L, 5L),
                        billedToUserPlan = true,
                    ),
                ),
            ),
            events,
        )
        assertEquals("https://api.openai.com/v1/responses", transport.lastUrl)
        assertEquals("test-access-token", transport.lastAccessToken)
        val body = transport.lastRequestBody!!
        assertTrue(body.contains("\"store\":false"))
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("\"model\":\"gpt-6.1-sol\""))
        assertTrue(body.contains("Be brief."))
    }

    @Test
    fun `null model is resolved from the account directory preferring gpt-6_1-sol`() = runTest {
        val store = MemoryTokenStore(tokens())
        val transport = FakeResponsesTransport(sseBody = simpleSse)
        val models = FakeModelsTransport(
            bodies = listOf(catalogJson("gpt-6-luna", "gpt-6.1-sol", "gpt-6-terra")),
        )
        val events = adapter(store, transport, modelsTransport = models).streamInfer(
            BrainRequest(messages = listOf(BrainMessage(BrainRole.USER, "hi"))),
        ).toList()
        assertTrue(events.any { it is BrainStreamEvent.Completed })
        assertEquals(SiwcModelDiscovery.MODELS_URL, models.lastUrl)
        assertEquals("test-access-token", models.lastAccessToken)
        assertTrue(transport.lastRequestBody!!.contains("\"model\":\"gpt-6.1-sol\""))
    }

    @Test
    fun `null model without gpt-6_1-sol uses the first listed model`() = runTest {
        val store = MemoryTokenStore(tokens())
        val transport = FakeResponsesTransport(sseBody = simpleSse)
        val models = FakeModelsTransport(
            bodies = listOf(catalogJson("gpt-6-luna", "gpt-6-terra")),
        )
        adapter(store, transport, modelsTransport = models).streamInfer(
            BrainRequest(messages = listOf(BrainMessage(BrainRole.USER, "hi"))),
        ).toList()
        assertTrue(transport.lastRequestBody!!.contains("\"model\":\"gpt-6-luna\""))
    }

    @Test
    fun `null model with an empty directory fails without calling Responses`() = runTest {
        val store = MemoryTokenStore(tokens())
        val transport = FakeResponsesTransport(sseBody = simpleSse)
        val models = FakeModelsTransport(bodies = listOf("{\"models\":[]}"))
        val events = adapter(store, transport, modelsTransport = models).streamInfer(
            BrainRequest(messages = listOf(BrainMessage(BrainRole.USER, "hi"))),
        ).toList()
        val failed = events.filterIsInstance<BrainStreamEvent.Failed>().single()
        assertTrue(failed.error is BrainError.Unsupported)
        assertTrue(failed.error.message!!.contains("目录为空"))
        assertEquals(0, transport.calls)
    }

    @Test
    fun `null model when discovery returns 401 yields AuthFailed and no Responses call`() = runTest {
        val store = MemoryTokenStore(tokens())
        val transport = FakeResponsesTransport(sseBody = simpleSse)
        val models = FakeModelsTransport(bodies = listOf(""), statuses = listOf(401))
        val events = adapter(store, transport, modelsTransport = models).streamInfer(
            BrainRequest(messages = listOf(BrainMessage(BrainRole.USER, "hi"))),
        ).toList()
        val failed = events.filterIsInstance<BrainStreamEvent.Failed>().single()
        assertTrue(failed.error is BrainError.AuthFailed)
        assertEquals(0, transport.calls)
    }

    @Test
    fun `null model when discovery fails with a network error yields Transport`() = runTest {
        val store = MemoryTokenStore(tokens())
        val transport = FakeResponsesTransport(sseBody = simpleSse)
        val models = FakeModelsTransport(ioException = IOException("Network unreachable"))
        val events = adapter(store, transport, modelsTransport = models).streamInfer(
            BrainRequest(messages = listOf(BrainMessage(BrainRole.USER, "hi"))),
        ).toList()
        val failed = events.filterIsInstance<BrainStreamEvent.Failed>().single()
        assertTrue(failed.error is BrainError.Transport)
        assertEquals(0, transport.calls)
    }

    @Test
    fun `explicit model is used as-is without discovery`() = runTest {
        val store = MemoryTokenStore(tokens())
        val transport = FakeResponsesTransport(sseBody = simpleSse)
        val models = FakeModelsTransport()
        adapter(store, transport, modelsTransport = models).streamInfer(
            BrainRequest(
                messages = listOf(BrainMessage(BrainRole.USER, "hi")),
                model = "gpt-6.1-sol",
            ),
        ).toList()
        assertEquals(0, models.calls)
        assertTrue(transport.lastRequestBody!!.contains("\"model\":\"gpt-6.1-sol\""))
    }

    @Test
    fun `resolved model is persisted and reused without rediscovery`() = runTest {
        val store = MemoryTokenStore(tokens())
        val transport = FakeResponsesTransport(sseBody = simpleSse)
        val models = FakeModelsTransport(bodies = listOf(catalogJson("gpt-6.1-sol")))
        val modelStore = MemoryModelStore()
        val brain = adapter(store, transport, modelsTransport = models, modelStore = modelStore)
        val request = BrainRequest(messages = listOf(BrainMessage(BrainRole.USER, "hi")))
        brain.streamInfer(request).toList()
        assertEquals(listOf("gpt-6.1-sol"), modelStore.saves)
        brain.streamInfer(request).toList()
        assertEquals(1, models.calls)
        assertTrue(transport.lastRequestBody!!.contains("\"model\":\"gpt-6.1-sol\""))
    }

    @Test
    fun `HTTP 400 model_not_found retries once with the rediscovered model`() = runTest {
        val store = MemoryTokenStore(tokens())
        val transport = SequencedResponsesTransport(
            listOf(
                Triple(
                    400,
                    "",
                    "{\"error\":{\"message\":\"The 'gpt-6.1-sol' model is not supported when using Codex with a ChatGPT account.\",\"code\":\"model_not_found\"}}",
                ),
                Triple(200, simpleSse, null),
            ),
        )
        val models = FakeModelsTransport(
            bodies = listOf(
                catalogJson("gpt-6.1-sol"),
                catalogJson("gpt-6-luna"),
            ),
        )
        val events = adapter(store, transport, modelsTransport = models).streamInfer(
            BrainRequest(messages = listOf(BrainMessage(BrainRole.USER, "hi"))),
        ).toList()
        assertEquals(2, transport.calls)
        assertEquals(2, models.calls)
        assertTrue(transport.lastRequestBodies[0].contains("\"model\":\"gpt-6.1-sol\""))
        assertTrue(transport.lastRequestBodies[1].contains("\"model\":\"gpt-6-luna\""))
        val completed = events.filterIsInstance<BrainStreamEvent.Completed>().single()
        assertEquals("Hello world", completed.response.text)
        assertEquals("gpt-6-luna", completed.response.model)
    }

    @Test
    fun `HTTP 400 model_not_found with no other model fails with guidance and no second attempt`() = runTest {
        val store = MemoryTokenStore(tokens())
        val transport = FakeResponsesTransport(
            statusCode = 400,
            errorBody = "{\"error\":{\"message\":\"The 'gpt-6.1-sol' model is not supported.\",\"code\":\"model_not_found\"}}",
        )
        val models = FakeModelsTransport(bodies = listOf(catalogJson("gpt-6.1-sol")))
        val events = adapter(store, transport, modelsTransport = models).streamInfer(
            BrainRequest(messages = listOf(BrainMessage(BrainRole.USER, "hi"))),
        ).toList()
        val failed = events.filterIsInstance<BrainStreamEvent.Failed>().single()
        assertTrue(failed.error is BrainError.Unsupported)
        assertTrue(failed.error.message!!.contains("model_not_found"))
        assertTrue(failed.error.message!!.contains("重新登录"))
        assertEquals(1, transport.calls)
    }

    @Test
    fun `HTTP 400 with another code does not retry`() = runTest {
        val store = MemoryTokenStore(tokens())
        val transport = FakeResponsesTransport(
            statusCode = 400,
            errorBody = "{\"error\":{\"message\":\"bad request\",\"code\":\"invalid_request\"}}",
        )
        val events = adapter(store, transport).streamInfer(
            BrainRequest(
                messages = listOf(BrainMessage(BrainRole.USER, "hi")),
                model = "gpt-6.1-sol",
            ),
        ).toList()
        val failed = events.filterIsInstance<BrainStreamEvent.Failed>().single()
        assertTrue(failed.error is BrainError.Unsupported)
        assertEquals(1, transport.calls)
    }

    @Test
    fun `in-stream model_not_found surfaces as Unsupported with guidance`() = runTest {
        val store = MemoryTokenStore(tokens())
        val transport = FakeResponsesTransport(
            sseBody = sseText(
                "event: response.output_text.delta",
                "data: {\"type\":\"response.output_text.delta\",\"delta\":\"部分\"}",
                "",
                "event: response.failed",
                "data: {\"type\":\"response.failed\",\"response\":{\"status\":\"failed\",\"error\":{\"code\":\"model_not_found\",\"message\":\"The model is not supported.\"}}}",
                "",
            ),
        )
        val events = adapter(store, transport).streamInfer(
            BrainRequest(
                messages = listOf(BrainMessage(BrainRole.USER, "hi")),
                model = "gpt-6.1-sol",
            ),
        ).toList()
        assertEquals(1, transport.calls)
        assertTrue(events[0] is BrainStreamEvent.Delta)
        val failed = events[1] as BrainStreamEvent.Failed
        assertTrue(failed.error is BrainError.Unsupported)
        assertTrue(failed.error.message!!.contains("model_not_found"))
    }

    @Test
    fun `not signed in yields NotConfigured and no HTTP call`() = runTest {
        val store = MemoryTokenStore(null)
        val transport = FakeResponsesTransport(sseBody = simpleSse)
        val events = adapter(store, transport).streamInfer(
            BrainRequest(messages = listOf(BrainMessage(BrainRole.USER, "hi"))),
        ).toList()
        val failed = events.filterIsInstance<BrainStreamEvent.Failed>().single()
        assertTrue(failed.error is BrainError.NotConfigured)
        assertEquals(0, transport.calls)
    }

    @Test
    fun `HTTP 401 yields AuthFailed`() = runTest {
        val store = MemoryTokenStore(tokens())
        val transport = FakeResponsesTransport(
            statusCode = 401,
            errorBody = "{\"error\":{\"message\":\"bad token\",\"code\":\"invalid_token\"}}",
        )
        val events = adapter(store, transport).streamInfer(
            BrainRequest(
                messages = listOf(BrainMessage(BrainRole.USER, "hi")),
                model = "gpt-6.1-sol",
            ),
        ).toList()
        val failed = events.filterIsInstance<BrainStreamEvent.Failed>().single()
        assertTrue(failed.error is BrainError.AuthFailed)
    }

    @Test
    fun `HTTP 403 yields QuotaExceeded`() = runTest {
        val store = MemoryTokenStore(tokens())
        val transport = FakeResponsesTransport(statusCode = 403, errorBody = "{}")
        val events = adapter(store, transport).streamInfer(
            BrainRequest(
                messages = listOf(BrainMessage(BrainRole.USER, "hi")),
                model = "gpt-6.1-sol",
            ),
        ).toList()
        val failed = events.filterIsInstance<BrainStreamEvent.Failed>().single()
        assertTrue(failed.error is BrainError.QuotaExceeded)
    }

    @Test
    fun `HTTP 429 yields QuotaExceeded`() = runTest {
        val store = MemoryTokenStore(tokens())
        val transport = FakeResponsesTransport(statusCode = 429, errorBody = "{}")
        val events = adapter(store, transport).streamInfer(
            BrainRequest(
                messages = listOf(BrainMessage(BrainRole.USER, "hi")),
                model = "gpt-6.1-sol",
            ),
        ).toList()
        val failed = events.filterIsInstance<BrainStreamEvent.Failed>().single()
        assertTrue(failed.error is BrainError.QuotaExceeded)
    }

    @Test
    fun `tool call done surfaces as BrainStreamEvent ToolCall`() = runTest {
        val store = MemoryTokenStore(tokens())
        val transport = FakeResponsesTransport(
            sseBody = sseText(
                "event: response.output_item.added",
                "data: {\"type\":\"response.output_item.added\",\"output_index\":0,\"item_id\":\"fc_1\",\"item\":{\"type\":\"function_call\",\"call_id\":\"call-9\",\"name\":\"read_file\"}}",
                "",
                "event: response.function_call_arguments.done",
                "data: {\"type\":\"response.function_call_arguments.done\",\"item_id\":\"fc_1\",\"arguments\":\"{\\\"path\\\":\\\"README.md\\\"}\"}",
                "",
                "event: response.completed",
                "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_2\",\"usage\":{\"input_tokens\":20,\"output_tokens\":3,\"total_tokens\":23}}}",
                "",
            ),
        )
        val events = adapter(store, transport).streamInfer(
            BrainRequest(
                messages = listOf(BrainMessage(BrainRole.USER, "read it")),
                model = "gpt-6.1-sol",
            ),
        ).toList()
        val call = events.filterIsInstance<BrainStreamEvent.ToolCall>().single()
        assertEquals("call-9", call.id)
        assertEquals("read_file", call.name)
        assertTrue(call.argumentsJson.contains("README.md"))
    }

    @Test
    fun `temperature is rejected before any HTTP call`() = runTest {
        val store = MemoryTokenStore(tokens())
        val transport = FakeResponsesTransport(sseBody = simpleSse)
        try {
            adapter(store, transport).streamInfer(
                BrainRequest(
                    messages = listOf(BrainMessage(BrainRole.USER, "hi")),
                    temperature = 0.7,
                ),
            ).toList()
            fail("expected Unsupported")
        } catch (e: BrainError.Unsupported) {
            // Expected.
        }
        assertEquals(0, transport.calls)
    }

    @Test
    fun `infer collects text and returns the full response`() = runTest {
        val store = MemoryTokenStore(tokens())
        val transport = FakeResponsesTransport(sseBody = simpleSse)
        val response = adapter(store, transport).infer(
            BrainRequest(
                messages = listOf(BrainMessage(BrainRole.USER, "hi")),
                model = "gpt-6.1-sol",
            ),
        )
        assertEquals("Hello world", response.text)
        assertEquals("gpt-6.1-sol", response.model)
        assertEquals(10L, response.usage?.inputTokens)
        assertEquals(5L, response.usage?.outputTokens)
        assertTrue(response.billedToUserPlan)
    }

    @Test
    fun `revoke clears local tokens`() = runTest {
        val store = MemoryTokenStore(tokens())
        val transport = FakeResponsesTransport(sseBody = simpleSse)
        // Revoke resolves endpoints first (discovery GET fails -> falls back
        // to the verified constants), then POSTs the revocation (200 = ok).
        val http = object : SiwcHttpTransport {
            override fun postForm(url: String, params: Map<String, String>): SiwcHttpResponse {
                assertTrue(url.contains("/oauth/revoke"))
                return SiwcHttpResponse(200, "")
            }

            override fun get(url: String): SiwcHttpResponse {
                throw IOException("no network in test")
            }
        }
        val brain = adapter(store, transport, http)
        assertTrue(brain.isConfigured())
        brain.revoke()
        assertFalse(brain.isConfigured())
        assertEquals(null, store.loadTokens())
    }

    @Test
    fun `isConfigured reflects stored tokens`() {
        val store = MemoryTokenStore(tokens())
        val transport = FakeResponsesTransport()
        assertTrue(adapter(store, transport).isConfigured())
        val empty = MemoryTokenStore(null)
        assertFalse(adapter(empty, transport).isConfigured())
    }

    @Test
    fun `listModels returns the real directory slugs in server order`() = runTest {
        val store = MemoryTokenStore(tokens())
        val models = FakeModelsTransport(
            bodies = listOf(catalogJson("gpt-6-luna", "gpt-6.1-sol", "gpt-6-terra")),
        )
        val result = adapter(store, FakeResponsesTransport(), modelsTransport = models).listModels()
        assertEquals(listOf("gpt-6-luna", "gpt-6.1-sol", "gpt-6-terra"), result.getOrThrow())
        assertEquals(1, models.calls)
        assertEquals(SiwcModelDiscovery.MODELS_URL, models.lastUrl)
        assertEquals("test-access-token", models.lastAccessToken)
    }

    @Test
    fun `listModels with 401 fails as AuthFailed`() = runTest {
        val store = MemoryTokenStore(tokens())
        val models = FakeModelsTransport(bodies = listOf(""), statuses = listOf(401))
        val result = adapter(store, FakeResponsesTransport(), modelsTransport = models).listModels()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is BrainError.AuthFailed)
    }

    @Test
    fun `listModels with an empty directory fails as Unsupported`() = runTest {
        val store = MemoryTokenStore(tokens())
        val models = FakeModelsTransport(bodies = listOf("{\"models\":[]}"))
        val result = adapter(store, FakeResponsesTransport(), modelsTransport = models).listModels()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is BrainError.Unsupported)
    }

    @Test
    fun `listModels with a network failure fails as Transport`() = runTest {
        val store = MemoryTokenStore(tokens())
        val models = FakeModelsTransport(ioException = IOException("Network unreachable"))
        val result = adapter(store, FakeResponsesTransport(), modelsTransport = models).listModels()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is BrainError.Transport)
    }
}

/** Canned GET /v1/models body: every slug listed with visibility == "list". */
private fun catalogJson(vararg slugs: String): String {
    val items = slugs.joinToString(",") {
        "{\"slug\":\"$it\",\"display_name\":\"$it\",\"visibility\":\"list\"}"
    }
    return "{\"models\":[$items]}"
}
