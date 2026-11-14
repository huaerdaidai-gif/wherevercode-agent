import Foundation

public enum TaskStates {
    /// Unified 10 canonical states shared identically across Android and iOS.
    /// Note: `pause` is an independent boolean flag (`isPaused: Bool`), NEVER a primary state.
    public static let canonicalStates: [String] = [
        "queued",
        "assigned",
        "working",
        "blocked",
        "review",
        "rework",
        "requeued",
        "completed",
        "failed",
        "cancelled"
    ]

    public static let terminalStates: Set<String> = [
        "completed",
        "failed",
        "cancelled"
    ]

    public static let allowedTransitions: [String: Set<String>] = [
        "queued": ["assigned", "working", "cancelled", "failed"],
        "assigned": ["working", "blocked", "requeued", "cancelled", "failed"],
        "working": ["blocked", "review", "rework", "requeued", "completed", "failed", "cancelled"],
        "blocked": ["working", "requeued", "failed", "cancelled"],
        "review": ["completed", "rework", "failed", "cancelled"],
        "rework": ["assigned", "working", "review", "failed", "cancelled"],
        "requeued": ["assigned", "working", "cancelled", "failed"],
        "completed": [],
        "failed": ["requeued"],
        "cancelled": ["requeued"]
    ]

    public struct CanonicalMapping: Equatable, Sendable {
        public let state: String
        public let needsAttention: Bool
        public let isPaused: Bool

        public init(state: String, needsAttention: Bool = false, isPaused: Bool = false) {
            self.state = state
            self.needsAttention = needsAttention
            self.isPaused = isPaused
        }
    }

    public enum TaskStateError: Error, CustomStringConvertible, Equatable {
        case invalidTransition(from: String, to: String)
        case unknownCanonicalState(String)
        case unknownSystem(String)
        case unknownRawState(system: String, rawState: String)

        public var description: String {
            switch self {
            case .invalidTransition(let from, let to):
                return "invalid state transition: \(from) -> \(to)"
            case .unknownCanonicalState(let s):
                return "unknown canonical state: \(s)"
            case .unknownSystem(let sys):
                return "unknown system: \(sys)"
            case .unknownRawState(let sys, let raw):
                return "unknown raw state in \(sys): \(raw)"
            }
        }
    }

    public static func validateTransition(from: String, to: String) throws {
        guard canonicalStates.contains(from) else {
            throw TaskStateError.unknownCanonicalState(from)
        }
        guard canonicalStates.contains(to) else {
            throw TaskStateError.unknownCanonicalState(to)
        }
        if from == to { return }
        guard let allowed = allowedTransitions[from], allowed.contains(to) else {
            throw TaskStateError.invalidTransition(from: from, to: to)
        }
    }

    public static func mapToCanonical(system: String, rawState: String) throws -> CanonicalMapping {
        let lower = rawState.lowercased().trimmingCharacters(in: .whitespacesAndNewlines)
        switch system {
        case "canonical", "v5", "mcp":
            if canonicalStates.contains(lower) {
                return CanonicalMapping(
                    state: lower,
                    needsAttention: lower == "blocked" || lower == "review" || lower == "rework",
                    isPaused: false
                )
            }
            if lower == "paused" {
                return CanonicalMapping(state: "working", needsAttention: true, isPaused: true)
            }
            if lower == "in_progress" || lower == "running" || lower == "claimed" {
                return CanonicalMapping(state: "working", needsAttention: false, isPaused: false)
            }
            if lower == "pending" || lower == "open" {
                return CanonicalMapping(state: "queued", needsAttention: false, isPaused: false)
            }
            if lower == "submitted" || lower == "awaiting_review" {
                return CanonicalMapping(state: "review", needsAttention: true, isPaused: false)
            }
            if lower == "done" || lower == "resolved" {
                return CanonicalMapping(state: "completed", needsAttention: false, isPaused: false)
            }
            throw TaskStateError.unknownRawState(system: system, rawState: rawState)
        default:
            throw TaskStateError.unknownSystem(system)
        }
    }

    public static func displayLabel(for state: String, isPaused: Bool = false) -> String {
        let base: String
        switch state {
        case "queued": base = "排队中 (queued)"
        case "assigned": base = "已指派 (assigned)"
        case "working": base = "执行中 (working)"
        case "blocked": base = "阻塞待处理 (blocked)"
        case "review": base = "待审查 (review)"
        case "rework": base = "需返工 (rework)"
        case "requeued": base = "重新入队 (requeued)"
        case "completed": base = "已完成 (completed)"
        case "failed": base = "失败 (failed)"
        case "cancelled": base = "已取消 (cancelled)"
        default: base = state
        }
        return isPaused ? "\(base) · 已暂停" : base
    }
}
