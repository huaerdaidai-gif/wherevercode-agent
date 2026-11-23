// WhereverCode Agent — iOS tests
//
// 内网地址判定与平台地址校验（频道三层二期·内网层）：与安卓
// PlatformAgent isLanAddress + hostFromAddress 的内网用例同口径——
// 内网 http 放行、公网 http 拒绝、WS 协议随地址切换。

import Foundation
import XCTest
@testable import WhereverCodeAgent

final class LanAddressTests: XCTestCase {

    func testLanRanges() {
        XCTAssertTrue(LanAddress.isLanAddress("192.168.1.7"))
        XCTAssertTrue(LanAddress.isLanAddress("192.168.1.7:18744"))
        XCTAssertTrue(LanAddress.isLanAddress("10.0.0.5"))
        XCTAssertTrue(LanAddress.isLanAddress("172.16.0.9"))
        XCTAssertTrue(LanAddress.isLanAddress("172.31.255.1"))
        XCTAssertTrue(LanAddress.isLanAddress("100.75.41.0"))
        XCTAssertTrue(LanAddress.isLanAddress("127.0.0.1"))
        XCTAssertTrue(LanAddress.isLanAddress("localhost"))
        XCTAssertTrue(LanAddress.isLanAddress("mac.local"))
        XCTAssertFalse(LanAddress.isLanAddress("172.32.0.1"))
        XCTAssertFalse(LanAddress.isLanAddress("100.128.0.1"))
        XCTAssertFalse(LanAddress.isLanAddress("8.8.8.8"))
        XCTAssertFalse(LanAddress.isLanAddress("v5.cococolo.xyz"))
        XCTAssertFalse(LanAddress.isLanAddress(""))
        XCTAssertFalse(LanAddress.isLanAddress(nil))
        XCTAssertFalse(LanAddress.isLanAddress("192.168.1.999"))
        XCTAssertFalse(LanAddress.isLanAddress("192.168.1"))
    }

    func testAppSettingsAcceptsLanHttpAndRejectsPublicHttp() {
        var settings = AppSettings()
        settings.platformBaseURLString = "http://192.168.1.7:18744"
        XCTAssertEqual(settings.validatedBaseURL?.absoluteString, "http://192.168.1.7:18744")
        XCTAssertEqual(settings.mcpEndpoint?.absoluteString, "http://192.168.1.7:18744/api/open/v1/mcp")
        let ws = settings.channelWebSocketURL(channelID: "c1")
        XCTAssertEqual(ws?.scheme, "ws")
        XCTAssertEqual(ws?.host, "192.168.1.7")

        settings.platformBaseURLString = "http://v5.example"
        XCTAssertNil(settings.validatedBaseURL)
        settings.platformBaseURLString = "http://8.8.8.8:18744"
        XCTAssertNil(settings.validatedBaseURL)

        settings.platformBaseURLString = "https://v5.cococolo.xyz"
        XCTAssertEqual(settings.validatedBaseURL?.scheme, "https")
        XCTAssertEqual(settings.channelWebSocketURL(channelID: "c1")?.scheme, "wss")
    }
}
