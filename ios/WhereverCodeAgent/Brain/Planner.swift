import Foundation

/// Planner: task planning -> structured actions, ported from the
/// Android baseline `brain/Planner.kt` on main @4496658e.
///
/// The Planner PRODUCES plans; it never executes. Execution belongs
/// to the BrainEngine + ToolRegistry validation flow.
///
/// Plan generation itself comes from the brain (the brain outputs a
/// JSON plan); this file validates and structures that output:
/// - every step references a registered tool,
/// - every step carries an idempotency key in `task_id:seq` form, so
///   the same action is never executed twice,
/// - risks are carried as plain strings for the user to see.
public struct BrainAction: Equatable {
    public var actionId: String
    public var tool: String
    public var args: [String: JSONValue] = [:]
    public var requiresApproval: Bool = false
    public var idempotencyKey: String

    public init(
        actionId: String,
        tool: String,
        args: [String: JSONValue] = [:],
        requiresApproval: Bool = false,
        idempotencyKey: String
    ) {
        self.actionId = actionId
        self.tool = tool
        self.args = args
        self.requiresApproval = requiresApproval
        self.idempotencyKey = idempotencyKey
    }
}

public struct Plan: Equatable {
    public var goal: String
    public var steps: [BrainAction]
    public var risks: [String] = []

    public init(goal: String, steps: [BrainAction], risks: [String] = []) {
        self.goal = goal
        self.steps = steps
        self.risks = risks
    }
}

public protocol Planner {
    /// Parse + validate a brain-produced JSON plan. Returns nil when
    /// the JSON is not a plan at all; validation errors are reported
    /// separately via [PlanValidator].
    func parsePlanJson(_ json: String, taskId: String) -> Plan?
}

public enum PlanValidator {
    private static let idempotencyKeyRegex = try! NSRegularExpression(
        pattern: "^[^:]+:[0-9]+$", options: [])

    public static func idempotencyKey(taskId: String, seq: Int) -> String {
        "\(taskId):\(seq)"
    }

    public static func isValidIdempotencyKey(_ key: String) -> Bool {
        idempotencyKeyRegex.firstMatch(
            in: key, range: NSRange(key.startIndex..., in: key)) != nil
    }

    /// Returns a list of validation errors; empty means the plan is
    /// valid. Checks: goal non-blank, at least one step, every step's
    /// tool is registered, every idempotency key is `task_id:seq` for
    /// this task, no duplicate idempotency keys.
    public static func validate(
        _ plan: Plan, registeredTools: Set<String>, taskId: String
    ) -> [String] {
        var errors: [String] = []
        if plan.goal.trimmingCharacters(in: .whitespaces).isEmpty { errors.append("目标为空") }
        if plan.steps.isEmpty { errors.append("计划没有步骤") }
        var seenKeys: Set<String> = []
        for (index, step) in plan.steps.enumerated() {
            if !registeredTools.contains(step.tool) {
                errors.append("步骤 \(index + 1)：未知工具 \(step.tool)")
            }
            if !isValidIdempotencyKey(step.idempotencyKey) {
                errors.append("步骤 \(index + 1)：幂等键格式错误 \(step.idempotencyKey)（应为 task_id:seq）")
            } else if !step.idempotencyKey.hasPrefix("\(taskId):") {
                errors.append("步骤 \(index + 1)：幂等键不属于本任务 \(step.idempotencyKey)")
            }
            if !seenKeys.insert(step.idempotencyKey).inserted {
                errors.append("步骤 \(index + 1)：幂等键重复 \(step.idempotencyKey)")
            }
        }
        return errors
    }
}

/// Parses the brain's JSON plan shape:
/// {"goal","steps":[{"tool","args","requires_approval"}],"risks":[...]}
public struct JsonPlanner: Planner {
    public init() {}

    public func parsePlanJson(_ json: String, taskId: String) -> Plan? {
        guard let data = json.data(using: .utf8),
              let raw = try? JSONSerialization.jsonObject(with: data),
              let obj = raw as? [String: Any]
        else { return nil }
        let goal = (obj["goal"] as? String) ?? ""
        guard let stepsArray = obj["steps"] as? [Any] else { return nil }
        var steps: [BrainAction] = []
        for (index, rawStep) in stepsArray.enumerated() {
            guard let step = rawStep as? [String: Any] else { return nil }
            let tool = (step["tool"] as? String) ?? ""
            if tool.isEmpty { return nil }
            let args: [String: JSONValue]
            if let argsObj = step["args"] as? [String: Any] {
                args = argsObj.mapValues { JSONValue(anyObject: $0) }
            } else {
                args = [:]
            }
            let keyRaw = (step["idempotency_key"] as? String) ?? ""
            let key = keyRaw.isEmpty
                ? PlanValidator.idempotencyKey(taskId: taskId, seq: index)
                : keyRaw
            steps.append(BrainAction(
                actionId: (step["action_id"] as? String) ?? "step-\(index)",
                tool: tool,
                args: args,
                requiresApproval: (step["requires_approval"] as? Bool) ?? false,
                idempotencyKey: key))
        }
        var risks: [String] = []
        if let risksArray = obj["risks"] as? [Any] {
            risks = risksArray.compactMap { $0 as? String }
        }
        return Plan(goal: goal, steps: steps, risks: risks.filter {
            !$0.trimmingCharacters(in: .whitespaces).isEmpty
        })
    }
}
