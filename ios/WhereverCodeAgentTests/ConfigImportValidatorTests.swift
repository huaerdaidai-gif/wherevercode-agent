import Foundation
import XCTest
@testable import WhereverCodeAgent

final class ConfigImportValidatorTests: XCTestCase {
    private func fixture(_ name: String) throws -> Data {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: name, withExtension: "json", subdirectory: "Fixtures"))
        return try Data(contentsOf: url)
    }

    func testAcceptsAbsentNullableAndEmptySectionsFixtures() throws {
        let nullableData = try fixture("config-bundle-v1-absent-nullable")
        let nullableBundle = try ConfigBundleV1.decode(nullableData)
        XCTAssertNil(nullableBundle.payload.projects.first?.repoUrl)
        XCTAssertNil(nullableBundle.payload.appSettings.brainDisplayName)
        let nullable = try ConfigImportValidator.validate(nullableData)
        XCTAssertEqual(nullable.addCount, 1)
        let empty = try ConfigImportValidator.validate(fixture("config-bundle-v1-empty-sections"))
        XCTAssertTrue(empty.items.isEmpty)
    }

    func testBuildsDryRunDiffAndSensitiveWarnings() throws {
        let data = try fixture("config-bundle-v1-minimal")
        let bundle = try ConfigBundleV1.decode(data)
        let snapshot = bundle.payload
        let plan = try ConfigImportValidator.validate(data, existing: snapshot)
        XCTAssertEqual(plan.addCount, 0)
        XCTAssertGreaterThan(plan.unchangedCount, 0)
        XCTAssertEqual(snapshot, bundle.payload)
        XCTAssertTrue(plan.warnings.contains { $0.contains("endpoint") })
        XCTAssertTrue(plan.warnings.contains { $0.contains("path hint") })
    }

    func testSameNameDifferentIdCreatesConflictEdge() throws {
        let existing = BundlePayloadV1(projects: [BundleProjectV1(projectId: "local-project", name: "示例项目")])
        let plan = try ConfigImportValidator.validate(fixture("config-bundle-v1-minimal"), existing: existing)
        let project = try XCTUnwrap(plan.items.first { $0.category == "project" })
        XCTAssertEqual(project.action, .conflict)
        XCTAssertEqual(project.conflictsWith, ["local-project"])
    }

    func testRejectsUnknownSchemaAndSecretsPolicy() throws {
        let source = try XCTUnwrap(JSONSerialization.jsonObject(with: fixture("config-bundle-v1-empty-sections")) as? [String: Any])
        var badVersion = source; badVersion["schemaVersion"] = 99
        XCTAssertThrowsError(try ConfigImportValidator.validate(JSONSerialization.data(withJSONObject: badVersion))) { XCTAssertEqual(($0 as? ConfigImportRejected)?.code, "unsupportedSchema") }
        var badPolicy = source; badPolicy["secretsPolicy"] = "allowSecrets"
        XCTAssertThrowsError(try ConfigImportValidator.validate(JSONSerialization.data(withJSONObject: badPolicy))) { XCTAssertEqual(($0 as? ConfigImportRejected)?.code, "secretsPolicyViolation") }
    }

    func testRejectsMalformedJSONAndMissingRequiredSection() throws {
        XCTAssertThrowsError(try ConfigImportValidator.validate(Data("{broken".utf8)))
        var source = try XCTUnwrap(JSONSerialization.jsonObject(with: fixture("config-bundle-v1-empty-sections")) as? [String: Any])
        var payload = try XCTUnwrap(source["payload"] as? [String: Any]); payload.removeValue(forKey: "agents"); source["payload"] = payload
        XCTAssertThrowsError(try ConfigImportValidator.validate(JSONSerialization.data(withJSONObject: source)))
    }

    func testRejectsSecretsUnclosedReferencesAndUserInfo() throws {
        var source = try XCTUnwrap(JSONSerialization.jsonObject(with: fixture("config-bundle-v1-empty-sections")) as? [String: Any])
        var payload = try XCTUnwrap(source["payload"] as? [String: Any]); payload["apiKey"] = "fake-value"; source["payload"] = payload
        XCTAssertThrowsError(try ConfigImportValidator.validate(JSONSerialization.data(withJSONObject: source))) { XCTAssertEqual(($0 as? ConfigImportRejected)?.code, "secretDetected") }

        source = try XCTUnwrap(JSONSerialization.jsonObject(with: fixture("config-bundle-v1-minimal")) as? [String: Any])
        payload = try XCTUnwrap(source["payload"] as? [String: Any])
        var agents = try XCTUnwrap(payload["agents"] as? [[String: Any]]); agents[0]["authorizedProjectIds"] = ["missing"]; payload["agents"] = agents; source["payload"] = payload
        XCTAssertThrowsError(try ConfigImportValidator.validate(JSONSerialization.data(withJSONObject: source))) { XCTAssertEqual(($0 as? ConfigImportRejected)?.code, "unclosedReference") }

        source = try XCTUnwrap(JSONSerialization.jsonObject(with: fixture("config-bundle-v1-minimal")) as? [String: Any])
        payload = try XCTUnwrap(source["payload"] as? [String: Any]); var projects = try XCTUnwrap(payload["projects"] as? [[String: Any]])
        projects[0]["repoUrl"] = "https://user:password@example.invalid/repo"; payload["projects"] = projects; source["payload"] = payload
        XCTAssertThrowsError(try ConfigImportValidator.validate(JSONSerialization.data(withJSONObject: source))) { XCTAssertEqual(($0 as? ConfigImportRejected)?.code, "urlUserInfo") }
    }

    func testRejectsPathTraversal() throws {
        var source = try XCTUnwrap(JSONSerialization.jsonObject(with: fixture("config-bundle-v1-minimal")) as? [String: Any])
        var payload = try XCTUnwrap(source["payload"] as? [String: Any]); var projects = try XCTUnwrap(payload["projects"] as? [[String: Any]])
        var homes = try XCTUnwrap(projects[0]["homes"] as? [[String: Any]]); homes[0]["pathHint"] = "../../private"; projects[0]["homes"] = homes
        payload["projects"] = projects; source["payload"] = payload
        XCTAssertThrowsError(try ConfigImportValidator.validate(JSONSerialization.data(withJSONObject: source))) { XCTAssertEqual(($0 as? ConfigImportRejected)?.code, "unsafePath") }
    }
}
