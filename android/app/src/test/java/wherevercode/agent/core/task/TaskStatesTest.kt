package wherevercode.agent.core.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskStatesTest {

    @Test
    fun `every transition in the table is legal`() {
        for ((from, targets) in TaskStates.TRANSITIONS) {
            for (to in targets) {
                assertTrue("expected $from -> $to to be legal", TaskStates.checkTransition(from, to))
            }
        }
        // Creation may only go to queued.
        assertTrue(TaskStates.checkTransition(null, "queued"))
    }

    @Test
    fun `illegal transitions throw InvalidStateTransition`() {
        assertThrows(TaskStates.InvalidStateTransition::class.java) {
            TaskStates.checkTransition("queued", "working")
        }
        assertThrows(TaskStates.InvalidStateTransition::class.java) {
            TaskStates.checkTransition("blocked", "review")
        }
        assertThrows(TaskStates.InvalidStateTransition::class.java) {
            TaskStates.checkTransition(null, "assigned")
        }
    }

    @Test
    fun `rework and requeued are canonical states with the rework loop`() {
        // P0：返工是显式态，不是审查直接跳回进行中。review → working
        // 这条直边已删（生产里没有任何 review→working 的写操作）。
        assertTrue(TaskStates.CANONICAL_STATES.contains("rework"))
        assertTrue(TaskStates.CANONICAL_STATES.contains("requeued"))
        assertTrue(TaskStates.checkTransition("review", "rework"))
        assertTrue(TaskStates.checkTransition("rework", "assigned"))
        assertThrows(TaskStates.InvalidStateTransition::class.java) {
            TaskStates.checkTransition("review", "working")
        }
        // 租约过期回队 / 死信边。
        assertTrue(TaskStates.checkTransition("assigned", "requeued"))
        assertTrue(TaskStates.checkTransition("working", "requeued"))
        assertTrue(TaskStates.checkTransition("assigned", "failed"))
        assertTrue(TaskStates.checkTransition("requeued", "assigned"))
        assertTrue(TaskStates.checkTransition("requeued", "cancelled"))
        assertTrue(TaskStates.checkTransition("rework", "cancelled"))
    }

    @Test
    fun `isClaimable admits only queued, requeued and rework`() {
        assertTrue(TaskStates.isClaimable("queued"))
        assertTrue(TaskStates.isClaimable("requeued"))
        assertTrue(TaskStates.isClaimable("rework"))
        assertFalse(TaskStates.isClaimable("assigned"))
        assertFalse(TaskStates.isClaimable("working"))
        assertFalse(TaskStates.isClaimable("review"))
        assertFalse(TaskStates.isClaimable("blocked"))
        assertFalse(TaskStates.isClaimable("completed"))
        assertFalse(TaskStates.isClaimable("failed"))
        assertFalse(TaskStates.isClaimable("cancelled"))
    }

    @Test
    fun `terminal states have no outgoing edges`() {
        for (terminal in TaskStates.TERMINAL_STATES) {
            for (target in TaskStates.CANONICAL_STATES) {
                assertThrows(TaskStates.InvalidStateTransition::class.java) {
                    TaskStates.checkTransition(terminal, target)
                }
            }
            assertTrue(TaskStates.isTerminal(terminal))
        }
        assertFalse(TaskStates.isTerminal("blocked"))
        assertFalse(TaskStates.isTerminal("review"))
        assertEquals(setOf("completed", "failed", "cancelled"), TaskStates.TERMINAL_STATES)
    }

    @Test
    fun `unknown from-state throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            TaskStates.checkTransition("not-a-state", "queued")
        }
    }

    @Test
    fun `canonical maps the three ported systems`() {
        assertEquals("queued", TaskStates.canonical("task_assignment", "waiting").state)
        assertEquals("queued", TaskStates.canonical("task_assignment", "ready").state)
        assertEquals("cancelled", TaskStates.canonical("task_assignment", "stopped").state)
        assertEquals("working", TaskStates.canonical("conversation_task", "running").state)
        assertEquals("working", TaskStates.canonical("execution_host", "running").state)
        assertEquals("completed", TaskStates.canonical("execution_host", "completed").state)
        // needs_attention is an independent flag, not a state.
        assertFalse(TaskStates.canonical("task_assignment", "blocked").needsAttention)
    }

    @Test
    fun `canonical refuses unknown systems and states instead of guessing`() {
        assertThrows(IllegalArgumentException::class.java) {
            TaskStates.canonical("cloud_agent", "queued")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaskStates.canonical("task_assignment", "nope")
        }
    }

    @Test
    fun `contract fields are the 17 old record fields`() {
        assertEquals(
            listOf(
                "task_id", "project_id", "session_id", "title", "description",
                "state", "assigned_agent_id", "parent_task_id", "dependencies",
                "priority", "created_at", "started_at", "finished_at",
                "updated_at", "result", "error", "version",
            ),
            TaskContract.RECORD_FIELDS,
        )
        // Scheduler-owned fields are never client-writable.
        assertTrue(TaskContract.SCHEDULER_OWNED_FIELDS.contains("task_id"))
        assertTrue(TaskContract.SCHEDULER_OWNED_FIELDS.contains("version"))
        assertFalse(TaskContract.MUTABLE_FIELDS.contains("task_id"))
        assertFalse(TaskContract.MUTABLE_FIELDS.contains("version"))
    }

    @Test
    fun `normalizeRecordFields drops unknown fields`() {
        val normalized = TaskContract.normalizeRecordFields(
            mapOf("task_id" to "t1", "title" to "x", "surprise" to 1, "version" to 3),
        )
        assertEquals(mapOf("task_id" to "t1", "title" to "x", "version" to 3), normalized)
    }
}
