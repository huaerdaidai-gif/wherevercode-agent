import Foundation
import XCTest
@testable import WhereverCodeAgent

final class ConfigBundleV1Tests: XCTestCase {
    private func fixtureData() throws -> Data {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "config-bundle-v1-minimal", withExtension: "json", subdirectory: "Fixtures"))
        return try Data(contentsOf: url)
    }

    func testDecodesSharedGoldenFixtureAndRoundTrips() throws {
        let input = try ConfigBundleV1.decode(fixtureData())
        XCTAssertEqual(input.format, ConfigBundleV1.formatName)
        XCTAssertEqual(input.schemaVersion, 1)
        XCTAssertEqual(input.bundleId, "fixture-bundle-0001")
        XCTAssertEqual(input.payload.devices.first?.sourceDeviceId, "device-demo-1")
        XCTAssertEqual(input.payload.agents.first?.agentId, "agent-demo")
        XCTAssertEqual(input.payload.projects.first?.projectId, "demo-project")
        XCTAssertEqual(input.payload.brains.first?.brainId, "brain-demo")
        XCTAssertEqual(input.payload.appSettings.brainDisplayName, "示例")
        XCTAssertEqual(input.payload.brains.first?.credentialState, "reauthorizationRequired")
        let output = try input.encode()
        XCTAssertEqual(try ConfigBundleV1.decode(output), input)
        let serialized = try XCTUnwrap(String(data: output, encoding: .utf8))
        XCTAssertTrue(serialized.contains("\"brainDisplayName\":\"示例\""))
        XCTAssertFalse(serialized.contains("platformBaseUrl"))
        XCTAssertFalse(serialized.contains("freedomLevel"))
        XCTAssertFalse(serialized.contains("drawerOrder"))
        XCTAssertFalse(serialized.contains("apiKey"))
        XCTAssertFalse(serialized.contains("accessToken"))
        XCTAssertFalse(serialized.contains("pairingToken"))
    }

    func testRejectsUnknownFormatAndVersion() throws {
        let data = try fixtureData()
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        var badFormat = object
        badFormat["format"] = "other"
        XCTAssertThrowsError(try ConfigBundleV1.decode(JSONSerialization.data(withJSONObject: badFormat)))
        var badVersion = object
        badVersion["schemaVersion"] = 2
        XCTAssertThrowsError(try ConfigBundleV1.decode(JSONSerialization.data(withJSONObject: badVersion)))
    }
}
