import Foundation

/// Canonical task-state contract, ported from the Android baseline
/// `core/task/TaskStates.kt` (itself ported verbatim from the old
/// project's task_states.py). Pure functions only: no I/O, no storage.
///
/// - `rework`: review sent the task back; rework must pass through
///   it (review → rework → assigned → working; the old direct
///   review → working edge is gone).
/// - `requeued`: lease expiry returns the task to a claimable state
///   instead of quietly re-queueing; attempts exhausted → failed.
/// - Claimable states are queued / requeued / rework; every claim
///   filter uses `isClaimable`, never a hand-written state check.
public enum TaskStates {
    public static let canonicalStates: [String] = [
        "queued", "assigned", "working", "blocked",
        "review", "rework", "requeued", "completed", "failed", "cancelled",
    ]

    public static let terminalStates: Set<String> = ["completed", "failed", "cancelled"]

    /// Legal targets per source state. Creation (nil source) is
    /// handled separately: it may only go to "queued". Terminal
    /// states have no outgoing edges.
    public static var allowedTransitions: [String: Set<String>] { transitions }

    public static let transitions: [String: Set<String>] = [
        "queued": ["assigned", "working", "cancelled"],
        "requeued": ["assigned", "cancelled"],
        "rework": ["assigned", "cancelled"],
        "assigned": ["working", "queued", "requeued", "failed", "cancelled"],
        "working": ["blocked", "review", "failed", "cancelled", "requeued"],
        "blocked": ["working", "cancelled"],
        "review": ["rework", "completed", "failed", "cancelled"],
        "completed": [],
        "failed": [],
        "cancelled": [],
    ]

    public struct CanonicalMapping: Equatable, Sendable {
        public var state: String
        public let isPaused: Bool
        public var needsAttention: Bool
        public var rawState: String
        public var system: String

        public init(state: String, isPaused: Bool = false, needsAttention: Bool = false, rawState: String = "", system: String = "") {
            self.state = state
            self.isPaused = isPaused
            self.needsAttention = needsAttention
            self.rawState = rawState
            self.system = system
        }
    }

    /// system → raw state → (canonical state, needsAttention)
    private static let mapping: [String: [String: (String, Bool)]] = [
        "task_assignment": [
            "waiting": ("queued", false),
            "ready": ("queued", false),
            "assigned": ("assigned", false),
            "working": ("working", false),
            "blocked": ("blocked", false),
            "review": ("review", false),
            "completed": ("completed", false),
            "failed": ("failed", false),
            "stopped": ("cancelled", false),
        ],
        "conversation_task": [
            "queued": ("queued", false),
            "running": ("working", false),
            "completed": ("completed", false),
            "failed": ("failed", false),
            "stopped": ("cancelled", false),
        ],
        "execution_host": [
            "queued": ("queued", false),
            "running": ("working", false),
            "completed": ("completed", false),
            "failed": ("failed", false),
            "cancelled": ("cancelled", false),
        ],
    ]

    public enum TaskStateError: Error, Equatable, CustomStringConvertible {
        case invalidTransition(from: String?, to: String?)
        case unknownCanonicalState(String)
        case unknownSystem(String)
        case unknownRawState(system: String, rawState: String)

        public var description: String {
            switch self {
            case .invalidTransition(let from, let to):
                return "invalid state transition: \(from ?? "nil") -> \(to ?? "nil")"
            case .unknownCanonicalState(let state):
                return "unknown canonical state: \(state)"
            case .unknownSystem(let system):
                return "unknown system: \(system)"
            case .unknownRawState(let system, let rawState):
                return "unknown state \(rawState) for system \(system)"
            }
        }
    }

    /// Throws unless the transition is legal. A nil source means
    /// creation, which may only go to "queued". An unknown source
    /// raises `unknownCanonicalState`; a known source with a
    /// forbidden (or unknown) target raises `invalidTransition`.
    public static func checkTransition(fromState: String?, toState: String) throws {
        guard let fromState else {
            if toState != "queued" {
                throw TaskStateError.invalidTransition(from: nil, to: toState)
            }
            return
        }
        guard canonicalStates.contains(fromState) else {
            throw TaskStateError.unknownCanonicalState(fromState)
        }
        guard transitions[fromState]?.contains(toState) == true else {
            throw TaskStateError.invalidTransition(from: fromState, to: toState)
        }
    }

    /// Maps one system's raw state to the canonical state. Unknown
    /// systems and unknown raw states throw; nothing is guessed.
    public static func canonical(system: String, rawState: String) throws -> CanonicalMapping {
        if (system == "task_assignment" || system == "conversation_task" || system == "v5") && rawState == "paused" {
            return CanonicalMapping(state: "working", isPaused: true, needsAttention: true, rawState: rawState, system: system)
        }
        let lookupSystem = (system == "v5") ? "task_assignment" : system
        guard let table = mapping[lookupSystem] else {
            throw TaskStateError.unknownSystem(system)
        }
        guard let (state, needsAttention) = table[rawState] else {
            throw TaskStateError.unknownRawState(system: system, rawState: rawState)
        }
        return CanonicalMapping(state: state, isPaused: false, needsAttention: needsAttention, rawState: rawState, system: system)
    }

    public static func validateTransition(from currentState: String?, to nextState: String) throws {
        try checkTransition(fromState: currentState, toState: nextState)
    }

    public static func mapToCanonical(system: String, rawState: String) throws -> CanonicalMapping {
        if canonicalStates.contains(rawState) {
            return CanonicalMapping(state: rawState, isPaused: false, needsAttention: rawState == "blocked" || rawState == "rework", rawState: rawState, system: system)
        }
        return try canonical(system: system, rawState: rawState)
    }

    public static func displayLabel(for state: String, isPaused: Bool = false) -> String {
        let base: String
        switch state {
        case "queued": base = "排队中 (queued)"
        case "assigned": base = "已指派 (assigned)"
        case "working": base = "执行中 (working)"
        case "blocked": base = "已阻塞 (blocked)"
        case "review": base = "待评审 (review)"
        case "rework": base = "需返工 (rework)"
        case "requeued": base = "重入队 (requeued)"
        case "completed": base = "已完成 (completed)"
        case "failed": base = "已失败 (failed)"
        case "cancelled": base = "已取消 (cancelled)"
        default: base = state
        }
        return isPaused ? "\(base) · 已暂停" : base
    }

    /// 所有状态的中文显示标签（照片批 E）：与安卓 KanbanStatus.labelOf
    /// 逐字一致，是任务状态标签的唯一事实来源——控制台总览面板的任务行
    /// 与最近事件行用它，不再直出英文原始状态。未知状态显示「处理中」。
    /// （displayLabel 是任务页的双语标签，与本标签口径不同，互不影响。）
    public static func label(of state: String) -> String {
        switch state.lowercased() {
        case "queued", "pending": return "待办"
        case "assigned", "claimed": return "已接收"
        case "working", "running": return "进行中"
        case "review": return "待审核"
        case "rework": return "返工"
        case "requeued": return "已回队"
        case "blocked": return "已阻塞"
        case "completed", "complete", "done": return "已完成"
        case "paused": return "已暂停"
        case "cancel_requested", "stopping": return "正在停止"
        case "failed", "error": return "失败"
        case "cancelled", "canceled", "stopped": return "已停止"
        default: return "处理中"
        }
    }

    public static func isTerminal(_ state: String) -> Bool {
        terminalStates.contains(state)
    }

    /// Claimable: queued (new) / requeued (lease expired back) /
    /// rework (review sent back). All claim filters use this.
    public static func isClaimable(_ state: String) -> Bool {
        state == "queued" || state == "requeued" || state == "rework"
    }
}
