package wherevercode.agent.brain.siwc

import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * SIWC 运行时模型发现（M3 阻塞修复，2026-11-08）。
 *
 * 背景：K70 真机上 "在吗" 收到服务端 400 model_not_found，因为旧代码把
 * 模型写死为 "gpt-5"（SiwcBrainAdapter.DEFAULT_MODEL），而该模型已不在
 * ChatGPT 账号登录的 Codex 可用目录里。官方机制没有固定白名单：可用模型
 * = 该账号 GET /v1/models 返回且 visibility == "list" 的目录
 * （SIWC《Models and inference》："request the selected ChatGPT account's
 * model catalog with the same access token you will use for inference"，
 * 示例 jq：[.models[] | select(.visibility == "list") | {slug, display_name}]）。
 *
 * 本类只做发现，不做推理：
 * - 用用户已授权的同一 SIWC token 调 GET https://api.openai.com/v1/models；
 * - 只取 visibility == "list" 的 slug；目录为空或发现失败时明确报错，
 *   绝不回退到任何写死模型；
 * - 选模型时优先 gpt-6.1-sol（官方示例与 Codex 模型页共同指向的 Plus
 *   账号兼容模型），它不在目录里时用目录中实际返回的第一个 slug；
 *   gpt-6.1-sol 只是"目录内的优先项"，不是"发现失败时的兜底"——
 *   它是否在 maintainer 该账号目录里，须由运行时发现证实，代码不预设。
 * - 绝不使用 gpt-5-codex：官方 API 模型页上它的状态就是 Shut down。
 *
 * 红线（与 ResponsesApiClient 相同）：token 由调用方传入；本层绝不存储
 * token、不读 Keystore、不记日志、不把 token 写进任何异常信息。
 */
class SiwcModelDiscovery(
    private val transport: SiwcModelsTransport = HttpsSiwcModelsTransport(),
    private val modelsUrl: String = MODELS_URL,
) {
    @Volatile
    private var cachedCatalog: SiwcModelCatalog? = null

    /** 丢弃内存中的目录缓存，下次发现重新向服务器要目录（400 model_not_found 重试前调用）。 */
    fun invalidate() {
        cachedCatalog = null
    }

    /**
     * 取该账号的可用模型目录（内存有缓存时直接返回，不再发请求）。
     *
     * 错误语义（调用方据此向用户提示，绝不把失败包装成假回复）：
     * - 空 token：[BrainError.NotConfigured]；
     * - 401：[BrainError.AuthFailed]（请重新登录）；
     * - 403 / 429：[BrainError.QuotaExceeded]（资格 / 额度类，不可重试）；
     * - 目录为空：[BrainError.Unsupported]（明确说目录为空，不猜原因）；
     * - 网络异常 / 5xx / 响应解析失败：[BrainError.Transport]（可重试）。
     */
    suspend fun discover(accessToken: String): SiwcModelCatalog {
        cachedCatalog?.let { return it }
        if (accessToken.isBlank()) {
            throw BrainError.NotConfigured(
                "未提供 SIWC OAuth token：请用户先通过“使用 ChatGPT 登录”完成授权，" +
                    "本层不持有、不生成 token。",
            )
        }
        val response = withContext(Dispatchers.IO) {
            try {
                transport.get(modelsUrl, accessToken)
            } catch (e: IOException) {
                throw BrainError.Transport("获取模型目录失败（网络异常）：${e.message}", e)
            }
        }
        when (response.status) {
            200 -> {
                val catalog = parseCatalog(response.body)
                if (catalog.models.isEmpty()) {
                    throw BrainError.Unsupported(
                        "该 ChatGPT 账号的可用模型目录为空（GET /v1/models 未返回 " +
                            "visibility == \"list\" 的模型）：无法确定用哪个模型推理，" +
                            "请检查该账号的计划与授权范围，或重新登录。",
                    )
                }
                cachedCatalog = catalog
                return catalog
            }
            401 -> throw BrainError.AuthFailed(
                "ChatGPT 授权失败（401）：token 无效或已过期，请重新登录。",
            )
            403 -> throw BrainError.QuotaExceeded(
                "模型目录请求被拒绝（403）：可能为 SIWC 资格不足、计划不符或额度受限，" +
                    "请检查该 ChatGPT 账号的计划与授权范围；不可重试。",
            )
            429 -> throw BrainError.QuotaExceeded(
                "模型目录请求受限（429）：请稍后再试或检查计划用量；本层按不可重试处理。",
            )
            else -> throw BrainError.Transport(
                "模型目录请求失败（HTTP ${response.status}），可重试。",
            )
        }
    }

    companion object {
        /** 官方规定的模型发现端点（SIWC《Models and inference》）。 */
        const val MODELS_URL: String = "https://api.openai.com/v1/models"

        /**
         * 目录内的优先模型（有官方证据的 Plus 账号兼容模型：SIWC
         * 《Models and inference》curl 与 Python 两个官方示例共同使用的
         * 唯一模型名；官方 Codex 模型页写 Plus/Pro/Business/Enterprise/Edu
         * 账号应选它 "when available"）。只是优先项：它不在目录里时用
         * 目录中实际返回的其他 slug；目录为空或发现失败时不回退到它。
         */
        const val PREFERRED_MODEL: String = "gpt-6.1-sol"

        /**
         * 从已发现的目录里选一个模型（纯函数，无网络）。
         * 优先 [PREFERRED_MODEL]，不在目录里时用目录中实际返回的第一个
         * slug；目录为空返回 null（调用方须明确报错，不回退写死模型）。
         */
        fun selectModel(catalog: SiwcModelCatalog): String? =
            if (PREFERRED_MODEL in catalog.models) PREFERRED_MODEL
            else catalog.models.firstOrNull()

        /**
         * 解析 GET /v1/models 的响应体（纯函数）。
         * 只取 `models[]` 中 `visibility == "list"` 的 `slug`（官方 jq
         * 示例的规则）；没有 visibility 字段的条目不计入目录。
         * 响应不是合法 JSON 或没有 `models` 数组时抛 Transport（可重试），
         * 不猜一个模型出来。
         */
        fun parseCatalog(body: String): SiwcModelCatalog {
            val root = try {
                JSONObject(body)
            } catch (_: Exception) {
                throw BrainError.Transport("模型目录响应不是合法 JSON，无法确定可用模型。")
            }
            val array = root.optJSONArray("models")
                ?: throw BrainError.Transport("模型目录响应里没有 models 字段，无法确定可用模型。")
            val slugs = mutableListOf<String>()
            for (i in 0 until array.length()) {
                val entry = array.optJSONObject(i) ?: continue
                if (entry.optString("visibility", "") != "list") continue
                val slug = entry.optString("slug", "")
                if (slug.isNotBlank()) slugs += slug
            }
            return SiwcModelCatalog(slugs)
        }
    }
}

/** 该账号的可用模型目录：GET /v1/models 返回且 visibility == "list" 的 slug，服务端返回顺序。 */
data class SiwcModelCatalog(val models: List<String>)

/**
 * 持久化"从账号目录里解析出的模型"（只存模型 slug  plain 文本，绝不是 token）。
 * 由调用方（BrainBootstrap）用 BrainConfigStore 实现：解析出的模型经
 * saveConfig 写回 SIWC 的 BrainConfig，之后重启也不用重新发现。
 */
interface SiwcModelStore {
    /** 上次解析出并存下的模型；没有返回 null（调用方须走发现，不猜默认值）。 */
    fun loadModel(): String?

    /** 存下本次解析出的模型。 */
    fun saveModel(model: String)
}

/**
 * 可注入的模型目录 HTTP 传输层：生产用 [HttpsSiwcModelsTransport]，单测用 fake 实现。
 * token 只经过 [get] 的参数，不由本层存储；实现者同样不得存储或记录它。
 */
interface SiwcModelsTransport {
    /** GET 模型目录。网络失败时抛 [IOException]（由调用方译为 Transport）。 */
    @Throws(IOException::class)
    fun get(url: String, accessToken: String): SiwcModelsResponse
}

/** 一次 GET /v1/models 的响应：状态码加全文 body。 */
data class SiwcModelsResponse(val status: Int, val body: String)

/**
 * 基于 [HttpsURLConnection] 的默认传输实现。不引入新依赖（与 ResponsesApiClient
 * 的 HttpsResponsesTransport 同源）。超时为本地选择，未在官方文档中核实：
 * 连接 15 秒，读取 30 秒（模型目录是一次短 GET，不是长流）。
 */
class HttpsSiwcModelsTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : SiwcModelsTransport {

    override fun get(url: String, accessToken: String): SiwcModelsResponse {
        val connection = URL(url).openConnection() as HttpsURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("Accept", "application/json")
        // token 只放进 Authorization 头；本类不存储、不记录、不写进异常。
        connection.setRequestProperty("Authorization", "Bearer $accessToken")
        try {
            val status = try {
                connection.responseCode
            } catch (e: IOException) {
                throw e
            }
            val body = try {
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            } catch (_: Exception) {
                ""
            }
            return SiwcModelsResponse(status, body)
        } finally {
            try {
                connection.disconnect()
            } catch (_: Exception) {
                // 关闭时的异常不掩盖主流程结果。
            }
        }
    }
}
