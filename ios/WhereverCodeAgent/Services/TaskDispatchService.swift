// WhereverCode Agent — iOS
//
// 任务派工服务（spec §2）：16 个工具的统一入口，由
// LocalChannelService.dispatch 的一行钩子按工具名路由进来，
// 内网门面经频道服务转发、同一条路径。
//
// 作用域（spec §6）：owner 见全部 16 个工具；agent 只见干活侧
// 9 个，调派单侧工具按「未知工具」拒绝（与工具不存在同口径，
// 不泄露存在性）。tools/list 的名单由 toolNames 按作用域给，
// 门面（进程内/内网）共用这一份。
//
// 工具行为按 W4–W11 逐包落地；未落地的工具如实抛「任务派工
// 尚未启用」，不假成功。

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

    public static let scopeOwner = "owner"
    public static let scopeAgent = "agent"

    public static func isTaskTool(_ tool: String) -> Bool { allTools.contains(tool) }

    /// tools/list 的名单：owner = 干活 9 + 派单 7（顺序同 spec §6）。
    public static func toolNames(callerScope: String) -> [String] {
        callerScope == scopeOwner ? workerTools + ownerTools : workerTools
    }

    public let store: TaskDispatchStore
    private let directory: TaskAgentDirectory?
    private let bridge: TaskChannelBridge?
    private let clock: () -> Double
    private let leaseSeconds: Double
    private let lock = NSRecursiveLock()

    public init(store: TaskDispatchStore, directory: TaskAgentDirectory?,
                bridge: TaskChannelBridge?, clock: @escaping () -> Double,
                leaseSeconds: Double = TaskDispatchSpec.leaseSeconds) {
        self.store = store
        self.directory = directory
        self.bridge = bridge
        self.clock = clock
        self.leaseSeconds = leaseSeconds
    }

    public func dispatch(tool: String, args: [String: Any], callerId: String,
                         callerName: String, callerScope: String) throws -> [String: Any] {
        lock.lock(); defer { lock.unlock() }
        if !Self.isTaskTool(tool) { throw TaskDispatchError("未知工具") }
        if Self.ownerTools.contains(tool) && callerScope != Self.scopeOwner {
            throw TaskDispatchError("未知工具")
        }
        throw TaskDispatchError("任务派工尚未启用")
    }

    /// UI 软删除（spec §2.17）：不是 MCP 工具，只供任务页调用。W11 落地。
    public func uiDeleteTask(args: [String: Any], callerId: String) throws -> [String: Any] {
        lock.lock(); defer { lock.unlock() }
        throw TaskDispatchError("任务派工尚未启用")
    }
}
