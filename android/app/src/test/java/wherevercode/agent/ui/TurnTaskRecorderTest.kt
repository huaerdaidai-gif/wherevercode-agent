package wherevercode.agent.ui

import wherevercode.agent.core.task.testTaskScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.AgentDiscovery
import wherevercode.agent.core.task.DispatchFakeTaskStore
import wherevercode.agent.core.task.InMemoryTaskStepStore
import wherevercode.agent.core.task.TaskScheduler
import wherevercode.agent.core.task.TaskStepStatus

/**
 * Turn -> task mirror (phase 1, U3): a chat turn opens a working task,
 * its tool calls become steps, and its terminal status lands on the
 * task row — through the real [TaskScheduler], never faked.
 *
 * Robolectric only because [TaskScheduler] needs a real [AgentDiscovery]
 * (it takes a Context); the claim path never calls discovery.
 */
@RunWith(RobolectricTestRunner::class)
class TurnTaskRecorderTest {

    private lateinit var taskStore: DispatchFakeTaskStore
    private lateinit var stepStore: InMemoryTaskStepStore
    private lateinit var scheduler: TaskScheduler
    private lateinit var recorder: TurnTaskRecorder

    @Before
    fun setUp() {
        taskStore = DispatchFakeTaskStore()
        stepStore = InMemoryTaskStepStore()
        val discovery = AgentDiscovery(RuntimeEnvironment.getApplication())
        scheduler = testTaskScheduler(taskStore, discovery)
        recorder = TurnTaskRecorder(taskStore, scheduler, stepStore)
    }

    @Test
    fun `beginTurn creates a working task carrying the session id`() {
        val taskId = recorder.beginTurn("帮我写 calculator.py", "conv-1", "deepseek")
        assertNotNull("beginTurn 应返回任务 id", taskId)
        val task = taskStore.getTask(taskId!!)
        assertNotNull(task)
        assertEquals("working", task?.state)
        assertEquals("conv-1", task?.sessionId)
        assertEquals("deepseek", task?.brainId)
        assertEquals(TurnTaskRecorder.LOCAL_AGENT_ID, task?.assignedAgentId)
        assertEquals(taskId, recorder.currentTaskId())
    }

    @Test
    fun `tool calls become steps and DONE completes the task`() {
        val taskId = recorder.beginTurn("帮我写 calculator.py", "conv-1", "deepseek")!!
        recorder.recordToolCall(taskId, "c1", "write_file", mapOf("path" to "calc.py"))
        recorder.recordToolResult(taskId, "c1", true, "已写入 calc.py")
        recorder.recordToolCall(taskId, "c2", "run_command", mapOf("argv" to listOf("python3", "-m", "unittest")))
        recorder.recordToolResult(taskId, "c2", true, "2 passed")
        recorder.finishTurn(taskId, TurnStatus.DONE, "完成了，测试 2 passed", null, true)
        val task = taskStore.getTask(taskId)
        assertEquals("completed", task?.state)
        assertEquals("完成了，测试 2 passed", task?.result)
        val steps = stepStore.listSteps(taskId)
        assertEquals(2, steps.size)
        assertEquals(listOf("write_file", "run_command"), steps.map { it.toolName })
        assertTrue("步骤全部成功", steps.all { it.status == TaskStepStatus.SUCCESS })
        assertEquals("已写入 calc.py", steps[0].resultSummary)
        assertNull(recorder.currentTaskId())
    }

    @Test
    fun `DONE with 040e9317 verbatim failure headline is failed not completed`() {
        // 2026-11-12 09:46 实证：任务 040e9317 的大脑正文第一段逐字。
        // 轮次终态 DONE，但正文明说“这个任务我目前无法完成”——
        // 修前 finishTurn 无条件记 completed（假完成）。
        val taskId = recorder.beginTurn("团队任务测试", "conv-1", "model-api-zhipu")!!
        val text = "我来执行这个团队任务测试。先查看仓库现状并从默认分支创建新分支。" +
            "工具调用返回了“未知扩展工具”错误，说明 git 仓库扩展工具当前实际不可用。" +
            "我先检查本地工作区，看仓库是否在本地存在。这个任务我目前无法完成，如实向您报告情况："
        recorder.finishTurn(taskId, TurnStatus.DONE, text, null, true)
        val task = taskStore.getTask(taskId)
        assertEquals("failed", task?.state)
        assertTrue("error 应带上原文", (task?.error ?: "").contains("无法完成"))
        assertNull("失败不写 result", task?.result)
    }

    @Test
    fun `DONE with need-info headline is failed`() {
        val taskId = recorder.beginTurn("团队任务测试", "conv-1", "model-api-zhipu")!!
        recorder.finishTurn(
            taskId,
            TurnStatus.DONE,
            "在当前工作区中没有找到 wherevercode-agent 仓库，请提供该仓库在此环境中的路径。",
            null,
            true,
        )
        assertEquals("failed", taskStore.getTask(taskId)?.state)
    }

    @Test
    fun `DONE with success headline mentioning no problems still completes`() {
        // 误判护栏经 finishTurn 同样生效：“没有发现问题”不判失败。
        val taskId = recorder.beginTurn("帮我检查", "conv-1", "deepseek")!!
        recorder.finishTurn(taskId, TurnStatus.DONE, "已完成检查，没有发现问题。", null, true)
        val task = taskStore.getTask(taskId)
        assertEquals("completed", task?.state)
        assertEquals("已完成检查，没有发现问题。", task?.result)
    }

    @Test
    fun `ERROR finish marks the task failed with the real error`() {
        val taskId = recorder.beginTurn("帮我写 calculator.py", "conv-1", "deepseek")!!
        recorder.finishTurn(
            taskId,
            TurnStatus.ERROR,
            "",
            "brain_not_configured：未配置大脑",
            false,
        )
        val task = taskStore.getTask(taskId)
        assertEquals("failed", task?.state)
        assertEquals("brain_not_configured：未配置大脑", task?.error)
    }

    @Test
    fun `EMPTY finish marks the task failed, never completed`() {
        val taskId = recorder.beginTurn("你好", "conv-1", "deepseek")!!
        recorder.finishTurn(taskId, TurnStatus.EMPTY, "", "大脑没有返回可显示的内容（空响应）。", false)
        assertEquals("failed", taskStore.getTask(taskId)?.state)
    }

    @Test
    fun `PAUSED finish marks the task cancelled, not working`() {
        val taskId = recorder.beginTurn("帮我写 calculator.py", "conv-1", "deepseek")!!
        recorder.finishTurn(taskId, TurnStatus.PAUSED, "", null, true)
        val task = taskStore.getTask(taskId)
        assertEquals("cancelled", task?.state)
        assertEquals("轮次已暂停，剩余步骤未执行。", task?.result)
    }

    @Test
    fun `beginTurn with null brainId creates a working task for remote turns`() {
        // V5 修复（2026-11-11，CodeBuddy 卡死轮次）：远端轮次
        // beginTurn 传 brainId=null，真机上它从未成功建过任务行；
        // 先证明 recorder 本身认 null brainId（任务行照常建），
        // 控制指令不再依赖它（见 ChatViewModelRemoteAgentTest 里
        // “control uses the actual run task id”）。
        val taskId = recorder.beginTurn("在吗", "conv-remote", null)
        assertNotNull("brainId 为 null 时 beginTurn 也应返回任务 id", taskId)
        val task = taskStore.getTask(taskId!!)
        assertNotNull(task)
        assertEquals("working", task?.state)
        assertEquals("conv-remote", task?.sessionId)
        assertNull(task?.brainId)
    }

    @Test
    fun `beginTurn returns null while another task is in hand and leaves no queued orphan`() {
        val first = recorder.beginTurn("第一个任务", "conv-1", "deepseek")
        assertNotNull(first)
        val second = recorder.beginTurn("第二个任务", "conv-1", "deepseek")
        assertNull("已有在手任务时 beginTurn 应返回 null", second)
        val rows = taskStore.listTasks()
        assertEquals(2, rows.size)
        assertTrue("不留 queued 僵尸行", rows.none { it.state == "queued" })
        assertEquals("cancelled", rows.first { it.taskId != first }.state)
    }

    @Test
    fun `recorder never throws when the store fails`() {
        taskStore.failCreate = true
        val taskId = recorder.beginTurn("帮我写 calculator.py", "conv-1", "deepseek")
        assertNull("写库失败时 beginTurn 返回 null，不抛异常", taskId)
        recorder.finishTurn("ghost", TurnStatus.DONE, "x", null, false)
    }

    // 2026-11-12 回复归属修复：远端轮次只建行、不经调度器认领门禁。

    @Test
    fun `beginRemoteRound creates a working task carrying the agent id and session id`() {
        val taskId = recorder.beginRemoteRound("你自己领啊", "conv-codex", "codex", "wherevercode-agent-2")
        assertNotNull("beginRemoteRound 应返回任务 id", taskId)
        val task = taskStore.getTask(taskId!!)
        assertNotNull(task)
        assertEquals("working", task?.state)
        assertEquals("conv-codex", task?.sessionId)
        assertEquals("codex", task?.assignedAgentId)
        assertEquals("wherevercode-agent-2", task?.projectId)
        assertEquals("你自己领啊", task?.title)
        assertEquals(taskId, recorder.currentTaskId())
    }

    @Test
    fun `beginRemoteRound works while another task is in hand`() {
        // 真机事故的根因：beginTurn 走调度器全局单任务门禁，一脑在
        // 手时认领失败回 null，轮次只能拿随机 UUID 发 Hand（活跑在
        // 任务表外面）。beginRemoteRound 不经认领门禁，在手任务不
        // 应挡住轮次建行。
        val first = recorder.beginTurn("第一个任务", "conv-1", "deepseek")
        assertNotNull(first)
        assertEquals("working", taskStore.getTask(first!!)?.state)
        val round = recorder.beginRemoteRound("你自己领啊", "conv-codex", "codex")
        assertNotNull("已有在手任务时 beginRemoteRound 也应返回任务 id", round)
        val task = taskStore.getTask(round!!)
        assertEquals("working", task?.state)
        assertEquals("codex", task?.assignedAgentId)
        // 两行都在任务表里可查，不留表外活。
        assertEquals(2, taskStore.listTasks().size)
    }

    @Test
    fun `beginRemoteRound returns null when the store fails and leaves no queued orphan`() {
        taskStore.failCreate = true
        val taskId = recorder.beginRemoteRound("你自己领啊", "conv-codex", "codex")
        assertNull("写库失败时 beginRemoteRound 返回 null，不抛异常", taskId)
        assertTrue("不留 queued 僵尸行", taskStore.listTasks().none { it.state == "queued" })
    }

    @Test
    fun `beginRemoteRound row finishes DONE to completed with the reply as result`() {
        val taskId = recorder.beginRemoteRound("你自己领啊", "conv-codex", "codex")!!
        recorder.finishTurn(taskId, TurnStatus.DONE, "可靠性修复已做完", null, false)
        val task = taskStore.getTask(taskId)
        assertEquals("completed", task?.state)
        assertEquals("可靠性修复已做完", task?.result)
        assertNull(recorder.currentTaskId())
    }
}
