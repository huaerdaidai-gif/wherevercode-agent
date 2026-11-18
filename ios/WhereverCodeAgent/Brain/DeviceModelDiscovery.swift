import Foundation

/// 设备本地模型自动发现（对齐 Android DeviceModelDiscovery.kt）：
/// 按常用兼容协议探一台设备（本机或已配对设备）上的本地模型服务端点——
/// Ollama / LM Studio / vLLM 的 OpenAI 兼容 `/v1/models`。
/// 与 Agent 的「从设备添加」同口径：设备只是来源，在场省步骤，不建归属——
/// 发现并添加的模型配置不与设备关联，删设备不删模型。
///
/// 探测是只读 GET，不推理、不计费；端点没响应/没模型就跳过。
/// 发现不限于局域网：已配对设备无论在局域网、Tailscale 还是公网都探——
/// 本机/私有/网状网段地址走 http，公网地址走 https（公网明文 http 永远拒绝）。
public struct DiscoveredModelEndpoint: Equatable, Sendable {
    /// 服务名（Ollama / LM Studio / vLLM）。
    public let serviceName: String
    /// 可直接存为模型连接的 baseUrl（http://host:port/v1）。
    public let baseUrl: String
    public let models: [String]

    public init(serviceName: String, baseUrl: String, models: [String]) {
        self.serviceName = serviceName
        self.baseUrl = baseUrl
        self.models = models
    }
}

public enum DeviceModelDiscovery {

    public struct EndpointSpec: Sendable {
        public let serviceName: String
        public let port: Int

        public init(_ serviceName: String, _ port: Int) {
            self.serviceName = serviceName
            self.port = port
        }
    }

    /// 常用本地模型服务端点（端口为各家官方默认）。
    public static let endpoints: [EndpointSpec] = [
        EndpointSpec("Ollama", 11434),
        EndpointSpec("LM Studio", 1234),
        EndpointSpec("vLLM", 8000),
    ]

    /// 一台主机要探的全部候选 baseUrl（服务名 → baseUrl）。
    public static func candidateBaseUrls(host: String) -> [(serviceName: String, baseUrl: String)] {
        var clean = host.trimmingCharacters(in: .whitespacesAndNewlines)
        while clean.hasSuffix("/") { clean.removeLast() }
        let cleanHost = clean.replacingOccurrences(of: "http://", with: "").replacingOccurrences(of: "https://", with: "")
        let hostOnly = String(cleanHost.split(separator: ":").first ?? "")

        let scheme = isPlainHttpHost(hostOnly) ? "http" : "https"
        return endpoints.map { spec in
            (spec.serviceName, "\(scheme)://\(hostOnly):\(spec.port)/v1")
        }
    }

    /// 明文 http 只探本机、私有局域网与网状网段（Tailscale）地址。
    public static func isPlainHttpHost(_ host: String) -> Bool {
        host == "localhost" || host == "127.0.0.1" || BrainConfigValidator.isPrivateLanIpv4(host)
    }

    /// 探一台主机：逐个端点拉 /models，只收「有模型」的端点。
    /// Key 用占位值 "local"——本地服务端忽略 Key，但客户端要求非空。
    public static func probeHost(
        _ host: String,
        client: GenericOpenAiApiClient = GenericOpenAiApiClient()
    ) async -> [DiscoveredModelEndpoint] {
        var found: [DiscoveredModelEndpoint] = []
        for (serviceName, baseUrl) in candidateBaseUrls(host: host) {
            let result = await client.listModels(apiKey: "local", baseUrl: baseUrl)
            if case .success(let models) = result, !models.isEmpty {
                found.append(DiscoveredModelEndpoint(
                    serviceName: serviceName,
                    baseUrl: baseUrl,
                    models: models.map(\.id)
                ))
            }
        }
        return found
    }
}
