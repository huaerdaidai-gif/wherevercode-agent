import Foundation

/// Failover: same-brain retry + user-configured brain switching,
/// ported from the Android baseline `brain/Failover.kt` on main
/// @4496658e.
///
/// Rules:
/// - A retryable error retries ONCE on the same brain.
/// - After that, switch along the USER's explicit fallback order
///   only. Default: NO automatic switching (an empty fallback list
///   means stay).
/// - retryable=false (e.g. 401, model not found) reports the
///   actionable reason immediately, never retries.
/// - Capability degradation: toolCalling=false -> text parsing +
///   strict JSON extraction, labelled as such; streaming=false ->
///   whole text returned at once, NEVER a fake typewriter animation.
public struct FailoverPolicy: Equatable {
    public var maxSameBrainRetries: Int = 1
    public var fallbackBrainIds: [String] = []

    public init(maxSameBrainRetries: Int = 1, fallbackBrainIds: [String] = []) {
        self.maxSameBrainRetries = maxSameBrainRetries
        self.fallbackBrainIds = fallbackBrainIds
    }
}

public struct FailoverError: Error, Equatable {
    public var code: String
    public var message: String
    public var retryable: Bool

    public init(code: String, message: String, retryable: Bool) {
        self.code = code
        self.message = message
        self.retryable = retryable
    }
}

public enum FailoverOutcome<T> {
    case success(value: T, brainId: String, attempts: Int)
    case failure(error: BrainErrorInfo, triedBrains: [String])
}

public enum Failover {
    public static func shouldRetry(
        error: BrainErrorInfo, attemptsSoFar: Int, policy: FailoverPolicy
    ) -> Bool {
        error.retryable && attemptsSoFar < policy.maxSameBrainRetries
    }

    /// First fallback brain not tried yet, or nil (empty list = never
    /// auto-switch).
    public static func nextFallback(
        currentBrainId: String,
        policy: FailoverPolicy,
        tried: Set<String>
    ) -> String? {
        if policy.fallbackBrainIds.isEmpty { return nil }
        return policy.fallbackBrainIds.first { $0 != currentBrainId && !tried.contains($0) }
    }

    /// Human-readable degradation notes for the UI when a brain lacks
    /// a capability.
    public static func describeDegradation(_ capabilities: BrainCapabilities) -> [String] {
        var notes: [String] = []
        if !capabilities.toolCalling {
            notes.append("该大脑不支持原生工具调用，改用文本解析 + 严格 JSON 提取，解析可能失败。")
        }
        if !capabilities.streaming {
            notes.append("该大脑不支持流式输出，将整段返回，不使用打字机动画冒充流式。")
        }
        return notes
    }

    /// Strict JSON extraction for toolCalling=false brains: find the
    /// FIRST JSON object in the text that names a registered tool,
    /// parse it strictly, and return the actions. Anything ambiguous
    /// or malformed returns nil — never guess a tool call.
    /// Expected shape: {"tool":"read_file","args":{"path":"..."},
    /// "requires_approval":false} or {"actions":[{...}, ...]}.
    public static func extractToolCallsFromText(
        text: String,
        registeredTools: Set<String>,
        taskId: String
    ) -> [BrainAction]? {
        let chars = Array(text)
        guard let start = chars.firstIndex(of: "{") else { return nil }
        var depth = 0
        var end = -1
        for i in start..<chars.count {
            switch chars[i] {
            case "{":
                depth += 1
            case "}":
                depth -= 1
                if depth == 0 {
                    end = i
                }
            default:
                break
            }
            if end >= 0 { break }
        }
        if end < 0 { return nil }
        let jsonText = String(chars[start...end])
        guard let data = jsonText.data(using: .utf8),
              let raw = try? JSONSerialization.jsonObject(with: data),
              let obj = raw as? [String: Any]
        else { return nil }
        var steps: [[String: Any]] = []
        if obj.keys.contains("actions") {
            guard let array = obj["actions"] as? [Any] else { return nil }
            for item in array {
                guard let step = item as? [String: Any] else { return nil }
                steps.append(step)
            }
        } else {
            steps.append(obj)
        }
        var actions: [BrainAction] = []
        for (index, step) in steps.enumerated() {
            let tool = (step["tool"] as? String) ?? ""
            if !registeredTools.contains(tool) { return nil }
            let args: [String: JSONValue]
            if let argsObj = step["args"] as? [String: Any] {
                args = argsObj.mapValues { JSONValue(anyObject: $0) }
            } else {
                args = [:]
            }
            let rawKey = (step["idempotency_key"] as? String) ?? ""
            actions.append(BrainAction(
                actionId: (step["action_id"] as? String) ?? "text-\(index)",
                tool: tool,
                args: args,
                requiresApproval: (step["requires_approval"] as? Bool) ?? false,
                idempotencyKey: rawKey.isEmpty
                    ? PlanValidator.idempotencyKey(taskId: taskId, seq: index)
                    : rawKey))
        }
        return actions.isEmpty ? nil : actions
    }
}

/// Generic retry-then-fallback runner. `attempt` throws
/// [FailoverError] on failure; any other error propagates to the
/// caller uncaught (same as Android). Each fallback brain gets one
/// attempt (the same-brain retry applies only to the first brain).
public final class FailoverRunner {
    private let policy: FailoverPolicy

    public init(policy: FailoverPolicy) {
        self.policy = policy
    }

    public func run<T>(
        firstBrainId: String,
        attempt: (String) async throws -> T
    ) async throws -> FailoverOutcome<T> {
        var tried: [String] = []
        var brainId: String? = firstBrainId
        var sameBrainAttempts = 0
        var lastError: BrainErrorInfo? = nil
        while let current = brainId {
            // A retried brain is one attempt, not two entries: the
            // same brain must appear once in triedBrains.
            if !tried.contains(current) { tried.append(current) }
            do {
                let value = try await attempt(current)
                return .success(value: value, brainId: current, attempts: tried.count + sameBrainAttempts)
            } catch let e as FailoverError {
                lastError = BrainErrorInfo(code: e.code, message: e.message, retryable: e.retryable)
                // Non-retryable (e.g. 401, model not found): report
                // the actionable reason immediately — no same-brain
                // retry and no fallback switch.
                if !e.retryable {
                    return .failure(error: lastError!, triedBrains: tried)
                }
                if Failover.shouldRetry(error: lastError!, attemptsSoFar: sameBrainAttempts, policy: policy) {
                    sameBrainAttempts += 1
                    continue
                }
                sameBrainAttempts = 0
                brainId = Failover.nextFallback(
                    currentBrainId: current, policy: policy, tried: Set(tried))
            }
        }
        return .failure(
            error: lastError ?? BrainErrorInfo(
                code: "failover_failed", message: "所有大脑均失败", retryable: false),
            triedBrains: tried)
    }
}
