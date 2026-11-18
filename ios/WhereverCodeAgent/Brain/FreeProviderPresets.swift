import Foundation

/// 免费 token 模型服务商预设（对齐 Android FreeProviderPresets.kt）：
/// 项目主打开源免费——用户自己去服务商注册、领免费 API key、填进 App；
/// key 只存本机 Keychain，这里永远不预填 key。
///
/// 与 GenericOpenAiPresets（常用模型服务商）是两个独立功能、两份独立数据，绝不合并：
/// 常用组是「填服务器地址」，本组是「填注册好的免费 key」。
///
/// 名单逐家按官方文档/定价页核验（2026-11-18）。
/// 免费 API 真能用即可，不强制有 GET /models 列表接口——没有列表接口的两家
/// （智谱、Cloudflare）以 FIXED 内置官方免费模型名单入列。
///
/// 配置落点：从本组添加的模型连接统一写 extra 标记
/// [EXTRA_TIER] = [TIER_FREE]、[EXTRA_PRESET] = 预设 id，供智能路由的
/// 「免费优先、付费须用户开启」守卫识别档次。
public enum FreeModelListMode: String, Codable, Sendable {
    /// 填 key 后调 GET /models 实时拉取，用户勾选。
    case live = "LIVE"
    /// 无列表接口：用内置名单（官方免费款），用户勾选。
    case fixed = "FIXED"
}

public struct FreeProviderPreset: Identifiable, Codable, Equatable, Sendable {
    public let id: String
    public let displayName: String
    /// FIXED 组的名单；LIVE 组为空。
    public let fixedModels: [String]
    public let listMode: FreeModelListMode
    /// 含 `{account_id}` 占位符时，用户须多填一项（仅 Cloudflare）。
    public let baseUrlTemplate: String
    /// 领 key 的路径说明（呈现给用户，不含任何真实 key）。
    public let keyHelp: String
    public let note: String

    public init(
        id: String,
        displayName: String,
        fixedModels: [String] = [],
        listMode: FreeModelListMode,
        baseUrlTemplate: String,
        keyHelp: String,
        note: String = ""
    ) {
        self.id = id
        self.displayName = displayName
        self.fixedModels = fixedModels
        self.listMode = listMode
        self.baseUrlTemplate = baseUrlTemplate
        self.keyHelp = keyHelp
        self.note = note
    }

    public var needsAccountId: Bool {
        baseUrlTemplate.contains("{account_id}")
    }

    /// 把用户填的 Account ID 代入模板；不需要时原样返回。
    public func resolveBaseUrl(accountId: String? = nil) -> String {
        var base = baseUrlTemplate.trimmingCharacters(in: .whitespacesAndNewlines)
        while base.hasSuffix("/") { base.removeLast() }
        if needsAccountId {
            let acc = (accountId ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            return base.replacingOccurrences(of: "{account_id}", with: acc)
        }
        return base
    }
}

public enum FreeProviderPresets {
    public static let extraTier: String = "tier"
    public static let extraPreset: String = "provider_preset"
    public static let tierFree: String = "free"

    public static let presets: [FreeProviderPreset] = [
        FreeProviderPreset(
            id: "google-ai-studio",
            displayName: "Google AI Studio（Gemini）",
            fixedModels: [],
            listMode: .live,
            baseUrlTemplate: "https://generativelanguage.googleapis.com/v1beta/openai",
            keyHelp: "用 Google 账号登录 AI Studio 即可领免费 key，不用绑卡。",
            note: "免费层可用 Gemini Flash 类模型；免费限额数字只在登录后可见，官方不保证。"
        ),
        FreeProviderPreset(
            id: "openrouter",
            displayName: "OpenRouter",
            fixedModels: [],
            listMode: .live,
            baseUrlTemplate: "https://openrouter.ai/api/v1",
            keyHelp: "在 OpenRouter 注册后在控制台建 key，不用绑卡。",
            note: "免费模型是 ID 以 :free 结尾的款；未充值账户 50 次/天，靠智能路由在免费家之间切换可绕开单家上限。"
        ),
        FreeProviderPreset(
            id: "groq",
            displayName: "Groq",
            fixedModels: [],
            listMode: .live,
            baseUrlTemplate: "https://api.groq.com/openai/v1",
            keyHelp: "在 Groq 控制台注册后建 key，不用绑卡。",
            note: "速度最快；免费限额按模型逐个定（约 1,000 次/天量级），阵列以实时拉取为准。"
        ),
        FreeProviderPreset(
            id: "siliconflow",
            displayName: "硅基流动 SiliconFlow",
            fixedModels: [],
            listMode: .live,
            baseUrlTemplate: "https://api.siliconflow.cn/v1",
            keyHelp: "手机号注册后在控制台建 key；用免费模型须先完成实名认证（身份证 + 人脸）。",
            note: "国产编码模型免费阵容全；列表含向量/图像等非对话模型，请选对话模型（名字带 GLM、DeepSeek、Qwen 等）。须实名认证。"
        ),
        FreeProviderPreset(
            id: "zhipu",
            displayName: "智谱 GLM",
            fixedModels: [
                "glm-4.7-flash",
                "glm-4-flash-250414",
                "glm-z1-flash",
                "glm-4.6v-flash",
                "glm-4v-flash",
                "glm-4.1v-thinking-flash"
            ],
            listMode: .fixed,
            baseUrlTemplate: "https://open.bigmodel.cn/api/paas/v4",
            keyHelp: "手机号在智谱开放平台注册后建 key，调用不强制实名、不用绑卡。",
            note: "智谱没有模型列表接口，名单为内置的官方免费款（2026-11 核）；可用性以实际调用为准。"
        ),
        FreeProviderPreset(
            id: "cloudflare",
            displayName: "Cloudflare Workers AI",
            fixedModels: [
                "@cf/meta/llama-3.3-70b-instruct-fp8-fast",
                "@cf/qwen/qwen2.5-coder-32b-instruct",
                "@cf/deepseek-ai/deepseek-r1-distill-qwen-32b",
                "@cf/openai/gpt-oss-120b"
            ],
            listMode: .fixed,
            baseUrlTemplate: "https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1",
            keyHelp: "在 Cloudflare 控制台建 API Token（Workers AI 权限），并填你的 Account ID；不用绑卡。",
            note: "每天免费 10,000 Neurons（UTC 零点重置）；没有模型列表接口，名单为内置常用款。"
        )
    ]

    public static func preset(id: String) -> FreeProviderPreset? {
        presets.first { $0.id == id }
    }

    /// 按 baseUrl 反查预设（尾斜杠宽容；Cloudflare 模板按前缀匹配）。
    public static func presetForBaseUrl(_ baseUrl: String) -> FreeProviderPreset? {
        var wanted = baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        while wanted.hasSuffix("/") { wanted.removeLast() }

        return presets.first { preset in
            var template = preset.baseUrlTemplate.trimmingCharacters(in: .whitespacesAndNewlines)
            while template.hasSuffix("/") { template.removeLast() }

            if preset.needsAccountId {
                let parts = template.components(separatedBy: "{account_id}")
                if parts.count == 2 {
                    let prefix = parts[0]
                    let suffix = parts[1]
                    return wanted.hasPrefix(prefix) && wanted.hasSuffix(suffix) && wanted.count > prefix.count + suffix.count
                }
                return false
            } else {
                return template == wanted
            }
        }
    }
}
