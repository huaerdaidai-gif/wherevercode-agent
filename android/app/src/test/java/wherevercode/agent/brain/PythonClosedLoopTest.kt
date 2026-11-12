package wherevercode.agent.brain

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import wherevercode.agent.core.device.JvmDeviceAdapter
import wherevercode.agent.core.python.ProcessPythonRuntime
import wherevercode.agent.core.security.CommandLimits
import wherevercode.agent.core.security.DEFAULT_ALLOWED_COMMANDS
import wherevercode.agent.core.security.InMemoryAuditLog
import wherevercode.agent.core.security.validateCommand
import wherevercode.agent.core.workspace.FileWorkspaceAdapter

/**
 * 2026-11-09 阶段 2（B4）：App 自主开发闭环的“模型指令级”测试。
 *
 * 与 PythonToolChainTest（直接调 ToolRegistry.execute）不同，本文件
 * 让一个按工具结果决策下一步的脚本化“模型”（FakeAdapter，fake
 * transport，零真实模型调用、零网络）驱动真实 BrainEngine：
 * 写 calculator.py（含 bug）→ 写 test_calculator.py → run_command
 * `python3 -m unittest`（真实退出码 1）→ 读失败输出修复
 * calculator.py → 再跑同一条命令（真实退出码 0）。
 *
 * Python 执行本身是真实的（宿主机 ProcessPythonRuntime；无 python3
 * 时 assumeTrue 跳过，不伪造成功）。Chaquopy 内嵌 Python 在 K70 上
 * 真跑 calculator.py 仍未验证，见实施报告。
 *
 * 形态区分（B4，机器可核）：Android APK 构建在本机不可行——
 * run_command 白名单（DEFAULT_ALLOWED_COMMANDS）不含 gradle /
 * gradlew，工具层直接拒绝；Python 项目可闭环，Android 完整闭环
 * 需形态 ①（外部构建设备），是后续阶段的事。
 */
class PythonClosedLoopTest {

    private lateinit var tmpDir: File
    private lateinit var workspaceRoot: File
    private lateinit var conversations: ConversationStore
    private lateinit var tools: ToolRegistry
    private lateinit var eventLog: EventLog

    @Before
    fun setUp() {
        tmpDir = Files.createTempDirectory("python-closed-loop-test").toFile()
        workspaceRoot = File(tmpDir, "workspace").apply { mkdirs() }
        val taskRoot = File(tmpDir, "tasks").apply { mkdirs() }
        val workspaceAdapter = FileWorkspaceAdapter(workspaceRoot, taskRoot)
        conversations = InMemoryConversationStore()
        eventLog = InMemoryEventLog()
        tools = ToolRegistry(
            deviceAdapter = JvmDeviceAdapter(listOf(workspaceRoot), workspaceAdapter),
            workspaceAdapter = workspaceAdapter,
            limits = CommandLimits(),
            taskStore = FakeTaskStore(),
            auditLog = InMemoryAuditLog(),
            pythonRuntime = ProcessPythonRuntime(),
        )
        BrainAdapterRegistry.clearForTests()
        BrainAdapterRegistry.registerConfig(
            BrainConfig(
                brainId = "fake-brain",
                kind = BrainKind.MODEL_API,
                adapterId = "fake-test-adapter",
                label = "Fake（测试替身）",
                baseUrl = null,
                model = "fake-model",
                authRef = null,
            ),
        )
    }

    @After
    fun tearDown() {
        BrainAdapterRegistry.clearForTests()
        tmpDir.deleteRecursively()
    }

    private fun engine(adapter: BrainAdapter, gate: ApprovalGate): BrainEngine {
        BrainAdapterRegistry.registerAdapter(adapter)
        return BrainEngine(
            adapters = BrainAdapterRegistry,
            conversations = conversations,
            contextManager = ContextManager(),
            memory = InMemoryMemoryStore(),
            tools = tools,
            eventLog = eventLog,
            approvalGate = gate,
            currentBrainId = { "fake-brain" },
        )
    }

    private fun context() = ToolContext(
        taskId = "conversation-1",
        agentId = "brain",
        approvalGate = FakeApprovalGate(allow = true),
        workspaceRoot = workspaceRoot,
        deviceId = "local",
        folder = workspaceRoot.path,
    )

    private fun requirePython() {
        val probe = ProcessPythonRuntime().probe()
        assumeTrue("宿主机无 python3，跳过真实执行测试：${probe.reason}", probe.available)
    }

    /**
     * 脚本化“模型”：每轮只看请求里的 role=tool 结果 JSON 再决策，
     * 与真实模型的行为一致（读结果 → 定下一步），不是盲放固定序列。
     * 只有看到“Python 退出码 1”才修复；只有看到“Python 退出码 0”
     * 才收尾。若失败没出现或修复后仍失败，sawFailure / sawPass 保持
     * false，测试随之失败——不会把没发生的闭环说成通过。
     */
    private class ClosedLoopModel {
        var sawFailure = false
        var sawPass = false
        var firstRunData = ""
        var secondRunData = ""
        var rounds = 0

        fun script(request: ChatRequest): Flow<BrainEvent> {
            rounds += 1
            val results = request.messages.filter { it.role == "tool" }
                .map { JSONObject(it.content) }
            return when (results.size) {
                0 -> flowOf(
                    BrainEvent.TextDelta("先写 calculator.py（初版）。"),
                    BrainEvent.ToolCall(
                        "call-1",
                        "write_file",
                        mapOf("path" to "calculator.py", "content" to BUGGY_CALCULATOR),
                    ),
                    BrainEvent.Done("stop"),
                )
                1 -> flowOf(
                    BrainEvent.TextDelta("再写测试 test_calculator.py。"),
                    BrainEvent.ToolCall(
                        "call-2",
                        "write_file",
                        mapOf("path" to "test_calculator.py", "content" to TEST_CALCULATOR),
                    ),
                    BrainEvent.Done("stop"),
                )
                2 -> flowOf(
                    BrainEvent.TextDelta("跑一遍测试看看。"),
                    BrainEvent.ToolCall(
                        "call-3",
                        "run_command",
                        mapOf(
                            "argv" to listOf("python3", "-m", "unittest"),
                            "timeout" to 60,
                        ),
                    ),
                    BrainEvent.Done("stop"),
                )
                3 -> {
                    val last = results.last()
                    if (!last.optBoolean("ok") &&
                        last.optString("summary").contains("Python 退出码 1")
                    ) {
                        sawFailure = true
                        firstRunData = last.optString("data")
                        flowOf(
                            BrainEvent.TextDelta("测试失败了，add 算错了，修复 calculator.py 再跑。"),
                            BrainEvent.ToolCall(
                                "call-4",
                                "write_file",
                                mapOf("path" to "calculator.py", "content" to FIXED_CALCULATOR),
                            ),
                            BrainEvent.Done("stop"),
                        )
                    } else {
                        flowOf(
                            BrainEvent.TextDelta("测试意外通过，闭环前提不成立，停止。"),
                            BrainEvent.Done("stop"),
                        )
                    }
                }
                4 -> flowOf(
                    BrainEvent.TextDelta("修复后重跑同一条测试命令。"),
                    BrainEvent.ToolCall(
                        "call-5",
                        "run_command",
                        mapOf(
                            "argv" to listOf("python3", "-m", "unittest"),
                            "timeout" to 60,
                        ),
                    ),
                    BrainEvent.Done("stop"),
                )
                else -> {
                    val last = results.last()
                    if (last.optBoolean("ok") &&
                        last.optString("summary").contains("Python 退出码 0")
                    ) {
                        sawPass = true
                        secondRunData = last.optString("data")
                        flowOf(
                            BrainEvent.TextDelta("计算器已修复，测试全部通过。"),
                            BrainEvent.Done("stop"),
                        )
                    } else {
                        flowOf(
                            BrainEvent.TextDelta("修复后测试仍未通过，停止。"),
                            BrainEvent.Done("stop"),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `model instruction level - write, fail, fix, rerun, pass`() {
        requirePython()
        val model = ClosedLoopModel()
        val engine = engine(FakeAdapter(model::script), FakeApprovalGate(allow = true))
        val events = runBlocking {
            engine.chat(
                "closed-loop-conv",
                "写一个 calculator.py（加减乘除），再写 test_calculator.py，" +
                    "用 python3 -m unittest 跑测试；失败就按输出修复再跑，直到通过。",
            ).toList()
        }
        // 模型必须真的“看到”失败再修复、真的“看到”通过再收尾。
        assertTrue("模型必须先看到一次真实失败（退出码 1），否则闭环前提不成立", model.sawFailure)
        assertTrue("修复后同一条测试命令必须真实通过（退出码 0）", model.sawPass)
        assertTrue("失败输出应含 FAILED：${model.firstRunData}", model.firstRunData.contains("FAILED"))
        assertTrue("通过输出应含 OK：${model.secondRunData}", model.secondRunData.contains("OK"))

        val calls = events.filterIsInstance<BrainEvent.ToolCall>()
        assertEquals(
            listOf("write_file", "write_file", "run_command", "write_file", "run_command"),
            calls.map { it.name },
        )
        val results = events.filterIsInstance<BrainEvent.ToolResult>()
        assertEquals(5, results.size)
        assertFalse(results[2].ok)
        assertTrue(results[2].summary, results[2].summary.contains("Python 退出码 1"))
        assertTrue(results[4].ok)
        assertTrue(results[4].summary, results[4].summary.contains("Python 退出码 0"))
        assertTrue(
            "不得有工具循环护栏报错：${events.filterIsInstance<BrainEvent.Error>()}",
            events.filterIsInstance<BrainEvent.Error>().isEmpty(),
        )
        assertTrue(events.last() is BrainEvent.Done)
        val finalText = events.filterIsInstance<BrainEvent.TextDelta>()
            .joinToString("") { it.text }
        assertTrue("最终文字应报全部通过：$finalText", finalText.contains("全部通过"))
        assertEquals(
            "修复后的文件必须落盘",
            FIXED_CALCULATOR,
            File(workspaceRoot, "calculator.py").readText(),
        )
        // 5 轮工具 + 1 轮收尾文字。
        assertEquals(6, model.rounds)
    }

    @Test
    fun `gradle is refused - android build is not possible on device`() {
        assertFalse("gradle 不在 run_command 白名单", validateCommand(listOf("gradle", "assembleDebug")).first)
        assertFalse("gradlew 也不在白名单", validateCommand(listOf("gradlew", "assembleDebug")).first)
        assertFalse("白名单本身就不含 gradle", "gradle" in DEFAULT_ALLOWED_COMMANDS)
        val outcome = runBlocking {
            tools.execute(
                Action(
                    actionId = "gradle-1",
                    tool = "run_command",
                    args = mapOf("argv" to listOf("gradle", "assembleDebug")),
                    idempotencyKey = "idem-gradle-1",
                ),
                context(),
            )
        }
        assertFalse(outcome.ok)
        assertEquals("hand_command_not_allowed", outcome.errorCode)
    }

    companion object {
        // 初版 bug：add 写成了减法，测试必然失败（退出码 1）。
        private val BUGGY_CALCULATOR = """
            def add(a, b):
                return a - b

            def sub(a, b):
                return a - b

            def mul(a, b):
                return a * b

            def div(a, b):
                if b == 0:
                    raise ZeroDivisionError("division by zero")
                return a / b
        """.trimIndent()

        // 修复版故意比初版多一行注释（文件变大）：CPython 只凭
        // mtime（秒）+ 大小校验 .pyc；同一秒内同大小改写会命中
        // 过期 __pycache__（已复现），与模型无关，是测试必须避开
        // 的真机同款坑。
        private val FIXED_CALCULATOR = """
            def add(a, b):
                # 修复：加法错写成了减法
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

        private val TEST_CALCULATOR = """
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
    }
}
