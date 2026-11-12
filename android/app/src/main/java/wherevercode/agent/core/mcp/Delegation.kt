package wherevercode.agent.core.mcp

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import wherevercode.agent.core.security.CredentialVerifyResult
import wherevercode.agent.core.security.RevocationStore
import wherevercode.agent.core.security.TaskCredentialIssuer
import wherevercode.agent.core.security.parseTaskCredential
import wherevercode.agent.core.transport.DiscoveredPeer

/**
 * 小米 12 中间调度者（阶段 2 B 路，审计 §6.1 步骤③④、§6.3 步骤④）。
 *
 * 旧项目没有这个角色，新写。小米 12 既是执行者（跑本机 Agent），
 * 也是转发者：替 K70 调 Mac 的手，结果原路返回。
 *
 * 转授规则（三条，缺一不可）：
 * 1. 任务凭证转授：先验收入凭证（任务 / 目标本机 / 文件夹 / 有效期 / 撤销），
 *    再签发新凭证——同一任务 id、目标改为下一跳（Mac）、TTL 缩短
 *    （留 [RETURN_TRIP_RESERVE_SECONDS] 秒给回程）。
 * 2. seq 续接：下一跳的首个信封 seq = 上游已用最大 seq + 1，之后连续递增，
 *    不得重置（否则对端 ReplayGuard 会把回包当重放丢掉）。
 * 3. 超时合并：多跳总超时取最小剩余——上游剩余超时与转授后凭证剩余 TTL
 *    两者取小，不得无限延长；超时后任务进 blocked（可恢复）。
 *
 * 转发失败时原路返回中文原因（上游收到的就是 [HandEvent.Blocked]），
 * 不得静默改本地执行（审计 §6.3 全程）。
 */
data class IncomingDelegatedCall(
    val taskId: String,
    val agentId: String,
    /** 上游设备（K70）。 */
    val sourceDeviceId: String,
    /** 最终目标设备（Mac）。 */
    val targetDeviceId: String,
    val folder: String,
    val action: String,
    val args: Map<String, String> = emptyMap(),
    /** 上游发来的原始任务凭证（toWire 形式）。 */
    val credentialWire: String,
    /** 上游已用到的最大 seq，转授从 lastSeq + 1 续接。 */
    val lastSeq: Long,
    /** 上游剩余总超时（毫秒）。 */
    val remainingTimeoutMs: Long,
    /** 下一跳对端（Mac）的发现记录。 */
    val peer: DiscoveredPeer,
    /** 下一跳对端 X25519 公钥（配对记录里取）；为空时拒绝转授。 */
    val peerPublicKey: ByteArray?,
)

/** 下一跳调用（由调用方注入，一般是配给 Mac 的 HandClient.call）。 */
fun interface NextHopCall {
    fun call(request: HandCallRequest): Flow<HandEvent>
}

class Delegator(
    private val nextHop: NextHopCall,
    private val ownDeviceId: String,
    /** 任务凭证签名密钥（须与签发方一致，见回报未问题）。 */
    private val signingKey: ByteArray,
    private val revocation: RevocationStore,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {

    companion object {
        /** 转授时给回程预留的秒数：新凭证 TTL = 上游剩余 TTL − 60s。 */
        const val RETURN_TRIP_RESERVE_SECONDS = 60L

        /** 剩余时间少于该值时拒绝转授（转过去也做不完，不如让上游重发）。 */
        const val MIN_REMAINING_SECONDS_TO_FORWARD = 120L
    }

    fun forward(incoming: IncomingDelegatedCall): Flow<HandEvent> = flow {
        // 1. 验收入凭证：解析失败直接拒绝。
        val incomingCredential = parseTaskCredential(incoming.credentialWire)
        if (incomingCredential == null) {
            emit(
                HandEvent.Blocked(
                    "转授失败：上游任务凭证无法解析，请重新发起任务",
                    HandProtocol.Errors.INVALID_CREDENTIAL,
                ),
            )
            return@flow
        }

        // 2. 验收入凭证：任务 / 目标本机 / 文件夹 / 有效期 / 撤销。
        val verifyResult = TaskCredentialIssuer.verify(
            credential = incomingCredential,
            expectedTaskId = incoming.taskId,
            expectedTargetDeviceId = ownDeviceId,
            expectedFolder = incoming.folder,
            signingKey = signingKey,
            revocation = revocation,
            nowEpochSeconds = nowEpochSeconds(),
        )
        if (verifyResult != CredentialVerifyResult.OK) {
            emit(
                HandEvent.Blocked(
                    "转授失败：上游任务凭证校验未通过（${verifyReasonText(verifyResult)}），请重新发起任务",
                    verifyErrorCode(verifyResult),
                ),
            )
            return@flow
        }

        // 3. 剩余时间不足，拒绝转授（不把注定超时的任务往下传）。
        val now = nowEpochSeconds()
        val remainingTtlSeconds = incomingCredential.expiresAtEpochSeconds - now
        if (remainingTtlSeconds <= MIN_REMAINING_SECONDS_TO_FORWARD) {
            emit(
                HandEvent.Blocked(
                    "转授失败：上游凭证剩余时间不足（剩余 $remainingTtlSeconds 秒，" +
                        "需多于 $MIN_REMAINING_SECONDS_TO_FORWARD 秒才能转授），请重新发起任务",
                    HandProtocol.Errors.EXPIRED_CREDENTIAL,
                ),
            )
            return@flow
        }

        // 4. 新凭证：同一任务 id，目标改为下一跳，TTL 缩短（留 60s 回程）。
        val delegatedTtlSeconds = remainingTtlSeconds - RETURN_TRIP_RESERVE_SECONDS

        // 5. 下一跳公钥缺失，拒绝转授。
        val peerKey = incoming.peerPublicKey
        if (peerKey == null) {
            emit(
                HandEvent.Blocked(
                    "转授失败：没有下一跳设备的公钥，请先与 ${incoming.targetDeviceId} 配对",
                    HandProtocol.Errors.NOT_AUTHORIZED,
                ),
            )
            return@flow
        }

        // 6. 超时合并：取最小剩余，不得延长。
        val effectiveTimeoutMs = minOf(
            incoming.remainingTimeoutMs,
            delegatedTtlSeconds * 1000,
        )
        val nextRequest = HandCallRequest(
            taskId = incoming.taskId,
            agentId = incoming.agentId,
            peer = incoming.peer,
            peerPublicKey = peerKey,
            folder = incoming.folder,
            action = incoming.action,
            args = incoming.args,
            // seq 续接：上游最大 seq + 1，不得重置。
            firstSeq = incoming.lastSeq + 1,
            credentialTtlSeconds = delegatedTtlSeconds,
        )

        // 7. 转发：下一跳的 Blocked 原样返回；超时与异常都转中文原因。
        try {
            withTimeout(effectiveTimeoutMs) {
                nextHop.call(nextRequest).collect { event ->
                    emit(event)
                }
            }
        } catch (e: TimeoutCancellationException) {
            emit(
                HandEvent.Blocked(
                    "转发超时（多跳剩余时间 ${effectiveTimeoutMs}ms 已用完）：任务已进 blocked，可重试",
                    HandProtocol.Errors.NOT_AUTHORIZED,
                ),
            )
        } catch (e: Exception) {
            emit(
                HandEvent.Blocked(
                    "转发失败：${e.message}，请检查 ${incoming.targetDeviceId} 是否在线后重试",
                    HandProtocol.Errors.NOT_AUTHORIZED,
                ),
            )
        }
    }

    private fun verifyReasonText(result: CredentialVerifyResult): String = when (result) {
        CredentialVerifyResult.OK -> "通过"
        CredentialVerifyResult.INVALID -> "凭证无效（签名不匹配）"
        CredentialVerifyResult.EXPIRED -> "凭证已过期"
        CredentialVerifyResult.REVOKED -> "凭证已被撤销"
        CredentialVerifyResult.WRONG_TASK -> "凭证不属于该任务"
        CredentialVerifyResult.WRONG_TARGET -> "凭证不是发给本设备的"
        CredentialVerifyResult.WRONG_FOLDER -> "凭证文件夹不匹配"
    }

    private fun verifyErrorCode(result: CredentialVerifyResult): String = when (result) {
        CredentialVerifyResult.OK -> ""
        CredentialVerifyResult.INVALID -> HandProtocol.Errors.INVALID_CREDENTIAL
        CredentialVerifyResult.EXPIRED -> HandProtocol.Errors.EXPIRED_CREDENTIAL
        CredentialVerifyResult.REVOKED -> HandProtocol.Errors.REVOKED_CREDENTIAL
        CredentialVerifyResult.WRONG_TASK -> HandProtocol.Errors.WRONG_TASK
        CredentialVerifyResult.WRONG_TARGET -> HandProtocol.Errors.WRONG_TARGET
        CredentialVerifyResult.WRONG_FOLDER -> HandProtocol.Errors.WRONG_FOLDER
    }
}
