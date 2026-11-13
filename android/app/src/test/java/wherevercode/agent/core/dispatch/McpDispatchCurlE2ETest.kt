package wherevercode.agent.core.dispatch

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 外部进程驱动：起服务端后用 `scripts/mcp-dispatch-e2e.sh`（curl）走一遍。
 * 机器上没有 bash/curl/python3 或找不到脚本时跳过（Assume）。
 */
@RunWith(RobolectricTestRunner::class)
class McpDispatchCurlE2ETest {
    private lateinit var f: DispatchTestFixture
    private var server: McpDispatchHttpServer? = null

    @Before
    fun setUp() { f = DispatchTestFixture() }

    @After
    fun tearDown() { server?.stop(); f.close() }

    private fun has(cmd: String): Boolean = try {
        ProcessBuilder("sh", "-c", "command -v $cmd").start().waitFor() == 0
    } catch (_: Exception) { false }

    @Test
    fun `curl script drives the dispatch flow end to end`() {
        val script = listOf(File("../../scripts/mcp-dispatch-e2e.sh"), File("scripts/mcp-dispatch-e2e.sh")).firstOrNull { it.isFile }
        assumeTrue("script not found", script != null)
        assumeTrue("bash/curl/python3 required", has("bash") && has("curl") && has("python3"))
        val actors = DispatchActors(f.tokens)
        val srv = McpDispatchHttpServer(f.service).start().also { server = it }
        val log = E2ELog("curl")
        log.hide(actors.chatgptIssued.token); log.hide(actors.grokIssued.token)
        log.line("server listening on ${srv.url}; running ${script!!.path}")
        val pb = ProcessBuilder("bash", script.absolutePath).redirectErrorStream(true)
        pb.environment()["MCP_URL"] = srv.url
        pb.environment()["DISPATCHER_TOKEN"] = actors.chatgptIssued.token
        pb.environment()["EXECUTOR_TOKEN"] = actors.grokIssued.token
        pb.environment()["EXECUTOR_ID"] = "grok-bot"
        val proc = pb.start()
        val output = proc.inputStream.bufferedReader().readText()
        assertTrue("script timed out", proc.waitFor(120, TimeUnit.SECONDS))
        output.lines().forEach { log.line(it) }
        log.line("exit=${proc.exitValue()}")
        assertFalse(output.contains(actors.chatgptIssued.token))
        assertFalse(output.contains(actors.grokIssued.token))
        assertEquals(output, 0, proc.exitValue())
        assertTrue(output.trim().endsWith("E2E_OK"))
        assertEquals(2, f.taskStore.listTasks().size)
    }
}
