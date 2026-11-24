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
// 语义基线 = v5_platform.py 现行行为（A 组夹具对拍）：校验顺序、
// version +1 时机、事件文案逐项照基线。失败回滚口径也照基线
// 事务边界：租约清扫先行落盘（基线单独 commit），其余变更在
// 工具成功后才落盘，抛错时回到清扫后的快照。
//
// 落地进度：W4/W5 = 租约清扫 + start/get/list/decide + 干活侧
// 读与领活链（list_my/claim/release/progress/heartbeat/
// report_status）；W6 = 交付链（submit/attach/get_delivery_diff）；
// W9 = update/withdraw/reopen。16 工具已全量落地。

import Foundation

/// 派单侧建单时查 Agent 是否存在、取显示名（start_task 的 agent/reviewer 校验）。
public typealias TaskAgentDirectory = (_ agentId: String) -> String?

/// Agent 名册口：角色查询（attach 的 pack 角色门、submit 找 pack
/// Agent）。生产由 LCS 的 Agent 注册表提供；未接名册时 pack 链
/// 不可用（submit 走无 pack 路径、attach 如实报 pack_role_required）。
public protocol TaskAgentRoster {
    /// 角色列表；Agent 不存在返回 nil。
    func agentRoles(_ agentId: String) -> [String]?
    /// 注册顺序里第一个带该角色的 Agent；没有返回 nil。
    func firstAgent(withRole role: String) -> (id: String, name: String)?
}

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

    private static let workInstructions =
        "完成 goal，不要直接推 main。中途用 report_progress 报进度（同时续租 2 小时），" +
        "也可以用 heartbeat 只续租不报进度；完成后用 submit_result 交付：" +
        "summary 写做了什么、怎么验证的，并给出 branch、commit 或 pr_url 其中至少一个作为证据。"

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
    private let roster: TaskAgentRoster?
    private let channelOwnerChecker: ((String, String) -> Bool)?
    private let lock = NSRecursiveLock()

    /// 失败回滚点：dispatch 入口先置为当前快照，租约清扫落盘后前移。
    private var rollbackPoint: String

    public init(store: TaskDispatchStore, directory: TaskAgentDirectory?,
                bridge: TaskChannelBridge?, clock: @escaping () -> Double,
                leaseSeconds: Double = TaskDispatchSpec.leaseSeconds,
                roster: TaskAgentRoster? = nil,
                channelOwnerChecker: ((String, String) -> Bool)? = nil) {
        self.store = store
        self.directory = directory
        self.bridge = bridge
        self.clock = clock
        self.leaseSeconds = leaseSeconds
        self.roster = roster
        self.channelOwnerChecker = channelOwnerChecker
        self.rollbackPoint = store.snapshotText()
    }

    public func dispatch(tool: String, args: [String: Any], callerId: String,
                         callerName: String, callerScope: String) throws -> [String: Any] {
        lock.lock(); defer { lock.unlock() }
        if !Self.isTaskTool(tool) { throw TaskDispatchError("未知工具") }
        if Self.ownerTools.contains(tool) && callerScope != Self.scopeOwner {
            throw TaskDispatchError("未知工具")
        }
        // 基线在分发层先验 task_id 类型（对所有带 task_id 的工具一致）。
        if let raw = args["task_id"], !(raw is NSNull), !(raw is String) {
            throw TaskDispatchError("task_id 格式无效")
        }
        rollbackPoint = store.snapshotText()
        let seqBefore = store.eventSeq
        do {
            let result: [String: Any]
            switch tool {
            case "list_my_tasks": result = try listMyTasks(callerId: callerId)
            case "claim_task": result = try claimTask(args: args, callerId: callerId, callerName: callerName)
            case "release_task": result = try releaseTask(args: args, callerId: callerId, callerName: callerName)
            case "report_progress": result = try reportProgress(args: args, callerId: callerId, callerName: callerName)
            case "heartbeat": result = try heartbeat(args: args, callerId: callerId)
            case "report_status": result = try reportStatus(args: args, callerId: callerId)
            case "submit_result": result = try submitResult(args: args, callerId: callerId, callerName: callerName)
            case "attach_delivery_diff": result = try attachDelivery(args: args, callerId: callerId, callerName: callerName)
            case "get_delivery_diff": result = try getDeliveryDiff(args: args, callerId: callerId)
            case "start_task": result = try startTask(args: args, callerId: callerId)
            case "get_task": result = try getTask(args: args)
            case "list_tasks": result = try listTasks(args: args)
            case "decide_task": result = try decideTask(args: args)
            case "update_task": result = try updateTask(args: args, callerName: callerName)
            case "withdraw_task": result = try withdrawTask(args: args, callerName: callerName)
            case "reopen_task": result = try reopenTask(args: args, callerName: callerName)
            default: throw TaskDispatchError("任务派工尚未启用")
            }
            store.persist()
            writeBackEvents(afterSeq: seqBefore)
            return result
        } catch let error as TaskDispatchError {
            store.restoreFromText(rollbackPoint)
            throw error
        }
    }

    /// spec §5 写回事件清单（verdict 的 changes_requested 形态
    /// 记在 work 任务上、同名 kind 一并写回）。
    private static let writebackKinds: Set<String> = [
        "created", "claimed", "submitted", "verdict", "approved",
        "changes_requested", "decided", "withdrawn", "reopened",
        "reassigned", "lease_expired", "rounds_exceeded",
    ]

    /// spec §5 事件写回：本次调用新落账本的事件里，命中写回清单且
    /// 任务绑了频道的，反向写成频道消息（子任务经 parent 归属父任务
    /// 的频道，引用用父任务 id）。写回失败不回滚任务操作。
    private func writeBackEvents(afterSeq: Int64) {
        guard let bridge else { return }
        for event in store.events {
            if event.seq <= afterSeq { continue }
            if !Self.writebackKinds.contains(event.kind) { continue }
            guard let task = store.tasks[event.taskId] else { continue }
            let channelId = task.channelId
                ?? task.parentId.flatMap { store.tasks[$0]?.channelId }
            guard let channelId else { continue }
            let rootId = task.channelId != nil ? task.taskId : (task.parentId ?? task.taskId)
            bridge.postTaskEvent(channelId: channelId, taskId: rootId,
                                 kind: event.kind, text: event.text)
        }
    }

    /// UI 软删除（spec §2.17）：不是 MCP 工具，只供任务页调用。
    /// 仅终态 work 任务；仅建单人或频道 owner（频道 owner 由
    /// channelOwnerChecker 按 LCS 成员角色判定，未接时只认建单人）。
    /// 重复删除幂等成功（不 +version、不写事件，同 withdraw 口径）。
    /// list 排除、get_task 留墓碑。
    public func uiDeleteTask(args: [String: Any], callerId: String,
                             callerName: String? = nil) throws -> [String: Any] {
        lock.lock(); defer { lock.unlock() }
        rollbackPoint = store.snapshotText()
        do {
            guard let task = stringArg(args, "task_id").flatMap({ store.tasks[$0] }) else {
                throw TaskDispatchError("任务不存在", status: 404, code: "task_not_found")
            }
            let isChannelOwner = task.channelId.map {
                channelOwnerChecker?($0, callerId) == true
            } ?? false
            if callerId != task.createdBy && !isChannelOwner {
                throw TaskDispatchError(
                    "只有建单人或频道 owner 可以删除",
                    status: 403, code: "task_forbidden")
            }
            if task.kind != "work" {
                throw TaskDispatchError("只有工作任务可以删除")
            }
            if task.state != TaskDispatchSpec.approved &&
                task.state != TaskDispatchSpec.done &&
                task.state != TaskDispatchSpec.cancelled {
                throw TaskDispatchError(
                    "只有终态任务可以删除",
                    status: 409, code: "task_not_terminal")
            }
            if task.deletedAt == nil {
                task.deletedAt = now()
                task.deletedBy = callerId
                touch(task)
                event(taskId: task.taskId, who: callerName ?? callerId,
                      kind: "deleted", text: "任务已删除")
            }
            store.persist()
            return ["ok": true, "task": ownerView(task)]
        } catch let error as TaskDispatchError {
            store.restoreFromText(rollbackPoint)
            throw error
        }
    }

    // ---- 基础件 ----------------------------------------------------------

    private func now() -> Double { clock() }

    private func newId() -> String {
        UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
    }

    private func label(_ state: String) -> String {
        TaskDispatchSpec.stateLabels[state] ?? state
    }

    /// 基线 _update：version +1、updated_at 刷新。
    private func touch(_ task: TaskRecord) {
        task.version += 1
        task.updatedAt = now()
    }

    private func event(taskId: String, who: String?, kind: String, text: String) {
        store.appendEvent(taskId: taskId, at: now(), who: who, kind: kind, text: text)
    }

    private func rawArg(_ args: [String: Any], _ key: String) -> Any? {
        guard let value = args[key], !(value is NSNull) else { return nil }
        return value
    }

    private func stringArg(_ args: [String: Any], _ key: String) -> String? {
        rawArg(args, key) as? String
    }

    /// 基线 _text：必填须非空字符串，长度按原文计，返回去空白后的值；
    /// 可选时原样返回（不去空白），非字符串同样报错。
    private func textArg(_ args: [String: Any], _ key: String, field: String,
                         maxLen: Int, required: Bool = true) throws -> String? {
        guard let raw = rawArg(args, key) else {
            if required { throw TaskDispatchError("\(field) 不能为空") }
            return nil
        }
        guard let string = raw as? String,
              !required || !string.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else {
            throw TaskDispatchError("\(field) 不能为空")
        }
        if string.count > maxLen {
            throw TaskDispatchError("\(field) 不能超过 \(maxLen) 个字符")
        }
        return required ? string.trimmingCharacters(in: .whitespacesAndNewlines) : string
    }

    private func attemptIdArg(_ args: [String: Any]) throws -> String? {
        guard let raw = rawArg(args, "attempt_id") else { return nil }
        guard let string = raw as? String, string.count <= 120 else {
            throw TaskDispatchError("attempt_id 格式无效")
        }
        return string.isEmpty ? nil : string
    }

    private func idemKey(_ task: TaskRecord, action: String, attemptId: String) -> String {
        "\(task.taskId)|\(task.epoch)|\(action)|\(attemptId)"
    }

    /// NSNumber 且非 Bool、且为整数值时返回 Int，否则 nil。
    private func intValue(_ raw: Any?) -> Int? {
        guard let number = raw as? NSNumber,
              CFGetTypeID(number) != CFBooleanGetTypeID() else { return nil }
        let double = number.doubleValue
        guard double == double.rounded(), abs(double) < 9.0e15 else { return nil }
        return Int(double)
    }

    /**
     * 基线 _check_fencing（顺序不可换，spec §4.2）：先验
     * expected_version（类型、再比对），后查 attempt_id 幂等回放。
     * 命中回放时返回首次结果，调用方原样返回、不再执行。
     */
    private func checkFencing(_ task: TaskRecord, args: [String: Any],
                              action: String) throws -> [String: Any]? {
        if let versionRaw = rawArg(args, "expected_version") {
            guard let expected = intValue(versionRaw) else {
                throw TaskDispatchError("expected_version 必须是整数")
            }
            if expected != task.version {
                throw TaskDispatchError(
                    "版本已变（当前 v\(task.version)，你给的是 v\(expected)）：请重新读取任务后再试",
                    status: 409, code: "stale_version")
            }
        }
        guard let attemptId = try attemptIdArg(args) else { return nil }
        guard let stored = store.idempotency[idemKey(task, action: action, attemptId: attemptId)],
              let data = stored.data(using: .utf8),
              let parsed = try? JSONSerialization.jsonObject(with: data),
              let object = parsed as? [String: Any] else { return nil }
        return object
    }

    private func recordAttempt(_ task: TaskRecord, action: String,
                               attemptId: String?, result: [String: Any]) {
        guard let attemptId else { return }
        let key = idemKey(task, action: action, attemptId: attemptId)
        if store.idempotency[key] == nil,
           let data = try? JSONSerialization.data(withJSONObject: result),
           let text = String(data: data, encoding: .utf8) {
            store.idempotency[key] = text
        }
    }

    /// 基线 _owned_open：不归我按不存在；状态不对 409；租约过期 409。
    private func ownedOpen(taskId: String?, agentId: String,
                           states: Set<String>) throws -> TaskRecord {
        let task = taskId.flatMap { store.tasks[$0] }
        guard let task, task.assigneeId == agentId || task.claimedById == agentId else {
            throw TaskDispatchError("任务不存在", status: 404, code: "task_not_found")
        }
        if !states.contains(task.state) {
            throw TaskDispatchError(
                "任务当前状态是\(label(task.state))，做不了这一步",
                status: 409, code: "task_state_\(task.state)")
        }
        if task.state == TaskDispatchSpec.claimed, let leaseUntil = task.leaseUntil,
           leaseUntil <= now() {
            throw TaskDispatchError("租约已过期，请重新领取", status: 409, code: "stale_attempt")
        }
        return task
    }

    // ---- 租约清扫（W4，基线 expire_leases） -------------------------------

    private func expireLeases() {
        let now = now()
        for task in store.orderedTasks {
            if task.state == TaskDispatchSpec.claimed, let leaseUntil = task.leaseUntil,
               leaseUntil < now {
                let who = task.claimedByName ?? task.assigneeName
                task.state = TaskDispatchSpec.queued
                task.leaseUntil = nil
                task.claimedById = nil
                task.claimedByName = nil
                touch(task)
                event(taskId: task.taskId, who: "平台", kind: "lease_expired",
                      text: "\(who) 超过 2 小时没有进展，任务回到等待接手")
            }
        }
        // 预留过期：基线是直接 UPDATE，不 +version、不写事件。
        for task in store.orderedTasks {
            if let reservedUntil = task.reservedUntil, reservedUntil < now {
                task.reservedForId = nil
                task.reservedUntil = nil
            }
        }
        // pack 排队超时跳过：取消子任务，父 work 直接开审查或转裁定。
        for task in store.orderedTasks {
            if task.kind != "pack" || task.state != TaskDispatchSpec.queued ||
                task.createdAt >= now - TaskDispatchSpec.packTimeoutSeconds {
                continue
            }
            task.state = TaskDispatchSpec.cancelled
            task.leaseUntil = nil
            task.claimedById = nil
            task.claimedByName = nil
            touch(task)
            event(taskId: task.taskId, who: "平台", kind: "pack_skipped",
                  text: "递代码活排队超过 30 分钟没人领，已跳过递代码")
            guard let parentId = task.parentId, let work = store.tasks[parentId] else { continue }
            if work.state != TaskDispatchSpec.reviewing { continue }
            let hasReview = store.orderedTasks.contains { $0.parentId == work.taskId && $0.kind == "review" }
            if hasReview { continue }
            if work.reviewerId != nil {
                _ = openReview(work: work, summary: work.summary, branch: work.branch,
                               commit: work.commitSha, prUrl: work.prUrl)
                event(taskId: work.taskId, who: "平台", kind: "pack_skipped",
                      text: "没有在线的递代码 Agent，直接交给审查")
            } else {
                work.state = TaskDispatchSpec.needsDecision
                work.decisionReason = "no_pack_agent"
                touch(work)
                event(taskId: work.taskId, who: "平台", kind: "pack_skipped",
                      text: "没有在线的递代码 Agent，转为需要你决定")
            }
        }
        // 基线清扫单独 commit：落盘并前移回滚点，其后失败不回滚清扫。
        store.persist()
        rollbackPoint = store.snapshotText()
    }

    /// 基线 _open_review：给 work 开审查子任务，证据快照在开审时落子任务。
    @discardableResult
    private func openReview(work: TaskRecord, summary: String?, branch: String?,
                            commit: String?, prUrl: String?) -> TaskRecord {
        let review = TaskRecord(
            taskId: newId(),
            projectId: work.projectId,
            kind: "review",
            parentId: work.taskId,
            assigneeId: work.reviewerId!,
            assigneeName: work.reviewerName ?? "",
            reviewerId: nil,
            reviewerName: nil,
            title: "审查：" + String(work.title.prefix(110)),
            goal: work.goal,
            state: TaskDispatchSpec.queued,
            round: work.round,
            maxRounds: work.maxRounds,
            leaseUntil: nil,
            summary: summary,
            branch: branch,
            commitSha: commit,
            prUrl: prUrl,
            verdict: nil,
            feedback: nil,
            decisionReason: nil,
            claimedById: nil,
            claimedByName: nil,
            reservedForId: nil,
            reservedUntil: nil,
            createdAt: now(),
            updatedAt: now()
        )
        store.putTask(review)
        return review
    }

    // ---- 视图（基线 agent_view / owner_view） ------------------------------

    private func taskEvents(_ taskId: String, limit: Int) -> [TaskEventRecord] {
        Array(store.eventsOf(taskId).suffix(limit))
    }

    private func eventObject(_ e: TaskEventRecord) -> [String: Any] {
        ["seq": e.seq, "at": e.at, "who": e.who ?? NSNull(),
         "kind": e.kind, "text": e.text]
    }

    private struct DeliveryInfo {
        var totalPages: Int
        var pagesReceived: Int
        var truncated: Bool
        var changedFiles: [String]
    }

    /// 基线 delivery_info：页数与改动文件清单（delivery_files 优先，
    /// 否则从已收页的 diff 头解析）。
    private func deliveryInfo(_ workId: String?) -> DeliveryInfo {
        guard let workId, let work = store.tasks[workId] else {
            return DeliveryInfo(totalPages: 0, pagesReceived: 0, truncated: false, changedFiles: [])
        }
        let pages = store.deliveryPages[work.taskId] ?? [:]
        var files = parseFilesJson(work.deliveryFiles)
        if files.isEmpty {
            let joined = pages.values.sorted { $0.page < $1.page }.map { $0.text }.joined()
            files = parseDiffFiles(joined)
        }
        return DeliveryInfo(totalPages: work.deliveryTotalPages, pagesReceived: pages.count,
                            truncated: work.deliveryTruncated, changedFiles: files)
    }

    private func parseFilesJson(_ text: String?) -> [String] {
        guard let text, !text.isEmpty, let data = text.data(using: .utf8),
              let parsed = try? JSONSerialization.jsonObject(with: data),
              let array = parsed as? [Any] else { return [] }
        return array.compactMap { $0 as? String }
    }

    /// 基线 _parse_diff_files：从 unified diff 的 +++ 头取改动文件。
    private func parseDiffFiles(_ text: String) -> [String] {
        var files: [String] = []
        for line in text.components(separatedBy: "\n") {
            let name: String
            if line.hasPrefix("+++ b/") {
                name = String(line.dropFirst(6)).trimmingCharacters(in: .whitespaces)
            } else if line.hasPrefix("+++ ") {
                name = String(line.dropFirst(4)).trimmingCharacters(in: .whitespaces)
            } else {
                continue
            }
            if !name.isEmpty && name != "/dev/null" && !files.contains(name) {
                files.append(name)
            }
        }
        return Array(files.prefix(500))
    }

    private func opt(_ value: Any?) -> Any { value ?? NSNull() }

    private func agentView(_ task: TaskRecord) -> [String: Any] {
        var view: [String: Any] = [:]
        view["task_id"] = task.taskId
        view["kind"] = task.kind
        view["project_id"] = task.projectId
        view["title"] = task.title
        view["goal"] = task.goal
        view["state"] = task.state
        view["round"] = task.round
        view["max_rounds"] = task.maxRounds
        view["source"] = "v5_platform"
        view["task_ref"] = "v5:" + task.taskId
        view["version"] = task.version
        view["attempt"] = task.attempt
        view["attempt_id"] = opt(task.attemptId)
        view["cancel_pending"] = false
        view["assignee_name"] = task.assigneeName
        view["claimed_by_name"] = opt(task.claimedByName)
        view["lease_until"] = opt(task.leaseUntil)
        view["created_at"] = task.createdAt
        view["updated_at"] = task.updatedAt
        view["summary"] = opt(task.summary)
        view["branch"] = opt(task.branch)
        view["commit"] = opt(task.commitSha)
        view["pr_url"] = opt(task.prUrl)
        view["feedback"] = opt(task.feedback)
        view["verdict"] = opt(task.verdict)
        view["messages"] = taskEvents(task.taskId, limit: 50)
            .filter { $0.kind == "input" }.map { eventObject($0) }
        var instructions: String?
        if task.kind == "work" && task.round > 1 {
            view["previous_delivery"] = [
                "summary": opt(task.summary), "branch": opt(task.branch),
                "commit": opt(task.commitSha), "pr_url": opt(task.prUrl),
            ] as [String: Any]
            view["review_feedback"] = opt(task.feedback)
            instructions = "这是返工轮：先逐条处理 review_feedback，再交付。"
        }
        if task.kind == "pack" {
            let info = deliveryInfo(task.parentId)
            view["work_id"] = opt(task.parentId)
            view["delivery"] = [
                "summary": opt(task.summary), "branch": opt(task.branch),
                "commit": opt(task.commitSha), "pr_url": opt(task.prUrl),
                "pages_received": info.pagesReceived, "total_pages": info.totalPages,
            ] as [String: Any]
            instructions = "递代码：用 attach_delivery_diff(task_id, page, total_pages, text) " +
                "把这次交付的 diff 按页上传，task_id 就是本任务的 task_id；" +
                "全部页到齐后平台自动开审查，你不用再 submit_result。"
        }
        if task.kind == "review" {
            if task.parentId == nil {
                instructions = "独立审查任务：没有递代码，不用 get_delivery_diff。" +
                    "按 goal 里写的分支/提交，直接去仓库看真实改动，按需运行相关测试。" +
                    "用 submit_result 交 verdict：verdict=approve 或 changes_requested，" +
                    "summary 写具体理由和要改的地方。不要写代码、不要推分支。"
            } else {
                let work = store.tasks[task.parentId!]
                let info = deliveryInfo(task.parentId)
                view["delivery"] = [
                    "implementer": opt(work?.claimedByName ?? work?.assigneeName),
                    "summary": opt(task.summary), "branch": opt(task.branch),
                    "commit": opt(task.commitSha), "pr_url": opt(task.prUrl),
                    "total_pages": info.totalPages, "pages_received": info.pagesReceived,
                    "changed_files": info.changedFiles,
                    "delivery_truncated": info.truncated,
                ] as [String: Any]
                if info.truncated {
                    view["delivery_note"] = "改动过大，只给了前 \(info.pagesReceived) 页"
                }
                instructions = "审查这次交付是否满足 goal：读分支上的真实改动，按需运行相关测试。" +
                    "有递代码时用 get_delivery_diff(task_id, page) 按页读改动，" +
                    "task_id 可填审查任务或原开发任务的 task_id。" +
                    "用 submit_result 交结论：verdict=approve 或 changes_requested，" +
                    "summary 写具体理由和要改的地方（会原样转给实现方）。"
            }
        }
        if task.kind != "review" {
            view["instructions"] = instructions ?? Self.workInstructions
        } else if let instructions {
            view["instructions"] = instructions
        }
        return view
    }

    private func ownerView(_ task: TaskRecord, withEvents: Bool = false) -> [String: Any] {
        var view: [String: Any] = [:]
        view["task_id"] = task.taskId
        view["kind"] = task.kind
        view["project_id"] = task.projectId
        view["title"] = task.title
        view["goal"] = task.goal
        view["state"] = task.state
        view["state_label"] = label(task.state)
        view["round"] = task.round
        view["max_rounds"] = task.maxRounds
        view["assignee_id"] = task.assigneeId
        view["assignee_name"] = task.assigneeName
        view["reviewer_id"] = opt(task.reviewerId)
        view["reviewer_name"] = opt(task.reviewerName)
        view["claimed_by_id"] = opt(task.claimedById)
        view["claimed_by_name"] = opt(task.claimedByName)
        view["lease_until"] = opt(task.leaseUntil)
        view["summary"] = opt(task.summary)
        view["branch"] = opt(task.branch)
        view["commit"] = opt(task.commitSha)
        view["pr_url"] = opt(task.prUrl)
        view["verdict"] = opt(task.verdict)
        view["feedback"] = opt(task.feedback)
        view["decision_reason"] = opt(task.decisionReason)
        view["channel_id"] = opt(task.channelId)
        view["version"] = task.version
        view["epoch"] = task.epoch
        view["created_at"] = task.createdAt
        view["updated_at"] = task.updatedAt
        if let deletedAt = task.deletedAt {
            view["deleted_at"] = deletedAt
            view["deleted_by"] = opt(task.deletedBy)
        }
        if withEvents {
            view["events"] = taskEvents(task.taskId, limit: 10).map { eventObject($0) }
        }
        return view
    }

    // ---- 干活侧（W5） ------------------------------------------------------

    private func listMyTasks(callerId: String) throws -> [String: Any] {
        expireLeases()
        let tasks = store.orderedTasks
            .filter {
                ($0.state == TaskDispatchSpec.queued || $0.state == TaskDispatchSpec.claimed) &&
                    ($0.assigneeId == callerId || $0.claimedById == callerId) &&
                    $0.deletedAt == nil
            }
            .sorted { $0.createdAt < $1.createdAt }
        return [
            "tasks": tasks.map { agentView($0) },
            "note": "排队的先领；领取后租约 2 小时，超过 2 小时没有动静会回到队伍。",
        ]
    }

    private func claimResult(_ task: TaskRecord) -> [String: Any] {
        ["task": agentView(task), "lease_seconds": Int(leaseSeconds)]
    }

    private func claimTask(args: [String: Any], callerId: String,
                           callerName: String) throws -> [String: Any] {
        expireLeases()
        guard let task = stringArg(args, "task_id").flatMap({ store.tasks[$0] }) else {
            throw TaskDispatchError("任务不存在", status: 404, code: "task_not_found")
        }
        if let replay = try checkFencing(task, args: args, action: "claim_task") {
            return replay
        }
        if task.state == TaskDispatchSpec.claimed {
            if task.claimedById == callerId || task.assigneeId == callerId {
                task.leaseUntil = now() + leaseSeconds
                touch(task)
                let result = claimResult(task)
                recordAttempt(task, action: "claim_task",
                              attemptId: try attemptIdArg(args), result: result)
                return result
            }
            throw TaskDispatchError("任务已被别人领走", status: 409, code: "task_state_claimed")
        }
        if task.state != TaskDispatchSpec.queued {
            throw TaskDispatchError(
                "任务当前状态是\(label(task.state))，领不了",
                status: 409, code: "task_state_\(task.state)")
        }
        if task.assigneeId != callerId {
            throw TaskDispatchError("任务不存在", status: 404, code: "task_not_found")
        }
        if let reservedFor = task.reservedForId, let reservedUntil = task.reservedUntil,
           reservedUntil > now(), reservedFor != callerId {
            throw TaskDispatchError(
                "这轮返工只留给上次的实现方，租约到期后才回到队伍",
                status: 409, code: "task_reserved")
        }
        task.state = TaskDispatchSpec.claimed
        task.leaseUntil = now() + leaseSeconds
        task.claimedById = callerId
        task.claimedByName = callerName
        touch(task)
        event(taskId: task.taskId, who: callerName, kind: "claimed",
              text: "\(callerName) 接手了" + (task.round > 1 ? "（第 \(task.round) 轮）" : ""))
        let result = claimResult(task)
        recordAttempt(task, action: "claim_task",
                      attemptId: try attemptIdArg(args), result: result)
        return result
    }

    private func reportProgress(args: [String: Any], callerId: String,
                                callerName: String) throws -> [String: Any] {
        let text = try textArg(args, "text", field: "text",
                               maxLen: TaskDispatchSpec.maxProgressChars)!
        let task = try ownedOpen(taskId: stringArg(args, "task_id"), agentId: callerId,
                                 states: [TaskDispatchSpec.claimed])
        if let replay = try checkFencing(task, args: args, action: "report_progress") {
            return replay
        }
        task.leaseUntil = now() + leaseSeconds
        touch(task)
        event(taskId: task.taskId, who: callerName, kind: "progress", text: text)
        let result: [String: Any] = ["ok": true, "lease_seconds": Int(leaseSeconds)]
        recordAttempt(task, action: "report_progress",
                      attemptId: try attemptIdArg(args), result: result)
        return result
    }

    private func heartbeat(args: [String: Any], callerId: String) throws -> [String: Any] {
        let task = try ownedOpen(taskId: stringArg(args, "task_id"), agentId: callerId,
                                 states: [TaskDispatchSpec.claimed])
        if let replay = try checkFencing(task, args: args, action: "heartbeat") {
            return replay
        }
        task.leaseUntil = now() + leaseSeconds
        touch(task)
        let result: [String: Any] = ["ok": true, "lease_seconds": Int(leaseSeconds)]
        recordAttempt(task, action: "heartbeat",
                      attemptId: try attemptIdArg(args), result: result)
        return result
    }

    private func releaseTask(args: [String: Any], callerId: String,
                             callerName: String) throws -> [String: Any] {
        let reason = try textArg(args, "reason", field: "reason",
                                 maxLen: TaskDispatchSpec.maxProgressChars, required: false)
        let task = try ownedOpen(taskId: stringArg(args, "task_id"), agentId: callerId,
                                 states: [TaskDispatchSpec.claimed])
        if let replay = try checkFencing(task, args: args, action: "release_task") {
            return replay
        }
        switch task.kind {
        case "work":
            task.state = TaskDispatchSpec.needsDecision
            task.decisionReason = "released"
            clearClaim(task)
            touch(task)
        case "pack":
            task.state = TaskDispatchSpec.queued
            clearClaim(task)
            touch(task)
        default:
            task.state = TaskDispatchSpec.cancelled
            clearClaim(task)
            touch(task)
            if let parentId = task.parentId, let parent = store.tasks[parentId] {
                parent.state = TaskDispatchSpec.needsDecision
                parent.decisionReason = "review_released"
                touch(parent)
            }
        }
        // 基线口径：事件记在父任务上（无父记自己）——pack 释放也记父 work。
        event(taskId: task.parentId ?? task.taskId, who: callerName, kind: "released",
              text: "\(callerName) 放弃了这个任务" + (reason != nil ? "：\(reason!)" : ""))
        let result: [String: Any] = ["ok": true]
        recordAttempt(task, action: "release_task",
                      attemptId: try attemptIdArg(args), result: result)
        return result
    }

    private func clearClaim(_ task: TaskRecord) {
        task.leaseUntil = nil
        task.claimedById = nil
        task.claimedByName = nil
    }

    private func reportStatus(args: [String: Any], callerId: String) throws -> [String: Any] {
        let text = (rawArg(args, "quota_text") as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let reset = (rawArg(args, "quota_reset_at") as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if let text, text.count > TaskDispatchSpec.maxQuotaTextChars {
            throw TaskDispatchError("quota_text 不能超过 500 个字符")
        }
        if let reset, reset.count > TaskDispatchSpec.maxQuotaResetChars {
            throw TaskDispatchError("quota_reset_at 不能超过 120 个字符")
        }
        if (text ?? "").isEmpty && (reset ?? "").isEmpty {
            throw TaskDispatchError("quota_text 和 quota_reset_at 至少填一个")
        }
        let existing = store.statusReports[callerId]
        let record = AgentStatusRecord(
            quotaText: text ?? existing?.quotaText,
            quotaResetAt: reset ?? existing?.quotaResetAt,
            reportedAt: now()
        )
        store.statusReports[callerId] = record
        return [
            "ok": true,
            "quota_text": opt(record.quotaText),
            "quota_reset_at": opt(record.quotaResetAt),
            "reported_at": record.reportedAt,
        ]
    }

    // ---- 交付链（W6，基线 submit_work / submit_verdict /
    //      attach_delivery / get_delivery_page） ----------------------------

    // 基线 _REF_RE / _SHA_RE / _URL_RE（证据格式门）。
    private static let refRegex = try! NSRegularExpression(pattern: "^[A-Za-z0-9._/-]{1,200}$")
    private static let shaRegex = try! NSRegularExpression(pattern: "^[0-9a-f]{7,64}$")
    private static let urlRegex = try! NSRegularExpression(pattern: "^https?://\\S{1,2000}$")

    private func recordAttemptRaw(taskId: String?, epoch: Int, action: String,
                                  attemptId: String?, result: [String: Any]) {
        guard let attemptId, let taskId else { return }
        let key = "\(taskId)|\(epoch)|\(action)|\(attemptId)"
        if store.idempotency[key] == nil,
           let data = try? JSONSerialization.data(withJSONObject: result),
           let text = String(data: data, encoding: .utf8) {
            store.idempotency[key] = text
        }
    }

    /// 基线 _opt_ref / _opt_url：给定时须是字符串且格式合规。
    private func optRef(_ args: [String: Any], _ key: String, field: String,
                        regex: NSRegularExpression) throws -> String? {
        guard let raw = rawArg(args, key) else { return nil }
        guard let string = raw as? String,
              regex.firstMatch(in: string, range: NSRange(string.startIndex..., in: string)) != nil
        else {
            throw TaskDispatchError("\(field) 格式无效")
        }
        return string
    }

    private func submitResult(args: [String: Any], callerId: String,
                              callerName: String) throws -> [String: Any] {
        if let verdictRaw = rawArg(args, "verdict") {
            guard let verdict = verdictRaw as? String,
                  verdict == "approve" || verdict == "changes_requested" else {
                throw TaskDispatchError("verdict 只能是 approve 或 changes_requested")
            }
            return try submitVerdict(args: args, callerId: callerId,
                                     callerName: callerName, verdict: verdict)
        }
        return try submitWork(args: args, callerId: callerId, callerName: callerName)
    }

    private func submitWork(args: [String: Any], callerId: String,
                            callerName: String) throws -> [String: Any] {
        let summary = try textArg(args, "summary", field: "summary",
                                  maxLen: TaskDispatchSpec.maxSummaryChars)!
        let branch = try optRef(args, "branch", field: "branch", regex: Self.refRegex)
        let commit = try optRef(args, "commit", field: "commit", regex: Self.shaRegex)
        let prUrl = try optRef(args, "pr_url", field: "pr_url", regex: Self.urlRegex)
        if branch == nil && commit == nil && prUrl == nil {
            throw TaskDispatchError(
                "交付需要证据：请带上 branch、commit 或 pr_url 其中至少一个",
                status: 422, code: "evidence_required")
        }
        let task = try ownedOpen(taskId: stringArg(args, "task_id"), agentId: callerId,
                                 states: [TaskDispatchSpec.claimed])
        if let replay = try checkFencing(task, args: args, action: "submit_result") {
            return replay
        }
        if task.kind == "pack" {
            throw TaskDispatchError(
                "递代码任务请用 attach_delivery_diff 递 diff，不用 submit_result",
                status: 409, code: "use_attach_for_pack_tasks")
        }
        if task.kind != "work" {
            throw TaskDispatchError(
                "审查任务请用 verdict 交结论",
                status: 409, code: "use_verdict_for_review_tasks")
        }
        let delivered = "交付" +
            (branch != nil ? "：分支 \(branch!)" : "") +
            (commit != nil ? " 提交 \(String(commit!.prefix(12)))" : "")
        event(taskId: task.taskId, who: callerName, kind: "submitted",
              text: delivered + "\n" + summary)
        // 提交即清本任务旧 delivery_pages（基线 DELETE 同步清计数）。
        store.deliveryPages.removeValue(forKey: task.taskId)
        task.summary = summary
        task.branch = branch
        task.commitSha = commit
        task.prUrl = prUrl
        task.leaseUntil = nil
        task.deliveryTotalPages = 0
        task.deliveryPages = 0
        task.deliveryTruncated = false
        task.deliveryFiles = nil
        let packAgent = roster?.firstAgent(withRole: "pack")
        let note: String
        if let packAgent, branch != nil {
            task.state = TaskDispatchSpec.reviewing
            touch(task)
            _ = openPack(work: task, packAgent: packAgent)
            event(taskId: task.taskId, who: "平台", kind: "pack_opened",
                  text: "已排递代码活，改动递到后自动开审查")
            note = "已排递代码，改动递到后自动交给审查方。"
        } else if task.reviewerId != nil {
            task.state = TaskDispatchSpec.reviewing
            touch(task)
            _ = openReview(work: task, summary: summary, branch: branch,
                           commit: commit, prUrl: prUrl)
            event(taskId: task.taskId, who: "平台", kind: "review_opened",
                  text: "已交给 \(task.reviewerName ?? "") 审查")
            note = "已交给审查方，结论出来后会自动通知；要返工时这个任务会重新出现在 list_my_tasks。"
        } else {
            task.state = TaskDispatchSpec.needsDecision
            task.decisionReason = "no_reviewer"
            touch(task)
            note = "已交付，等项目负责人查看。"
        }
        let result: [String: Any] = ["ok": true, "state": task.state, "note": note]
        recordAttempt(task, action: "submit_result",
                      attemptId: try attemptIdArg(args), result: result)
        return result
    }

    /// 基线 pack 子任务开单（submit 带 branch 且有 pack 角色 Agent 时）。
    @discardableResult
    private func openPack(work: TaskRecord,
                          packAgent: (id: String, name: String)) -> TaskRecord {
        let pack = TaskRecord(
            taskId: newId(),
            projectId: work.projectId,
            kind: "pack",
            parentId: work.taskId,
            assigneeId: packAgent.id,
            assigneeName: packAgent.name,
            reviewerId: nil,
            reviewerName: nil,
            title: "递代码：" + String(work.title.prefix(110)),
            goal: work.goal,
            state: TaskDispatchSpec.queued,
            round: work.round,
            maxRounds: work.maxRounds,
            leaseUntil: nil,
            summary: work.summary,
            branch: work.branch,
            commitSha: work.commitSha,
            prUrl: work.prUrl,
            verdict: nil,
            feedback: nil,
            decisionReason: nil,
            claimedById: nil,
            claimedByName: nil,
            reservedForId: nil,
            reservedUntil: nil,
            createdAt: now(),
            updatedAt: now()
        )
        store.putTask(pack)
        return pack
    }

    private func submitVerdict(args: [String: Any], callerId: String,
                               callerName: String, verdict: String) throws -> [String: Any] {
        let summary = try textArg(args, "summary", field: "summary",
                                  maxLen: TaskDispatchSpec.maxSummaryChars)!
        let task = try ownedOpen(taskId: stringArg(args, "task_id"), agentId: callerId,
                                 states: [TaskDispatchSpec.claimed])
        if let replay = try checkFencing(task, args: args, action: "submit_result") {
            return replay
        }
        if task.kind != "review" {
            throw TaskDispatchError(
                "开发任务请用 submit_result 交 summary + branch/commit，不要用 verdict",
                status: 409, code: "use_submit_for_work_tasks")
        }
        task.state = TaskDispatchSpec.done
        task.verdict = verdict
        task.feedback = summary
        clearClaim(task)
        touch(task)
        event(taskId: task.taskId, who: callerName, kind: "verdict",
              text: "审查结论：\(verdict)\n\(summary)")
        let work = task.parentId.flatMap { store.tasks[$0] }
        var note = "结论已记录，团队流程会据此继续。"
        if let work {
            if work.state != TaskDispatchSpec.reviewing {
                event(taskId: work.taskId, who: callerName, kind: "verdict_ignored",
                      text: "任务已不在审查中，结论未生效")
            } else if verdict == "approve" {
                work.state = TaskDispatchSpec.approved
                work.verdict = verdict
                work.feedback = summary
                work.decisionReason = nil
                work.claimedById = nil
                work.claimedByName = nil
                work.reservedForId = nil
                work.reservedUntil = nil
                touch(work)
                event(taskId: work.taskId, who: callerName, kind: "approved",
                      text: "审查通过\n\(summary)")
            } else if work.round < work.maxRounds {
                let reserved = work.claimedById
                let implementer = work.claimedByName ?? work.assigneeName
                work.state = TaskDispatchSpec.queued
                work.verdict = verdict
                work.feedback = summary
                work.round += 1
                work.claimedById = nil
                work.claimedByName = nil
                work.reservedForId = reserved
                work.reservedUntil = reserved != nil ? now() + leaseSeconds : nil
                touch(work)
                event(taskId: work.taskId, who: callerName, kind: "changes_requested",
                      text: "审查要求修改，自动打回给 \(implementer)（第 \(work.round) 轮）\n\(summary)")
                note = "结论已转给实现方，任务已打回返工。"
            } else {
                work.state = TaskDispatchSpec.needsDecision
                work.verdict = verdict
                work.feedback = summary
                work.decisionReason = "rounds_exceeded"
                work.claimedById = nil
                work.claimedByName = nil
                work.reservedForId = nil
                work.reservedUntil = nil
                touch(work)
                event(taskId: work.taskId, who: callerName, kind: "rounds_exceeded",
                      text: "返工轮数已用完，转为需要项目负责人决定")
                note = "返工轮数已用完，转为需要项目负责人决定。"
            }
        }
        let result: [String: Any] = ["ok": true, "state": TaskDispatchSpec.done, "note": note]
        recordAttempt(task, action: "submit_result",
                      attemptId: try attemptIdArg(args), result: result)
        return result
    }

    private func attachDelivery(args: [String: Any], callerId: String,
                                callerName: String) throws -> [String: Any] {
        let page = intValue(rawArg(args, "page"))
        let totalPages = intValue(rawArg(args, "total_pages"))
        guard let page, let totalPages,
              1 <= page, page <= totalPages,
              totalPages <= TaskDispatchSpec.maxDeliveryPages else {
            throw TaskDispatchError("page/total_pages 无效（1 <= page <= total_pages <= 200）")
        }
        guard let text = rawArg(args, "text") as? String,
              text.count <= TaskDispatchSpec.maxPageChars else {
            throw TaskDispatchError("一页 diff 不能超过 65536 个字符")
        }
        let truncatedRaw = rawArg(args, "truncated")
        let truncated: Bool
        if let bool = truncatedRaw as? Bool {
            truncated = bool
        } else if let number = truncatedRaw as? NSNumber {
            truncated = number.doubleValue != 0
        } else {
            truncated = truncatedRaw != nil
        }
        let argTaskId = stringArg(args, "task_id")
        guard var row = argTaskId.flatMap({ store.tasks[$0] }) else {
            throw TaskDispatchError("任务不存在", status: 404, code: "task_not_found")
        }
        if row.kind == "work" {
            // 填 work id 时自动定位其最新 pack 子任务（基线口径）。
            guard let pack = store.orderedTasks
                .filter({ $0.parentId == row.taskId && $0.kind == "pack" })
                .sorted(by: { $0.createdAt < $1.createdAt }).last else {
                throw TaskDispatchError("任务不存在", status: 404, code: "task_not_found")
            }
            row = pack
        }
        if row.kind != "pack" {
            throw TaskDispatchError(
                "只有递代码任务能递 diff",
                status: 409, code: "use_attach_for_pack_tasks")
        }
        let roles = roster?.agentRoles(callerId)
        if roles == nil || !roles!.contains("pack") {
            throw TaskDispatchError(
                "只有带递代码角色的 Agent 能递 diff",
                status: 409, code: "pack_role_required")
        }
        if row.state != TaskDispatchSpec.claimed ||
            (row.assigneeId != callerId && row.claimedById != callerId) {
            throw TaskDispatchError("任务不存在", status: 404, code: "task_not_found")
        }
        if let replay = try checkFencing(row, args: args, action: "attach_delivery_diff") {
            return replay
        }
        let workId = row.parentId!
        guard let work = store.tasks[workId] else {
            throw TaskDispatchError("任务不存在", status: 404, code: "task_not_found")
        }
        var pages = store.deliveryPages[workId] ?? [:]
        for old in pages.values {
            if old.totalPages != totalPages {
                throw TaskDispatchError("total_pages 与前面几页不一致")
            }
        }
        // 基线按字符数计总量（len(text)），不是字节数。
        let storedChars = pages.values.filter { $0.page != page }
            .reduce(0) { $0 + $1.text.count } + text.count
        if storedChars > TaskDispatchSpec.maxDeliveryBytes {
            throw TaskDispatchError("整份 diff 不能超过 2 MB")
        }
        pages[page] = DeliveryPageRecord(page: page, totalPages: totalPages, text: text)
        store.deliveryPages[workId] = pages
        let received = pages.count
        let files = parseDiffFiles(
            pages.values.sorted { $0.page < $1.page }.map { $0.text }.joined())
        work.deliveryTotalPages = totalPages
        work.deliveryPages = received
        if truncated { work.deliveryTruncated = true }
        if let data = try? JSONSerialization.data(withJSONObject: files),
           let filesText = String(data: data, encoding: .utf8) {
            work.deliveryFiles = filesText
        }
        touch(work)
        if received < totalPages {
            event(taskId: workId, who: callerName, kind: "pack_progress",
                  text: "递代码 \(received)/\(totalPages) 页")
            let result: [String: Any] = [
                "ok": true, "work_id": workId, "pack_task_id": row.taskId,
                "pages_received": received, "total_pages": totalPages, "complete": false,
            ]
            recordAttemptRaw(taskId: argTaskId, epoch: row.epoch,
                             action: "attach_delivery_diff",
                             attemptId: try attemptIdArg(args), result: result)
            return result
        }
        row.state = TaskDispatchSpec.done
        clearClaim(row)
        touch(row)
        if work.reviewerId != nil {
            _ = openReview(work: work, summary: work.summary, branch: work.branch,
                           commit: work.commitSha, prUrl: work.prUrl)
            event(taskId: workId, who: "平台", kind: "review_opened",
                  text: "递代码完成（\(totalPages) 页），已交给 \(work.reviewerName ?? "") 审查")
        } else {
            work.state = TaskDispatchSpec.needsDecision
            work.decisionReason = "no_reviewer"
            touch(work)
            event(taskId: workId, who: "平台", kind: "pack_done",
                  text: "递代码完成（\(totalPages) 页），等你查看")
        }
        let result: [String: Any] = [
            "ok": true, "work_id": workId, "pack_task_id": row.taskId,
            "pages_received": received, "total_pages": totalPages, "complete": true,
        ]
        recordAttemptRaw(taskId: argTaskId, epoch: row.epoch,
                         action: "attach_delivery_diff",
                         attemptId: try attemptIdArg(args), result: result)
        return result
    }

    private func getDeliveryDiff(args: [String: Any], callerId: String) throws -> [String: Any] {
        guard let page = intValue(rawArg(args, "page")), page >= 1 else {
            throw TaskDispatchError("page 无效")
        }
        guard let row = stringArg(args, "task_id").flatMap({ store.tasks[$0] }) else {
            throw TaskDispatchError("任务不存在", status: 404, code: "task_not_found")
        }
        if row.kind == "review" && row.parentId == nil {
            throw TaskDispatchError(
                "独立审查任务没有递代码",
                status: 409, code: "no_delivery_diff")
        }
        let workId = row.kind == "review" ? row.parentId! : row.taskId
        guard let work = store.tasks[workId], work.kind == "work" else {
            throw TaskDispatchError("任务不存在", status: 404, code: "task_not_found")
        }
        let review = store.orderedTasks
            .filter { $0.parentId == workId && $0.kind == "review" }
            .sorted { $0.createdAt < $1.createdAt }.last
        let allowed = review != nil &&
            (review!.assigneeId == callerId || review!.claimedById == callerId)
        if !allowed {
            throw TaskDispatchError("任务不存在", status: 404, code: "task_not_found")
        }
        let pages = store.deliveryPages[workId] ?? [:]
        guard let found = pages[page] else {
            throw TaskDispatchError("这一页 diff 还没到", status: 404, code: "delivery_page_missing")
        }
        return [
            "ok": true,
            "work_id": workId,
            "page": page,
            "total_pages": found.totalPages,
            "pages_received": pages.count,
            "truncated": work.deliveryTruncated,
            "changed_files": parseFilesJson(work.deliveryFiles),
            "text": found.text,
        ]
    }

    // ---- 派单侧（W5 读 + start/decide；update/withdraw/reopen 在 W9） ------

    private func agentName(_ agentId: String) -> String? {
        directory?(agentId)
    }

    private func startTask(args: [String: Any], callerId: String) throws -> [String: Any] {
        guard let agentRaw = rawArg(args, "agent") as? String, !agentRaw.isEmpty else {
            throw TaskDispatchError("agent 不能为空")
        }
        guard let assigneeName = agentName(agentRaw) else {
            throw TaskDispatchError("agent \(agentRaw) 不存在")
        }
        var reviewerId: String?
        var reviewerName: String?
        if let reviewerRaw = rawArg(args, "reviewer") {
            guard let rid = reviewerRaw as? String, let rname = agentName(rid) else {
                throw TaskDispatchError("reviewer \(reviewerRaw) 不存在")
            }
            reviewerId = rid
            reviewerName = rname
        }
        let goal = try textArg(args, "goal", field: "goal",
                               maxLen: TaskDispatchSpec.maxGoalChars)!
        let projectId = try textArg(args, "project_id", field: "project_id", maxLen: 120)!
        let titleRaw = rawArg(args, "title") as? String
        let title: String
        if let titleRaw, !titleRaw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            title = String(titleRaw.trimmingCharacters(in: .whitespacesAndNewlines)
                .prefix(TaskDispatchSpec.maxTitleChars))
        } else {
            title = String((goal.components(separatedBy: "\n").first ?? "")
                .prefix(TaskDispatchSpec.maxTitleChars))
        }
        let channelId = stringArg(args, "channel_id")
        let idemKey = stringArg(args, "idempotency_key")
        // spec §2.10：幂等作用域收紧为 (channel_id, key)，同 key 内容不同 409。
        let scopedKey = idemKey.map { "start_task|\(channelId ?? "")|\($0)" }
        if let scopedKey, let storedText = store.idempotency[scopedKey],
           let data = storedText.data(using: .utf8),
           let parsed = try? JSONSerialization.jsonObject(with: data),
           let stored = parsed as? [String: Any] {
            let storedReviewer = stored["reviewer"] as? String
            let same = (stored["agent"] as? String) == agentRaw &&
                (stored["goal"] as? String) == goal &&
                (stored["project_id"] as? String) == projectId &&
                storedReviewer == reviewerId &&
                (stored["title"] as? String) == title
            if !same {
                throw TaskDispatchError(
                    "idempotency_key 已用于不同内容的任务",
                    status: 409, code: "idempotency_conflict")
            }
            if let taskId = stored["task_id"] as? String, let existing = store.tasks[taskId] {
                return ["task": ownerView(existing), "state": TaskDispatchSpec.queued]
            }
        }
        let task = TaskRecord(
            taskId: newId(),
            projectId: projectId,
            kind: "work",
            parentId: nil,
            assigneeId: agentRaw,
            assigneeName: assigneeName,
            reviewerId: reviewerId,
            reviewerName: reviewerName,
            title: title,
            goal: goal,
            state: TaskDispatchSpec.queued,
            round: 1,
            maxRounds: TaskDispatchSpec.defaultMaxRounds,
            leaseUntil: nil,
            summary: nil,
            branch: nil,
            commitSha: nil,
            prUrl: nil,
            verdict: nil,
            feedback: nil,
            decisionReason: nil,
            claimedById: nil,
            claimedByName: nil,
            reservedForId: nil,
            reservedUntil: nil,
            createdAt: now(),
            updatedAt: now()
        )
        task.channelId = channelId
        task.createdBy = callerId
        store.putTask(task)
        event(taskId: task.taskId, who: callerId, kind: "created",
              text: "任务已派给 \(assigneeName)")
        if let scopedKey {
            let record: [String: Any] = [
                "task_id": task.taskId, "agent": agentRaw, "goal": goal,
                "project_id": projectId, "reviewer": opt(reviewerId), "title": title,
            ]
            if let data = try? JSONSerialization.data(withJSONObject: record),
               let text = String(data: data, encoding: .utf8) {
                store.idempotency[scopedKey] = text
            }
        }
        return ["task": ownerView(task), "state": TaskDispatchSpec.queued]
    }

    private func getTask(args: [String: Any]) throws -> [String: Any] {
        guard let task = stringArg(args, "task_id").flatMap({ store.tasks[$0] }) else {
            throw TaskDispatchError("任务不存在", status: 404, code: "task_not_found")
        }
        let includeEvents = (rawArg(args, "include_events") as? Bool) == true
        return ["task": ownerView(task, withEvents: includeEvents)]
    }

    private func listTasks(args: [String: Any]) throws -> [String: Any] {
        expireLeases()
        let projectId = stringArg(args, "project_id")
        let channelId = stringArg(args, "channel_id")
        let limit = min(200, max(1, intValue(rawArg(args, "limit")) ?? 50))
        let offset = max(0, intValue(rawArg(args, "offset")) ?? 0)
        let tasks = store.orderedTasks
            .filter { $0.kind == "work" && $0.deletedAt == nil }
            .filter { projectId == nil || projectId!.isEmpty || $0.projectId == projectId }
            .filter { channelId == nil || $0.channelId == channelId }
            .sorted { $0.updatedAt > $1.updatedAt }
            .dropFirst(offset)
            .prefix(limit)
        return ["tasks": tasks.map { ownerView($0) }]
    }

    private func decideTask(args: [String: Any]) throws -> [String: Any] {
        let action = stringArg(args, "action")
        guard action == "done" || action == "cancel" else {
            throw TaskDispatchError("action 只能是 done（确认完成）或 cancel（取消）")
        }
        let reason = try textArg(args, "reason", field: "reason",
                                 maxLen: TaskDispatchSpec.maxProgressChars)!
        guard let task = stringArg(args, "task_id").flatMap({ store.tasks[$0] }) else {
            throw TaskDispatchError("任务不存在", status: 404, code: "task_not_found")
        }
        if task.state != TaskDispatchSpec.needsDecision {
            throw TaskDispatchError(
                "任务当前状态是\(label(task.state))，不需要决定",
                status: 409, code: "task_state_\(task.state)")
        }
        task.state = action == "done" ? TaskDispatchSpec.done : TaskDispatchSpec.cancelled
        task.decisionReason = nil
        clearClaim(task)
        touch(task)
        let verb = action == "done" ? "确认完成" : "取消"
        event(taskId: task.taskId, who: "项目负责人", kind: "decided",
              text: "已\(verb)：\(reason)")
        return ["ok": true, "task": ownerView(task), "state": task.state]
    }

    // ---- 派单侧新工具（W9，spec §2.14–2.16） ------------------------------

    private func ownerTask(args: [String: Any]) throws -> TaskRecord {
        guard let task = stringArg(args, "task_id").flatMap({ store.tasks[$0] }) else {
            throw TaskDispatchError("任务不存在", status: 404, code: "task_not_found")
        }
        return task
    }

    private func stateGuard(_ task: TaskRecord, allowed: Set<String>) throws {
        if !allowed.contains(task.state) {
            throw TaskDispatchError(
                "任务当前状态是\(label(task.state))，做不了这一步",
                status: 409, code: "task_state_\(task.state)")
        }
    }

    private func updateTask(args: [String: Any], callerName: String) throws -> [String: Any] {
        let task = try ownerTask(args: args)
        if task.kind != "work" {
            throw TaskDispatchError("只有工作任务可以更新")
        }
        guard let expectedRaw = rawArg(args, "expected_version") else {
            throw TaskDispatchError("expected_version 必填")
        }
        guard let expected = intValue(expectedRaw) else {
            throw TaskDispatchError("expected_version 必须是整数")
        }
        if expected != task.version {
            throw TaskDispatchError(
                "版本已变（当前 v\(task.version)，你给的是 v\(expected)）：请重新读取任务后再试",
                status: 409, code: "stale_version")
        }
        try stateGuard(task, allowed: [TaskDispatchSpec.queued, TaskDispatchSpec.claimed])
        if let channelRaw = rawArg(args, "channel_id"), (channelRaw as? String) != task.channelId {
            throw TaskDispatchError("channel_id 不可修改")
        }
        // 先全量校验再落地（任一字段不合法整单不动）。
        var newTitle: String? = nil
        if rawArg(args, "title") != nil {
            guard let rawTitle = rawArg(args, "title") as? String,
                  !rawTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                throw TaskDispatchError("title 不能为空")
            }
            newTitle = String(rawTitle.trimmingCharacters(in: .whitespacesAndNewlines).prefix(100))
        }
        var newGoal: String? = nil
        if rawArg(args, "goal") != nil {
            newGoal = try textArg(args, "goal", field: "goal",
                                  maxLen: TaskDispatchSpec.maxGoalChars)
        }
        var reviewerTouched = false
        var newReviewerId: String? = nil
        var newReviewerName: String? = nil
        if args["reviewer"] != nil {
            reviewerTouched = true
            if let reviewerRaw = rawArg(args, "reviewer") {
                guard let reviewerId = reviewerRaw as? String else {
                    throw TaskDispatchError("reviewer 格式无效")
                }
                guard let name = directory?(reviewerId) else {
                    throw TaskDispatchError("reviewer \(reviewerId) 不存在")
                }
                newReviewerId = reviewerId
                newReviewerName = name
            }
        }
        var reassignTo: (id: String, name: String)? = nil
        if let agentRaw = rawArg(args, "agent") {
            guard let agentId = agentRaw as? String else {
                throw TaskDispatchError("agent 格式无效")
            }
            guard let name = directory?(agentId) else {
                throw TaskDispatchError("agent \(agentId) 不存在")
            }
            if agentId != task.assigneeId {
                reassignTo = (id: agentId, name: name)
            }
        }
        if let newTitle { task.title = newTitle }
        if let newGoal { task.goal = newGoal }
        if reviewerTouched {
            task.reviewerId = newReviewerId
            task.reviewerName = newReviewerName
        }
        if let reassignTo {
            task.assigneeId = reassignTo.id
            task.assigneeName = reassignTo.name
            task.state = TaskDispatchSpec.queued
            clearClaim(task)
            task.reservedForId = reassignTo.id
            task.reservedUntil = now() + leaseSeconds
            touch(task)
            event(taskId: task.taskId, who: callerName, kind: "reassigned",
                  text: "改派给 \(reassignTo.name)，原执行人已释放")
        } else {
            touch(task)
            event(taskId: task.taskId, who: callerName, kind: "updated",
                  text: "任务内容已更新")
        }
        return ["ok": true, "task": ownerView(task)]
    }

    private func withdrawTask(args: [String: Any], callerName: String) throws -> [String: Any] {
        let task = try ownerTask(args: args)
        if task.kind != "work" {
            throw TaskDispatchError("只有工作任务可以撤回")
        }
        if task.state == TaskDispatchSpec.cancelled {
            // 幂等重放：已取消直接成功，不 +version、不写事件（spec §1）。
            return ["ok": true, "state": TaskDispatchSpec.cancelled, "task": ownerView(task)]
        }
        try stateGuard(task, allowed: [
            TaskDispatchSpec.queued, TaskDispatchSpec.claimed, TaskDispatchSpec.reviewing,
        ])
        let reason = try textArg(args, "reason", field: "reason",
                                 maxLen: TaskDispatchSpec.maxProgressChars,
                                 required: false)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let text = "任务已撤回" + ((reason?.isEmpty == false) ? "：\(reason!)" : "")
        // 打开着的子任务同步取消（各 +1、各记 withdrawn）。
        for child in store.orderedTasks where child.parentId == task.taskId {
            if child.state == TaskDispatchSpec.done ||
                child.state == TaskDispatchSpec.cancelled ||
                child.state == TaskDispatchSpec.approved {
                continue
            }
            child.state = TaskDispatchSpec.cancelled
            clearClaim(child)
            child.reservedForId = nil
            child.reservedUntil = nil
            touch(child)
            event(taskId: child.taskId, who: callerName, kind: "withdrawn", text: text)
        }
        task.state = TaskDispatchSpec.cancelled
        clearClaim(task)
        task.reservedForId = nil
        task.reservedUntil = nil
        touch(task)
        event(taskId: task.taskId, who: callerName, kind: "withdrawn", text: text)
        return ["ok": true, "state": TaskDispatchSpec.cancelled, "task": ownerView(task)]
    }

    private func reopenTask(args: [String: Any], callerName: String) throws -> [String: Any] {
        let task = try ownerTask(args: args)
        if task.kind != "work" {
            throw TaskDispatchError("只有工作任务可以重开")
        }
        try stateGuard(task, allowed: [
            TaskDispatchSpec.approved, TaskDispatchSpec.done, TaskDispatchSpec.cancelled,
        ])
        var snapshot = "上一轮：状态 \(label(task.state))、第 \(task.round) 轮"
        if let verdict = task.verdict { snapshot += "、结论 \(verdict)" }
        if let summary = task.summary { snapshot += "、交付摘要：\(String(summary.prefix(100)))" }
        task.state = TaskDispatchSpec.queued
        task.round = 1
        task.epoch += 1
        task.verdict = nil
        task.feedback = nil
        task.summary = nil
        task.branch = nil
        task.commitSha = nil
        task.prUrl = nil
        task.decisionReason = nil
        clearClaim(task)
        task.reservedForId = nil
        task.reservedUntil = nil
        task.deliveryTotalPages = 0
        task.deliveryPages = 0
        task.deliveryTruncated = false
        task.deliveryFiles = nil
        store.deliveryPages.removeValue(forKey: task.taskId)
        touch(task)
        event(taskId: task.taskId, who: callerName, kind: "reopened",
              text: "任务已重开（\(snapshot)）")
        return ["ok": true, "task": ownerView(task)]
    }
}
