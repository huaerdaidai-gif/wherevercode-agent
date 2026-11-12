package wherevercode.agent.core.autonomy

import android.content.Context
import android.content.SharedPreferences

/**
 * 自主度三档的持久化（V5-UI.md §4）。
 *
 * 存法与 WorkMode.kt 同款：独立 SharedPreferences 文件
 * `wherevercode_autonomy`，只存三个 int；非法存量一律回默认，
 * 绝不崩。读是实时读——门每次决策都重新读档位，所以模式切换
 * 立刻对进行中的任务生效（下一道门按新档位走）；已经批过的决定
 * 不受影响（批准缓存的失效只由 ApprovalBroker.invalidate() 触发，
 * 模式切换绝不调它）。
 */
class AutonomyStore(
    context: Context,
    /** 测试用：覆盖 prefs 文件名（生产用默认名）。 */
    prefsFile: String = PREFS_FILE,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(prefsFile, Context.MODE_PRIVATE)

    companion object {
        /** 持久化文件（与大脑配置、抽屉定制、工作模式分开存，避免互相覆盖）。 */
        const val PREFS_FILE: String = "wherevercode_autonomy"

        const val KEY_UI_FREEDOM: String = "ui_freedom"
        const val KEY_INITIATIVE: String = "initiative"
        const val KEY_EXECUTION_LEVEL: String = "execution_level"
    }

    /** 三档一次读全（门每次决策读一份快照，同一道门内档位一致）。 */
    data class Settings(
        val uiFreedom: UiFreedom,
        val initiative: Initiative,
        val executionLevel: ExecutionLevel,
    )

    fun loadAll(): Settings = Settings(loadUiFreedom(), loadInitiative(), loadExecutionLevel())

    fun loadUiFreedom(): UiFreedom =
        UiFreedom.fromLevel(prefs.getInt(KEY_UI_FREEDOM, UiFreedom.DEFAULT.level))
            ?: UiFreedom.DEFAULT

    fun loadInitiative(): Initiative =
        Initiative.fromLevel(prefs.getInt(KEY_INITIATIVE, Initiative.DEFAULT.level))
            ?: Initiative.DEFAULT

    fun loadExecutionLevel(): ExecutionLevel =
        ExecutionLevel.fromLevel(prefs.getInt(KEY_EXECUTION_LEVEL, ExecutionLevel.DEFAULT.level))
            ?: ExecutionLevel.DEFAULT

    /** 当前档位组合对应的模式（派生值，不另存）。 */
    fun currentMode(): AutonomyMode {
        val settings = loadAll()
        return AutonomyPolicy.modeOf(settings.initiative, settings.executionLevel)
    }

    fun saveUiFreedom(value: UiFreedom) {
        prefs.edit().putInt(KEY_UI_FREEDOM, value.level).apply()
    }

    fun saveInitiative(value: Initiative) {
        prefs.edit().putInt(KEY_INITIATIVE, value.level).apply()
    }

    fun saveExecutionLevel(value: ExecutionLevel) {
        prefs.edit().putInt(KEY_EXECUTION_LEVEL, value.level).apply()
    }

    /**
     * 模式切换：只写主动性 + 执行权限两个预设值（监督：建议 + L0；
     * 全权：高自主 + L4）。UI 自由度不动——它只管显示复杂度。
     * 切完立即生效（门实时读档位），已批过的决定不被改写。
     */
    fun applyMode(mode: AutonomyMode) {
        val (initiative, level) = AutonomyPolicy.presetFor(mode)
        prefs.edit()
            .putInt(KEY_INITIATIVE, initiative.level)
            .putInt(KEY_EXECUTION_LEVEL, level.level)
            .apply()
    }
}
