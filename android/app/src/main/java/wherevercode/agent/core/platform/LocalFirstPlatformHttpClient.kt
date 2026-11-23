package wherevercode.agent.core.platform

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import wherevercode.agent.core.agent.PlatformHttpClient
import wherevercode.agent.core.agent.UrlConnectionPlatformHttpClient

/**
 * 平台 HTTP 客户端的本地优先路由（频道三层一期·本地层）：
 * 端点主机是 [LocalPlatform.LOCAL_HOST] 时不走网络，把 JSON-RPC
 * `tools/call` 在进程内直交 [LocalChannelService.dispatch]，结果按
 * 平台 MCP 的同一信封形状返回——于是现成的
 * [wherevercode.agent.core.agent.PlatformChannelMcpTransport] 与频道
 * 页全套逻辑对本地/远程无差别。其他主机原样委托真实网络客户端。
 *
 * 错误口径与远程一致：工具失败回 isError=true 的结果，传输层会把
 * 文案原样抛成 IOException 给界面，绝不假成功。
 */
class LocalFirstPlatformHttpClient(
    private val delegate: PlatformHttpClient = UrlConnectionPlatformHttpClient(),
    private val serviceProvider: () -> LocalChannelService? = { LocalPlatform.serviceOrNull() },
) : PlatformHttpClient {

    override fun postJson(url: String, bearerToken: String, bodyJson: String): String {
        if (!LocalPlatform.isLocalEndpoint(url)) {
            return delegate.postJson(url, bearerToken, bodyJson)
        }
        val service = serviceProvider()
            ?: throw IOException("本地平台服务未启动")
        val request = try {
            JSONObject(bodyJson)
        } catch (_: Exception) {
            throw IOException("本地平台收到无法解析的请求")
        }
        val id: Any = if (request.has("id")) request.get("id") else JSONObject.NULL
        if (request.optString("method", "") != "tools/call") {
            return envelope(id, errorResult("本地平台只支持 tools/call"))
        }
        if (bearerToken != LocalPlatform.LOCAL_TOKEN) {
            return envelope(id, errorResult("本地平台凭证无效"))
        }
        val params = request.optJSONObject("params") ?: JSONObject()
        val name = params.optString("name", "")
        val arguments = params.optJSONObject("arguments") ?: JSONObject()
        return try {
            val result = service.dispatch(
                name,
                arguments,
                LocalPlatform.APP_AGENT_ID,
                LocalPlatform.APP_AGENT_NAME,
            )
            envelope(id, okResult(result))
        } catch (e: LocalServiceException) {
            envelope(id, errorResult(e.message ?: "本地平台调用失败"))
        }
    }

    override fun getJson(url: String, bearerToken: String?): String {
        if (!LocalPlatform.isLocalEndpoint(url)) {
            return delegate.getJson(url, bearerToken)
        }
        return JSONObject()
            .put("ok", true)
            .put("service", "local-platform")
            .put("version", "1")
            .toString()
    }

    private fun okResult(result: JSONObject): JSONObject = JSONObject().apply {
        put("content", JSONArray().put(JSONObject().apply {
            put("type", "text")
            put("text", result.toString())
        }))
        put("isError", false)
    }

    private fun errorResult(message: String): JSONObject = JSONObject().apply {
        put("content", JSONArray().put(JSONObject().apply {
            put("type", "text")
            put("text", message)
        }))
        put("isError", true)
    }

    private fun envelope(id: Any, result: JSONObject): String = JSONObject().apply {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }.toString()
}
