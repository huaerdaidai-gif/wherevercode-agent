import Foundation

public struct Plan: Codable, Equatable, Sendable {
    public let goal: String
    public let steps: [BrainAction]
    public let risks: [String]

    public init(goal: String, steps: [BrainAction], risks: [String] = []) {
        self.goal = goal
        self.steps = steps
        self.risks = risks
    }
}

public enum Planner {
    public static func idempotencyKey(taskId: String, seq: Int) -> String {
        "\(taskId):\(seq)"
    }

    public static func validate(_ plan: Plan, registeredTools: Set<String>, taskId: String) -> [String] {
        var errors: [String] = []
        if plan.steps.isEmpty {
            errors.append("计划没有步骤")
        }
        var seenKeys: Set<String> = []
        for step in plan.steps {
            if !registeredTools.contains(step.tool) {
                errors.append("\(step.actionId)：未知工具 \(step.tool)")
            }
            if !step.idempotencyKey.hasPrefix("\(taskId):") {
                errors.append("\(step.actionId)：幂等键不属于本任务 \(step.idempotencyKey)（应为 task_id:seq）")
            }
            if !seenKeys.insert(step.idempotencyKey).inserted {
                errors.append("\(step.actionId)：幂等键重复 \(step.idempotencyKey)")
            }
        }
        return errors
    }
}
