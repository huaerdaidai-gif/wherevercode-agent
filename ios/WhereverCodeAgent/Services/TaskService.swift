// WhereverCode Agent — iOS MVP
//
// Task lifecycle over MCP (verified against the live server, 2026-11-13):
// - `list_my_tasks` `{}` -> `{"tasks": [...], "note": ...}`
// - `claim_task` `{task_id, expected_version?, attempt_id?}` — one
//   task at a time; lease is 2 hours, renewed by progress/heartbeat.
// - `report_progress` `{task_id, text (max 2000), ...}` — progress IS
//   the heartbeat and renews the lease.
// - `heartbeat` `{task_id, ...}` — renews the lease without a record.
// - `submit_result` `{task_id, summary (max 8000), branch|commit|
//   pr_url (at least one for dev tasks), verdict?}` — verdict is only
//   `approve` or `changes_requested`, for review tasks.
// - `release_task` `{task_id, reason?, ...}` — give a task back.
// - `attach_delivery_diff` / `get_delivery_diff` exist on the server
//   but are delivery/review plumbing, not part of this MVP's UI; they
//   are intentionally NOT wrapped here yet (see ApprovalGate).
//
// There is NO `create_task` / `assign_task` on the server: the app
// cannot invent tasks, it can only work the ones assigned to its token.

import Foundation

public struct TaskService {
    private let client: McpClient

    public init(client: McpClient) {
        self.client = client
    }

    /// Tasks assigned to this token, in claim order.
    public func listMyTasks() async throws -> [AgentTask] {
        let result = try await client.callTool(name: "list_my_tasks")
        return AgentTask.list(from: result)
    }

    /// Claim one task. Pass the version from `listMyTasks` when known
    /// so a stale claim is rejected instead of silently applied.
    public func claim(taskID: String, expectedVersion: Int? = nil) async throws -> [String: Any] {
        var args: [String: Any] = ["task_id": taskID, "attempt_id": UUID().uuidString]
        if let expectedVersion { args["expected_version"] = expectedVersion }
        return try await client.callTool(name: "claim_task", arguments: args)
    }

    /// Report progress (also renews the 2-hour lease).
    public func reportProgress(taskID: String, text: String, expectedVersion: Int? = nil) async throws {
        var args: [String: Any] = [
            "task_id": taskID,
            "text": String(text.prefix(2000)),
            "attempt_id": UUID().uuidString,
        ]
        if let expectedVersion { args["expected_version"] = expectedVersion }
        _ = try await client.callTool(name: "report_progress", arguments: args)
    }

    /// Renew the lease without producing a progress record.
    public func heartbeat(taskID: String, expectedVersion: Int? = nil) async throws {
        var args: [String: Any] = ["task_id": taskID, "attempt_id": UUID().uuidString]
        if let expectedVersion { args["expected_version"] = expectedVersion }
        _ = try await client.callTool(name: "heartbeat", arguments: args)
    }

    /// Submit a result. For dev tasks the server requires at least one
    /// of `branch` / `commit` / `pr_url` as evidence; the UI enforces
    /// this before calling. `verdict` is only for review tasks.
    public func submitResult(
        taskID: String,
        summary: String,
        branch: String? = nil,
        commit: String? = nil,
        prURL: String? = nil,
        verdict: String? = nil,
        expectedVersion: Int? = nil
    ) async throws -> [String: Any] {
        var args: [String: Any] = [
            "task_id": taskID,
            "summary": String(summary.prefix(8000)),
            "attempt_id": UUID().uuidString,
        ]
        if let branch, !branch.isEmpty { args["branch"] = branch }
        if let commit, !commit.isEmpty { args["commit"] = commit }
        if let prURL, !prURL.isEmpty { args["pr_url"] = prURL }
        if let verdict, !verdict.isEmpty { args["verdict"] = verdict }
        if let expectedVersion { args["expected_version"] = expectedVersion }
        return try await client.callTool(name: "submit_result", arguments: args)
    }

    /// Give a task back with a reason. Never just abandon a claim.
    public func release(taskID: String, reason: String? = nil) async throws {
        var args: [String: Any] = ["task_id": taskID, "attempt_id": UUID().uuidString]
        if let reason, !reason.isEmpty { args["reason"] = String(reason.prefix(2000)) }
        _ = try await client.callTool(name: "release_task", arguments: args)
    }
}
