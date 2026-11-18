package wherevercode.agent.brain.generic

/**
 * 设备本地模型自动发现（三件套之三，2026-11-18）：按常用兼容协议
 * 探一台设备（本机或已配对设备）上的本地模型服务端点——Ollama /
 * LM Studio / vLLM 的 OpenAI 兼容 `/v1/models`。与 Agent 的「从
 * 设备添加」同口径：设备只是来源，在场省步骤，不建归属——发现并
 * 添加的模型配置不与设备关联，删设备不删模型。
 *
 * 探测是只读 GET，不推理、不计费；端点没响应/没模型就跳过，不把
 * 「探不到」说成「设备坏了」。局域网 http 只对本机与私有局域网
 * 地址放行（见 [wherevercode.agent.brain.BrainConfigValidator] 的
 * LAN 规则），公网 http 永远拒绝。
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
        return ENDPOINTS.map { spec -> spec.serviceName to "http://$clean:${spec.port}/v1" }
    }

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
