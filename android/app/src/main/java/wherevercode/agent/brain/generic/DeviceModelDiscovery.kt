package wherevercode.agent.brain.generic

import wherevercode.agent.brain.BrainConfigValidator

/**
 * 设备本地模型自动发现（三件套之三，2026-11-18）：按常用兼容协议
 * 探一台设备（本机或已配对设备）上的本地模型服务端点——Ollama /
 * LM Studio / vLLM 的 OpenAI 兼容 `/v1/models`。与 Agent 的「从
 * 设备添加」同口径：设备只是来源，在场省步骤，不建归属——发现并
 * 添加的模型配置不与设备关联，删设备不删模型。
 *
 * 探测是只读 GET，不推理、不计费；端点没响应/没模型就跳过，不把
 * 「探不到」说成「设备坏了」。发现不限于局域网（Funk 2026-11-18
 * 23:56）：已配对设备无论在局域网、Tailscale 还是公网都探——
 * 本机/私有/网状网段地址走 http，公网地址走 https（公网明文
 * http 永远拒绝，见 BrainConfigValidator 的地址规则）。
 */
data class DiscoveredModelEndpoint(
    /** 服务名（Ollama / LM Studio / vLLM）。 */
    val serviceName: String,
    /** 可直接存为模型连接的 baseUrl（http://host:port/v1）。 */
    val baseUrl: String,
    val models: List<String>,
)

object DeviceModelDiscovery {

    data class EndpointSpec(val serviceName: String, val port: Int)

    /** 常用本地模型服务端点（端口为各家官方默认）。 */
    val ENDPOINTS: List<EndpointSpec> = listOf(
        EndpointSpec("Ollama", 11434),
        EndpointSpec("LM Studio", 1234),
        EndpointSpec("vLLM", 8000),
    )

    /** 一台主机要探的全部候选 baseUrl（服务名 → baseUrl）。 */
    fun candidateBaseUrls(host: String): List<Pair<String, String>> {
        val clean = host.trim().trimEnd('/')
        val scheme = if (isPlainHttpHost(clean)) "http" else "https"
        return ENDPOINTS.map { spec -> spec.serviceName to "$scheme://$clean:${spec.port}/v1" }
    }

    /** 明文 http 只探本机、私有局域网与网状网段（Tailscale）地址。 */
    fun isPlainHttpHost(host: String): Boolean =
        host == "localhost" || host == "127.0.0.1" ||
            BrainConfigValidator.isPrivateLanIpv4(host)

    /**
     * 探一台主机：逐个端点拉 /models，只收「有模型」的端点。
     * Key 用占位值 "local"——本地服务端忽略 Key，但客户端要求
     * 非空（见 [GenericOpenAiPresets] 注记）。
     */
    suspend fun probeHost(
        host: String,
        client: GenericOpenAiApiClient = GenericOpenAiApiClient(),
    ): List<DiscoveredModelEndpoint> {
        val found = mutableListOf<DiscoveredModelEndpoint>()
        for ((serviceName, baseUrl) in candidateBaseUrls(host)) {
            val models = client.listModels("local", baseUrl).getOrNull() ?: continue
            if (models.isEmpty()) continue
            found += DiscoveredModelEndpoint(serviceName, baseUrl, models.map { it.id })
        }
        return found
    }
}
