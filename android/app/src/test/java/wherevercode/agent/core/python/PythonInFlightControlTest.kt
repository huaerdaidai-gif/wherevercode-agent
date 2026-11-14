package wherevercode.agent.core.python

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class PythonInFlightControlTest {
    @Test fun `stop terminates an actually running Python process before its later write`() = interrupt("stopped")
    @Test fun `pause quiesces running Python and returns a resumable control outcome`() = interrupt("paused")

    private fun interrupt(kind: String) {
        val root = Files.createTempDirectory("python-live-control").toFile()
        val pool = Executors.newSingleThreadExecutor()
        val requested = AtomicBoolean(false)
        try {
            val script = File(root, "work.py").apply { writeText("""
                import pathlib, time
                pathlib.Path('started').write_text('running')
                for i in range(1000):
                    time.sleep(0.02)
                pathlib.Path('must-not-exist').write_text('continued after control')
            """.trimIndent()) }
            val runtime = ProcessPythonRuntime()
            assertTrue("Test requires the real host Python runtime", runtime.probe().available)
            val future = pool.submit<PythonRunResult> { runtime.run(PythonRunRequest(script = script, cwd = root,
                timeoutMillis = 30_000, control = PythonRunControl(
                    { kind == "stopped" && requested.get() }, { kind == "paused" && requested.get() }))) }
            val readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!File(root, "started").exists() && System.nanoTime() < readyDeadline) Thread.sleep(10)
            assertTrue("The process must really start before control is requested", File(root, "started").exists())
            requested.set(true)
            val result = future.get(5, TimeUnit.SECONDS)
            assertEquals(kind, result.interruption)
            assertFalse(result.timedOut)
            assertNotEquals(0, result.exitCode)
            assertFalse(File(root, "must-not-exist").exists())
        } finally { requested.set(true); pool.shutdownNow(); root.deleteRecursively() }
    }
}
