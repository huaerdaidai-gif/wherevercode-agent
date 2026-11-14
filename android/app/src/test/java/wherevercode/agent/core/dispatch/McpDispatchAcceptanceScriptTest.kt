package wherevercode.agent.core.dispatch

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Runs the server-agnostic `scripts/dispatch_acceptance.py` (the same script used to accept the V5
 * deploy) against the App-native dispatch server: scoped discovery, the denial matrix and the
 * create → assign → claim → progress → submit → get_task_status chain. Skipped without python3.
 */
@RunWith(RobolectricTestRunner::class)
class McpDispatchAcceptanceScriptTest {
    private lateinit var f: DispatchTestFixture
    private lateinit var a: DispatchActors
    private var server: McpDispatchHttpServer? = null

    @Before
    fun setUp() {
        f = DispatchTestFixture()
        a = DispatchActors(f.tokens)
    }

    @After
    fun tearDown() { server?.stop(); f.close() }

    private fun script(): File? =
        listOf(File("../../scripts/dispatch_acceptance.py"), File("scripts/dispatch_acceptance.py")).firstOrNull { it.isFile }

    private fun hasPython(): Boolean = try {
        ProcessBuilder("sh", "-c", "command -v python3").start().waitFor() == 0
    } catch (_: Exception) { false }

    private fun run(env: Map<String, String>): Pair<Int, String> {
        val s = script()
        assumeTrue("script not found", s != null)
        assumeTrue("python3 required", hasPython())
        val srv = server ?: McpDispatchHttpServer(f.service).start().also { server = it }
        val pb = ProcessBuilder("python3", s!!.absolutePath).redirectErrorStream(true)
        pb.environment()["MCP_URL"] = srv.url
        pb.environment().putAll(env)
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText()
        assertTrue("script timed out", proc.waitFor(120, TimeUnit.SECONDS))
        val log = E2ELog("acceptance")
        listOf(a.chatgptIssued, a.grokIssued, a.codexIssued).forEach { log.hide(it.token) }
        out.lines().forEach { log.line(it) }
        listOf(a.chatgptIssued, a.grokIssued, a.codexIssued).forEach { assertFalse("token leaked", out.contains(it.token)) }
        return proc.exitValue() to out
    }

    @Test
    fun `acceptance script passes the full dispatch chain and denial matrix`() {
        val (code, out) = run(
            mapOf(
                "DISPATCHER_TOKEN" to a.chatgptIssued.token,
                "EXECUTOR_TOKEN" to a.grokIssued.token,
                "EXECUTOR_ID" to "grok-bot",
                "OTHER_EXECUTOR_TOKEN" to a.codexIssued.token,
            ),
        )
        assertEquals(out, 0, code)
        assertTrue(out, out.trim().endsWith("ACCEPTANCE_OK"))
        listOf(
            "executor create_task refused", "executor assign_task refused", "assign to unknown agent refused",
            "non-assignee claim refused", "double claim by another executor refused", "submit by non-claimer refused",
            "unrelated executor cannot read status", "final status = completed",
        ).forEach { assertTrue("missing check: $it\n$out", out.contains("PASS: $it")) }
        val tasks = f.taskStore.listTasks()
        assertEquals("idempotent create made exactly one task", 1, tasks.size)
        assertEquals("completed", tasks.single().state)
        val kinds = f.db.taskEventDao().listByTask(tasks.single().taskId, 100).map { it.kind }
        listOf(
            McpDispatchService.EVENT_MCP_CREATED, McpDispatchService.EVENT_MCP_ASSIGNED, McpDispatchService.EVENT_MCP_CLAIMED,
            McpDispatchService.EVENT_MCP_PROGRESS, McpDispatchService.EVENT_MCP_DELIVERED,
        ).forEach { assertTrue("audit event $it missing: $kinds", kinds.contains(it)) }
    }

    @Test
    fun `acceptance fails when the dispatcher credential only has executor scope`() {
        // This is the production V5 situation: the credential handed to ChatGPT only discovers executor tools.
        val (code, out) = run(
            mapOf(
                "DISPATCHER_TOKEN" to a.codexIssued.token,
                "EXECUTOR_TOKEN" to a.grokIssued.token,
                "EXECUTOR_ID" to "grok-bot",
            ),
        )
        assertNotEquals(out, 0, code)
        assertTrue(out, out.contains("FAIL: dispatcher discovers create_task/assign_task/get_task_status"))
        assertTrue("nothing may be created", f.taskStore.listTasks().isEmpty())
    }
}
