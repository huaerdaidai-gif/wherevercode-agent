import Foundation

public struct TaskService: Sendable {
    public let client: McpClient

    public init(client: McpClient) {
        self.client = client
    }

    public func listMyTasks() async throws -> [AgentTask] {
        let res = try await client.callTool(name: "agent_my_tasks", arguments: [:])
        let items = (res["tasks"] as? [[String: Any]]) ?? []
        return items.compactMap { item in
            guard let id = (item["task_id"] as? String) ?? (item["id"] as? String) else { return nil }
            let title = (item["title"] as? String) ?? id
            let detail = (item["description"] as? String) ?? (item["detail"] as? String) ?? ""
            let status = (item["status"] as? String) ?? "queued"
            let version = (item["version"] as? Int) ?? 1
            return AgentTask(id: id, title: title, detail: detail, status: status, version: version)
        }
    }

    public func claim(taskID: String, expectedVersion: Int? = nil) async throws -> [String: Any] {
        var args: [String: Any] = ["task_id": taskID]
        if let expectedVersion { args["expected_version"] = expectedVersion }
        return try await client.callTool(name: "agent_task_claim", arguments: args)
    }

    public func reportProgress(taskID: String, text: String, expectedVersion: Int? = nil) async throws {
        var args: [String: Any] = ["task_id": taskID, "progress": text]
        if let expectedVersion { args["expected_version"] = expectedVersion }
        _ = try await client.callTool(name: "agent_task_progress", arguments: args)
    }

    public func heartbeat(taskID: String, expectedVersion: Int? = nil) async throws {
        var args: [String: Any] = ["task_id": taskID]
        if let expectedVersion { args["expected_version"] = expectedVersion }
        _ = try await client.callTool(name: "agent_task_heartbeat", arguments: args)
    }

    public func submitResult(
        taskID: String,
        summary: String,
        branch: String?,
        commit: String?,
        prURL: String?,
        verdict: String?,
        expectedVersion: Int? = nil
    ) async throws -> [String: Any] {
        var args: [String: Any] = ["task_id": taskID, "summary": summary]
        if let branch, !branch.isEmpty { args["branch"] = branch }
        if let commit, !commit.isEmpty { args["commit"] = commit }
        if let prURL, !prURL.isEmpty { args["pr_url"] = prURL }
        if let verdict, !verdict.isEmpty { args["verdict"] = verdict }
        if let expectedVersion { args["expected_version"] = expectedVersion }
        return try await client.callTool(name: "agent_task_submit", arguments: args)
    }

    public func release(taskID: String, reason: String? = nil) async throws {
        var args: [String: Any] = ["task_id": taskID]
        if let reason, !reason.isEmpty { args["reason"] = reason }
        _ = try await client.callTool(name: "agent_task_release", arguments: args)
    }
}
