package wherevercode.agent.ui

/**
 * 首次使用引导三屏模型（V5 Phase B C2，批 3，R3）。
 *
 * 依据：V5 原型图（v5-prototype-20261110.png）上排前 4 屏——欢迎页 →
 * 1/3 选择使用方向 → 2/3 无线调试 ADB 引导 → 3/3 准备完成 →
 * 聊天首页。首批默认浅色极简（v4.3-01）；颜色/圆角/间距一律走
 * 主题 token，见 [OnboardingView]，本模型不管 View。
 *
 * 纯 JVM 逻辑，不含 Android View。持久化只经过 [Store]（宿主用
 * SharedPreferences 实现，最轻，不为引导新建 Room 表）。
 *
 * 诚实规矩（批 3 硬约束）：
 * - 3/3 的三行状态只渲染宿主传入的 [ReadinessRows]；没有检测手段
 *   的一律是 [Readiness.NOT_CHECKED]（“未检测”），绝不写死“可用”。
 * - 方向选择只存不联动：首页问候/建议 pill 按方向调整是后续批次，
 *   本步 [selectDirection] 只落盘。
 * - ADB 配对接线不在本步：2/3 的“一键引导设置”只做引导动作（宿主
 *   打开系统开发者设置），不声称已配对；配对是 C3/A1 的活。
 * - 回看入口不在本步：设置页不加“重新查看引导”，记为 TODO（见
 *   MainActivity 接线注释），后续批次再补。
 */
class OnboardingController(private val store: Store) {

    /** 持久化口子（宿主：SharedPreferences；测试：内存假实现）。 */
    interface Store {
        fun isCompleted(): Boolean
        fun setCompleted()
        fun directionId(): String?
        fun setDirectionId(id: String?)
    }

    /** 四屏：欢迎页 + 编号三屏（原型图 “1/3”“2/3”“3/3”）。 */
    enum class Step {
        WELCOME,
        DIRECTION,
        ADB,
        DONE,
    }

    /** 使用方向（原型图 1/3 五项；id 稳定，落盘只存 id）。 */
    enum class Direction(val id: String, val title: String, val subtitle: String) {
        EASY("easy", "轻松体验", "聊天、问答、日常助手"),
        CREATE("create", "开发创造", "代码、项目、测试、构建"),
        LEARN("learn", "学习研究", "资料整理、分析与报告"),
        WORK("work", "工作经营", "任务、流程与团队协作"),
        DEVICE("device", "设备指挥", "连接设备、远程执行与调度"),
    }

    /** 3/3 状态行的诚实三态：可用 / 未配置 / 未检测。 */
    enum class Readiness {
        AVAILABLE,
        NOT_CONFIGURED,
        NOT_CHECKED,
    }

    /** 3/3 三行状态（宿主按真实配置/检测填；未知一律 NOT_CHECKED）。 */
    data class ReadinessRows(
        val brain: Readiness,
        val execEnv: Readiness,
        val device: Readiness,
    )

    companion object {
        /** 原型图原文案（视图与测试共用同一份常量，不各写一份）。 */
        const val WELCOME_TITLE = "WhereverCode-agent"
        const val WELCOME_VERSION = "V5"
        const val WELCOME_HEADLINE = "你的超级 Agent"
        const val WELCOME_FEATURES = "对话 · 执行 · 协作 · 交付"
        const val WELCOME_SUBTITLE = "在手机上掌控你的 AI 团队"
        const val WELCOME_START = "开始使用 →"
        const val WELCOME_IMPORT = "已有账号或配置？导入 / 恢复"
        const val WELCOME_LATER = "稍后再看"

        const val DIRECTION_TITLE = "选择你的使用方向"
        const val DIRECTION_NEXT = "下一步 →"
        const val DIRECTION_SKIP = "跳过"

        const val ADB_TITLE = "解锁手机执行能力"
        const val ADB_RECOMMEND = "推荐开启：无线调试（ADB）"
        const val ADB_BENEFIT = "无需 Root，无需 Termux，即可让 Agent 获得更强的执行能力。"
        const val ADB_LATER_NOTE = "（可在稍后在「设备」中开启）"
        val ADB_STEPS: List<String> = listOf(
            "1  开启“开发者选项”",
            "2  打开“无线调试”",
            "3  在本 App 中一键配对",
        )
        const val ADB_GUIDE = "一键引导设置"
        const val ADB_GUIDE_NOTE = "本步只打开系统设置做引导；无线配对接线在后续批次，暂未实现。"
        const val ADB_DONE_CONTINUE = "我已按步骤开启，继续 →"
        const val ADB_SKIP = "跳过，先体验基础功能"

        const val DONE_TITLE = "准备完成"
        const val DONE_HEADLINE = "基础环境已就绪！"
        const val DONE_SUBTITLE = "你现在可以开始与 Agent 对话、执行任务、管理项目或继续完善配置。"
        const val DONE_BRAIN = "Agent 大脑"
        const val DONE_EXEC_ENV = "基础执行环境"
        const val DONE_DEVICE = "设备连接"
        const val DONE_ENTER = "进入工作台 →"

        const val IMPORT_TODO = "导入 / 恢复待开发"

        /** 方向 id → 方向；非法/空存量回 null（默认），绝不崩。 */
        fun directionFromId(id: String?): Direction? =
            Direction.values().firstOrNull { it.id == id }

        /** 诚实标签：可用 / 未配置 / 未检测（无检测手段绝不写“可用”）。 */
        fun readinessLabel(readiness: Readiness): String = when (readiness) {
            Readiness.AVAILABLE -> "可用"
            Readiness.NOT_CONFIGURED -> "未配置"
            Readiness.NOT_CHECKED -> "未检测"
        }
    }

    private var step: Step = Step.WELCOME

    /** 首次启动才出现：已完成过的用户直接进聊天首页，不再弹引导。 */
    fun shouldShow(): Boolean = !store.isCompleted()

    fun step(): Step = step

    fun setStep(next: Step) {
        step = next
    }

    /** 当前已选方向（非法存量回 null，即未选）。 */
    fun selectedDirection(): Direction? = directionFromId(store.directionId())

    /**
     * 选方向并立即落盘（只存不联动）。非法 id 返回 false、不改存量；
     * 合法 id 返回 true。
     */
    fun selectDirection(id: String): Boolean {
        if (directionFromId(id) == null) return false
        store.setDirectionId(id)
        return true
    }

    /**
     * 前进一屏：欢迎 → 方向 → ADB → 完成。已在完成屏时 [next] 即
     * 记完成（与 [finish] 同效），返回 false 表示流程已结束。
     */
    fun next(): Boolean {
        step = when (step) {
            Step.WELCOME -> Step.DIRECTION
            Step.DIRECTION -> Step.ADB
            Step.ADB -> Step.DONE
            Step.DONE -> {
                finish()
                return false
            }
        }
        return true
    }

    /** 后退一屏；欢迎页已无上一屏，返回 false。 */
    fun back(): Boolean {
        step = when (step) {
            Step.WELCOME -> return false
            Step.DIRECTION -> Step.WELCOME
            Step.ADB -> Step.DIRECTION
            Step.DONE -> Step.ADB
        }
        return true
    }

    /** 任何一屏跳过都算走完流程：记完成，不再弹。 */
    fun skip() {
        finish()
    }

    /** 走完流程：记完成；3/3 的“进入工作台”与各屏跳过都走这里。 */
    fun finish() {
        store.setCompleted()
    }
}
