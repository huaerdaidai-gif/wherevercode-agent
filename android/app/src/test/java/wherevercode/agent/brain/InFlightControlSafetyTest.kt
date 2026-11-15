package wherevercode.agent.brain

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import wherevercode.agent.core.device.JvmDeviceAdapter
import wherevercode.agent.core.security.CommandLimits
import wherevercode.agent.core.security.InMemoryAuditLog
import wherevercode.agent.core.workspace.FileWorkspaceAdapter

class InFlightControlSafetyTest {
    @Test fun `pause during approval prevents the real file write after approval returns`() = approvalRace(true)
    @Test fun `stop during approval prevents the real file write after approval returns`() = approvalRace(false)

    private fun approvalRace(pause: Boolean) = runBlocking {
        val root = Files.createTempDirectory("approval-control-race").toFile()
        try {
            val workspace = File(root, "workspace").apply { mkdirs() }
            val adapter = FileWorkspaceAdapter(workspace, File(root, "tasks").apply { mkdirs() })
            val tools = ToolRegistry(JvmDeviceAdapter(listOf(workspace), adapter), adapter,
                CommandLimits(), FakeTaskStore(), InMemoryAuditLog())
            val turn = TurnController()
            val gate = object : ApprovalGate {
                override suspend fun requestApproval(action: Action): Boolean {
                    if (pause) turn.pause() else turn.stop()
                    return true
                }
            }
            val result = tools.executeControlled(Action("write", "write_file", mapOf("path" to "must-not-exist.txt", "content" to "unsafe"), idempotencyKey = "approval-control-race"),
                ToolContext("task", "local", gate, workspace, "local", ""),
                stopped = { turn.snapshot() == TurnControlState.STOPPED },
                paused = { turn.snapshot() == TurnControlState.PAUSED })
            assertFalse(result.ok)
            assertEquals(if (pause) "turn_paused" else "turn_stopped", result.errorCode)
            assertFalse(File(workspace, "must-not-exist.txt").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun `begin does not erase a pause requested after task claim`() {
        val controller = TurnController()
        controller.pause()
        controller.begin { TurnControlState.PAUSED }
        assertEquals(TurnControlState.PAUSED, controller.snapshot())
    }

    @Test fun `concurrent stop during initial durable control read is never downgraded`() {
        val controller = TurnController()
        controller.begin { controller.stop(); TurnControlState.PAUSED }
        assertEquals(TurnControlState.STOPPED, controller.snapshot())
    }
}

