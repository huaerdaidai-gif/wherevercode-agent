package wherevercode.agent.brain

import wherevercode.agent.core.security.SecretRedactor

/**
 * 项目长期技能记忆（V5，2026-11-11，maintainer 点名要的“越用越聪明”）。
 *
 * 一个技能 = 一条在某个项目里走通的做法，长期记住，下次同类任务
 * 直接复用，不用从零开始。大脑从自己的经验里学习：做对了强化、
 * 做错了纠正、maintainer 让返工也记进去，用得越多越准。
 *
 * 成本口径（maintainer 2026-11-11 11:18–11:19：该省的省，该花的花）：
 * 技能只捞相关的 —— 取用时按任务内容匹配触发词，不把全部历史
 * 塞进 prompt；每轮最多注入 3 个技能、总计约 300 token（ContextManager
 * 预算 24,000 token 的约 1%），无相关技能时一字不注。
 *
 * 存放：技能属项目（[Skill.projectId]），Room 持久化（`skills` 表，
 * TaskDatabase version 7）；任务未挂项目时记全局（projectId = ""），
 * 取用时项目技能优先、全局技能补位。UI 的项目选择尚未落到
 * TaskRecord.projectId（聊天建的任务 projectId 为空），所以现阶段
 * 技能以全局为主；UI 接线后自动按项目分流，无需改这里。
 */
object SkillMemory {

    /** 每轮最多注入的技能数。 */
    const val MAX_SKILLS_PER_CALL: Int = 3

    /** 每轮注入的技能文本总上限（字符，约 300 token）。 */
    const val MAX_SKILL_TEXT_CHARS: Int = 1200

    /** 单个技能进 prompt 的做法摘要上限（字符）。 */
    const val MAX_SUMMARY_CHARS: Int = 300

    /** 技能可存的触发词上限。 */
    const val MAX_TRIGGERS: Int = 12

    /** 技能可存的证据任务上限（只留最近的）。 */
    const val MAX_EVIDENCE_TASKS: Int = 10

    /** 触发词重叠多少算“同类技能”（Jaccard），用于捕获时合并强化。 */
    const val MERGE_JACCARD_THRESHOLD: Double = 0.4

    /** 全局技能的 projectId（任务未挂项目时用）。 */
    const val GLOBAL_PROJECT_ID: String = ""

    /** 把 null projectId 归一成全局。 */
    fun projectIdOrGlobal(projectId: String?): String = projectId ?: GLOBAL_PROJECT_ID
}

/**
 * 一条技能。
 *
 * @param triggers 触发词/短语（小写归一化后存；取用时子串匹配）。
 * @param summary 1–3 句做法说明（进 prompt 的主体）。
 * @param steps 走通的步骤简写（供人看、供技能页展开）。
 * @param tools 用过的工具名（去重、有序）。
 * @param pitfalls 踩过的坑 / 要避开的（进 prompt，主动避开）。
 * @param evidenceTaskIds 走通的证据任务 id（最近 [SkillMemory.MAX_EVIDENCE_TASKS] 个）。
 * @param successCount 成功次数（强化）；[failureCount] 失败/返工次数（纠正）。
 */
data class Skill(
    val id: String,
    val projectId: String,
    val name: String,
    val triggers: List<String>,
    val summary: String,
    val steps: List<String>,
    val tools: List<String>,
    val pitfalls: List<String>,
    val evidenceTaskIds: List<String>,
    val successCount: Int,
    val failureCount: Int,
    val lastUsedEpochSeconds: Long,
    val createdAtEpochSeconds: Long,
    val updatedAtEpochSeconds: Long,
) {
    /** 成功率（0 成功时为 null，不显示百分比）。 */
    val successRate: Double?
        get() {
            val total = successCount + failureCount
            return if (total == 0) null else successCount.toDouble() / total
        }
}

/**
 * 一个已落终态的任务 + 它的执行证据（捕获技能的输入）。
 *
 * 刻意不用 TaskRecord / TaskStep 类型：brain 层不依赖 core/task
 *（core/task 已反向依赖 brain 的 ConversationDao），避免包循环。
 * 调用方（LocalTaskRunner / ChatViewModel）从 TaskRecord + TaskStep
 * 映射过来，文本先脱敏再传。
 */
data class SettledTaskEvidence(
    val taskId: String,
    val projectId: String?,
    val goal: String,
    val success: Boolean,
    /** 失败原因（已脱敏、已截断）；成功时为空。 */
    val error: String?,
    /** 成功过的工具名（去重、有序）。 */
    val toolsUsed: List<String>,
    /** 关键步骤简写（每条一句话，如 "list_files 列出 android 目录"）。 */
    val keySteps: List<String>,
)

/** 技能持久化接口。实现：Room（[wherevercode.agent.core.task.RoomSkillStore]）与内存。 */
interface SkillStore {
    fun upsert(skill: Skill)
    fun get(id: String): Skill?
    fun remove(id: String)

    /**
     * 列出某项目的技能（含全局技能，projectId = "" 的行），按
     * updatedAtEpochSeconds 倒序。取用层再按任务内容打分过滤。
     */
    fun listForProject(projectId: String): List<Skill>

    /**
     * 按任务内容捞相关技能：触发词命中 + 成功率 + 新近度打分，
     * 只取前 [SkillMemory.MAX_SKILLS_PER_CALL] 个，且仅
     * successCount >= 1 的技能可被取用（只记坑的技能不进 prompt）。
     */
    fun retrieveForTask(projectId: String, taskText: String): List<Skill> =
        SkillRetrieval.retrieve(listForProject(projectId), projectId, taskText)

    /**
     * 捞出的技能渲染成一段可直接进 system prompt 的文本；
     * 无相关技能时返回 ""（不烧 token）。
     */
    fun summarizeForPrompt(projectId: String, taskText: String): String =
        SkillRetrieval.renderForPrompt(retrieveForTask(projectId, taskText))

    /**
     * 本项目积累的做法偏好（不进 prompt，供技能页 / 诊断看）：
     * 成功率最高的技能各一句话。
     */
    fun preferencesSummary(projectId: String): String {
        val top =
            listForProject(projectId)
                .filter { it.successCount >= 1 }
                .sortedWith(
                    compareByDescending<Skill> { it.successCount - it.failureCount }
                        .thenByDescending { it.lastUsedEpochSeconds },
                ).take(3)
        if (top.isEmpty()) return ""
        return top.joinToString("\n") { skill ->
            val rate = skill.successRate?.let { "%.0f%%".format(it * 100) } ?: "—"
            "· ${skill.name}（成功 ${skill.successCount} 次，成功率 $rate）：${skill.summary}"
        }
    }
}

/** 技能取用：打分 + 渲染（纯函数，可单测）。 */
object SkillRetrieval {

    /** 最近多少秒内用过算“新鲜”（7 天），新鲜 +2 分。 */
    private const val RECENT_SECONDS: Long = 7L * 24 * 3600

    fun normalizeText(text: String): String = text.lowercase().trim()

    /**
     * 技能与任务的相关分。0 = 不相关（不取用）。
     * 触发词命中是必要条件：没有触发词命中，即使项目对得上也不取，
     * 避免无关技能白烧 token。
     */
    fun score(
        skill: Skill,
        taskText: String,
        projectId: String,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): Int {
        if (skill.successCount < 1) return 0
        val normalized = normalizeText(taskText)
        if (normalized.isBlank()) return 0
        var triggerHits = 0
        var score = 0
        for (trigger in skill.triggers) {
            val t = trigger.trim().lowercase()
            if (t.isBlank()) continue
            if (normalized.contains(t)) {
                triggerHits++
                score += 10
            }
        }
        if (triggerHits == 0) return 0
        if (skill.projectId.isNotEmpty() && skill.projectId == projectId) score += 5
        score += minOf(skill.successCount, 5)
        score -= minOf(skill.failureCount, 5)
        if (nowSeconds - skill.lastUsedEpochSeconds < RECENT_SECONDS) score += 2
        return score
    }

    /** 取用：打分、只留正分、按分排序、最多前 3 个。 */
    fun retrieve(
        skills: List<Skill>,
        projectId: String,
        taskText: String,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): List<Skill> =
        skills
            .mapNotNull { skill ->
                val s = score(skill, taskText, projectId, nowSeconds)
                if (s > 0) skill to s else null
            }.sortedByDescending { it.second }
            .take(SkillMemory.MAX_SKILLS_PER_CALL)
            .map { it.first }

    /** 渲染成进 prompt 的技能块；总长度卡在上限内，超了截断。 */
    fun renderForPrompt(skills: List<Skill>): String {
        if (skills.isEmpty()) return ""
        val rendered =
            skills.joinToString("\n") { skill ->
                val summary = skill.summary.take(SkillMemory.MAX_SUMMARY_CHARS)
                val pits =
                    skill.pitfalls.take(3).joinToString("；") { it.take(80) }
                val pitText = if (pits.isBlank()) "" else "注意避坑：$pits。"
                val rate =
                    skill.successRate?.let { "成功率 %.0f%%".format(it * 100) } ?: ""
                val rateText = if (rate.isBlank()) "" else "（用过 ${skill.successCount} 次，$rate）"
                "【技能：${skill.name}】$summary$rateText$pitText"
            }
        return rendered.take(SkillMemory.MAX_SKILL_TEXT_CHARS)
    }
}

/** 技能捕获：从任务证据里抽技能（纯函数，可单测）。 */
object SkillCapture {

    /** 触发词里过滤掉的常见词（中英）。 */
    private val STOPWORDS =
        setOf(
            "的", "了", "在", "是", "我", "你", "他", "她", "它", "和", "与", "或",
            "把", "被", "给", "对", "就", "都", "还", "也", "要", "能", "会",
            "可以", "请", "帮", "帮我", "一下", "这个", "那个", "什么", "怎么",
            "如何", "为什么", "一个", "一种", "然后", "再", "先", "去", "来",
            "a", "an", "the", "is", "are", "was", "were", "to", "of", "in",
            "on", "for", "with", "and", "or", "not", "do", "does", "did",
            "can", "could", "should", "would", "please", "help", "me", "my",
            "this", "that", "what", "how", "why", "it", "its", "be", "as",
            "at", "by", "from", "into", "than", "then", "so", "if",
        )

    /**
     * 从任务目标 + 用过的工具抽触发词：拉丁词（>=3 字母）+ 中文
     * 短语（2–8 字，去停用词）+ 工具名（最稳定的信号）。
     */
    fun extractTriggers(goalText: String, toolsUsed: List<String>): List<String> {
        val out = linkedSetOf<String>()
        for (tool in toolsUsed) {
            val t = tool.trim().lowercase()
            if (t.length >= 2) out += t
        }
        val lower = goalText.lowercase()
        // 拉丁词：按非字母数字下划线切。
        lower.split(Regex("[^a-z0-9_]+")).forEach { token ->
            if (token.length >= 3 && token !in STOPWORDS) out += token
        }
        // 中文短语：先把停用词当分隔符，再按标点/空白切，留 2–8 字。
        var cjk = lower
        for (stop in STOPWORDS) {
            if (stop.length >= 2) cjk = cjk.replace(stop, " ")
        }
        cjk.split(Regex("[\\s，。；：！？、,.!?:;()\\[\\]{}<>\"'“”‘’/\\\\|\\-_=+*~`^@#\$%&]+"))
            .forEach { segment ->
                // 段里混着拉丁词时只留中文部分。
                val cjkOnly = segment.filter { it.code in 0x4E00..0x9FFF }
                if (cjkOnly.length in 2..8) out += cjkOnly
            }
        return out.take(SkillMemory.MAX_TRIGGERS)
    }

    /** 技能名：取目标的第一句/第一分句，最多 24 字，截在词边界。 */
    fun deriveName(goalText: String): String {
        val first =
            goalText.trim()
                .split(Regex("[，。；！？\\n]")).firstOrNull().orEmpty().trim()
        if (first.isEmpty()) return "未命名技能"
        if (first.length <= 24) return first
        val cut = first.take(24)
        val lastSpace = cut.lastIndexOf(' ')
        return if (lastSpace > 8) cut.take(lastSpace) else cut
    }

    /** 做法摘要：关键步骤拼成 1–3 句；无步骤时用目标本身。 */
    fun summarizeSteps(goalText: String, keySteps: List<String>): String {
        val body =
            if (keySteps.isEmpty()) {
                goalText.trim()
            } else {
                keySteps.joinToString("；") { it.trim() }
            }
        return SecretRedactor.redact(body, SkillMemory.MAX_SUMMARY_CHARS)
    }

    /**
     * 从一条成功任务建技能。返回 null = 建不出（任务失败、或没有
     * 成功过的工具/步骤，记了也没用）。
     */
    fun buildFromTask(
        evidence: SettledTaskEvidence,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): Skill? {
        if (!evidence.success) return null
        if (evidence.toolsUsed.isEmpty() && evidence.keySteps.isEmpty()) return null
        val projectId = SkillMemory.projectIdOrGlobal(evidence.projectId)
        val name = deriveName(evidence.goal)
        val tools = evidence.toolsUsed.distinct().take(10)
        val steps =
            evidence.keySteps
                .map { SecretRedactor.redact(it.trim(), 120) }
                .filter { it.isNotBlank() }
                .distinct().take(8)
        val triggers = extractTriggers(evidence.goal, tools)
        if (triggers.isEmpty() && steps.isEmpty()) return null
        return Skill(
            id = java.util.UUID.randomUUID().toString(),
            projectId = projectId,
            name = name,
            triggers = triggers,
            summary = summarizeSteps(evidence.goal, steps),
            steps = steps,
            tools = tools,
            pitfalls = emptyList(),
            evidenceTaskIds = listOf(evidence.taskId),
            successCount = 1,
            failureCount = 0,
            lastUsedEpochSeconds = nowSeconds,
            createdAtEpochSeconds = nowSeconds,
            updatedAtEpochSeconds = nowSeconds,
        )
    }

    /** 两条技能的触发词 Jaccard 相似度（捕获时判断是否同类）。 */
    fun triggerJaccard(a: Skill, b: Skill): Double {
        val setA = a.triggers.toSet()
        val setB = b.triggers.toSet()
        if (setA.isEmpty() || setB.isEmpty()) return 0.0
        val inter = setA.intersect(setB).size.toDouble()
        val union = setA.union(setB).size.toDouble()
        return if (union == 0.0) 0.0 else inter / union
    }

    /** 同类技能合并强化：成功 +1，工具/步骤/触发词并集，证据任务追加。 */
    fun mergeInto(existing: Skill, fresh: Skill, nowSeconds: Long): Skill {
        val mergedEvidence =
            (existing.evidenceTaskIds + fresh.evidenceTaskIds)
                .distinct().takeLast(SkillMemory.MAX_EVIDENCE_TASKS)
        return existing.copy(
            tools = (existing.tools + fresh.tools).distinct().take(10),
            steps = (existing.steps + fresh.steps).distinct().take(8),
            triggers = (existing.triggers + fresh.triggers).distinct().take(SkillMemory.MAX_TRIGGERS),
            evidenceTaskIds = mergedEvidence,
            successCount = existing.successCount + 1,
            lastUsedEpochSeconds = nowSeconds,
            updatedAtEpochSeconds = nowSeconds,
        )
    }

    /** 记坑：失败/返工时把原因记进 pitfalls，失败 +1。 */
    fun withPitfall(existing: Skill, pitfall: String, nowSeconds: Long): Skill {
        val clean = SecretRedactor.redact(pitfall.trim(), 120)
        if (clean.isBlank()) return existing
        val pits =
            (listOf(clean) + existing.pitfalls).distinct().take(5)
        return existing.copy(
            pitfalls = pits,
            failureCount = existing.failureCount + 1,
            lastUsedEpochSeconds = nowSeconds,
            updatedAtEpochSeconds = nowSeconds,
        )
    }
}

/**
 * 技能打磨：任务落终态时捕获 / 记坑；返工时纠正。
 *
 * 调用点：[wherevercode.agent.core.task.LocalTaskRunner.finishTask] /
 * finishAsFailed（本地分派任务终态）与
 * [wherevercode.agent.ui.ChatViewModel.finishManusTaskRow]（Manus
 * 任务终态）。返工入口 [onTaskRework] 由审查 UI 落返工按钮时调用。
 */
class SkillRefiner(private val store: SkillStore) {

    /**
     * 任务落终态：成功 → 建技能（同类则合并强化）；失败 → 在最相关
     * 的已有技能上记坑（失败 +1）。返回受影响的技能，null = 没记
     * （建不出技能、或失败时找不到相关技能）。
     */
    fun onTaskSettled(
        evidence: SettledTaskEvidence,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): Skill? {
        val projectId = SkillMemory.projectIdOrGlobal(evidence.projectId)
        if (!evidence.success) {
            val target =
                store.listForProject(projectId)
                    .filter { it.successCount >= 1 }
                    .maxByOrNull { SkillRetrieval.score(it, evidence.goal, projectId, nowSeconds) }
                    ?.takeIf {
                        SkillRetrieval.score(it, evidence.goal, projectId, nowSeconds) > 0
                    } ?: return null
            val pitfall = evidence.error?.ifBlank { null } ?: "任务失败，未留原因"
            val updated = SkillCapture.withPitfall(target, pitfall, nowSeconds)
            store.upsert(updated)
            return updated
        }
        val fresh = SkillCapture.buildFromTask(evidence, nowSeconds) ?: return null
        val similar =
            store.listForProject(projectId)
                .filter { it.successCount >= 1 }
                .maxByOrNull { SkillCapture.triggerJaccard(it, fresh) }
                ?.takeIf { SkillCapture.triggerJaccard(it, fresh) >= SkillMemory.MERGE_JACCARD_THRESHOLD }
        return if (similar != null) {
            val merged = SkillCapture.mergeInto(similar, fresh, nowSeconds)
            store.upsert(merged)
            merged
        } else {
            store.upsert(fresh)
            fresh
        }
    }

    /**
     * 返工（maintainer 的反馈：改回去、重做）：找到含该任务证据的技能，
     * 失败 +1 并记坑。返回 false = 没找到对应的技能（无可纠正）。
     */
    fun onTaskRework(
        taskId: String,
        reason: String,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): Boolean {
        val target =
            store.listForProject(SkillMemory.GLOBAL_PROJECT_ID)
                .firstOrNull { taskId in it.evidenceTaskIds } ?: return false
        store.upsert(SkillCapture.withPitfall(target, "返工：$reason", nowSeconds))
        return true
    }
}

/** 内存技能库（测试与回退用；生产走 Room）。 */
class InMemorySkillStore : SkillStore {
    private val skills = LinkedHashMap<String, Skill>()

    override fun upsert(skill: Skill) {
        skills[skill.id] = skill
    }

    override fun get(id: String): Skill? = skills[id]

    override fun remove(id: String) {
        skills.remove(id)
    }

    override fun listForProject(projectId: String): List<Skill> =
        skills.values
            .filter { it.projectId == projectId || it.projectId == SkillMemory.GLOBAL_PROJECT_ID }
            .sortedByDescending { it.updatedAtEpochSeconds }
}
