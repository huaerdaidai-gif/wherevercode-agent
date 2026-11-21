// OAuthPkce.swift + DeviceCodeTransport.swift
// Ported from the Android app (brain/oauth/OAuthCrypto.kt's
// OAuthPkce and brain/oauth/OAuthHttpTransport.kt).
//
// PKCE (RFC 7636, S256): the verifier is 64 random bytes,
// base64url-encoded without padding; the challenge is the
// base64url-encoded SHA-256 of the verifier's ASCII bytes. The
// device-code engine sends the challenge with the device-code
// request and the verifier with the token request. No client
// secret is sent for any device-code vendor: all are public
// clients.

import CryptoKit
import Foundation

public enum OAuthPkce {

    public static func generateVerifier() -> String {
        var bytes = [UInt8](repeating: 0, count: 64)
        for i in bytes.indices {
            bytes[i] = UInt8.random(in: UInt8.min ... UInt8.max)
        }
        return base64UrlEncode(Data(bytes))
    }

    public static func challengeFor(verifier: String) -> String {
        let digest = SHA256.hash(data: Data(verifier.utf8))
        return base64UrlEncode(Data(digest))
    }

    public static func generateState() -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        for i in bytes.indices {
            bytes[i] = UInt8.random(in: UInt8.min ... UInt8.max)
        }
        return base64UrlEncode(Data(bytes))
    }

    static func base64UrlEncode(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}

/// One form-POST response: HTTP status + raw body text.
public struct OAuthHttpResponse: Equatable, Sendable {
    public let status: Int
    public let body: String

    public init(status: Int, body: String) {
        self.status = status
        self.body = body
    }
}

/// The form-POST transport the OAuth engines run on. Parameters
/// are ordered pairs (the Android engine builds a LinkedHashMap;
/// insertion order is preserved on the wire). Implementations
/// throw on transport-level failure (no HTTP response at all);
/// any HTTP response, including errors, comes back as a value.
public protocol OAuthHttpTransport: Sendable {
    func postForm(
        url: String,
        params: [(String, String)],
        headers: [String: String]
    ) async throws -> OAuthHttpResponse
}

/// URLSession-backed form transport. Form encoding follows the
/// classic `application/x-www-form-urlencoded` rules (space as
/// `+`, everything outside the unreserved set percent-encoded) —
/// the same wire shape as Android's URLEncoder-based transport.
public struct URLSessionOAuthHttpTransport: OAuthHttpTransport {
    private let session: URLSession

    public init(session: URLSession = .shared) {
        self.session = session
    }

    public func postForm(
        url: String,
        params: [(String, String)],
        headers: [String: String]
    ) async throws -> OAuthHttpResponse {
        guard let endpoint = URL(string: url) else {
            throw BrainErrorInfo(
                code: "transport_error",
                message: "OAuth 端点地址无效。",
                retryable: false)
        }
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.setValue(
            "application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        for (name, value) in headers {
            request.setValue(value, forHTTPHeaderField: name)
        }
        let body = params
            .map { "\(Self.formEncode($0.0))=\(Self.formEncode($0.1))" }
            .joined(separator: "&")
        request.httpBody = Data(body.utf8)
        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? -1
        return OAuthHttpResponse(
            status: status,
            body: String(data: data, encoding: .utf8) ?? "")
    }

    static func formEncode(_ value: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: ".-_*")
        return value
            .addingPercentEncoding(withAllowedCharacters: allowed)?
            .replacingOccurrences(of: "%20", with: "+")
            ?? value
    }
}
