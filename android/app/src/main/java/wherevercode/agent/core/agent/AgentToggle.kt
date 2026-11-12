package wherevercode.agent.core.agent

/**
 * Agent 开关（maintainer 2026-11-12 定：每个 Agent 都要有开/关，支持热插拔）。
 *
 * 语义：
 * - 每个 Agent（设备 / 云端 / 平台，全部已注册的以及以后加的）都可
 *   单独开/关；关掉只是停用，不是删除——记录、配置、密钥引用都留着，
 *   随时可再打开。
 * - 热插拔：开关立即生效，不用重启 App。所有消费点每次都从
 *   [AgentStore] 现读（选择器、泵、频道中转、扇出、审查候选），不
 *   缓存启用态；新加 Agent 默认启用（[AgentRecord.enabled] 默认
 *   true），加完立刻可用。
 * - 关掉的 Agent：不在选 Agent 里出现（[enabledAgents]）、不进频道
 *   （`buildChannelMembers` 与 `ChannelAgentRelay.findMentions` 过滤）、
 *   不接派活（[wherevercode.agent.core.team.RemoteTaskPump] 的
 *   `isAgentEnabled` 门禁，留 queued + needs_attention + 可见原因）；
 *   列表里仍看得到（标已停用），一点开关就能再打开。
 *
 * 纯函数 + 存储口，不读 Android 框架（SharedPreferences 版行为见
 * [SharedPreferencesAgentStoreTest] 的老记录兼容测试）。
 */
fun List<AgentRecord>.enabledAgents(): List<AgentRecord> = filter { it.enabled }

/**
 * 翻一个 Agent 的开关。记录不存在返回 false；已是目标态时直接返回
 * true（不重复写）。保存走 [AgentStore.saveAgent]（重校验 + 打
 * updatedAt 时间戳），写完立即生效。
 */
fun AgentStore.setAgentEnabled(agentId: String, enabled: Boolean): Boolean {
    val record = getAgent(agentId) ?: return false
    if (record.enabled == enabled) return true
    saveAgent(record.copy(enabled = enabled))
    return true
}

/** 停用原因（泵留队、发送守门共用同一文案，用户在任务详情里看得到为什么没派）。 */
fun disabledAgentReason(agent: AgentRecord): String =
    "该 Agent 已停用：${agent.displayName}，在 Agent 页重新启用后可派活。"
