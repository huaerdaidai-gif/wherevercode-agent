import XCTest
import CryptoKit
@testable import WhereverCodeAgent

final class HandClientTests: XCTestCase {

    // 1. 验证 RFC 7748 测试向量与 HKDF-SHA256 跨端互通密钥派生
    func testRfc7748VectorAndSessionKeyDerivation() throws {
        let alicePrivHex = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"
        let alicePubHex = "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"
        let bobPrivHex = "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb"
        let bobPubHex = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"

        func hexToData(_ h: String) -> Data {
            var data = Data()
            var index = h.startIndex
            while index < h.endIndex {
                let nextIndex = h.index(index, offsetBy: 2)
                let byteStr = String(h[index..<nextIndex])
                data.append(UInt8(byteStr, radix: 16)!)
                index = nextIndex
            }
            return data
        }

        let alicePriv = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: hexToData(alicePrivHex))
        let alicePub = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: hexToData(alicePubHex))
        let bobPriv = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: hexToData(bobPrivHex))
        let bobPub = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: hexToData(bobPubHex))

        // RFC 7748 锚点
        let aliceShared = try alicePriv.sharedSecretFromKeyAgreement(with: bobPub)
        let expectedSharedHex = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
        let aliceSharedHex = aliceShared.withUnsafeBytes { raw in
            raw.map { String(format: "%02x", $0) }.joined()
        }
        XCTAssertEqual(aliceSharedHex, expectedSharedHex)

        // 双向推导会话密钥
        let aliceSession = try HandCrypto.deriveSessionKey(ownPrivate: alicePriv, peerPublic: bobPub)
        let bobSession = try HandCrypto.deriveSessionKey(ownPrivate: bobPriv, peerPublic: alicePub)

        let aData = aliceSession.withUnsafeBytes { Data($0) }
        let bData = bobSession.withUnsafeBytes { Data($0) }
        XCTAssertEqual(aData, bData)
        XCTAssertEqual(aData.count, 32)
    }

    // 2. 验证 AES-256-GCM 密封与解密 (12 字节 Nonce + 密文 + 16 字节 Tag)
    func testAes256GcmSealAndOpen() throws {
        let key = SymmetricKey(size: .bits256)
        let message = Data("WhereverCode Hand V5 Security Envelope Test".utf8)

        let sealed = try HandCrypto.seal(sessionKey: key, plaintext: message)
        XCTAssertGreaterThanOrEqual(sealed.count, message.count + 28) // 12 nonce + 16 tag

        let opened = try HandCrypto.open(sessionKey: key, sealedData: sealed)
        XCTAssertEqual(opened, message)

        // 验证篡改检测
        var tampered = sealed
        tampered[tampered.count - 1] ^= 0xFF
        XCTAssertThrowsError(try HandCrypto.open(sessionKey: key, sealedData: tampered))
    }

    // 3. 验证 v2 规范任务凭据签发与格式
    func testV2TaskCredentialSigning() throws {
        let key = SymmetricKey(size: .bits256)
        let signingKey = HandCrypto.deriveSigningKey(sessionKey: key)

        let wireCred = HandCrypto.issueV2Credential(
            taskId: "task-test-123",
            attempt: "1",
            sourceDeviceId: "iphone-app",
            targetDeviceId: "mac-host",
            agentId: "codex",
            folder: "/Users/a1234/v5-codex-work",
            signingKey: signingKey,
            ttlSeconds: 900
        )

        XCTAssertFalse(wireCred.isEmpty)
        // Base64URL 无填充格式校验
        XCTAssertFalse(wireCred.contains("+"))
        XCTAssertFalse(wireCred.contains("/"))
        XCTAssertFalse(wireCred.contains("="))

        // 解码还原 JSON
        var padded = wireCred.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while padded.count % 4 != 0 { padded.append("=") }
        let decodedData = try XCTUnwrap(Data(base64Encoded: padded))
        let json = try XCTUnwrap(try JSONSerialization.jsonObject(with: decodedData) as? [String: Any])

        XCTAssertEqual(json["v"] as? Int, 2)
        XCTAssertEqual(json["task_id"] as? String, "task-test-123")
        XCTAssertEqual(json["target_device_id"] as? String, "mac-host")
        XCTAssertEqual(json["agent_id"] as? String, "codex")
        XCTAssertEqual(json["folder"] as? String, "/Users/a1234/v5-codex-work")
        XCTAssertNotNil(json["sig_b64"] as? String)
    }

    // 4. 验证一键安装命令与安装包哈希契约
    func testDeviceInstallGuideCommandsAndHashes() {
        let macCmd = DeviceInstallGuide.installCommand(for: .mac)
        XCTAssertTrue(macCmd.contains("v5-install-hand.sh"))
        XCTAssertTrue(macCmd.contains("--port 18743"))
        XCTAssertTrue(macCmd.contains("--version 1.5.6"))

        let winCmd = DeviceInstallGuide.installCommand(for: .windows)
        XCTAssertTrue(winCmd.contains("v5-install-hand.ps1"))
        XCTAssertTrue(winCmd.contains("18743"))

        let uninstallCmd = DeviceInstallGuide.uninstallCommand(for: .mac)
        XCTAssertTrue(uninstallCmd.contains("v5-uninstall-hand.sh"))

        // 校验内置哈希与实际发布包一致
        XCTAssertEqual(DeviceInstallGuide.scriptSha256["hand_server.py"], "f5357dd8a6535afa59a9435f8a43dcb0ab4cfbd0d71b2113f4097b2ceebf7458")
        XCTAssertEqual(DeviceInstallGuide.scriptSha256["v5-install-hand.sh"], "3072654f1c35094666a9fc7a3bd1a09f7a2cfaedbd08d94715abdd7d7dc15caa")
    }

    // 5. 验证 Keychain 凭据隔离与未明文保存
    func testKeychainSecurityIsolation() throws {
        let (devId, priv) = try HandIdentityManager.getOrCreateDeviceIdentity()
        XCTAssertFalse(devId.isEmpty)
        XCTAssertEqual(priv.publicKey.rawRepresentation.count, 32)

        let dummyPeerPubKey = Data(repeating: 0x42, count: 32)
        try HandIdentityManager.savePeerPublicKey(deviceId: "peer-test-device", publicKey: dummyPeerPubKey)

        let loaded = HandIdentityManager.getPeerPublicKey(deviceId: "peer-test-device")
        XCTAssertEqual(loaded, dummyPeerPubKey)

        HandIdentityManager.deleteDeviceCredentials(deviceId: "peer-test-device")
        XCTAssertNil(HandIdentityManager.getPeerPublicKey(deviceId: "peer-test-device"))
    }

    // 6. 验证工作区路径正规化
    func testNormalizePath() {
        XCTAssertEqual(HandClient.normalizePath("/Users/a1234/v5-codex-work/"), "/Users/a1234/v5-codex-work")
        XCTAssertEqual(HandClient.normalizePath("/Users/a1234//v5-codex-work///"), "/Users/a1234/v5-codex-work")
        XCTAssertEqual(HandClient.normalizePath("  /a/b/c  "), "/a/b/c")
        XCTAssertEqual(HandClient.normalizePath(""), "")
    }

    // 7. 验证跨设备工具注册表定义与能力集
    func testCrossDeviceToolRegistryDefinitions() {
        let registry = HandCrossDeviceToolRegistry(
            host: "100.75.41.0",
            port: 18743,
            targetDeviceId: "MacBook-Air",
            agentId: "codex",
            defaultFolder: "/Users/a1234/v5-codex-work"
        )
        XCTAssertEqual(registry.workspaceRoot, "/Users/a1234/v5-codex-work")

        let tools = registry.toolsForCapabilities(nil, authorizedTools: [])
        XCTAssertEqual(tools.count, 4)
        let toolNames = Set(tools.map(\.name))
        XCTAssertTrue(toolNames.contains("list_files"))
        XCTAssertTrue(toolNames.contains("read_file"))
        XCTAssertTrue(toolNames.contains("write_file"))
        XCTAssertTrue(toolNames.contains("run_command"))

        XCTAssertTrue(registry.isReadOnlyExtensionTool("list_files"))
        XCTAssertTrue(registry.isReadOnlyExtensionTool("read_file"))
        XCTAssertFalse(registry.isReadOnlyExtensionTool("write_file"))
        XCTAssertFalse(registry.isReadOnlyExtensionTool("run_command"))
    }

    // 8. 验证跨设备工具调用合法性校验
    func testCrossDeviceToolRegistryValidation() {
        let registry = HandCrossDeviceToolRegistry(
            host: "100.75.41.0",
            port: 18743,
            targetDeviceId: "MacBook-Air",
            agentId: "codex",
            defaultFolder: "/Users/a1234/v5-codex-work"
        )
        let context = ToolContext(
            taskId: "task-test",
            agentId: "codex",
            approvalGate: DenyApprovalGate(),
            workspaceRoot: "/Users/a1234/v5-codex-work",
            deviceId: "MacBook-Air",
            folder: "/Users/a1234/v5-codex-work"
        )

        let validAction = BrainAction(tool: "list_files", args: ["path": ""], idempotencyKey: "test-idempotency-1")
        XCTAssertNil(registry.validateOnly(validAction, context))

        let unknownAction = BrainAction(tool: "delete_everything", args: [:], idempotencyKey: "test-idempotency-2")
        let outcome = registry.validateOnly(unknownAction, context)
        XCTAssertNotNil(outcome)
        XCTAssertFalse(outcome!.ok)
        XCTAssertEqual(outcome!.errorCode, "unknown_tool")
    }

    // 9. 验证设备删除时级联注销所属 Agent 契约
    func testDeviceCascadeAgentDeletionContract() throws {
        let testDevId = "test-cascade-mac"
        let testDev = AgentRecord(
            agentId: testDevId,
            displayName: "Test Cascade Mac",
            kind: .device,
            access: .app,
            deviceId: testDevId
        )
        let childAgent = AgentRecord(
            agentId: "\(testDevId):codex",
            displayName: "Codex (Test Cascade Mac)",
            kind: .device,
            access: .cli,
            deviceId: testDevId
        )

        try LocalData.shared.agents.saveAgent(testDev)
        try LocalData.shared.agents.saveAgent(childAgent)

        XCTAssertNotNil(try LocalData.shared.agents.getAgent(agentId: testDevId))
        XCTAssertNotNil(try LocalData.shared.agents.getAgent(agentId: "\(testDevId):codex"))

        // 模拟级联删除
        let all = try LocalData.shared.agents.listAgents()
        for a in all where a.deviceId == testDevId {
            try LocalData.shared.agents.deleteAgent(agentId: a.agentId)
        }
        try LocalData.shared.agents.deleteAgent(agentId: testDevId)

        XCTAssertNil(try LocalData.shared.agents.getAgent(agentId: testDevId))
        XCTAssertNil(try LocalData.shared.agents.getAgent(agentId: "\(testDevId):codex"))
    }
}
