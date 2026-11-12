package wherevercode.agent.brain.oauth

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal HTTP port for the OAuth endpoints, so tests can substitute a fake.
 * This is the generic transport the [OAuthEngine] uses for every vendor;
 * it was extracted verbatim from the SIWC flow (where it was
 * `SiwcHttpTransport`). The old names remain available as typealiases in
 * `wherevercode.agent.brain.siwc` so existing callers and tests keep
 * compiling unchanged.
 */
interface OAuthHttpTransport {
    /** POST an `application/x-www-form-urlencoded` body. Never put tokens in a URL. */
    fun postForm(url: String, params: Map<String, String>): OAuthHttpResponse

    /**
     * POST with extra request headers (2026-11-12, device-code engine:
     * Kimi requires `X-Msh-*` device headers on its OAuth calls).
     * Default implementation drops the headers so existing
     * implementations and test fakes keep compiling unchanged.
     */
    fun postForm(
        url: String,
        params: Map<String, String>,
        headers: Map<String, String>,
    ): OAuthHttpResponse = postForm(url, params)

    /** GET. Used for the OIDC discovery document and the JWKS. */
    fun get(url: String): OAuthHttpResponse
}

/** One HTTP response: status code plus the full body as text. */
data class OAuthHttpResponse(val status: Int, val body: String)

/**
 * [OAuthHttpTransport] over [HttpURLConnection]. No new dependency.
 * Only https URLs are accepted — OAuth tokens and codes never travel over
 * plain http (the loopback callback is the sole http exception, and it
 * carries only the code/state to 127.0.0.1, never a token).
 *
 * The User-Agent string is unchanged from the SIWC flow.
 */
class UrlConnectionOAuthHttpTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : OAuthHttpTransport {

    override fun postForm(url: String, params: Map<String, String>): OAuthHttpResponse =
        postForm(url, params, emptyMap())

    override fun postForm(
        url: String,
        params: Map<String, String>,
        headers: Map<String, String>,
    ): OAuthHttpResponse {
        requireHttps(url)
        val body = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        val connection = open(url)
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty(
                "Content-Type",
                "application/x-www-form-urlencoded",
            )
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty(
                "User-Agent",
                "WhereverCode-Android/0.1.0-spike (SIWC)",
            )
            for ((name, value) in headers) {
                connection.setRequestProperty(name, value)
            }
            connection.outputStream.use { out ->
                out.write(body.toByteArray(Charsets.UTF_8))
            }
            return readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    override fun get(url: String): OAuthHttpResponse {
        requireHttps(url)
        val connection = open(url)
        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty(
                "User-Agent",
                "WhereverCode-Android/0.1.0-spike (SIWC)",
            )
            return readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.instanceFollowRedirects = false
        return connection
    }

    private fun readResponse(connection: HttpURLConnection): OAuthHttpResponse {
        val status = connection.responseCode
        val stream = if (status < HttpURLConnection.HTTP_BAD_REQUEST) {
            connection.inputStream
        } else {
            connection.errorStream
        }
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        return OAuthHttpResponse(status, body)
    }

    private fun requireHttps(url: String) {
        // Message kept verbatim from the SIWC flow (not user-facing; only a
        // programmer error can trigger it).
        require(url.startsWith("https://")) { "SIWC 只允许 https 端点：$url" }
    }
}
