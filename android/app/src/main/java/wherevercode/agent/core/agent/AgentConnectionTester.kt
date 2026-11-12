package wherevercode.agent.core.agent

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import wherevercode.agent.brain.BrainKind

/**
 * 连接测试探针结果（只含状态，不含密钥与正文）。
 */
sealed class ModelsProbeResult {
    /** /models 返回 200；modelCount 为 null 表示列表无法解析。 */
    data class Ok(val modelCount: Int?) : ModelsProbeResult()
    object Unauthorized : ModelsProbeResult()
    data class HttpError(val code: Int, val message: String) : ModelsProbeResult()
    data class NetworkError(val message: String) : ModelsProbeResult()
}

data class AgentTestResult(
    val loginState: AgentLoginState,
    val summary: String,
    val testedAtEpochSeconds: Long,
)

/**
 * 远端设备 Agent 探针结果（hello 握手返回的 Agent 清单判定）。
 * 只含状态与可显示的设备/Agent 名，不含密钥与正文。
 */
sealed class DeviceProbeResult {
    /** hello 通，且清单里有该 Agent、available=true。 */
    data class Available(
        val deviceDisplayName: String,
        val agentDisplayName: String,
        val folder: String,
    ) : DeviceProbeResult()

    /** hello 通，但清单里没有该 Agent。 */
    data class AgentMissing(val deviceDisplayName: String) : DeviceProbeResult()

    /** hello 通，清单里有该 Agent，但 available=false（执行入口未通过核查）。 */
    data class AgentUnavailable(val deviceDisplayName: String, val folder: String) : DeviceProbeResult()

    /**
     * hello 通，但远端尚未批准此手机（配对状态 PENDING）。
     * V5 修复（2026-11-11，hello 通、run 就 reset）：hello 只证明
     * 设备可达、Agent 在清单里，不证明调用能通过；PENDING 的调用
     * 会被服务端拒绝。探测到 PENDING 绝不能报 Available，必须
     * 单独报出来，让用户去远端设备上批准配对。
     */
    data class PendingApproval(val deviceDisplayName: String) : DeviceProbeResult()

    /** hello 没通，或设备未添加/未配对：reason 是中文可操作原因。 */
    data class Unreachable(val reason: String) : DeviceProbeResult()
}

/**
 * Agent 连接测试（阶段 3，A1 P1）。
 *
 * 诚实分级（审计 O9：登录态恒 UNKNOWN 的根源是一档探测）：
 * - 模型 API：GET `{baseUrl}/models`（OpenAI 兼容端点；URL 规则与
 *   DeepSeekApiClient.modelsUrl 一致）。200 → LOGGED_IN（有效的密钥
 *   本身就是登录证明）；401/403 → NOT_LOGGED_IN；其他失败 → UNKNOWN。
 * - 本机 CLI：跑 `<binary> --version`。成功只证明二进制可运行，
 *   登录态仍为 UNKNOWN（需一次真实运行确认，绝不由 --version 推断登录）。
 * - 订阅授权：无法自动探测，须用户先在设置里完成登录。
 *
 * 探针可注入（[modelsProbe] / [manusProbe] / [versionRunner]），单测用假探针；
 * 默认的 [defaultModelsProbe] 走 JDK HttpURLConnection（调用方须在
 * IO 线程调用，见 MainActivity）。密钥只以参数传入，不记日志。
 *
 * Manus 例外（V5 Phase B C3，Manus 404 修复，GPT 8d8ed67e）：Manus
 * 是任务型 API，没有 /models 端点，旧探针 GET {baseUrl}/models 在
 * api.manus.ai 上恒为 404。Manus 记录（见 [ManusTaskAdapter.isManusRecord]）
 * 改走 [manusProbe]：GET /v2/user.me + `x-manus-api-key` 只读验证，
 * 不创建任务；测试通过只写“认证通过”，不写“可执行”。
 *
 * 远端设备例外（V5 修复，2026-11-11）：kind == DEVICE 且 deviceId
 * 非空的记录走 [deviceProbe]（问设备上的 Hand hello，不问本机沙箱）。
 * [deviceProbe] 为 null = 调用方未接线：如实报未接线，绝不回落
 * 本机 `--version` 测试（回落就是本 bug 的根源）。
 */
/**
 * 平台 Agent 探针结果（只读探查平台频道，不发消息、不唤醒对方）。
 * 只含状态与可显示的 Agent 名，不含密钥与正文。
 */
sealed class PlatformProbeResult {
    /** 频道可读（平台凭证有效、频道已加入）。只证明通道通，不证明对方在线。 */
    object ChannelReadable : PlatformProbeResult()

    /** 平台凭证被拒绝（401/403）。 */
    object Unauthorized : PlatformProbeResult()

    /** 频道不可读，或网络不通：reason 是中文可操作原因。 */
    data class Unreachable(val reason: String) : PlatformProbeResult()
}

class AgentConnectionTester(
    private val modelsProbe: (baseUrl: String, secret: String?) -> ModelsProbeResult = ::defaultModelsProbe,
    private val manusProbe: (baseUrl: String, secret: String?) -> ManusResult<ManusAuthInfo> = ::defaultManusProbe,
    private val versionRunner: (binary: String) -> String?,
    private val deviceProbe: ((AgentRecord) -> DeviceProbeResult)? = null,
    private val platformProbe: ((AgentRecord) -> PlatformProbeResult)? = null,
) {
    /**
     * 测试一次并返回带结果的记录副本（调用方负责存回 AgentStore）。
     * [secretResolver] 由调用方提供：authRef -> 密钥值（BrainKeyStore），
     * 无值时返回 null。
     */
    fun testAndUpdate(
        record: AgentRecord,
        secretResolver: (String?) -> String? = { null },
    ): AgentRecord {
        val result = test(record, secretResolver)
        return record.copy(
            loginState = result.loginState,
            lastTestedAtEpochSeconds = result.testedAtEpochSeconds,
            lastTestSummary = result.summary,
        )
    }

    fun test(
        record: AgentRecord,
        secretResolver: (String?) -> String? = { null },
    ): AgentTestResult {
        val now = System.currentTimeMillis() / 1000
        val outcome: Pair<AgentLoginState, String> = when (record.brainKind) {
            BrainKind.MODEL_API -> testModelApi(record, secretResolver)
            BrainKind.PLATFORM_AGENT -> testPlatform(record)
            BrainKind.CLOUD_AGENT -> AgentLoginState.UNKNOWN to
                "云端 Agent 无法测试连接：App 的 MCP 端点尚未开通（手机没有公网地址），" +
                    "对方现在还连不进来。请先按 Agent 页的设置说明准备，端点开通后再测。"
            BrainKind.AGENT_RUNTIME ->
                if (record.kind == AgentKind.DEVICE && record.deviceId != null) {
                    testDevice(record)
                } else {
                    testCli(record)
                }
            BrainKind.SUBSCRIPTION -> AgentLoginState.UNKNOWN to
                "订阅授权无法自动探测：请先在设置里完成登录，登录成功即视为已验证。"
        }
        return AgentTestResult(outcome.first, outcome.second, now)
    }

    /**
     * 远端设备 Agent 测试（V5 修复，2026-11-11）：问设备上的 Hand，
     * 不问本机沙箱。旧逻辑把远端 Agent 当成本机 CLI，用
     * `<binary> --version` 在手机沙箱里找 example-agent，永远找不到，
     * 于是每个远端 DEVICE Agent 都假失败（2026-11-11 真机：
     * VPS 上的 Example Agent 明明可用，测试却报“二进制不可运行”）。
     * hello + available 只证明设备可达、Agent 在清单里且执行入口
     * 已核查，不证明该 Agent 自身的登录态，所以登录态仍为 UNKNOWN
     *（需一次真实运行确认，与本机 CLI 的诚实分级同口径）。
     *
     * V5 修复（2026-11-11，能用的 Agent 显示“未验证”）：记录已是
     * LOGGED_IN（一次真实往返成功后标上的，见 ChatViewModel 的
     * onRemoteRoundSucceeded）时，hello 亦确认执行入口可用，本次
     * 测试只确认、绝不把已验证降回 UNKNOWN——否则点一次“测试”就
     * 把刚回过话的 Agent 打回“未验证”，与真实情况对不上。探针为
     * AgentMissing / AgentUnavailable / Unreachable 时仍回 UNKNOWN：
     * 探针无法确认时不硬贴已验证。
     */
    private fun testDevice(record: AgentRecord): Pair<AgentLoginState, String> {
        val probe = deviceProbe
            ?: return AgentLoginState.UNKNOWN to
                "远端设备测试未接线：请先在设备页确认设备 ${record.deviceId} 已添加并配对。"
        val result = try {
            probe(record)
        } catch (e: Exception) {
            return AgentLoginState.UNKNOWN to "远端探测失败：${e.message ?: "未知错误"}。"
        }
        return when (result) {
            is DeviceProbeResult.Available -> {
                if (record.loginState == AgentLoginState.LOGGED_IN) {
                    AgentLoginState.LOGGED_IN to
                        "认证通过：${result.deviceDisplayName} 上的 ${result.agentDisplayName} 已在对话中真实回过消息，本次测试 hello 亦确认执行入口可用。"
                } else {
                    AgentLoginState.UNKNOWN to
                        "远端可用：${result.deviceDisplayName} 上的 ${result.agentDisplayName} 已确认可用（hello 已核查执行入口）。"
                }
            }
            is DeviceProbeResult.AgentMissing -> AgentLoginState.UNKNOWN to
                "远端无此 Agent：${result.deviceDisplayName} 的 hello 清单里没有 ${record.agentId}，请检查服务端配置。"
            is DeviceProbeResult.AgentUnavailable -> AgentLoginState.UNKNOWN to
                "远端 Agent 不可用：${result.deviceDisplayName} 上的 ${record.agentId} 执行入口未通过核查（脚本不存在）。"
            is DeviceProbeResult.PendingApproval -> AgentLoginState.UNKNOWN to
                "远端尚未批准此手机：${result.deviceDisplayName} 上的配对状态还是 PENDING，hello 通但调用会被拒绝。请在远端设备上批准此手机的配对后再试。"
            is DeviceProbeResult.Unreachable -> AgentLoginState.UNKNOWN to result.reason
        }
    }

    /**
     * 平台 Agent 测试（V5，2026-11-11）：只读探查平台频道（读一页消息），
     * 不发消息、不唤醒对方。频道可读只证明平台凭证有效、频道已加入，
     * 不证明对方在线、更不证明对方会回复——真实验证仍要发一条消息、
     * 看它回不回。探针未接线时如实报未接线，绝不回落别的探针。
     */
    private fun testPlatform(record: AgentRecord): Pair<AgentLoginState, String> {
        val probe = platformProbe
            ?: return AgentLoginState.UNKNOWN to
                "平台通道测试未接线：请先确认平台凭证已填，再直接发一条消息验证。"
        return try {
            when (val result = probe(record)) {
                is PlatformProbeResult.ChannelReadable -> AgentLoginState.UNKNOWN to
                    "平台通道可读：凭证有效、频道已加入。只证明通道通，不证明 ${record.displayName} 在线或会回复——请发一条消息验证。"
                is PlatformProbeResult.Unauthorized -> AgentLoginState.NOT_LOGGED_IN to
                    "平台凭证被拒绝（401/403），请检查 App 的平台凭证。"
                is PlatformProbeResult.Unreachable -> AgentLoginState.UNKNOWN to
                    "平台通道不可读：${result.reason}"
            }
        } catch (e: Exception) {
            AgentLoginState.UNKNOWN to "平台通道探测失败：${e.message ?: "未知错误"}。"
        }
    }

    private fun testModelApi(
        record: AgentRecord,
        secretResolver: (String?) -> String?,
    ): Pair<AgentLoginState, String> {
        val baseUrl = record.baseUrl
        if (baseUrl.isNullOrBlank()) {
            return AgentLoginState.UNKNOWN to "未填服务地址，无法测试。"
        }
        val secret = if (AgentRecord.isLocalModelUrl(baseUrl)) {
            null
        } else {
            val ref = record.authRef
            if (ref != null) {
                secretResolver(ref)
                    ?: return AgentLoginState.UNKNOWN to
                        "密钥引用 $ref 在本机 Keystore 里没有值，请先填写密钥。"
            } else {
                null
            }
        }
        // Manus 记录不走 /models 探针（该端点不存在，恒 404），改走
        // GET /v2/user.me 只读验证；旧记录靠主机识别，无损迁移。
        if (ManusTaskAdapter.isManusRecord(record)) {
            return testManus(record, secret)
        }
        return when (val probe = modelsProbe(baseUrl, secret)) {
            is ModelsProbeResult.Ok -> AgentLoginState.LOGGED_IN to
                if (probe.modelCount != null) {
                    "连接成功：/models 返回 ${probe.modelCount} 个模型。"
                } else {
                    "连接成功：/models 返回 200（模型列表无法解析）。"
                }
            is ModelsProbeResult.Unauthorized -> AgentLoginState.NOT_LOGGED_IN to
                "密钥被拒绝（401/403），请检查密钥。"
            is ModelsProbeResult.HttpError -> AgentLoginState.UNKNOWN to
                "连接失败：HTTP ${probe.code}（${probe.message.ifBlank { "无错误说明" }}）。"
            is ModelsProbeResult.NetworkError -> AgentLoginState.UNKNOWN to
                "连接失败：${probe.message}。"
        }
    }

    /**
     * Manus 只读验证：GET /v2/user.me + `x-manus-api-key`。
     * 只验证认证与可达性；成功只写“认证通过”，不写“可执行”。
     * 401/403 → NOT_LOGGED_IN；404/429/其他失败 → UNKNOWN。
     */
    private fun testManus(
        record: AgentRecord,
        secret: String?,
    ): Pair<AgentLoginState, String> {
        val baseUrl = record.baseUrl ?: ""
        return when (val probe = manusProbe(baseUrl, secret)) {
            is ManusResult.Success -> AgentLoginState.LOGGED_IN to
                "认证通过：Manus 密钥有效（GET /v2/user.me 返回 200）。" +
                "只验证了认证与可达性，未验证可执行。"
            is ManusResult.Failure -> when (val error = probe.error) {
                ManusError.Unauthorized -> AgentLoginState.NOT_LOGGED_IN to
                    "密钥被拒绝（401 未认证），请检查 Manus API Key。"
                ManusError.Forbidden -> AgentLoginState.NOT_LOGGED_IN to
                    "无权限（403）：密钥缺少所需权限，请检查 Key 权限。"
                ManusError.NotFound -> AgentLoginState.UNKNOWN to
                    "连接失败（404）：Manus API 没有 /models 端点；" +
                    "测试已改走 GET /v2/user.me，若仍报 404 请检查服务地址是否为 https://api.manus.ai。"
                ManusError.RateLimited -> AgentLoginState.UNKNOWN to
                    "请求过于频繁（429 限速），请稍后再试。"
                is ManusError.InvalidArgument -> AgentLoginState.UNKNOWN to
                    "连接失败：${error.detail}"
                is ManusError.HttpError -> AgentLoginState.UNKNOWN to
                    "连接失败：HTTP ${error.code}（${error.detail.ifBlank { "无错误说明" }}）。"
                is ManusError.NetworkError -> AgentLoginState.UNKNOWN to
                    "连接失败：${error.detail}。"
                is ManusError.InvalidResponse -> AgentLoginState.UNKNOWN to
                    "连接失败：响应无法解析（${error.detail}）。"
                ManusError.Timeout -> AgentLoginState.UNKNOWN to
                    "连接失败：请求超时。"
            }
        }
    }

    private fun testCli(record: AgentRecord): Pair<AgentLoginState, String> {
        val binary = record.binary
        if (binary.isNullOrBlank()) {
            return AgentLoginState.UNKNOWN to "未填 CLI 二进制名，无法测试。"
        }
        val version = try {
            versionRunner(binary)
        } catch (_: Exception) {
            null
        }
        return if (version != null) {
            AgentLoginState.UNKNOWN to "二进制可运行（--version 有输出），登录状态仍未验证（需一次真实运行确认）。"
        } else {
            AgentLoginState.UNKNOWN to "二进制不可运行：沙箱内未找到或 --version 无输出。"
        }
    }
}

/**
 * 默认 /models 探针（OpenAI 兼容）。调用方须在 IO 线程调用。
 * URL 规则：已含 /models 的直接用；以 /chat/completions 结尾的去掉
 * 后缀再加 /models；其余直接加 /models。
 */
fun defaultModelsProbe(baseUrl: String, secret: String?): ModelsProbeResult {
    val url = modelsUrl(baseUrl)
    return try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            if (secret != null) setRequestProperty("Authorization", "Bearer $secret")
        }
        val code = conn.responseCode
        when {
            code == 200 -> {
                val body = try {
                    conn.inputStream.bufferedReader().readText()
                } catch (_: Exception) {
                    ""
                }
                val count = try {
                    JSONObject(body).optJSONArray("data")?.length()
                } catch (_: Exception) {
                    null
                }
                ModelsProbeResult.Ok(count)
            }
            code == 401 || code == 403 -> ModelsProbeResult.Unauthorized
            else -> ModelsProbeResult.HttpError(code, conn.responseMessage ?: "")
        }
    } catch (e: Exception) {
        ModelsProbeResult.NetworkError(e.message ?: "网络错误")
    }
}

fun modelsUrl(baseUrl: String): String {
    val base = baseUrl.trim().trimEnd('/')
    return when {
        base.endsWith("/models") -> base
        base.endsWith("/chat/completions") -> base.removeSuffix("/chat/completions") + "/models"
        else -> "$base/models"
    }
}
