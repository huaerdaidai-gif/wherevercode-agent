package wherevercode.agent.python

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import wherevercode.agent.brain.Action
import wherevercode.agent.brain.ApprovalGate
import wherevercode.agent.brain.FakeTaskStore
import wherevercode.agent.brain.ToolContext
import wherevercode.agent.brain.ToolOutcome
import wherevercode.agent.brain.ToolRegistry
import wherevercode.agent.core.device.JvmDeviceAdapter
import wherevercode.agent.core.python.PythonProbe
import wherevercode.agent.core.python.PythonRunRequest
import wherevercode.agent.core.python.PythonRunResult
import wherevercode.agent.core.python.PythonRuntime
import wherevercode.agent.core.security.CommandLimits
import wherevercode.agent.core.security.InMemoryAuditLog
import wherevercode.agent.core.workspace.FileWorkspaceAdapter

/**
 * K70 真机回归（2026-11-09）：Python 已产生 stdout、退出码 0，run_command
 * 却返回 hand_run_failed + "Python 执行失败：null"。
 *
 * 根因在 ChaquopyPythonRuntime 的 JSON 解析：agent_runner.py 成功时返回
 * `"error": null`，而 Android 框架 org.json 的 `optString(name, fallback)`
 * 遇到 JSON null 会返回字符串 "null"（不是 fallback），于是 failureReason
 * 变成非 null 的 "null"，上层据此判定失败。JVM 单测里的 org.json:json
 * 返回 fallback，测不出这个差异；本测试必须用 Robolectric 才能使用
 * Android 的真实 org.json 实现。
 *
 * 测试走 ToolRegistry.execute → runPython → ChaquopyPythonRuntime 的解析
 * 函数，锁定成功、非零退出、脚本异常、helper 异常四条路径。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ChaquopyPythonResultParsingTest {

    private lateinit var tmpDir: File
    private lateinit var workspaceRoot: File
    private lateinit var taskRoot: File
    private lateinit var workspaceAdapter: FileWorkspaceAdapter

    private class AllowGate : ApprovalGate {
        override suspend fun requestApproval(action: Action): Boolean = true
    }

    /** 把真机 helper 返回的原始 JSON 喂给生产解析函数，再交给 ToolRegistry。 */
    private class HelperJsonRuntime(private val raw: String) : PythonRuntime {
        override fun probe(): PythonProbe = PythonProbe(
            available = true,
            label = ChaquopyPythonRuntime.LABEL,
            version = "3.11.6",
        )

        override fun run(request: PythonRunRequest): PythonRunResult =
            ChaquopyPythonRuntime.parseRunResult(raw)
    }

    @Before
    fun setUp() {
        tmpDir = Files.createTempDirectory("chaquopy-python-result-test").toFile()
        workspaceRoot = File(tmpDir, "workspace").apply { mkdirs() }
        taskRoot = File(tmpDir, "tasks").apply { mkdirs() }
        File(workspaceRoot, "agent-test").mkdirs()
        File(workspaceRoot, "agent-test/hello.py").writeText(
            "print('Hello from Android Agent')\n",
            Charsets.UTF_8,
        )
        workspaceAdapter = FileWorkspaceAdapter(workspaceRoot, taskRoot)
    }

    @After
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    private fun runHelperJson(raw: String): ToolOutcome {
        val tools = ToolRegistry(
            deviceAdapter = JvmDeviceAdapter(listOf(workspaceRoot), workspaceAdapter),
            workspaceAdapter = workspaceAdapter,
            limits = CommandLimits(),
            taskStore = FakeTaskStore(),
            auditLog = InMemoryAuditLog(),
            pythonRuntime = HelperJsonRuntime(raw),
        )
        return runBlocking {
            tools.execute(
                Action(
                    actionId = "run-python",
                    tool = "run_command",
                    args = mapOf(
                        "argv" to listOf("python3", "agent-test/hello.py"),
                        "timeout" to 60,
                    ),
                    idempotencyKey = "idem-run-python",
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

    private fun helperJson(
        exitCode: Int,
        stdout: String,
        stderr: String,
        error: String?,
    ): String = buildString {
        append("{\"ok\":").append(error == null).append(',')
        append("\"exit_code\":").append(exitCode).append(',')
        append("\"stdout\":\"").append(stdout).append("\",")
        append("\"stderr\":\"").append(stderr).append("\",")
        append("\"timed_out\":false,")
        append("\"cwd\":\"/workspace\",\"argv\":[],")
        append("\"error\":")
        if (error == null) append("null") else append('"').append(error).append('"')
        append('}')
    }

    @Test
    fun `exit 0 with JSON null error is reported as success`() {
        val outcome = runHelperJson(
            helperJson(
                exitCode = 0,
                stdout = "Hello from Android Agent\\n",
                stderr = "",
                error = null,
            ),
        )
        assertTrue(
            "stdout 已产生且退出码为 0，不得报失败：${outcome.summary}\n${outcome.data}",
            outcome.ok,
        )
        assertNull("成功执行不应带错误码：${outcome.errorCode}", outcome.errorCode)
        assertEquals(
            "Python 退出码 0（${ChaquopyPythonRuntime.LABEL}，真实执行）",
            outcome.summary,
        )
        assertTrue(
            "必须保留真实 stdout：${outcome.data}",
            outcome.data.orEmpty().contains("Hello from Android Agent"),
        )
        assertFalse(
            "不能把 JSON null 显示成失败原因：${outcome.summary}",
            outcome.summary.contains("null"),
        )
    }

    @Test
    fun `non-zero exit keeps stdout and stderr and reports the real exit code`() {
        val outcome = runHelperJson(
            helperJson(
                exitCode = 3,
                stdout = "partial output\\n",
                stderr = "boom on stderr\\n",
                error = null,
            ),
        )
        assertFalse("非零退出必须报告失败", outcome.ok)
        assertEquals("hand_run_failed", outcome.errorCode)
        assertTrue("必须报告真实退出码：${outcome.summary}", outcome.summary.contains("Python 退出码 3"))
        assertTrue("必须保留 stdout：${outcome.data}", outcome.data.orEmpty().contains("partial output"))
        assertTrue("必须保留 stderr：${outcome.data}", outcome.data.orEmpty().contains("boom on stderr"))
        assertFalse(
            "非零退出不能被写成无意义的 null：${outcome.summary}",
            outcome.summary.contains("：null"),
        )
    }

    @Test
    fun `script exception is a failure with a meaningful reason`() {
        val traceback = "Traceback (most recent call last):\\nValueError: boom\\n"
        val outcome = runHelperJson(
            helperJson(
                exitCode = 1,
                stdout = "before crash\\n",
                stderr = traceback,
                error = null,
            ),
        )
        assertFalse("脚本异常必须报告失败", outcome.ok)
        assertEquals("hand_run_failed", outcome.errorCode)
        assertTrue("必须报告真实退出码：${outcome.summary}", outcome.summary.contains("Python 退出码 1"))
        assertFalse(
            "异常路径不能出现无意义的 null：${outcome.summary}",
            outcome.summary.contains("null"),
        )
        assertTrue(
            "必须保留 traceback：${outcome.data}",
            outcome.data.orEmpty().contains("ValueError: boom"),
        )
        assertTrue(
            "异常前的 stdout 也必须保留：${outcome.data}",
            outcome.data.orEmpty().contains("before crash"),
        )
    }

    @Test
    fun `helper runtime error is preserved verbatim`() {
        val outcome = runHelperJson(
            helperJson(
                exitCode = 1,
                stdout = "",
                stderr = "FileNotFoundError: script not found\\n",
                error = "FileNotFoundError: script not found",
            ),
        )
        assertFalse("helper 异常必须报告失败", outcome.ok)
        assertEquals("hand_run_failed", outcome.errorCode)
        assertTrue(
            "必须报告真实异常原因：${outcome.summary}",
            outcome.summary.contains("FileNotFoundError: script not found"),
        )
        assertFalse(
            "不得把真实异常替换成 null：${outcome.summary}",
            outcome.summary.contains("：null"),
        )
        assertTrue(
            "必须保留 stderr：${outcome.data}",
            outcome.data.orEmpty().contains("FileNotFoundError"),
        )
    }
}
