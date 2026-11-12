package wherevercode.agent.ui

import android.content.SharedPreferences

/**
 * 大脑显示名称（V5 C2-3-R4c 隐私修正，maintainer 05:16/05:22 拍板）。
 *
 * 用户可在设置页“大脑”分节自定义；默认 WhereverCode。用户可见文案
 * 里出现的大脑名都走这里（设置页大脑状态行、帮助页、默认会话标签），
 * 不再写死旧称或通用“大脑”。问候语与大脑名无关——问候固定
 * “你好”，不读已存身份、不显示真人名字。
 *
 * 空白输入恢复默认（删键）。纯映射 + SharedPreferences 读写，不碰
 * 网络、不碰 Keystore、不复用第三方代码。
 */
object BrainDisplayName {
    /** SharedPreferences 文件名与键（只存显示名，无密钥）。 */
    const val PREFS_FILE = "brain_display_name"
    const val KEY = "brain_display_name"

    /** 默认大脑显示名。 */
    const val DEFAULT_NAME = "WhereverCode"

    /**
     * 存量/输入 → 显示名：空白（含全空格）一律回默认；其余 trim 后原样。
     * 真人实名不得作为默认值或测试数据出现——默认只能是 WhereverCode。
     */
    fun resolve(stored: String?): String {
        val trimmed = stored?.trim()
        return if (trimmed.isNullOrEmpty()) DEFAULT_NAME else trimmed
    }

    /** 默认会话标签：按“<名>会话”的形式走设置，默认“WhereverCode会话”。 */
    fun sessionLabel(stored: String?): String = "${resolve(stored)}会话"

    /** 从 SharedPreferences 读当前大脑显示名（未存过即默认）。 */
    fun load(prefs: SharedPreferences): String = resolve(prefs.getString(KEY, null))

    /** 存自定义名；空白输入删键（恢复默认）。 */
    fun save(prefs: SharedPreferences, raw: String) {
        val trimmed = raw.trim()
        with(prefs.edit()) {
            if (trimmed.isEmpty()) remove(KEY) else putString(KEY, trimmed)
            apply()
        }
    }
}
