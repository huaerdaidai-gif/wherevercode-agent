import Foundation

/// BrainEngine: conversation loop + tool-call loop (stage 2), ported
/// from the Android baseline `brain/BrainEngine.kt` on main
/// @4496658e.
///
/// Approval is injected through [ApprovalGate] so the engine can be
/// unit-tested with a fake gate; the default implementation refuses
/// anything that needs confirmation (UI wiring is stage 3).
///
/// Flow:
/// 1. ContextManager assembles messages.
/// 2. Adapter.chatStream() yields BrainEvents.
/// 3. A tool call goes through ToolRegistry validation
///    (structure -> permission -> approval) and only then executes;
///    the result is fed back as a role=tool message.
/// 4. Every event is written to [EventLog] (redacted first).
/// 5. Switching brains only changes the conversation's brainId; the
///    task row never moves.
///
/// Platform note: the iOS ConversationStore throws (SQLite); store
/// failures inside a turn degrade to "this write did not persist"
/// instead of crashing the turn, which is the closest honest analogue
/// of Android's unthrowing Room calls.

public protocol ApprovalGate {
    func requestApproval(action: BrainAction) async -> Bool

    /// Second authorization check, run by the registry after approval
    /// and before acting: the production gate uses it to void an
    /// authorization whose UI has gone away. Default: no extra limit.
    func isApprovalStillValid(action: BrainAction) -> Bool
}

extension ApprovalGate {
    public func isApprovalStillValid(action: BrainAction) -> Bool { true }
}

/// Default gate: refuse anything that needs confirmation. The UI
/// replaces it in stage 3.
public struct DenyApprovalGate: ApprovalGate {
    public init() {}
    public func requestApproval(action: BrainAction) async -> Bool { false }
}

public struct EngineEvent: Equatable {
    public var seq: Int64
    public var conversationId: String
    public var kind: String
    public var detail: String
    public var epochSeconds: Int64

    public init(seq: Int64, conversationId: String, kind: String, detail: String, epochSeconds: Int64) {
        self.seq = seq
        self.conversationId = conversationId
        self.kind = kind
        self.detail = detail
        self.epochSeconds = epochSeconds
    }
}

public protocol EventLog {
    func record(_ event: EngineEvent)
    func entries(conversationId: String?) -> [EngineEvent]
}

public final class InMemoryEventLog: EventLog {
    private let lock = NSLock()
    private var items: [EngineEvent] = []
    private var seq: Int64 = 0

    public init() {}

    public func record(_ event: EngineEvent) {
        lock.lock(); defer { lock.unlock() }
        seq += 1
        var stored = event
        stored.seq = seq
        items.append(stored)
    }

    public func entries(conversationId: String? = nil) -> [EngineEvent] {
        lock.lock(); defer { lock.unlock() }
        if let conversationId {
            return items.filter { $0.conversationId == conversationId }
        }
        return items
    }
}

public struct ChatOutcome: Equatable {
    public var conversationId: String
    public var assistantText: String
    public var toolCallsExecuted: Int
    public var brainId: String
    public var switchedBrain: Bool

    public init(conversationId: String, assistantText: String, toolCallsExecuted: Int, brainId: String, switchedBrain: Bool) {
        self.conversationId = conversationId
        self.assistantText = assistantText
        self.toolCallsExecuted = toolCallsExecuted
        self.brainId = brainId
        self.switchedBrain = switchedBrain
    }
}

/// Turn-internal control snapshot (mapped by the caller from the
/// control plane / cancellation token).
public enum TurnControlState: String, Equatable {
    case running = "RUNNING"
    case paused = "PAUSED"
    case stopped = "STOPPED"
    case goalChanged = "GOAL_CHANGED"
}

/// Budget snapshot for tool-round decisions (rounds / call count /
/// total duration).
public struct TurnBudget: Equatable {
    public var roundIndex: Int
    public var toolCallsExecuted: Int
    public var maxToolRounds: Int
    public var maxToolCallsPerTurn: Int
    public var elapsedMillis: Int64
    public var maxTurnMillis: Int64

    public init(roundIndex: Int, toolCallsExecuted: Int, maxToolRounds: Int, maxToolCallsPerTurn: Int, elapsedMillis: Int64, maxTurnMillis: Int64) {
        self.roundIndex = roundIndex
        self.toolCallsExecuted = toolCallsExecuted
        self.maxToolRounds = maxToolRounds
        self.maxToolCallsPerTurn = maxToolCallsPerTurn
        self.elapsedMillis = elapsedMillis
        self.maxTurnMillis = maxTurnMillis
    }

    public func isExhausted() -> Bool {
        roundIndex >= maxToolRounds
            || toolCallsExecuted >= maxToolCallsPerTurn
            || (maxTurnMillis > 0 && elapsedMillis >= maxTurnMillis)
    }
}

/// One executed / cached tool call within a single user message.
/// Lives only inside one `chat()` call; discarded when the call ends,
/// never carried across messages or goals. The cache key includes the
/// brainId: after a brain switch the old cache does not hit, but the
/// ledger itself is not cleared (write-side repeat and progress
/// judgements still use the full ledger, so a write cannot land twice
/// by switching brains).
public struct ToolCallRecord: Equatable {
    public var key: String
    public var name: String
    public var args: [String: JSONValue]
    public var roundIndex: Int
    public var outcome: ToolOutcome
    public var fromCache: Bool
    public var resultHash: String
    public var executedAtEpochSeconds: Int64
    public var brainId: String
    public var idempotencyKey: String

    public init(key: String, name: String, args: [String: JSONValue], roundIndex: Int, outcome: ToolOutcome, fromCache: Bool, resultHash: String, executedAtEpochSeconds: Int64, brainId: String, idempotencyKey: String) {
        self.key = key
        self.name = name
        self.args = args
        self.roundIndex = roundIndex
        self.outcome = outcome
        self.fromCache = fromCache
        self.resultHash = resultHash
        self.executedAtEpochSeconds = executedAtEpochSeconds
        self.brainId = brainId
        self.idempotencyKey = idempotencyKey
    }
}

/// Per-turn in-memory tool ledger. The ledger is authoritative;
/// the engine's seen-set is only a fast write-side check.
public final class TurnToolLedger {
    private var records: [String: ToolCallRecord] = [:]
    private var insertionOrder: [String] = []

    public init() {}

    private func compositeKey(_ key: String, _ brainId: String) -> String {
        "\(brainId)|\(key)"
    }

    public func record(_ r: ToolCallRecord) {
        let composite = compositeKey(r.key, r.brainId)
        if records[composite] == nil { insertionOrder.append(composite) }
        records[composite] = r
    }

    public func lookup(key: String, brainId: String) -> ToolCallRecord? {
        records[compositeKey(key, brainId)]
    }

    /// Distinct tool signatures (brainId stripped), for progress
    /// judgement.
    public var distinctKeyCount: Int {
        Set(records.values.map { $0.key }).count
    }

    /// The newest record's result hash ("" when empty), for progress
    /// judgement.
    public var lastResultHash: String {
        insertionOrder.last.flatMap { records[$0]?.resultHash } ?? ""
    }
}

public final class BrainEngine {
    private let adapters: BrainAdapterRegistry
    private let conversations: ConversationStore
    private let contextManager: ContextManager
    private let memory: MemoryStore
    /// Project long-term skill store. Nil by default (older callers
    /// and tests are untouched); production injects the real store.
    /// Per turn, relevant skills are pulled into the context by task
    /// content — never the whole history.
    private let skillStore: SkillStore?
    private let tools: ToolRegistry
    private let eventLog: EventLog
    private let approvalGate: ApprovalGate
    private let currentBrainIdProvider: () -> String?
    private let maxToolRounds: Int
    /// Real-execution cap per user message (cache hits do not count)
    /// and total-duration cap. Defaults 8 / 120 s; maxTurnMillis = 0
    /// means unlimited (tests only).
    private let maxToolCallsPerTurn: Int
    private let maxTurnMillis: Int64
    /// Control-state snapshot provider. Default: always running;
    /// production wiring maps the control plane / cancellation token.
    private let controlStateProvider: () -> TurnControlState
    /// Test clock seam: defaults to system time.
    private let nowMillisProvider: () -> Int64
    private let failoverPolicy: FailoverPolicy
    /// Optional session ledger. Default is the no-op ledger, so
    /// existing callers and tests are untouched. When a real ledger
    /// is injected, every turn is classified first: CONTROL
    /// short-circuits here — it goes to the router, never reaches an
    /// adapter, and costs zero model calls — while CHAT continues
    /// through the existing flow and is recorded as a session message.
    /// Control still works when no brain is configured (user
    /// sovereignty sits above the brain).
    private let sessionLedger: SessionLedger
    /// The current chat project's git home (provided by the caller;
    /// nil when the open project has no git home). Snapshotted once
    /// per turn: git-home tools are offered only when it is non-nil.
    private let gitHostProjectContextProvider: () -> (project: Project, location: ProjectLocation)?
    /// Web search client: used only when the turn's search toggle is
    /// on.
    private let webSearch: WebSearchClient

    public init(
        adapters: BrainAdapterRegistry,
        conversations: ConversationStore,
        contextManager: ContextManager,
        memory: MemoryStore,
        skillStore: SkillStore? = nil,
        tools: ToolRegistry,
        eventLog: EventLog,
        approvalGate: ApprovalGate,
        currentBrainId: @escaping () -> String?,
        maxToolRounds: Int = 8,
        maxToolCallsPerTurn: Int = 8,
        maxTurnMillis: Int64 = 120_000,
        controlState: @escaping () -> TurnControlState = { .running },
        nowMillis: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
        failoverPolicy: FailoverPolicy = FailoverPolicy(),
        sessionLedger: SessionLedger = NoOpSessionLedger(),
        gitHostProjectContext: @escaping () -> (project: Project, location: ProjectLocation)? = { nil },
        webSearch: WebSearchClient = WebSearchClient()
    ) {
        self.adapters = adapters
        self.conversations = conversations
        self.contextManager = contextManager
        self.memory = memory
        self.skillStore = skillStore
        self.tools = tools
        self.eventLog = eventLog
        self.approvalGate = approvalGate
        self.currentBrainIdProvider = currentBrainId
        self.maxToolRounds = maxToolRounds
        self.maxToolCallsPerTurn = maxToolCallsPerTurn
        self.maxTurnMillis = maxTurnMillis
        self.controlStateProvider = controlState
        self.nowMillisProvider = nowMillis
        self.failoverPolicy = failoverPolicy
        self.sessionLedger = sessionLedger
        self.gitHostProjectContextProvider = gitHostProjectContext
        self.webSearch = webSearch
    }

    /// One user turn. Returns a stream of BrainEvents (text deltas,
    /// tool calls/results, usage, errors, done). The caller collects
    /// it; history is persisted as the turn completes. Cancellation
    /// of the collecting task finishes the stream with
    /// CancellationError — it is not a brain failure and is never
    /// dressed up as one.
    ///
    /// - `brainIdOverride` switches the brain for this conversation
    ///   only; the conversation row keeps its history.
    /// - `allowFailover` = false forbids cross-brain failover for
    ///   this turn (same-brain retry still applies). Turns for a
    ///   user-selected Agent MUST pass false: the user picked that
    ///   brain; silently switching to another brain / another billing
    ///   party is the bug itself.
    /// - `taskId`: the turn-task id opened by the caller; tool calls
    ///   use it as their ToolContext.taskId so the turn's change set
    ///   is attributed to the task. Nil falls back to the
    ///   conversation id.
    /// - `projectId`: the project this turn belongs to (free chat
    ///   passes nil -> only global skills are pulled).
    /// - `currentMessageOverride`: the current-turn body sent to the
    ///   model (attachment-expanded form, with text-file contents).
    ///   History always stores `userText` (attachments leave only a
    ///   description line); file contents never enter history and are
    ///   never resent per round.
    /// - `imageDataUrls`: this turn's images (downsampled data
    ///   URLs). Attached only to this turn's user message; filtered
    ///   per round by the round brain's vision capability.
    /// - `webSearchEnabled`: the web-search toggle. On: search first,
    ///   results enter this turn's context as a system message. Off:
    ///   no search, no network.
    public func chat(
        conversationId: String,
        userText: String,
        brainIdOverride: String? = nil,
        allowFailover: Bool = true,
        taskId: String? = nil,
        projectId: String? = nil,
        currentMessageOverride: String? = nil,
        imageDataUrls: [String] = [],
        webSearchEnabled: Bool = false
    ) -> AsyncThrowingStream<BrainEvent, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    try await self.runTurn(
                        conversationId: conversationId,
                        userText: userText,
                        brainIdOverride: brainIdOverride,
                        allowFailover: allowFailover,
                        taskId: taskId,
                        projectId: projectId,
                        currentMessageOverride: currentMessageOverride,
                        imageDataUrls: imageDataUrls,
                        webSearchEnabled: webSearchEnabled,
                        emit: { continuation.yield($0) })
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    // MARK: - Turn implementation

    private func runTurn(
        conversationId: String,
        userText: String,
        brainIdOverride: String?,
        allowFailover: Bool,
        taskId: String?,
        projectId: String?,
        currentMessageOverride: String?,
        imageDataUrls: [String],
        webSearchEnabled: Bool,
        emit: @escaping (BrainEvent) -> Void
    ) async throws {
        // Blank-bubble fix: remember whether a specific Error event
        // was already emitted, so the final empty-response guard does
        // not replace/duplicate it. Every Error emission goes through
        // emitError.
        var terminalErrorEmitted = false
        func emitError(_ err: BrainErrorInfo) {
            terminalErrorEmitted = true
            emit(.error(err))
        }
        // Reuse the caller-supplied conversation id: the conversation
        // store's get-or-create keeps the same id, so turns never
        // fragment into one conversation + session row per turn.
        let conversation: Conversation
        do {
            conversation = try conversations.getOrCreateConversation(
                id: conversationId,
                brainId: brainIdOverride ?? currentBrainIdProvider(),
                title: String(userText.prefix(40)))
        } catch {
            let err = BrainErrorInfo(
                code: "store_error",
                message: "会话存储失败：\(error.localizedDescription)",
                retryable: true)
            emitError(err)
            emit(.done(finishReason: "error"))
            return
        }
        let requestedBrainId = brainIdOverride ?? conversation.brainId
        // Classify first. A CONTROL instruction short-circuits here —
        // recorded + routed by the ledger, no adapter call, no model
        // cost — and works even with no brain configured.
        let ledgerResult = sessionLedger.record(
            IncomingMessage(
                conversationId: conversation.id,
                senderId: "user",
                content: userText))
        if ledgerResult.kind == .control {
            let summary = ledgerResult.summary
            emit(.textDelta(summary))
            log(conversation.id, "control_routed", SecretRedactor.redact(summary, limit: 500))
            emit(.done(finishReason: ledgerResult.ok ? "control" : "control_rejected"))
            return
        }
        // Brain backfill: an old conversation row's brainId may be
        // nil (stored while no brain was current); it never heals by
        // itself and the old conversation errors on every touch. When
        // this turn has no brain id, fall back to the current global
        // brain and write the correction back; only when it is still
        // empty afterwards report "not configured" honestly. Never
        // "default to some configured brain" here — no default brain
        // is the settled semantic. The CONTROL short-circuit above is
        // unaffected.
        var backfilledBrainId = false
        var effectiveBrainId: String? = nil
        if let requested = requestedBrainId, !Self.isBlank(requested) {
            effectiveBrainId = requested
        } else if let fallback = currentBrainIdProvider(), !Self.isBlank(fallback) {
            try? conversations.updateConversationBrain(id: conversation.id, brainId: fallback)
            log(conversation.id, "brain_backfilled", "旧会话未绑大脑，已按当前大脑 \(fallback) 继续")
            backfilledBrainId = true
            effectiveBrainId = fallback
        }
        guard let effective = effectiveBrainId, !Self.isBlank(effective) else {
            let err = BrainErrorInfo(
                code: "brain_not_configured",
                message: "未配置大脑：请先在设置里配置一个大脑，再发送消息。",
                retryable: false)
            emitError(err)
            emit(.done(finishReason: "error"))
            log(conversation.id, "error", err.message)
            return
        }
        if !backfilledBrainId && effective != conversation.brainId {
            try? conversations.updateConversationBrain(id: conversation.id, brainId: effective)
            log(conversation.id, "brain_switched", "大脑已切换为 \(effective)，任务与历史不变")
        }

        _ = try? conversations.addMessage(
            ConversationMessage(
                id: newId(),
                conversationId: conversation.id,
                role: "user",
                content: userText,
                source: "user",
                createdAtEpochSeconds: nowSeconds()))
        log(conversation.id, "user_message", SecretRedactor.redact(userText, limit: 500))

        let history = (try? conversations.listMessages(conversationId: conversation.id)) ?? []
        // This turn's git-home snapshot (only when the open project
        // is a git home); lives only inside this turn.
        let gitHostContext = gitHostProjectContextProvider()
        // First-round routing: a greeting / small-talk first round
        // carries no tools — the context's "available tools" reminder
        // must not list them either, or the system prompt still primes
        // the model to act; an action-oriented first round carries the
        // full tool definitions. The first round's tools are computed
        // from THIS turn brain's real capabilities, never null
        // capabilities (null would list the full set): the model only
        // ever sees tools it can actually call.
        let actionOriented = Self.looksActionOriented(userText)
        let requestedCaps = adapters.configFor(effective)
            .flatMap { adapters.get($0.adapterId) }?.capabilities()
        let firstRoundTools = actionOriented
            ? toolsForTurn(requestedCaps, gitHostContext)
            : []
        // Skill memory: pull the skills relevant to this turn's task
        // text (project skills first, global as backfill; none
        // relevant -> "", no tokens burned).
        let skillSummary = skillStore?.summarizeForPrompt(
            projectId: SkillMemory.projectIdOrGlobal(projectId),
            taskText: userText) ?? ""
        let assembled = contextManager.build(
            systemPrompt: Self.systemPrompt,
            memorySummary: memory.summarize(),
            history: history,
            currentMessage: currentMessageOverride ?? userText,
            tools: firstRoundTools,
            skillSummary: skillSummary,
            currentImages: imageDataUrls.map { ChatImage(dataUrl: $0) })
        var messages = assembled.messages
        // Web search: only when the toggle is on. Results are
        // inserted as a system message just before this turn's user
        // message (this turn's context only, never persisted into
        // history); a failed / empty search inserts an honest note
        // instead, so the model never believes it searched when it
        // did not.
        if webSearchEnabled {
            let searchNote = await runWebSearch(conversationId: conversation.id, query: userText)
            messages.insert(
                ChatMessage(role: "system", content: searchNote),
                at: messages.count - 1)
        }
        // This turn's user message is the last assembled message;
        // images live only on it. (The web-search insert above goes
        // before it, so it is still last here.)
        let currentUserMessageIndex = messages.count - 1
        // Per-round image filtering: the round brain's vision
        // capability decides whether the images are sent; without
        // vision the images are dropped AND the body says so plainly
        // — the model must never invent content for an image it did
        // not receive. Recomputed every round (a failover switch
        // re-evaluates against the new brain), always from the
        // original message list, so the note never accumulates.
        func messagesForRound(_ adapter: BrainAdapter) -> [ChatMessage] {
            if imageDataUrls.isEmpty { return messages }
            if adapter.capabilities().vision { return messages }
            return messages.enumerated().map { index, msg in
                if index == currentUserMessageIndex {
                    var copy = msg
                    copy.images = []
                    copy.content = msg.content +
                        "\n[注意：本轮随附了一张图片，但当前大脑不支持看图片。" +
                        "你收不到图片内容，请如实告诉用户你看不到图片，不要猜测图片里有什么。]"
                    return copy
                }
                return msg
            }
        }
        // The workspace root may be nil (not configured). ToolContext
        // .workspaceRoot is only a placeholder (real resolution lives
        // in the registry; an empty root fails its permission check
        // with path_not_allowed) — use "" as the placeholder, never
        // fall back to "/".
        let toolContext = ToolContext(
            taskId: taskId ?? conversation.id,
            agentId: "brain",
            approvalGate: approvalGate,
            workspaceRoot: tools.workspaceRoot ?? "",
            deviceId: "local",
            folder: tools.workspaceRoot ?? "",
            gitHostProject: gitHostContext?.project,
            gitHostLocation: gitHostContext?.location)

        var brainId: String = effective
        var switched = false
        var sameBrainAttempts = 0
        var triedBrains: Set<String> = []
        var toolCallsExecuted = 0
        var assistantText = ""
        var finished = false
        // Multi-step machinery: the ledger (authoritative) + the
        // seen set (write-side fast check) + the terminal-failure set
        // + the three-dimensional progress judgement. All live only
        // inside this turn.
        let ledger = TurnToolLedger()
        let turnStartedAt = nowMillisProvider()
        var seenToolCalls: Set<String> = []
        var seenTerminalFailures: Set<String> = []
        // tool_no_progress fix: a repeated hand_not_found against the
        // same normalised target is treated as a terminal failure
        // (guard 3's ending); recentFailures feeds guard 2's failure
        // summary; knownListings remembers this turn's successful
        // directory listings so a failed read can be answered with
        // the available files.
        var seenNotFoundFailures: Set<String> = []
        var recentFailures: [String] = []
        var knownListings: [String: String] = [:]
        var noProgressRounds = 0
        var lastProgressSignature: String? = nil
        var lastPendingToolCalls: [BrainToolCall] = []
        var doneReason: String? = nil
        var roundIndex = 0
        // Known-empty workspace: set once a root listing reports
        // "empty"; while set, read_file is no longer offered (see
        // decideToolsForRound). Cleared by any not_empty result.
        var workspaceKnownEmpty = false

        /// Update the known-empty state from one tool outcome. Only a
        /// root "empty" sets it (a subdirectory being empty does not
        /// mean the root is); any "not_empty" clears it.
        func noteWorkspaceState(_ outcome: ToolOutcome) {
            if outcome.workspaceState == "empty" && (outcome.path ?? "").isEmpty {
                workspaceKnownEmpty = true
            } else if outcome.workspaceState == "not_empty" {
                workspaceKnownEmpty = false
            }
        }

        /// Normalise a workspace-relative path for "same target"
        /// judgement, with the same splitting rules as the
        /// registry's workspace resolution (trim, backslashes to
        /// slashes, drop empty and "." segments), so "README.txt",
        /// "./README.txt" and "/data/.../README.txt" count as the
        /// same target. Judgement key only, never path resolution
        /// (".." stays in the key; the resolution layer refuses it).
        func normalizeWorkspacePath(_ raw: String) -> String {
            raw.trimmingCharacters(in: .whitespaces)
                .replacingOccurrences(of: "\\", with: "/")
                .split(separator: "/", omittingEmptySubsequences: true)
                .filter { $0 != "." }
                .joined(separator: "/")
        }

        /// hand_not_found "same target" key: tool name + normalised
        /// path; without a path, the full call signature.
        func notFoundTargetKey(_ call: BrainToolCall) -> String {
            if let path = call.args["path"]?.stringValue {
                return "\(call.name)|hand_not_found|\(normalizeWorkspacePath(path))"
            }
            return toolCallKey(call)
        }

        /// For a failed read_file, find this turn's known listing of
        /// available files: the target directory's listing first,
        /// else the root listing (its "dir <subdir>" lines still hint
        /// at relative paths). Nil when nothing was listed this turn
        /// — never invent one.
        func listingForFailedRead(_ call: BrainToolCall) -> String? {
            let rawPath = call.args["path"]?.stringValue
            let parent: String
            if let rawPath, !Self.isBlank(rawPath) {
                parent = normalizeWorkspacePath(rawPath)
                    .split(separator: "/").dropLast().joined(separator: "/")
            } else {
                parent = ""
            }
            if let listing = knownListings[parent] { return listing }
            return knownListings[""]
        }

        /// Deterministic argument repair for the exact real-world
        /// failure shape: the model calls `read_file` with a missing
        /// path in the same round as (or right after) `list_files`.
        /// This repairs ONLY a missing/blank `path`, ONLY from a
        /// listing this turn already received (never from disk behind
        /// the tool layer, never by inventing a file). If the listing
        /// has exactly one file we use it; if it has several, we use
        /// a file the user explicitly named. Otherwise the call is
        /// left untouched and the invalid_action feedback applies.
        func repairMissingReadPath(_ call: BrainToolCall) -> BrainToolCall {
            if call.name != "read_file" { return call }
            let rawPath = call.args["path"]?.stringValue
            if let rawPath, !Self.isBlank(rawPath) { return call }
            var seenFiles: Set<String> = []
            var files: [String] = []
            for (dir, data) in knownListings {
                for line in data.split(whereSeparator: { $0.isNewline }) {
                    let trimmed = String(line).trimmingCharacters(in: .whitespaces)
                    guard trimmed.hasPrefix("file ") else { continue }
                    let name = String(trimmed.dropFirst("file ".count))
                        .trimmingCharacters(in: .whitespaces)
                    if name.isEmpty { continue }
                    let full = dir.isEmpty ? name : "\(dir)/\(name)"
                    if seenFiles.insert(full).inserted { files.append(full) }
                }
            }
            if files.isEmpty { return call }
            let mentionedRaw = Self.fileNamePattern.matches(
                in: userText, range: NSRange(userText.startIndex..., in: userText))
                .compactMap { Range($0.range, in: userText).map { String(userText[$0]) } }
                .filter { name in
                    files.contains { $0 == name || $0.split(separator: "/").last.map(String.init) == name }
                }
            var distinctMentioned: [String] = []
            for name in mentionedRaw where !distinctMentioned.contains(name) {
                distinctMentioned.append(name)
            }
            let candidate: String?
            if distinctMentioned.count == 1 {
                candidate = files.first {
                    $0 == distinctMentioned[0]
                        || $0.split(separator: "/").last.map(String.init) == distinctMentioned[0]
                }
            } else if distinctMentioned.isEmpty && files.count == 1 {
                candidate = files[0]
            } else {
                candidate = nil
            }
            guard let candidate else { return call }
            var args = call.args
            args["path"] = .string(candidate)
            let repaired = call.withArgs(args)
            log(
                conversation.id, "tool_arg_repaired",
                "read_file 缺少 path，已按本轮 list_files 结果补为 \(candidate)（调用 \(call.id)）")
            return repaired
        }

        /// Control-state stop handling. Returns true = this turn must
        /// stop immediately (no remaining call executes); only an
        /// Error event is emitted here — Done is emitted uniformly
        /// after the loop (doneReason is set here). PAUSED waits for
        /// the user's resume instruction and never auto-resumes the
        /// old turn; GOAL_CHANGED voids the old turn (its cache is
        /// not carried over).
        func stopForControl(_ state: TurnControlState) -> Bool {
            if state == .running { return false }
            let code: String
            let message: String
            let reason: String
            switch state {
            case .paused:
                code = "turn_paused"
                message = "已暂停：不再发新工具调用，等用户恢复指令。"
                reason = "paused"
            case .stopped:
                code = "turn_stopped"
                message = "已停止：剩余工具调用不再执行。"
                reason = "stopped"
            case .goalChanged:
                code = "goal_changed"
                message = "目标已变更：旧轮次作废，剩余工具调用不再执行。"
                reason = "goal_changed"
            case .running:
                return false
            }
            emitError(BrainErrorInfo(code: code, message: message, retryable: false))
            log(conversation.id, "error", message)
            doneReason = reason
            return true
        }

        /// Guard 1 convergence: on a repeated same-args write call,
        /// when text already exists return the text and stop (the
        /// same handling as guard 2); only with no text report
        /// tool_call_repeated.
        func stopForRepeatedWriteCall(_ call: BrainToolCall) {
            if !Self.isBlank(assistantText) {
                log(
                    conversation.id, "tool_call_repeated",
                    "模型重复调用了同一工具（\(call.name)，参数相同），已有文字，返回文字并停止")
                finished = true
            } else {
                let err = BrainErrorInfo(
                    code: "tool_call_repeated",
                    message: "模型重复调用了同一工具（\(call.name)，参数相同），已停止，避免空转。",
                    retryable: false)
                emitError(err)
                log(conversation.id, "error", err.message)
            }
        }

        /// Append ONE assistant message per round that requested
        /// tools, carrying the structured tool calls (ids included)
        /// before any tool result. A round with N calls must be one
        /// assistant message with N tool_calls followed by N tool
        /// messages, not N separate assistant/tool pairs
        /// (OpenAI-compatible APIs reject the latter and cannot pair
        /// the results).
        ///
        /// The readable JSON echo is kept in the message content for
        /// adapters that do not understand native tool_calls, while
        /// native adapters use the structured list. Both are
        /// persisted so a later turn can rebuild the pairing.
        func appendAssistantToolCalls(_ calls: [BrainToolCall]) {
            if calls.isEmpty { return }
            let structured = calls.map { call in
                ChatToolCall(
                    id: call.id,
                    name: call.name,
                    argumentsJson: JSONValue.stringify(call.args))
            }
            let echo: String
            if calls.count == 1, let call = calls.first {
                echo = JSONValue.stringify([
                    "id": .string(call.id),
                    "name": .string(call.name),
                    "arguments": .object(call.args),
                ])
            } else {
                let array = calls.map { call in
                    JSONValue.object([
                        "id": .string(call.id),
                        "name": .string(call.name),
                        "arguments": .object(call.args),
                    ]).anyObject
                }
                if let data = try? JSONSerialization.data(withJSONObject: array),
                   let text = String(data: data, encoding: .utf8) {
                    echo = text
                } else {
                    echo = "[]"
                }
            }
            messages.append(ChatMessage(
                role: "assistant", content: echo, toolCalls: structured))
            _ = try? conversations.addMessage(
                ConversationMessage(
                    id: newId(),
                    conversationId: conversation.id,
                    role: "assistant",
                    content: SecretRedactor.redact(echo, limit: 2000),
                    source: "tool-call:" + calls.map { $0.name }.joined(separator: ","),
                    createdAtEpochSeconds: nowSeconds(),
                    toolCalls: structured))
        }

        /// The official tool-result feedback (role=tool +
        /// tool_call_id), shared by real executions and cache hits.
        /// The assistant echo was appended once for the whole round
        /// by appendAssistantToolCalls. Returns true = guard 3 fired
        /// while feeding back; this turn must stop.
        func appendFeedback(
            _ call: BrainToolCall,
            _ outcome: ToolOutcome,
            fromCache: Bool,
            cachedAtRound: Int?
        ) -> Bool {
            var toolJson: [String: JSONValue] = [
                "tool": .string(call.name),
                "ok": .bool(outcome.ok),
                "summary": .string(outcome.summary),
            ]
            // Remember this turn's successful listings so a later
            // failed read can be answered with the available files.
            if call.name == "list_files" && outcome.ok, let data = outcome.data {
                knownListings[outcome.path ?? ""] = data
            }
            // Machine-readable errorCode (e.g. invalid_action), so
            // the model and the logs no longer identify failures by
            // the Chinese summary alone.
            if let errorCode = outcome.errorCode {
                toolJson["errorCode"] = .string(errorCode)
            }
            toolJson["data"] = outcome.data.map(JSONValue.string) ?? .null
            // A missing-argument / wrong-shape read_file feedback
            // carries this turn's known available-files listing
            // instead of only a machine code, so the model does not
            // have to guess a path. Unknown listing: not attached,
            // never invented.
            if call.name == "read_file" && !outcome.ok
                && (outcome.errorCode == "invalid_action" || outcome.errorCode == "hand_not_found"),
               let listing = listingForFailedRead(call) {
                toolJson["availableFiles"] = .string(String(listing.prefix(2000)))
            }
            if fromCache {
                toolJson["cached"] = .bool(true)
                if let cachedAtRound {
                    toolJson["cachedAtRound"] = .number(Double(cachedAtRound))
                }
            }
            if let state = outcome.workspaceState { toolJson["state"] = .string(state) }
            if let path = outcome.path { toolJson["path"] = .string(path) }
            if let label = outcome.workspaceLabel { toolJson["workspace"] = .string(label) }
            if let host = outcome.host { toolJson["host"] = .string(host) }
            let toolText = JSONValue.stringify(toolJson)
            messages.append(ChatMessage(
                role: "tool", content: toolText, toolCallId: call.id, name: call.name))
            _ = try? conversations.addMessage(
                ConversationMessage(
                    id: newId(),
                    conversationId: conversation.id,
                    role: "tool",
                    content: SecretRedactor.redact(toolText, limit: 2000),
                    source: "tool:\(call.name)",
                    createdAtEpochSeconds: nowSeconds(),
                    toolCallId: call.id))
            // Record recent failures for guard 2's ending summary
            // (last 4 only; failures and cache hits still do not
            // count as progress — the trigger arithmetic is
            // untouched).
            if !outcome.ok {
                recentFailures.append("\(call.name)(\(outcome.errorCode ?? "failed"))")
                while recentFailures.count > 4 { recentFailures.removeFirst() }
            }
            // Guard 3: a terminal failure is only fed back the first
            // time; the same (tool, category) appearing again stops
            // the turn.
            if !outcome.ok, let errorCode = outcome.errorCode,
               Self.terminalFailureCodes.contains(errorCode) {
                let failureKey = "\(call.name)|\(errorCode)"
                if !seenTerminalFailures.insert(failureKey).inserted {
                    let err = BrainErrorInfo(
                        code: "tool_repeated_failure",
                        message: "工具 \(call.name) 再次以同样原因失败（\(errorCode)），已停止，不再重试。",
                        retryable: false)
                    emitError(err)
                    log(conversation.id, "error", err.message)
                    return true
                }
            }
            // hand_not_found is NOT in the terminal set (the model
            // could retry forever with different absolute-path
            // shapes, converged only by guard 2); a second
            // hand_not_found against the same normalised target is
            // treated as a terminal failure, with guard 3's ending.
            // Different targets do not interfere: reading two
            // different missing files still gets one retry each.
            if !outcome.ok && outcome.errorCode == "hand_not_found" {
                let notFoundKey = notFoundTargetKey(call)
                if !seenNotFoundFailures.insert(notFoundKey).inserted {
                    let err = BrainErrorInfo(
                        code: "tool_repeated_failure",
                        message: "工具 \(call.name) 再次以同样原因失败（\(outcome.errorCode ?? "")），已停止，不再重试。",
                        retryable: false)
                    emitError(err)
                    log(conversation.id, "error", "\(err.message) 目标=\(notFoundKey)")
                    return true
                }
            }
            return false
        }

        outer: while !finished {
            // Tool-result-loss guard: the previous round had tool
            // calls, but this round's messages lack the matching tool
            // messages — never execute new calls; stop instead of
            // letting the model fabricate "already read..." text
            // without results.
            let missingResults = Self.findMissingToolResults(
                messages: messages, calls: lastPendingToolCalls)
            if !missingResults.isEmpty {
                let err = BrainErrorInfo(
                    code: "tool_result_missing",
                    message: "工具结果丢失（未找到对应的 tool 消息：\(missingResults.joined(separator: ","))），已停止。",
                    retryable: false)
                emitError(err)
                log(conversation.id, "error", err.message)
                break outer
            }
            // Loop-head control check (primary enforcement, one of
            // two).
            if stopForControl(controlStateProvider()) { break outer }
            // Round cap (by roundIndex) and total-duration cap are
            // checked at the loop head.
            if roundIndex >= maxToolRounds {
                let err = BrainErrorInfo(
                    code: "tool_round_limit",
                    message: "工具调用已达上限 \(maxToolRounds) 轮，本轮停止。",
                    retryable: false)
                emitError(err)
                log(conversation.id, "error", err.message)
                break outer
            }
            if maxTurnMillis > 0 && nowMillisProvider() - turnStartedAt >= maxTurnMillis {
                let err = BrainErrorInfo(
                    code: "turn_timeout",
                    message: "本轮已超过总时长上限 \(maxTurnMillis) 毫秒，已停止。",
                    retryable: false)
                emitError(err)
                log(conversation.id, "error", err.message)
                break outer
            }
            guard let config = adapters.configFor(brainId),
                  let adapter = adapters.get(config.adapterId) else {
                let err = BrainErrorInfo(
                    code: "brain_not_found",
                    message: "找不到大脑配置 \(brainId)：请检查大脑设置。",
                    retryable: false)
                emitError(err)
                log(conversation.id, "error", err.message)
                break outer
            }
            let budget = TurnBudget(
                roundIndex: roundIndex,
                toolCallsExecuted: toolCallsExecuted,
                maxToolRounds: maxToolRounds,
                maxToolCallsPerTurn: maxToolCallsPerTurn,
                elapsedMillis: nowMillisProvider() - turnStartedAt,
                maxTurnMillis: maxTurnMillis)
            let toolDefs = decideToolsForRound(
                roundIndex: roundIndex,
                actionOriented: actionOriented,
                capabilities: adapter.capabilities(),
                budget: budget,
                controlState: controlStateProvider(),
                workspaceKnownEmpty: workspaceKnownEmpty,
                toolsForCapabilities: { caps in toolsForTurn(caps, gitHostContext) })
            // Known-empty workspace: this round no longer offers
            // read_file.
            if workspaceKnownEmpty && !toolDefs.contains(where: { $0.name == "read_file" }) {
                log(conversation.id, "empty_workspace_no_read", "工作区已知为空，本轮不再提供 read_file")
            }
            let request = ChatRequest(
                messages: messagesForRound(adapter),
                model: config.model,
                tools: toolDefs)
            var roundError: BrainErrorInfo? = nil
            var sawToolCalls = false
            var pendingToolCalls: [BrainToolCall] = []
            do {
                for try await event in adapter.chatStream(request: request) {
                    switch event {
                    case .textDelta(let text):
                        assistantText += text
                        emit(.textDelta(SecretRedactor.redactStreamChunk(text)))
                    case .reasoningDelta:
                        emit(event)
                    case .toolCall(let call):
                        sawToolCalls = true
                        pendingToolCalls.append(call)
                        emit(event)
                    case .usage:
                        emit(event)
                    case .error(let err):
                        roundError = err
                    case .done:
                        break
                    case .toolResult:
                        emit(event)
                    }
                }
            } catch is CancellationError {
                // Cancellation is not a brain failure: propagate it
                // so the caller/UI can show an explicit cancelled
                // state instead of a fabricated error or a blank
                // bubble.
                throw CancellationError()
            } catch {
                roundError = BrainErrorInfo(
                    code: "brain_call_failed",
                    message: "大脑调用失败：\(error.localizedDescription)",
                    retryable: true)
            }
            if let err = roundError {
                log(conversation.id, "error", "\(err.code): \(err.message)")
                if Failover.shouldRetry(
                    error: err, attemptsSoFar: sameBrainAttempts, policy: failoverPolicy) {
                    sameBrainAttempts += 1
                    log(conversation.id, "failover_retry", "同大脑重试第 \(sameBrainAttempts) 次")
                    continue outer
                }
                triedBrains.insert(brainId)
                // Non-retryable errors report immediately (same rule
                // as FailoverRunner): no fallback switch on e.g. 401.
                // allowFailover=false turns (user-selected Agent)
                // never switch either: a retryable failure is reported
                // as-is, never silently moved to another brain /
                // another billing party.
                let next = (allowFailover && err.retryable)
                    ? Failover.nextFallback(
                        currentBrainId: brainId, policy: failoverPolicy, tried: triedBrains)
                    : nil
                if let next {
                    brainId = next
                    switched = true
                    sameBrainAttempts = 0
                    try? conversations.updateConversationBrain(
                        id: conversation.id, brainId: brainId)
                    log(conversation.id, "brain_switched", "失败后切换大脑为 \(brainId)")
                    continue outer
                }
                emitError(err)
                emit(.done(finishReason: "error"))
                return
            }
            sameBrainAttempts = 0
            if !sawToolCalls {
                // Tools present but unused: a normal text ending —
                // no error, no fabricated tool call; one event is
                // logged for capability grading.
                if !toolDefs.isEmpty {
                    log(conversation.id, "tools_unused", "tools 在场但模型本轮未调用工具")
                }
                finished = true
                break outer
            }
            // Guard 1 (adjusted, not removed): read/write split. A
            // read-only call with the same arguments is answered from
            // the cache in the execution loop below, not stopped
            // wholesale; a WRITE call repeated with the same
            // arguments stops immediately, unexecuted, unpersisted —
            // limited to calls whose previous run SUCCEEDED (see the
            // record point in the execution loop): a failed call may
            // be retried (the dev loop "fail -> fix -> rerun" must
            // rerun the same test command; failed retries are
            // converged by guards 2/3 and the budget). Allowlist:
            // anything not in the cacheable set is treated as a
            // write. When text already exists, return the text and
            // stop (guard 2's handling); only with no text report
            // tool_call_repeated.
            let repeatedWriteCall = pendingToolCalls.first {
                seenToolCalls.contains(toolCallKey($0)) && !isReadOnlyToolCall($0)
            }
            if let repeatedWriteCall {
                stopForRepeatedWriteCall(repeatedWriteCall)
                break outer
            }
            // Guard 2 (adjusted, not removed): three-dimensional
            // progress judgement (text length + new-signature count +
            // latest result hash), threshold still 2 rounds; checked
            // BEFORE execution — a no-progress round's calls are not
            // executed. noProgressRounds was computed after the
            // previous round's feedback (see loop tail).
            if noProgressRounds >= 2 {
                // The ending carries the recent-failure summary
                // (tool name + errorCode) so "model made no progress"
                // is distinguishable from "kept failing"; the trigger
                // arithmetic itself is untouched (failures and cache
                // hits still do not count as progress). With no
                // failure records the wording is exactly the old one.
                let failureSummary = recentFailures.suffix(3).joined(separator: "、")
                let failureSuffix = failureSummary.isEmpty
                    ? "" : "最近失败：\(failureSummary)。"
                if !Self.isBlank(assistantText) {
                    log(
                        conversation.id, "tool_no_progress",
                        "连续两轮无进展，返回已有文字并停止工具循环\(failureSuffix)")
                    finished = true
                    break outer
                }
                let err = BrainErrorInfo(
                    code: "tool_no_progress",
                    message: "大脑连续调用工具却没有给出文字回复，本轮停止。\(failureSuffix)",
                    retryable: false)
                emitError(err)
                log(conversation.id, "error", err.message)
                break outer
            }
            // Repair missing read_file paths from a listing this turn
            // already received, then append ONE assistant message
            // carrying every structured tool call (with ids) for the
            // round. Tool results are appended per call below with
            // matching tool_call_id values.
            let effectiveToolCalls = pendingToolCalls.map { repairMissingReadPath($0) }
            appendAssistantToolCalls(effectiveToolCalls)
            callLoop: for (callIndex, originalCall) in effectiveToolCalls.enumerated() {
                // Same-round list_files -> read_file: the pre-pass
                // above ran before the listing existed. Try the
                // repair again now that earlier calls in this round
                // may have populated knownListings. The assistant
                // echo keeps the original call arguments; the tool
                // result is paired by id and carries the resolved
                // path, so the model can see what was read.
                let call = repairMissingReadPath(originalCall)
                // Per-execution control check (primary enforcement,
                // two of two).
                if stopForControl(controlStateProvider()) { break outer }
                // Call-count budget check (before execution).
                if toolCallsExecuted >= maxToolCallsPerTurn {
                    let err = BrainErrorInfo(
                        code: "tool_call_budget_exceeded",
                        message: "工具调用次数已达上限 \(maxToolCallsPerTurn) 次，本轮停止。",
                        retryable: false)
                    emitError(err)
                    log(conversation.id, "error", err.message)
                    break outer
                }
                let key = toolCallKey(call)
                let cached = ledger.lookup(key: key, brainId: brainId)
                if let cached, isReadOnlyToolCall(call) {
                    // Read-only cache hit: no real execution, no
                    // budget spent, no re-approval; validateOnly
                    // re-checks permission first (authorization may
                    // have been revoked).
                    let recheck = tools.validateOnly(
                        BrainAction(
                            actionId: call.id,
                            tool: call.name,
                            args: call.args,
                            requiresApproval: false,
                            idempotencyKey: cached.idempotencyKey),
                        toolContext)
                    if let recheck {
                        emit(.toolResult(id: call.id, ok: recheck.ok, summary: recheck.summary))
                        log(
                            conversation.id, "cache_hit_denied",
                            "\(call.name) 缓存命中但重检拒绝：\(recheck.errorCode ?? "null")")
                        noteWorkspaceState(recheck)
                        if appendFeedback(call, recheck, fromCache: false, cachedAtRound: nil) {
                            break outer
                        }
                    } else {
                        let summary = cached.outcome.summary + "（缓存结果，未重新执行）"
                        emit(.toolResult(id: call.id, ok: cached.outcome.ok, summary: summary))
                        log(
                            conversation.id, "tool_result_cached",
                            "\(call.name) key=\(key) 原轮次=\(cached.roundIndex)")
                        noteWorkspaceState(cached.outcome)
                        if appendFeedback(
                            call, cached.outcome, fromCache: true,
                            cachedAtRound: cached.roundIndex) {
                            break outer
                        }
                    }
                    continue callLoop
                }
                // Two identical writes in the same round: the
                // pre-round guard 1 checks once; block again here so
                // a duplicate write inside pendingToolCalls cannot
                // land twice in one round. Same handling: text first,
                // then stop; error only when there is no text.
                if seenToolCalls.contains(key) && !isReadOnlyToolCall(call) {
                    stopForRepeatedWriteCall(call)
                    break outer
                }
                let idempotencyKey = PlanValidator.idempotencyKey(
                    taskId: conversation.id, seq: toolCallsExecuted)
                let action = BrainAction(
                    actionId: call.id,
                    tool: call.name,
                    args: call.args,
                    requiresApproval: false,
                    idempotencyKey: idempotencyKey)
                let outcome: ToolOutcome
                do {
                    // The registry-level second stop gate (defence in
                    // depth; does not rely on the engine).
                    outcome = try await tools.execute(action, toolContext, stopped: {
                        let state = self.controlStateProvider()
                        return state == .stopped || state == .goalChanged
                    })
                } catch is CancellationError {
                    throw CancellationError()
                } catch {
                    // A tool throwing must still produce a clear
                    // result for the UI and the model; it must not
                    // leave the turn blank.
                    outcome = ToolOutcome(
                        ok: false,
                        summary: "工具执行异常：\(error.localizedDescription)",
                        errorCode: "tool_failed")
                }
                toolCallsExecuted += 1
                // Only successfully executed calls enter guard 1's
                // repeat set — a failed call may be retried (see the
                // pre-round guard 1 note).
                if outcome.ok { seenToolCalls.insert(key) }
                emit(.toolResult(id: call.id, ok: outcome.ok, summary: outcome.summary))
                log(conversation.id, "tool_result", "\(call.name) ok=\(outcome.ok) \(outcome.summary)")
                // The outcome's workspace state updates the
                // known-empty state.
                noteWorkspaceState(outcome)
                // Failures are not cached (a failure may be
                // transient; caching it would freeze a one-off
                // failure into a permanent one).
                if outcome.ok {
                    ledger.record(
                        ToolCallRecord(
                            key: key,
                            name: call.name,
                            args: call.args,
                            roundIndex: roundIndex,
                            outcome: outcome,
                            fromCache: false,
                            resultHash: resultHash(call.name, outcome),
                            executedAtEpochSeconds: nowSeconds(),
                            brainId: brainId,
                            idempotencyKey: idempotencyKey))
                }
                // Official feedback shape (assistant tool-call echo
                // first, then the role=tool result JSON), in that
                // order; both enter this turn's messages and the
                // stored history (sources "tool-call:<name>" and
                // "tool:<name>"). The chat UI renders streamed
                // bubbles and never replays stored history, so the
                // echo JSON never appears in the chat UI.
                if appendFeedback(call, outcome, fromCache: false, cachedAtRound: nil) {
                    break outer
                }
                // ask_user ending semantics: a successful question
                // ends the turn — the question is handed to the user
                // as text, doneReason = "awaiting_user", the model is
                // not called again. ask_user has no wait semantics of
                // its own (it returns ok synchronously and the loop
                // would immediately re-ask the model — exactly how a
                // real device run repeated the same call into guard
                // 1). A question is not an idempotent read and never
                // enters the read-only cache allowlist; a FAILED
                // ask_user (ok=false) does not end the turn — it is
                // fed back for a retry as usual.
                if call.name == "ask_user" && outcome.ok {
                    let question = outcome.data ?? outcome.summary
                    assistantText += question
                    emit(.textDelta(question))
                    log(
                        conversation.id, "ask_user_awaiting",
                        SecretRedactor.redact(question, limit: 500))
                    doneReason = "awaiting_user"
                    finished = true
                    break outer
                }
                // ask_user missing its question ends the turn at
                // once: the question itself is broken (structure
                // validation invalid_action), feedback-and-retry is
                // meaningless and only burns a round re-asking. End
                // with a text explanation, doneReason =
                // "ask_user_invalid". Only for the missing-argument
                // case; other failures take the normal
                // feedback-retry path.
                if call.name == "ask_user" && !outcome.ok
                    && outcome.errorCode == "invalid_action" {
                    let failureText = "提问失败：缺少问题内容（\(outcome.summary)），请直接用文字回答。"
                    assistantText += failureText
                    emit(.textDelta(failureText))
                    log(
                        conversation.id, "ask_user_invalid",
                        SecretRedactor.redact(failureText, limit: 500))
                    doneReason = "ask_user_invalid"
                    finished = true
                    break outer
                }
                // Post-execution budget re-check (prevents overshoot
                // when one round carries several calls).
                if toolCallsExecuted >= maxToolCallsPerTurn
                    && callIndex < effectiveToolCalls.count - 1 {
                    let err = BrainErrorInfo(
                        code: "tool_call_budget_exceeded",
                        message: "工具调用次数已达上限 \(maxToolCallsPerTurn) 次，本轮停止。",
                        retryable: false)
                    emitError(err)
                    log(conversation.id, "error", err.message)
                    break outer
                }
            }
            lastPendingToolCalls = effectiveToolCalls
            // Guard 2's three-dimensional progress signature
            // (computed once after feedback, before roundIndex += 1):
            // text length + distinct-signature count + latest result
            // hash; identical to the previous round -> +1, else
            // reset.
            let progressSignature =
                "\(assistantText.count)|\(ledger.distinctKeyCount)|\(ledger.lastResultHash)"
            if let last = lastProgressSignature, last == progressSignature {
                noProgressRounds += 1
            } else {
                noProgressRounds = 0
            }
            lastProgressSignature = progressSignature
            roundIndex += 1
        }

        let finalText = assistantText
        // Blank-bubble fix: a turn that produced no user-visible text
        // is never a silent success. The UI must show a concrete
        // error instead of leaving the streamed bubble empty.
        if Self.isBlank(finalText) {
            if terminalErrorEmitted {
                // A specific terminal error (control pause/stop,
                // guard, API failure) was already emitted; do not
                // replace it with the generic empty-response error.
                emit(.done(finishReason: doneReason ?? "error"))
                return
            }
            let toolNote = toolCallsExecuted == 0
                ? "也没有执行任何工具调用"
                : "工具已执行 \(toolCallsExecuted) 次，但没有文字结论"
            let err = BrainErrorInfo(
                code: "empty_response",
                message: "大脑没有返回可显示的文字回复（空响应）\(toolNote)。请重试；若持续出现，请检查 DeepSeek 配置与网络。",
                retryable: true)
            emitError(err)
            log(conversation.id, "error", err.message)
            emit(.done(finishReason: "error"))
            return
        }
        _ = try? conversations.addMessage(
            ConversationMessage(
                id: newId(),
                conversationId: conversation.id,
                role: "assistant",
                content: SecretRedactor.redact(finalText, limit: 12000),
                source: brainId,
                createdAtEpochSeconds: nowSeconds()))
        log(
            conversation.id, "turn_done",
            "brain=\(brainId) tools=\(toolCallsExecuted) switched=\(switched)")
        emit(.done(finishReason: doneReason ?? (
            terminalErrorEmitted ? "error" : (switched ? "switched_brain" : "stop"))))
    }

    // MARK: - Helpers

    /// Web search (only called when the toggle is on): returns the
    /// system-message body for this turn's context — found, not
    /// found, and failed each get an honest sentence, so the model
    /// never believes it searched when it did not. A throwing search
    /// never takes the whole turn down.
    private func runWebSearch(conversationId: String, query: String) async -> String {
        switch await webSearch.search(query: query) {
        case .failure(let error):
            let reason = error.message
            log(
                conversationId, "web_search_failed",
                SecretRedactor.redact(reason, limit: 200))
            return "联网搜索失败（\(reason)）：本次回答没有使用搜索结果，请按已有知识回答，并说明你的知识可能不是最新的。"
        case .success(let found):
            if found.hits.isEmpty {
                return "联网搜索（\(found.source)）没有找到与“\(found.query)”相关的结果：本次回答没有使用搜索结果，请按已有知识回答，并说明你的知识可能不是最新的。"
            }
            return WebSearchClient.formatForModel(found)
        }
    }

    /// This turn's tools = local tools (permission-filtered) + the
    /// current git home's tools (absent without a git home — the
    /// model never sees them). Honest boundary: when the adapter
    /// does not support tool calling, neither local nor git-home
    /// tools are listed — listing them would only prime the model to
    /// "act" with tools it cannot call (the first-round context's
    /// tool reminder and the request's tool definitions must agree).
    private func toolsForTurn(
        _ capabilities: BrainCapabilities?,
        _ gitHostContext: (project: Project, location: ProjectLocation)?
    ) -> [ToolDef] {
        if let capabilities, !capabilities.toolCalling { return [] }
        let local = tools.toolsForCapabilities(
            capabilities, authorizedTools: tools.allToolNames())
        let gitHost = tools.gitHostToolsFor(
            project: gitHostContext?.project, location: gitHostContext?.location)
        return local + gitHost
    }

    /// Decide this round's tool definitions. Tools stop being offered
    /// in exactly four situations, judged in order: control state not
    /// running, budget exhausted, first round not action-oriented,
    /// no tool capability. "Tools were already executed" is NOT a
    /// reason to stop offering them (multi-step chains converge via
    /// the cache + progress judgement, not via absent tools); the
    /// first-round routing is kept.
    ///
    /// When the workspace root is known empty, read_file is no
    /// longer offered as an option — there is no file to read, so
    /// read_file would inevitably miss its argument or invent a
    /// path. Other tools (list_files, ask_user, ...) are unaffected.
    private func decideToolsForRound(
        roundIndex: Int,
        actionOriented: Bool,
        capabilities: BrainCapabilities?,
        budget: TurnBudget,
        controlState: TurnControlState,
        workspaceKnownEmpty: Bool,
        toolsForCapabilities: (BrainCapabilities?) -> [ToolDef]
    ) -> [ToolDef] {
        if controlState != .running { return [] }
        if budget.isExhausted() { return [] }
        if roundIndex == 0 && !actionOriented { return [] }
        guard let capabilities, capabilities.toolCalling else { return [] }
        let defs = toolsForCapabilities(capabilities)
        if !workspaceKnownEmpty { return defs }
        return defs.filter { $0.name != "read_file" }
    }

    /// Cacheable read-only calls (allowlist): list_files / read_file,
    /// run_command whose argv[0] is read-only, and plugin_/mcp_
    /// extension tools whose source reports read-only. Anything not
    /// in the allowlist is treated as a write (write_file,
    /// dispatch_task, non-read-only run_command, ask_user).
    private func isReadOnlyToolCall(_ call: BrainToolCall) -> Bool {
        switch call.name {
        case "list_files", "read_file":
            return true
        default:
            if call.name.hasPrefix("plugin_") || call.name.hasPrefix("mcp_") {
                // Extension tools: the read-only judgement is asked
                // of the source; unknown sources are writes.
                return tools.isReadOnlyExtensionTool(call.name)
            }
            if call.name == "run_command" {
                var parsed: [String]? = nil
                if case .array(let items)? = call.args["argv"] {
                    let strings = items.compactMap { $0.stringValue }
                    parsed = strings.isEmpty ? nil : strings
                } else if case .string(let raw)? = call.args["argv"],
                          let data = raw.data(using: .utf8),
                          let array = (try? JSONSerialization.jsonObject(with: data)) as? [Any] {
                    let strings = array
                        .map { ($0 as? String) ?? "" }
                        .filter { !$0.isEmpty }
                    parsed = strings.isEmpty ? nil : strings
                }
                return ToolRegistryRules.isReadOnlyRun(parsed)
            }
            return false
        }
    }

    /// Result hash (progress judgement and "same call, changed
    /// result" detection). Only summary + the first 2000 chars of
    /// data; write calls use summary + errorCode, so file contents
    /// never become hash input. (Android uses Java's String.hashCode;
    /// the value only needs within-turn stability, so a deterministic
    /// djb2 is used here.)
    private func resultHash(_ toolName: String, _ outcome: ToolOutcome) -> String {
        let raw: String
        if toolName == "write_file" || toolName == "dispatch_task" {
            raw = "\(outcome.summary)|\(outcome.errorCode ?? "")"
        } else {
            raw = "\(outcome.summary)|\(outcome.data.map { String($0.prefix(2000)) } ?? "")"
        }
        var hash: UInt64 = 5381
        for byte in raw.utf8 {
            hash = (hash &* 33) &+ UInt64(byte)
        }
        return String(hash, radix: 16)
    }

    /// Guard 1's call signature: tool name + sorted arguments. The
    /// same signature repeated = stop.
    ///
    /// Normalisation: string values are trimmed first; path-like
    /// arguments ("path", "cwd") whose value is blank or "." mean
    /// the default workspace root and are dropped from the signature
    /// — an omitted path, "" and "." are the same call and must not
    /// bypass guard 1. Other arguments keep their value (trimmed
    /// only, no whitespace normalisation).
    private func toolCallKey(_ call: BrainToolCall) -> String {
        let args = call.args.entriesSorted()
            .filter { key, value in
                // Blank normalisation applies only to string path-like
                // arguments; non-string values are kept as-is.
                guard (key == "path" || key == "cwd"),
                      case .string(let raw) = value else { return true }
                let trimmed = raw.trimmingCharacters(in: .whitespaces)
                return !(trimmed.isEmpty || trimmed == ".")
            }
            .map { key, value in
                let text: String
                if case .string(let raw) = value {
                    text = raw.trimmingCharacters(in: .whitespaces)
                } else {
                    text = value.keyString
                }
                return "\(key)=\(text)"
            }
            .joined(separator: ",")
        return "\(call.name)|\(args)"
    }

    private func log(_ conversationId: String, _ kind: String, _ detail: String) {
        eventLog.record(
            EngineEvent(
                seq: 0,
                conversationId: conversationId,
                kind: kind,
                detail: SecretRedactor.redact(detail, limit: 2000),
                epochSeconds: nowSeconds()))
    }

    /// System clock: as on Android, message timestamps do NOT use the
    /// injected turn clock (that clock exists for timeout tests).
    private func nowSeconds() -> Int64 {
        Int64(Date().timeIntervalSince1970)
    }

    private func newId() -> String {
        UUID().uuidString.lowercased()
    }
}

// MARK: - Companion (system prompt, guards' tables, heuristics)

extension BrainEngine {
    /// Tool-result-loss detection: which of the previous round's
    /// tool calls have no matching role=tool message (paired by
    /// toolCallId) in this round's request messages. Empty = feedback
    /// complete. Used by the engine's loop-head guard and directly by
    /// tests.
    public static func findMissingToolResults(
        messages: [ChatMessage],
        calls: [BrainToolCall]
    ) -> [String] {
        if calls.isEmpty { return [] }
        let present = Set(
            messages.filter { $0.role == "tool" && $0.toolCallId != nil }
                .map { $0.toolCallId! })
        return calls.filter { !present.contains($0.id) }.map { $0.id }
    }

    public static var systemPrompt: String {
        "你是 WhereverCode 超级 Agent 的大脑。能直接回答的问题，直接用文字回答，" +
            "不要调用工具；只有需要读文件、跑命令、派任务时才调用工具。" +
            "你不直接改文件；所有行动由程序验证后执行。" +
            "不知道就说不知道，不要编造。" +
            "读文件前须先列出目录：read_file 的 path 只能取自已返回的 list_files 结果，" +
            "不得与 list_files 同一轮发出；工作区已确认为空时直接用文字回答，不要再调 read_file。" +
            "做开发任务时按此循环：先 list_files 看现状；用 write_file 每次写一个文件；" +
            "用 run_command 的 [\"python3\", \"--version\"] 检查真实 Python 环境；" +
            "可用就运行真实测试并读取退出码和输出，失败按输出修复，最多重试三轮；" +
            "不可用就如实报告能力缺口。绝不声称执行了没有实际执行的命令，" +
            "也不编造测试结果或退出码。"
    }

    /// Terminal tool-failure categories: retrying the same call is
    /// meaningless when the failure is not about the argument or the
    /// permission itself. The first occurrence is only fed back to
    /// the model; the same (tool, category) appearing again stops the
    /// turn. Other failures (file missing, execution failed, ...) may
    /// be retried once with different arguments, with
    /// tool_call_repeated as the backstop for exact repeats.
    public static var terminalFailureCodes: Set<String> {
        [
            "invalid_action", "invalid_argument", "path_not_allowed", "user_denied",
            // Authorization voided by changed arguments / a destroyed
            // UI: retrying the same call is meaningless (either run
            // the full approval again, or wait for the user to return
            // to the screen).
            "approval_action_changed", "approval_revoked",
        ]
    }

    /// First-round routing: is this turn's user message
    /// action-oriented? Greetings / small talk ("在吗", "你好") are
    /// not — the first round carries no tool definitions and the
    /// model answers in text; mentions of reading/writing/running/
    /// checking/dispatching or files/commands are. This is a
    /// heuristic split, not semantic understanding: on a miss the
    /// model can still answer in text and the user rephrases.
    ///
    /// "任务" is deliberately NOT an action word: as an object it is
    /// usually a question topic ("能帮我完成什么任务？"), not an
    /// instruction. Dispatch is triggered by explicit verbs instead
    /// ("派" / "交给" / "新建", dispatch/assign). The split must stay
    /// conservative — a miss costs a text answer, a false positive
    /// puts tools in front of a small model that will then call them.
    public static var actionWords: [String] {
        [
            "读", "写", "改", "删", "创建", "新建", "列出", "运行", "执行",
            "派", "交给", "打开", "下载", "上传", "部署", "测试", "编译", "构建",
            "检查", "复制", "移动", "安装", "提交", "搜索", "查找", "查看",
            "文件", "文件夹", "目录", "代码", "命令", "日志",
        ]
    }

    public static var actionWordsEn: [NSRegularExpression] {
        [
            "read", "write", "edit", "delete", "create", "list", "run",
            "execute", "dispatch", "assign", "check", "find", "search", "open",
            "build", "test", "deploy", "show", "file", "command",
            "code", "cat", "ls",
        ].map { try! NSRegularExpression(pattern: "\\b\($0)\\b", options: [.caseInsensitive]) }
    }

    /// File names the user explicitly wrote ("读取 README.txt"). Used
    /// only to choose among files a list_files result already
    /// returned this turn; never used to invent a path when no
    /// listing exists.
    public static var fileNamePattern: NSRegularExpression {
        try! NSRegularExpression(
            pattern: "[\\p{L}\\p{N}_.\\-]+\\.(?:txt|md|markdown|json|kt|kts|java|py|js|ts|tsx|jsx|css|html|xml|ya?ml|csv|log|sh|gradle|properties|toml|ini|cfg|conf)",
            options: [.caseInsensitive])
    }

    public static func looksActionOriented(_ text: String) -> Bool {
        if actionWords.contains(where: { text.contains($0) }) { return true }
        return actionWordsEn.contains {
            $0.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) != nil
        }
    }

    static func isBlank(_ value: String?) -> Bool {
        guard let value else { return true }
        return value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}

private extension Dictionary where Key == String, Value == JSONValue {
    /// Dictionary entries sorted by key (the engine's signature
    /// normalisation iterates arguments in key order).
    func entriesSorted() -> [(key: String, value: JSONValue)] {
        keys.sorted().map { ($0, self[$0]!) }
    }
}
