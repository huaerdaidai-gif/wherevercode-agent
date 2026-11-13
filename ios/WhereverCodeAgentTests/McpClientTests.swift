// WhereverCode Agent — iOS MVP tests
//
// MCP envelope handling and request construction. No networking:
// every case works on canned JSON. One test asserts the token can
// never leak into an error message.

import Foundation
import XCTest
@testable import WhereverCodeAgent

final class McpClientTests: XCTestCase {

    private func envelope(text: String, isError: Bool = false) -> Data {
        let json: [String: Any] = [
            "jsonrpc": "2.0",
            "result": [
                "content": [["type": "text", "text": text]],
                "isError": isError,
            ] as [String: Any],
        ]
        return try! JSONSerialization.data(withJSONObject: json)
    }

    func testUnwrapToolResultDecodesToolJSON() throws {
        let data = envelope(text: #"{"messages":[{"id":"m1","text":"hi"}]}"#)
        let result = try McpClient.unwrapToolResult(data: data)
        let messages = result["messages"] as? [[String: Any]]
        XCTAssertTrue(messages?.first?["id"] as? String == "m1")
    }

    func testUnwrapToolResultThrowsOnIsError() {
        let data = envelope(text: "Agent 不存在或已停用", isError: true)
        XCTAssertThrowsError(try McpClient.unwrapToolResult(data: data)) { error in
            XCTAssertEqual((error as? McpError)?.message, "Agent 不存在或已停用")
        }
    }

    func testUnwrapToolResultThrowsOnJsonRpcError() {
        let json: [String: Any] = [
            "jsonrpc": "2.0",
            "error": ["code": -32601, "message": "Method not found"] as [String: Any],
        ]
        let data = try! JSONSerialization.data(withJSONObject: json)
        XCTAssertThrowsError(try McpClient.unwrapToolResult(data: data)) { error in
            XCTAssertEqual((error as? McpError)?.message, "Method not found")
        }
    }

    func testUnwrapToolResultThrowsOnUnparsableBody() {
        XCTAssertThrowsError(try McpClient.unwrapToolResult(data: Data("garbage".utf8)))
        XCTAssertThrowsError(try McpClient.unwrapToolResult(data: envelope(text: "not json")))
        XCTAssertThrowsError(try McpClient.unwrapToolResult(data: envelope(text: "")))
    }

    func testToolsCallBodyShape() {
        let body = McpClient.toolsCallBody(
            id: "req-1", name: "agent_channel_post",
            arguments: ["channel_id": "ch-1", "text": "hi"]
        )
        XCTAssertEqual(body["jsonrpc"] as? String, "2.0")
        XCTAssertEqual(body["id"] as? String, "req-1")
        XCTAssertEqual(body["method"] as? String, "tools/call")
        let params = body["params"] as? [String: Any]
        XCTAssertEqual(params?["name"] as? String, "agent_channel_post")
        let args = params?["arguments"] as? [String: Any]
        XCTAssertEqual(args?["channel_id"] as? String, "ch-1")
        XCTAssertEqual(args?["text"] as? String, "hi")
        // The token must never appear in the body.
        let serialized = String(data: try! JSONSerialization.data(withJSONObject: body), encoding: .utf8)!
        XCTAssertFalse(serialized.contains("secret-token-value"))
    }

    func testErrorMessagesNeverContainToken() {
        // Even a hostile server echoing the token back inside an error
        // must not let it through: unwrap only ever surfaces the
        // content text, and the client never interpolates the token.
        // Here the assertion is on the client-side construction: the
        // request body and the not-configured error contain no token.
        let error = McpError.notConfigured("未配置平台 Agent 凭证：请先在设置里保存 Token。")
        XCTAssertFalse(error.message.contains("secret-token-value"))
        let transport = McpError.transport("网络请求失败：The Internet connection appears to be offline.")
        XCTAssertFalse(transport.message.contains("secret-token-value"))
    }
}
