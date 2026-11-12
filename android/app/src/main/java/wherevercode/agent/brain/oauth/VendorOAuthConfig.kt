package wherevercode.agent.brain.oauth

/**
 * One vendor's membership-login configuration for the generic OAuth 2.0 +
 * PKCE engine ([OAuthEngine]).
 *
 * The engine implements the protocol exactly once; everything that differs
 * between vendors lives in this table. Adding a vendor means adding one
 * [VendorOAuthConfig] entry — never a second copy of the login flow.
 *
 * What belongs here (and only here):
 * - endpoint URLs (discovery document + verified-constant fallbacks),
 * - the requested scopes and the `resource` parameter (null = omit it),
 * - how the vendor's client id is obtained: `dynamicClientId` is the
 *   well-known id sent on first registration (the server then issues a
 *   real client id); `fixedClientId` is a client id the V5 developer
 *   registered themselves for this app (e.g. a Google Cloud Console
 *   Desktop OAuth client) — null means no such client is registered
 *   yet and login is not possible until one is. A vendor with both
 *   ids null must not be presented as log-in-able.
 * - `callbackPath`: the loopback redirect path (only the port may vary).
 * - `sharingScope`: the scope whose grant means "plan usage enabled"
 *   (null = the vendor has no such concept).
 * - `inferenceSupported`: false means signing in does NOT make the
 *   vendor usable as an inference brain (Gemini: the membership token
 *   cannot call the Gemini API); the entry is a login-only entry and
 *   the UI must say so — never let a user believe login == brain.
 *
 * What does NOT belong here: any token, secret, or login state. Tokens
 * live only in Keystore-backed storage (see the SIWC token store); this
 * table holds public endpoint metadata only.
 *
 * Verification rule (maintainer 2026-11-10): a vendor enters this table only
 * after its membership-login flow has been verified. Logging in is not
 * the same as being able to run inference on the membership token — that
 * has to be verified vendor by vendor, separately.
 */
data class VendorOAuthConfig(
    /** Stable id used in code and logs, e.g. `"chatgpt"`. */
    val vendorId: String,
    /** Product name shown to the user, e.g. `"ChatGPT"`. */
    val displayName: String,
    /** Authorization-server operator named in transport errors, e.g. `"OpenAI"`. */
    val operatorName: String,
    /** OIDC issuer; the discovery document must report exactly this. */
    val issuer: String,
    /** OIDC discovery document URL. */
    val discoveryUrl: String,
    /** Authorization endpoint (fallback when discovery is unreachable). */
    val authorizationUrl: String,
    /** Token endpoint (fallback when discovery is unreachable). */
    val tokenUrl: String,
    /** Revocation endpoint (fallback when discovery is unreachable). */
    val revocationUrl: String,
    /** JWKS URI (fallback when discovery is unreachable). */
    val jwksUri: String,
    /** Scopes requested at authorization time, in request order. */
    val scopes: List<String>,
    /** `resource` parameter sent with authorization and token requests, or null to omit. */
    val resource: String?,
    /** Well-known client id for first (dynamic) registration, or null if the vendor needs a fixed client id. */
    val dynamicClientId: String?,
    /**
     * Fixed client id registered by the V5 developer for this app
     * (2026-11-12, for vendors with no DCR and no public well-known
     * client id — Google is the case). Null = not registered yet:
     * login is disabled until a developer registers a client (e.g. a
     * Google Cloud Console Desktop OAuth client) and fills this in.
     * NEVER fill this with another vendor's CLI client id: reusing
     * the Gemini CLI client id is banned by its terms, and the
     * personal tier behind it stopped serving on 2026-06-18.
     */
    val fixedClientId: String? = null,
    /** Loopback redirect path, e.g. `"/auth/callback"`. */
    val callbackPath: String,
    /** Scope whose grant means plan usage is enabled, or null when the vendor has no such concept. */
    val sharingScope: String?,
    /**
     * False when signing in does NOT make the vendor usable as an
     * inference brain. Gemini is the case: a Google account OAuth
     * token authorises Google API scopes, not the Gemini API (which
     * only takes an API Key); the internal Code Assist endpoint is
     * undocumented, its terms ban reuse, and its personal tier is
     * dead. The stable Gemini brain route stays API Key (AI Studio)
     * or Vertex AI. A false entry must be shown as login-only.
     */
    val inferenceSupported: Boolean = true,
)

/**
 * Canonical ChatGPT / SIWC endpoint constants, verified 2026-11-08
 * against the live OIDC discovery document
 * (`https://auth.openai.com/.well-known/openid-configuration`).
 * Single source of truth: [VendorOAuthConfigs.CHATGPT] and
 * `SiwcAuthManager`'s companion constants both read these values.
 */
const val CHATGPT_ISSUER = "https://auth.openai.com"
const val CHATGPT_AUTHORIZATION_URL = "https://auth.openai.com/api/accounts/authorize"
const val CHATGPT_TOKEN_URL = "https://auth.openai.com/api/accounts/oauth/token"
const val CHATGPT_REVOCATION_URL = "https://auth.openai.com/api/accounts/oauth/revoke"
const val CHATGPT_JWKS_URI = "https://auth.openai.com/.well-known/jwks.json"
const val CHATGPT_DISCOVERY_URL = "https://auth.openai.com/.well-known/openid-configuration"

/** `resource` parameter for ChatGPT: the API the token is used against. */
const val CHATGPT_RESOURCE = "https://api.openai.com/v1"

/** Client id sent on first registration; OpenAI issues a real client id in return. */
const val CHATGPT_DYNAMIC_CLIENT_ID = "dynamic_agent_client"

/** Scope that spends the user's ChatGPT plan (plan usage is opt-in). */
const val CHATGPT_SHARING_SCOPE = "chatgpt.tokens.use.direct"

/**
 * Scopes for the ChatGPT sign-in request, in request order. `offline_access`
 * requests a refresh token; `resource.invoke` with
 * `chatgpt.tokens.use.direct` requests API access using the user's plan.
 * Verbatim from the SIWC flow (see `SiwcBrainAdapter.SIWC_SCOPES`).
 */
val CHATGPT_SCOPES: List<String> = listOf(
    "openid",
    "profile",
    "email",
    "offline_access",
    "resource.invoke",
    "chatgpt.tokens.use.direct",
)

/**
 * The vendor table. ChatGPT (dynamic registration, SIWC) plus Gemini
 * (fixed client id, login-only — see below).
 *
 * Qwen / Kimi / Grok are NOT here: they use the RFC 8628 device-code
 * flow, not authorization-code + loopback, so they live in the
 * device-code table ([wherevercode.agent.brain.devicecode.
 * DeviceCodeConfigs]) with their own engine.
 *
 * Deliberately NOT in this table (verified 2026-11-12, do not add
 * without a re-verification — vendor policies change):
 * - Claude: Anthropic's Legal page expressly bans third parties from
 *   offering Claude.ai login, and from routing requests through
 *   Pro/Max credentials; enforced server-side since 2026-01.
 * - DeepSeek: no paid membership exists at all; inference is API Key
 *   only; no third-party OAuth.
 * - 智谱 (Zhipu): Qingyan membership is limited to its own apps and
 *   its terms expressly ban third-party use of the account; inference
 *   is API Key only (incl. GLM Coding Plan Key).
 * - Meta AI: no public API/OAuth for the Meta AI assistant; the Muse
 *   Code subscription is expressly "Muse Code CLI only".
 * - Copilot (Microsoft): Entra has no DCR; and Copilot is not a
 *   model-API product — no public doc says a Copilot subscription
 *   token can call a model API (inference is separately-billed
 *   Azure OpenAI).
 * - 文心一言 (Baidu): the membership token cannot call any API;
 *   Qianfan uses client_credentials with a confidential client.
 * - 腾讯混元 (Tencent Hunyuan): no public OAuth for Yuanbao/Hunyuan
 *   membership; inference is TokenHub API Key or CAM AK/SK.
 * - 豆包会员 (Doubao): no public OAuth; every folk path steals
 *   session cookies (excluded by rule).
 * - Workbuddy: membership-login flow unverified.
 *
 * Being able to log in is not the same as being able to run inference on
 * the membership token — that is verified vendor by vendor, separately,
 * and is not claimed by any entry here.
 */
object VendorOAuthConfigs {
    val CHATGPT: VendorOAuthConfig = VendorOAuthConfig(
        vendorId = "chatgpt",
        displayName = "ChatGPT",
        operatorName = "OpenAI",
        issuer = CHATGPT_ISSUER,
        discoveryUrl = CHATGPT_DISCOVERY_URL,
        authorizationUrl = CHATGPT_AUTHORIZATION_URL,
        tokenUrl = CHATGPT_TOKEN_URL,
        revocationUrl = CHATGPT_REVOCATION_URL,
        jwksUri = CHATGPT_JWKS_URI,
        scopes = CHATGPT_SCOPES,
        resource = CHATGPT_RESOURCE,
        dynamicClientId = CHATGPT_DYNAMIC_CLIENT_ID,
        callbackPath = "/auth/callback",
        sharingScope = CHATGPT_SHARING_SCOPE,
    )

    /**
     * Gemini (Google) — LOGIN-ONLY entry (2026-11-12, membership
     * verification). Login itself is documented standard OAuth 2.0 +
     * PKCE + loopback for Desktop apps, but Google has no DCR and no
     * usable well-known client id, so `fixedClientId` is null until a
     * V5 developer registers a V5-owned Desktop OAuth client in
     * Google Cloud Console and fills it in here. Registering is a
     * developer-side step; this code never registers on the user's
     * behalf and never reuses the Gemini CLI client id.
     *
     * Even after login, this entry CANNOT be used as an inference
     * brain (`inferenceSupported = false`): the membership token
     * cannot call the Gemini API; the internal Code Assist endpoint
     * is undocumented, its terms ban reuse, and its personal tier
     * stopped serving on 2026-06-18. The UI shows this entry as
     * "仅登录，不能当大脑用推理". The stable Gemini brain route
     * stays API Key (AI Studio) or Vertex AI.
     */
    val GEMINI: VendorOAuthConfig = VendorOAuthConfig(
        vendorId = "gemini",
        displayName = "Gemini",
        operatorName = "Google",
        issuer = "https://accounts.google.com",
        discoveryUrl = "https://accounts.google.com/.well-known/openid-configuration",
        authorizationUrl = "https://accounts.google.com/o/oauth2/v2/auth",
        tokenUrl = "https://oauth2.googleapis.com/token",
        revocationUrl = "https://oauth2.googleapis.com/revoke",
        jwksUri = "https://www.googleapis.com/oauth2/v3/certs",
        scopes = listOf(
            "https://www.googleapis.com/auth/cloud-platform",
            "https://www.googleapis.com/auth/userinfo.email",
            "https://www.googleapis.com/auth/userinfo.profile",
        ),
        resource = null,
        dynamicClientId = null,
        fixedClientId = null, // V5-owned Desktop OAuth client: register first, then fill in.
        callbackPath = "/oauth2callback",
        sharingScope = null,
        inferenceSupported = false,
    )

    /** All configured vendors. ChatGPT (usable brain) + Gemini (login-only). */
    val all: List<VendorOAuthConfig> get() = listOf(CHATGPT, GEMINI)
}
