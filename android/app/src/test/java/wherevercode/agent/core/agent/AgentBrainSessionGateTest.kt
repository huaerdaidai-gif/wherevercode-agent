package wherevercode.agent.core.agent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentBrainSessionGateTest {
    @Test
    fun `concurrent turns for same brain serialize and stale release cannot end next turn`() {
        val brainId = "model-api-shared"
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val attempts = (1..2).map {
                pool.submit<AgentBrainSessionGate.Lease?> {
                    ready.countDown()
                    start.await()
                    AgentBrainSessionGate.tryAcquire(brainId)
                }
            }
            ready.await()
            start.countDown()

            val concurrentResults = attempts.map { it.get() }
            assertEquals(1, concurrentResults.count { it != null })
            assertEquals(1, concurrentResults.count { it == null })
            val firstLease = concurrentResults.filterNotNull().single()
            assertTrue(firstLease.brainId == brainId)
            assertTrue(AgentBrainSessionGate.isHeldForTests())

            // The rejected second send can retry after the active turn ends.
            AgentBrainSessionGate.release(firstLease)
            val secondLease = AgentBrainSessionGate.tryAcquire(brainId)
                ?: throw AssertionError("second turn should acquire after first turn ends")
            assertTrue(AgentBrainSessionGate.isHeldForTests())

            // A delayed cleanup from turn one cannot unregister/release turn two.
            AgentBrainSessionGate.release(firstLease)
            assertTrue(AgentBrainSessionGate.isHeldForTests())

            AgentBrainSessionGate.release(secondLease)
            assertFalse(AgentBrainSessionGate.isHeldForTests())
        } finally {
            pool.shutdownNow()
        }
    }
}
