package wherevercode.agent

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import wherevercode.agent.brain.Action
import wherevercode.agent.brain.ApprovalBroker
import wherevercode.agent.brain.ApprovalGate
import wherevercode.agent.brain.ApprovalPromptFormatter
import wherevercode.agent.brain.ApprovalRecord
import wherevercode.agent.brain.PendingApproval

/**
 * AndroidApprovalGate：把 [ApprovalBroker] 的审批决定接到真实弹窗上。
 *
 * 2026-11-09 修复的竞态/安全问题：
 * 1. **敏感信息**：弹窗只用 [ApprovalPromptFormatter] 展示工具、目标路径/
 *    命令（脱敏截断）与内容长度；不展示文件正文、Key、环境变量、输出。
 * 2. **重复审批**：同一逻辑调用（actionId + idempotencyKey + 参数指纹）
 *    批准一次后不再弹窗；参数变化即新指纹，必须重新批准。
 * 3. **并发审批**：broker 串行化，永远只有一个弹窗；后来者排队，不会被
 *    误判成"用户拒绝"。
 * 4. **Activity 销毁**：`detach()` 会拒绝挂起中的请求、作废已批准决定，
 *    并让 [isApprovalStillValid] 在界面销毁后返回 false——执行方在动手
 *    前复核，堵住"允许后界面被销毁仍在执行"的窗口。
 * 5. **单例**：审批门挂在 Application 上，旋转重建 Activity 不会让引擎
 *    永久持有一个已关闭的旧门（旧实现的 onDestroy→close 就会）。
 *
 * 没有 Activity（后台/启动中/已销毁）时一律立即拒绝，不阻塞、不放行。
 */
class AndroidApprovalGate private constructor() : ApprovalGate {

    private val broker = ApprovalBroker(
        timeoutMillis = ApprovalBroker.DEFAULT_TIMEOUT_MILLIS,
        presenter = { action -> present(action) },
    )

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var activityRef: WeakReference<Activity>? = null

    @Volatile
    private var closed: Boolean = false

    // 以下三个字段只在主线程读写。
    private var pendingDialog: AlertDialog? = null
    private var pendingSettle: ((Boolean) -> Unit)? = null

    /** 由 Activity 在 onCreate / onStart 调用。 */
    fun attach(activity: Activity) {
        if (closed) return
        activityRef = WeakReference(activity)
    }

    /**
     * 由 Activity 在 onDestroy 调用。只解绑当前这一个 Activity（旋转时
     * 新 Activity 已 attach 的情况不受影响），并作废挂起/已批准的授权。
     */
    fun detach(activity: Activity) {
        if (activityRef?.get() !== activity) return
        activityRef = null
        rejectPending("界面已销毁，授权作废")
    }

    /** 拒绝挂起的审批并作废已批准决定（不关闭审批能力，下个界面可继续用）。 */
    fun rejectPending(reason: String) {
        broker.invalidate()
        handler.post { pendingSettle?.invoke(false) }
    }

    /** 审批历史（本进程内，见 [ApprovalBroker.history]）。 */
    fun approvalHistory(): List<ApprovalRecord> = broker.history()

    /** 正在等待用户决定的审批；无等待时为 null（供倒计时条读取）。 */
    fun pendingApproval(): PendingApproval? = broker.pendingApproval()

    /** 本次审批留给用户的决策时长（毫秒）。 */
    fun approvalTimeoutMillis(): Long = broker.timeoutMillis()

    override suspend fun requestApproval(action: Action): Boolean {
        if (closed) return false
        val activity = activityRef?.get() ?: return false
        if (activity.isFinishing || activity.isDestroyed) return false
        return broker.request(action)
    }

    /**
     * 执行前复核：授权必须仍然有效（同一授权世代、界面仍存活）。
     * ToolRegistry 在真正执行前调用；返回 false 时不得执行。
     */
    override fun isApprovalStillValid(action: Action): Boolean {
        if (closed) return false
        val activity = activityRef?.get() ?: return false
        if (activity.isFinishing || activity.isDestroyed) return false
        return broker.isApproved(action)
    }

    /** 永久关闭（进程退出/测试用）。正常 Activity 销毁只应调用 [detach]。 */
    fun close() {
        closed = true
        broker.close()
        handler.post { pendingSettle?.invoke(false) }
    }

    private suspend fun present(action: Action): Boolean =
        suspendCancellableCoroutine { continuation ->
            handler.post {
                val activity = activityRef?.get()
                if (closed || activity == null || activity.isFinishing ||
                    activity.isDestroyed || pendingDialog != null || !continuation.isActive
                ) {
                    if (continuation.isActive) continuation.resume(false)
                    return@post
                }

                var settled = false
                lateinit var dialog: AlertDialog
                val settle: (Boolean) -> Unit = { allowed ->
                    if (!settled) {
                        settled = true
                        pendingDialog = null
                        pendingSettle = null
                        try {
                            if (dialog.isShowing) dialog.dismiss()
                        } catch (_: Exception) {
                            // Dialog may already be gone with the Activity.
                        }
                        val effective = allowed && !closed &&
                            !activity.isFinishing && !activity.isDestroyed &&
                            continuation.isActive
                        if (continuation.isActive) continuation.resume(effective)
                    }
                }

                dialog = AlertDialog.Builder(activity)
                    .setTitle(ApprovalPromptFormatter.title(action))
                    .setMessage(ApprovalPromptFormatter.message(action))
                    .setPositiveButton("允许") { _, _ -> settle(true) }
                    .setNegativeButton("拒绝") { _, _ -> settle(false) }
                    .setOnCancelListener { settle(false) }
                    .create()
                dialog.setOnDismissListener { settle(false) }

                pendingDialog = dialog
                pendingSettle = settle
                continuation.invokeOnCancellation {
                    handler.post {
                        if (!settled) settle(false)
                    }
                }
                try {
                    dialog.show()
                } catch (_: Exception) {
                    settle(false)
                }
            }
        }

    companion object {
        @Volatile
        private var instance: AndroidApprovalGate? = null

        /**
         * 进程级单例。引擎一直持有同一个门，Activity 旋转只做
         * attach/detach，不会出现"旧门已关闭、新门没人用"的永久拒绝。
         */
        fun get(context: Context): AndroidApprovalGate {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val created = AndroidApprovalGate()
                instance = created
                return created
            }
        }

        /** 测试用：清掉单例（生产代码不调用）。 */
        fun resetForTests() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }
    }
}
