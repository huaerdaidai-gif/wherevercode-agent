package wherevercode.agent.core.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 任务包（P0 耐久任务引擎，2026-11-11）：跨设备交接契约，versioned。
 *
 * Robolectric：TaskPacket 用 org.json（Android 框架类），本地 JVM
 * 单元测试里是桩，必须走 Robolectric。
 */
@RunWith(RobolectricTestRunner::class)
class TaskPacketTest {

    private fun record() = TaskRecord(
        taskId = "t1",
        projectId = "proj-a",
        title = "写个计算器",
        description = "在 calculator.py 里实现加减乘除",
        state = "assigned",
        attempt = 2,
        attemptId = "att-2",
        leaseExpiresAt = 1_700_000_600L,
        priority = 1,
        dependencies = listOf("t0"),
    )

    @Test
    fun `json round trip preserves every field`() {
        val packet = TaskPacket.fromTask(
            record(),
            repo = "huaerdaidai-gif/wherevercode-agent",
            baseCommit = "abc123",
        )
        val restored = TaskPacket.fromJson(packet.toJson())
        assertEquals(packet, restored)
        assertEquals(1, restored.version)
        assertEquals("task/t1", restored.branch)
        assertEquals("huaerdaidai-gif/wherevercode-agent", restored.repo)
        assertEquals("abc123", restored.baseCommit)
        assertEquals(listOf("优先级 1", "依赖任务先完成：t0"), restored.constraints)
    }

    @Test
    fun `unknown version throws instead of guessing`() {
        val json = TaskPacket.fromTask(record()).toJson()
            .replace("\"version\":1", "\"version\":99")
        assertThrows(IllegalArgumentException::class.java) {
            TaskPacket.fromJson(json)
        }
    }

    @Test
    fun `missing description falls back to the title and branch follows the rule`() {
        val packet = TaskPacket.fromTask(record().copy(description = null))
        assertEquals("写个计算器", packet.objective)
        assertEquals("task/t1", packet.branch)
        // v1 未知字段是 null / 空清单，绝不编。
        assertNull(packet.expectedOutput)
        assertTrue(packet.acceptanceCriteria.isEmpty())
        assertTrue(packet.filesInScope.isEmpty())
        assertNull(packet.repo)
        assertNull(packet.baseCommit)
    }

    @Test
    fun `prompt text puts the objective verbatim first then the contract`() {
        val packet = TaskPacket.fromTask(record())
        val text = packet.toPromptText()
        assertTrue(text.startsWith("在 calculator.py 里实现加减乘除"))
        assertTrue(text.contains("任务：t1 · 第 2 次认领"))
        assertTrue(text.contains("attemptId：att-2"))
        assertTrue(text.contains("租约：600 秒"))
        assertTrue(text.contains("分支：在 task/t1 分支上干活"))
        assertTrue(text.contains("文件范围：未指定（整个项目家）"))
        assertTrue(text.contains("验收标准：未指定（按任务目标验收）"))
        // 租约时长取自 RoomTaskStore.LEASE_SECONDS，不是老项目的 90 秒。
        assertEquals(RoomTaskStore.LEASE_SECONDS, packet.leaseSeconds)
        assertEquals(1_700_000_600L, packet.leaseExpiresAt)
    }
}
