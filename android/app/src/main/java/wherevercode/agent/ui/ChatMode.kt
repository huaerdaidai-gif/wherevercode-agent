package wherevercode.agent.ui

import wherevercode.agent.core.agent.AgentRecord

/**
 * 三个对话模式（maintainer 2026-11-12 06:46 定）。
 *
 * 模式是“跟谁说话”的区分，不是老项目‘商量/动手’那种“讨论还是
 * 动手”的分法——每个模式里仍然是一个统一对话（讨论和动手在一
 * 起），不拆成两个模式。
 *
 * ① 大脑对话（BRAIN）：跟大脑说——规划、派活、问答。就像 Codex、
 *    Claude 的 App：直接跟大脑 AI 聊天，你说话，它回应、能干活
 *    （规划、派活、回答问题），不是一种特殊的模式、不用另外学一
 *    套用法。大脑是对话方，回应和干活都在对话里自然发生。
 * ② 单兵对话（SINGLE_AGENT）：跟一个 Agent 说——一对一、直接干活。
 *    不分同设备/远程设备（maintainer 2026-11-12 06:47）：本机上的 Agent
 *    和远程设备上的 Agent（如 Mac 的 Codex、VPS 的 Example Agent），
 *    都是一对一跟一个 Agent 说话，都是同一个模式；设备位置不改变
 *    模式。单兵模式里只有那个 Agent 说话，大脑只在幕后派活、可关。
 * ③ 团队对话/频道（TEAM）：跟整个团队说——与所有设备、所有 Agent
 *    对话（maintainer 2026-11-12 06:48；全部 8 个已注册 Agent 都在频道
 *    里，见 maintainer 2026-11-11 22:19 的定）。一起讨论，讨论多家署名、
 *    汇报走协调者。频道本身是 P3 的工作（TeamChannelController），
 *    本路只定模式与标识、不重做频道。
 *
 * 纯模型，无 Android 依赖，可 JVM 单测。
 */
enum class ChatMode(
    /** 界面上的模式名（横幅/模式切换里显示）。 */
    val label: String,
    /** 一句话说明（模式切换对话框里显示）。 */
    val description: String,
) {
    BRAIN(
        "大脑对话",
        "跟大脑说：规划、派活、问答。像 Codex/Claude App，直接跟大脑 AI 聊天。",
    ),
    SINGLE_AGENT(
        "单兵对话",
        "跟一个 Agent 说：一对一、直接干活。不分同设备/远程设备。",
    ),
    TEAM(
        "团队对话",
        "跟整个团队说：所有设备、所有 Agent 一起讨论，协调者汇报。",
    ),
    ;

    companion object {
        /**
         * 聊天屏的模式由对话方推导：没选 Agent = 大脑对话；选了一个
         * Agent = 单兵对话。设备位置（同设备/远程设备）不改变模式；
         * Agent 的 brainKind 也不改变模式（脑后 Agent 也是选了一个
         * Agent）。频道屏固定是 [TEAM]，不在这里推导。
         */
        fun forConversation(agent: AgentRecord?): ChatMode =
            if (agent == null) BRAIN else SINGLE_AGENT
    }
}
