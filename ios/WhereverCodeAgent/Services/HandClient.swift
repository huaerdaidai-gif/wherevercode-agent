import Foundation
import CryptoKit
import Security
#if canImport(Darwin)
import Darwin
#endif
#if canImport(Network)
import Network
#endif

// MARK: - Hand 协议数据模型

public struct RemoteRepoCheckout: Codable, Equatable, Sendable {
    public let path: String
    public let branch: String?
    public let commit: String?

    public init(path: String, branch: String? = nil, commit: String? = nil) {
        self.path = path
        self.branch = branch
        self.commit = commit
    }
}

public struct RemoteAgentInfo: Codable, Equatable, Identifiable, Sendable {
    public var id: String { agentId }
    public let agentId: String
    public let displayName: String
    public let kind: String
    public let capabilities: [String]
    public let folder: String
    public let available: Bool
    public let repos: [RemoteRepoCheckout]

    public init(
        agentId: String,
        displayName: String,
        kind: String = "agent",
        capabilities: [String] = ["dev"],
        folder: String = "",
        available: Bool = true,
        repos: [RemoteRepoCheckout] = []
    ) {
        self.agentId = agentId
        self.displayName = displayName
        self.kind = kind
        self.capabilities = capabilities
        self.folder = folder
        self.available = available
        self.repos = repos
    }
}

public struct RemoteHello: Equatable, Sendable {
    public let deviceId: String
    public let displayName: String
    public let publicKey: Data
    public let agents: [RemoteAgentInfo]
    public let clientStatus: String
    public let handVersion: String

    public var fingerprintSha256: String {
        SHA256.hash(data: publicKey).map { String(format: "%02x", $0) }.joined()
    }

    public init(
        deviceId: String,
        displayName: String,
        publicKey: Data,
        agents: [RemoteAgentInfo] = [],
        clientStatus: String = "",
        handVersion: String = ""
    ) {
        self.deviceId = deviceId
        self.displayName = displayName
        self.publicKey = publicKey
        self.agents = agents
        self.clientStatus = clientStatus
        self.handVersion = handVersion
    }
}

public struct RemoteFileEntry: Codable, Equatable, Identifiable, Sendable {
    public var id: String { name }
    public let name: String
    public let isDir: Bool

    enum CodingKeys: String, CodingKey {
        case name
        case isDir = "is_dir"
    }

    public init(name: String, isDir: Bool) {
        self.name = name
        self.isDir = isDir
    }
}

public enum PairConfirmResult: Equatable, Sendable {
    case approved
    case codeExpired(reason: String)
    case codeConsumed(reason: String)
    case codeWrong(reason: String)
    case clientLocked(reason: String)
    case revoked(reason: String)
    case rejected(code: String, reason: String)
    case failed(reason: String)
}

public struct DiscoveredPeer: Equatable, Identifiable, Sendable {
    public var id: String { deviceId }
    public let deviceId: String
    public let displayName: String
    public let host: String
    public let port: Int
    public let fingerprintSha256: String
    public let agents: [RemoteAgentInfo]
    public let clientStatus: String
}

public enum HandClientError: LocalizedError, Equatable {
    case connectionFailed(String)
    case timeout
    case frameTooLarge(Int)
    case invalidHandshake(String)
    case invalidSealedBlob
    case cryptoError(String)
    case serverRejected(code: String, message: String)
    case credentialError(String)

    public var errorDescription: String? {
        switch self {
        case .connectionFailed(let msg): return "连接失败：\(msg)"
        case .timeout: return "网络连接或响应超时"
        case .frameTooLarge(let sz): return "网络帧超过 4MB 上限（\(sz) 字节）"
        case .invalidHandshake(let msg): return "Hand 握手失败：\(msg)"
        case .invalidSealedBlob: return "密封信封格式损坏或验签失败"
        case .cryptoError(let msg): return "密码学协商失败：\(msg)"
        case .serverRejected(let code, let msg): return "远端拒绝（\(code)）：\(msg)"
        case .credentialError(let msg): return "任务凭证错误：\(msg)"
        }
    }
}

// MARK: - Hand 客户端密码学与任务凭证 (DeviceKeys)

public struct HandCrypto {
    public static let hkdfInfo = "wherevercode-cross-device/v1"
    public static let signingInfo = "wherevercode-credential-signing/v1"

    /// X25519 + HKDF-SHA256 会话密钥协商
    public static func deriveSessionKey(
        ownPrivate: Curve25519.KeyAgreement.PrivateKey,
        peerPublic: Curve25519.KeyAgreement.PublicKey
    ) throws -> SymmetricKey {
        let sharedSecret = try ownPrivate.sharedSecretFromKeyAgreement(with: peerPublic)
        let ownPubBytes = ownPrivate.publicKey.rawRepresentation
        let peerPubBytes = peerPublic.rawRepresentation

        // 双方按字节序排序拼接作为 salt
        let (first, second): (Data, Data)
        if ownPubBytes.lexicographicallyPrecedes(peerPubBytes) {
            first = ownPubBytes
            second = peerPubBytes
        } else {
            first = peerPubBytes
            second = ownPubBytes
        }
        let salt = Data(SHA256.hash(data: first + second))
        return sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: salt,
            sharedInfo: Data(hkdfInfo.utf8),
            outputByteCount: 32
        )
    }

    /// 由会话密钥派生凭据签名密钥
    public static func deriveSigningKey(sessionKey: SymmetricKey) -> SymmetricKey {
        let signSalt = Data(SHA256.hash(data: Data(signingInfo.utf8)))
        return HKDF<SHA256>.deriveKey(
            inputKeyMaterial: sessionKey,
            salt: signSalt,
            info: Data(signingInfo.utf8),
            outputByteCount: 32
        )
    }

    /// AES-256-GCM 密封：12 字节 nonce 在前，密文，16 字节 tag 在后
    public static func seal(sessionKey: SymmetricKey, plaintext: Data) throws -> Data {
        let nonce = AES.GCM.Nonce()
        let sealedBox = try AES.GCM.seal(plaintext, using: sessionKey, nonce: nonce)
        guard let combined = sealedBox.combined else {
            throw HandClientError.cryptoError("AES-GCM sealed box export failed")
        }
        return combined
    }

    /// AES-256-GCM 解密封
    public static func open(sessionKey: SymmetricKey, sealedData: Data) throws -> Data {
        guard sealedData.count >= 12 + 16 else {
            throw HandClientError.invalidSealedBlob
        }
        let sealedBox = try AES.GCM.SealedBox(combined: sealedData)
        return try AES.GCM.open(sealedBox, using: sessionKey)
    }

    /// 签发 v2 规范任务凭据 (HMAC-SHA256 + Base64URL)
    public static func issueV2Credential(
        userId: String = "",
        taskId: String,
        attempt: String = "1",
        sourceDeviceId: String,
        targetDeviceId: String,
        agentId: String,
        folder: String,
        signingKey: SymmetricKey,
        ttlSeconds: Int64 = 900,
        epoch: Int64 = 1,
        goalVersion: Int64 = 1,
        nowEpochSeconds: Int64 = Int64(Date().timeIntervalSince1970)
    ) -> String {
        let jti = UUID().uuidString
        let iat = nowEpochSeconds
        let exp = nowEpochSeconds + ttlSeconds

        let canonicalFields: [String] = [
            userId,
            taskId,
            attempt,
            sourceDeviceId,
            targetDeviceId,
            agentId,
            folder,
            String(iat),
            String(exp),
            jti,
            agentId,
            folder,
            String(epoch),
            String(goalVersion)
        ]
        let canonicalPayload = canonicalFields.joined(separator: "|")
        let canonicalData = Data(canonicalPayload.utf8)

        let signingKeyData = signingKey.withUnsafeBytes { Data($0) }
        let macKey = SymmetricKey(data: SHA256.hash(data: signingKeyData))
        let sig = HMAC<SHA256>.authenticationCode(for: canonicalData, using: macKey)
        let sigB64 = Data(sig).base64EncodedString()

        let obj: [String: Any] = [
            "v": 2,
            "user_id": userId,
            "task_id": taskId,
            "attempt": attempt,
            "source_device_id": sourceDeviceId,
            "target_device_id": targetDeviceId,
            "agent_id": agentId,
            "folder": folder,
            "iat": iat,
            "exp": exp,
            "jti": jti,
            "scope": [
                "agent_id": agentId,
                "folder": folder
            ],
            "epoch": epoch,
            "goal_version": goalVersion,
            "sig_b64": sigB64
        ]
        guard let jsonData = try? JSONSerialization.data(withJSONObject: obj, options: []) else {
            return ""
        }
        var b64url = jsonData.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
        while b64url.hasSuffix("=") {
            b64url.removeLast()
        }
        return b64url
    }
}

// MARK: - 本机设备身份管理（Keychain 隔离安全存储）

public struct HandIdentityManager: Sendable {
    private static let service = "wherevercode.agent"
    private static let privateKeyAccount = "device.identity.private_key"
    private static let deviceIdAccount = "device.identity.device_id"

    // 内存兜底（防止单测或命令行无 GUI Keychain 交互权限时报错）
    private static let lock = NSLock()
    private static var memoryIdentity: (deviceId: String, privateKey: Curve25519.KeyAgreement.PrivateKey)?
    private static var memoryPeerKeys: [String: Data] = [:]

    public static func getOrCreateDeviceIdentity() throws -> (deviceId: String, privateKey: Curve25519.KeyAgreement.PrivateKey) {
        lock.lock()
        defer { lock.unlock() }

        if let mem = memoryIdentity {
            return mem
        }

        let keyStore = KeychainStore(service: service, account: privateKeyAccount)
        let idStore = KeychainStore(service: service, account: deviceIdAccount)

        if let privB64 = try? keyStore.loadToken(),
           let privData = Data(base64Encoded: privB64),
           let priv = try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: privData),
           let devId = try? idStore.loadToken() {
            memoryIdentity = (devId, priv)
            return (devId, priv)
        }

        // 新建并尝试写入 Keychain (WhenUnlockedThisDeviceOnly)
        let newPriv = Curve25519.KeyAgreement.PrivateKey()
        let newId = "iphone-" + UUID().uuidString.prefix(8).lowercased()

        let privB64 = newPriv.rawRepresentation.base64EncodedString()
        do {
            try keyStore.saveToken(privB64)
            try idStore.saveToken(newId)
        } catch {
            // Keychain 不可用时（如 headless SSH 单测环境），走安全内存实例
        }

        memoryIdentity = (newId, newPriv)
        return (newId, newPriv)
    }

    public static func savePeerPublicKey(deviceId: String, publicKey: Data) throws {
        lock.lock()
        defer { lock.unlock() }
        memoryPeerKeys[deviceId] = publicKey
        let store = KeychainStore(service: service, account: "device:\(deviceId):public_key")
        try? store.saveToken(publicKey.base64EncodedString())
    }

    public static func getPeerPublicKey(deviceId: String) -> Data? {
        lock.lock()
        defer { lock.unlock() }
        if let mem = memoryPeerKeys[deviceId] {
            return mem
        }
        let store = KeychainStore(service: service, account: "device:\(deviceId):public_key")
        guard let b64 = try? store.loadToken() else { return nil }
        return Data(base64Encoded: b64)
    }

    public static func deleteDeviceCredentials(deviceId: String) {
        lock.lock()
        defer { lock.unlock() }
        memoryPeerKeys.removeValue(forKey: deviceId)
        let store = KeychainStore(service: service, account: "device:\(deviceId):public_key")
        try? store.deleteToken()
    }
}

// MARK: - POSIX TCP Socket 帧传输引擎

public final class PosixTcpSocket {
    private var fd: Int32 = -1

    public init() {}

    deinit {
        close()
    }

    public func connect(host: String, port: Int, timeoutSeconds: Double = 5.0) throws {
        #if canImport(Darwin)
        let socketFd = Darwin.socket(AF_INET, SOCK_STREAM, 0)
        guard socketFd >= 0 else {
            throw HandClientError.connectionFailed("无法创建 socket: errno \(errno)")
        }
        self.fd = socketFd

        var tv = timeval(tv_sec: __darwin_time_t(timeoutSeconds), tv_usec: 0)
        setsockopt(socketFd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        setsockopt(socketFd, SOL_SOCKET, SO_SNDTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))

        var hints = addrinfo()
        hints.ai_family = AF_INET
        hints.ai_socktype = SOCK_STREAM

        var res: UnsafeMutablePointer<addrinfo>?
        let portStr = String(port)
        let gaiErr = getaddrinfo(host, portStr, &hints, &res)
        guard gaiErr == 0, let addrList = res else {
            Darwin.close(socketFd)
            self.fd = -1
            throw HandClientError.connectionFailed("域名或地址解析失败：\(host)")
        }
        defer { freeaddrinfo(addrList) }

        let connRes = Darwin.connect(socketFd, addrList.pointee.ai_addr, addrList.pointee.ai_addrlen)
        guard connRes == 0 else {
            let err = errno
            Darwin.close(socketFd)
            self.fd = -1
            throw HandClientError.connectionFailed("连接 \(host):\(port) 失败：errno \(err)")
        }
        #else
        throw HandClientError.connectionFailed("当前平台不支持 Darwin 套接字")
        #endif
    }

    public func writeFrame(_ payload: Data) throws {
        #if canImport(Darwin)
        guard fd >= 0 else { throw HandClientError.connectionFailed("Socket 未连接") }
        guard payload.count <= 4 * 1024 * 1024 else {
            throw HandClientError.frameTooLarge(payload.count)
        }
        var lengthBig = UInt32(payload.count).bigEndian
        var frame = Data(bytes: &lengthBig, count: 4)
        frame.append(payload)

        var totalSent = 0
        try frame.withUnsafeBytes { rawBuf in
            guard let ptr = rawBuf.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return }
            while totalSent < frame.count {
                let sent = Darwin.send(fd, ptr + totalSent, frame.count - totalSent, 0)
                guard sent > 0 else {
                    throw HandClientError.connectionFailed("写入数据失败：errno \(errno)")
                }
                totalSent += sent
            }
        }
        #endif
    }

    public func readFrame() throws -> Data {
        #if canImport(Darwin)
        guard fd >= 0 else { throw HandClientError.connectionFailed("Socket 未连接") }
        var header = Data(count: 4)
        var totalHeader = 0
        try header.withUnsafeMutableBytes { rawBuf in
            guard let ptr = rawBuf.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return }
            while totalHeader < 4 {
                let n = Darwin.recv(fd, ptr + totalHeader, 4 - totalHeader, 0)
                if n == 0 { throw HandClientError.connectionFailed("远端过早关闭连接") }
                if n < 0 { throw HandClientError.timeout }
                totalHeader += n
            }
        }
        let payloadLen = Int(header.withUnsafeBytes { $0.load(as: UInt32.self).bigEndian })
        guard payloadLen > 0, payloadLen <= 4 * 1024 * 1024 else {
            throw HandClientError.frameTooLarge(payloadLen)
        }

        var payload = Data(count: payloadLen)
        var totalPayload = 0
        try payload.withUnsafeMutableBytes { rawBuf in
            guard let ptr = rawBuf.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return }
            while totalPayload < payloadLen {
                let n = Darwin.recv(fd, ptr + totalPayload, payloadLen - totalPayload, 0)
                if n == 0 { throw HandClientError.connectionFailed("数据未读完对端已关闭") }
                if n < 0 { throw HandClientError.timeout }
                totalPayload += n
            }
        }
        return payload
        #else
        throw HandClientError.connectionFailed("不支持的平台")
        #endif
    }

    public func close() {
        #if canImport(Darwin)
        if fd >= 0 {
            Darwin.close(fd)
            fd = -1
        }
        #endif
    }
}

// MARK: - HandClient 服务主实现

public actor HandClient {
    public static let shared = HandClient()
    private init() {}

    /// 探测远端 Hand 服务端状态并交换 Hello
    public func probeHello(host: String, port: Int = 18743, timeoutSeconds: Double = 5.0) async throws -> RemoteHello {
        return try await Task.detached {
            let (ownId, ownPriv) = try HandIdentityManager.getOrCreateDeviceIdentity()
            let sock = PosixTcpSocket()
            try sock.connect(host: host, port: port, timeoutSeconds: timeoutSeconds)
            defer { sock.close() }

            let helloObj: [String: Any] = [
                "type": "wherevercode_hello",
                "version": 1,
                "device_id": ownId,
                "public_key_b64": ownPriv.publicKey.rawRepresentation.base64EncodedString()
            ]
            let helloData = try JSONSerialization.data(withJSONObject: helloObj)
            try sock.writeFrame(helloData)

            let replyData = try sock.readFrame()
            guard let replyJson = try? JSONSerialization.jsonObject(with: replyData) as? [String: Any],
                  let type = replyJson["type"] as? String, type == "wherevercode_hello",
                  let serverDeviceId = replyJson["device_id"] as? String,
                  let serverDisplayName = replyJson["display_name"] as? String ?? replyJson["device_id"] as? String,
                  let serverPubB64 = replyJson["public_key_b64"] as? String,
                  let serverPubKey = Data(base64Encoded: serverPubB64) else {
                throw HandClientError.invalidHandshake("回包不是合法的 wherevercode_hello")
            }

            var agentInfos: [RemoteAgentInfo] = []
            if let agentsArr = replyJson["agents"] as? [[String: Any]] {
                for ag in agentsArr {
                    let aid = ag["agent_id"] as? String ?? ""
                    let dname = ag["display_name"] as? String ?? aid
                    let kind = ag["kind"] as? String ?? "agent"
                    let caps = ag["capabilities"] as? [String] ?? []
                    let fld = ag["folder"] as? String ?? ""
                    let avail = ag["available"] as? Bool ?? true
                    var repos: [RemoteRepoCheckout] = []
                    if let repoArr = ag["repos"] as? [[String: Any]] {
                        for r in repoArr {
                            let p = r["path"] as? String ?? ""
                            let b = r["branch"] as? String
                            let c = r["commit"] as? String
                            repos.append(RemoteRepoCheckout(path: p, branch: b, commit: c))
                        }
                    }
                    agentInfos.append(RemoteAgentInfo(
                        agentId: aid,
                        displayName: dname,
                        kind: kind,
                        capabilities: caps,
                        folder: fld,
                        available: avail,
                        repos: repos
                    ))
                }
            }

            let status = replyJson["client_status"] as? String ?? ""
            let version = replyJson["version"] as? String ?? ""
            return RemoteHello(
                deviceId: serverDeviceId,
                displayName: serverDisplayName,
                publicKey: serverPubKey,
                agents: agentInfos,
                clientStatus: status,
                handVersion: version
            )
        }.value
    }

    /// 发起配对确认（提交 6 位数字配对码并验证服务端公钥指纹）
    public func sendPairConfirm(
        host: String,
        port: Int = 18743,
        hello: RemoteHello,
        code: String
    ) async throws -> PairConfirmResult {
        let cleanCode = code.trimmingCharacters(in: .whitespacesAndNewlines)
        if hello.clientStatus != "APPROVED" {
            guard cleanCode.count == 6, cleanCode.allSatisfy({ $0.isNumber }) else {
                return .failed(reason: "配对码必须是 6 位数字。")
            }
        }

        return try await Task.detached {
            let (ownId, ownPriv) = try HandIdentityManager.getOrCreateDeviceIdentity()
            let sock = PosixTcpSocket()
            try sock.connect(host: host, port: port, timeoutSeconds: 6.0)
            defer { sock.close() }

            // 1. Hello 交换
            let helloObj: [String: Any] = [
                "type": "wherevercode_hello",
                "version": 1,
                "device_id": ownId,
                "public_key_b64": ownPriv.publicKey.rawRepresentation.base64EncodedString()
            ]
            try sock.writeFrame(try JSONSerialization.data(withJSONObject: helloObj))

            let serverHelloData = try sock.readFrame()
            guard let serverHelloJson = try? JSONSerialization.jsonObject(with: serverHelloData) as? [String: Any],
                  let sPubB64 = serverHelloJson["public_key_b64"] as? String,
                  let sPubKey = Data(base64Encoded: sPubB64) else {
                return .failed(reason: "目标设备 Hello 应答无效。")
            }
            guard sPubKey == hello.publicKey else {
                return .failed(reason: "目标设备公钥与已核对的指纹不匹配，可能存在中间人或密钥已被重置。")
            }

            // 幂等直通：若目标机在 Hello 中已将当前设备标记为 APPROVED
            // 直接保存公钥并返回成功，免去多余的配对确认，彻底避免连接被远端过早关闭
            if let clientStatus = serverHelloJson["client_status"] as? String, clientStatus == "APPROVED" {
                try HandIdentityManager.savePeerPublicKey(deviceId: hello.deviceId, publicKey: sPubKey)
                return .approved
            }

            // 2. 派生会话密钥并封装密封帧
            let peerCurvePub = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: sPubKey)
            let sessionKey = try HandCrypto.deriveSessionKey(ownPrivate: ownPriv, peerPublic: peerCurvePub)

            let envObj: [String: Any] = [
                "type": "cross_device_pair_confirm",
                "version": 1,
                "code": cleanCode
            ]
            let envPlain = try JSONSerialization.data(withJSONObject: envObj)
            let sealed = try HandCrypto.seal(sessionKey: sessionKey, plaintext: envPlain)
            try sock.writeFrame(sealed)

            // 3. 读取密封回执
            let replySealed: Data
            do {
                replySealed = try sock.readFrame()
            } catch let error as HandClientError {
                switch error {
                case .connectionFailed(let msg):
                    return .failed(reason: "远端过早关闭连接：\(msg)（请核对目标机配对服务状态）")
                default:
                    return .failed(reason: error.localizedDescription)
                }
            } catch {
                return .failed(reason: "读取回执失败：\(error.localizedDescription)")
            }

            let replyPlain = try HandCrypto.open(sessionKey: sessionKey, sealedData: replySealed)
            guard let replyJson = try? JSONSerialization.jsonObject(with: replyPlain) as? [String: Any] else {
                return .failed(reason: "回执信封无法解析为 JSON。")
            }

            let type = replyJson["type"] as? String ?? ""
            if type == "cross_device_pair_approved" {
                // 保存公钥到 Keychain
                try HandIdentityManager.savePeerPublicKey(deviceId: hello.deviceId, publicKey: sPubKey)
                return .approved
            } else if type == "cross_device_error" {
                let args = replyJson["args"] as? [String: Any]
                let code = args?["code"] as? String ?? ""
                let message = args?["message"] as? String ?? "配对被远端拒绝。"
                switch code {
                case "pair_code_expired":
                    return .codeExpired(reason: message)
                case "pair_code_consumed":
                    return .codeConsumed(reason: message)
                case "pair_code_wrong":
                    return .codeWrong(reason: message)
                case "pair_code_locked":
                    return .clientLocked(reason: message)
                case "device_revoked":
                    return .revoked(reason: message)
                default:
                    return .rejected(code: code, reason: message)
                }
            } else {
                return .failed(reason: "未知回执类型：\(type)")
            }
        }.value
    }

    /// 请求已认证设备远程刷新配对码（必须持有已配对设备的私钥及对端公钥）
    public func remoteRotatePairCode(
        host: String,
        port: Int = 18743,
        targetDeviceId: String
    ) async throws -> (code: String, expiresIn: Int) {
        return try await Task.detached {
            let (ownId, ownPriv) = try HandIdentityManager.getOrCreateDeviceIdentity()
            guard let peerPubData = HandIdentityManager.getPeerPublicKey(deviceId: targetDeviceId) else {
                throw HandClientError.credentialError("本地未持有设备「\(targetDeviceId)」的配对公钥，无法发起授权刷新，请在目标机上直接运行 pair-code 命令。")
            }
            let peerCurvePub = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: peerPubData)

            let sock = PosixTcpSocket()
            try sock.connect(host: host, port: port, timeoutSeconds: 6.0)
            defer { sock.close() }

            // 1. Hello
            let helloObj: [String: Any] = [
                "type": "wherevercode_hello",
                "version": 1,
                "device_id": ownId,
                "public_key_b64": ownPriv.publicKey.rawRepresentation.base64EncodedString()
            ]
            try sock.writeFrame(try JSONSerialization.data(withJSONObject: helloObj))
            let hData = try sock.readFrame()
            guard let hJson = try? JSONSerialization.jsonObject(with: hData) as? [String: Any],
                  let status = hJson["client_status"] as? String, status == "APPROVED" else {
                throw HandClientError.serverRejected(code: "not_approved", message: "本设备尚未在目标机获得配对授权，无法远程刷新。")
            }

            // 2. 派生会话密钥
            let sessionKey = try HandCrypto.deriveSessionKey(ownPrivate: ownPriv, peerPublic: peerCurvePub)

            // 3. 发送管理动作
            let adminEnv: [String: Any] = [
                "type": "wherevercode_device_admin",
                "version": 1,
                "seq": 1,
                "action": "rotate_pair_code",
                "confirmed": true
            ]
            let sealed = try HandCrypto.seal(sessionKey: sessionKey, plaintext: try JSONSerialization.data(withJSONObject: adminEnv))
            try sock.writeFrame(sealed)

            // 4. 读取应答
            let replySealed = try sock.readFrame()
            let replyPlain = try HandCrypto.open(sessionKey: sessionKey, sealedData: replySealed)
            guard let replyJson = try? JSONSerialization.jsonObject(with: replyPlain) as? [String: Any],
                  let type = replyJson["type"] as? String, type == "wherevercode_device_admin_reply",
                  let args = replyJson["args"] as? [String: Any],
                  let newCode = args["pair_code"] as? String else {
                throw HandClientError.serverRejected(code: "rotate_failed", message: "远程刷新配对码应答无效。")
            }
            let ttl = args["expires_in"] as? Int ?? 300
            return (newCode, ttl)
        }.value
    }

    /// 发起密封工具调用（list, read, write, authorize_project_folder, run）
    private func executeCall(
        host: String,
        port: Int,
        targetDeviceId: String,
        folder: String,
        agentId: String,
        action: String,
        args: [String: String],
        onProgress: ((String) -> Void)? = nil
    ) async throws -> [String: Any] {
        return try await Task.detached {
            let (ownId, ownPriv) = try HandIdentityManager.getOrCreateDeviceIdentity()
            guard let peerPubData = HandIdentityManager.getPeerPublicKey(deviceId: targetDeviceId) else {
                throw HandClientError.credentialError("本地 Keychain 中未找到设备「\(targetDeviceId)」的配对公钥，请重新配对。")
            }
            let peerCurvePub = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: peerPubData)

            let sock = PosixTcpSocket()
            try sock.connect(host: host, port: port, timeoutSeconds: 10.0)
            defer { sock.close() }

            // 1. Hello
            let helloObj: [String: Any] = [
                "type": "wherevercode_hello",
                "version": 1,
                "device_id": ownId,
                "public_key_b64": ownPriv.publicKey.rawRepresentation.base64EncodedString()
            ]
            try sock.writeFrame(try JSONSerialization.data(withJSONObject: helloObj))
            let hData = try sock.readFrame()
            guard let hJson = try? JSONSerialization.jsonObject(with: hData) as? [String: Any],
                  let status = hJson["client_status"] as? String, status == "APPROVED" else {
                throw HandClientError.serverRejected(code: "not_approved", message: "本设备尚未在目标机上被批准配对。")
            }

            // 2. 派生密钥
            let sessionKey = try HandCrypto.deriveSessionKey(ownPrivate: ownPriv, peerPublic: peerCurvePub)
            let signingKey = HandCrypto.deriveSigningKey(sessionKey: sessionKey)

            // 3. 签发任务凭证
            let taskId = "task-" + UUID().uuidString.prefix(8).lowercased()
            let wireCred = HandCrypto.issueV2Credential(
                taskId: taskId,
                attempt: "1",
                sourceDeviceId: ownId,
                targetDeviceId: targetDeviceId,
                agentId: agentId,
                folder: folder,
                signingKey: signingKey
            )

            var callArgs = args
            callArgs["credential"] = wireCred

            let callEnv: [String: Any] = [
                "type": "cross_device_tool_call",
                "version": 1,
                "seq": 1,
                "task_id": taskId,
                "agent_id": agentId,
                "source_device_id": ownId,
                "target_device_id": targetDeviceId,
                "folder": folder,
                "action": action,
                "args": callArgs
            ]

            let plainCall = try JSONSerialization.data(withJSONObject: callEnv)
            let sealedCall = try HandCrypto.seal(sessionKey: sessionKey, plaintext: plainCall)
            try sock.writeFrame(sealedCall)

            // 4. 读取回包循环
            while true {
                let rSealed = try sock.readFrame()
                let rPlain = try HandCrypto.open(sessionKey: sessionKey, sealedData: rSealed)
                guard let resJson = try? JSONSerialization.jsonObject(with: rPlain) as? [String: Any] else {
                    throw HandClientError.invalidSealedBlob
                }
                let type = resJson["type"] as? String ?? ""
                if type == "cross_device_progress" {
                    if let pArgs = resJson["args"] as? [String: Any],
                       let msg = pArgs["message"] as? String {
                        onProgress?(msg)
                    }
                    continue
                } else if type == "cross_device_result" {
                    return resJson
                } else if type == "cross_device_error" {
                    let errArgs = resJson["args"] as? [String: Any]
                    let code = errArgs?["code"] as? String ?? "unknown"
                    let msg = errArgs?["message"] as? String ?? "操作被远端拒绝"
                    throw HandClientError.serverRejected(code: code, message: msg)
                } else {
                    throw HandClientError.serverRejected(code: "bad_type", message: "未知响应帧：\(type)")
                }
            }
        }.value
    }

    /// 列出远程项目目录中的文件
    public func listDirectory(
        host: String,
        port: Int = 18743,
        targetDeviceId: String,
        folder: String,
        agentId: String = "codex",
        path: String = ""
    ) async throws -> [RemoteFileEntry] {
        let res = try await executeCall(
            host: host,
            port: port,
            targetDeviceId: targetDeviceId,
            folder: folder,
            agentId: agentId,
            action: "list",
            args: ["path": path]
        )
        guard let args = res["args"] as? [String: Any],
              let outputStr = args["output"] as? String,
              let data = outputStr.data(using: .utf8),
              let list = try? JSONDecoder().decode([RemoteFileEntry].self, from: data) else {
            return []
        }
        return list
    }

    /// 读取远程文件内容
    public func readFile(
        host: String,
        port: Int = 18743,
        targetDeviceId: String,
        folder: String,
        agentId: String = "codex",
        path: String
    ) async throws -> String {
        let res = try await executeCall(
            host: host,
            port: port,
            targetDeviceId: targetDeviceId,
            folder: folder,
            agentId: agentId,
            action: "read",
            args: ["path": path]
        )
        guard let args = res["args"] as? [String: Any],
              let output = args["output"] as? String else {
            return ""
        }
        return output
    }

    /// 写入远程文件
    public func writeFile(
        host: String,
        port: Int = 18743,
        targetDeviceId: String,
        folder: String,
        agentId: String = "codex",
        path: String,
        content: String
    ) async throws -> String {
        let res = try await executeCall(
            host: host,
            port: port,
            targetDeviceId: targetDeviceId,
            folder: folder,
            agentId: agentId,
            action: "write",
            args: ["path": path, "content": content]
        )
        guard let args = res["args"] as? [String: Any],
              let output = args["output"] as? String else {
            return "写入成功"
        }
        return output
    }

    /// 授权远程项目路径
    public func authorizeProjectFolder(
        host: String,
        port: Int = 18743,
        targetDeviceId: String,
        agentId: String = "codex",
        folder: String
    ) async throws -> Bool {
        let res = try await executeCall(
            host: host,
            port: port,
            targetDeviceId: targetDeviceId,
            folder: folder,
            agentId: agentId,
            action: "authorize_project_folder",
            args: ["folder": folder]
        )
        guard let args = res["args"] as? [String: Any],
              let output = args["output"] as? String,
              let data = output.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return false
        }
        return json["authorized"] as? Bool ?? false
    }

    /// 发送控制指令（暂停、继续、停止）
    public func sendControl(
        host: String,
        port: Int = 18743,
        targetDeviceId: String,
        folder: String,
        agentId: String = "codex",
        action: String,
        operationId: String = UUID().uuidString
    ) async throws -> (confirmed: Bool, message: String) {
        let argv = ["__control__", action, operationId]
        guard let argvData = try? JSONSerialization.data(withJSONObject: argv),
              let argvStr = String(data: argvData, encoding: .utf8) else {
            return (false, "参数序列化失败")
        }
        let res = try await executeCall(
            host: host,
            port: port,
            targetDeviceId: targetDeviceId,
            folder: folder,
            agentId: agentId,
            action: "run",
            args: ["argv": argvStr]
        )
        guard let args = res["args"] as? [String: Any],
              let output = args["output"] as? String,
              let data = output.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return (false, "无法解析控制回执")
        }
        let confirmed = json["confirmed"] as? Bool ?? false
        let msg = json["message"] as? String ?? ""
        return (confirmed, msg)
    }

    /// 执行远程 Agent 任务并流式获取日志
    public func runAgent(
        host: String,
        port: Int = 18743,
        targetDeviceId: String,
        folder: String,
        agentId: String,
        prompt: String,
        timeoutSeconds: Double = 30.0,
        onProgress: @escaping (String) -> Void
    ) async throws -> String {
        let argv = ["agent", agentId, prompt]
        guard let argvData = try? JSONSerialization.data(withJSONObject: argv),
              let argvStr = String(data: argvData, encoding: .utf8) else {
            return ""
        }
        let res = try await executeCall(
            host: host,
            port: port,
            targetDeviceId: targetDeviceId,
            folder: folder,
            agentId: agentId,
            action: "run",
            args: ["argv": argvStr, "timeout": String(Int(timeoutSeconds))],
            onProgress: onProgress
        )
        let args = res["args"] as? [String: Any]
        return args?["output"] as? String ?? "执行完成"
    }

    /// 局域网快速发现（并发探测常见网段与 mDNS 辅助）
    public func discoverLanDevices(knownHosts: [String] = ["192.168.1.4", "100.75.41.0", "127.0.0.1"]) async -> [DiscoveredPeer] {
        var results: [DiscoveredPeer] = []
        await withTaskGroup(of: DiscoveredPeer?.self) { group in
            for host in knownHosts {
                group.addTask {
                    guard let hello = try? await self.probeHello(host: host, port: 18743, timeoutSeconds: 1.5) else {
                        return nil
                    }
                    return DiscoveredPeer(
                        deviceId: hello.deviceId,
                        displayName: hello.displayName,
                        host: host,
                        port: 18743,
                        fingerprintSha256: hello.fingerprintSha256,
                        agents: hello.agents,
                        clientStatus: hello.clientStatus
                    )
                }
            }
            for await item in group {
                if let peer = item, !results.contains(where: { $0.deviceId == peer.deviceId }) {
                    results.append(peer)
                }
            }
        }
        return results
    }
}
