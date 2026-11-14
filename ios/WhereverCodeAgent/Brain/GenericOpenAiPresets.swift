import Foundation

public struct GenericOpenAiPreset: Equatable, Identifiable, Sendable {
    public var id: String { displayName }
    public let displayName: String
    public let baseUrl: String
    public let note: String

    public init(displayName: String, baseUrl: String, note: String) {
        self.displayName = displayName
        self.baseUrl = baseUrl
        self.note = note
    }

    public static let all: [GenericOpenAiPreset] = [
        GenericOpenAiPreset(displayName: "OpenAI 官方 API", baseUrl: "https://api.openai.com/v1", note: "标准 OpenAI /v1/chat/completions 端点，Key 存本机 Keychain。"),
        GenericOpenAiPreset(displayName: "OpenRouter", baseUrl: "https://openrouter.ai/api/v1", note: "聚合多厂商模型路由，支持标准 tool_calls。"),
        GenericOpenAiPreset(displayName: "Meta Model API (Muse Spark)", baseUrl: "https://api.meta.ai/v1", note: "Meta 官方 Model API（Muse Spark 模型）；不是个人助理 Muse（无公开 API）。"),
        GenericOpenAiPreset(displayName: "xAI 官方 API (Grok)", baseUrl: "https://api.x.ai/v1", note: "xAI 官方 API（OpenAI 兼容）；模型用 grok-4.7 等 Grok 模型 ID。单独计费，不共享 Grok/X 会员额度。"),
        GenericOpenAiPreset(displayName: "Google Gemini (OpenAI 兼容)", baseUrl: "https://generativelanguage.googleapis.com/v1beta/openai/", note: "Google 官方 OpenAI 兼容端点（beta）；模型用 Gemini 模型 ID。"),
        GenericOpenAiPreset(displayName: "Ollama（本地/局域网）", baseUrl: "http://localhost:11434/v1", note: "手机上的 localhost 是手机自身；电脑上的 Ollama 请填电脑的局域网地址。"),
        GenericOpenAiPreset(displayName: "LM Studio（本地/局域网）", baseUrl: "http://localhost:1234/v1", note: "手机上的 localhost 是手机自身；电脑上的 LM Studio 请填电脑的局域网地址。")
    ]
}
