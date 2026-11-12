package wherevercode.agent.brain.generic

/**
 * Provider presets for the generic OpenAI-compatible form (UI worker
 * fills baseUrl + display name from these; the key is always typed by
 * the user on the device, never pre-filled here).
 *
 * Only baseUrl + display name are preset — never a key, never a model:
 * the model always comes from the provider's own live `/models` list.
 *
 * Verification (2026-11-10, official docs read first-hand):
 * - OpenAI `https://api.openai.com/v1` — reference OpenAI-compatible
 *   base; `/chat/completions` + `GET /models`.
 * - OpenRouter `https://openrouter.ai/api/v1` — OpenRouter's own docs:
 *   "a unified API … one OpenAI-compatible base URL".
 * - DeepSeek `https://api.deepseek.com` — already the in-app DeepSeek
 *   brain's default; kept here so a user can add a second DeepSeek
 *   configuration through the generic form.
 * - Meta Muse `https://api.meta.ai/v1` — Meta's official Model API
 *   (dev.meta.ai): OpenAI-compatible `/chat/completions` + `GET
 *   `/models`, Bearer key, SSE streaming, tool calling documented.
 *   Kept here so a user can add a second Muse configuration through
 *   the generic form; the first-class Muse Spark brain is the
 *   dedicated `model-api-muse` adapter. Note: this is the Muse Spark
 *   model, not the personal Muse assistant (no public API for that).
 * - Google Gemini `https://generativelanguage.googleapis.com/v1beta/openai/`
 *   — Google's official "OpenAI compatibility" doc (ai.google.dev):
 *   `base_url` exactly this value, `/chat/completions` + streaming +
 *   function calling documented; the layer is still in beta, so the
 *   preset note says so.
 * - Ollama `http://localhost:11434/v1` and LM Studio
 *   `http://localhost:1234/v1` — both serve `/v1/chat/completions` +
 *   `GET /v1/models`; the server ignores the key but clients must still
 *   send a non-empty placeholder. Caveat, written on the presets: on
 *   the phone, `localhost` is the phone itself — a model server running
 *   on the user's Mac/PC is NOT reachable at `localhost` from the App.
 *   The user must type that computer's LAN address instead, and the
 *   current [wherevercode.agent.brain.BrainConfigValidator] only allows
 *   https or loopback http, so a LAN http address is rejected until
 *   the validator gains an explicit LAN rule (follow-up, not smuggled
 *   in here).
 *
 * Deliberately absent: Anthropic. Anthropic has NO OpenAI-compatible
 * endpoint — its API is the Messages API (`/v1/messages`), a different
 * wire protocol. Wiring an Anthropic key into this adapter would fake a
 * connection that cannot work. Claude access needs a separate Messages
 * API adapter (follow-up item, not this batch).
 */
data class GenericOpenAiPreset(
    val displayName: String,
    val baseUrl: String,
    val note: String = "",
)

object GenericOpenAiPresets {

    val PRESETS: List<GenericOpenAiPreset> = listOf(
        GenericOpenAiPreset(
            displayName = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
        ),
        GenericOpenAiPreset(
            displayName = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
        ),
        GenericOpenAiPreset(
            displayName = "DeepSeek",
            baseUrl = "https://api.deepseek.com",
        ),
        GenericOpenAiPreset(
            displayName = "Meta Muse",
            baseUrl = "https://api.meta.ai/v1",
            note = "Meta 官方 Model API（Muse Spark 模型）；不是个人助理 Muse（无公开 API）。",
        ),
        GenericOpenAiPreset(
            displayName = "xAI Grok",
            baseUrl = "https://api.x.ai/v1",
            note = "xAI 官方 API（OpenAI 兼容）；模型用 grok-4.7 等 Grok 模型 ID。单独计费，不共享 Grok/X 会员额度。",
        ),
        GenericOpenAiPreset(
            displayName = "Google Gemini",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai/",
            note = "Google 官方 OpenAI 兼容端点（beta）；模型用 Gemini 模型 ID。",
        ),
        GenericOpenAiPreset(
            displayName = "Ollama（本地）",
            baseUrl = "http://localhost:11434/v1",
            note = "手机上的 localhost 是手机自身；电脑上的 Ollama 请填电脑的局域网地址。",
        ),
        GenericOpenAiPreset(
            displayName = "LM Studio（本地）",
            baseUrl = "http://localhost:1234/v1",
            note = "手机上的 localhost 是手机自身；电脑上的 LM Studio 请填电脑的局域网地址。",
        ),
    )

    /** Match a saved baseUrl back to its preset (trailing-slash tolerant). */
    fun presetForBaseUrl(baseUrl: String): GenericOpenAiPreset? {
        val wanted = baseUrl.trim().trimEnd('/')
        return PRESETS.firstOrNull { it.baseUrl.trimEnd('/') == wanted }
    }
}
