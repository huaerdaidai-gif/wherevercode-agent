package wherevercode.agent.core.dispatch

import java.io.File
import org.json.JSONObject
import org.junit.Assert.fail
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.AgentDiscovery
import wherevercode.agent.core.control.ControlPlane
import wherevercode.agent.core.control.RoomControlAuditLog
import wherevercode.agent.core.control.RoomControlStore
import wherevercode.agent.core.security.AuditLog
import wherevercode.agent.core.security.FileAuditLog
import wherevercode.agent.core.security.InMemoryAuditLog
import wherevercode.agent.core.security.RoomAuthorizationStore
import wherevercode.agent.core.security.RoomRevocationStore
import wherevercode.agent.core.task.RoomTaskStore
import wherevercode.agent.core.task.TaskDatabase
import wherevercode.agent.core.task.TaskScheduler

/**
 * 测试夹具：真实的 App 存储（Room TaskDatabase + RoomTaskStore +
 * RoomControlStore + ControlPlane + TaskScheduler），不用假任务库——
 * 证明派工模块复用的是 App 原有任务系统。Robolectric 只提供 Context。
 *
 * [dbName] 非空时用文件库（重启测试：关库后用同名重开）。
 */
class DispatchTestFixture(
    dbName: String? = null,
    stateFile: File? = null,
    auditFile: File? = null,
    tokenSource: DispatchStateStore? = null,
) {
    val context = RuntimeEnvironment.getApplication()
    val db: TaskDatabase = if (dbName == null) TaskDatabase.inMemory(context)
    else TaskDatabase.create(context, dbName, allowMainThreadQueries = true)
    val taskStore = RoomTaskStore(db)
    val controlStore = RoomControlStore(db)
    val revocationStore = RoomRevocationStore(db)
    val controlPlane = ControlPlane(
        taskStore,
        RoomAuthorizationStore(db),
        revocationStore,
        controlStore = controlStore,
        auditLog = RoomControlAuditLog(db.controlAuditDao()),
        actor = "mcp-dispatch",
    )
    val state: DispatchStateStore = tokenSource ?: stateFile?.let { FileDispatchStateStore(it) } ?: InMemoryDispatchStateStore()
    val audit: AuditLog = auditFile?.let { FileAuditLog(it) } ?: InMemoryAuditLog()
    val tokens = DispatchTokenRegistry(state)
    val scheduler = TaskScheduler(taskStore, AgentDiscovery(context), controlStore)
    val service = McpDispatchService(
        taskStore = taskStore,
        controlPlane = controlPlane,
        controlStore = controlStore,
        revocationStore = revocationStore,
        claimPort = SchedulerClaimPort(scheduler),
        tokens = tokens,
        state = state,
        audit = audit,
    )

    fun close() = db.close()

    fun call(p: DispatchPrincipal, tool: String, args: JSONObject = JSONObject()): JSONObject =
        service.call(p, tool, args)

    fun fail(p: DispatchPrincipal, tool: String, args: JSONObject = JSONObject()): DispatchException {
        try {
            service.call(p, tool, args)
        } catch (e: DispatchException) {
            return e
        }
        fail("expected $tool to fail for ${p.auditId}")
        throw AssertionError()
    }

    companion object {
        fun args(vararg pairs: Pair<String, Any?>): JSONObject =
            JSONObject().apply { pairs.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }
    }
}

/** 一组常用身份：owner、ChatGPT（派工）、Grok/Codex（执行）、Hand 设备、Muse（另一个派工方）。 */
class DispatchActors(val tokens: DispatchTokenRegistry) {
    val ownerIssued = tokens.issue("owner", DispatchRole.OWNER, emptySet(), "phone owner")
    val chatgptIssued = tokens.issue("chatgpt", DispatchRole.AGENT, setOf(DispatchCapability.DISPATCH), "ChatGPT dispatcher")
    val grokIssued = tokens.issue("grok-bot", DispatchRole.AGENT, setOf(DispatchCapability.EXECUTE), "Grok executor")
    val codexIssued = tokens.issue("codex", DispatchRole.AGENT, setOf(DispatchCapability.EXECUTE), "Codex executor")
    val handIssued = tokens.issue("hand-mac", DispatchRole.DEVICE, setOf(DispatchCapability.EXECUTE), "Hand device")
    val museIssued = tokens.issue("muse", DispatchRole.AGENT, setOf(DispatchCapability.DISPATCH), "other dispatcher")

    val owner get() = ownerIssued.principal
    val chatgpt get() = chatgptIssued.principal
    val grok get() = grokIssued.principal
    val codex get() = codexIssued.principal
    val hand get() = handIssued.principal
    val muse get() = museIssued.principal
}
