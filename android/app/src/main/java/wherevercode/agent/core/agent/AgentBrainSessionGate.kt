package wherevercode.agent.core.agent

import java.util.UUID

/**
 * Serializes Agent turns that temporarily use the process-wide brain adapter
 * registry. The registry cannot identify a chat session, so only one
 * synthetic-config turn may own it at a time. Competing turns fail fast and
 * can be retried after the owner finishes.
 */
object AgentBrainSessionGate {
    class Lease internal constructor(
        val brainId: String,
        internal val token: String,
    )

    private var ownerToken: String? = null

    @Synchronized
    fun tryAcquire(brainId: String): Lease? {
        if (ownerToken != null) return null
        return Lease(brainId, UUID.randomUUID().toString()).also { ownerToken = it.token }
    }

    @Synchronized
    fun release(lease: Lease) {
        if (ownerToken == lease.token) ownerToken = null
    }

    @Synchronized
    fun isOwner(lease: Lease): Boolean = ownerToken == lease.token

    @Synchronized
    fun isHeldForTests(): Boolean = ownerToken != null
}
