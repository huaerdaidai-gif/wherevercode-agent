package wherevercode.agent.core.session

/**
 * DeliveryTracker (M2 S5): the per-(message, member) delivery / read
 * state machine.
 *
 * States (U8), strictly forward, never conflated:
 *   PERSISTED (stored) -> DELIVERED (delivered) -> CLAIMED (agent
 *   picked it up) -> READ (read / confirmed).
 * "Sent" never pretends to be "executed": a member that has not polled
 * stays DELIVERED (waiting to be claimed), and a member that cannot be
 * woken in real time is honestly recorded as pending pickup — never
 * faked as delivered.
 *
 * Delivery rows carry metadata only (messageId, memberId, state,
 * timestamp) — never the body (U11). `markRead` advances only the
 * calling member's own water level; the read level is monotonic and
 * never moves backwards.
 */
class DeliveryTracker(
    private val deliveryDao: SessionDeliveryDao,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    /**
     * Create the delivery rows for a freshly persisted message. The
     * sender's own row starts DELIVERED (they already hold the
     * message); every other member starts PERSISTED (waiting to be
     * picked up). Existing rows are left untouched.
     */
    fun ensureRows(
        messageId: String,
        memberIds: List<String>,
        senderId: String? = null,
    ): List<SessionDeliveryEntity> {
        val now = nowSeconds()
        for (memberId in memberIds.distinct()) {
            if (deliveryDao.get(messageId, memberId) != null) continue
            val state = if (memberId == senderId) {
                DeliveryState.DELIVERED
            } else {
                DeliveryState.PERSISTED
            }
            runCatching {
                deliveryDao.insert(
                    SessionDeliveryEntity(
                        messageId = messageId,
                        memberId = memberId,
                        deliveryState = state.name,
                        updatedAtEpochSeconds = now,
                    ),
                )
            }
        }
        return deliveryDao.listByMessage(messageId)
    }

    fun stateOf(messageId: String, memberId: String): DeliveryState? =
        deliveryDao.get(messageId, memberId)?.state()

    fun listForMessage(messageId: String): List<SessionDeliveryEntity> =
        deliveryDao.listByMessage(messageId)

    /** Rows this member has not read yet (only_unread). */
    fun unreadFor(memberId: String): List<SessionDeliveryEntity> =
        deliveryDao.listUnread(memberId)

    /** Rows this member has not claimed yet (PERSISTED or DELIVERED). */
    fun pendingFor(memberId: String): List<SessionDeliveryEntity> =
        deliveryDao.listUnread(memberId).filter {
            it.state() == DeliveryState.PERSISTED || it.state() == DeliveryState.DELIVERED
        }

    fun markDelivered(messageId: String, memberId: String): DeliveryState? =
        advance(messageId, memberId, DeliveryState.DELIVERED)

    fun markClaimed(messageId: String, memberId: String): DeliveryState? =
        advance(messageId, memberId, DeliveryState.CLAIMED)

    /** Advances only this member's own water level. */
    fun markRead(messageId: String, memberId: String): DeliveryState? =
        advance(messageId, memberId, DeliveryState.READ)

    /**
     * Forward-only advance: a later state wins, an earlier or equal
     * state is a no-op (the water level never moves backwards).
     * Returns the resulting state, or null when no row exists.
     */
    private fun advance(
        messageId: String,
        memberId: String,
        target: DeliveryState,
    ): DeliveryState? {
        val current = deliveryDao.get(messageId, memberId) ?: return null
        val currentState = current.state()
        if (target.ordinal <= currentState.ordinal) return currentState
        val changed = deliveryDao.updateState(
            messageId = messageId,
            memberId = memberId,
            state = target.name,
            now = nowSeconds(),
        )
        return if (changed == 1) target else currentState
    }
}
