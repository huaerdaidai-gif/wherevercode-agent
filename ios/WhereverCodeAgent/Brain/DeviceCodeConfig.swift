// DeviceCodeConfig.swift
// Ported from the Android app (brain/devicecode/DeviceCodeConfig.kt)
// — one vendor's RFC 8628 device-code membership-login
// configuration. The device-code engine (DeviceCodeEngine.swift)
// implements the protocol exactly once; everything vendor-specific
// lives in this table. Adding a device-code vendor means adding
// one entry here — never a second copy of the flow (the roster is
// a config table with no count cap by design).
//
// What belongs here (and only here): public endpoint URLs, the
// public client id (no secret — device-code vendors here are
// public clients), the scopes, the inference base URL, and the
// honesty flags. No token, no secret, no login state: tokens live
// only in Keychain-backed storage (DeviceCodeTokenStore.swift).
//
// Compliance greys (write them into code AND UI copy, never hide):
// - Qwen's client id comes from the official CLI open-source repo
//   (QwenLM/qwen-code); Alibaba has not documented this OAuth
//   client as open to third parties. If the vendor tightens it,
//   this path breaks — keep the API Key route as the fallback.
// - Grok's client id and scopes appear only in third-party source
//   (docs.x.ai does not record them); xAI's backend has an
//   allowlist and standard SuperGrok subscribers have been 403'd.
//   Grok is experimental: the UI must say "可能 403、可能随时失效",
//   and it is NOT listed in any outward "supported membership
//   brains" list.
// - Kimi's wording is "Kimi Code 订阅", never "kimi.com 会员通用":
//   the relation between the web-chat subscription and the Kimi
//   Code subscription is unclear (official issue #757).

import Foundation

public struct DeviceCodeConfig: Equatable, Sendable {
    /// Stable id used in code and logs, e.g. `"qwen"`.
    public let vendorId: String
    /// Product name shown to the user, e.g. `"Qwen 会员"`.
    public let displayName: String
    /// Authorization-server operator named in transport errors, e.g. `"阿里"`.
    public let operatorName: String
    /// Device authorization endpoint (RFC 8628).
    public let deviceCodeUrl: String
    /// Token endpoint.
    public let tokenUrl: String
    /// Public client id (no secret).
    public let clientId: String
    /// Scopes requested at device-code time (empty = send none, Kimi).
    public let scopes: [String]
    /// Inference base URL the membership token is sent to (Bearer).
    public let inferenceBaseUrl: String
    /// True when the vendor path is experimental / unendorsed: the
    /// UI must show the experimental note and the vendor must not
    /// be advertised as a supported membership brain.
    public let experimental: Bool
    /// User-facing experimental / risk note (shown when `experimental`).
    public let experimentalNote: String
    /// User-facing compliance note (shown in the login dialog).
    public let complianceNote: String
    /// True when this vendor's OAuth calls need extra headers (Kimi).
    public let needsDeviceHeaders: Bool

    public init(
        vendorId: String,
        displayName: String,
        operatorName: String,
        deviceCodeUrl: String,
        tokenUrl: String,
        clientId: String,
        scopes: [String],
        inferenceBaseUrl: String,
        experimental: Bool = false,
        experimentalNote: String = "",
        complianceNote: String = "",
        needsDeviceHeaders: Bool = false
    ) {
        self.vendorId = vendorId
        self.displayName = displayName
        self.operatorName = operatorName
        self.deviceCodeUrl = deviceCodeUrl
        self.tokenUrl = tokenUrl
        self.clientId = clientId
        self.scopes = scopes
        self.inferenceBaseUrl = inferenceBaseUrl
        self.experimental = experimental
        self.experimentalNote = experimentalNote
        self.complianceNote = complianceNote
        self.needsDeviceHeaders = needsDeviceHeaders
    }

    /// Keychain reference for this vendor's device-code tokens.
    public var authRef: String { "devicecode:\(vendorId)" }

    /// Adapter id for this vendor's membership brain.
    public var adapterId: String { "devicecode-\(vendorId)" }

    /// Brain id for this vendor's membership brain (same as vendor id).
    public var brainId: String { vendorId }
}

/// The device-code vendor table. Verified 2026-11-12 (see the
/// membership verification report); each entry's inference claim
/// was verified vendor by vendor — "can log in" is not "can run
/// inference", and both are recorded here.
///
/// Deliberately NOT in this table (one reason each; do not add
/// without a re-verification):
/// - Claude: Anthropic's Legal page expressly bans third-party
///   Claude.ai login (enforced server-side since 2026-01).
/// - DeepSeek: no paid membership exists; API Key only.
/// - 智谱: membership limited to its own apps, terms ban third-party use.
/// - Meta AI: no public API/OAuth; Muse Spark subscription is CLI-only.
/// - Copilot: Entra has no DCR and Copilot is not a model-API product.
/// - 文心一言: membership token cannot call any API (Qianfan is a
///   confidential client_credentials flow).
/// - 腾讯混元: no public OAuth for the membership.
/// - 豆包会员: no public OAuth; folk paths steal cookies (excluded).
/// - Gemini: authorization-code + loopback, not device-code — it
///   lives in VendorOAuthConfig.swift as a login-only entry with a
///   fixed client id.
public enum DeviceCodeConfigs {

    public static let qwen = DeviceCodeConfig(
        vendorId: "qwen",
        displayName: "Qwen 会员",
        operatorName: "阿里",
        deviceCodeUrl: "https://chat.qwen.ai/api/v1/oauth2/device/code",
        tokenUrl: "https://chat.qwen.ai/api/v1/oauth2/token",
        // Public client id from the official CLI open-source repo
        // QwenLM/qwen-code (packages/core/src/qwen/qwenOAuth2.ts).
        // Alibaba has not documented this client as open to third
        // parties — see the compliance note below.
        clientId: "f0304373b74a44d2b584a3fb70ca9e56",
        scopes: ["openid", "profile", "email", "model.completion"],
        inferenceBaseUrl: "https://portal.qwen.ai/v1/chat/completions",
        complianceNote: "Qwen 的登录 client id 来自官方 CLI 开源仓库 "
            + "QwenLM/qwen-code，未见阿里以文档形式向第三方开放；"
            + "开源后若厂商收紧会断，届时请改用 API Key 大脑。"
    )

    public static let kimi = DeviceCodeConfig(
        vendorId: "kimi",
        displayName: "Kimi Code 订阅",
        operatorName: "Moonshot",
        deviceCodeUrl: "https://auth.kimi.com/api/oauth/device_authorization",
        tokenUrl: "https://auth.kimi.com/api/oauth/token",
        // Public client id from the official CLI open-source repo
        // MoonshotAI/kimi-cli (KLIP-14, status Implemented); no secret.
        clientId: "17e5f671-d194-4dfb-9706-5516cb48c098",
        scopes: [],
        inferenceBaseUrl: "https://api.kimi.com/coding/v1",
        complianceNote: "此处是“Kimi Code 订阅”，不是“kimi.com 会员通用”："
            + "网页聊天订阅与 Kimi Code 订阅的关系官方尚未说清（issue #757）。",
        needsDeviceHeaders: true
    )

    public static let grok = DeviceCodeConfig(
        vendorId: "grok-membership",
        displayName: "Grok 会员（实验性）",
        operatorName: "xAI",
        deviceCodeUrl: "https://auth.x.ai/oauth2/device/code",
        tokenUrl: "https://auth.x.ai/oauth2/token",
        // WARNING: this client id and the scopes below appear ONLY
        // in third-party source; docs.x.ai does not record them,
        // and xAI's backend has an allowlist (standard SuperGrok
        // subscribers have been 403'd). Experimental: the UI must
        // show "可能 403、可能随时失效", and this vendor is NOT in
        // any outward "supported membership brains" list.
        clientId: "grok-cli",
        scopes: ["grok-cli:access", "api:access"],
        inferenceBaseUrl: "https://api.x.ai/v1",
        experimental: true,
        experimentalNote: "实验性：client id 与 scope 只见于第三方源码，"
            + "docs.x.ai 未记载；xAI 后端有 allowlist，可能 403、可能随时失效。",
        complianceNote: "Grok 会员登录是实验性的：可能 403、可能随时失效；"
            + "不建议作为主力大脑，稳妥路线是 Grok API Key 或已登录的 Example Agent CLI。"
    )

    /// All device-code vendors, in display order.
    public static let all: [DeviceCodeConfig] = [qwen, kimi, grok]

    /// Config for a vendor id, or nil.
    public static func byId(_ vendorId: String) -> DeviceCodeConfig? {
        all.first { $0.vendorId == vendorId }
    }

    /// True when the brain id belongs to a device-code membership brain.
    public static func isDeviceCodeBrainId(_ brainId: String) -> Bool {
        byId(brainId) != nil
    }

    /// Brain ids of the membership brains this table seeds
    /// (BrainBootstrap seeds exactly these four membership ids: the
    /// three here plus Gemini, which is an authorization-code entry).
    public static var brainIds: [String] { all.map(\.brainId) }
}

/// Kimi's `X-Msh-*` device headers (KLIP-14): platform, client
/// version, device name/model, and a stable per-install device
/// UUID. The UUID is not a secret and not a credential — it is a
/// stable device identifier the vendor asks for; it lives in plain
/// UserDefaults (never in Keychain, never in a BrainConfig).
public enum KimiDeviceHeaders {
    private static let uuidKey = "wherevercode_devicecode.device_uuid"

    /// Device model name for `X-Msh-Device-Name`. The app layer
    /// sets this from UIDevice at startup; the Brain layer itself
    /// stays UIKit-free, so the default is the plain platform name.
    public static var deviceName: String = "iOS"

    public static func make() -> [String: String] {
        let defaults = UserDefaults.standard
        var uuid = defaults.string(forKey: uuidKey)
        if uuid == nil || uuid!.isEmpty {
            uuid = UUID().uuidString
            defaults.set(uuid, forKey: uuidKey)
        }
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String
            ?? "unknown"
        return [
            "X-Msh-Platform": "ios",
            "X-Msh-Version": version,
            "X-Msh-Device-Name": deviceName,
            "X-Msh-Device-Id": uuid ?? "",
        ]
    }
}
