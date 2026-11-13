package wherevercode.agent.core.mcp

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.json.JSONObject
import wherevercode.agent.core.mcp.HandEnvelope
import wherevercode.agent.core.mcp.HandProtocol
import wherevercode.agent.core.security.DeviceKeys
import wherevercode.agent.core.security.PeerTrustChecker
import wherevercode.agent.core.security.ReplayGuard
import wherevercode.agent.core.security.TaskCredentialIssuer
import wherevercode.agent.core.security.toWire
import wherevercode.agent.core.transport.DirectTransport
import wherevercode.agent.core.transport.DiscoveredPeer
import wherevercode.agent.core.transport.TransportAdapter
import wherevercode.agent.core.transport.TransportConnection

/**
 * 调远端手的客户端（阶段 2 B 路，审计 §6.3 步骤②、⑤）。
 *
 * 走注入的 [TransportAdapter]，绝不自己 new [wherevercode.agent.core.transport.LanTransport]：
 * 传输可替换（局域网 / Tailscale / 自备中继），HandClient 只认接口。
 *
 * 调用链（每步失败都以 [HandEvent.Blocked] 终态发出中文可操作原因，
 * 任务进 blocked（可恢复））：
 * 1. action 白名单（只允许 list/read/write/run/authorize_project_folder）；
 * 2. 配对信任（[PeerTrustChecker]，未配对直接拒绝）；
 * 3. 会话密钥：[DeviceKeys.deriveSessionKey]（X25519 + HKDF）；
 * 4. 任务凭证：[TaskCredentialIssuer.issue]（TTL 900s），[toWire] 后随信封发送；
 * 5. 信封：[HandProtocol.toJson] → [DeviceKeys.seal] → 一次 POST；
 * 6. 流式回包：每帧 [DeviceKeys.open] 验签 → [HandProtocol.parseJson] →
 *    taskId 核对 → [ReplayGuard] 校验 seq（重放帧丢弃）→ 按类型发事件。
 *
 * 绝不回落本地执行：本类没有任何本地执行路径。任何一步拒绝，
 * 调用方都必须把任务置 blocked（可恢复），不得静默改成本地跑
 *（审计 §6.3 全程、旧项目 `resolve_cross_device_task()` 的原则）。
 *
 * 回包类型（B 路定义，Hand 服务端须按此回包，待与 Hand 服务端实现方确认）：
 * - `cross_device_progress`：进度，args["message"] / args["percent"]；
 * - `cross_device_result`：终态结果，args["output"]，服务端随后关闭流；
 * - `cross_device_error`：终态拒绝，args["code"] / args["message"]，服务端随后关闭流。
 */
sealed class HandEvent {
    /** 进度事件（流式）。 */
    data class Progress(val seq: Long, val message: String, val percent: Int? = null) : HandEvent()

    /** 终态：成功结果。 */
    data class Result(val seq: Long, val output: String) : HandEvent()

    /** 终态：被拒绝，任务进 blocked（可恢复）。reason 必须是中文可操作文案。 */
    data class Blocked(val reason: String, val code: String) : HandEvent()
}

/**
 * 控制台 WS token 的领用结果（[HandClient.requestWsToken]）。
 * token 是 32 字节随机 hex，单次使用，120 秒过期；失败只给中文原因，
 * 调用方（ConsoleWsFeed）按失败走退避重连，绝不因此崩溃控制台。
 */
sealed class WsTokenResult {
    data class Ok(val token: String, val expiresInSeconds: Long) : WsTokenResult()
    data class Failed(val reason: String) : WsTokenResult()
}

/**
 * Hand 设备管理调用的结果（[HandClient.requestHandAdmin]，自更新用）。
 * Ok 带 Hand 回包的 args（stage/apply/rollback 各自的关键字段）；
 * Failed 只给中文原因，不抛错——调用方（HandUpdateRunner）按失败
 * 决定回滚或如实报错。
 */
sealed class HandAdminResult {
    data class Ok(val args: Map<String, String>) : HandAdminResult()
    data class Failed(val reason: String) : HandAdminResult()
}

data class HandCallRequest(
    val taskId: String,
    val agentId: String,
    val peer: DiscoveredPeer,
    /**
     * 对端 X25519 公钥。为空时从 [PeerTrustChecker.peerPublicKey]（配对记录）取；
     * 两处都没有时调用失败（Blocked），须重新配对。
     */
    val peerPublicKey: ByteArray? = null,
    val folder: String,
    val action: String,
    val args: Map<String, String> = emptyMap(),
    /** 首个信封的 seq；转授时由 Delegator 续接（见 Delegation.kt）。 */
    val firstSeq: Long = 1L,
    /** 本次调用的任务凭证 TTL，默认 900s（[TaskCredentialIssuer.DEFAULT_TTL_SECONDS]）。 */
    val credentialTtlSeconds: Long = TaskCredentialIssuer.DEFAULT_TTL_SECONDS,
)

/**
 * V5 修复（2026-11-11，CodeBuddy 卡死轮次）：在途 Hand 连接登记表。
 *
 * [HandRemoteAgentChatPort] 持有唯一一份，每次建 [HandClient] 都传
 * 同一份；[HandClient.call] 建好连接后登记、结束时注销。停止时
 * [abort] 按任务 id 关掉该任务的全部在途连接（run 连接 + 可能正
 * 在飞的控制连接）：阻塞 socket 读立刻抛，轮次马上终态。
 * [TransportConnection.close] 是 suspend 函数，[abort] 在 IO 上
 * 异步关连接（关 socket 本身只是毫秒级操作）；只关连接，不改任何
 * 轮次状态，连接的正常关闭仍由 call() 的 finally 负责。
 */
class ActiveHandCallRegistry(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val calls = ConcurrentHashMap<String, MutableSet<TransportConnection>>()

    fun register(taskId: String, connection: TransportConnection) {
        calls.computeIfAbsent(taskId) { ConcurrentHashMap.newKeySet() }.add(connection)
    }

    fun unregister(taskId: String, connection: TransportConnection) {
        val set = calls[taskId] ?: return
        set.remove(connection)
        if (set.isEmpty()) calls.remove(taskId, set)
    }

    /** 关掉该任务的全部在途连接；没有登记时是 no-op。 */
    fun abort(taskId: String) {
        val set = calls.remove(taskId) ?: return
        for (connection in set) {
            scope.launch { runCatching { connection.close() } }
        }
    }
}

class HandClient(
    /** 注入的传输层，不得在类内 new 具体实现。 */
    private val transport: TransportAdapter,
    private val ownDeviceId: String,
    private val ownKeyPair: DeviceKeys.KeyPair,
    /** 任务凭证签名密钥（来源见回报未问题：须与签发方一致，调用方负责提供）。 */
    private val signingKey: ByteArray,
    /** 配对信任判定（由 PairingManager 实现）。 */
    private val trustChecker: PeerTrustChecker,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    /**
     * V5 修复（2026-11-11，CodeBuddy 卡死轮次）：在途连接登记表。
     * null = 不登记（旧调用方/测试替身不受影响）。生产上由
     * [HandRemoteAgentChatPort] 持唯一一份、每次建 client 都传
     * 同一份；停止时按任务 id 关连接，让阻塞读立刻终态。
     */
    private val activeCalls: ActiveHandCallRegistry? = null,
) {

    companion object {
        const val TYPE_PROGRESS = "cross_device_progress"
        const val TYPE_RESULT = "cross_device_result"
        const val TYPE_ERROR = "cross_device_error"

        /** 控制台 WS token 的请求 / 回包类型（与 Hand 侧 WS 端点定死的同一份）。 */
        const val TYPE_WS_TOKEN = "wherevercode_ws_token"
        const val TYPE_WS_TOKEN_REPLY = "wherevercode_ws_token_reply"

        // V5 跨设备开发（2026-11-12，D2）：authorize_project_folder
        // 是管理动作（把项目文件夹记入 Agent 的 auth.json），不执行
        // 命令；与 list/read/write/run 同走密封通道与任务凭证。
        val ALLOWED_ACTIONS = setOf("list", "read", "write", "run", "authorize_project_folder")
    }

    /**
     * 领控制台 WS token（V5 控制台实时推送，2026-11-11）。
     *
     * 经密封通道，已配对客户端才能领：复用 [call] 的信任检查、会话密钥
     * 推导、seal、connect、sendHello、send，再读一帧密封回包开包取
     * args.token。这不是 _dispatch 的 list/read/write/run：**不带任务凭证**
     * （不调 TaskCredentialIssuer，不碰 call() 的凭证逻辑），信封字段
     * task_id / agent_id / folder / action 全空，seq 固定 1。
     * 回包 `{"type":"wherevercode_ws_token_reply","args":{"token":…,"expires_in":120}}`；
     * token 不是 64 位 hex、类型不对、验签失败、连接失败一律回 [WsTokenResult.Failed]，
     * 不抛错（调用方按失败退避重连）。
     */
    suspend fun requestWsToken(peer: DiscoveredPeer): WsTokenResult {
        // 1. 配对信任：未配对直接拒绝（与 call() 同口径）。
        if (!trustChecker.isTrusted(peer)) {
            return WsTokenResult.Failed(
                trustChecker.trustFailureReason(peer)
                    ?: "未配对：请先在目标设备上点一次确认，完成配对后再试",
            )
        }

        // 2. 对端公钥（配对记录）；没有则无法建会话。
        val peerKey = trustChecker.peerPublicKey(peer.deviceId)
            ?: return WsTokenResult.Failed(
                "配对记录里没有对方的公钥（配对时未完成密钥交换）：请删除配对并重新配对",
            )

        // 3. 会话密钥（X25519 + HKDF，与 call() 同款）。
        val sessionKey = try {
            DeviceKeys.deriveSessionKey(ownKeyPair.privateKey, peerKey)
        } catch (e: Exception) {
            return WsTokenResult.Failed("会话密钥协商失败：对方公钥无效，请重新配对（${e.message}）")
        }

        // 4. 密封信封：无任务凭证，字段全空（见方法注释）。
        val envelope = HandEnvelope(
            type = TYPE_WS_TOKEN,
            version = 1,
            seq = 1,
            taskId = "",
            agentId = "",
            sourceDeviceId = ownDeviceId,
            targetDeviceId = peer.deviceId,
            folder = "",
            action = "",
            args = emptyMap(),
        )
        val sealed = try {
            DeviceKeys.seal(
                sessionKey,
                HandProtocol.toJson(envelope).toByteArray(Charsets.UTF_8),
            )
        } catch (e: Exception) {
            return WsTokenResult.Failed("信封加密失败：${e.message}，请重试")
        }

        // 5. 连接并发送（先 hello 再发密封信封，与 call() 同款）。
        val connection = try {
            transport.connect(peer)
        } catch (e: Exception) {
            return WsTokenResult.Failed(
                "连接目标设备失败：请检查两台设备在同一 Wi-Fi、目标设备已开机且 Hand 在运行（${e.message}）",
            )
        }
        try {
            try {
                connection.sendHello(ownDeviceId, ownKeyPair.publicKey)
                connection.send(sealed)
            } catch (e: Exception) {
                return WsTokenResult.Failed("发送请求失败：${e.message}，请重试")
            }

            // 6. 只读一帧密封回包（不是流式多帧），开包取 token。
            val frame = try {
                connection.incoming().first()
            } catch (e: Exception) {
                return WsTokenResult.Failed("读取回包失败：${e.message}，请重试")
            }
            val opened = try {
                DeviceKeys.open(sessionKey, frame)
            } catch (e: IllegalArgumentException) {
                return WsTokenResult.Failed("回包验签失败：回包被篡改或会话密钥不匹配，请重新配对后重试")
            }
            return parseWsTokenReply(String(opened, Charsets.UTF_8))
                ?: WsTokenResult.Failed("回包不是有效的 WS token 回包：对方 Hand 版本可能不一致，请检查目标设备 App 版本")
        } finally {
            try {
                connection.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Hand 设备管理调用（自更新 update_hand，2026-11-12）。
     *
     * 与 [requestWsToken] 同款密封管理调用：配对信任检查 → 会话密钥 →
     * 密封信封（type=wherevercode_device_admin，action="update_hand"，
     * args 带 phase 与阶段参数，confirmed=true——Hand 只在确认位
     * 为 true 时执行）→ hello → 发 → 读一帧密封回包。
     * 不带任务凭证（不调 TaskCredentialIssuer），task_id / agent_id /
     * folder 全空（设备管理没有任务），seq 固定 1。
     * 回包 `{"type":"wherevercode_device_admin_reply","args":{...}}`；
     * 类型不对、验签失败、连接失败一律回 [HandAdminResult.Failed]，
     * 不抛错（调用方按失败回滚或如实报错）。
     * 旧版 Hand 没有 update_hand 动作：发过去会得 cross_device_error
     * 或无回包，都按 Failed 处理（UI 层先看 updateSupported 挡住）。
     */
    suspend fun requestHandAdmin(
        peer: DiscoveredPeer,
        action: String,
        args: Map<String, String>,
    ): HandAdminResult {
        if (!trustChecker.isTrusted(peer)) {
            return HandAdminResult.Failed(
                trustChecker.trustFailureReason(peer)
                    ?: "未配对：请先在目标设备上点一次确认，完成配对后再试",
            )
        }
        val peerKey = trustChecker.peerPublicKey(peer.deviceId)
            ?: return HandAdminResult.Failed(
                "配对记录里没有对方的公钥（配对时未完成密钥交换）：请删除配对并重新配对",
            )
        val sessionKey = try {
            DeviceKeys.deriveSessionKey(ownKeyPair.privateKey, peerKey)
        } catch (e: Exception) {
            return HandAdminResult.Failed("会话密钥协商失败：对方公钥无效，请重新配对（${e.message}）")
        }
        val envelope = HandEnvelope(
            type = HandProtocol.DEVICE_ADMIN_TYPE,
            version = 1,
            seq = 1,
            taskId = "",
            agentId = "",
            sourceDeviceId = ownDeviceId,
            targetDeviceId = peer.deviceId,
            folder = "",
            action = action,
            args = args,
            confirmed = true,
        )
        val sealed = try {
            DeviceKeys.seal(
                sessionKey,
                HandProtocol.toJson(envelope).toByteArray(Charsets.UTF_8),
            )
        } catch (e: Exception) {
            return HandAdminResult.Failed("信封加密失败：${e.message}，请重试")
        }
        val connection = try {
            transport.connect(peer)
        } catch (e: Exception) {
            return HandAdminResult.Failed(
                "连接目标设备失败：请检查两台设备在同一 Wi-Fi、目标设备已开机且 Hand 在运行（${e.message}）",
            )
        }
        try {
            try {
                connection.sendHello(ownDeviceId, ownKeyPair.publicKey)
                connection.send(sealed)
            } catch (e: Exception) {
                return HandAdminResult.Failed("发送请求失败：${e.message}，请重试")
            }
            val frame = try {
                connection.incoming().first()
            } catch (e: Exception) {
                return HandAdminResult.Failed("读取回包失败：${e.message}，请重试")
            }
            val opened = try {
                DeviceKeys.open(sessionKey, frame)
            } catch (e: IllegalArgumentException) {
                return HandAdminResult.Failed("回包验签失败：回包被篡改或会话密钥不匹配，请重新配对后重试")
            }
            return parseHandAdminReply(String(opened, Charsets.UTF_8))
                ?: HandAdminResult.Failed("回包不是有效的设备管理回包：对方 Hand 版本可能不一致，请检查目标设备 App 版本")
        } finally {
            try {
                connection.close()
            } catch (_: Exception) {
            }
        }
    }

    fun call(request: HandCallRequest): Flow<HandEvent> = flow {
        // 1. action 白名单。
        if (request.action !in ALLOWED_ACTIONS) {
            emit(
                HandEvent.Blocked(
                    "不支持的操作“${request.action}”：跨设备只允许 list/read/write/run/authorize_project_folder",
                    HandProtocol.Errors.COMMAND_NOT_ALLOWED,
                ),
            )
            return@flow
        }

        // 2. 配对信任：未配对直接拒绝。
        if (!trustChecker.isTrusted(request.peer)) {
            emit(
                HandEvent.Blocked(
                    trustChecker.trustFailureReason(request.peer)
                        ?: "未配对：请先在目标设备上点一次确认，完成配对后再试",
                    HandProtocol.Errors.NOT_AUTHORIZED,
                ),
            )
            return@flow
        }

        // 3. 对端公钥（请求里有就用请求里的，否则取配对记录）。
        val peerKey = request.peerPublicKey
            ?: trustChecker.peerPublicKey(request.peer.deviceId)
        if (peerKey == null) {
            emit(
                HandEvent.Blocked(
                    "配对记录里没有对方的公钥（配对时未完成密钥交换）：请删除配对并重新配对",
                    HandProtocol.Errors.NOT_AUTHORIZED,
                ),
            )
            return@flow
        }

        // 4. 会话密钥。
        val sessionKey = try {
            DeviceKeys.deriveSessionKey(ownKeyPair.privateKey, peerKey)
        } catch (e: Exception) {
            emit(
                HandEvent.Blocked(
                    "会话密钥协商失败：对方公钥无效，请重新配对（${e.message}）",
                    HandProtocol.Errors.NOT_AUTHORIZED,
                ),
            )
            return@flow
        }

        // 5. 任务凭证（TTL 900s），随信封发送。
        val credential = TaskCredentialIssuer.issue(
            userId = "",
            taskId = request.taskId,
            attempt = "",
            sourceDeviceId = ownDeviceId,
            targetDeviceId = request.peer.deviceId,
            agentId = request.agentId,
            folder = request.folder,
            signingKey = signingKey,
            ttlSeconds = request.credentialTtlSeconds,
            nowEpochSeconds = nowEpochSeconds(),
        )
        val envelope = HandEnvelope(
            seq = request.firstSeq,
            taskId = request.taskId,
            agentId = request.agentId,
            sourceDeviceId = ownDeviceId,
            targetDeviceId = request.peer.deviceId,
            folder = request.folder,
            action = request.action,
            args = request.args + ("credential" to credential.toWire()),
        )
        val sealed = try {
            DeviceKeys.seal(
                sessionKey,
                HandProtocol.toJson(envelope).toByteArray(Charsets.UTF_8),
            )
        } catch (e: Exception) {
            emit(
                HandEvent.Blocked(
                    "信封加密失败：${e.message}，请重试",
                    HandProtocol.Errors.NOT_AUTHORIZED,
                ),
            )
            return@flow
        }

        // 6. 连接并发送。
        val connection = try {
            transport.connect(request.peer)
        } catch (e: Exception) {
            emit(
                HandEvent.Blocked(
                    "连接目标设备失败：请检查两台设备在同一 Wi-Fi、目标设备已开机且 Hand 在运行（${e.message}）",
                    HandProtocol.Errors.NOT_AUTHORIZED,
                ),
            )
            return@flow
        }
        // V5 修复（2026-11-11，CodeBuddy 卡死轮次）：登记在途连接，
        // 停止时 abort 能按任务 id 关掉它（否则 cancel() 中断不了
        // 阻塞读）。注销在外层 finally 里，与关闭连接一起做。
        activeCalls?.register(request.taskId, connection)
        try {
            try {
                // V5 修复（2026-11-11，Grok/CodeBuddy 对话“空响应”）：
                // 先发 hello 再发密封信封——服务端每连接先读 hello 帧
                // 再读信封；不发 hello 直接发信封会被服务端静默丢弃。
                // 不需要 hello 的传输（LanTransport）用默认空实现。
                connection.sendHello(ownDeviceId, ownKeyPair.publicKey)
                connection.send(sealed)
            } catch (e: Exception) {
                emit(
                    HandEvent.Blocked(
                        "发送请求失败：${e.message}，请重试",
                        HandProtocol.Errors.NOT_AUTHORIZED,
                    ),
                )
                return@flow
            }

            // 7. 流式回包：验签 → 解析 → taskId 核对 → seq 防重放 → 按类型发事件。
            val replayGuard = ReplayGuard()
            replayGuard.checkAndRecord(request.firstSeq, nowEpochSeconds())
            var done = false
            try {
                connection.incoming().collect { frame ->
                    if (done) return@collect
                    // 凭证过期中途检查：回包晚于凭证有效期，直接拒绝，不再处理。
                    if (nowEpochSeconds() >= credential.expiresAtEpochSeconds) {
                        emit(
                            HandEvent.Blocked(
                                "任务凭证已过期（有效期 ${request.credentialTtlSeconds} 秒）：请重新发起任务",
                                HandProtocol.Errors.EXPIRED_CREDENTIAL,
                            ),
                        )
                        done = true
                        connection.close()
                        return@collect
                    }
                    val opened = try {
                        DeviceKeys.open(sessionKey, frame)
                    } catch (e: IllegalArgumentException) {
                        emit(
                            HandEvent.Blocked(
                                "结果信封验签失败：回包被篡改或会话密钥不匹配，请重新配对后重试",
                                HandProtocol.Errors.INVALID_CREDENTIAL,
                            ),
                        )
                        done = true
                        connection.close()
                        return@collect
                    }
                    val response = HandProtocol.parseJson(String(opened, Charsets.UTF_8))
                    if (response == null) {
                        emit(
                            HandEvent.Blocked(
                                "收到无法解析的回包：对方 Hand 版本可能不一致，请检查目标设备 App 版本",
                                HandProtocol.Errors.INVALID_CREDENTIAL,
                            ),
                        )
                        done = true
                        connection.close()
                        return@collect
                    }
                    if (response.taskId != request.taskId) {
                        emit(
                            HandEvent.Blocked(
                                "回包任务不匹配（收到 ${response.taskId}，期望 ${request.taskId}）：可能串话，请重试",
                                HandProtocol.Errors.WRONG_TASK,
                            ),
                        )
                        done = true
                        connection.close()
                        return@collect
                    }
                    if (response.agentId != request.agentId ||
                        response.sourceDeviceId != request.peer.deviceId ||
                        response.targetDeviceId != ownDeviceId ||
                        response.folder != request.folder ||
                        response.action != request.action
                    ) {
                        emit(
                            HandEvent.Blocked(
                                "Hand 回包身份或操作范围与本次请求不匹配，请重试",
                                HandProtocol.Errors.INVALID_CREDENTIAL,
                            ),
                        )
                        done = true
                        connection.close()
                        return@collect
                    }
                    if (!replayGuard.checkAndRecord(response.seq, nowEpochSeconds())) {
                        // 重放 / 过期 / 超前太多的 seq：只丢弃该帧，不阻塞整个任务。
                        return@collect
                    }
                    when (response.type) {
                        TYPE_PROGRESS -> {
                            val percent = response.args["percent"]?.toIntOrNull()
                            emit(
                                HandEvent.Progress(
                                    response.seq,
                                    response.args["message"].orEmpty(),
                                    percent,
                                ),
                            )
                        }

                        TYPE_RESULT -> {
                            emit(HandEvent.Result(response.seq, response.args["output"].orEmpty()))
                            done = true
                            connection.close()
                        }

                        TYPE_ERROR -> {
                            val code = response.args["code"].orEmpty()
                            val message = response.args["message"]
                                ?: HandProtocol.Errors.message(code)
                            emit(HandEvent.Blocked(message, code))
                            done = true
                            connection.close()
                        }

                        else -> {
                            emit(
                                HandEvent.Blocked(
                                    "收到未知的回包类型“${response.type}”：对方 Hand 版本可能不一致，请检查目标设备 App 版本",
                                    HandProtocol.Errors.INVALID_CREDENTIAL,
                                ),
                            )
                            done = true
                            connection.close()
                        }
                    }
                }
            } catch (e: Exception) {
                if (!done) {
                    // V5 修复（2026-11-11，CodeBuddy“在吗”两次“传输中断：
                    // Read timed out”）：读超时单独给诚实文案——超时不是
                    // 网络断了，而是远端 Agent 在超时内没返回任何内容
                    // （冷启动、执行中都可能），不要只说“请检查网络”。
                    val message = if (e is java.net.SocketTimeoutException) {
                        "远端 Agent 超过 ${DirectTransport.CALL_READ_TIMEOUT_MS / 1000} 秒" +
                            "没有返回任何内容：可能正在冷启动或执行中，请稍等再试，" +
                            "或到远端设备上看该 Agent 是否在运行"
                    } else {
                        // V5 修复（2026-11-11，hello 通、run 就 reset）：
                        // 连接被重置不一定是网络问题——hello 已通说明
                        // 网络与 Hand 都在，reset 往往是服务端在 hello
                        // 之后直接关了连接（例如本机在远端还是 PENDING
                        // 未批准；旧版 Hand 不回错误直接掐连接）。新版
                        // Hand 会回明确的未批准错误，不再走这条路；但
                        // 对旧版服务端，文案要把配对这个可能原因说出
                        // 来，不要只让用户去查网络。
                        val detail = e.message.orEmpty()
                        if (detail.contains("reset", ignoreCase = true) ||
                            detail.contains("broken pipe", ignoreCase = true)
                        ) {
                            "传输中断：$detail。远端 hello 正常但调用被中断，可能是远端设备尚未批准此手机（配对未通过），请在远端设备上批准配对后再试；也请检查网络后重试"
                        } else {
                            "传输中断：$detail，请检查网络后重试"
                        }
                    }
                    emit(
                        HandEvent.Blocked(
                            message,
                            HandProtocol.Errors.NOT_AUTHORIZED,
                        ),
                    )
                    done = true
                }
            }
            // V5 修复（2026-11-11，Grok/CodeBuddy 对话“空响应”）：回包流
            // 正常结束却一个终态事件都没发（服务端静默关闭、零帧回包），
            // 过去是静默空事件、聊天层只能报“空响应”；现在给一个可诊断
            // 的阻塞原因。done 已为 true 说明 Result/Blocked 已发过，
            // 不再重复。
            if (!done) {
                emit(
                    HandEvent.Blocked(
                        "远端设备没有返回结果（连接已关闭）：请检查设备配对与 Hand 服务",
                        HandProtocol.Errors.NOT_AUTHORIZED,
                    ),
                )
            }
        } finally {
            activeCalls?.unregister(request.taskId, connection)
            try {
                connection.close()
            } catch (_: Exception) {
            }
        }
    }
}

/**
 * 解析 WS token 回包：`{"type":"wherevercode_ws_token_reply","version":1,
 * "args":{"token":"<64 hex>","expires_in":120}}`。
 * 类型/版本不对、token 不是 64 位 hex、JSON 坏了都返回 null（调用方报
 * “不是有效的 WS token 回包”，不抛错）。expires_in 缺省按 120 秒。
 * 注意不能走 [HandProtocol.parseJson]：它只认 cross_device_tool_call 与
 * 三种回包类型，且要求 action 在白名单、task_id 非空——token 回包两项
 * 都不满足。
 */
fun parseWsTokenReply(json: String): WsTokenResult? {
    return try {
        val obj = JSONObject(json)
        if (obj.optString("type", "") != HandClient.TYPE_WS_TOKEN_REPLY) return null
        if (obj.optInt("version", -1) != 1) return null
        val args = obj.optJSONObject("args") ?: return null
        val token = args.optString("token", "")
        if (!ConsoleWsClient.TOKEN_REGEX.matches(token)) return null
        val expiresIn = args.optLong("expires_in", 120L).takeIf { it > 0 } ?: 120L
        WsTokenResult.Ok(token, expiresIn)
    } catch (_: Exception) {
        null
    }
}

/**
 * 解析 Hand 设备管理回包：`{"type":"wherevercode_device_admin_reply",
 * "version":1,"args":{...}}`。类型/版本不对、args 缺失、JSON 坏了
 * 都返回 null（调用方报“不是有效的设备管理回包”，不抛错）。
 * 不能走 [HandProtocol.parseJson]：它只认 cross_device_tool_call 与
 * 三种回包类型，且要求 action 在白名单、task_id 非空——管理回包
 * 两项都不满足（与 parseWsTokenReply 同理）。
 */
fun parseHandAdminReply(json: String): HandAdminResult? {
    return try {
        val obj = JSONObject(json)
        if (obj.optString("type", "") != HandProtocol.DEVICE_ADMIN_REPLY_TYPE) return null
        if (obj.optInt("version", -1) != 1) return null
        val argsObj = obj.optJSONObject("args") ?: return null
        val args = LinkedHashMap<String, String>()
        val keys = argsObj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            args[key] = argsObj.optString(key, "")
        }
        HandAdminResult.Ok(args)
    } catch (_: Exception) {
        null
    }
}
