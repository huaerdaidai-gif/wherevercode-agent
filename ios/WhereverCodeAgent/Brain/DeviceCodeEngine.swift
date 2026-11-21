// DeviceCodeEngine.swift
// Ported from the Android app (brain/devicecode/
// DeviceCodeEngine.kt) — the RFC 8628 device-code flow engine.
// One engine covers Qwen + Kimi + Grok: the protocol is
// implemented exactly once, here; everything vendor-specific
// comes from the DeviceCodeConfig passed in (endpoints, client
// id, scopes, inference base, device headers).
//
// Flow (the caller orchestrates the user-facing half):
// 1. requestDeviceCode — POST device/code, get `user_code` +
//    `verification_uri_complete`; the caller shows them and the
//    user authorizes in a browser on any device.
// 2. pollForToken — poll the token endpoint every `interval`
//    seconds with `grant_type=device_code` + the PKCE verifier
//    until the user authorizes (or the code expires / is denied).
// 3. refreshTokens — `grant_type=refresh_token` when the access
//    token expires.
//
// PKCE (S256) is sent on both steps: `code_challenge` with the
// device-code request, `code_verifier` with the token request
// (OAuthPkce.swift — the same helper the authorization-code
// engine uses). No client secret is sent for any vendor here:
// all three are public clients.
//
// Error mapping (Android caliber): network errors and 5xx/429
// become retryable transport errors; `expired_token` /
// `access_denied` / `invalid_grant` / `invalid_client` become
// non-retryable auth failures and invoke `onGrantDead` (the
// grant is dead — the caller clears its stored tokens);
// `authorization_pending` / `slow_down` are not errors — the
// poll loop handles them. Errors are thrown as BrainErrorInfo
// (the iOS brain layer's error value). No token value is ever
// included in a message. Messages use `config.displayName` /
// `config.operatorName`.
//
// The engine is stateless: the caller owns the pending attempt
// (device code + PKCE verifier) and the stored tokens
// (DeviceCodeTokenStore.swift). This layer produces no AI text.

import Foundation

/// One device-code response (RFC 8628 §3.2): what the server
/// returns for `POST device/code`, and what the user is shown.
public struct DeviceCodeResponse: Equatable, Sendable {
    public let deviceCode: String
    public let userCode: String
    public let verificationUri: String
    public let verificationUriComplete: String
    public let expiresInSeconds: Int64
    public let intervalSeconds: Int64

    public init(
        deviceCode: String,
        userCode: String,
        verificationUri: String,
        verificationUriComplete: String,
        expiresInSeconds: Int64,
        intervalSeconds: Int64
    ) {
        self.deviceCode = deviceCode
        self.userCode = userCode
        self.verificationUri = verificationUri
        self.verificationUriComplete = verificationUriComplete
        self.expiresInSeconds = expiresInSeconds
        self.intervalSeconds = intervalSeconds
    }
}

/// One token-endpoint response for the device-code / refresh grants.
public struct DeviceCodeTokens: Equatable, Sendable {
    public let accessToken: String
    public let refreshToken: String?
    public let expiresInSeconds: Int64
    public let scope: String
    /// Epoch millis when the tokens were obtained (for expiry math).
    public let obtainedAtMillis: Int64

    public init(
        accessToken: String,
        refreshToken: String?,
        expiresInSeconds: Int64,
        scope: String,
        obtainedAtMillis: Int64
    ) {
        self.accessToken = accessToken
        self.refreshToken = refreshToken
        self.expiresInSeconds = expiresInSeconds
        self.scope = scope
        self.obtainedAtMillis = obtainedAtMillis
    }

    /// True when the access token is expired or about to expire
    /// (60s leeway: a token that dies mid-turn is a failed turn).
    /// A token with no refresh token and expired is dead — the
    /// user must log in again.
    public func isExpired(nowMillis: Int64, leewaySeconds: Int64 = 60) -> Bool {
        obtainedAtMillis + max(expiresInSeconds - leewaySeconds, 0) * 1_000 <= nowMillis
    }

    /// True when a refresh attempt is possible at all.
    public var refreshable: Bool {
        !(refreshToken ?? "").trimmingCharacters(in: .whitespaces).isEmpty
    }

    public func withRefreshToken(_ token: String?) -> DeviceCodeTokens {
        DeviceCodeTokens(
            accessToken: accessToken,
            refreshToken: token,
            expiresInSeconds: expiresInSeconds,
            scope: scope,
            obtainedAtMillis: obtainedAtMillis)
    }
}

public final class DeviceCodeEngine {
    public let config: DeviceCodeConfig
    private let http: OAuthHttpTransport
    private let clockMillis: () -> Int64
    private let sleeper: (Int64) async -> Void

    public init(
        config: DeviceCodeConfig,
        http: OAuthHttpTransport,
        clockMillis: @escaping () -> Int64 = {
            Int64(Date().timeIntervalSince1970 * 1_000)
        },
        sleeper: @escaping (Int64) async -> Void = { millis in
            try? await Task.sleep(nanoseconds: UInt64(max(millis, 0)) * 1_000_000)
        }
    ) {
        self.config = config
        self.http = http
        self.clockMillis = clockMillis
        self.sleeper = sleeper
    }

    // MARK: - Step 1: device code

    /// Request a device code. `codeChallenge` is the S256 PKCE
    /// challenge for the verifier the caller keeps for the token
    /// request. `extraHeaders` carries vendor device headers
    /// (Kimi `X-Msh-*`); empty for vendors that need none.
    public func requestDeviceCode(
        codeChallenge: String,
        extraHeaders: [String: String] = [:]
    ) async throws -> DeviceCodeResponse {
        var params: [(String, String)] = [("client_id", config.clientId)]
        if !config.scopes.isEmpty {
            params.append(("scope", config.scopes.joined(separator: " ")))
        }
        params.append(("code_challenge", codeChallenge))
        params.append(("code_challenge_method", "S256"))
        let response: OAuthHttpResponse
        do {
            response = try await http.postForm(
                url: config.deviceCodeUrl, params: params, headers: extraHeaders)
        } catch let error as BrainErrorInfo {
            throw error
        } catch {
            throw BrainErrorInfo(
                code: "transport_error",
                message: "连接 \(config.operatorName) 设备码端点失败。",
                retryable: true)
        }
        if !(200 ... 299).contains(response.status) {
            let error = Self.parseErrorCode(response.body)
            if (500 ... 599).contains(response.status) || response.status == 429 {
                throw BrainErrorInfo(
                    code: "transport_error",
                    message: "\(config.operatorName) 设备码端点暂不可用（HTTP \(response.status)），可稍后重试。",
                    retryable: true)
            }
            throw BrainErrorInfo(
                code: error ?? "auth_failed",
                message: "\(config.displayName) 设备码请求失败"
                    + (error.map { "（\($0)）" } ?? "（HTTP \(response.status)）") + "。",
                retryable: false)
        }
        guard let json = Self.parseJson(response.body) else {
            throw BrainErrorInfo(
                code: "transport_error",
                message: "\(config.operatorName) 设备码端点返回了无法解析的响应。",
                retryable: true)
        }
        let deviceCode = Self.string(json, "device_code")
        let userCode = Self.string(json, "user_code")
        let verificationUri = Self.string(json, "verification_uri")
        var verificationUriComplete = Self.string(json, "verification_uri_complete")
        if verificationUriComplete.isEmpty { verificationUriComplete = verificationUri }
        if deviceCode.isEmpty || userCode.isEmpty || verificationUri.isEmpty {
            throw BrainErrorInfo(
                code: "transport_error",
                message: "\(config.operatorName) 设备码端点返回了不完整的响应。",
                retryable: true)
        }
        return DeviceCodeResponse(
            deviceCode: deviceCode,
            userCode: userCode,
            verificationUri: verificationUri,
            verificationUriComplete: verificationUriComplete,
            expiresInSeconds: Self.int64(
                json, "expires_in", Self.defaultDeviceCodeExpiresSeconds),
            intervalSeconds: max(
                Self.int64(json, "interval", Self.defaultPollIntervalSeconds),
                Self.minPollIntervalSeconds))
    }

    // MARK: - Step 2: poll for tokens

    /// Poll the token endpoint until the user authorizes. Sleeps
    /// `intervalSeconds` between attempts (grown by 5s on
    /// `slow_down`, per RFC 8628 §3.5); gives up on
    /// `expired_token` / `access_denied` / `invalid_grant` /
    /// `invalid_client`, and when the device code's own lifetime
    /// runs out. `isCancelled` is checked before every attempt so
    /// the user can stop waiting. Returns the token set (with
    /// `obtainedAtMillis` stamped from the clock).
    public func pollForToken(
        deviceCode: String,
        codeVerifier: String,
        intervalSeconds: Int64,
        expiresInSeconds: Int64,
        extraHeaders: [String: String] = [:],
        isCancelled: () -> Bool = { false },
        onGrantDead: () -> Void = {}
    ) async throws -> DeviceCodeTokens {
        let deadlineMillis = clockMillis() + expiresInSeconds * 1_000
        var intervalMillis = max(intervalSeconds, Self.minPollIntervalSeconds) * 1_000
        while true {
            if isCancelled() || Task.isCancelled {
                throw BrainErrorInfo(
                    code: "device_code_cancelled",
                    message: "已取消\(config.displayName)登录。",
                    retryable: false)
            }
            if clockMillis() >= deadlineMillis {
                onGrantDead()
                throw BrainErrorInfo(
                    code: "expired_token",
                    message: "\(config.displayName) 设备码已过期，请重新开始登录。",
                    retryable: false)
            }
            let params: [(String, String)] = [
                ("grant_type", Self.deviceCodeGrantType),
                ("device_code", deviceCode),
                ("client_id", config.clientId),
                ("code_verifier", codeVerifier),
            ]
            let response: OAuthHttpResponse
            do {
                response = try await http.postForm(
                    url: config.tokenUrl, params: params, headers: extraHeaders)
            } catch let error as BrainErrorInfo {
                throw error
            } catch {
                throw BrainErrorInfo(
                    code: "transport_error",
                    message: "连接 \(config.operatorName) 令牌端点失败。",
                    retryable: true)
            }
            // Error first, status second: RFC 8628 §3.5 signals
            // pending/slow_down/expired/denied with HTTP 400 + an
            // `error` field, but some servers answer 200 with an
            // `error` body instead — a 200 that carries `error` is
            // never a token grant, so it is handled here too.
            if let error = Self.parseErrorCode(response.body) {
                switch error {
                case "authorization_pending":
                    await sleeper(intervalMillis)
                case "slow_down":
                    intervalMillis += Self.slowDownExtraMillis
                    await sleeper(intervalMillis)
                case "expired_token":
                    onGrantDead()
                    throw BrainErrorInfo(
                        code: error,
                        message: "\(config.displayName) 设备码已过期，请重新开始登录。",
                        retryable: false)
                case "access_denied":
                    onGrantDead()
                    throw BrainErrorInfo(
                        code: error,
                        message: "\(config.displayName) 登录被拒绝：请确认是在你自己的账号上授权。",
                        retryable: false)
                case "invalid_grant", "invalid_client":
                    onGrantDead()
                    throw BrainErrorInfo(
                        code: error,
                        message: "\(config.displayName) 授权已失效（\(error)），请重新登录。",
                        retryable: false)
                default:
                    if (500 ... 599).contains(response.status) || response.status == 429 {
                        throw BrainErrorInfo(
                            code: "transport_error",
                            message: "\(config.operatorName) 令牌端点暂不可用（HTTP \(response.status)），可稍后重试。",
                            retryable: true)
                    }
                    throw BrainErrorInfo(
                        code: error,
                        message: "\(config.displayName) 令牌请求失败（\(error)）。",
                        retryable: false)
                }
                continue
            }
            if (200 ... 299).contains(response.status) {
                return try parseTokenResponse(response.body)
            }
            if (500 ... 599).contains(response.status) || response.status == 429 {
                throw BrainErrorInfo(
                    code: "transport_error",
                    message: "\(config.operatorName) 令牌端点暂不可用（HTTP \(response.status)），可稍后重试。",
                    retryable: true)
            }
            throw BrainErrorInfo(
                code: "auth_failed",
                message: "\(config.displayName) 令牌请求失败（HTTP \(response.status)）。",
                retryable: false)
        }
    }

    // MARK: - Step 3: refresh

    /// Refresh a grant. POSTs a form-encoded
    /// `grant_type=refresh_token` grant. A missing refresh token
    /// is a caller bug (check `refreshable` first); an
    /// `invalid_grant` here means the grant is dead and invokes
    /// `onGrantDead`.
    public func refreshTokens(
        refreshToken: String,
        extraHeaders: [String: String] = [:],
        onGrantDead: () -> Void = {}
    ) async throws -> DeviceCodeTokens {
        let params: [(String, String)] = [
            ("grant_type", "refresh_token"),
            ("refresh_token", refreshToken),
            ("client_id", config.clientId),
        ]
        let response: OAuthHttpResponse
        do {
            response = try await http.postForm(
                url: config.tokenUrl, params: params, headers: extraHeaders)
        } catch let error as BrainErrorInfo {
            throw error
        } catch {
            throw BrainErrorInfo(
                code: "transport_error",
                message: "连接 \(config.operatorName) 令牌端点失败。",
                retryable: true)
        }
        // Error first, status second (same rule as pollForToken):
        // a 200 carrying an `error` field is never a token grant.
        if let error = Self.parseErrorCode(response.body) {
            if (500 ... 599).contains(response.status) || response.status == 429 {
                throw BrainErrorInfo(
                    code: "transport_error",
                    message: "\(config.operatorName) 令牌端点暂不可用（HTTP \(response.status)），可稍后重试。",
                    retryable: true)
            }
            if error == "invalid_grant" || error == "invalid_client" {
                onGrantDead()
                throw BrainErrorInfo(
                    code: error,
                    message: "\(config.displayName) 授权已失效（\(error)），请重新登录。",
                    retryable: false)
            }
            throw BrainErrorInfo(
                code: error,
                message: "\(config.displayName) 令牌刷新失败（\(error)）。",
                retryable: false)
        }
        if (200 ... 299).contains(response.status) {
            return try parseTokenResponse(response.body)
        }
        if (500 ... 599).contains(response.status) || response.status == 429 {
            throw BrainErrorInfo(
                code: "transport_error",
                message: "\(config.operatorName) 令牌端点暂不可用（HTTP \(response.status)），可稍后重试。",
                retryable: true)
        }
        throw BrainErrorInfo(
            code: "auth_failed",
            message: "\(config.displayName) 令牌刷新失败（HTTP \(response.status)）。",
            retryable: false)
    }

    // MARK: - Parsing

    private func parseTokenResponse(_ body: String) throws -> DeviceCodeTokens {
        guard let json = Self.parseJson(body) else {
            throw BrainErrorInfo(
                code: "transport_error",
                message: "\(config.operatorName) 令牌端点返回了无法解析的响应。",
                retryable: true)
        }
        let accessToken = Self.string(json, "access_token")
        if accessToken.isEmpty {
            throw BrainErrorInfo(
                code: "transport_error",
                message: "\(config.operatorName) 令牌端点返回了不完整的响应。",
                retryable: true)
        }
        let refresh = Self.string(json, "refresh_token")
        return DeviceCodeTokens(
            accessToken: accessToken,
            refreshToken: refresh.isEmpty ? nil : refresh,
            expiresInSeconds: Self.int64(json, "expires_in", Self.defaultExpiresInSeconds),
            scope: Self.string(json, "scope"),
            obtainedAtMillis: clockMillis())
    }

    static func parseJson(_ body: String) -> [String: Any]? {
        guard let data = body.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data),
              let dict = object as? [String: Any]
        else { return nil }
        return dict
    }

    static func parseErrorCode(_ body: String) -> String? {
        guard let json = parseJson(body) else { return nil }
        let error = string(json, "error")
        return error.isEmpty ? nil : error
    }

    static func string(_ json: [String: Any], _ key: String) -> String {
        (json[key] as? String) ?? ""
    }

    static func int64(_ json: [String: Any], _ key: String, _ fallback: Int64) -> Int64 {
        if let number = json[key] as? NSNumber { return number.int64Value }
        if let text = json[key] as? String, let value = Int64(text) { return value }
        return fallback
    }

    // MARK: - Constants

    /// RFC 8628 device-code grant type.
    public static let deviceCodeGrantType =
        "urn:ietf:params:oauth:grant-type:device_code"

    /// Server omitted `expires_in`: device codes live 10 minutes by default.
    public static let defaultDeviceCodeExpiresSeconds: Int64 = 600

    /// Server omitted `interval`: poll every 5 seconds by default.
    public static let defaultPollIntervalSeconds: Int64 = 5

    /// Never poll faster than once per second, whatever the server says.
    public static let minPollIntervalSeconds: Int64 = 1

    /// `slow_down` grows the poll interval by 5 seconds (RFC 8628 §3.5).
    public static let slowDownExtraMillis: Int64 = 5_000

    /// Token `expires_in` default (Android: OAuthEngine's default).
    public static let defaultExpiresInSeconds: Int64 = 3_600
}
