package wherevercode.agent.ui

import android.content.SharedPreferences

/**
 * 工作模式（V4.3 六种工作模式）。
 *
 * 来源：maintainer 2026-11-10 02:42 纠正——V5 原型图的功能与 V4.3 的功能全部
 * 叠加做新 V5，分步落地。UI 优先按自定义模式（一套 UI 加主题/布局/
 * 模式开关，5 种风格是可切换的模式，不是 5 套 UI）；其他功能也叠加，
 * 按“基础版 - 先跑通”7 项排进后续批次，长期目标 8 项排更后。
 *
 * 本文件只是接线口子（R1，批 3 第一步）：模式 id 的唯一事实来源 +
 * 持久化位。当前只有对话协作已实现，其余五种的切换 UI、终端/编辑器/
 * 监控/自动驾驶/黑客流式都是后续批次的活——这里只定义 id 与存取，
 * 不实现任何模式行为，不画任何切换 UI。
 *
 * 切换 UI 的接线点（第二步）：抽屉“我的工作台”里的工作模式行（R2 的
 * 抽屉按原型图“我的工作台”屏结构做）；读 [loadModeId]、写 [saveModeId]，
 * 非法存量一律回默认，绝不崩。
 */
enum class WorkMode(
    /** 持久化 id（写进 SharedPreferences，不改名）。 */
    val id: String,
    /** 中文名（切换 UI 用）。 */
    val label: String,
    /** 该模式在本批是否已实现（只有对话协作是 true）。 */
    val implemented: Boolean,
) {
    CHAT_COLLAB("chat_collab", "对话协作", true),
    DEV("dev", "开发", false),
    MONITOR("monitor", "监控", false),
    AUTOPILOT("autopilot", "自动驾驶", false),
    HACKER("hacker", "黑客", false),
    CUSTOM("custom", "自定义", false);

    companion object {
        /** 模式 id 持久化文件（与大脑配置、抽屉定制分开存，避免互相覆盖）。 */
        const val PREFS_FILE: String = "wherevercode_work_mode"

        /** 模式 id 键。 */
        const val KEY_MODE_ID: String = "work_mode_id"

        /** 默认模式：对话协作（第一步唯一已实现的模式）。 */
        val DEFAULT: WorkMode = CHAT_COLLAB

        /** 未知 id 返回 null（调用方回默认，不编造模式）。 */
        fun fromId(id: String?): WorkMode? = values().firstOrNull { it.id == id }

        /** 读持久化模式 id；未存/非法值一律回默认。 */
        fun loadModeId(prefs: SharedPreferences): WorkMode {
            val stored = prefs.getString(KEY_MODE_ID, null)
            return fromId(stored) ?: DEFAULT
        }

        /** 写持久化模式 id（只接受已定义的模式）。 */
        fun saveModeId(prefs: SharedPreferences, mode: WorkMode) {
            prefs.edit().putString(KEY_MODE_ID, mode.id).apply()
        }
    }
}
