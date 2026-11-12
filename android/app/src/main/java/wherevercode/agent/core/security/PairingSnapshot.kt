package wherevercode.agent.core.security

/**
 * 配对记录的内存快照（V5 修复，2026-11-11）。
 *
 * 根因：Room 默认禁止主线程查询（`TaskDatabase.create()` 不开
 * `allowMainThreadQueries`），而对话路由判定（横幅、发送、打开会话）
 * 跑在 UI 线程。旧代码在 UI 线程直接读 `pairingStore`（Room），
 * Room 抛 `IllegalStateException`，被 `resolveRemoteTarget` 的
 * `catch (_: Exception) { null }` 当成“未配对”吞掉——同一台设备，
 * 测试在后台线程读库能通过，对话在 UI 线程读库永远失败。
 * 快照由后台线程刷新（`refresh` 做 Room I/O），UI 线程只读快照，
 * 不碰 Room。快照是路由判定的读缓存，不是信任来源：真正的信任
 * 状态仍在 Room 里，Hand 握手与后台路径照常直读信任库。
 */
class PairingSnapshotCache {
    @Volatile
    private var records: Map<String, PairingRecord> = emptyMap()

    /**
     * 从信任库全量刷新。做 Room I/O，**必须在后台线程调用**，绝不
     * 在 UI 线程调（否则还是同一个主线程异常）。
     */
    fun refresh(store: PairingTrustStore) {
        records = store.all().associateBy { it.deviceId }
    }

    /** 主线程安全：只读 volatile 内存快照，不碰 Room。 */
    fun load(deviceId: String): PairingRecord? = records[deviceId]
}
