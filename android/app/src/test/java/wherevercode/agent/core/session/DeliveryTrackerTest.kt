package wherevercode.agent.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryTrackerTest {
    private val messageId = "66666666-6666-4666-8666-666666666666"

    private fun tracker(): Pair<DeliveryTracker, FakeSessionDeliveryDao> {
        val dao = FakeSessionDeliveryDao()
        return DeliveryTracker(dao) to dao
    }

    @Test
    fun `four states are distinguishable - PERSISTED, DELIVERED, CLAIMED, READ`() {
        val (tracker, _) = tracker()
        tracker.ensureRows(messageId, listOf("user", "muse"), senderId = "user")
        assertEquals(DeliveryState.PERSISTED, tracker.stateOf(messageId, "muse"))
        assertEquals(DeliveryState.DELIVERED, tracker.markDelivered(messageId, "muse"))
        assertEquals(DeliveryState.DELIVERED, tracker.stateOf(messageId, "muse"))
        assertEquals(DeliveryState.CLAIMED, tracker.markClaimed(messageId, "muse"))
        assertEquals(DeliveryState.READ, tracker.markRead(messageId, "muse"))
        assertEquals(DeliveryState.READ, tracker.stateOf(messageId, "muse"))
    }

    @Test
    fun `the local user writing a message is DELIVERED at once`() {
        val (tracker, _) = tracker()
        tracker.ensureRows(messageId, listOf("user", "muse"), senderId = "user")
        assertEquals(DeliveryState.DELIVERED, tracker.stateOf(messageId, "user"))
        assertEquals(DeliveryState.PERSISTED, tracker.stateOf(messageId, "muse"))
    }

    @Test
    fun `mark_read advances only the reader's own water level`() {
        val (tracker, _) = tracker()
        tracker.ensureRows(messageId, listOf("user", "muse"), senderId = "user")
        tracker.markDelivered(messageId, "muse")
        tracker.markRead(messageId, "muse")
        assertEquals(DeliveryState.READ, tracker.stateOf(messageId, "muse"))
        assertEquals(DeliveryState.DELIVERED, tracker.stateOf(messageId, "user"))
    }

    @Test
    fun `only_unread returns only the caller's own unread rows`() {
        val (tracker, _) = tracker()
        tracker.ensureRows(messageId, listOf("user", "muse"), senderId = "user")
        tracker.markRead(messageId, "muse")
        assertTrue(tracker.unreadFor("muse").isEmpty())
        assertEquals(1, tracker.unreadFor("user").size)
        assertEquals(messageId, tracker.unreadFor("user").single().messageId)
    }

    @Test
    fun `the read water level is monotonic and never moves backwards`() {
        val (tracker, _) = tracker()
        tracker.ensureRows(messageId, listOf("user", "muse"), senderId = "user")
        assertEquals(DeliveryState.READ, tracker.markRead(messageId, "muse"))
        assertEquals(DeliveryState.READ, tracker.markDelivered(messageId, "muse"))
        assertEquals(DeliveryState.READ, tracker.markClaimed(messageId, "muse"))
        assertEquals(DeliveryState.READ, tracker.stateOf(messageId, "muse"))
        // No row, no state.
        assertNull(tracker.markRead("missing-message", "muse"))
    }

    @Test
    fun `a cloud member that has not claimed yet stays DELIVERED - never faked as CLAIMED`() {
        val (tracker, _) = tracker()
        tracker.ensureRows(messageId, listOf("user", "cloud-agent"), senderId = "user")
        // Delivered to the cloud member's inbox, but it has not polled.
        assertEquals(DeliveryState.DELIVERED, tracker.markDelivered(messageId, "cloud-agent"))
        assertEquals(DeliveryState.DELIVERED, tracker.stateOf(messageId, "cloud-agent"))
        assertTrue(tracker.stateOf(messageId, "cloud-agent") != DeliveryState.CLAIMED)
        // It is still pending pickup, honestly recorded as such.
        assertEquals(1, tracker.pendingFor("cloud-agent").size)
        // Once it really claims, the state moves.
        assertEquals(DeliveryState.CLAIMED, tracker.markClaimed(messageId, "cloud-agent"))
        assertTrue(tracker.pendingFor("cloud-agent").isEmpty())
    }
}
