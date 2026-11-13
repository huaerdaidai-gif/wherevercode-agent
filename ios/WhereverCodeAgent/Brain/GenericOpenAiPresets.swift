import Foundation

/// Provider presets for the generic OpenAI-compatible form.
/// Ported verbatim from Android `GenericOpenAiPresets.kt` on main
/// @4496658e (values verified against official docs 2026-11-10).
/// Presets are form suggestions only — never a silent default: a
/// brain exists only after the user picks one and saves.
public struct GenericOpenAiPreset: Equatable {
    public var displayName: String
    public var baseUrl: String
    public var note: String = ""

    public init(displayName: String, baseUrl: String, note: String = "") {
        self.displayName = displayName
        self.baseUrl = baseUrl
        self.note = note
    }
}

public enum GenericOpenAiPresets {
    public static let presets: [GenericOpenAiPreset] = [
        GenericOpenAiPreset(
            displayName: "OpenAI",
            baseUrl: "https://api.openai.com/v1"),
        GenericOpenAiPreset(
            displayName: "OpenRouter",
            baseUrl: "https://openrouter.ai/api/v1"),
        GenericOpenAiPreset(
            displayName: "DeepSeek",
            baseUrl: "https://api.deepseek.com"),
        GenericOpenAiPreset(
            displayName: "Meta Muse",
            baseUrl: "https://api.meta.ai/v1",
            note: "Meta 官方 Model API（Muse Spark 模型）；不是个人助理 Muse（无公开 API）。"),
        GenericOpenAiPreset(
            displayName: "xAI Grok",
            baseUrl: "https://api.x.ai/v1",
            note: "xAI 官方 API（OpenAI 兼容）；模型用 grok-4.7 等 Grok 模型 ID。单独计费，不共享 Grok/X 会员额度。"),
        GenericOpenAiPreset(
            displayName: "Google Gemini",
            baseUrl: "https://generativelanguage.googleapis.com/v1beta/openai/",
            note: "Google 官方 OpenAI 兼容端点（beta）；模型用 Gemini 模型 ID。"),
        GenericOpenAiPreset(
            displayName: "Ollama（本地）",
            baseUrl: "http://localhost:11434/v1",
            note: "手机上的 localhost 是手机自身；电脑上的 Ollama 请填电脑的局域网地址。"),
        GenericOpenAiPreset(
            displayName: "LM Studio（本地）",
            baseUrl: "http://localhost:1234/v1",
            note: "手机上的 localhost 是手机自身；电脑上的 LM Studio 请填电脑的局域网地址。"),
    ]

    /// Match a saved baseUrl back to its preset (trailing-slash
    /// tolerant).
    public static func presetForBaseUrl(_ baseUrl: String) -> GenericOpenAiPreset? {
        let wanted = trimTrailingSlashes(baseUrl.trimmingCharacters(in: .whitespaces))
        return presets.first { preset in
            trimTrailingSlashes(preset.baseUrl) == wanted
        }
    }

    private static func trimTrailingSlashes(_ value: String) -> String {
        var s = value
        while s.hasSuffix("/") { s.removeLast() }
        return s
    }
}
