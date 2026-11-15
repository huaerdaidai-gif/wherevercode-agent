package wherevercode.agent.core.mcp

/**
 * T6 结构化结果协议 v1（2026-11-15）：远端任务终态由协议字段决定，
 * 不再猜测正文。
 *
 * 承载方式：全部是 `cross_device_result` 帧 args 的**可选键**（旧 Hand
 * 不发送，调用方走 legacy 兜底并显式标记来源）。不新增帧类型、不改
 * 信封版本。error 帧（`cross_device_error`）本身已结构化（code 就是
 * 机器可读错误码），App 侧按 code 精确映射终态。
 *
 * 本文件只放纯常量与纯函数：不依赖 Android、不依赖 TaskStore，可在
 * 纯 JVM 单测中直接验证（`RemoteResultProtocolTest`）。
 *
 * 状态映射（TaskStates 禁止扩集，落点全走既有合法边）：
 * - succeeded → review → completed（流水线 hold 时停 review）
 * - failed   → failed（error 带 [error_code=…] 前缀）
 * - cancelled → cancelled
 * - 断线/客户端读超时且恢复失败 → failed + remote_result_unknown
 *  （文案必须含“结果未知”，绝不写成确定失败）
 * - 旧协议（无 status）→ RemoteMemberVerdictClassifier 兜底 +
 *   legacy_verdict_fallback 显式标记
 * - status 非法/自相矛盾 → invalid_protocol_status 保守失败，
 *   绝不降级成功
 *
 * 设计文档：T6-S2.md（Muse 2026-11-15 批复通过）。
 */
object RemoteResultProtocol {
    /** cross_device_result args 新增键（全部可选；旧 Hand 缺省）。 */
    const val ARG_STATUS: String = "status"
    const val ARG_ERROR_CODE: String = "error_code"
    const val ARG_ERROR_MESSAGE: String = "error_message"
    const val ARG_EXIT_CODE: String = "exit_code"

    /** status 封闭取值（TaskStates 之外的第二状态空间，只描述远端执行器终态）。 */
    const val STATUS_SUCCEEDED: String = "succeeded"
    const val STATUS_FAILED: String = "failed"
    const val STATUS_CANCELLED: String = "cancelled"
    val KNOWN_STATUSES: Set<String> = setOf(STATUS_SUCCEEDED, STATUS_FAILED, STATUS_CANCELLED)

    // —— error_code 代码表（App 侧统一口径；Hand 侧对应值见 T6-S2 §8）——

    /** Agent 退出码非 0（新 Hand 精确码；迁移期旧 Hand 仍报 agent_not_found，同样按失败落账）。 */
    const val CODE_AGENT_EXIT_NONZERO: String = "agent_exit_nonzero"

    /** Agent 被信号终止（非控制停止）。 */
    const val CODE_AGENT_SIGNAL_KILLED: String = "agent_signal_killed"

    /** 服务端确认超时并已杀进程：确定失败（与“结果未知”严格区分）。 */
    const val CODE_AGENT_TIMEOUT: String = "agent_timeout"

    /** 客户端断线/读超时且交付恢复失败：本次执行结果未知（远端可能仍在执行或已完成）。 */
    const val CODE_REMOTE_RESULT_UNKNOWN: String = "remote_result_unknown"

    /** App 执行器链路异常（execute 的非取消 catch）。 */
    const val CODE_REMOTE_EXECUTOR_ERROR: String = "remote_executor_error"

    /** 旧协议兜底标记：Result 无 status，判定来自正文分类器，不得伪装成结构化结果。 */
    const val CODE_LEGACY_VERDICT_FALLBACK: String = "legacy_verdict_fallback"

    /** 回包 status 非法或自相矛盾：保守失败，绝不降级成功。 */
    const val CODE_INVALID_PROTOCOL_STATUS: String = "invalid_protocol_status"

    /** 客户端侧产物完整性校验重取后仍不一致（与 Hand 的 artifact_* 终态码区分开）。 */
    const val CODE_ARTIFACT_VERIFY_FAILED: String = "artifact_verify_failed"

    /** 旧协议兜底的来源标记（与 error_code 一起进 error 前缀）。 */
    const val SOURCE_FALLBACK: String = "fallback"

    /** recordTaskEvent 审计 kind：本次终态由旧协议兜底判定（非状态事件）。 */
    const val EVENT_REMOTE_RESULT_FALLBACK: String = "remote_result_fallback"

    /** recordTaskEvent 审计 kind：本次终态为“结果未知”（保留原始歧义说明）。 */
    const val EVENT_REMOTE_RESULT_UNKNOWN: String = "remote_result_unknown"

    fun isKnownResultStatus(status: String?): Boolean =
        status != null && status in KNOWN_STATUSES

    /**
     * 纯文本兜底路径的机器可读前缀（TaskStore.error 没有结构化通道时）。
     * 由常量生成；调用方禁止手写 `[error_code=…]` 字符串。
     * code 为空时返回空前缀，纯普通文案不加标记。
     */
    fun errorPrefix(errorCode: String?): String =
        if (errorCode.isNullOrBlank()) "" else "[error_code=$errorCode] "

    /** error 列统一格式：前缀 + 文案。 */
    fun errorText(errorCode: String?, message: String): String =
        errorPrefix(errorCode) + message

    /** 来源标记前缀（旧协议兜底专用，与 error code 双标记）。 */
    fun sourcePrefix(source: String): String = "[source=$source] "

    /**
     * 结构化终态判定（纯函数）。规则（T6-S2 §4/§6）：
     * - status 缺失/空白 → [StructuredVerdict.NeedsLegacyFallback]（调用方走正文分类器并显式标记）；
     * - status 不在封闭集 → [StructuredVerdict.Invalid]（保守失败）；
     * - succeeded 却带 error_code → 自相矛盾 → [StructuredVerdict.Invalid]；
     * - failed → code 取帧 error_code，缺省 [CODE_AGENT_EXIT_NONZERO]；
     *   message 取帧 error_message，缺省用正文（展示用）；
     * - cancelled → 透传帧 error_code（可空）。
     */
    fun resolveStructured(
        status: String?,
        errorCode: String?,
        errorMessage: String?,
        output: String,
    ): StructuredVerdict {
        if (status.isNullOrBlank()) return StructuredVerdict.NeedsLegacyFallback
        if (status !in KNOWN_STATUSES) return StructuredVerdict.Invalid(status)
        return when (status) {
            STATUS_SUCCEEDED ->
                if (errorCode.isNullOrBlank()) {
                    StructuredVerdict.Succeeded
                } else {
                    // status=succeeded 却带 error_code：协议自相矛盾，按非法处理，
                    // 不猜哪个字段是真的。
                    StructuredVerdict.Invalid(status)
                }
            STATUS_FAILED -> StructuredVerdict.Failed(
                code = errorCode?.takeIf { it.isNotBlank() } ?: CODE_AGENT_EXIT_NONZERO,
                message = errorMessage?.takeIf { it.isNotBlank() } ?: output,
            )
            STATUS_CANCELLED -> StructuredVerdict.Cancelled(errorCode?.takeIf { it.isNotBlank() })
            else -> StructuredVerdict.Invalid(status) // 封闭集已过滤，保底仍按非法。
        }
    }

    /** [resolveStructured] 的结果；[Invalid] 携带原始 status 供日志与文案。 */
    sealed class StructuredVerdict {
        /** 远端执行器确认成功。 */
        object Succeeded : StructuredVerdict()

        /** 远端执行器确认失败。[code] 机器可读；[message] 供用户查看。 */
        data class Failed(val code: String, val message: String) : StructuredVerdict()

        /** 远端确认执行已取消。[code] 可空（取消原因码）。 */
        data class Cancelled(val code: String?) : StructuredVerdict()

        /** status 非法/自相矛盾：保守失败，绝不降级成功。 */
        data class Invalid(val rawStatus: String) : StructuredVerdict()

        /** 帧缺 status（旧 Hand）：调用方走正文分类器兜底并显式标记。 */
        object NeedsLegacyFallback : StructuredVerdict()
    }
}
