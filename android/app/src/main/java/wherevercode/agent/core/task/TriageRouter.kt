package wherevercode.agent.core.task

import android.content.Context
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout
import wherevercode.agent.brain.BrainAdapter
import wherevercode.agent.brain.BrainEvent
import wherevercode.agent.brain.ChatMessage
import wherevercode.agent.brain.ChatRequest

/**
 * 端侧小模型路由 v1（2026-11-11，最小实现）。
 *
 * 定位：P1 确定性打分仍是默认主路（不用 LLM 路由器），本模块只加一层
 * 可选的小模型分流——经通用大脑适配器调一个小模型，对任务做意图 /
 * 复杂度分流：复杂任务建议走 P2 流水线，简单任务单 Agent 直接做。
 * 分流只是建议：不改派单、不替用户决定、不挡任务；P2 本身仍是
 * opt-in（用户批 plan 才编码），分流建议永远到不了自动执行。
 *
 * 真机端侧推理（llama.cpp / ONNX + 模型权重进 APK）是大改动，
 * 这次不做：本模块调的是用户已配好的通用大脑里的小模型（云端
 * API 或本机模型服务），真端侧运行时的设计见工作区
 * ONDEVICE_INFERENCE_DESIGN.md，等 maintainer 定了再做。
 *
 * 三条硬线：
 * 1. 默认关闭：[RouterSettings.triageEnabled] 默认 false，
 *    [RouterSettings.triageBrainId] 默认 null；不开就等于没有
 *    这个模块，P1 行为零变化。
 * 2. 默认只用免费档小模型：不写死任何默认模型、不自动选模型；
 *    用户在设置里从自己已配好的通用大脑里挑，设置文案只引导
 *    向免费档（见 [FREE_TIER_HINTS]）；绝不默认用付费模型。
 * 3. 严格回落：小模型不可用 / 超时 / 返回异常 / 解析失败时，
 *    [TriageRunner.run] 返回 null——无声回落到 P1 确定性打分，
 *    绝不报错、绝不卡住、绝不假装分流过。
 *
 * 分流决策的记录：成功的分流以 [TriageRecord] 挂在 P1 的
 * [RoutingDecision.triage] 上，进路由决策日志（SharedPreferences
 * 版做 JSON 往返；见 EvalLog.kt 的 toJson/parseDecision），
 * 供 eval 回看“分流建议准不准”。回落时 triage 为 null——
 * 日志里只剩 P1 决策，不编造分流记录。
 */
enum class TriageMode {
    /** 单 Agent 直接做（默认模式）。 */
    SINGLE,

    /** 建议走 P2 流水线（规划→编码→干净上下文审查）。 */
    PIPELINE,
}

/**
 * 一次成功分流的记录（挂在 [RoutingDecision.triage] 上进日志）。
 * [suggestedMode] 只是建议；[reason] 是小模型给的一句话理由；
 * [modelLabel] 是分流用的大脑名 + 模型（可查用的是哪个小模型）；
 * [latencyMs] 是本次分流调用耗时。
 */
data class TriageRecord(
    val suggestedMode: TriageMode,
    val reason: String,
    val modelLabel: String,
    val latencyMs: Long,
)

/**
 * 分流设置（SharedPreferences 持久化；内存版只供测试与
 * BrainComponents 默认值）。与 eval 日志同一口径：
 * 诊断性设置，丢了也只是回到默认关闭，不影响任务。
 */
interface RouterSettings {
    val triageEnabled: Boolean
    val triageBrainId: String?
    val triageTimeoutMs: Long
    fun save(enabled: Boolean, triageBrainId: String?)
}

class InMemoryRouterSettings(
    override var triageEnabled: Boolean = false,
    override var triageBrainId: String? = null,
    override var triageTimeoutMs: Long = TriageRouter.DEFAULT_TRIAGE_TIMEOUT_MS,
) : RouterSettings {
    @Synchronized
    override fun save(enabled: Boolean, triageBrainId: String?) {
        triageEnabled = enabled
        this.triageBrainId = if (enabled) triageBrainId else null
    }
}

class SharedPreferencesRouterSettings(context: Context) : RouterSettings {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override val triageEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)

    override val triageBrainId: String?
        get() = prefs.getString(KEY_BRAIN_ID, null)?.takeIf { it.isNotBlank() }

    override val triageTimeoutMs: Long
        get() = prefs.getLong(KEY_TIMEOUT_MS, TriageRouter.DEFAULT_TRIAGE_TIMEOUT_MS)
            .takeIf { it > 0 } ?: TriageRouter.DEFAULT_TRIAGE_TIMEOUT_MS

    @Synchronized
    override fun save(enabled: Boolean, triageBrainId: String?) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_BRAIN_ID, if (enabled) triageBrainId else null)
            .apply()
    }

    companion object {
        const val PREFS_NAME: String = "wherevercode_router_settings"
        const val KEY_ENABLED: String = "triage_enabled"
        const val KEY_BRAIN_ID: String = "triage_brain_id"
        const val KEY_TIMEOUT_MS: String = "triage_timeout_ms"
    }
}

object TriageRouter {
    /** 分流调用超时（毫秒）：小模型只做一句话分类，8 秒没回就回落。 */
    const val DEFAULT_TRIAGE_TIMEOUT_MS: Long = 8000L

    /** 分流提示词里任务文本上限（字符）：只喂任务本身，不喂聊天记录，省 token。 */
    const val MAX_TRIAGE_TEXT_CHARS: Int = 2000

    /**
     * 设置文案用的免费档小模型引导（只显示、不默认、不自动选）：
     * 用户从自己已配好的通用大脑里挑；下面这些只是告诉用户
     * “挑这种免费档的就行”。模型 id 以各家官方免费档为准，
     * 文案过期以设置页实际可配的模型列表为准。
     */
    val FREE_TIER_HINTS: List<String> = listOf(
        "GLM-4.7-Flash（智谱免费）",
        "GLM-4.5-Flash（智谱免费）",
        "Cloudflare 免费层模型（如 @cf/meta/llama-3.1-8b-instruct-fp8-fast）",
    )

    /**
     * 分流提示词：只问一件事——这个任务复杂到值得走 P2 流水线吗。
     * 输出格式钉死一行：`SIMPLE：<一句话理由>` 或
     * `COMPLEX：<一句话理由>`。不给例子、不给多轮、不带工具，
     * 模型只做分类，不执行任何操作。
     */
    fun buildPrompt(taskText: String): String {
        val text = taskText.take(MAX_TRIAGE_TEXT_CHARS)
        return "你是任务分流器，只做分类，不执行任务。判断下面这个开发任务" +
            "是否复杂到值得走“规划→编码→干净上下文审查”流水线（多文件、" +
            "高风险、要先出设计），还是单 Agent 直接做就够（单点小改、" +
            "问答、查资料）。\n" +
            "只输出一行，格式：SIMPLE：<一句话理由> 或 COMPLEX：<一句话理由>，" +
            "不要输出其他任何内容。\n" +
            "任务：\n$text"
    }

    /**
     * 严格解析分流回答：首行必须以 SIMPLE 或 COMPLEX 开头
     * （大小写不敏感），冒号后是理由；理由可空（补默认文案）。
     * 其他一切（空串、多行闲聊、没分类、只有理由）都返回 null——
     * 解析失败 = 回落，不猜。
     */
    fun parseResponse(text: String): Pair<TriageMode, String>? {
        val firstLine = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() } ?: return null
        val upper = firstLine.uppercase()
        val mode = when {
            upper.startsWith("SIMPLE") -> TriageMode.SINGLE
            upper.startsWith("COMPLEX") -> TriageMode.PIPELINE
            else -> return null
        }
        val rest = firstLine.substringAfter(":", "").trim()
            .ifBlank { firstLine.substringAfter("：", "").trim() }
        val reason = rest.ifBlank {
            if (mode == TriageMode.PIPELINE) "任务偏复杂，建议走流水线" else "任务简单，单 Agent 直接做"
        }
        return mode to reason
    }
}

/**
 * 分流执行器：调一次小模型，成功返回 [TriageRecord]，
 * 任何失败（未配置、没 Key、超时、报错、空回复、解析失败）
 * 都返回 null。永不抛异常——调用方无需再 try/catch，
 * null 就是“回落到 P1”。
 *
 * 计费诚实：只发一次短分类请求（maxTokens 128）；用量
 * 以适配器回的 Usage 为准，本模块不估算 token。
 */
object TriageRunner {
    suspend fun run(
        adapter: BrainAdapter,
        modelLabel: String,
        taskText: String,
        timeoutMs: Long,
    ): TriageRecord? {
        val startedMs = System.currentTimeMillis()
        return try {
            val prompt = TriageRouter.buildPrompt(taskText)
            val request = ChatRequest(
                messages = listOf(ChatMessage(role = "user", content = prompt)),
                // model 为空 = 用分流大脑自己配置的模型（绝不硬编码模型）。
                model = "",
                maxTokens = 128,
                temperature = 0.0,
            )
            val text = StringBuilder()
            var failed = false
            try {
                withTimeout(timeoutMs) {
                    adapter.chatStream(request).collect { event ->
                        when (event) {
                            is BrainEvent.TextDelta -> text.append(event.text)
                            is BrainEvent.Error -> failed = true
                            else -> Unit
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                return null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                return null
            }
            if (failed) return null
            val parsed = TriageRouter.parseResponse(text.toString()) ?: return null
            TriageRecord(
                suggestedMode = parsed.first,
                reason = parsed.second,
                modelLabel = modelLabel,
                latencyMs = System.currentTimeMillis() - startedMs,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
