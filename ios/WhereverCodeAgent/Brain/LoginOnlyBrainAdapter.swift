// VendorOAuthConfig.swift + LoginOnlyBrainAdapter.swift
// Ported from the Android app (brain/oauth/VendorOAuthConfig.kt
// and brain/devicecode/LoginOnlyBrainAdapter.kt).
//
// VendorOAuthConfig is the authorization-code (loopback) vendor
// table — the counterpart of DeviceCodeConfig.swift for vendors
// whose membership login is NOT RFC 8628 device-code. Only the
// Gemini entry is ported today: ChatGPT's authorization-code
// flow on iOS is the SIWC body (SiwcM2BrainAdapter), which has
// its own wiring and is not driven by this table.
//
// Gemini is login-only: a Google account OAuth token authorises
// Google API scopes, not the Gemini API (which only takes an API
// Key); the internal Code Assist endpoint is undocumented, its
// terms ban reuse, and its personal tier stopped serving on
// 2026-06-18. The UI shows this entry as "仅登录，不能当大脑用
// 推理". The stable Gemini brain route stays API Key (AI Studio)
// or Vertex AI.
//
// The adapter is driven by this config (fixed client id
// support): until a V5 developer registers a V5-owned Desktop
// OAuth client and fills `fixedClientId`, even login is
// unavailable and the UI says so.

import Foundation

public struct VendorOAuthConfig: Equatable, Sendable {
    public let vendorId: String
    public let displayName: String
    public let operatorName: String
    public let issuer: String
    public let discoveryUrl: String
    public let authorizationUrl: String
    public let tokenUrl: String
    public let revocationUrl: String?
    public let jwksUri: String
    public let scopes: [String]
    public let resource: String?
    public let dynamicClientId: String?
    /// V5-owned Desktop OAuth client id; nil until one is
    /// registered and filled in — login is unavailable before that.
    public let fixedClientId: String?
    public let callbackPath: String
    public let sharingScope: String?
    /// False = login-only: signing in never makes it a brain.
    public let inferenceSupported: Bool

    public init(
        vendorId: String,
        displayName: String,
        operatorName: String,
        issuer: String,
        discoveryUrl: String,
        authorizationUrl: String,
        tokenUrl: String,
        revocationUrl: String?,
        jwksUri: String,
        scopes: [String],
        resource: String?,
        dynamicClientId: String?,
        fixedClientId: String?,
        callbackPath: String,
        sharingScope: String?,
        inferenceSupported: Bool
    ) {
        self.vendorId = vendorId
        self.displayName = displayName
        self.operatorName = operatorName
        self.issuer = issuer
        self.discoveryUrl = discoveryUrl
        self.authorizationUrl = authorizationUrl
        self.tokenUrl = tokenUrl
        self.revocationUrl = revocationUrl
        self.jwksUri = jwksUri
        self.scopes = scopes
        self.resource = resource
        self.dynamicClientId = dynamicClientId
        self.fixedClientId = fixedClientId
        self.callbackPath = callbackPath
        self.sharingScope = sharingScope
        self.inferenceSupported = inferenceSupported
    }
}

public enum VendorOAuthConfigs {

    public static let gemini = VendorOAuthConfig(
        vendorId: "gemini",
        displayName: "Gemini",
        operatorName: "Google",
        issuer: "https://accounts.google.com",
        discoveryUrl: "https://accounts.google.com/.well-known/openid-configuration",
        authorizationUrl: "https://accounts.google.com/o/oauth2/v2/auth",
        tokenUrl: "https://oauth2.googleapis.com/token",
        revocationUrl: "https://oauth2.googleapis.com/revoke",
        jwksUri: "https://www.googleapis.com/oauth2/v3/certs",
        scopes: [
            "https://www.googleapis.com/auth/cloud-platform",
            "https://www.googleapis.com/auth/userinfo.email",
            "https://www.googleapis.com/auth/userinfo.profile",
        ],
        resource: nil,
        dynamicClientId: nil,
        // V5-owned Desktop OAuth client: register first, then fill in.
        fixedClientId: nil,
        callbackPath: "/oauth2callback",
        sharingScope: nil,
        inferenceSupported: false
    )

    /// All configured vendors in this table (Gemini today).
    public static let all: [VendorOAuthConfig] = [gemini]
}

/// Login-only brain adapter (Gemini).
///
/// Some vendors let the user sign in but offer no membership
/// inference endpoint at all. Gemini is the case: a Google
/// account OAuth token authorises Google API scopes, not the
/// Gemini API (which only takes an API Key); the internal Code
/// Assist endpoint is undocumented, its terms ban reuse, and its
/// personal tier stopped serving on 2026-06-18. Registering this
/// adapter is what makes the login entry visible and deletable
/// in the brain list — but every inference call is refused here,
/// honestly, with the reason and the stable alternative (API
/// Key / Vertex AI). The brain can never be "configured" (the
/// settings layer reports it unusable) and can never be
/// switched to.
public final class LoginOnlyBrainAdapter: BrainAdapter {
    public let config: VendorOAuthConfig

    public init(config: VendorOAuthConfig) {
        self.config = config
    }

    public var adapterId: String { Self.adapterId }
    public var kind: BrainKind { .subscription }
    public var label: String { config.displayName }

    public func capabilities() -> BrainCapabilities {
        BrainCapabilities(
            streaming: false,
            toolCalling: false,
            vision: false,
            maxContextTokens: nil,
            notes: "\(config.displayName)仅登录，不能当大脑用推理："
                + "会员 token 调不了 \(config.displayName) API；"
                + "稳妥路线是 API Key 或 Vertex AI。")
    }

    public func validateCredentials() async -> Result<Void, Error> {
        .failure(BrainErrorInfo(
            code: "not_supported",
            message: Self.notABrainMessage,
            retryable: false))
    }

    /// A login-only brain has no model catalogue: empty list is honest.
    public func listModels() async -> Result<[ModelInfo], Error> {
        .success([])
    }

    public func chatStream(request: ChatRequest) -> AsyncThrowingStream<BrainEvent, Error> {
        AsyncThrowingStream { continuation in
            continuation.yield(.error(
                code: "not_supported",
                message: Self.notABrainMessage,
                retryable: false))
            continuation.yield(.done(finishReason: "error"))
            continuation.finish()
        }
    }

    /// Stable adapter id for the Gemini login-only brain.
    public static let adapterId = "oauth-gemini"

    public static let notABrainMessage =
        "Gemini 会员登录不能当大脑用推理：会员 token 调不了 Gemini API，"
        + "内部端点未文档化且条款禁止复用；请用 API Key（AI Studio）或 Vertex AI 作 Gemini 大脑。"
}
