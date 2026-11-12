package wherevercode.agent.ui

import wherevercode.agent.brain.BrainEngine

/**
 * 一句话的意图：闲聊还是任务。
 *
 * 大脑/Agent 边界（maintainer 2026-11-12）：同一句自然对话，可能是闲聊/
 * 提问，也可能是要派活的任务指令；判错了代价很大——闲聊当成任务会
 * 乱干活，任务当成闲聊会没干活。所以分类必须做明确、看得见、可纠正。
 *
 * 分类是意图层面的，不是纯关键词：[BrainEngine.looksActionOriented]
 * 只认“含不含动作词”，会把“这个文件是干嘛的？”（问句）也当成任务。
 * 本分类器在动作词信号上再看句式：
 * - 任务（TASK）：含动作词，且不是纯问句；或含请求词（帮我/请）+ 动作词
 *   （“能不能帮我新建一个文件？”是任务，哪怕带问号）。
 * - 闲聊（CHAT）：问候（在吗/你好）、纯问句（问什么/怎么/为什么，且
 *   无动作词）、无动作词的陈述。
 * - 不明（UNCERTAIN）：含动作词但纯问句且无请求词（“这个文件是干嘛
 *   的？”——问的是文件本身，不是让干活）；或含请求词但无动作词
 *   （“帮我看一下”——要帮忙但没说什么活）。
 *
 * 安全默认：拿不准时按闲聊处理（不乱干活），但必须在界面上标明
 * “意图不明”，用户能用显式标任务纠正。显式标任务（forceTask）永远
 * 优先于分类。
 *
 * 纯函数，无 Android 依赖，可 JVM 单测。
 */
enum class TaskIntent {
    TASK,
    CHAT,
    UNCERTAIN,
}

object TaskIntentClassifier {

    private val GREETINGS: List<String> = listOf(
        "在吗", "在么", "在不在", "你好", "您好", "嗨", "哈喽", "hello", "hi",
    )

    private val QUESTION_STARTERS: List<String> = listOf(
        "什么", "怎么", "为什么", "为啥", "是不是", "能不能", "可以", "会不会",
        "哪里", "哪个", "哪些", "谁", "何时", "多久", "是否",
    )

    private val REQUEST_WORDS: List<String> = listOf(
        "帮我", "请帮", "麻烦", "请你", "帮一下",
    )

    fun classify(text: String): TaskIntent {
        val t = text.trim()
        if (t.isEmpty()) return TaskIntent.CHAT
        // 问候：短句问候一定是闲聊（“在吗”再短也是问候，不是任务）。
        if (GREETINGS.any { g -> t == g || t.startsWith(g) && t.length <= g.length + 3 }) {
            return TaskIntent.CHAT
        }
        val hasAction = BrainEngine.looksActionOriented(t)
        val isQuestion = t.endsWith("？") || t.endsWith("?") ||
            QUESTION_STARTERS.any { q -> t.startsWith(q) }
        val hasRequest = REQUEST_WORDS.any { it in t }
        return when {
            // 含动作词 + 请求词：哪怕是问句也是任务（“能不能帮我新建一个文件？”）。
            hasAction && hasRequest -> TaskIntent.TASK
            // 含动作词 + 不是问句：明确任务（“新建一个文件 test.txt”）。
            hasAction && !isQuestion -> TaskIntent.TASK
            // 含动作词 + 纯问句 + 无请求词：问的是东西本身，不是让干活。
            hasAction && isQuestion -> TaskIntent.UNCERTAIN
            // 有请求词但无动作词：要帮忙，但没说什么活。
            hasRequest -> TaskIntent.UNCERTAIN
            // 纯问句 / 无动作词的陈述：闲聊。安全默认：不乱干活。
            else -> TaskIntent.CHAT
        }
    }
}
