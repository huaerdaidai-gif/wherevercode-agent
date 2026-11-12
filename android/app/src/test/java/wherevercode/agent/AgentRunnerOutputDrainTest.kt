package wherevercode.agent

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AgentRunnerOutputDrainTest {

    @Test
    fun `successful process waits for queued output before returning`() {
        val context = RuntimeEnvironment.getApplication()
        val binDir = File(context.filesDir, "node/bin").apply { mkdirs() }
        val binary = File(binDir, "codebuddy")
        val exitedMarker = File(context.filesDir, "cli-exited")
        binary.writeText(
            """#!/bin/sh
printf 'first line\n'
printf 'final sentinel\n'
printf done > '${exitedMarker.absolutePath.replace("'", "'\\''")}'
sleep 0.5
""",
        )
        assertTrue("test CLI script must be executable", binary.setExecutable(true))

        val callbackEntered = CountDownLatch(1)
        val releaseReader = CountDownLatch(1)
        var result: AgentRunResult? = null
        val runnerThread = Thread {
            result = AgentRunner(context, RuntimeEnv(context)).runPromptDetailed(
                prompt = "capture output",
                binary = "codebuddy",
                onRawLine = { line ->
                    if (line == "first line") {
                        callbackEntered.countDown()
                        releaseReader.await(5, TimeUnit.SECONDS)
                    }
                },
            )
        }

        runnerThread.start()
        assertTrue("CLI output reader did not start", callbackEntered.await(5, TimeUnit.SECONDS))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!exitedMarker.exists() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("CLI script did not reach its exit marker", exitedMarker.exists())
        Thread.sleep(700) // allow the child process to exit while the reader callback is held

        runnerThread.join(500)
        assertTrue("result must wait for its output reader", runnerThread.isAlive)
        releaseReader.countDown()
        runnerThread.join(5_000)

        assertTrue("AgentRunner did not finish", !runnerThread.isAlive)
        assertNotNull(result)
        assertTrue(result is AgentRunResult.Success)
        val success = result as AgentRunResult.Success
        assertTrue(success.lines.contains("first line"))
        assertTrue(success.lines.contains("final sentinel"))
        assertTrue(success.text.contains("final sentinel"))
    }
}
