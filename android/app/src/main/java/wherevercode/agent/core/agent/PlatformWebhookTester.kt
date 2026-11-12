package wherevercode.agent.core.agent

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * “发一条测试通知”的结果：如实报回包状态，通就是通、不通就报
 * 具体原因，绝不伪装成功。
 */
sealed class WebhookTestResult {
    /** webhook 端点回了 2xx：唤醒通路是活的（只证明端点可达）。 */
    data class Ok(val httpStatus: Int, val bodyPreview: String) : WebhookTestResult()

    /** 端点回了非 2xx：带回真实状态码与少量回包（已截断，不含密钥）。 */
    data class HttpError(val httpStatus: Int, val bodyPreview: String) : WebhookTestResult()

    /** 发不出去（DNS/连接/超时/地址非法等）：带具体原因。 */
    data class NetworkError(val message: String) : WebhookTestResult()
}

/** 测试通知的原始 HTTP POST（可注入，单测用假实现）。 */
interface WebhookHttpClient {
    /** 返回 `(HTTP 状态码, 响应体)`；发不出去时抛 [IOException]。 */
    @Throws(IOException::class)
    fun postJson(url: String, headers: Map<String, String>, bodyJson: String): Pair<Int, String>
}

/** [WebhookHttpClient] 的 [HttpURLConnection] 实现（不引入新依赖）。 */
class UrlConnectionWebhookHttpClient(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : WebhookHttpClient {
    override fun postJson(
        url: String,
        headers: Map<String, String>,
        bodyJson: String,
    ): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "WhereverCode-Android/1.0 (PlatformAgent-Test)")
            for ((key, value) in headers) connection.setRequestProperty(key, value)
            connection.outputStream.use { it.write(bodyJson.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
            return status to body
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * 平台 Agent 的“发一条测试通知”（V5，2026-11-11）。
 *
 * 往表单里填的 webhook 地址发一条测试通知，验证唤醒通路是活的：
 * - 请求体是明确标明测试的 JSON（`event: "wherevercode_test"`），
 *   接收方应把它当测试忽略、不要当作真实任务唤醒；
 * - 填了签名密钥时附 `X-WhereverCode-Signature: sha256=<hex>`
 *  （HMAC-SHA256，密钥只以参数传入、不记日志）；
 *   没填密钥时不带签名头——是否验签由接收方定，App 不假装签过。
 * - 返回值如实报 HTTP 状态：2xx 才是通；非 2xx 与发不出去都按
 *   原样报原因。
 *
 * 诚实边界：2xx 只证明 webhook 端点可达、能收通知，不证明平台
 * 真会用它唤醒该 Agent（唤醒配置在平台上，见 [PlatformAgent]），
 * 也不证明该 Agent 会回复。真实端到端验证仍要 maintainer 在真机上对
 * 该 Agent 发一条消息、看它回不回。
 */
object PlatformWebhookTester {

    /** 测试通知的签名头名（HMAC-SHA256 十六进制）。 */
    const val SIGNATURE_HEADER: String = "X-WhereverCode-Signature"

    /** 测试通知 JSON 里标明测试的事件名。 */
    const val TEST_EVENT: String = "wherevercode_test"

    fun testNotification(
        webhookUrl: String,
        platformAgentId: String,
        signingKey: String?,
        httpClient: WebhookHttpClient = UrlConnectionWebhookHttpClient(),
    ): WebhookTestResult {
        val url = webhookUrl.trim()
        if (url.isBlank()) return WebhookTestResult.NetworkError("未填 webhook 地址。")
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            return WebhookTestResult.NetworkError("webhook 地址须以 https:// 或 http:// 开头。")
        }
        val body = JSONObject().apply {
            put("event", TEST_EVENT)
            put("source", "v5-app")
            put("agent_id", platformAgentId)
            put("text", "WhereverCode V5 App 测试通知：只验证 webhook 端点可达，请忽略，不要当作真实任务。")
        }.toString()
        val headers = mutableMapOf<String, String>()
        val key = signingKey?.takeIf { it.isNotBlank() }
        if (key != null) {
            headers[SIGNATURE_HEADER] = "sha256=" + hmacSha256Hex(key, body)
        }
        return try {
            val (status, responseBody) = httpClient.postJson(url, headers, body)
            val preview = responseBody.take(200)
            if (status in 200..299) WebhookTestResult.Ok(status, preview)
            else WebhookTestResult.HttpError(status, preview)
        } catch (e: Exception) {
            WebhookTestResult.NetworkError(e.message?.take(200) ?: "未知网络错误")
        }
    }

    /** HMAC-SHA256（小写十六进制）。 */
    fun hmacSha256Hex(key: String, body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(body.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
