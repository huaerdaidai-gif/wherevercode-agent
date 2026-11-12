package wherevercode.agent.brain.devicecode

import android.content.Context
import android.os.Build
import java.util.UUID

/**
 * One vendor's RFC 8628 device-code membership-login configuration
 * (2026-11-12, membership verification + add-all).
 *
 * The device-code engine ([DeviceCodeEngine]) implements the protocol
 * exactly once; everything vendor-specific lives in this table.
 * Adding a device-code vendor means adding one [DeviceCodeConfig]
 * entry — never a second copy of the flow.
 *
 * What belongs here (and only here): public endpoint URLs, the public
 * client id (no secret — device-code vendors here are public
 * clients), the scopes, the inference base URL, and the honesty
 * flags. No token, no secret, no login state: tokens live only in
 * Keystore-backed storage ([DeviceCodeTokenStore]).
 *
 * Compliance greys (write them into code AND UI copy, never hide):
 * - Qwen's client id comes from the official CLI open-source repo
 *   (QwenLM/qwen-code); Alibaba has not documented this OAuth client
 *   as open to third parties. If the vendor tightens it after V5
 *   goes open-source, this path breaks — keep the API Key route as
 *   the fallback.
 * - Grok's client id and scopes appear only in third-party source
 *   (docs.x.ai does not record them); xAI's backend has an allowlist
 *   and standard SuperGrok subscribers have been 403'd. Grok is
 *   experimental: the UI must say "可能 403、可能随时失效", and it
 *   is NOT listed in any outward "supported membership brains" list.
 * - Kimi's wording is "Kimi Code 订阅", never "kimi.com 会员通用":
 *   the relation between the web-chat subscription and the Kimi Code
 *   subscription is unclear (official issue #757).
 */
data class DeviceCodeConfig(
    /** Stable id used in code and logs, e.g. `"qwen"`. */
    val vendorId: String,
    /** Product name shown to the user, e.g. `"Qwen 会员"`. */
    val displayName: String,
    /** Authorization-server operator named in transport errors, e.g. `"阿里"`. */
    val operatorName: String,
    /** Device authorization endpoint (RFC 8628). */
    val deviceCodeUrl: String,
    /** Token endpoint. */
    val tokenUrl: String,
    /** Public client id (no secret). */
    val clientId: String,
    /** Scopes requested at device-code time (empty = send none, Kimi). */
    val scopes: List<String>,
    /** Inference base URL the membership token is sent to (Bearer). */
    val inferenceBaseUrl: String,
    /**
     * True when the vendor path is experimental / unendorsed: the UI
     * must show the experimental note and the vendor must not be
     * advertised as a supported membership brain.
     */
    val experimental: Boolean = false,
    /** User-facing experimental / risk note (shown when [experimental]). */
    val experimentalNote: String = "",
    /** User-facing compliance note (shown in the login dialog). */
    val complianceNote: String = "",
    /** True when this vendor's OAuth calls need extra headers (Kimi). */
    val needsDeviceHeaders: Boolean = false,
) {
    /** Keystore reference for this vendor's device-code tokens. */
    val authRef: String get() = "devicecode:$vendorId"

    /** Adapter id for this vendor's membership brain. */
    val adapterId: String get() = "devicecode-$vendorId"

    /** Brain id for this vendor's membership brain (same as vendor id). */
    val brainId: String get() = vendorId
}

/**
 * The device-code vendor table. Verified 2026-11-12 (see the
 * membership verification report); each entry's inference claim was
 * verified vendor by vendor — "can log in" is not "can run
 * inference", and both are recorded here.
 *
 * Deliberately NOT in this table (one reason each; do not add
 * without a re-verification):
 * - Claude: Anthropic's Legal page expressly bans third-party
 *   Claude.ai login (enforced server-side since 2026-01).
 * - DeepSeek: no paid membership exists; API Key only.
 * - 智谱: membership limited to its own apps, terms ban third-party use.
 * - Meta AI: no public API/OAuth; Muse Code subscription is CLI-only.
 * - Copilot: Entra has no DCR and Copilot is not a model-API product.
 * - 文心一言: membership token cannot call any API (Qianfan is a
 *   confidential client_credentials flow).
 * - 腾讯混元: no public OAuth for the membership.
 * - 豆包会员: no public OAuth; folk paths steal cookies (excluded).
 * - Gemini: authorization-code + loopback, not device-code — it lives
 *   in [wherevercode.agent.brain.oauth.VendorOAuthConfigs] as a
 *   login-only entry with a fixed client id.
 */
object DeviceCodeConfigs {
    val QWEN: DeviceCodeConfig = DeviceCodeConfig(
        vendorId = "qwen",
        displayName = "Qwen 会员",
        operatorName = "阿里",
        deviceCodeUrl = "https://chat.qwen.ai/api/v1/oauth2/device/code",
        tokenUrl = "https://chat.qwen.ai/api/v1/oauth2/token",
        // Public client id from the official CLI open-source repo
        // QwenLM/qwen-code (packages/core/src/qwen/qwenOAuth2.ts).
        // Alibaba has not documented this client as open to third
        // parties — see the compliance note below.
        clientId = "f0304373b74a44d2b584a3fb70ca9e56",
        scopes = listOf("openid", "profile", "email", "model.completion"),
        inferenceBaseUrl = "https://portal.qwen.ai/v1/chat/completions",
        complianceNote = "Qwen 的登录 client id 来自官方 CLI 开源仓库 " +
            "QwenLM/qwen-code，未见阿里以文档形式向第三方开放；" +
            "开源后若厂商收紧会断，届时请改用 API Key 大脑。",
    )

    val KIMI: DeviceCodeConfig = DeviceCodeConfig(
        vendorId = "kimi",
        displayName = "Kimi Code 订阅",
        operatorName = "Moonshot",
        deviceCodeUrl = "https://auth.kimi.com/api/oauth/device_authorization",
        tokenUrl = "https://auth.kimi.com/api/oauth/token",
        // Public client id from the official CLI open-source repo
        // MoonshotAI/kimi-cli (KLIP-14, status Implemented); no secret.
        clientId = "17e5f671-d194-4dfb-9706-5516cb48c098",
        scopes = emptyList(),
        inferenceBaseUrl = "https://api.kimi.com/coding/v1",
        needsDeviceHeaders = true,
        complianceNote = "此处是“Kimi Code 订阅”，不是“kimi.com 会员通用”：" +
            "网页聊天订阅与 Kimi Code 订阅的关系官方尚未说清（issue #757）。",
    )

    val GROK: DeviceCodeConfig = DeviceCodeConfig(
        vendorId = "grok-membership",
        displayName = "Grok 会员（实验性）",
        operatorName = "xAI",
        deviceCodeUrl = "https://auth.x.ai/oauth2/device/code",
        tokenUrl = "https://auth.x.ai/oauth2/token",
        // WARNING: this client id and the scopes below appear ONLY in
        // third-party source; docs.x.ai does not record them, and
        // xAI's backend has an allowlist (standard SuperGrok
        // subscribers have been 403'd). Experimental: the UI must show
        // "可能 403、可能随时失效", and this vendor is NOT in any
        // outward "supported membership brains" list.
        clientId = "grok-cli",
        scopes = listOf("grok-cli:access", "api:access"),
        inferenceBaseUrl = "https://api.x.ai/v1",
        experimental = true,
        experimentalNote = "实验性：client id 与 scope 只见于第三方源码，" +
            "docs.x.ai 未记载；xAI 后端有 allowlist，可能 403、可能随时失效。",
        complianceNote = "Grok 会员登录是实验性的：可能 403、可能随时失效；" +
            "不建议作为主力大脑，稳妥路线是 Grok API Key 或已登录的 Example Agent CLI。",
    )

    /** All device-code vendors, in display order. */
    val all: List<DeviceCodeConfig> get() = listOf(QWEN, KIMI, GROK)

    /** Config for a vendor id, or null. */
    fun byId(vendorId: String): DeviceCodeConfig? = all.firstOrNull { it.vendorId == vendorId }

    /** True when the brain id belongs to a device-code membership brain. */
    fun isDeviceCodeBrainId(brainId: String): Boolean = byId(brainId) != null

    /**
     * Brain ids of the membership brains this table seeds
     * (BrainBootstrap seeds exactly these four membership ids: the
     * three here plus Gemini, which is an authorization-code entry).
     */
    val brainIds: List<String> get() = all.map { it.brainId }
}

/**
 * Kimi's `X-Msh-*` device headers (KLIP-14): platform, client
 * version, device name/model, and a stable per-install device UUID.
 * The UUID is not a secret and not a credential — it is a stable
 * device identifier the vendor asks for; it lives in plain
 * SharedPreferences (never in Keystore, never in a BrainConfig).
 */
object KimiDeviceHeaders {
    private const val PREFS_NAME = "wherevercode_devicecode"
    private const val KEY_DEVICE_UUID = "device_uuid"

    fun forContext(context: Context): Map<String, String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var uuid = prefs.getString(KEY_DEVICE_UUID, null)
        if (uuid.isNullOrBlank()) {
            uuid = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_UUID, uuid).apply()
        }
        val version = try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
                ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
        return mapOf(
            "X-Msh-Platform" to "android",
            "X-Msh-Version" to version,
            "X-Msh-Device-Name" to Build.MODEL,
            "X-Msh-Device-Id" to uuid,
        )
    }
}
