package wherevercode.agent.core.task

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import wherevercode.agent.brain.BrainAdapterRegistry
import wherevercode.agent.brain.BrainEngine
import wherevercode.agent.brain.BrainEvent

/**
 * P2 流水线自动执行（2026-11-11）：把 P2 备好的规划者提示词与审查
 * 输入接上真正的 Agent 调用。
 *
 * 两段自动跑、一段人工门不动：
 * - 规划：任务进流水线规划阶段且尚无 plan 草稿时，[runPlanner] 按
 *   P2 的 [PipelineOrchestrator.buildPlannerPrompt] 调规划 Agent，
 *   返回的 plan 结构化落成 [PlanArtifact] 草稿（[PipelineOrchestrator.submitPlan]），
 *   等用户批准——批准门不变，用户不点不进编码。
 * - 审查：编码完成停在 review 后，[runReview] 先跑机械检查（不过直接
 *   打回，不进 LLM 审查），再按 [PipelineOrchestrator.buildReviewInput]
 *   （干净上下文：只给 diff + 任务包 + plan + 测试结果，绝不给聊天记录）
 *   调审查 Agent（[PipelineOrchestrator.selectReviewer] 优先不同 Agent
 *   且不同设备），verdict 落进 pipeline_verdicts 表；changes_requested
 *   走 P0 requestRework，返工上限 2 轮不变（[PipelineOrchestrator.recordReview]）。
 *
 * 诚实规则（与 P2 同款，不编结果）：
 * - plan 解析失败 → 草稿失败态（planStatus=FAILED），不伪造 plan 内容，
 *   不把 Agent 的原文硬塞进结构化字段；任务留 queued 待用户手动提交
 *   plan（详情页对话框仍可用），不自动重试（不烧 token 循环）。
 * - Agent 不回、超时、返回异常、verdict 非法值 → 任务进 failed +
 *   needsAttention，绝不假装通过、绝不静默丢。
 * - 机械检查 FAILED 时不调 LLM 审查（省一次调用，也守顺序）。
 * - 一轮审查只跑一次：本轮已有 verdict 时 [runReview] 直接跳过；
 *   规划草稿已存在（DRAFT/APPROVED/FAILED）时 [runPlanner] 直接跳过。
 * - 泵只在无在途工作时跑（[isWorkInHand]，与 LocalTaskRunner 的
 *   TurnController 单轮规则同款：本机大脑一次只跑一轮）。
 *
 * 事件（经 task_events 进频道事件流；[PipelineStore.saveState] 与
 * 状态写同一事务）：规划开始 / 草稿待批（P2 的 PIPELINE_PLAN_SUBMITTED）
 * / 审查开始 / verdict 落盘（P2 的 PIPELINE_REVIEW_APPROVED /
 * PIPELINE_REWORK_REQUESTED / PIPELINE_REWORK_EXHAUSTED）；失败另记
 * PIPELINE_PLAN_FAILED / PIPELINE_REVIEW_FAILED。
 *
 * [PipelineAgentCaller] 是唯一的 Agent 调用口：生产实现调本机大脑
 * （[BrainEnginePipelineAgentCaller]）或远端 Hand（core/team 的
 * [wherevercode.agent.core.team.RemotePipelineAgentCaller]），
 * [CompositePipelineAgentCaller] 按 [canCall] 路由；测试注入假实现。
 * 调用方只负责“把 prompt 发给某个 Agent 并拿回原文”，解析与落盘
 * 全在这里，不在调用方。
 */

/** 一次 Agent 调用的结果：原文，或真实失败原因。 */
sealed class PipelineAgentCallResult {
    data class Text(val text: String) : PipelineAgentCallResult()
    data class Failure(val error: String) : PipelineAgentCallResult()
}

/**
 * 调一个 Agent 的口。agentId/deviceId 标识“哪个 Agent”：
 * 本机大脑传 brainId（deviceId 为 null）；远端成员传成员 agentId +
 * 配对设备 id。agentId 为 null = 当前默认大脑。调用方拿不到目标
 * Agent 时返回 [PipelineAgentCallResult.Failure]，不抛、不编。
 */
interface PipelineAgentCaller {
    /** 本调用方能不能联系到这个 Agent（路由用；联系不到返回 false）。 */
    fun canCall(agentId: String?, deviceId: String?): Boolean
    suspend fun callAgent(
        taskId: String,
        agentId: String?,
        deviceId: String?,
        prompt: String,
        conversationId: String,
    ): PipelineAgentCallResult
}

/**
 * 本机大脑调用方：一次 [BrainEngine.chat] 轮次（[allowFailover] = false——
 * 选定的审查大脑失败时不许悄悄回落到编码者的大脑，那会毁掉“不同
 * Agent 审查”的意义；规划者同理，失败就如实失败）。
 * 超时（默认 600 秒，与 RemoteTaskDispatcher 同口径）未回 → Failure。
 */
class BrainEnginePipelineAgentCaller(
    private val engine: BrainEngine,
    private val timeoutMs: Long = 600_000,
) : PipelineAgentCaller {
    override fun canCall(agentId: String?, deviceId: String?): Boolean {
        if (deviceId != null) return false
        if (agentId == null) return true
        return BrainAdapterRegistry.configFor(agentId) != null
    }

    override suspend fun callAgent(
        taskId: String,
        agentId: String?,
        deviceId: String?,
        prompt: String,
        conversationId: String,
    ): PipelineAgentCallResult {
        val text = StringBuilder()
        var failure: String? = null
        val finished = withTimeoutOrNull(timeoutMs) {
            try {
                engine.chat(
                    conversationId = conversationId,
                    userText = prompt,
                    brainIdOverride = agentId,
                    allowFailover = false,
                ).collect { event ->
                    when (event) {
                        is BrainEvent.TextDelta -> text.append(event.text)
                        is BrainEvent.Error -> failure = "${event.code}：${event.message}"
                        else -> Unit
                    }
                }
                true
            } catch (e: Exception) {
                failure = "大脑调用异常：${e.message ?: "未知错误"}"
                true
            }
        }
        if (finished == null) {
            return PipelineAgentCallResult.Failure("大脑调用超时（${timeoutMs / 1000} 秒未返回）")
        }
        failure?.let { return PipelineAgentCallResult.Failure(it) }
        val out = text.toString().trim()
        if (out.isEmpty()) return PipelineAgentCallResult.Failure("大脑未返回内容")
        return PipelineAgentCallResult.Text(out)
    }
}

/**
 * 多调用方合成：按 [PipelineAgentCaller.canCall] 顺序路由（生产顺序是
 * [远端, 本机]——远端成员 id 与大脑 id 不在同一命名空间，两边
 * [canCall] 不会同时为 true）；都联系不到 → Failure，不编一个。
 */
class CompositePipelineAgentCaller(
    private val callers: List<PipelineAgentCaller>,
) : PipelineAgentCaller {
    override fun canCall(agentId: String?, deviceId: String?): Boolean =
        callers.any { it.canCall(agentId, deviceId) }

    override suspend fun callAgent(
        taskId: String,
        agentId: String?,
        deviceId: String?,
        prompt: String,
        conversationId: String,
    ): PipelineAgentCallResult {
        val caller = callers.firstOrNull { it.canCall(agentId, deviceId) }
            ?: return PipelineAgentCallResult.Failure(
                "没有能联系到 Agent ${agentId ?: "（默认大脑）"} 的调用方",
            )
        return caller.callAgent(taskId, agentId, deviceId, prompt, conversationId)
    }
}

/** 从 Agent 原文里抽第一个 JSON 对象（```json 围栏、首尾废话都容忍）。 */
private fun extractJsonObject(text: String): String? {
    val start = text.indexOf('{')
    if (start < 0) return null
    val end = text.lastIndexOf('}')
    if (end <= start) return null
    return text.substring(start, end + 1)
}

private fun jsonStringList(arr: org.json.JSONArray?): List<String> {
    if (arr == null) return emptyList()
    val out = mutableListOf<String>()
    for (i in 0 until arr.length()) {
        val s = arr.optString(i, "").trim()
        if (s.isNotEmpty()) out += s
    }
    return out
}

/**
 * 解析规划者返回的 plan：JSON → [PlanArtifact] → [PlanArtifact.validate]。
 * 任一步失败返回 null（调用方落草稿失败态，不伪造、不补字段）。
 */
fun parsePlanJson(text: String): PlanArtifact? {
    val obj = extractJsonObject(text) ?: return null
    return try {
        val plan = PlanArtifact.fromJson(obj)
        if (plan.validate().isEmpty()) plan else null
    } catch (_: Exception) {
        null
    }
}

/** 解析出的审查 verdict（checklist 只在打回时有内容）。 */
data class ParsedReviewVerdict(
    val verdict: String,
    val checklist: List<String> = emptyList(),
)

/**
 * 解析审查者返回的 verdict，只认两值。
 * - 先看 JSON：含 verdict 键的 JSON 对象只认该键的值（非法值直接判
 *   无效，不再从正文里“抢救”一个 approve 出来）；checklist/items
 *   数组是打回清单。
 * - 再看正文：全文恰好出现一种 verdict 词（approve / changes_requested，
 *   按词边界计）才认；两种都出现、或一种都没有 → 无效（歧义不猜）。
 * - changes_requested 必须带非空清单（空清单的打回是无效 verdict，
 *   与 P2 recordReview 的拒绝口径一致）；清单取 verdict 词所在行之
 *   后的非空行，去“- /• /1. ”前缀，最多 20 条、每条 ≤200 字。
 */
fun parseReviewVerdict(text: String): ParsedReviewVerdict? {
    val trimmed = text.trim()
    val obj = extractJsonObject(trimmed)
    if (obj != null) {
        try {
            val json = JSONObject(obj)
            if (json.has("verdict")) {
                val verdict = json.optString("verdict", "").trim().lowercase()
                if (!ReviewVerdict.isValid(verdict)) return null
                val checklist = jsonStringList(
                    json.optJSONArray("checklist") ?: json.optJSONArray("items"),
                )
                if (verdict == ReviewVerdict.CHANGES_REQUESTED && checklist.isEmpty()) return null
                return ParsedReviewVerdict(verdict, checklist)
            }
        } catch (_: Exception) {
            // 不是合法 JSON，走正文词扫描。
        }
    }
    val lower = trimmed.lowercase()
    val approveCount = Regex("\\bapprove\\b").findAll(lower).count()
    val reworkCount = Regex("\\bchanges_requested\\b").findAll(lower).count()
    if (approveCount > 0 && reworkCount == 0) {
        return ParsedReviewVerdict(ReviewVerdict.APPROVE)
    }
    if (reworkCount > 0 && approveCount == 0) {
        val lines = trimmed.lines()
        val at = lines.indexOfFirst { it.lowercase().contains("changes_requested") }
        val items = lines.drop(at + 1)
            .map { it.trim().removePrefix("- ").removePrefix("• ").removePrefix("* ") }
            .map { it.replace(Regex("^\\d+[.、]\\s*"), "") }
            .filter { it.isNotEmpty() }
            .take(20)
            .map { it.take(200) }
        if (items.isEmpty()) return null
        return ParsedReviewVerdict(ReviewVerdict.CHANGES_REQUESTED, items)
    }
    return null
}

/** 自动审查的输入来源（调用方提供；默认全空 = 如实“未提供”）。 */
data class PipelineReviewInputs(
    /** diff 摘要（成员回传的 diffSummary；没有时 null，审查输入如实写“未提供”）。 */
    val diffSummary: String? = null,
    /** 改动文件清单（write_file 步骤抽出，或成员回传的 files；没有时空清单）。 */
    val changedFiles: List<String> = emptyList(),
    /** 测试 verdict（成员回传；本机路径今天没有，如实 NOT_RUN）。 */
    val testVerdict: PipelineTestVerdict = PipelineTestVerdict.NOT_RUN,
)

class PipelineRunner(
    private val taskStore: TaskStore,
    private val orchestrator: PipelineOrchestrator,
    private val pipelineStore: PipelineStore,
    private val caller: PipelineAgentCaller,
    /** 审查者候选（生产：已配置大脑 + 远端可达成员；默认空 = 审查如实报无审查者可用）。 */
    private val reviewerCandidates: () -> List<PipelineOrchestrator.ReviewerCandidate> = { emptyList() },
    /** 每任务的审查输入（生产：步骤抽文件清单；默认全空）。 */
    private val reviewInputs: (TaskRecord) -> PipelineReviewInputs = { PipelineReviewInputs() },
    /** 有在途工作（assigned/working）时泵 defer（本机大脑一次只跑一轮）。 */
    private val isWorkInHand: () -> Boolean = { false },
    private val pollIntervalMs: Long = 10_000,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    sealed class PlannerResult {
        data class DraftReady(val plan: PlanArtifact) : PlannerResult()
        data class DraftFailed(val reason: String) : PlannerResult()
        data class Skipped(val reason: String) : PlannerResult()
    }

    sealed class ReviewerResult {
        data object Approved : ReviewerResult()
        data class ChangesRequested(val reworkRounds: Int) : ReviewerResult()
        data class Failed(val reason: String) : ReviewerResult()
        data class Skipped(val reason: String) : ReviewerResult()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private val lock = Any()
    private val inFlight = HashSet<String>()

    @Volatile
    var onTasksChanged: (() -> Unit)? = null

    /** 泵在跑一轮规划/审查时为 true（聊天侧可据此拒发新轮次，见 LocalTaskRunner.busy 同款）。 */
    @Volatile
    private var busy: Boolean = false

    fun isBusy(): Boolean = busy

    fun start() {
        synchronized(lock) {
            if (loopJob?.isActive == true) return
            loopJob = scope.launch {
                while (isActive) {
                    try {
                        pumpOnce()
                    } catch (_: Exception) {
                        // 单轮失败不杀泵；下一轮继续。
                    }
                    try {
                        delay(pollIntervalMs)
                    } catch (_: kotlinx.coroutines.CancellationException) {
                        break
                    }
                }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            loopJob?.cancel()
            loopJob = null
        }
    }

    /**
     * 单轮取活：先扫 review 态的流水线任务（审查先行，尽早给 verdict），
     * 再扫 plan 态无草稿的任务（自动起草）。一轮只处理一个任务（本机
     * 大脑一次一轮；远端调用也不并行，避免 verdict 乱序）。
     * 有在途工作时整轮 defer。返回 true = 本轮跑了一个任务。
     */
    suspend fun pumpOnce(): Boolean {
        if (isWorkInHand()) return false
        val tasks = try {
            taskStore.listTasks()
        } catch (_: Exception) {
            return false
        }
        val reviewDue = tasks.firstOrNull { task ->
            val state = pipelineStateOf(task.taskId) ?: return@firstOrNull false
            state.enabled && state.stage == PipelineStage.REVIEW && task.state == "review"
        }
        if (reviewDue != null) {
            return when (runReview(reviewDue.taskId)) {
                is ReviewerResult.Skipped -> false
                else -> true
            }
        }
        val planDue = tasks.firstOrNull { task ->
            val state = pipelineStateOf(task.taskId) ?: return@firstOrNull false
            state.enabled && state.stage == PipelineStage.PLAN &&
                state.planStatus == PipelinePlanStatus.NONE
        }
        if (planDue != null) {
            return when (runPlanner(planDue.taskId)) {
                is PlannerResult.Skipped -> false
                else -> true
            }
        }
        return false
    }

    /**
     * 规划者自动起草：调规划 Agent → 结构化 plan 草稿 → 等用户批准。
     * 只在“规划阶段且尚无草稿”时跑；已有草稿/草稿失败/已批准一律跳过
     * （FAILED 不自动重试——用户手动提交 plan 仍可用）。
     */
    suspend fun runPlanner(taskId: String): PlannerResult {
        val task = taskStore.getTask(taskId)
            ?: return PlannerResult.Skipped("任务不存在：$taskId")
        val state = pipelineStore.getState(taskId)
            ?: return PlannerResult.Skipped("任务未启用流水线")
        if (!state.enabled) return PlannerResult.Skipped("任务未启用流水线")
        if (state.stage != PipelineStage.PLAN) {
            return PlannerResult.Skipped("当前阶段是${PipelineStage.labelOf(state.stage)}，不在规划")
        }
        when (state.planStatus) {
            PipelinePlanStatus.DRAFT -> return PlannerResult.Skipped("已有 plan 草稿待批准")
            PipelinePlanStatus.APPROVED -> return PlannerResult.Skipped("plan 已批准")
            PipelinePlanStatus.FAILED -> return PlannerResult.Skipped("plan 草稿已失败，等待用户手动提交")
        }
        if (!beginFlight(taskId)) return PlannerResult.Skipped("规划已在进行中")
        busy = true
        try {
            recordEvent(task, TaskEventKind.PIPELINE_PLAN_STARTED, task.brainId ?: "planner", "规划者开始起草 plan")
            val prompt = orchestrator.buildPlannerPrompt(task)
            val result = try {
                caller.callAgent(
                    taskId = taskId,
                    agentId = task.brainId,
                    deviceId = null,
                    prompt = prompt,
                    conversationId = "pipeline-plan-$taskId",
                )
            } catch (e: Exception) {
                PipelineAgentCallResult.Failure("规划者调用异常：${e.message ?: "未知错误"}")
            }
            val raw = when (result) {
                is PipelineAgentCallResult.Failure -> {
                    val reason = "规划者未返回可用 plan：${result.error}"
                    markPlanFailed(task, reason)
                    return PlannerResult.DraftFailed(reason)
                }
                is PipelineAgentCallResult.Text -> result.text
            }
            val plan = parsePlanJson(raw)
            if (plan == null) {
                val reason = "plan 解析失败：规划者返回内容不是可用 plan（目标/步骤/验收标准缺失或 JSON 损坏），未伪造草稿"
                markPlanFailed(task, reason)
                return PlannerResult.DraftFailed(reason)
            }
            val submitted = orchestrator.submitPlan(taskId, plan, task.brainId ?: "planner")
            if (submitted is PipelineOrchestrator.PipelineResult.Rejected) {
                val reason = "plan 草稿提交被拒：${submitted.reason}"
                markPlanFailed(task, reason)
                return PlannerResult.DraftFailed(reason)
            }
            onTasksChanged?.invoke()
            return PlannerResult.DraftReady(plan)
        } finally {
            busy = false
            endFlight(taskId)
        }
    }

    /**
     * 审查者自动执行：机械检查先于 LLM 审查；审查 verdict 落盘。
     * 机械 FAILED → 走 P2 的机械门打回（不调 LLM）；无审查者 / Agent
     * 不回 / verdict 非法 → 任务 failed + needsAttention。
     */
    suspend fun runReview(taskId: String): ReviewerResult {
        val task = taskStore.getTask(taskId)
            ?: return ReviewerResult.Skipped("任务不存在：$taskId")
        val state = pipelineStore.getState(taskId)
            ?: return ReviewerResult.Skipped("任务未启用流水线")
        if (!state.enabled) return ReviewerResult.Skipped("任务未启用流水线")
        if (state.stage != PipelineStage.REVIEW) {
            return ReviewerResult.Skipped("当前阶段是${PipelineStage.labelOf(state.stage)}，不在审查")
        }
        if (task.state != "review") {
            return ReviewerResult.Skipped("任务不在 review 态（当前 ${task.state}），不能审查")
        }
        val plan = state.approvedPlan
            ?: return failReview(task, "没有已批准的 plan，无法审查")
        val lastVerdict = try {
            pipelineStore.listVerdicts(taskId).lastOrNull()
        } catch (_: Exception) {
            return ReviewerResult.Skipped("verdict 历史读失败，不重复审查")
        }
        if (lastVerdict != null && lastVerdict.round == state.reworkRounds) {
            return ReviewerResult.Skipped("本轮已有 verdict，不重复审查")
        }
        if (!beginFlight(taskId)) return ReviewerResult.Skipped("审查已在进行中")
        busy = true
        try {
            recordEvent(task, TaskEventKind.PIPELINE_REVIEW_STARTED, state.coderAgentId ?: "pipeline-runner", "审查开始（机械检查先行）")
            val inputs = reviewInputs(task)
            val mechanical = orchestrator.runMechanicalChecks(taskId, inputs.changedFiles, inputs.testVerdict)
                ?: return failReview(task, "没有已批准的 plan，无法运行机械检查")
            if (!mechanical.passed) {
                val checklist = mechanical.failed.map { "${it.name}：${it.detail}" }
                val recorded = orchestrator.recordReview(
                    taskId, ReviewVerdict.CHANGES_REQUESTED,
                    PipelineOrchestrator.MECHANICAL_REVIEWER, checklist, mechanical,
                )
                if (recorded is PipelineOrchestrator.PipelineResult.Rejected) {
                    return failReview(task, "机械检查打回落盘失败：${recorded.reason}")
                }
                onTasksChanged?.invoke()
                return afterRework(taskId, "机械检查不通过，已打回返工")
            }
            val chosen = orchestrator.selectReviewer(
                state.coderAgentId, state.coderDeviceId, reviewerCandidates(),
            ) ?: return failReview(task, "无审查者可用：没有已配置的大脑或可达的远端成员")
            val prompt = orchestrator.buildReviewInput(task, plan, inputs.diffSummary, inputs.changedFiles, mechanical)
            val result = try {
                caller.callAgent(
                    taskId = taskId,
                    agentId = chosen.agentId,
                    deviceId = chosen.deviceId,
                    prompt = prompt,
                    conversationId = "pipeline-review-$taskId-r${state.reworkRounds}",
                )
            } catch (e: Exception) {
                PipelineAgentCallResult.Failure("审查者调用异常：${e.message ?: "未知错误"}")
            }
            val raw = when (result) {
                is PipelineAgentCallResult.Failure ->
                    return failReview(task, "审查者未返回 verdict：${result.error}")
                is PipelineAgentCallResult.Text -> result.text
            }
            val parsed = parseReviewVerdict(raw)
                ?: return failReview(task, "审查者返回的 verdict 无法解析（只认 approve / changes_requested，打回须带清单），未伪造 verdict")
            val recorded = orchestrator.recordReview(
                taskId, parsed.verdict, chosen.agentId, parsed.checklist, mechanical,
            )
            if (recorded is PipelineOrchestrator.PipelineResult.Rejected) {
                return failReview(task, "审查 verdict 落盘被拒：${recorded.reason}")
            }
            onTasksChanged?.invoke()
            if (parsed.verdict == ReviewVerdict.APPROVE) return ReviewerResult.Approved
            return afterRework(taskId, "审查打回返工")
        } finally {
            busy = false
            endFlight(taskId)
        }
    }

    /** 打回落盘后看任务态：rework = 再一轮编码；failed = 已达 2 轮上限。 */
    private fun afterRework(taskId: String, note: String): ReviewerResult {
        val task = taskStore.getTask(taskId)
        val state = pipelineStore.getState(taskId)
        if (task?.state == "failed") {
            return ReviewerResult.Failed(task.error ?: "返工已达 ${PipelineOrchestrator.MAX_REWORK_ROUNDS} 轮上限，任务进 failed")
        }
        if (task?.state == "rework") {
            return ReviewerResult.ChangesRequested(state?.reworkRounds ?: 0)
        }
        return ReviewerResult.Failed("打回落盘后任务态异常（当前 ${task?.state ?: "任务不存在"}）：$note")
    }

    /** 审查失败：任务进 failed + needsAttention（绝不假装通过）。 */
    private fun failReview(task: TaskRecord, reason: String): ReviewerResult {
        failTask(task, TaskEventKind.PIPELINE_REVIEW_FAILED, "pipeline-runner", reason)
        onTasksChanged?.invoke()
        return ReviewerResult.Failed(reason)
    }

    /** plan 起草失败：草稿失败态 + 任务挂 needsAttention，任务态不动（留 queued 待手动提交）。 */
    private fun markPlanFailed(task: TaskRecord, reason: String) {
        val fresh = pipelineStore.getState(task.taskId) ?: return
        if (!fresh.enabled || fresh.stage != PipelineStage.PLAN) return
        if (fresh.planStatus != PipelinePlanStatus.NONE) return
        val next = fresh.copy(
            planStatus = PipelinePlanStatus.FAILED,
            updatedAtEpochSeconds = nowSeconds(),
        )
        try {
            pipelineStore.saveState(
                next,
                runnerEvent(task, TaskEventKind.PIPELINE_PLAN_FAILED, task.brainId ?: "planner", reason),
            )
        } catch (_: Exception) {
            // 事件/状态落盘失败：草稿失败态没记下，泵下一轮会重试一次
            // （planStatus 仍是 NONE）。不静默丢——调用方拿到 DraftFailed。
        }
        try {
            val current = taskStore.getTask(task.taskId) ?: return
            taskStore.updateTask(
                task.taskId,
                mapOf("error" to reason.take(4000), "needs_attention" to true),
                current.version, current.goalVersion, current.authEpoch,
            )
        } catch (_: Exception) {
            // 挂旗失败不影响草稿失败态本身。
        }
    }

    /** 任务进 failed + needsAttention（审查失败路径）。 */
    private fun failTask(task: TaskRecord, kind: String, actor: String, reason: String) {
        val current = try {
            taskStore.getTask(task.taskId)
        } catch (_: Exception) {
            null
        } ?: return
        if (TaskStates.isTerminal(current.state)) return
        val updated = try {
            taskStore.updateTask(
                task.taskId,
                mapOf(
                    "state" to "failed",
                    "error" to reason.take(4000),
                    "needs_attention" to true,
                ),
                current.version, current.goalVersion, current.authEpoch,
            )
        } catch (_: Exception) {
            null
        } ?: return
        // 任务态落 failed 时 updateTask 已记一条 failed 事件；这里再记
        // 一条流水线失败事件（说明是哪一步失败的），两条都不丢。
        try {
            pipelineStore.getState(task.taskId)?.let {
                pipelineStore.saveState(it, runnerEvent(updated, kind, actor, reason))
            }
        } catch (_: Exception) {
            // 流水线事件没记下不影响 failed 本身。
        }
    }

    /** 阶段审计事件（状态不动，只记事件；与状态写同一事务）。 */
    private fun recordEvent(task: TaskRecord, kind: String, actor: String, reason: String) {
        val state = try {
            pipelineStore.getState(task.taskId)
        } catch (_: Exception) {
            null
        } ?: return
        try {
            pipelineStore.saveState(state, runnerEvent(task, kind, actor, reason))
        } catch (_: Exception) {
            // 事件没记下不杀本次执行；审查/verdict 的关键事件由
            // recordReview 在同一事务里记，不走这里。
        }
    }

    private fun runnerEvent(task: TaskRecord, kind: String, actor: String?, reason: String?): TaskEvent =
        TaskEvent(
            eventId = UUID.randomUUID().toString(),
            taskId = task.taskId,
            attemptId = task.attemptId,
            kind = kind,
            fromState = task.state,
            toState = task.state,
            actor = actor,
            reason = reason,
            createdAtEpochSeconds = nowSeconds(),
        )

    private fun pipelineStateOf(taskId: String): PipelineState? = try {
        pipelineStore.getState(taskId)
    } catch (_: Exception) {
        null
    }

    private fun beginFlight(taskId: String): Boolean = synchronized(lock) {
        inFlight.add(taskId)
    }

    private fun endFlight(taskId: String) {
        synchronized(lock) {
            inFlight.remove(taskId)
        }
    }
}
