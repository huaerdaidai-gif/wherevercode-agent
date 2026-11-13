// WhereverCode Agent — iOS MVP
//
// Keychain storage for the platform Agent token.
// The token is stored ONLY here: never in UserDefaults, never in a file,
// never in a log line, never in an error message.

import Foundation
import Security

public enum KeychainError: Error, Equatable {
    case notFound
    case unexpectedStatus(OSStatus)
    case unexpectedData

    public var message: String {
        switch self {
        case .notFound: return "未保存平台 Token。"
        case .unexpectedStatus(let status): return "Keychain 操作失败（状态 \(status)）。"
        case .unexpectedData: return "Keychain 里的数据无法读取。"
        }
    }
}

/// Minimal Keychain wrapper for a single generic-password item.
public struct KeychainStore {
    #if os(iOS)
    static let accessibleAttribute: CFString = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
    #else
    static let accessibleAttribute: CFString = kSecAttrAccessibleWhenUnlocked
    #endif

    private let service: String
    private let account: String

    public init(service: String = "wherevercode.agent", account: String = "platform-agent-token") {
        self.service = service
        self.account = account
    }

    public func saveToken(_ token: String) throws {
        let data = Data(token.utf8)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            // This device only; never synced to iCloud, never readable
            // from a backup on another device. (On macOS the
            // ThisDeviceOnly variant does not exist, so the plain
            // WhenUnlocked class is used there; iOS always gets the
            // device-only variant.)
            kSecAttrAccessible as String: Self.accessibleAttribute,
        ]
        var status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            var addQuery = query
            addQuery.merge(attributes) { _, new in new }
            status = SecItemAdd(addQuery as CFDictionary, nil)
        }
        guard status == errSecSuccess else {
            throw KeychainError.unexpectedStatus(status)
        }
    }

    public func loadToken() throws -> String {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status != errSecItemNotFound else { throw KeychainError.notFound }
        guard status == errSecSuccess else { throw KeychainError.unexpectedStatus(status) }
        guard let data = item as? Data, let token = String(data: data, encoding: .utf8) else {
            throw KeychainError.unexpectedData
        }
        return token
    }

    public func deleteToken() throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeychainError.unexpectedStatus(status)
        }
    }

    /// Non-throwing presence check for UI state (never returns the token).
    public var hasToken: Bool {
        (try? loadToken()) != nil
    }
}
