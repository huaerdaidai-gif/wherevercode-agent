package wherevercode.agent.brain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import wherevercode.agent.core.security.SecretRedactor

/**
 * 审批决定的终态。超时与主动拒绝必须可区分（阶段 3，U4）：
 * 超时是用户 60 秒内没有点，主动拒绝是用户点了拒绝；两者在界面与
 * 任务页审批历史里分开显示。无界面/已关闭/授权世代已变都不是用户
 * 的决定，记为 [DENIED_UNAVAILABLE]。
 */
enum class ApprovalOutcome {
    APPROVED,
    DENIED_USER,
    DENIED_TIMEOUT,
    DENIED_UNAVAILABLE,
}

/**
 * 一条审批历史记录（本进程内，最多保留 [ApprovalBroker.MAX_HISTORY] 条）。
 * 只记工具、目标摘要（路径/命令首词/任务目标，已脱敏截断）与决定，
 * 不记文件正文、密钥、环境变量与命令输出。
 */
data class ApprovalRecord(
    val actionId: String,
    val tool: String,
    val target: String?,
    val outcome: ApprovalOutcome,
    /** 去重命中：本次未弹窗，沿用已批准的决定。 */
    val cached: Boolean = false,
    val atEpochSeconds: Long,
)

/** 正在等待用户决定的审批（供聊天页/任务页倒计时条读取）。 */
data class PendingApproval(
    val actionId: String,
    val tool: String,
    val target: String?,
    /** 用户必须在此之前决定（epoch 毫秒）；超时即拒绝。 */
    val deadlineEpochMillis: Long,
)

/**
 * 2026-11-09 审批安全：与 UI 无关的审批协调器。
 *
 * 负责四件容易出错、且与 Android 生命周期无关的事：
 *
 * 1. **串行化**：同一时刻只允许一个审批弹窗。并发请求在互斥锁上排队，
 *    不会出现两个弹窗叠加、也不会把后来的请求直接判成"用户拒绝"。
 * 2. **去重**：同一个逻辑调用（actionId + idempotencyKey + 参数指纹）
 *    在已获批准后不再重复弹窗；参数一变就是新指纹，必须重新审批。
 * 3. **超时即拒绝**：每次展示最多等 [timeoutMillis]；超时返回 false。
 *    排队等待时间不计入该超时（用户看到弹窗后仍有完整决策时间）。
 * 4. **授权可撤销**：`invalidate()`（Activity 销毁、用户停止、进程退出）
 *    会让所有已批准决定立即失效；执行方在真正动手前调用
 *    [isApproved] 复核，堵住"点了允许之后界面被销毁"的竞态。
 *
 * 纯 Kotlin / 协程实现，不依赖 Android，可直接单测。
 */
class ApprovalBroker(
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val presenter: suspend (Action) -> Boolean,
) {
    companion object {
        const val DEFAULT_TIMEOUT_MILLIS: Long = 60_000

        /** 审批历史保留上限（本进程内，超出丢最旧的）。 */
        const val MAX_HISTORY: Int = 50
    }

    private val lock = Any()
    private val mutex = Mutex()

    /** key -> 授权时的 epoch；epoch 变了即视为失效。 */
    private val approved = HashMap<String, Long>()
    private var epoch: Long = 0

    /** 审批历史（本进程内，按时间先后；只记决定，不记敏感内容）。 */
    private val history = ArrayDeque<ApprovalRecord>()

    /** 正在等待用户决定的审批；无等待时为 null。 */
    @Volatile
    private var pending: PendingApproval? = null

    @Volatile
    private var closed: Boolean = false

    /** 审批历史快照（最旧在前）。 */
    fun history(): List<ApprovalRecord> = synchronized(lock) { history.toList() }

    /** 正在等待用户决定的审批；无等待时返回 null。 */
    fun pendingApproval(): PendingApproval? = pending

    /** 本次审批留给用户的决策时长（毫秒）。 */
    fun timeoutMillis(): Long = timeoutMillis

    /** 审批身份：工具 + 目标参数 + 本次调用的幂等键。 */
    fun keyOf(action: Action): String =
        "${action.actionId}|${action.idempotencyKey}|${ApprovalFingerprint.of(action)}"

    /**
     * 请求一次审批。返回 true 才允许执行。超时、关闭、UI 不可用都返回 false。
     * CancellationException 会向上传播（调用方被取消时不得把取消当同意）。
     *
     * 每一次决定都记入 [history]（阶段 3，U4）：批准 / 用户拒绝 /
     * 超时拒绝 / 无界面拒绝分开记；去重命中记一次“已批准（缓存）”。
     * 弹窗展示期间 [pendingApproval] 可读，供界面显示剩余秒数倒计时。
     */
    suspend fun request(action: Action): Boolean {
        if (closed) {
            record(action, ApprovalOutcome.DENIED_UNAVAILABLE)
            return false
        }
        val key = keyOf(action)
        return mutex.withLock {
            val epochAtStart = synchronized(lock) {
                if (closed) {
                    recordLocked(action, ApprovalOutcome.DENIED_UNAVAILABLE)
                    return@withLock false
                }
                if (approved[key] == epoch) {
                    recordLocked(action, ApprovalOutcome.APPROVED, cached = true)
                    return@withLock true
                }
                epoch
            }
            // 弹窗展示期间登记待审批（含截止时间），供界面倒计时读取。
            pending = PendingApproval(
                actionId = action.actionId,
                tool = action.tool,
                target = targetOf(action),
                deadlineEpochMillis = System.currentTimeMillis() + timeoutMillis,
            )
            var timedOut = false
            val decision = try {
                val presented = withTimeoutOrNull(timeoutMillis) { presenter(action) }
                timedOut = presented == null
                presented ?: false
            } catch (e: CancellationException) {
                pending = null
                throw e
            } catch (_: Exception) {
                // UI 抛异常（Activity 已销毁等）：默认拒绝，绝不默认放行。
                record(action, ApprovalOutcome.DENIED_UNAVAILABLE)
                pending = null
                return@withLock false
            } finally {
                pending = null
            }
            if (timedOut) {
                record(action, ApprovalOutcome.DENIED_TIMEOUT)
                return@withLock false
            }
            if (!decision) {
                record(action, ApprovalOutcome.DENIED_USER)
                return@withLock false
            }
            val effective = synchronized(lock) {
                !closed && epoch == epochAtStart && decision
            }
            if (effective) {
                synchronized(lock) {
                    if (epoch == epochAtStart && !closed) approved[key] = epoch
                }
                record(action, ApprovalOutcome.APPROVED)
            } else {
                // 点了允许，但其间授权世代已变（界面销毁/用户停止）：
                // 决定作废，不是用户的拒绝。
                record(action, ApprovalOutcome.DENIED_UNAVAILABLE)
            }
            effective
        }
    }

    /** 目标摘要：只取路径 / 命令首词 / 任务目标，已脱敏截断；绝不含正文与密钥。 */
    private fun targetOf(action: Action): String? {
        val path = (action.args["path"] as? String)?.takeIf { it.isNotBlank() }
        if (path != null) return SecretRedactor.redact(path, 160)
        val argv = action.args["argv"]
        val first = when (argv) {
            is List<*> -> argv.firstOrNull()?.toString()
            is String -> argv
            else -> null
        }?.takeIf { it.isNotBlank() }
        if (first != null) return SecretRedactor.redact(first, 160)
        if (action.tool == "dispatch_task") {
            val goal = (action.args["goal"] as? String)?.takeIf { it.isNotBlank() }
            if (goal != null) return SecretRedactor.redact(goal.take(80), 160)
        }
        return null
    }

    private fun record(action: Action, outcome: ApprovalOutcome, cached: Boolean = false) {
        synchronized(lock) { recordLocked(action, outcome, cached) }
    }

    private fun recordLocked(action: Action, outcome: ApprovalOutcome, cached: Boolean = false) {
        history.addLast(
            ApprovalRecord(
                actionId = action.actionId,
                tool = action.tool,
                target = targetOf(action),
                outcome = outcome,
                cached = cached,
                atEpochSeconds = System.currentTimeMillis() / 1000,
            ),
        )
        while (history.size > MAX_HISTORY) history.removeFirst()
    }

    /**
     * 执行前的第二道检查：这个操作在"当前授权世代"里是否仍被批准。
     * Activity 销毁 / 用户停止 / 关闭都会让旧批准失效。
     */
    fun isApproved(action: Action): Boolean = synchronized(lock) {
        !closed && approved[keyOf(action)] == epoch
    }

    /** 让所有已批准决定立即失效（保留服务，不关闭审批能力）。 */
    fun invalidate() {
        synchronized(lock) {
            epoch += 1
            approved.clear()
        }
    }

    /** 永久关闭：后续请求一律拒绝，已批准决定作废。 */
    fun close() {
        synchronized(lock) {
            closed = true
            epoch += 1
            approved.clear()
        }
    }

    fun isClosed(): Boolean = closed

    fun approvalCount(): Int = synchronized(lock) { approved.size }
}
