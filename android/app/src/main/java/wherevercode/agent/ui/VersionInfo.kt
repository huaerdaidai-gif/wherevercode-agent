package wherevercode.agent.ui

import wherevercode.agent.BuildConfig

/**
 * V5 C1：真实版本信息。
 *
 * 提交 SHA 与构建时间由 Gradle 在构建时写进 [BuildConfig]（COMMIT_SHA /
 * BUILD_TIME；不在 git 仓库里构建时 COMMIT_SHA 为 "unknown"）。本对象只做
 * 格式化，不做 IO、不读包管理器，所以 JVM 单元测试可以直接覆盖。
 *
 * 显示约定：短 SHA 取完整 SHA 前 7 位；拿不到 SHA 时显示 "unknown"，
 * 拿不到构建时间时显示"未知"，不崩、不显示乱码。
 */
object VersionInfo {

    /** 未知提交的哨兵值（与 build.gradle.kts 里的回退值一致）。 */
    const val UNKNOWN_SHA = "unknown"

    /**
     * 短 SHA：完整 SHA 的前 7 位。
     * 输入为 "unknown"、空白、不足 7 位或含非十六进制字符时返回 [UNKNOWN_SHA]。
     */
    fun shortSha(commitSha: String): String {
        val sha = commitSha.trim()
        if (sha.equals(UNKNOWN_SHA, ignoreCase = true)) return UNKNOWN_SHA
        if (sha.length < 7) return UNKNOWN_SHA
        val short = sha.take(7)
        if (!short.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return UNKNOWN_SHA
        return short
    }

    /**
     * “关于”区追加的两行：短 SHA 与构建时间。
     * 默认读 [BuildConfig]；测试传入显式值，只断言格式与回退行为，不断言具体 SHA。
     */
    fun detailLines(
        commitSha: String = BuildConfig.COMMIT_SHA,
        buildTime: String = BuildConfig.BUILD_TIME,
    ): String {
        val time = buildTime.trim().ifBlank { "未知" }
        return "提交：${shortSha(commitSha)}\n构建时间：$time"
    }

    /**
     * 完整的“关于”文本：保留原有版本行（如 "WhereverCode 超级 Agent ·
     * 0.2.0-ui2（2）"）不动，在其后追加短 SHA 与构建时间。
     */
    fun aboutText(
        versionLine: String,
        commitSha: String = BuildConfig.COMMIT_SHA,
        buildTime: String = BuildConfig.BUILD_TIME,
    ): String = "$versionLine\n${detailLines(commitSha, buildTime)}"
}
