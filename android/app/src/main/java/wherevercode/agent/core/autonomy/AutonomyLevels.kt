package wherevercode.agent.core.autonomy

/**
 * 自主度三档（V5-UI.md §4，三种独立控制）。
 *
 * maintainer 2026-11-11 22:43 定：他可以监督审批，也可以全部交给大脑、
 * 由大脑作为总管智能处理任务、派发、拆分、审查、协作、测试等自动化。
 * 这三种控制就是两种模式的开关：
 * - 监督模式：主动性 0–2 档、执行权限 L0–L2——plan 批准、派发确认、
 *   审查 verdict 每一道都等用户点；写操作按等级审批。
 * - 全权模式：主动性 3–4 档、执行权限 L3–L4——大脑总管自动批 plan、
 *   自动派发、自动采纳审查 verdict；写操作按等级自动放行。
 *
 * 三档互相独立（V5-UI.md §4）：执行权限独立于 UI 自由度和主动性，
 * 任何高风险动作都须合适授权；UI 自由度只控制显示复杂度，不参与
 * 任何执行决策。
 *
 * 本文件只有纯函数与枚举：不读 SharedPreferences、不碰 Android，
 * 可直接单测。默认值 = 监督模式（审批门全开），与 update9 之前
 * 的行为完全一致（写操作一律审批、派发一律等用户发起）。
 */
enum class UiFreedom(
    /** 持久化值（写进 SharedPreferences，不改名）。 */
    val level: Int,
    /** 中文名（设置页用）。 */
    val label: String,
) {
    NOVICE(0, "小白"),
    ORDINARY(1, "普通"),
    STANDARD(2, "标准"),
    ADVANCED(3, "进阶"),
    EXPERT(4, "高阶");

    companion object {
        /** 默认：标准（设置页首屏只留干净的大脑切换器那一档）。 */
        val DEFAULT: UiFreedom = STANDARD

        /** 未知值返回 null（调用方回默认，绝不崩）。 */
        fun fromLevel(level: Int): UiFreedom? = values().firstOrNull { it.level == level }
    }
}

enum class Initiative(
    /** 持久化值（写进 SharedPreferences，不改名）。 */
    val level: Int,
    /** 中文名（设置页用，V5-UI.md §4 原文）。 */
    val label: String,
) {
    PASSIVE(0, "被动响应"),
    REMIND(1, "提醒"),
    SUGGEST(2, "建议"),
    LIMITED_AUTO(3, "授权范围内有限自主"),
    HIGH_AUTO(4, "高自主");

    companion object {
        /** 默认：建议——大脑给建议，用户做决定（监督模式）。 */
        val DEFAULT: Initiative = SUGGEST

        /** 未知值返回 null（调用方回默认，绝不崩）。 */
        fun fromLevel(level: Int): Initiative? = values().firstOrNull { it.level == level }
    }
}

enum class ExecutionLevel(
    /** 持久化值（写进 SharedPreferences，不改名）。 */
    val level: Int,
    /** 中文名（设置页用）。 */
    val label: String,
) {
    L0(0, "L0 · 只读审批"),
    L1(1, "L1 · 本机写"),
    L2(2, "L2 · 可建分支"),
    L3(3, "L3 · 可开 PR"),
    L4(4, "L4 · 全权");

    companion object {
        /** 默认：L0——任何写操作都要审批（update9 之前行为）。 */
        val DEFAULT: ExecutionLevel = L0

        /** 未知值返回 null（调用方回默认，绝不崩）。 */
        fun fromLevel(level: Int): ExecutionLevel? = values().firstOrNull { it.level == level }
    }
}

/** 两种模式：监督审批，或全部交给大脑总管。 */
enum class AutonomyMode(val label: String) {
    SUPERVISED("监督模式"),
    FULL_AUTO("全权模式"),
}

object AutonomyPolicy {
    /** 返工上限（P2 口径）：2 轮；到了上限一律升级给用户，全权模式也不例外。 */
    const val REWORK_LIMIT: Int = 2

    /** plan 批准是否等用户点：主动性 0–2 等，3–4 自动批。 */
    fun planApprovalRequired(initiative: Initiative): Boolean = initiative.level <= 2

    /** 派发确认是否等用户点：主动性 0–2 等，3–4 自动派发。 */
    fun dispatchConfirmationRequired(initiative: Initiative): Boolean = initiative.level <= 2

    /**
     * 审查 verdict 采纳是否等用户点：主动性 0–2 等；3–4 自动采纳，
     * 但返工已达上限（>= [REWORK_LIMIT]）时一律升级给用户——
     * 全权模式也不能无限返工。
     */
    fun verdictApprovalRequired(initiative: Initiative, reworkRounds: Int): Boolean =
        initiative.level <= 2 || reworkRounds >= REWORK_LIMIT

    /** 当前档位组合对应的模式：主动性 >= 3 且执行权限 >= L3 才是全权模式。 */
    fun modeOf(initiative: Initiative, level: ExecutionLevel): AutonomyMode =
        if (initiative.level >= 3 && level.level >= 3) AutonomyMode.FULL_AUTO
        else AutonomyMode.SUPERVISED

    /** 模式切换的预设档位：切模式只写这两个值，三档仍可之后单独调。 */
    fun presetFor(mode: AutonomyMode): Pair<Initiative, ExecutionLevel> = when (mode) {
        AutonomyMode.SUPERVISED -> Initiative.SUGGEST to ExecutionLevel.L0
        AutonomyMode.FULL_AUTO -> Initiative.HIGH_AUTO to ExecutionLevel.L4
    }

    /**
     * 写操作是否要用户审批（执行权限 L0–L4 的梯子）。
     *
     * - 只读永远自动：read_file / list_files / git 家读 / ask_user
     *   一律 false，与等级无关。
     * - dispatch_task 不走这张表（返回 null）：派发是分派行为，
     *   跟主动性门 [dispatchConfirmationRequired]，不跟 L 级。
     * - 未知工具返回 null：调用方回落到原有 needsApproval
     *   （扩展工具由来源自己决定）。
     * - 梯子（只决定“要不要审批”，绝不放宽命令白名单与路径权限）：
     *   L0 全部写操作都审批；L1 放行本机写文件；L2 放行 git 家写文件
     *   与建分支；L3 放行开 PR 与非只读 run_command；L4 全部放行。
     */
    fun writeNeedsApproval(tool: String, level: ExecutionLevel, isReadOnlyRun: Boolean): Boolean? =
        when (tool) {
            "read_file", "list_files", "list_github_files", "read_github_file", "ask_user" -> false
            "dispatch_task" -> null
            "write_file" -> level.level < 1
            "write_github_file", "create_github_branch" -> level.level < 2
            "create_github_pr" -> level.level < 3
            "run_command" -> if (isReadOnlyRun) false else level.level < 3
            else -> null
        }
}
