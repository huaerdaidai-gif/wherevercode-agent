package wherevercode.agent.core.security

/**
 * ReplayGuard: anti-replay for cross-device seq numbers (stage 2).
 *
 * Ported from the old project's `cross_device_limits.py` ReplayGuard
 * (300s window, thread-safe). The old guard keyed entries by
 * (session, sender) and carried a per-request timestamp; this stage-2
 * contract passes only (seq, now), so ONE guard instance stands for one
 * (session, sender) pair — the caller creates a fresh instance per
 * session/sender, and staleness is judged against the seq watermark:
 *
 * - a seq seen before -> refuse (duplicate);
 * - a seq at or below the highest seq that has already aged out of the
 *   window -> refuse (expired: a replayed old request);
 * - a seq more than [windowSeconds] ahead of the highest seq seen so
 *   far -> refuse (implausibly far in the future);
 * - otherwise accept and record it.
 *
 * Entries older than the window are pruned on each check so the table
 * cannot grow without bound; pruning is safe because a replayed old
 * request still fails the watermark check even after its entry is gone.
 */
class ReplayGuard(private val windowSeconds: Long = 300) {
    private val lock = Any()
    private val seen = LinkedHashMap<Long, Long>() // seq -> recordedAtEpochSeconds
    private var highestSeenSeq: Long = Long.MIN_VALUE
    private var expiredWatermark: Long = Long.MIN_VALUE // seqs <= this are expired

    /**
     * Returns true when the seq is accepted (fresh, in-window, unseen),
     * false when refused (duplicate, expired, or too far ahead).
     * Thread-safe.
     */
    fun checkAndRecord(seq: Long, nowEpochSeconds: Long): Boolean {
        synchronized(lock) {
            // Prune entries older than the window; the watermark remembers
            // the highest pruned seq so replays of pruned seqs still fail.
            val iterator = seen.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (nowEpochSeconds - entry.value > windowSeconds) {
                    if (entry.key > expiredWatermark) expiredWatermark = entry.key
                    iterator.remove()
                }
            }
            if (seq in seen) return false
            if (seq <= expiredWatermark) return false
            if (highestSeenSeq != Long.MIN_VALUE && seq > highestSeenSeq + windowSeconds) {
                return false
            }
            seen[seq] = nowEpochSeconds
            if (seq > highestSeenSeq) highestSeenSeq = seq
            return true
        }
    }

    /** Test/observability helper: how many seqs are currently remembered. */
    fun rememberedCount(): Int = synchronized(lock) { seen.size }
}
