package wherevercode.agent.core.security

import wherevercode.agent.core.transport.DiscoveredPeer

/**
 * 首次连接配对（阶段 2 B 路，审计 §6.3 步骤②、§2 #18）。
 *
 * 铁律（接手手册，审计 §2 #18）：**同账号默认放行已否决，授权是每台设备
 * 本机点一次**。目标设备必须本机点一次确认才建立信任，没有例外——
 * 账号相同、同一局域网、此前配对过其他设备，都不能自动放行。
 *
 * 配对流程是显式状态机：待确认 → 已确认 / 已拒绝；已确认 → 已撤销。
 * 拒绝与撤销都要有中文原因。状态非法转移抛 [IllegalStateException]（中文说明）。
 *
 * 信任的持久化：[PairingTrustStore] 是本模块的存储口；生产实现由协调员
 * 接到 A 路的 AuthorizationStore（见回报），本文件不假设其具体 API。
 * 配对记录里的 [PairingRecord.peerPublicKey] 是对端 X25519 公钥，
 * 在配对确认后、已 pinning 的 TLS 通道上交换得到（交换细节由 Hand 服务端实现，
 * 见回报未问题）；未交换前为 null，此时 [HandClient] 无法建立会话。
 */
enum class PairingState {
    /** 已发起、等目标设备本机确认。 */
    PENDING,

    /** 目标设备本机已确认，建立信任。 */
    CONFIRMED,

    /** 目标设备本机已拒绝，或本机主动拒绝。 */
    REJECTED,

    /** 已确认后被撤销信任。 */
    REVOKED,
}

/** 配对记录。拒绝 / 撤销时 [reason] 必填中文原因。 */
data class PairingRecord(
    val deviceId: String,
    val displayName: String,
    val fingerprintSha256: String,
    /** 对端 X25519 公钥（配对确认后交换得到；未交换前为 null）。 */
    val peerPublicKey: ByteArray? = null,
    val state: PairingState,
    val reason: String? = null,
    val updatedAtEpochSeconds: Long,
)

/** 配对信任的存储口。生产实现接到 AuthorizationStore；测试用内存实现。 */
interface PairingTrustStore {
    fun load(deviceId: String): PairingRecord?
    fun save(record: PairingRecord)
    fun remove(deviceId: String)
    fun all(): List<PairingRecord>
}

/** 内存版存储（测试与无持久化场景用；不跨进程、不跨重启）。 */
class InMemoryPairingTrustStore : PairingTrustStore {
    private val records = mutableMapOf<String, PairingRecord>()

    override fun load(deviceId: String): PairingRecord? = records[deviceId]

    override fun save(record: PairingRecord) {
        records[record.deviceId] = record
    }

    override fun remove(deviceId: String) {
        records.remove(deviceId)
    }

    override fun all(): List<PairingRecord> = records.values.toList()
}

/**
 * 迁移式配对存储（V5 加设备修复，2026-11-11）。
 *
 * 根因：MainActivity 的 pairingStore 原先是 `by lazy` 的二选一缓存——
 * 首次访问时大脑若未建好，就把 [InMemoryPairingTrustStore] 缓存下来，
 * 之后整进程都用内存：配对成功当时能用，App 重启后记录全丢，设备
 * 变“未配对（不可用）”，用户眼里就是“添加设备不能用”。
 *
 * 本实现不缓存后端选择：每次操作都重新解析——持久化（Room，大脑建
 * 好后由 [resolvePersistent] 给出）可用就用它；首次切到持久化时，
 * 把大脑建好前已写进内存的记录一次性迁过去（已在持久化的不覆盖）；
 * 持久化不可用时仍写内存，不丢配对。迁移与读写共用同一把锁，迁
 * 移窗口内的写入不会丢。
 */
class MigratingPairingTrustStore(
    private val memory: PairingTrustStore = InMemoryPairingTrustStore(),
    private val resolvePersistent: () -> PairingTrustStore?,
) : PairingTrustStore {
    private val lock = Any()
    private var migrated = false

    /** 须在 lock 内调用：解析当前后端，首次切到持久化时迁内存记录。 */
    private fun currentLocked(): PairingTrustStore {
        val persistent = resolvePersistent() ?: return memory
        if (!migrated) {
            migrated = true
            memory.all().forEach { record ->
                if (persistent.load(record.deviceId) == null) {
                    persistent.save(record)
                }
            }
        }
        return persistent
    }

    override fun load(deviceId: String): PairingRecord? =
        synchronized(lock) { currentLocked().load(deviceId) }

    override fun save(record: PairingRecord) {
        synchronized(lock) { currentLocked().save(record) }
    }

    override fun remove(deviceId: String) {
        synchronized(lock) { currentLocked().remove(deviceId) }
    }

    override fun all(): List<PairingRecord> =
        synchronized(lock) { currentLocked().all() }
}

/**
 * [wherevercode.agent.core.mcp.HandClient] 用来判定对端是否可信的口。
 * 由 [PairingManager] 实现；测试可用测试替身。
 */
interface PeerTrustChecker {
    fun isTrusted(peer: DiscoveredPeer): Boolean

    /** 不可信时的中文可操作原因；可信时返回 null。 */
    fun trustFailureReason(peer: DiscoveredPeer): String?

    /** 配对时交换到的对端 X25519 公钥；未交换返回 null。 */
    fun peerPublicKey(deviceId: String): ByteArray?
}

class PairingManager(
    private val store: PairingTrustStore,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : PeerTrustChecker {

    companion object {
        fun stateText(state: PairingState): String = when (state) {
            PairingState.PENDING -> "待确认"
            PairingState.CONFIRMED -> "已确认"
            PairingState.REJECTED -> "已拒绝"
            PairingState.REVOKED -> "已撤销"
        }
    }

    /**
     * 发起配对：生成一条待确认记录，UI 把 [DiscoveredPeer.fingerprintSha256]
     * 显示给用户，让用户到目标设备上核对同一指纹后点确认。
     *
     * 幂等：已确认且指纹一致时直接返回已确认记录，不重复弹窗；
     * 已确认但指纹变了（设备重装 / 换了密钥），旧信任作废，生成新的待确认记录，
     * 必须重新核对。
     */
    fun requestPairing(peer: DiscoveredPeer): PairingRecord {
        val existing = store.load(peer.deviceId)
        if (existing != null && existing.state == PairingState.CONFIRMED &&
            existing.fingerprintSha256.equals(peer.fingerprintSha256, ignoreCase = true)
        ) {
            return existing
        }
        val record = PairingRecord(
            deviceId = peer.deviceId,
            displayName = peer.displayName,
            fingerprintSha256 = peer.fingerprintSha256.lowercase(),
            peerPublicKey = null,
            state = PairingState.PENDING,
            reason = null,
            updatedAtEpochSeconds = nowEpochSeconds(),
        )
        store.save(record)
        return record
    }

    /**
     * 目标设备本机点一次确认后调用：待确认 → 已确认，建立信任。
     * [peerPublicKey] 是配对时在已 pinning 的通道上交换到的对端 X25519 公钥，
     * 有就存下，没有就保持原值（仍为 null 时 HandClient 无法建会话）。
     */
    fun confirm(deviceId: String, peerPublicKey: ByteArray? = null): PairingRecord {
        val record = store.load(deviceId)
            ?: throw IllegalStateException("没有待确认的配对请求：请先在发现列表里点“配对”")
        check(record.state == PairingState.PENDING) {
            "该设备配对状态是“${stateText(record.state)}”，不是待确认，无法确认"
        }
        val confirmed = record.copy(
            state = PairingState.CONFIRMED,
            reason = null,
            peerPublicKey = peerPublicKey ?: record.peerPublicKey,
            updatedAtEpochSeconds = nowEpochSeconds(),
        )
        store.save(confirmed)
        return confirmed
    }

    /** 目标设备本机拒绝，或本机主动拒绝：待确认 → 已拒绝。 */
    fun reject(
        deviceId: String,
        reason: String = "对方在目标设备上拒绝了配对请求",
    ): PairingRecord {
        val record = store.load(deviceId)
            ?: throw IllegalStateException("没有待确认的配对请求：请先在发现列表里点“配对”")
        check(record.state == PairingState.PENDING) {
            "该设备配对状态是“${stateText(record.state)}”，不是待确认，无法拒绝"
        }
        val rejected = record.copy(
            state = PairingState.REJECTED,
            reason = reason,
            updatedAtEpochSeconds = nowEpochSeconds(),
        )
        store.save(rejected)
        return rejected
    }

    /**
     * 撤销信任：已确认 → 已撤销。撤销后该设备立即不可信，
     * 须重新走完整配对流程才能恢复。
     */
    fun revoke(deviceId: String, reason: String): PairingRecord {
        val record = store.load(deviceId)
            ?: throw IllegalStateException("没有该设备的配对记录，无法撤销")
        check(record.state == PairingState.CONFIRMED) {
            "该设备配对状态是“${stateText(record.state)}”，只有已确认的配对才能撤销"
        }
        require(reason.isNotBlank()) { "撤销必须给出中文原因" }
        val revoked = record.copy(
            state = PairingState.REVOKED,
            reason = reason,
            updatedAtEpochSeconds = nowEpochSeconds(),
        )
        store.save(revoked)
        return revoked
    }

    override fun isTrusted(peer: DiscoveredPeer): Boolean {
        val record = store.load(peer.deviceId) ?: return false
        if (record.state != PairingState.CONFIRMED) return false
        return record.fingerprintSha256.equals(peer.fingerprintSha256, ignoreCase = true)
    }

    override fun trustFailureReason(peer: DiscoveredPeer): String? {
        val record = store.load(peer.deviceId)
            ?: return "未配对：请先在目标设备上点一次确认，完成配对后再试"
        if (record.state != PairingState.CONFIRMED) {
            return "配对未完成（当前状态：${stateText(record.state)}）" +
                if (record.state == PairingState.REJECTED || record.state == PairingState.REVOKED) {
                    "，原因：${record.reason ?: "未说明"}。请重新发起配对，并在目标设备上确认。"
                } else {
                    "：请在目标设备上确认配对。"
                }
        }
        if (!record.fingerprintSha256.equals(peer.fingerprintSha256, ignoreCase = true)) {
            return "设备指纹已变化（可能重装或换了密钥）：请删除旧配对并重新核对指纹"
        }
        return null
    }

    override fun peerPublicKey(deviceId: String): ByteArray? =
        store.load(deviceId)?.peerPublicKey

    /**
     * 同账号默认放行已否决：本函数永远抛错，没有任何调用方能绕过。
     * 每台设备都必须本机点一次确认，账号相同也不能自动信任。
     */
    fun requestSameAccountAutoTrust(deviceId: String): Nothing {
        throw IllegalStateException(
            "同账号默认放行已否决：每台设备都必须本机点一次确认，" +
                "账号相同也不能自动信任（设备 $deviceId）",
        )
    }
}
