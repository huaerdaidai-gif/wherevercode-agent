package wherevercode.agent.core.transport

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import wherevercode.agent.core.security.DeviceKeys

/**
 * 密封配对确认（App 内配对的第二步：指纹核对之后的配对码验证）。
 *
 * 流程：hello（一帧，拿对方公钥 + client_status）→ 派生会话密钥
 * （X25519 + HKDF，与 HandClient 同款）→ 密封帧
 * cross_device_pair_confirm {code} → 等一帧密封回包：
 * cross_device_pair_approved = 对方已批准；cross_device_error
 * （args.code=pair_code_wrong）= 配对码不对；其他 = 失败原因。
 *
 * 安全边界：配对码只在密封帧里走（AES-256-GCM），不在 hello 明文里；
 * 码错/锁定都由服务端判定，App 只原样转达服务端给的中文原因，
 * 不自己编。旧版 Hand 没有配对确认帧：它收到密封帧后按未批准
 * 拒绝（cross_device_not_authorized），App 会如实报“对方 Hand
 * 太旧，请先升级 Hand 再配对”。
 */
sealed interface PairConfirmResult {
    /** 对方已批准（手机侧与 Hand 侧同时完成）。 */
    data object Approved : PairConfirmResult

    /** 对方拒绝（配对码不对、已锁定等）：reason 是服务端原话。 */
    data class Rejected(val reason: String) : PairConfirmResult

    /** 没走到对方判定（连不上、超时、回包验签失败等）。 */
    data class Failed(val reason: String) : PairConfirmResult
}

object PairingClient {

    /** 配对码：6 位数字（与 Hand 侧 PAIR_CODE_LENGTH 同款）。 */
    const val PAIR_CODE_LENGTH = 6

    /** 配对码格式校验：6 位数字。 */
    fun isValidPairCode(code: String): Boolean =
        code.length == PAIR_CODE_LENGTH && code.all { it.isDigit() }

    /**
     * 发一次密封配对确认。peer 是探测到的对端（host/port/deviceId）；
     * serverPublicKey 是配对对话框里用户已核对过指纹的那把公钥——
     * 本函数重做 hello 再核对一次，公钥变了就拒绝（防中间人）。
     */
    suspend fun sendPairConfirm(
        peer: DiscoveredPeer,
        ownDeviceId: String,
        ownPrivateKey: ByteArray,
        serverPublicKey: ByteArray,
        code: String,
    ): PairConfirmResult = withContext(Dispatchers.IO) {
        if (!isValidPairCode(code)) {
            return@withContext PairConfirmResult.Failed("配对码必须是 6 位数字")
        }
        val ownPublicKey = try {
            DeviceKeys.publicKeyFromPrivate(ownPrivateKey)
        } catch (e: Exception) {
            return@withContext PairConfirmResult.Failed("本机身份密钥无效（${e.message}）")
        }
        val socket = Socket()
        try {
            socket.connect(
                InetSocketAddress(peer.host, peer.port),
                DirectTransport.CONNECT_TIMEOUT_MS,
            )
            socket.soTimeout = DirectTransport.READ_TIMEOUT_MS
            val out = socket.outputStream
            val helloObj = JSONObject().apply {
                put("type", DirectTransport.HELLO_TYPE)
                put("version", DirectTransport.HELLO_VERSION)
                put("device_id", ownDeviceId)
                put(
                    "public_key_b64",
                    java.util.Base64.getEncoder().encodeToString(ownPublicKey),
                )
            }
            writeFrame(out, helloObj.toString().toByteArray(Charsets.UTF_8))
            out.flush()
            val helloFrame = readFrame(socket.inputStream)
                ?: return@withContext PairConfirmResult.Failed("远端没有回 hello：对方可能不是 Hand 服务端")
            val hello = try {
                DirectTransport.parseHello(String(helloFrame, Charsets.UTF_8), peer.deviceId)
            } catch (e: Exception) {
                return@withContext PairConfirmResult.Failed("远端 hello 回包无效（${e.message}）")
            }
            if (!hello.publicKey.contentEquals(serverPublicKey)) {
                return@withContext PairConfirmResult.Failed(
                    "远端公钥与核对的指纹不符：请重新核对指纹（可能遭遇中间人，或设备密钥已更换）",
                )
            }
            val sessionKey = try {
                DeviceKeys.deriveSessionKey(ownPrivateKey, serverPublicKey)
            } catch (e: Exception) {
                return@withContext PairConfirmResult.Failed("会话密钥协商失败：对方公钥无效（${e.message}）")
            }
            val env = JSONObject().apply {
                put("type", "cross_device_pair_confirm")
                put("version", 1)
                put("code", code)
            }
            val sealed = try {
                DeviceKeys.seal(sessionKey, env.toString().toByteArray(Charsets.UTF_8))
            } catch (e: Exception) {
                return@withContext PairConfirmResult.Failed("配对确认加密失败（${e.message}）")
            }
            writeFrame(out, sealed)
            out.flush()
            val replyFrame = try {
                readFrame(socket.inputStream)
            } catch (e: IOException) {
                return@withContext PairConfirmResult.Failed("等待对方批准回包超时：对方可能已离线或 Hand 已停止")
            } ?: return@withContext PairConfirmResult.Failed("对方没有回配对结果：连接已关闭")
            val reply = try {
                JSONObject(String(DeviceKeys.open(sessionKey, replyFrame), Charsets.UTF_8))
            } catch (e: Exception) {
                return@withContext PairConfirmResult.Failed("对方回包验签失败：请重新配对")
            }
            interpretPairReply(reply)
        } catch (e: IOException) {
            PairConfirmResult.Failed("连接 ${peer.displayName}（${peer.host}:${peer.port}）失败：${e.message}")
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 解读密封配对回包（纯函数，无网络：可直接单测）。
     * approved = 对方已批准；cross_device_error + pair_code_wrong 等 =
     * 对方拒绝（原话转达）；cross_device_not_authorized = 对方是旧版
     * Hand（没有配对确认帧），这是失败不是拒绝——要升级 Hand。
     */
    fun interpretPairReply(reply: JSONObject): PairConfirmResult {
        return when (reply.optString("type", "")) {
            "cross_device_pair_approved" -> PairConfirmResult.Approved
            "cross_device_error" -> {
                val args = reply.optJSONObject("args")
                val code = args?.optString("code", "").orEmpty()
                val message = args?.optString("message", "")?.takeIf { it.isNotBlank() }
                    ?: reply.optString("message", "").takeIf { it.isNotBlank() }
                    ?: "对方拒绝了配对"
                if (code == "cross_device_not_authorized") {
                    PairConfirmResult.Failed("对方 Hand 太旧，不支持 App 内配对：请先升级对方 Hand（重装或等更新），或在服务端跑 hand_server.py pair-approve 批准")
                } else {
                    PairConfirmResult.Rejected(message)
                }
            }
            else -> PairConfirmResult.Failed(
                "对方回了无法识别的配对结果：${reply.optString("type", "")}",
            )
        }
    }
}
