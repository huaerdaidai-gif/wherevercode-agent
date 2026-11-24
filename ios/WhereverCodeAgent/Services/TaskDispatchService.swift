// WhereverCode Agent — iOS
//
// 任务派工服务（spec §2）：16 个工具的统一入口，由
// LocalChannelService.dispatch 的一行钩子按工具名路由进来，
// 内网门面经频道服务转发、同一条路径。
//
// W1 脚手架阶段：工具名表与路由就位，具体工具行为按 W3–W9
// 逐包落地；未落地的工具如实抛「任务派工尚未启用」，不假成功。
// 角色化暴露（干活侧只见 9 个）在 W12 接入 tools/list 与本入口。

import Foundation

/// 派单侧建单时查 Agent 是否存在、取显示名（start_task 的 agent/reviewer 校验）。
public typealias TaskAgentDirectory = (_ agentId: String) -> String?

public final class TaskDispatchService {
    /// 干活侧 9 个（spec §6 的顺序即 tools/list 顺序）。
    public static let workerTools: [String] = [
        "list_my_tasks", "claim_task", "release_task", "report_progress",
        "report_status", "submit_result", "attach_delivery_diff",
        "get_delivery_diff", "heartbeat",
    ]

    /// 派单侧 7 个（owner 作用域）。
    public static let ownerTools: [String] = [
        "start_task", "get_task", "list_tasks", "decide_task",
        "update_task", "withdraw_task", "reopen_task",
    ]

    public static let allTools: Set<String> = Set(workerTools + ownerTools)

    public static func isTaskTool(_ tool: String) -> Bool { allTools.contains(tool) }

    private let store: TaskDispatchStore
    private let directory: TaskAgentDirectory?
    private let bridge: TaskChannelBridge?
    private let clock: () -> Double
    private let lock = NSRecursiveLock()

    public init(store: TaskDispatchStore, directory: TaskAgentDirectory?,
                bridge: TaskChannelBridge?, clock: @escaping () -> Double) {
        self.store = store
        self.directory = directory
        self.bridge = bridge
        self.clock = clock
    }

    public func dispatch(tool: String, args: [String: Any], callerId: String,
                         callerName: String) throws -> [String: Any] {
        lock.lock(); defer { lock.unlock() }
        if !Self.isTaskTool(tool) { throw TaskDispatchError("未知工具") }
        throw TaskDispatchError("任务派工尚未启用")
    }
}
