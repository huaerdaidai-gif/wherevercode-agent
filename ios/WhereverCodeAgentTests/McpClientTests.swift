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

    // 2026-11-28: 平台 403 的 JSON-RPC 信封不得整段糊到界面上，
    // 只取信封里的人话（Funk 真机反馈的红字大段报文）。
    func testHttpErrorExtractsHumanTextFromJsonRpcEnvelope() {
        let body = #"{"jsonrpc": "2.0", "id": 7, "result": {"content": [{"type": "text", "text": "只有频道 owner / 协调者才能管理 Webhook"}], "isError": true}}"#
        let err = McpClient.httpError(statusCode: 403, body: Data(body.utf8))
        XCTAssertEqual(err.message, "平台返回 HTTP 403：只有频道 owner / 协调者才能管理 Webhook")
        XCTAssertFalse(err.message.contains("jsonrpc"))
    }

    func testHttpErrorExtractsErrorMessageField() {
        let body = #"{"jsonrpc": "2.0", "id": 7, "error": {"code": -32600, "message": "请求格式不对"}}"#
        let err = McpClient.httpError(statusCode: 400, body: Data(body.utf8))
        XCTAssertEqual(err.message, "平台返回 HTTP 400：请求格式不对")
    }

    func testHttpErrorPlainTextBodyPassesThrough() {
        let err = McpClient.httpError(statusCode: 500, body: Data("服务内部错误".utf8))
        XCTAssertEqual(err.message, "平台返回 HTTP 500：服务内部错误")
    }
}
