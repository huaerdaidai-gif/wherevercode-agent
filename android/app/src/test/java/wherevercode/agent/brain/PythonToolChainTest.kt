package wherevercode.agent.brain

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import wherevercode.agent.core.device.JvmDeviceAdapter
import wherevercode.agent.core.python.ProcessPythonRuntime
import wherevercode.agent.core.security.CommandLimits
import wherevercode.agent.core.security.InMemoryAuditLog
import wherevercode.agent.core.workspace.FileWorkspaceAdapter

/**
 * 2026-11-09 自主开发闭环：验证 Python 执行真的接进了 run_command 工具链。
 *
 * 这些测试用宿主机的真实 python3（ProcessPythonRuntime）执行真实脚本，
 * 断言真实退出码与输出；没有 Python 的环境用 assumeTrue 明确跳过，
 * 不伪造成功。App 内嵌 Chaquopy 是同接口的另一个实现（无法在 JVM 单测
 * 里跑，见回报中的"未在真机验证"）。
 */
class PythonToolChainTest {

    private lateinit var tmpDir: File
    private lateinit var workspaceRoot: File
    private lateinit var workspaceAdapter: FileWorkspaceAdapter
    private lateinit var runtime: ProcessPythonRuntime
    private lateinit var tools: ToolRegistry

    private class AllowGate : ApprovalGate {
        override suspend fun requestApproval(action: Action): Boolean = true
    }

    @Before
    fun setUp() {
        tmpDir = Files.createTempDirectory("python-toolchain-test").toFile()
        workspaceRoot = File(tmpDir, "workspace").apply { mkdirs() }
        val taskRoot = File(tmpDir, "tasks").apply { mkdirs() }
        workspaceAdapter = FileWorkspaceAdapter(workspaceRoot, taskRoot)
        runtime = ProcessPythonRuntime()
        tools = ToolRegistry(
            deviceAdapter = JvmDeviceAdapter(listOf(workspaceRoot), workspaceAdapter),
            workspaceAdapter = workspaceAdapter,
            limits = CommandLimits(),
            taskStore = FakeTaskStore(),
            auditLog = InMemoryAuditLog(),
            pythonRuntime = runtime,
        )
    }

    @After
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    private fun context() = ToolContext(
        taskId = "conversation-1",
        agentId = "brain",
        approvalGate = AllowGate(),
        workspaceRoot = workspaceRoot,
        deviceId = "local",
        folder = workspaceRoot.path,
    )

    private fun write(path: String, content: String, id: String): ToolOutcome =
        kotlinx.coroutines.runBlocking {
        tools.execute(
            Action(
                actionId = id,
                tool = "write_file",
                args = mapOf("path" to path, "content" to content),
                idempotencyKey = "idem-$id",
            ),
            context(),
        )
    }

    private fun run(vararg argv: String, id: String): ToolOutcome = kotlinx.coroutines.runBlocking {
        tools.execute(
            Action(
                actionId = id,
                tool = "run_command",
                args = mapOf("argv" to argv.toList(), "timeout" to 60),
                idempotencyKey = "idem-$id",
            ),
            context(),
        )
    }

    private fun requirePython() {
        val probe = runtime.probe()
        assumeTrue("宿主机无 python3，跳过真实执行测试：${probe.reason}", probe.available)
    }

    @Test
    fun `python version probe is real and takes no write round`() {
        requirePython()
        val outcome = run("python3", "--version", id = "probe")
        assertTrue(outcome.summary, outcome.ok)
        assertTrue("探针结果应标明 Python 可用：${outcome.summary}", outcome.summary.contains("Python 可用"))
        assertTrue("应来自真实运行时标签：${outcome.summary}", outcome.summary.contains("系统 python3"))
        assertNull(workspaceAdapter.activeRound())
    }

    @Test
    fun `calculator acceptance - write code, run real tests, fix, rerun`() {
        requirePython()
        val calculator = """
            def add(a, b):
                return a + b

            def sub(a, b):
                return a - b

            def mul(a, b):
                return a * b

            def div(a, b):
                if b == 0:
                    raise ZeroDivisionError("division by zero")
                return a / b
        """.trimIndent()
        val testFile = """
            import unittest
            from calculator import add, sub, mul, div

            class CalculatorTest(unittest.TestCase):
                def test_add(self):
                    self.assertEqual(add(2, 3), 5)

                def test_sub(self):
                    self.assertEqual(sub(7, 2), 5)

                def test_mul(self):
                    self.assertEqual(mul(4, 3), 12)

                def test_div(self):
                    self.assertEqual(div(9, 3), 3)

                def test_div_by_zero(self):
                    with self.assertRaises(ZeroDivisionError):
                        div(1, 0)

            if __name__ == "__main__":
                unittest.main()
        """.trimIndent()

        assertTrue(write("agent-test/calculator.py", calculator, "w-calc").ok)
        assertTrue(write("agent-test/test_calculator.py", testFile, "w-test").ok)

        val passed = run("python3", "agent-test/test_calculator.py", id = "run-pass")
        assertTrue("测试应真实通过：${passed.summary}\n${passed.data}", passed.ok)
        assertEquals("Python 退出码 0（系统 python3，真实执行）", passed.summary)
        assertTrue("应看到 unittest 的真实输出", passed.data.orEmpty().contains("OK"))

        // 第 2 轮：故意写坏一条断言 → 真实退出码必须非 0。
        val broken = testFile.replace("self.assertEqual(add(2, 3), 5)", "self.assertEqual(add(2, 3), 6)")
        assertTrue(write("agent-test/test_calculator.py", broken, "w-broken").ok)
        val failed = run("python3", "agent-test/test_calculator.py", id = "run-fail")
        assertFalse("坏测试不得被报告为通过", failed.ok)
        assertTrue(failed.summary, failed.summary.startsWith("Python 退出码 1"))
        assertTrue("应包含真实失败信息", failed.data.orEmpty().contains("FAILED"))

        // 第 3 轮：修复后重跑，回到 0。
        assertTrue(write("agent-test/test_calculator.py", testFile, "w-fixed").ok)
        val fixed = run("python3", "agent-test/test_calculator.py", id = "run-fixed")
        assertTrue("修复后应通过：${fixed.summary}", fixed.ok)
        assertNull("每轮脚本执行结束都必须释放写轮次", workspaceAdapter.activeRound())
    }

    @Test
    fun `script exit code and stderr are real`() {
        requirePython()
        val script = """
            import sys
            print("stdout-line")
            print("stderr-line", file=sys.stderr)
            sys.exit(3)
        """.trimIndent()
        assertTrue(write("agent-test/exit3.py", script, "w-exit").ok)
        val outcome = run("python3", "agent-test/exit3.py", id = "run-exit")
        assertFalse(outcome.ok)
        assertEquals("hand_run_failed", outcome.errorCode)
        assertTrue(outcome.summary, outcome.summary.contains("退出码 3"))
        assertTrue(outcome.data.orEmpty().contains("stdout-line"))
        assertTrue(outcome.data.orEmpty().contains("stderr-line"))
        assertNull(workspaceAdapter.activeRound())
    }

    @Test
    fun `module mode runs through the same runtime`() {
        requirePython()
        val moduleScript = """
            import sys
            print("module-args=" + " ".join(sys.argv[1:]))
        """.trimIndent()
        assertTrue(write("mymodule.py", moduleScript, "w-mod").ok)
        // -m 形式要求模块在 sys.path 上；工作区根即 cwd，helper 会插入 cwd。
        val outcome = run("python3", "-m", "mymodule", "a", "b", id = "run-module")
        assertTrue("模块执行应真实成功：${outcome.summary}\n${outcome.data}", outcome.ok)
        assertTrue(outcome.data.orEmpty().contains("module-args=a b"))
    }

    @Test
    fun `python unavailable is reported truthfully, never simulated`() = runTest {
        val noPython = ToolRegistry(
            deviceAdapter = JvmDeviceAdapter(listOf(workspaceRoot), workspaceAdapter),
            workspaceAdapter = workspaceAdapter,
            limits = CommandLimits(),
            taskStore = FakeTaskStore(),
            auditLog = InMemoryAuditLog(),
            pythonRuntime = null,
        )
        val outcome = noPython.execute(
            Action(
                actionId = "probe-none",
                tool = "run_command",
                args = mapOf("argv" to listOf("python3", "--version")),
                idempotencyKey = "idem-probe-none",
            ),
            context(),
        )
        assertFalse(outcome.ok)
        assertEquals("python_unavailable", outcome.errorCode)
        assertTrue(outcome.summary.contains("未模拟执行"))
        assertNull(workspaceAdapter.activeRound())
    }
}
