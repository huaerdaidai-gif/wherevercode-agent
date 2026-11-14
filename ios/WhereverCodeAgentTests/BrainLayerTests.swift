import XCTest
@testable import WhereverCodeAgent

/// B-group brain layer tests: config validation + persistence,
/// display name, context assembly, failover, redaction, memory,
/// planner, and the engine's turn machinery against fakes (the same
/// injection shape the Android engine tests use).
final class BrainLayerTests: XCTestCase {

    // MARK: - Fakes

    final class FakeBrainAdapter: BrainAdapter {
        let adapterId: String
        let kind: BrainKind = .modelApi
        var label: String { adapterId }
        var caps: BrainCapabilities
        var handler: (ChatRequest, Int) -> [BrainEvent]
        private(set) var requests: [ChatRequest] = []

        init(
            adapterId: String,
            caps: BrainCapabilities = BrainCapabilities(streaming: true, toolCalling: true),
            handler: @escaping (ChatRequest, Int) -> [BrainEvent]
        ) {
            self.adapterId = adapterId
            self.caps = caps
            self.handler = handler
        }

        func capabilities() -> BrainCapabilities { caps }
        func validateCredentials() async -> Result<Void, Error> { .success(()) }
        func listModels() async -> Result<[ModelInfo], Error> { .success([]) }
        func chatStream(request: ChatRequest) -> AsyncThrowingStream<BrainEvent, Error> {
            requests.append(request)
            let events = handler(request, requests.count)
            return AsyncThrowingStream { continuation in
                for event in events { continuation.yield(event) }
                continuation.finish()
            }
        }
    }

    final class FakeToolRegistry: ToolRegistry {
        var workspaceRoot: String? = "/tmp/ws"
        var defs: [ToolDef]
        var executeHandler: (BrainAction) -> ToolOutcome
        var validateOnlyResult: ToolOutcome? = nil
        private(set) var executed: [BrainAction] = []

        init(defs: [ToolDef], executeHandler: @escaping (BrainAction) -> ToolOutcome) {
            self.defs = defs
            self.executeHandler = executeHandler
        }

        func toolsForCapabilities(
            _ capabilities: BrainCapabilities?, authorizedTools: Set<String>
        ) -> [ToolDef] { defs }
        func gitHostToolsFor(project: Project?, location: ProjectLocation?) -> [ToolDef] { [] }
        func allToolNames() -> Set<String> { Set(defs.map { $0.name }) }
        func validateOnly(_ action: BrainAction, _ context: ToolContext) -> ToolOutcome? {
            validateOnlyResult
        }
        func execute(
            _ action: BrainAction, _ context: ToolContext, stopped: @escaping () -> Bool
        ) async throws -> ToolOutcome {
            executed.append(action)
            return executeHandler(action)
        }
        func isReadOnlyExtensionTool(_ name: String) -> Bool { false }
    }

    struct ControlLedger: SessionLedger {
        func record(_ input: IncomingMessage) -> LedgerResult {
            LedgerResult(kind: .control, ok: true, summary: "已暂停任务")
        }
    }

    private func collect(
        _ stream: AsyncThrowingStream<BrainEvent, Error>
    ) async throws -> [BrainEvent] {
        var events: [BrainEvent] = []
        for try await event in stream { events.append(event) }
        return events
    }

    private func makeEngine(
        registry: BrainAdapterRegistry,
        store: ConversationStore,
        tools: ToolRegistry,
        currentBrainId: @escaping () -> String?,
        failoverPolicy: FailoverPolicy = FailoverPolicy(),
        sessionLedger: SessionLedger = NoOpSessionLedger()
    ) -> BrainEngine {
        BrainEngine(
            adapters: registry,
            conversations: store,
            contextManager: ContextManager(),
            memory: InMemoryMemoryStore(),
            tools: tools,
            eventLog: InMemoryEventLog(),
            approvalGate: DenyApprovalGate(),
            currentBrainId: currentBrainId,
            failoverPolicy: failoverPolicy,
            sessionLedger: sessionLedger)
    }

    private func registerBrain(
        _ registry: BrainAdapterRegistry,
        _ adapter: FakeBrainAdapter,
        brainId: String,
        fallbackBrainIds: [String] = []
    ) {
        registry.registerAdapter(adapter)
        registry.registerConfig(BrainConfig(
            brainId: brainId, kind: .modelApi, adapterId: adapter.adapterId,
            label: brainId, baseUrl: "https://example.com/v1", model: "m1",
            authRef: nil, fallbackBrainIds: fallbackBrainIds))
    }

    private func errorCodes(_ events: [BrainEvent]) -> [String] {
        events.compactMap { event in
            if case .error(let info) = event { return info.code }
            return nil
        }
    }

    private func doneReasons(_ events: [BrainEvent]) -> [String] {
        events.compactMap { event in
            if case .done(let reason) = event { return reason }
            return nil
        }
    }

    // MARK: - BrainConfigValidator

    private func config(
        kind: BrainKind = .modelApi,
        adapterId: String = "generic-openai",
        baseUrl: String? = "https://api.example.com/v1",
        model: String = "m1",
        authRef: String? = "key:test-1",
        extra: [String: String] = [:],
        fallbackBrainIds: [String] = [],
        cliBinary: String? = nil
    ) -> BrainConfig {
        BrainConfig(
            brainId: "b1", kind: kind, adapterId: adapterId, label: "测试脑",
            baseUrl: baseUrl, model: model, authRef: authRef, extra: extra,
            fallbackBrainIds: fallbackBrainIds, cliBinary: cliBinary)
    }

    func testValidatorAcceptsPlainModelApi() {
        XCTAssertEqual(BrainConfigValidator.validate(config()), [])
    }

    func testValidatorRejectsBlankModelAndHttpUrl() {
        let errors = BrainConfigValidator.validate(
            config(baseUrl: "http://api.example.com", model: ""))
        XCTAssertTrue(errors.contains("模型不能为空"))
        XCTAssertTrue(errors.contains { $0.hasPrefix("服务地址只允许 https") })
    }

    func testValidatorAllowsLocalhostModelUrl() {
        let errors = BrainConfigValidator.validate(
            config(baseUrl: "http://localhost:8080/v1"))
        XCTAssertFalse(errors.contains { $0.hasPrefix("服务地址只允许 https") })
        XCTAssertTrue(BrainConfigValidator.isLocalModelUrl("http://127.0.0.1:9000"))
        XCTAssertFalse(BrainConfigValidator.isAllowedBaseUrl("http://localhost.evil.com"))
    }

    func testValidatorSubscriptionRules() {
        // Subscription borrows a CLI login: no baseUrl, no authRef,
        // cliBinary required.
        let bad = BrainConfigValidator.validate(config(
            kind: .subscription, baseUrl: "https://x.example.com", authRef: "key:a"))
        XCTAssertTrue(bad.contains { $0.hasPrefix("订阅授权没有服务地址") })
        XCTAssertTrue(bad.contains { $0.hasPrefix("订阅授权没有密钥输入框") })
        XCTAssertTrue(bad.contains { $0.hasPrefix("订阅授权（非 ChatGPT 登录）") })
        let good = BrainConfigValidator.validate(config(
            kind: .subscription, baseUrl: nil, authRef: nil, cliBinary: "claude"))
        XCTAssertEqual(good, [])
    }

    func testValidatorSiwcAndDeviceCodeAndLoginOnlyExceptions() {
        // SIWC: authRef allowed, blank model allowed, no cliBinary.
        XCTAssertEqual(BrainConfigValidator.validate(config(
            kind: .subscription, adapterId: "siwc-chatgpt",
            baseUrl: nil, model: "", authRef: "siwc:tokens")), [])
        // Device-code membership: authRef + https inference URL,
        // blank model allowed.
        XCTAssertEqual(BrainConfigValidator.validate(config(
            kind: .subscription, adapterId: "devicecode-qwen",
            baseUrl: "https://example.com/v1", model: "", authRef: "devicecode:qwen")), [])
        let badUrl = BrainConfigValidator.validate(config(
            kind: .subscription, adapterId: "devicecode-qwen",
            baseUrl: "http://example.com/v1", model: "", authRef: "devicecode:qwen"))
        XCTAssertTrue(badUrl.contains { $0.hasPrefix("设备码会员大脑的推理地址只允许 https") })
        // Login-only: an empty login entry is the only valid shape.
        XCTAssertEqual(BrainConfigValidator.validate(config(
            kind: .subscription, baseUrl: nil, model: "", authRef: nil,
            extra: ["login_only": "true"])), [])
    }

    func testValidatorRejectsSecretsAndSelfFallback() {
        let errors = BrainConfigValidator.validate(config(
            authRef: "sk-abcdefghijklmnop",
            extra: ["api_key": "whatever"],
            fallbackBrainIds: ["b1"]))
        XCTAssertTrue(errors.contains("fallback 列表不能包含自己"))
        XCTAssertTrue(errors.contains("authRef 只能是引用名，不能是密钥本身"))
        XCTAssertTrue(errors.contains("extra 里不能放密钥（字段 api_key）"))
        // Export strips secret-shaped extra keys and never carries a
        // secret value.
        let exported = BrainConfigValidator.exportConfig(config(
            authRef: "key:test-1", extra: ["api_key": "x", "theme": "dark"]))
        if case .object(let extra) = exported["extra"] {
            XCTAssertNil(extra["api_key"])
            XCTAssertEqual(extra["theme"], .string("dark"))
        } else {
            XCTFail("extra missing from export")
        }
        XCTAssertEqual(exported["authRef"], .string("key:test-1"))
    }

    // MARK: - BrainConfigStore + display name (over the real kv)

    func testConfigStoreRoundTripAndTombstones() throws {
        let data = try LocalData.inMemory()
        let store = KeyValueBrainConfigStore(kv: data.kv)
        var cfg = config()
        cfg.loginState = .loggedIn
        cfg.cliBinary = "claude"
        cfg.extra = ["k": "v"]
        cfg.fallbackBrainIds = ["b2"]
        store.saveConfig(cfg)
        store.saveConfig(BrainConfig(
            brainId: "b2", kind: .modelApi, adapterId: "generic-openai",
            label: "二号", baseUrl: "https://example.com", model: "m2", authRef: nil))
        XCTAssertEqual(store.listConfigs().count, 2)
        let read = try XCTUnwrap(store.getConfig(brainId: "b1"))
        XCTAssertEqual(read, cfg)
        store.setCurrentBrainId("b1")
        XCTAssertEqual(store.currentBrainId(), "b1")
        // A second store over the same kv sees the same rows
        // (persistence, not memory).
        let store2 = KeyValueBrainConfigStore(kv: data.kv)
        XCTAssertEqual(store2.getConfig(brainId: "b1"), cfg)
        XCTAssertEqual(store2.currentBrainId(), "b1")
        // Deleting the current brain clears the current id.
        store.deleteConfig(brainId: "b1")
        XCTAssertNil(store.currentBrainId())
        XCTAssertEqual(store.listConfigs().count, 1)
        // Tombstones persist and clear.
        store.markBrainDeleted(brainId: "seed-1")
        XCTAssertEqual(store2.deletedBrainIds(), ["seed-1"])
        store.clearBrainDeleted(brainId: "seed-1")
        XCTAssertEqual(store.deletedBrainIds(), [])
    }

    func testBrainDisplayName() throws {
        XCTAssertEqual(BrainDisplayName.resolve(nil), "WhereverCode")
        XCTAssertEqual(BrainDisplayName.resolve("   "), "WhereverCode")
        XCTAssertEqual(BrainDisplayName.resolve(" 我的脑 "), "我的脑")
        XCTAssertEqual(BrainDisplayName.sessionLabel(nil), "WhereverCode会话")
        let data = try LocalData.inMemory()
        let names = BrainDisplayName(kv: data.kv)
        XCTAssertEqual(names.load(), "WhereverCode")
        names.save("  指挥官 ")
        XCTAssertEqual(names.load(), "指挥官")
        names.save("   ")
        XCTAssertEqual(names.load(), "WhereverCode")
    }

    // MARK: - SecretRedactor

    func testRedactor() {
        let redacted = SecretRedactor.redact("key is sk-abcdefghijklmnop ok")
        XCTAssertTrue(redacted.contains("[REDACTED]"))
        XCTAssertFalse(redacted.contains("sk-abcdefghijklmnop"))
        let bearer = SecretRedactor.redact("Authorization: Bearer abcdefgh123456")
        XCTAssertFalse(bearer.contains("abcdefgh123456"))
        let json = SecretRedactor.redact("{\"api_key\": \"supersecretvalue\"}")
        XCTAssertTrue(json.contains("[REDACTED]"))
        XCTAssertFalse(json.contains("supersecretvalue"))
        // Limit keeps the LAST chars (event-text semantics).
        XCTAssertEqual(
            SecretRedactor.redact(String(repeating: "a", count: 3000), limit: 100).count, 100)
        // Streaming: an open (incomplete) credential tail is masked.
        let chunk = SecretRedactor.redactStreamChunk("token: \"sk-abc")
        XCTAssertFalse(chunk.contains("sk-abc"))
        XCTAssertFalse(SecretRedactor.containsSecret("hello world"))
        XCTAssertTrue(SecretRedactor.containsSecret("sk-abcdefghijklmnop"))
    }

    // MARK: - MemoryStore

    func testMemoryStoreWriteRules() {
        let store = InMemoryMemoryStore()
        let fact = MemoryEntry(
            id: "1", kind: .fact, key: "lang", value: "中文", source: "user",
            createdAtEpochSeconds: 1, confirmedByUser: false)
        XCTAssertFalse(store.addFact(entry: fact, confirmedByUser: false))
        XCTAssertTrue(store.addFact(entry: fact, confirmedByUser: true))
        XCTAssertEqual(store.summarize(), "- [FACT] lang：中文（来源：user）")
        // Secret-shaped values are refused outright.
        let secret = MemoryEntry(
            id: "2", kind: .fact, key: "k", value: "sk-abcdefghijklmnop", source: "user",
            createdAtEpochSeconds: 1, confirmedByUser: true)
        XCTAssertFalse(store.addFact(entry: secret, confirmedByUser: true))
        // Task summaries require settlement.
        XCTAssertFalse(store.addTaskSummary(taskId: "t1", summary: "做完了", settled: false))
        XCTAssertTrue(store.addTaskSummary(taskId: "t1", summary: "做完了", settled: true))
        XCTAssertEqual(store.list(kind: .taskSummary).count, 1)
    }

    // MARK: - Planner

    func testPlannerParseAndValidate() {
        let planner = JsonPlanner()
        let plan = planner.parsePlanJson(
            """
            {"goal":"做事","steps":[
              {"tool":"read_file","args":{"path":"a"}},
              {"tool":"write_file","requires_approval":true}],
             "risks":["风险1",""]}
            """, taskId: "task-9")
        guard let plan else {
            XCTFail("plan should parse")
            return
        }
        XCTAssertEqual(plan.steps.count, 2)
        XCTAssertEqual(plan.steps[0].idempotencyKey, "task-9:0")
        XCTAssertEqual(plan.steps[1].idempotencyKey, "task-9:1")
        XCTAssertTrue(plan.steps[1].requiresApproval)
        XCTAssertEqual(plan.risks, ["风险1"])
        XCTAssertEqual(
            PlanValidator.validate(plan, registeredTools: ["read_file", "write_file"], taskId: "task-9"),
            [])
        let errors = PlanValidator.validate(
            plan, registeredTools: ["read_file"], taskId: "task-9")
        XCTAssertTrue(errors.contains { $0.contains("未知工具 write_file") })
        XCTAssertNil(planner.parsePlanJson("not json", taskId: "t"))
    }

    // MARK: - ContextManager

    func testContextManager() {
        let manager = ContextManager()
        XCTAssertEqual(manager.estimateTokens(""), 0)
        XCTAssertEqual(manager.estimateTokens("abcd"), 1)
        XCTAssertEqual(manager.estimateTokens("abcde"), 2)

        let history = [
            ConversationMessage(
                id: "m1", conversationId: "c", role: "user", content: "你好",
                source: "user", createdAtEpochSeconds: 1),
        ]
        let assembled = manager.build(
            systemPrompt: "SYS", memorySummary: "", history: history,
            currentMessage: "在吗", tools: [])
        XCTAssertEqual(assembled.messages.map { $0.role }, ["system", "user", "user"])
        XCTAssertEqual(assembled.compressedRounds, 0)
        // Images are estimated, never free: +1000 for one image.
        let withImage = manager.build(
            systemPrompt: "SYS", memorySummary: "", history: [],
            currentMessage: "看图", tools: [],
            currentImages: [ChatImage(dataUrl: "data:image/jpeg;base64,x")])
        XCTAssertEqual(
            withImage.estimatedTokens,
            manager.estimateTokens("SYS") + 4 + manager.estimateTokens("看图") + 4 + 1000)

        // Over budget: tool output shrinks first (note recorded),
        // then the oldest round compresses into a summary.
        let tight = ContextManager(budgetEstimatedTokens: 100)
        let userMsg = ConversationMessage(
            id: "m0", conversationId: "c", role: "user", content: "列出文件",
            source: "user", createdAtEpochSeconds: 1)
        let longTool = ConversationMessage(
            id: "m2", conversationId: "c", role: "tool",
            content: String(repeating: "x", count: 2000),
            source: "tool:list_files", createdAtEpochSeconds: 2, toolCallId: "c1")
        let squeezed = tight.build(
            systemPrompt: "SYS", memorySummary: "", history: [userMsg, longTool],
            currentMessage: "hi", tools: [])
        XCTAssertTrue(squeezed.compressionNotes.contains { $0.contains("工具原始输出已压缩") })
        XCTAssertEqual(squeezed.compressedRounds, 1)
        XCTAssertEqual(squeezed.messages.last?.content, "hi")
    }

    // MARK: - Failover

    func testFailoverRulesAndExtraction() {
        let policy = FailoverPolicy(maxSameBrainRetries: 1, fallbackBrainIds: ["b2", "b3"])
        let retryable = BrainErrorInfo(code: "x", message: "m", retryable: true)
        XCTAssertTrue(Failover.shouldRetry(error: retryable, attemptsSoFar: 0, policy: policy))
        XCTAssertFalse(Failover.shouldRetry(error: retryable, attemptsSoFar: 1, policy: policy))
        XCTAssertFalse(Failover.shouldRetry(
            error: BrainErrorInfo(code: "x", message: "m", retryable: false),
            attemptsSoFar: 0, policy: policy))
        XCTAssertEqual(
            Failover.nextFallback(currentBrainId: "b1", policy: policy, tried: ["b1"]), "b2")
        XCTAssertNil(Failover.nextFallback(
            currentBrainId: "b1", policy: FailoverPolicy(), tried: []))
        XCTAssertEqual(
            Failover.describeDegradation(
                BrainCapabilities(streaming: false, toolCalling: false)).count, 2)

        let single = Failover.extractToolCallsFromText(
            text: "好的 {\"tool\":\"read_file\",\"args\":{\"path\":\"a.txt\"}} 完成",
            registeredTools: ["read_file"], taskId: "task1")
        XCTAssertEqual(single?.count, 1)
        XCTAssertEqual(single?.first?.tool, "read_file")
        XCTAssertEqual(single?.first?.args["path"], .string("a.txt"))
        XCTAssertEqual(single?.first?.idempotencyKey, "task1:0")
        XCTAssertEqual(single?.first?.actionId, "text-0")

        let batch = Failover.extractToolCallsFromText(
            text: "{\"actions\":[{\"tool\":\"list_files\"},{\"tool\":\"read_file\",\"idempotency_key\":\"t:3\"}]}",
            registeredTools: ["list_files", "read_file"], taskId: "t")
        XCTAssertEqual(batch?.count, 2)
        XCTAssertEqual(batch?.last?.idempotencyKey, "t:3")
        // Unknown tool / no JSON / unbalanced: nil, never a guess.
        XCTAssertNil(Failover.extractToolCallsFromText(
            text: "{\"tool\":\"nope\"}", registeredTools: ["read_file"], taskId: "t"))
        XCTAssertNil(Failover.extractToolCallsFromText(
            text: "没有任何 json", registeredTools: ["read_file"], taskId: "t"))
        XCTAssertNil(Failover.extractToolCallsFromText(
            text: "{\"tool\":\"read_file\"", registeredTools: ["read_file"], taskId: "t"))
    }

    func testFailoverRunner() async throws {
        let policy = FailoverPolicy(maxSameBrainRetries: 1, fallbackBrainIds: ["b2"])
        let runner = FailoverRunner(policy: policy)
        // b1 fails retryable twice (initial + one same-brain retry),
        // then the runner switches to b2 which succeeds.
        final class Counter { var value = 0 }
        let b1Calls = Counter()
        let outcome = try await runner.run(firstBrainId: "b1") { brainId in
            if brainId == "b1" {
                b1Calls.value += 1
                throw FailoverError(code: "server", message: "炸了", retryable: true)
            }
            return "ok-\(brainId)"
        }
        XCTAssertEqual(b1Calls.value, 2)
        switch outcome {
        case .success(let value, let brainId, let attempts):
            XCTAssertEqual(value, "ok-b2")
            XCTAssertEqual(brainId, "b2")
            XCTAssertEqual(attempts, 2)
        case .failure:
            XCTFail("expected success via fallback")
        }
        // Non-retryable: immediate failure, exactly one attempt.
        let calls = Counter()
        let failed = try await runner.run(firstBrainId: "b1") { _ -> String in
            calls.value += 1
            throw FailoverError(code: "unauthorized", message: "401", retryable: false)
        }
        XCTAssertEqual(calls.value, 1)
        switch failed {
        case .failure(let error, let tried):
            XCTAssertEqual(error.code, "unauthorized")
            XCTAssertEqual(tried, ["b1"])
        case .success:
            XCTFail("expected failure")
        }
    }

    func testToolRegistryRules() {
        XCTAssertTrue(ToolRegistryRules.isReadOnlyRun(["ls", "-la"]))
        XCTAssertTrue(ToolRegistryRules.isReadOnlyRun(["/bin/cat", "f"]))
        XCTAssertTrue(ToolRegistryRules.isReadOnlyRun(["python3", "--version"]))
        XCTAssertTrue(ToolRegistryRules.isReadOnlyRun(["python3", "-u", "-V"]))
        XCTAssertFalse(ToolRegistryRules.isReadOnlyRun(["python3", "script.py"]))
        XCTAssertFalse(ToolRegistryRules.isReadOnlyRun(["rm", "-rf", "x"]))
        XCTAssertFalse(ToolRegistryRules.isReadOnlyRun([]))
        XCTAssertFalse(ToolRegistryRules.isReadOnlyRun(nil))
    }

    // MARK: - BrainEngine

    func testEngineRequiresConfiguredBrain() async throws {
        let registry = BrainAdapterRegistry()
        let adapter = FakeBrainAdapter(adapterId: "fake1") { _, _ in
            [.textDelta("不应被调用"), .done(finishReason: "stop")]
        }
        registerBrain(registry, adapter, brainId: "b1")
        let tools = FakeToolRegistry(defs: []) { _ in
            ToolOutcome(ok: true, summary: "ok")
        }
        let engine = makeEngine(
            registry: registry, store: InMemoryConversationStore(),
            tools: tools, currentBrainId: { nil })
        let events = try await collect(engine.chat(conversationId: "c1", userText: "在吗"))
        XCTAssertEqual(errorCodes(events), ["brain_not_configured"])
        XCTAssertEqual(doneReasons(events), ["error"])
        XCTAssertEqual(adapter.requests.count, 0)
    }

    func testEngineControlShortCircuitsWithoutBrain() async throws {
        let registry = BrainAdapterRegistry()
        let tools = FakeToolRegistry(defs: []) { _ in
            ToolOutcome(ok: true, summary: "ok")
        }
        let engine = makeEngine(
            registry: registry, store: InMemoryConversationStore(),
            tools: tools, currentBrainId: { nil }, sessionLedger: ControlLedger())
        let events = try await collect(engine.chat(conversationId: "c1", userText: "暂停"))
        XCTAssertEqual(events, [
            .textDelta("已暂停任务"),
            .done(finishReason: "control"),
        ])
    }

    func testEnginePlainTextTurn() async throws {
        let registry = BrainAdapterRegistry()
        let adapter = FakeBrainAdapter(adapterId: "fake1") { request, _ in
            XCTAssertTrue(request.tools.isEmpty, "问候首轮不带工具")
            return [.textDelta("你好"), .done(finishReason: "stop")]
        }
        registerBrain(registry, adapter, brainId: "b1")
        let store = InMemoryConversationStore()
        let tools = FakeToolRegistry(defs: [
            ToolDef(name: "list_files", description: "列出文件"),
        ]) { _ in ToolOutcome(ok: true, summary: "ok") }
        let engine = makeEngine(
            registry: registry, store: store, tools: tools, currentBrainId: { "b1" })
        let events = try await collect(engine.chat(conversationId: "c1", userText: "在吗"))
        XCTAssertEqual(events, [
            .textDelta("你好"),
            .done(finishReason: "stop"),
        ])
        let messages = try store.listMessages(conversationId: "c1")
        XCTAssertEqual(messages.map { $0.role }, ["user", "assistant"])
        XCTAssertEqual(messages.last?.content, "你好")
        XCTAssertEqual(messages.last?.source, "b1")
    }

    func testEngineToolRoundTrip() async throws {
        let registry = BrainAdapterRegistry()
        let adapter = FakeBrainAdapter(adapterId: "fake1") { request, callIndex in
            if callIndex == 1 {
                return [
                    .toolCall(BrainToolCall(id: "c1", name: "list_files", args: [:])),
                    .done(finishReason: "tool_calls"),
                ]
            }
            // Second round must see the paired tool result.
            let toolMessages = request.messages.filter { $0.role == "tool" }
            XCTAssertEqual(toolMessages.count, 1)
            XCTAssertEqual(toolMessages.first?.toolCallId, "c1")
            let assistant = request.messages.first { $0.role == "assistant" }
            XCTAssertEqual(assistant?.toolCalls.first?.id, "c1")
            return [.textDelta("已列出"), .done(finishReason: "stop")]
        }
        registerBrain(registry, adapter, brainId: "b1")
        let store = InMemoryConversationStore()
        let tools = FakeToolRegistry(defs: [
            ToolDef(name: "list_files", description: "列出文件"),
        ]) { _ in
            ToolOutcome(
                ok: true, summary: "列出 1 个文件", data: "file a.txt",
                workspaceState: "not_empty", path: "")
        }
        let engine = makeEngine(
            registry: registry, store: store, tools: tools, currentBrainId: { "b1" })
        let events = try await collect(
            engine.chat(conversationId: "c1", userText: "列出文件看看"))
        XCTAssertEqual(tools.executed.count, 1)
        XCTAssertTrue(events.contains(.toolResult(id: "c1", ok: true, summary: "列出 1 个文件")))
        XCTAssertEqual(doneReasons(events), ["stop"])
        let messages = try store.listMessages(conversationId: "c1")
        XCTAssertEqual(messages.map { $0.role }, ["user", "assistant", "tool", "assistant"])
    }

    func testEngineRepeatedWriteStops() async throws {
        let registry = BrainAdapterRegistry()
        let adapter = FakeBrainAdapter(adapterId: "fake1") { _, callIndex in
            [
                .toolCall(BrainToolCall(
                    id: "w\(callIndex)", name: "write_file",
                    args: ["path": .string("a.txt"), "content": .string("x")])),
                .done(finishReason: "tool_calls"),
            ]
        }
        registerBrain(registry, adapter, brainId: "b1")
        let tools = FakeToolRegistry(defs: [
            ToolDef(name: "write_file", description: "写文件"),
        ]) { _ in ToolOutcome(ok: true, summary: "已写入") }
        let engine = makeEngine(
            registry: registry, store: InMemoryConversationStore(),
            tools: tools, currentBrainId: { "b1" })
        let events = try await collect(
            engine.chat(conversationId: "c1", userText: "写文件 a.txt"))
        // The write executed exactly once; the repeat hit guard 1.
        XCTAssertEqual(tools.executed.count, 1)
        XCTAssertEqual(errorCodes(events), ["tool_call_repeated"])
        XCTAssertEqual(doneReasons(events), ["error"])
    }

    func testEngineReadOnlyCacheHit() async throws {
        let registry = BrainAdapterRegistry()
        let adapter = FakeBrainAdapter(adapterId: "fake1") { _, callIndex in
            if callIndex <= 2 {
                return [
                    .toolCall(BrainToolCall(
                        id: "r\(callIndex)", name: "read_file",
                        args: ["path": .string("a.txt")])),
                    .done(finishReason: "tool_calls"),
                ]
            }
            return [.textDelta("读完了"), .done(finishReason: "stop")]
        }
        registerBrain(registry, adapter, brainId: "b1")
        let tools = FakeToolRegistry(defs: [
            ToolDef(name: "read_file", description: "读文件"),
        ]) { _ in ToolOutcome(ok: true, summary: "内容如下", data: "hello") }
        let engine = makeEngine(
            registry: registry, store: InMemoryConversationStore(),
            tools: tools, currentBrainId: { "b1" })
        let events = try await collect(
            engine.chat(conversationId: "c1", userText: "读文件 a.txt"))
        // Second identical read came from the turn ledger, not the
        // registry: one real execution, one cached result.
        XCTAssertEqual(tools.executed.count, 1)
        XCTAssertTrue(events.contains(
            .toolResult(id: "r2", ok: true, summary: "内容如下（缓存结果，未重新执行）")))
        XCTAssertEqual(doneReasons(events), ["stop"])
    }

    func testEngineFailoverSwitchesBrain() async throws {
        let registry = BrainAdapterRegistry()
        let failing = FakeBrainAdapter(adapterId: "fake1") { _, _ in
            [
                .error(BrainErrorInfo(code: "server_error", message: "服务错误", retryable: true)),
                .done(finishReason: "error"),
            ]
        }
        let backup = FakeBrainAdapter(adapterId: "fake2") { _, _ in
            [.textDelta("二号接手了"), .done(finishReason: "stop")]
        }
        registerBrain(registry, failing, brainId: "b1", fallbackBrainIds: ["b2"])
        registerBrain(registry, backup, brainId: "b2")
        let tools = FakeToolRegistry(defs: []) { _ in
            ToolOutcome(ok: true, summary: "ok")
        }
        let engine = makeEngine(
            registry: registry, store: InMemoryConversationStore(),
            tools: tools, currentBrainId: { "b1" },
            failoverPolicy: FailoverPolicy(fallbackBrainIds: ["b2"]))
        let events = try await collect(engine.chat(conversationId: "c1", userText: "在吗"))
        // Same-brain retry once, then the switch.
        XCTAssertEqual(failing.requests.count, 2)
        XCTAssertEqual(backup.requests.count, 1)
        XCTAssertEqual(doneReasons(events), ["switched_brain"])
    }

    func testEngineNoFailoverWhenDisallowed() async throws {
        let registry = BrainAdapterRegistry()
        let failing = FakeBrainAdapter(adapterId: "fake1") { _, _ in
            [
                .error(BrainErrorInfo(code: "server_error", message: "服务错误", retryable: true)),
                .done(finishReason: "error"),
            ]
        }
        let backup = FakeBrainAdapter(adapterId: "fake2") { _, _ in
            [.textDelta("不应被调用"), .done(finishReason: "stop")]
        }
        registerBrain(registry, failing, brainId: "b1", fallbackBrainIds: ["b2"])
        registerBrain(registry, backup, brainId: "b2")
        let tools = FakeToolRegistry(defs: []) { _ in
            ToolOutcome(ok: true, summary: "ok")
        }
        let engine = makeEngine(
            registry: registry, store: InMemoryConversationStore(),
            tools: tools, currentBrainId: { "b1" },
            failoverPolicy: FailoverPolicy(fallbackBrainIds: ["b2"]))
        let events = try await collect(engine.chat(
            conversationId: "c1", userText: "在吗", allowFailover: false))
        XCTAssertEqual(failing.requests.count, 2)
        XCTAssertEqual(backup.requests.count, 0)
        XCTAssertEqual(errorCodes(events), ["server_error"])
        XCTAssertEqual(doneReasons(events), ["error"])
    }

    func testEngineAskUserEndsTurn() async throws {
        let registry = BrainAdapterRegistry()
        let adapter = FakeBrainAdapter(adapterId: "fake1") { _, _ in
            [
                .toolCall(BrainToolCall(
                    id: "q1", name: "ask_user",
                    args: ["question": .string("用哪个？")])),
                .done(finishReason: "tool_calls"),
            ]
        }
        registerBrain(registry, adapter, brainId: "b1")
        let tools = FakeToolRegistry(defs: [
            ToolDef(name: "ask_user", description: "向用户提问"),
        ]) { _ in ToolOutcome(ok: true, summary: "已提问", data: "你想用哪个？") }
        let engine = makeEngine(
            registry: registry, store: InMemoryConversationStore(),
            tools: tools, currentBrainId: { "b1" })
        let events = try await collect(
            engine.chat(conversationId: "c1", userText: "帮我选一个方案"))
        XCTAssertEqual(adapter.requests.count, 1)
        XCTAssertTrue(events.contains(.textDelta("你想用哪个？")))
        XCTAssertEqual(doneReasons(events), ["awaiting_user"])
    }

    func testEngineEmptyResponseIsAnError() async throws {
        let registry = BrainAdapterRegistry()
        let adapter = FakeBrainAdapter(adapterId: "fake1") { _, _ in
            [.done(finishReason: "stop")]
        }
        registerBrain(registry, adapter, brainId: "b1")
        let tools = FakeToolRegistry(defs: []) { _ in
            ToolOutcome(ok: true, summary: "ok")
        }
        let engine = makeEngine(
            registry: registry, store: InMemoryConversationStore(),
            tools: tools, currentBrainId: { "b1" })
        let events = try await collect(engine.chat(conversationId: "c1", userText: "在吗"))
        XCTAssertEqual(errorCodes(events), ["empty_response"])
        XCTAssertEqual(doneReasons(events), ["error"])
    }

    func testEngineFindMissingToolResults() {
        let calls = [BrainToolCall(id: "c1", name: "list_files", args: [:])]
        XCTAssertEqual(
            BrainEngine.findMissingToolResults(messages: [], calls: calls), ["c1"])
        let messages = [
            ChatMessage(role: "tool", content: "{}", toolCallId: "c1", name: "list_files"),
        ]
        XCTAssertEqual(
            BrainEngine.findMissingToolResults(messages: messages, calls: calls), [])
        XCTAssertEqual(
            BrainEngine.findMissingToolResults(messages: [], calls: []), [])
    }

    func testLooksActionOriented() {
        XCTAssertFalse(BrainEngine.looksActionOriented("在吗"))
        XCTAssertFalse(BrainEngine.looksActionOriented("你好"))
        // "任务" as a topic is not an instruction.
        XCTAssertFalse(BrainEngine.looksActionOriented("能帮我完成什么任务？"))
        XCTAssertTrue(BrainEngine.looksActionOriented("读一下这个文件"))
        XCTAssertTrue(BrainEngine.looksActionOriented("please run the tests"))
        XCTAssertTrue(BrainEngine.looksActionOriented("派给 Codex 做"))
    }
}
