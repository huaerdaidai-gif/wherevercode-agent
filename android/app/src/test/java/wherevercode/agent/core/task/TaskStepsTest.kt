package wherevercode.agent.core.task

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 回归：同一秒内连续加入的步骤必须按加入顺序返回。
 * 旧 listSteps 以随机 UUID 的 stepId 作第二排序键，同秒步骤顺序随机，
 * DispatchClaimExecuteTest 在 CI 42558012978 约 50% 概率失败。
 * 纯 Kotlin，无需 Robolectric。
 */
class TaskStepsTest {

    @Test
    fun `same-second steps keep insertion order`() {
        repeat(50) {
            val store = InMemoryTaskStepStore()
            val now = System.currentTimeMillis() / 1000
            store.addStep(
                TaskStep(
                    stepId = UUID.randomUUID().toString(),
                    taskId = "t1",
                    toolCallId = "c1",
                    toolName = "write_file",
                    startedAtEpochSeconds = now,
                ),
            )
            store.addStep(
                TaskStep(
                    stepId = UUID.randomUUID().toString(),
                    taskId = "t1",
                    toolCallId = "c2",
                    toolName = "run_command",
                    startedAtEpochSeconds = now,
                ),
            )
            assertEquals(
                listOf("write_file", "run_command"),
                store.listSteps("t1").map { it.toolName },
            )
        }
    }

    @Test
    fun `earlier steps sort before later ones`() {
        val store = InMemoryTaskStepStore()
        val now = System.currentTimeMillis() / 1000
        store.addStep(
            TaskStep(
                stepId = UUID.randomUUID().toString(),
                taskId = "t1",
                toolCallId = "c2",
                toolName = "run_command",
                startedAtEpochSeconds = now + 5,
            ),
        )
        store.addStep(
            TaskStep(
                stepId = UUID.randomUUID().toString(),
                taskId = "t1",
                toolCallId = "c1",
                toolName = "write_file",
                startedAtEpochSeconds = now,
            ),
        )
        assertEquals(
            listOf("write_file", "run_command"),
            store.listSteps("t1").map { it.toolName },
        )
    }
}
