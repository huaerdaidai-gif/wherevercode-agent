import Foundation

/// Tool execution seam for the brain engine: the value types and the
/// registry surface BrainEngine uses, ported from the Android
/// baseline `brain/ToolRegistry.kt` on main @4496658e.
///
/// Scope note: the concrete registry (tool handlers, workspace
/// adapter, permission tiers, git-host tools) is the tool batch
/// (checklist I4/G). What lands here is everything the engine
/// compiles and runs against — the same injection shape Android's
/// engine tests use with a fake registry.
public struct ToolContext {
    public var taskId: String
    public var agentId: String
    public var approvalGate: ApprovalGate
    public var workspaceRoot: String
    public var deviceId: String
    public var folder: String
    /// The current chat project's git home — non-nil only when the
    /// chat project IS a git home. Git-home tools exist only then;
    /// all git work goes through the git-host API port, never a local
    /// checkout, never a clone.
    public var gitHostProject: Project? = nil
    public var gitHostLocation: ProjectLocation? = nil

    public init(
        taskId: String,
        agentId: String,
        approvalGate: ApprovalGate,
        workspaceRoot: String,
        deviceId: String,
        folder: String,
        gitHostProject: Project? = nil,
        gitHostLocation: ProjectLocation? = nil
    ) {
        self.taskId = taskId
        self.agentId = agentId
        self.approvalGate = approvalGate
        self.workspaceRoot = workspaceRoot
        self.deviceId = deviceId
        self.folder = folder
        self.gitHostProject = gitHostProject
        self.gitHostLocation = gitHostLocation
    }
}

public struct ToolOutcome: Equatable {
    public var ok: Bool
    public var summary: String
    public var data: String? = nil
    public var errorCode: String? = nil
    /// Workspace-state identity fields (only the list/read handlers
    /// fill them; all other handlers keep them nil):
    /// "empty" | "not_empty" | "not_configured" | "missing" |
    /// "not_readable" | "not_a_directory".
    public var workspaceState: String? = nil
    /// Redacted relative path: "" = workspace root; never an absolute
    /// path.
    public var path: String? = nil
    /// Fixed label distinguishing the app workspace from a project.
    public var workspaceLabel: String? = nil
    /// Executing host (ToolContext.deviceId; "local" today).
    public var host: String? = nil

    public init(
        ok: Bool,
        summary: String,
        data: String? = nil,
        errorCode: String? = nil,
        workspaceState: String? = nil,
        path: String? = nil,
        workspaceLabel: String? = nil,
        host: String? = nil
    ) {
        self.ok = ok
        self.summary = summary
        self.data = data
        self.errorCode = errorCode
        self.workspaceState = workspaceState
        self.path = path
        self.workspaceLabel = workspaceLabel
        self.host = host
    }
}

/// Workspace state as decided by the list handler's first line.
public enum WorkspaceState: String, Equatable {
    case ok = "ok"
    case empty = "empty"
    case notEmpty = "not_empty"
    case notConfigured = "not_configured"
    case missing = "missing"
    case notReadable = "not_readable"
    case notADirectory = "not_a_directory"

    public var label: String { rawValue }
}

/// The registry surface the engine depends on. The concrete
/// implementation lands with the tool batch; tests inject fakes.
public protocol ToolRegistry {
    /// Workspace root path, or nil when not configured. (Android:
    /// `File?`.) The engine uses "" as the placeholder when nil —
    /// never a fallback to "/".
    var workspaceRoot: String? { get }

    /// Local tool definitions visible for these capabilities and this
    /// authorized set (permission filtering happens inside).
    func toolsForCapabilities(
        _ capabilities: BrainCapabilities?,
        authorizedTools: Set<String>
    ) -> [ToolDef]

    /// Git-home tool definitions for the current git home; empty when
    /// there is none (the model never sees them then).
    func gitHostToolsFor(project: Project?, location: ProjectLocation?) -> [ToolDef]

    func allToolNames() -> Set<String>

    /// Permission re-check without executing (used when a cached
    /// read-only result is about to be replayed: authorization may
    /// have been revoked since). Returns nil when the action may
    /// proceed, or the refusal outcome.
    func validateOnly(_ action: BrainAction, _ context: ToolContext) -> ToolOutcome?

    /// Validate (structure -> permission -> approval) and execute one
    /// action. `stopped` is the registry-level second stop gate,
    /// polled from the turn's control state. A thrown error is
    /// surfaced by the engine as a `tool_failed` outcome, never as a
    /// blank turn.
    func execute(
        _ action: BrainAction,
        _ context: ToolContext,
        stopped: @escaping () -> Bool
    ) async throws -> ToolOutcome

    /// Read-only judgement for extension tools (plugin_ / mcp_), asked
    /// of the tool's source; unknown sources are treated as writes.
    func isReadOnlyExtensionTool(_ name: String) -> Bool
}

/// Pure tool rules, ported from the Android ToolRegistry companion.
public enum ToolRegistryRules {
    /// Tools that always need user confirmation.
    public static let alwaysNeedsApproval: Set<String> = [
        "write_file", "dispatch_task",
        "write_github_file", "create_github_branch", "create_github_pr",
    ]

    /// run_command argv[0] values that are read-only/view: automatic,
    /// no confirmation.
    public static let readOnlyCommands: Set<String> = [
        "ls", "cat", "head", "tail", "grep", "find", "wc", "echo", "pwd", "git",
    ]

    /// Owner tag distinguishing brain-tool rounds from Hand-channel
    /// rounds in write records.
    public static let roundOwner = "brain-tool"

    /// Per-command captured-output character cap (prevents OOM).
    public static let maxCapturedChars = 1_000_000

    private static let pythonNames: Set<String> = ["python", "python3"]
    /// Python flags that do not change semantics (no inline code).
    private static let pythonIgnoredFlags: Set<String> = ["-u", "-B"]

    /// Read-only run judgement (shared by approval and the read-only
    /// cache): an allowlisted command, or a pure python version probe
    /// (`python3 --version` — executes no user code).
    public static func isReadOnlyRun(_ argv: [String]?) -> Bool {
        guard let argv, !argv.isEmpty else { return false }
        let name = argv[0].split(separator: "/").last.map(String.init) ?? argv[0]
        if readOnlyCommands.contains(name) { return true }
        return isPythonVersionProbe(argv)
    }

    private static func isPythonVersionProbe(_ argv: [String]) -> Bool {
        let name = argv[0].split(separator: "/").last.map(String.init) ?? argv[0]
        if !pythonNames.contains(name) { return false }
        let rest = Array(argv.dropFirst()).filter { !pythonIgnoredFlags.contains($0) }
        return rest == ["--version"] || rest == ["-V"]
    }
}
