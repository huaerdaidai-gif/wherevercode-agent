package wherevercode.agent.core.team

/**
 * 远端成员汇报的诚实 verdict（2026-11-11，任务 692638e2 教训）。
 *
 * Hand 的 result 帧只带 `output`（Agent CLI 的 stdout 全文），没有
 * 成功/失败标志；CLI 在正文里说失败也照样走 Result。且 Codex 这次
 * 是退出码 0 但正文报失败（Hand 服务端只在 returncode != 0 时发
 * error 帧），所以退出码也救不了，必须看正文。
 *
 * 这只是首行启发式：只认开头陈述（首个非空行、前 120 字内）的显式
 * 失败表述；失败写在第三段以后的长汇报会误判成功。持久解法是 Hand
 * 的 result 帧加 verdict/exit_code 字段（Hand 侧改动，不在本分支做）。
 *
 * 同一分类器也用于本地执行（[wherevercode.agent.core.task.LocalTaskRunner]）：
 * 生产执行器只按“轮次跑完且无 BrainEvent.Error”报 success，工具报错
 * 只走 ToolResult(ok=false)、不影响 success——2026-11-11 22:46 的
 * 本地重试就是这么把失败记成完成的。两条执行路径最终都以执行
 * verdict 为准，不以“跑完了”为准。
 *
 * 2026-11-12（重试任务 4f3b597c-ae0d，父 692638e2）：又一种假完成——
 * 结果正文第一行就写“在当前工作区 … 中没有找到 wherevercode-agent
 * 仓库或 Git 元数据，因此尚未创建分支、文件或提交。请提供该仓库在
 * 此环境中的路径”，工作明说没做完，任务却记成 completed——旧的
 * [FAILURE_MARKERS] 里没有“没找到/尚未创建/请提供”这类“需要信息/
 * 被挡住”的表述。本次补 [NEED_INFO_PATTERNS]：开头陈述明确表示
 * 工作未完成、被挡住、需要信息时，同样记 Failed，不得记 completed。
 * 误判护栏：宾语收紧到仓库/文件/目录/分支/路径/元数据等工作对象，
 * “没有发现问题”“没有找到回归”这类成功表述不在宾语表里，不会误判；
 * 行内反引号/引号包裹的标识符（如仓库名）先剥掉再匹配，避免名字
 * 长度把宾语推出匹配窗口。
 *
 * 结构化输出（JSON）不按自然语言失败表述判定：正文首行以 `{`、`[`
 * 或 `` ``` `` 开头时直接判 Success，合法性由调用方的解析器
 * （plan/verdict JSON 解析）判断——plan 与审查 verdict 的解析失败
 * 本来就会落 failed，分类器不再抢判。
 */
sealed class RemoteMemberVerdict {
    /** 开头没有显式失败表述：按成功处理（走原有 review 门）。 */
    object Success : RemoteMemberVerdict()

    /** 开头有显式失败表述，或远端返回空结果。[reason] 是全文（调用方截断后落库）。 */
    data class Failed(val reason: String) : RemoteMemberVerdict()
}

object RemoteMemberVerdictClassifier {
    /** 空/空白 output 的失败原因（调用方直接当 error 落库）。 */
    const val EMPTY_OUTPUT_REASON: String = "远端返回空结果，未收到可用的执行汇报"

    /** 本地执行空结果的失败原因（与远端文案区分，不写“远端”）。 */
    const val LOCAL_EMPTY_OUTPUT_REASON: String = "本地执行未返回任何结果，未收到可用的执行汇报"

    /** 只看开头陈述的窗口：首个非空行去标记后的前 120 字。 */
    const val HEADLINE_LIMIT: Int = 120

    private val FAILURE_MARKERS: List<String> = listOf(
        // 中文（大小写不敏感；英文统一小写后匹配）。
        "未能完成", "无法完成", "未完成", "执行失败", "任务失败", "操作失败", "失败了",
        // 英文。
        "failed to", "could not complete", "couldn't complete",
        "unable to complete", "did not complete", "was not completed",
        "task failed", "execution failed", "operation failed",
    )

    /**
     * 需要信息 / 被挡住的表述（2026-11-12 补）：同样表示工作没做完。
     * 全部是正则：动词后留一段再接宾语——宾语只认工作对象
     * （仓库/文件/目录/分支/路径/元数据），“没有发现问题”“没有找到
     * 回归”这类成功表述不会命中。“找到”类动词后留 40 字（仓库名、
     * 路径本身就二三十字）；“请提供”留 20 字；“需要/缺少”宾语紧贴，
     * 留 10 字。匹配前先剥掉行内反引号/双引号包裹的标识符
     * （见 [stripQuotedSegments]），带反引号的仓库名不占窗口。
     */
    private val NEED_INFO_PATTERNS: List<Regex> = listOf(
        // 中文。
        Regex("""没有找到.{0,40}(仓库|文件|目录|分支|路径|元数据)"""),
        Regex("""找不到.{0,40}(仓库|文件|目录|分支|路径|元数据)"""),
        Regex("""未找到.{0,40}(仓库|文件|目录|分支|路径|元数据)"""),
        Regex("""尚未(创建|完成|提交|执行|开始)"""),
        Regex("""请提供.{0,20}(路径|信息|地址|仓库|文件|目录)"""),
        Regex("""需要.{0,10}路径"""),
        Regex("""需要提供.{0,10}(仓库|路径|文件)"""),
        Regex("""缺少.{0,10}(路径|信息|权限|文件|仓库)"""),
        Regex("""做不了|无法继续|不能继续|无法进行|未能继续|无法推进"""),
        // 英文（headline 已统一小写）。
        Regex("""could not find.{0,40}(repo|repository|file|directory|path)"""),
        Regex("""(repo|repository).{0,12}not found|not found.{0,12}(repo|repository)"""),
        Regex("""please provide.{0,20}(path|information|repo|address)"""),
        Regex("""cannot proceed|unable to proceed|can't proceed|couldn't proceed"""),
        Regex("""not created yet|haven't created|has not been created"""),
        Regex("""need.{0,10}(the )?(path|repository)"""),
        Regex("""missing.{0,10}(path|information|permission)"""),
    )

    /** 剥掉行内反引号/双引号包裹的标识符（只留正文措辞供匹配）。 */
    private fun stripQuotedSegments(text: String): String =
        text
            .replace(Regex("""`[^`]*`"""), " ")
            .replace(Regex(""""[^"]*""""), " ")

    /**
     * 分类远端成员的一次汇报全文（本地执行的结果文本同款，见
     * [wherevercode.agent.core.task.LocalTaskRunner.finishTask]：
     * 本地执行器也只按“轮次跑完了”报 success，工具报错不影响它，
     * 所以 success 的结果文本同样要过分类器）。
     *
     * 只判开头陈述：取首个非空行，去掉行首 markdown/引用标记
     * （"> "、"# "、"**" 等），看前 120 字内是否含显式失败表述或
     * 需要信息/被挡住的表述。命中 → [RemoteMemberVerdict.Failed]
     * （reason 取全文）；否则 → [RemoteMemberVerdict.Success]；
     * 空/空白 output → Failed（reason 取 [emptyReason]，本地调用方
     * 传本地文案）。结构化输出（首行是 `{`、`[` 或 ``` 围栏）不判，
     * 由调用方的解析器定。
     */
    fun classify(output: String, emptyReason: String = EMPTY_OUTPUT_REASON): RemoteMemberVerdict {
        if (output.isBlank()) return RemoteMemberVerdict.Failed(emptyReason)
        val headline = output
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.replace(Regex("^(>\\s*|#{1,6}\\s+|\\*\\*)+"), "")
            ?.trim()
            .orEmpty()
            .take(HEADLINE_LIMIT)
            .lowercase()
        if (headline.isBlank()) return RemoteMemberVerdict.Failed(emptyReason)
        // 结构化输出（plan/verdict JSON）：正文启发式不抢判，解析器定。
        if (headline.startsWith("{") || headline.startsWith("[") || headline.startsWith("```")) {
            return RemoteMemberVerdict.Success
        }
        val prose = stripQuotedSegments(headline)
        val failed = FAILURE_MARKERS.any { prose.contains(it) } ||
            NEED_INFO_PATTERNS.any { it.containsMatchIn(prose) }
        return if (failed) {
            RemoteMemberVerdict.Failed(output)
        } else {
            RemoteMemberVerdict.Success
        }
    }
}

/** [RemoteMemberVerdictClassifier.classify] 的顶层别名，供调用方直读。 */
fun classifyRemoteMemberOutput(
    output: String,
    emptyReason: String = RemoteMemberVerdictClassifier.EMPTY_OUTPUT_REASON,
): RemoteMemberVerdict =
    RemoteMemberVerdictClassifier.classify(output, emptyReason)
