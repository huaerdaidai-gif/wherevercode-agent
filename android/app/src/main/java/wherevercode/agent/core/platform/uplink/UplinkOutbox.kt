package wherevercode.agent.core.platform.uplink

import java.util.TreeMap

/**
 * 回流事件缓存队列（断线缓存 + 重连按序补发 + 幂等）。
 *
 * - 键 = (seq, task_id)；seq 来自本地账本、全局单调，补发严格按 seq 升序。
 * - [enqueue] 幂等：同键已在队、或 seq 不高于已入队过的最高水位且不在队（即已确认送达）
 *   一律忽略，不会复活已确认事件；
 * - [ack] 删除已确认事件；
 * - 超过 [maxPending] 时丢最旧并计数（[dropped]），宁可让用户在报告里看到丢失，也不无界吃内存。
 */
class UplinkOutbox(private val maxPending: Int = 10_000) {
    private data class Key(val seq: Long, val taskId: String) : Comparable<Key> {
        override fun compareTo(other: Key): Int {
            val c = seq.compareTo(other.seq)
            return if (c != 0) c else taskId.compareTo(other.taskId)
        }
    }

    private val pending = TreeMap<Key, UplinkEvent>()
    private var highWater = 0L

    @Volatile
    var dropped = 0L
        private set

    @Synchronized
    fun enqueue(event: UplinkEvent): Boolean {
        val key = Key(event.seq, event.taskId)
        if (pending.containsKey(key)) return false
        if (event.seq <= highWater) return false
        highWater = event.seq
        pending[key] = event
        while (pending.size > maxPending) {
            pending.pollFirstEntry()
            dropped++
        }
        return true
    }

    @Synchronized
    fun pendingInOrder(): List<UplinkEvent> = pending.values.toList()

    @Synchronized
    fun ack(taskId: String, seq: Long): Boolean = pending.remove(Key(seq, taskId)) != null

    @Synchronized
    fun size(): Int = pending.size
}
