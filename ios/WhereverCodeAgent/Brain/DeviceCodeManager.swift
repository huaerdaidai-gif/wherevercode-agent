// DeviceCodeTokenStore.swift + DeviceCodeManager.swift
// Ported from the Android app (brain/devicecode/
// DeviceCodeTokenStore.kt and DeviceCodeManager.kt).
//
// Tokens live ONLY in the token store: one JSON blob per vendor
// under the vendor's authRef (`"devicecode:<vendorId>"`) in the
// same Keychain-backed store as every other brain credential
// (BrainKeyStore). The config holds only the authRef, never a
// token value; deletion (clearTokens, called by the
// brain-delete path) wipes the blob, so deleting the brain
// deletes the login with it — no residue, no resurrection.

import Foundation

public final class DeviceCodeTokenStore {
    private let keyStore: BrainKeyStore
    private let authRef: String

    public init(keyStore: BrainKeyStore = BrainKeyStore(), authRef: String) {
        self.keyStore = keyStore
        self.authRef = authRef
    }

    public func saveTokens(_ tokens: DeviceCodeTokens) {
        try? keyStore.putSecret(authRef: authRef, value: Self.toJson(tokens))
    }

    public func loadTokens() -> DeviceCodeTokens? {
        guard let raw = keyStore.getSecret(authRef: authRef) else { return nil }
        return Self.fromJson(raw)
    }

    public func hasTokens() -> Bool { loadTokens() != nil }

    public func clearTokens() {
        keyStore.deleteSecret(authRef: authRef)
    }

    static func toJson(_ tokens: DeviceCodeTokens) -> String {
        var obj: [String: Any] = [
            "access_token": tokens.accessToken,
            "expires_in": NSNumber(value: tokens.expiresInSeconds),
            "scope": tokens.scope,
            "obtained_at_millis": NSNumber(value: tokens.obtainedAtMillis),
        ]
        if let refresh = tokens.refreshToken {
            obj["refresh_token"] = refresh
        }
        guard let data = try? JSONSerialization.data(withJSONObject: obj),
              let text = String(data: data, encoding: .utf8)
        else { return "{}" }
        return text
    }

    static func fromJson(_ raw: String) -> DeviceCodeTokens? {
        guard let json = DeviceCodeEngine.parseJson(raw) else { return nil }
        let accessToken = DeviceCodeEngine.string(json, "access_token")
        if accessToken.isEmpty { return nil }
        let refresh = DeviceCodeEngine.string(json, "refresh_token")
        return DeviceCodeTokens(
            accessToken: accessToken,
            refreshToken: refresh.isEmpty ? nil : refresh,
            expiresInSeconds: DeviceCodeEngine.int64(json, "expires_in", 3_600),
            scope: DeviceCodeEngine.string(json, "scope"),
            obtainedAtMillis: DeviceCodeEngine.int64(json, "obtained_at_millis", 0))
    }
}

/// One in-flight device-code login attempt: the server-issued
/// code plus the PKCE verifier the token request must present.
/// The caller (the settings UI) shows `response` to the user and
/// calls `DeviceCodeManager.awaitAuthorization` to wait for the
/// user to authorize in a browser. The verifier is never shown
/// to the user and never leaves the device except to the
/// vendor's own token endpoint.
public struct DeviceCodeLoginAttempt: Sendable {
    public let response: DeviceCodeResponse
    public let codeVerifier: String

    public init(response: DeviceCodeResponse, codeVerifier: String) {
        self.response = response
        self.codeVerifier = codeVerifier
    }
}

/// Device-code login orchestration for one vendor. The engine
/// (DeviceCodeEngine) owns the protocol; this class owns the
/// session: PKCE generation, the pending attempt, token
/// persistence (DeviceCodeTokenStore), and refresh-before-use.
///
/// No network, no account, no model call happens here beyond the
/// vendor's own OAuth endpoints. No token value is ever logged
/// or included in an error message.
public final class DeviceCodeManager {
    public let config: DeviceCodeConfig
    private let tokenStore: DeviceCodeTokenStore
    private let engine: DeviceCodeEngine
    private let clockMillis: () -> Int64
    private let deviceHeaders: () -> [String: String]

    public init(
        config: DeviceCodeConfig,
        tokenStore: DeviceCodeTokenStore,
        http: OAuthHttpTransport,
        clockMillis: @escaping () -> Int64 = {
            Int64(Date().timeIntervalSince1970 * 1_000)
        },
        deviceHeaders: @escaping () -> [String: String] = { [:] }
    ) {
        self.config = config
        self.tokenStore = tokenStore
        self.engine = DeviceCodeEngine(config: config, http: http, clockMillis: clockMillis)
        self.clockMillis = clockMillis
        self.deviceHeaders = deviceHeaders
    }

    /// True when any tokens are stored (even expired ones).
    public func hasTokens() -> Bool { tokenStore.hasTokens() }

    /// True when the vendor can be used right now: a live access
    /// token, or an expired one with a refresh token to renew it.
    /// Used by the "configured" check (the model still has to be
    /// filled in separately — see BrainBootstrap).
    public func isSignedIn() -> Bool {
        guard let tokens = tokenStore.loadTokens() else { return false }
        return !tokens.isExpired(nowMillis: clockMillis()) || tokens.refreshable
    }

    /// Begin a login: generate a fresh PKCE verifier/challenge
    /// and ask the vendor for a device code. The caller shows the
    /// attempt's response (user code + verification URL) to the
    /// user, then calls `awaitAuthorization`.
    public func beginLogin() async throws -> DeviceCodeLoginAttempt {
        let verifier = OAuthPkce.generateVerifier()
        let challenge = OAuthPkce.challengeFor(verifier: verifier)
        let response = try await engine.requestDeviceCode(
            codeChallenge: challenge, extraHeaders: deviceHeaders())
        return DeviceCodeLoginAttempt(response: response, codeVerifier: verifier)
    }

    /// Wait for the user to authorize in a browser, then save
    /// the tokens. Polls per the server's interval until
    /// authorization, expiry, denial, or cancellation. On grant
    /// death the stored tokens are cleared so a stale grant
    /// cannot linger.
    public func awaitAuthorization(
        attempt: DeviceCodeLoginAttempt,
        isCancelled: @escaping () -> Bool = { false }
    ) async throws -> DeviceCodeTokens {
        let tokens = try await engine.pollForToken(
            deviceCode: attempt.response.deviceCode,
            codeVerifier: attempt.codeVerifier,
            intervalSeconds: attempt.response.intervalSeconds,
            expiresInSeconds: attempt.response.expiresInSeconds,
            extraHeaders: deviceHeaders(),
            isCancelled: isCancelled,
            onGrantDead: { [tokenStore] in tokenStore.clearTokens() })
        tokenStore.saveTokens(tokens)
        return tokens
    }

    /// A fresh access token for an inference call: refresh first
    /// when the stored one is expired (or about to expire) and
    /// refreshable. Throws an auth failure when there is no
    /// usable grant — the caller reports "please log in again"
    /// and never retries with a dead token.
    public func ensureFreshAccessToken() async throws -> String {
        guard let tokens = tokenStore.loadTokens() else {
            throw BrainErrorInfo(
                code: "device_code_not_signed_in",
                message: "\(config.displayName) 未登录：请先登录。",
                retryable: false)
        }
        if !tokens.isExpired(nowMillis: clockMillis()) { return tokens.accessToken }
        if !tokens.refreshable {
            tokenStore.clearTokens()
            throw BrainErrorInfo(
                code: "device_code_expired",
                message: "\(config.displayName) 登录已过期，请重新登录。",
                retryable: false)
        }
        return try await refreshTokens().accessToken
    }

    /// Refresh the stored grant; clears the store when the grant is dead.
    public func refreshTokens() async throws -> DeviceCodeTokens {
        guard let tokens = tokenStore.loadTokens() else {
            throw BrainErrorInfo(
                code: "device_code_not_signed_in",
                message: "\(config.displayName) 未登录：请先登录。",
                retryable: false)
        }
        guard let refreshToken = tokens.refreshToken, !refreshToken.isEmpty else {
            throw BrainErrorInfo(
                code: "device_code_expired",
                message: "\(config.displayName) 登录已过期，请重新登录。",
                retryable: false)
        }
        let fresh = try await engine.refreshTokens(
            refreshToken: refreshToken,
            extraHeaders: deviceHeaders(),
            onGrantDead: { [tokenStore] in tokenStore.clearTokens() })
        // A refresh response may omit a rotated refresh token:
        // keep the old one rather than dropping the ability to
        // refresh.
        let kept = (fresh.refreshToken ?? "").isEmpty
            ? fresh.withRefreshToken(refreshToken)
            : fresh
        tokenStore.saveTokens(kept)
        return kept
    }

    /// Sign out: wipe the local tokens. There is no documented
    /// revocation endpoint for Qwen / Kimi / Grok device-code
    /// grants, so sign-out is local-only — the vendor-side grant
    /// stays valid until it expires; the user can revoke it in
    /// the vendor's own account page. (Deleting the brain also
    /// wipes the tokens; see BrainSettingsModel.delete.)
    public func signOut() {
        tokenStore.clearTokens()
    }
}
