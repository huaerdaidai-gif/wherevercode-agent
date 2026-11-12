package wherevercode.agent.brain

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import wherevercode.agent.core.device.JvmDeviceAdapter
import wherevercode.agent.core.python.ProcessPythonRuntime
import wherevercode.agent.core.python.PythonProbe
import wherevercode.agent.core.python.PythonRunRequest
import wherevercode.agent.core.python.PythonRunResult
import wherevercode.agent.core.python.PythonRuntime
import wherevercode.agent.core.security.CommandLimits
import wherevercode.agent.core.security.InMemoryAuditLog
import wherevercode.agent.core.workspace.FileWorkspaceAdapter

/**
 * 2026-11-09 回归：unittest 的 bool 退出码 + Python 脚本的 cwd 相对路径。
 *
 * - unittest.main() 用 sys.exit(False/True) 退出；helper 必须把 bool
 *   归一化成 0/1，否则 JSON 变成 true/false，Android optInt 会回退 -1。
 * - ToolRegistry 必须以 cwd 为相对脚本路径的基准，同时保留原有
 *   工作区根目录相对路径，并且不能越界。
 */
class PythonUnittestAndCwdRegressionTest {

    private lateinit var tmpDir: File
    private lateinit var workspaceRoot: File
    private lateinit var taskRoot: File
    private lateinit var workspaceAdapter: FileWorkspaceAdapter
    private lateinit var recordingRuntime: RecordingPythonRuntime
    private lateinit var tools: ToolRegistry

    private class AllowGate : ApprovalGate {
        override suspend fun requestApproval(action: Action): Boolean = true
    }

    private class RecordingPythonRuntime : PythonRuntime {
        var lastRequest: PythonRunRequest? = null

        override fun probe(): PythonProbe = PythonProbe(
            available = true,
            label = "test-runtime",
            version = "3.11.6",
        )

        override fun run(request: PythonRunRequest): PythonRunResult {
            lastRequest = request
            return PythonRunResult(
                available = true,
                runtimeLabel = "test-runtime",
                exitCode = 0,
            )
        }
    }

    @Before
    fun setUp() {
        tmpDir = Files.createTempDirectory("python-unittest-cwd").toFile()
        workspaceRoot = File(tmpDir, "workspace").apply { mkdirs() }
        taskRoot = File(tmpDir, "tasks").apply { mkdirs() }
        workspaceAdapter = FileWorkspaceAdapter(workspaceRoot, taskRoot)
        recordingRuntime = RecordingPythonRuntime()
        tools = ToolRegistry(
            deviceAdapter = JvmDeviceAdapter(listOf(workspaceRoot), workspaceAdapter),
            workspaceAdapter = workspaceAdapter,
            limits = CommandLimits(),
            taskStore = FakeTaskStore(),
            auditLog = InMemoryAuditLog(),
            pythonRuntime = recordingRuntime,
        )
    }

    @After
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    private fun runCommand(
        argv: List<String>,
        cwd: String = "",
        id: String = "run-1",
    ): ToolOutcome {
        val args = mutableMapOf<String, Any?>(
            "argv" to argv,
            "timeout" to 30,
        )
        if (cwd.isNotEmpty()) args["cwd"] = cwd
        return runBlocking {
            tools.execute(
                Action(
                    actionId = id,
                    tool = "run_command",
                    args = args,
                    idempotencyKey = "idem-$id",
                ),
                ToolContext(
                    taskId = "task-1",
                    agentId = "brain",
                    approvalGate = AllowGate(),
                    workspaceRoot = workspaceRoot,
                    deviceId = "local",
                    folder = workspaceRoot.path,
                ),
            )
        }
    }

    private fun agentRunnerSource(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidates = listOf(
                File(dir, "src/main/python/agent_runner.py"),
                File(dir, "android/app/src/main/python/agent_runner.py"),
            )
            candidates.firstOrNull { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        error("agent_runner.py not found from ${System.getProperty("user.dir")}")
    }

    private fun requireHostPython() {
        val probe = ProcessPythonRuntime().probe()
        assumeTrue("宿主机无 python3，跳过 helper 回归：${probe.reason}", probe.available)
    }

    /** 用宿主机 python3 真实调用 app 内的 agent_runner.run()，返回其 JSON。 */
    private fun invokeHelper(script: File): JSONObject {
        requireHostPython()
        val driver = File(tmpDir, "invoke_agent_runner.py").apply {
            writeText(
                """
                import importlib.util
                import sys

                sys.dont_write_bytecode = True
                spec = importlib.util.spec_from_file_location("agent_runner_under_test", sys.argv[1])
                mod = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(mod)
                print(mod.run(sys.argv[2], sys.argv[3], "[]", "", int(sys.argv[4])))
                """.trimIndent(),
                Charsets.UTF_8,
            )
        }
        val process = ProcessBuilder(
            "python3",
            driver.absolutePath,
            agentRunnerSource().absolutePath,
            script.absolutePath,
            (script.parentFile ?: tmpDir).absolutePath,
            "30000",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue("python3 helper 未在 60 秒内结束", process.waitFor(60, TimeUnit.SECONDS))
        assertEquals("helper driver 失败：\n$output", 0, process.exitValue())
        val jsonLine = output.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.startsWith("{") }
        assertNotNull("helper 输出中没有 JSON：\n$output", jsonLine)
        return JSONObject(jsonLine!!)
    }

    private fun writeUnittest(dir: File, failing: Boolean): File {
        dir.mkdirs()
        val assertion = if (failing) "self.assertEqual(1, 2)" else "self.assertEqual(1, 1)"
        return File(dir, if (failing) "test_failure.py" else "test_success.py").apply {
            writeText(
                """
                import unittest

                class SampleTest(unittest.TestCase):
                    def test_case(self):
                        $assertion

                if __name__ == "__main__":
                    unittest.main()
                """.trimIndent(),
                Charsets.UTF_8,
            )
        }
    }

    @Test
    fun `unittest success exits with integer 0`() {
        val script = writeUnittest(File(tmpDir, "success"), failing = false)
        val json = invokeHelper(script)
        val exitCode = json.get("exit_code")
        assertTrue(
            "exit_code 必须是整数，实际为 ${exitCode?.javaClass}：$json",
            exitCode is Int,
        )
        assertEquals(0, exitCode)
    }

    @Test
    fun `unittest failure exits with integer 1`() {
        val script = writeUnittest(File(tmpDir, "failure"), failing = true)
        val json = invokeHelper(script)
        val exitCode = json.get("exit_code")
        assertTrue(
            "exit_code 必须是整数，实际为 ${exitCode?.javaClass}：$json",
            exitCode is Int,
        )
        assertEquals(1, exitCode)
        assertTrue(
            "失败测试必须保留 stderr：${json.optString("stderr")}",
            json.optString("stderr").contains("FAILED"),
        )
    }

    @Test
    fun `cwd relative script is found and root relative path stays compatible`() {
        val script = File(workspaceRoot, "agent-test/calculator/test_calculator.py").apply {
            parentFile?.mkdirs()
            writeText("print('calculator tests')\n", Charsets.UTF_8)
        }

        val fromCwd = runCommand(
            argv = listOf("python3", "test_calculator.py"),
            cwd = "agent-test/calculator",
            id = "cwd-relative",
        )
        assertTrue("cwd 相对路径应找到脚本：${fromCwd.summary}", fromCwd.ok)
        assertEquals(script, recordingRuntime.lastRequest?.script)
        assertEquals(
            File(workspaceRoot, "agent-test/calculator"),
            recordingRuntime.lastRequest?.cwd,
        )

        // cwd 非根时，原有“工作区根目录相对路径”调用仍必须可用。
        val fromRoot = runCommand(
            argv = listOf("python3", "agent-test/calculator/test_calculator.py"),
            cwd = "agent-test/calculator",
            id = "root-relative",
        )
        assertTrue("原根目录相对路径应保持兼容：${fromRoot.summary}", fromRoot.ok)
        assertEquals(script, recordingRuntime.lastRequest?.script)
    }

    @Test
    fun `cwd relative script cannot escape the workspace`() {
        File(workspaceRoot, "agent-test/calculator").mkdirs()
        File(tmpDir, "outside.py").writeText("print('outside')\n", Charsets.UTF_8)

        val outcome = runCommand(
            argv = listOf("python3", "../../../outside.py"),
            cwd = "agent-test/calculator",
            id = "escape",
        )
        assertFalse("越界路径必须被拒绝：${outcome.summary}", outcome.ok)
        assertEquals("path_not_allowed", outcome.errorCode)
        assertNull("越界路径不得进入 Python 运行时", recordingRuntime.lastRequest)
    }
}
