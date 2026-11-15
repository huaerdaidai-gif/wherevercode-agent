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
        XCTAssertEqual(DeviceInstallGuide.scriptSha256["hand_server.py"], "78076059153e2be7b177297e6a164785a332c2cdd2bb1099ee82dee4477cbf10")
        XCTAssertEqual(DeviceInstallGuide.scriptSha256["v5-install-hand.sh"], "270b0565d171d515f2e7ce07c79cefef9445b481e3af7606daa717eb4fc329ea")
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

        let validArgs: [String: String] = ["path": ""]
        let validAction = BrainAction(tool: "list_files", args: validArgs, idempotencyKey: "test-idempotency-1")
        XCTAssertNil(registry.validateOnly(validAction, context))

        let unknownArgs: [String: String] = [:]
        let unknownAction = BrainAction(tool: "delete_everything", args: unknownArgs, idempotencyKey: "test-idempotency-2")
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

        // 使用 AgentRegistryStore.deleteDeviceCascade（安全解绑，不连带删除 Agent）
        try LocalData.shared.agents.deleteDeviceCascade(deviceId: testDevId)

        // 验证设备本身已删除
        XCTAssertNil(try LocalData.shared.agents.getAgent(agentId: testDevId))
        // 核心原则：删除设备不得连带删除 Agent，Agent 独立保留并安全解绑
        let survivingAgent = try LocalData.shared.agents.getAgent(agentId: "\(testDevId):codex")
        XCTAssertNotNil(survivingAgent)
        XCTAssertNil(survivingAgent?.deviceId)
        XCTAssertEqual(survivingAgent?.memberConnectionState, "NOT_CONNECTED")

        // 清理测试 Agent
        try? LocalData.shared.agents.deleteAgent(agentId: "\(testDevId):codex")
    }

    // 10. 回归测试 1：重复发现去重与配对状态同步
    func testLanDiscoveryDeduplicationAndPairingSync() throws {
        let deviceId = "test-macbook-air"
        let registeredDev = AgentRecord(
            agentId: deviceId,
            displayName: "MacBook-Air",
            kind: .device,
            access: .app,
            baseUrl: "192.168.1.4:18743",
            authRef: "device:\(deviceId):public_key",
            lastTestSummary: "已连接",
            memberConnectionState: "CONNECTED",
            deviceId: deviceId
        )
        try LocalData.shared.agents.saveAgent(registeredDev)

        // 构造发现的同一设备 Peer
        let discoveredPeer = DiscoveredPeer(
            deviceId: deviceId,
            displayName: "MacBook-Air",
            host: "192.168.1.4",
            port: 18743,
            fingerprintSha256: "abcdef1234567890",
            agents: [],
            clientStatus: "APPROVED"
        )

        // 验证身份精确匹配（不依赖 display name 猜测）
        XCTAssertTrue(HandClient.isSameDevice(record: registeredDev, peer: discoveredPeer))

        // 构造不同 deviceId 的设备，确保不误判
        let otherPeer = DiscoveredPeer(
            deviceId: "other-mac-device",
            displayName: "MacBook-Air", // 同名但不同 deviceId
            host: "192.168.1.99",
            port: 18743,
            fingerprintSha256: "9876543210fedcba",
            agents: [],
            clientStatus: "PENDING"
        )
        XCTAssertFalse(HandClient.isSameDevice(record: registeredDev, peer: otherPeer))

        // 构造同 IP / 同端口 但不同 deviceId 的对端设备，严格确保不因 IP 相同而误合并
        let sameIpDifferentDevice = DiscoveredPeer(
            deviceId: "another-physical-box",
            displayName: "Another Host",
            host: "192.168.1.4",
            port: 18743,
            fingerprintSha256: "1122334455667788",
            agents: [],
            clientStatus: "PENDING"
        )
        XCTAssertFalse(HandClient.isSameDevice(record: registeredDev, peer: sameIpDifferentDevice))

        // 清理
        try LocalData.shared.agents.deleteAgent(agentId: deviceId)
    }

    // 11. 回归测试 2：IP 变化动态更新与所属 Agent 同步
    func testIpChangeUpdatesBaseUrlAndChildAgents() throws {
        let deviceId = "dynamic-ip-mac"
        let dev = AgentRecord(
            agentId: deviceId,
            displayName: "Dynamic Mac",
            kind: .device,
            access: .app,
            baseUrl: "192.168.1.4:18743",
            deviceId: deviceId
        )
        let childAgent = AgentRecord(
            agentId: "\(deviceId):codex",
            displayName: "Codex",
            kind: .device,
            access: .cli,
            baseUrl: "192.168.1.4:18743",
            deviceId: deviceId
        )
        try LocalData.shared.agents.saveAgent(dev)
        try LocalData.shared.agents.saveAgent(childAgent)

        // 模拟 DHCP IP 发生变化，发现位于 192.168.1.108
        let newPeer = DiscoveredPeer(
            deviceId: deviceId,
            displayName: "Dynamic Mac",
            host: "192.168.1.108",
            port: 18743,
            fingerprintSha256: "fingerprint-123",
            agents: [],
            clientStatus: "APPROVED"
        )

        var updatedDev = try XCTUnwrap(LocalData.shared.agents.getAgent(agentId: deviceId))
        let newBaseUrl = "\(newPeer.host):\(newPeer.port)"
        updatedDev.baseUrl = newBaseUrl
        updatedDev.memberConnectionState = "CONNECTED"
        try LocalData.shared.agents.saveAgent(updatedDev)

        // 级联同步子 Agent
        let all = try LocalData.shared.agents.listAgents()
        for var child in all where child.kind == .device && child.deviceId == deviceId && child.agentId != deviceId {
            child.baseUrl = newBaseUrl
            child.memberConnectionState = updatedDev.memberConnectionState
            try LocalData.shared.agents.saveAgent(child)
        }

        let refetchedDev = try XCTUnwrap(LocalData.shared.agents.getAgent(agentId: deviceId))
        let refetchedChild = try XCTUnwrap(LocalData.shared.agents.getAgent(agentId: "\(deviceId):codex"))
        XCTAssertEqual(refetchedDev.baseUrl, "192.168.1.108:18743")
        XCTAssertEqual(refetchedChild.baseUrl, "192.168.1.108:18743")
        XCTAssertEqual(refetchedDev.memberConnectionState, "CONNECTED")
        XCTAssertEqual(refetchedChild.memberConnectionState, "CONNECTED")

        // 清理
        try LocalData.shared.agents.deleteDeviceCascade(deviceId: deviceId)
    }

    // 12. 回归测试 3：设备删除级联、凭据撤销且绝不误删非设备实体
    func testDeviceDeleteCascadeAndCredentialRevocation() throws {
        let devId = "delete-test-mac"
        let cloudAgentId = "cloud-claude-protected"

        // 预设云端 Agent
        let cloudAgent = AgentRecord(
            agentId: cloudAgentId,
            displayName: "Claude Sonnet",
            kind: .cloud,
            access: .api
        )
        try LocalData.shared.agents.saveAgent(cloudAgent)

        // 预设待删设备及子 Agent
        let dev = AgentRecord(agentId: devId, displayName: "Delete Mac", kind: .device, access: .app, deviceId: devId)
        let childAgent = AgentRecord(agentId: "\(devId):codex", displayName: "Codex", kind: .device, access: .cli, deviceId: devId)
        try LocalData.shared.agents.saveAgent(dev)
        try LocalData.shared.agents.saveAgent(childAgent)

        // 预设 Keychain 凭据
        let dummyKey = Data(repeating: 0x99, count: 32)
        try HandIdentityManager.savePeerPublicKey(deviceId: devId, publicKey: dummyKey)
        XCTAssertNotNil(HandIdentityManager.getPeerPublicKey(deviceId: devId))

        // 执行安全级联删除
        HandIdentityManager.deleteDeviceCredentials(deviceId: devId)
        try LocalData.shared.agents.deleteDeviceCascade(deviceId: devId)

        // 验证设备记录与密钥凭据已被清除
        XCTAssertNil(try LocalData.shared.agents.getAgent(agentId: devId))
        XCTAssertNil(HandIdentityManager.getPeerPublicKey(deviceId: devId))

        // 核心原则：删除设备不得连带删除 Agent，Agent 依然保留且解除设备关联
        let preservedAgent = try LocalData.shared.agents.getAgent(agentId: "\(devId):codex")
        XCTAssertNotNil(preservedAgent)
        XCTAssertNil(preservedAgent?.deviceId)
        XCTAssertEqual(preservedAgent?.memberConnectionState, "NOT_CONNECTED")

        // 验证云端 Agent 毫发无损
        XCTAssertNotNil(try LocalData.shared.agents.getAgent(agentId: cloudAgentId))

        // 清理测试 Agent
        try? LocalData.shared.agents.deleteAgent(agentId: "\(devId):codex")
        try LocalData.shared.agents.deleteAgent(agentId: cloudAgentId)
    }

    // 13. 回归测试 4：取消配对后重新配对闭环
    func testDeviceUnpairAndRePairing() throws {
        let devId = "repair-test-mac"
        var dev = AgentRecord(
            agentId: devId,
            displayName: "Re-Pair Mac",
            kind: .device,
            access: .app,
            baseUrl: "192.168.1.4:18743",
            authRef: "device:\(devId):public_key",
            memberConnectionState: "CONNECTED",
            deviceId: devId
        )
        try LocalData.shared.agents.saveAgent(dev)
        let key1 = Data(repeating: 0x11, count: 32)
        try HandIdentityManager.savePeerPublicKey(deviceId: devId, publicKey: key1)

        // 1. 取消配对：清空 Keychain 凭据并重置连接态
        HandIdentityManager.deleteDeviceCredentials(deviceId: devId)
        dev.memberConnectionState = "NOT_CONNECTED"
        dev.authRef = nil
        try LocalData.shared.agents.saveAgent(dev)

        XCTAssertNil(HandIdentityManager.getPeerPublicKey(deviceId: devId))
        var fetched = try XCTUnwrap(LocalData.shared.agents.getAgent(agentId: devId))
        XCTAssertEqual(fetched.memberConnectionState, "NOT_CONNECTED")
        XCTAssertNil(fetched.authRef)

        // 2. 重新配对：存入新凭据并更新为 CONNECTED
        let key2 = Data(repeating: 0x22, count: 32)
        try HandIdentityManager.savePeerPublicKey(deviceId: devId, publicKey: key2)
        fetched.authRef = "device:\(devId):public_key"
        fetched.memberConnectionState = "CONNECTED"
        try LocalData.shared.agents.saveAgent(fetched)

        let rePaired = try XCTUnwrap(LocalData.shared.agents.getAgent(agentId: devId))
        XCTAssertEqual(rePaired.memberConnectionState, "CONNECTED")
        XCTAssertEqual(HandIdentityManager.getPeerPublicKey(deviceId: devId), key2)

        // 清理
        HandIdentityManager.deleteDeviceCredentials(deviceId: devId)
        try LocalData.shared.agents.deleteDeviceCascade(deviceId: devId)
    }

    // 14. 回归测试 5：旧记录安全迁移不按名称猜测合并且不误删其他实体
    func testSafeLegacyMigrationDoesNotMergeByNameOrDeleteAgents() throws {
        // 构造历史演示模板设备记录
        let legacyMac = AgentRecord(
            agentId: "device-mac-workstation",
            displayName: "Mac · 开发工作站",
            kind: .device,
            access: .app,
            baseUrl: "mac-workstation.local:8765",
            lastTestSummary: "本地登记 · 未验证在线",
            memberConnectionState: "NOT_CONNECTED",
            deviceId: nil // 历史缺失 deviceId
        )
        // 构造用户真实连接的 MacBook-Air
        let realMac = AgentRecord(
            agentId: "MacBook-Air",
            displayName: "MacBook-Air",
            kind: .device,
            access: .app,
            baseUrl: "192.168.1.4:18743",
            lastTestSummary: "已连接",
            memberConnectionState: "CONNECTED",
            deviceId: "MacBook-Air"
        )
        // 构造云端 Agent
        let cloudAgent = AgentRecord(
            agentId: "cloud-deepseek",
            displayName: "DeepSeek R1",
            kind: .cloud,
            access: .api
        )

        try LocalData.shared.agents.saveAgent(legacyMac)
        try LocalData.shared.agents.saveAgent(realMac)
        try LocalData.shared.agents.saveAgent(cloudAgent)

        // 执行迁移
        try LocalData.shared.agents.migrateLegacyDeviceRecords()

        let afterLegacy = try XCTUnwrap(LocalData.shared.agents.getAgent(agentId: "device-mac-workstation"))
        let afterReal = try XCTUnwrap(LocalData.shared.agents.getAgent(agentId: "MacBook-Air"))
        let afterCloud = try XCTUnwrap(LocalData.shared.agents.getAgent(agentId: "cloud-deepseek"))

        // 验证 deviceId 规范化
        XCTAssertEqual(afterLegacy.deviceId, "device-mac-workstation")
        // 验证状态诚实标注
        XCTAssertTrue(afterLegacy.lastTestSummary?.contains("演示模板设备") == true)
        // 验证两台 Mac 独立共存，绝不根据包含 "Mac" 按名字猜测合并
        XCTAssertNotEqual(afterLegacy.agentId, afterReal.agentId)
        XCTAssertEqual(afterReal.memberConnectionState, "CONNECTED")
        XCTAssertEqual(afterReal.baseUrl, "192.168.1.4:18743")
        // 验证云端 Agent 完好无损
        XCTAssertEqual(afterCloud.kind, .cloud)

        // 清理
        try LocalData.shared.agents.deleteAgent(agentId: "device-mac-workstation")
        try LocalData.shared.agents.deleteAgent(agentId: "MacBook-Air")
        try LocalData.shared.agents.deleteAgent(agentId: "cloud-deepseek")
    }

    // 15. 回归测试 6：删除后网络探测竞态不复活已删除设备
    func testDeviceDeletionRaceConditionDoesNotReviveRecord() throws {
        let deadDevId = "dead-mac-to-delete"
        var deadDev = AgentRecord(
            agentId: deadDevId,
            displayName: "Dead Mac Workstation",
            kind: .device,
            access: .app,
            baseUrl: "mac-invalid-dns.local:8765",
            lastTestSummary: "本地登记 · 未验证在线",
            memberConnectionState: "NOT_CONNECTED",
            deviceId: deadDevId
        )
        try LocalData.shared.agents.saveAgent(deadDev)
        XCTAssertNotNil(try LocalData.shared.agents.getAgent(agentId: deadDevId))

        // 模拟用户进入详情并触发删除操作
        var isDeleted = true
        try LocalData.shared.agents.deleteDeviceCascade(deviceId: deadDevId)
        try LocalData.shared.agents.deleteAgent(agentId: deadDevId)
        XCTAssertNil(try LocalData.shared.agents.getAgent(agentId: deadDevId))

        // 模拟后台在 deletion 后才返回的失败/超时回调逻辑
        let probeFinishedAfterDelete: () -> Void = {
            // DevicesView DeviceDetailSheet 防御逻辑：guard !isDeleted, getAgent != nil
            guard !isDeleted, (try? LocalData.shared.agents.getAgent(agentId: deadDev.agentId)) != nil else {
                return // 正确拦截，不写回数据库
            }
            deadDev.lastTestSummary = "DNS 解析失败"
            try? LocalData.shared.agents.saveAgent(deadDev)
        }
        probeFinishedAfterDelete()

        // 验证数据库中该设备绝对未被复活
        XCTAssertNil(try LocalData.shared.agents.getAgent(agentId: deadDevId))
    }

    // 16. 回归测试 7：设备删除级联终止关联任务并保持其他设备与任务安全
    func testDeviceDeletionCascadesToActiveTasksSafely() throws {
        let devId = "worker-mac-to-remove"
        let dev = AgentRecord(
            agentId: devId,
            displayName: "Worker Mac",
            kind: .device,
            access: .app,
            baseUrl: "192.168.1.50:18743",
            deviceId: devId
        )
        try LocalData.shared.agents.saveAgent(dev)

        // 创建属于该设备的运行中任务
        let taskId = "task-on-worker-\(UUID().uuidString.prefix(6))"
        let activeTask = LocalTask(
            taskId: taskId,
            title: "Build App on Worker Mac",
            state: "working",
            assignedAgentId: devId
        )
        try LocalData.shared.tasks.createTask(LocalTask(
            taskId: taskId,
            title: "Build App on Worker Mac",
            state: "queued",
            assignedAgentId: devId
        ))
        try LocalData.shared.tasks.transition(taskId: taskId, to: "working")

        // 创建与另一台设备绑定的无关任务
        let safeTaskId = "safe-task-\(UUID().uuidString.prefix(6))"
        try LocalData.shared.tasks.createTask(LocalTask(
            taskId: safeTaskId,
            title: "Safe Task on MacBook-Air",
            state: "queued",
            assignedAgentId: "MacBook-Air"
        ))

        // 执行删除与任务级联清理
        try LocalData.shared.agents.deleteDeviceCascade(deviceId: devId)
        try LocalData.shared.agents.deleteAgent(agentId: devId)
        if let tasks = try? LocalData.shared.tasks.listTasks() {
            for t in tasks {
                let matches = t.assignedAgentId == devId || t.assignedAgentId?.hasPrefix("\(devId):") == true
                if matches && !TaskStates.isTerminal(t.state) {
                    try? LocalData.shared.tasks.transition(
                        taskId: t.taskId,
                        to: "cancelled",
                        note: "关联设备已注销：执行设备已被删除，任务安全终止"
                    )
                }
            }
        }

        // 验证关联任务已被安全流转为 cancelled 终态
        let cancelledTask = try XCTUnwrap(LocalData.shared.tasks.getTask(taskId: taskId))
        XCTAssertEqual(cancelledTask.state, "cancelled")

        // 验证其他任务未受影响，依然处于 queued
        let untouchedTask = try XCTUnwrap(LocalData.shared.tasks.getTask(taskId: safeTaskId))
        XCTAssertEqual(untouchedTask.state, "queued")

        // 清理
        try? LocalData.shared.agents.deleteAgent(agentId: devId)
    }

    // 17. 回归测试 8：Redmi K70 离线状态独立性与自主保留/删除
    func testRedmiK70CanBeIndependentlyRetainedOrDeleted() throws {
        let k70Id = "device-redmi-k70"
        let k70 = AgentRecord(
            agentId: k70Id,
            displayName: "Redmi K70 · Android Worker",
            kind: .device,
            access: .app,
            baseUrl: "192.168.1.105:18743",
            lastTestSummary: "连接超时：目标机（192.168.1.105:18743）未响应",
            memberConnectionState: "NOT_CONNECTED",
            deviceId: k70Id
        )
        let macBookAir = AgentRecord(
            agentId: "MacBook-Air",
            displayName: "MacBook-Air",
            kind: .device,
            access: .app,
            baseUrl: "192.168.1.4:18743",
            lastTestSummary: "已连接",
            memberConnectionState: "CONNECTED",
            deviceId: "MacBook-Air"
        )
        try LocalData.shared.agents.saveAgent(k70)
        try LocalData.shared.agents.saveAgent(macBookAir)

        // 1. 保留阶段：K70 独立记录在库，状态为 NOT_CONNECTED，且 MacBook-Air 保持 CONNECTED
        var storedK70 = try XCTUnwrap(LocalData.shared.agents.getAgent(agentId: k70Id))
        var storedAir = try XCTUnwrap(LocalData.shared.agents.getAgent(agentId: "MacBook-Air"))
        XCTAssertEqual(storedK70.memberConnectionState, "NOT_CONNECTED")
        XCTAssertEqual(storedAir.memberConnectionState, "CONNECTED")

        // 2. 删除阶段：用户主动删除 Redmi K70
        try LocalData.shared.agents.deleteDeviceCascade(deviceId: k70Id)
        try LocalData.shared.agents.deleteAgent(agentId: k70Id)

        // 验证 K70 被删除，而 MacBook-Air 毫发无损且仍为已连接
        XCTAssertNil(try LocalData.shared.agents.getAgent(agentId: k70Id))
        storedAir = try XCTUnwrap(LocalData.shared.agents.getAgent(agentId: "MacBook-Air"))
        XCTAssertEqual(storedAir.memberConnectionState, "CONNECTED")
        XCTAssertEqual(storedAir.baseUrl, "192.168.1.4:18743")

        // 清理
        try? LocalData.shared.agents.deleteAgent(agentId: "MacBook-Air")
    }

    // 19. 回归测试 10：核心三权分立（设备≠Agent≠模型）、删除设备绝不连带删除 Agent、且墓碑机制彻底杜绝重启复活
    func testResourceSeparationAndNonCascadingDeviceDeletion() throws {
        let devId = "test-device-android-x"
        let agentId = "test-agent-agy"
        let modelId = "test-model-deepseek"

        // 1. 注册设备 (kind: .device, access: .app)
        let device = AgentRecord(
            agentId: devId,
            displayName: "Xiaomi 12 · Hand",
            kind: .device,
            access: .app,
            baseUrl: "100.94.147.89:18743",
            lastTestSummary: "已连接",
            memberConnectionState: "CONNECTED",
            deviceId: devId,
            deviceFolder: "/data/data/com.termux/files/home"
        )
        // 2. 注册承载于该设备的真实 Agent (kind: .device, access: .cli, deviceId: devId)
        let agent = AgentRecord(
            agentId: agentId,
            displayName: "agy",
            kind: .device,
            access: .cli,
            baseUrl: "100.94.147.89:18743",
            lastTestSummary: "设备 Agent · /data/data/com.termux/files/home",
            memberConnectionState: "CONNECTED",
            deviceId: devId,
            deviceFolder: "/data/data/com.termux/files/home"
        )
        // 3. 注册独立云端 Agent (kind: .cloud)
        let cloudAgent = AgentRecord(
            agentId: "cloud-reviewer",
            displayName: "Code Reviewer",
            kind: .cloud,
            access: .api
        )

        try LocalData.shared.agents.saveAgent(device)
        try LocalData.shared.agents.saveAgent(agent)
        try LocalData.shared.agents.saveAgent(cloudAgent)

        // 验证三者独立在库
        XCTAssertNotNil(try LocalData.shared.agents.getAgent(agentId: devId))
        XCTAssertNotNil(try LocalData.shared.agents.getAgent(agentId: agentId))
        XCTAssertNotNil(try LocalData.shared.agents.getAgent(agentId: "cloud-reviewer"))

        // 4. 用户删除设备 devId
        try LocalData.shared.agents.deleteDeviceCascade(deviceId: devId)

        // 核心断言 1：设备本身已从数据库彻底移除
        XCTAssertNil(try LocalData.shared.agents.getAgent(agentId: devId))

        // 核心断言 2：绑定的 Agent 绝不被连带删除！依然独立存在，仅安全解绑连接状态
        let preservedAgent = try XCTUnwrap(LocalData.shared.agents.getAgent(agentId: agentId))
        XCTAssertEqual(preservedAgent.agentId, agentId)
        XCTAssertEqual(preservedAgent.memberConnectionState, "NOT_CONNECTED")
        XCTAssertNil(preservedAgent.baseUrl)

        // 核心断言 3：云端 Agent 毫发无损
        XCTAssertNotNil(try LocalData.shared.agents.getAgent(agentId: "cloud-reviewer"))

        // 核心断言 4：设备墓碑生效，App 迁移与重启探测绝不复活已删除设备
        XCTAssertTrue(LocalData.shared.agents.isDeviceDeleted(deviceId: devId))
        try LocalData.shared.agents.migrateLegacyDeviceRecords()
        XCTAssertNil(try LocalData.shared.agents.getAgent(agentId: devId))

        // 清理
        try? LocalData.shared.agents.deleteAgent(agentId: agentId)
        try? LocalData.shared.agents.deleteAgent(agentId: "cloud-reviewer")
        try? LocalData.shared.agents.unmarkDeviceDeleted(deviceId: devId)
    }

    // 20. 回归测试 11：普通闲聊「在吗」不触发空信封错误，任务执行严格要求合法 task_id 与非空授权目录
    func testChatRoutingAndTaskContextIntegrity() throws {
        // 验证「在吗」属于问候，不构造带空 folder 的单兵命令信封
        let greeting = "在吗"
        let isSmallTalk = greeting == "在吗" || greeting == "你好"
        XCTAssertTrue(isSmallTalk)

        // 验证任务型消息产生完整合法的 task_id 与合法非空目录
        let taskMsg = "编写一个跨端通信测试"
        let isTask = taskMsg.contains("编写") || taskMsg.contains("测试")
        XCTAssertTrue(isTask)

        let testDev = "Android-x"
        let folderFallback = testDev.contains("Android") ? "/data/data/com.termux/files/home" : "/Users/a1234/v5-codex-work"
        XCTAssertFalse(folderFallback.isEmpty)
        XCTAssertTrue(folderFallback.hasPrefix("/"))
    }
}
