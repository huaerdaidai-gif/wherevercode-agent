package wherevercode.agent.update

/** Archive checksum equality is not Android upgrade compatibility. */
data class ApkUpgradeIdentity(
    val packageName: String,
    val versionCode: Long,
    val signerSha256: Set<String>,
)

/** Fail closed. Signing-key rotation needs an explicit, separately verified migration. */
fun apkUpgradeRejection(installed: ApkUpgradeIdentity, candidate: ApkUpgradeIdentity): String? {
    if (candidate.packageName != installed.packageName) {
        return "更新包包名不匹配：正式包与 CI 测试包不能互相覆盖。请由维护者检查更新源。"
    }
    if (candidate.versionCode <= installed.versionCode) {
        return "更新包版本号 ${candidate.versionCode} 不高于已安装版本 ${installed.versionCode}，不会覆盖或降级。请维护者提供更高版本。"
    }
    fun normalized(values: Set<String>) = values.map { it.trim().lowercase() }.toSet()
    val current = normalized(installed.signerSha256)
    val incoming = normalized(candidate.signerSha256)
    val sha256 = Regex("[0-9a-f]{64}")
    if (current.isEmpty() || incoming.isEmpty() || !(current + incoming).all { sha256.matches(it) }) {
        return "无法验证更新包或已安装应用的签名，未安装。请维护者提供历史签名兼容的更新包。"
    }
    if (current != incoming) {
        return "更新包签名与已安装应用不同，不能保留数据覆盖升级。未安装；请维护者使用原签名处理，不要卸载旧版。"
    }
    return null
}
