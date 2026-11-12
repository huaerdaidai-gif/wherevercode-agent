package wherevercode.agent.core.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import wherevercode.agent.brain.BrainKind

/**
 * Agent 记录持久化（阶段 3，A1 P1）。
 *
 * 照抄 [wherevercode.agent.brain.SharedPreferencesBrainConfigStore]
 * 的 list/save/delete/current 四件套形状（同一 prefs + JSON 数组
 * 做法，不同 key），不另造存储：密钥值一律不进这里，只存 authRef。
 * 解析容错：坏行跳过，不让一条坏记录毁掉整个列表。
 *
 * 纯存储口：校验由调用方在保存前做（[AgentRecord.validate]），
 * 本类保存时也再验一次，非法记录拒绝写入。
 */
interface AgentStore {
    fun listAgents(): List<AgentRecord>
    fun getAgent(agentId: String): AgentRecord?
    fun saveAgent(record: AgentRecord)
    fun deleteAgent(agentId: String)
}

class SharedPreferencesAgentStore(context: Context) : AgentStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun listAgents(): List<AgentRecord> {
        val json = prefs.getString(KEY_AGENTS, null) ?: return emptyList()
        val out = mutableListOf<AgentRecord>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                parseAgent(array.optJSONObject(i))?.let { out += it }
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return out
    }

    /**
     * 2026-11-12 回复归属修复：精确匹配不到时回落大小写不敏感匹配。
     * 注册时 agentId 强制小写（validate），但派活方常传显示名
     * （大脑 dispatch_task 传 "Codex"）——旧代码精确匹配不到就
     * 当 Agent 不存在，任务静默排队永不被认领（2026-11-12 真机：
     * 20106890 指派 "Codex"、注册是 "codex"，泵 20 多分钟不认领，
     * 无事件、无 error、无 needs_attention）。回落只读、不改注册
     * 的小写 id；返回的仍是注册记录本身。
     */
    override fun getAgent(agentId: String): AgentRecord? {
        val agents = listAgents()
        return agents.firstOrNull { it.agentId == agentId }
            ?: agents.firstOrNull { it.agentId.equals(agentId, ignoreCase = true) }
    }

    override fun saveAgent(record: AgentRecord) {
        val errors = record.validate()
        require(errors.isEmpty()) { "Agent 记录非法：${errors.joinToString("；")}" }
        val now = System.currentTimeMillis() / 1000
        val existing = getAgent(record.agentId)
        val stamped = record.copy(
            createdAtEpochSeconds = if (existing == null || record.createdAtEpochSeconds == 0L) {
                if (record.createdAtEpochSeconds != 0L) record.createdAtEpochSeconds else now
            } else {
                existing.createdAtEpochSeconds
            },
            updatedAtEpochSeconds = now,
        )
        val agents = listAgents().filter { it.agentId != record.agentId } + stamped
        persist(agents)
    }

    override fun deleteAgent(agentId: String) {
        persist(listAgents().filter { it.agentId != agentId })
    }

    private fun persist(agents: List<AgentRecord>) {
        val array = JSONArray()
        for (agent in agents) {
            val obj = JSONObject()
            obj.put("agentId", agent.agentId)
            obj.put("displayName", agent.displayName)
            obj.put("kind", agent.kind.name)
            obj.put("access", agent.access.name)
            obj.put("brainKind", agent.brainKind.name)
            obj.put("adapterId", agent.adapterId)
            obj.put("binary", agent.binary)
            obj.put("baseUrl", agent.baseUrl)
            obj.put("model", agent.model)
            obj.put("authRef", agent.authRef)
            val caps = JSONArray()
            agent.capabilities.forEach { caps.put(it) }
            obj.put("capabilities", caps)
            obj.put("quotaRule", agent.quotaRule)
            obj.put("dispatchable", agent.dispatchable)
            obj.put("enabled", agent.enabled)
            obj.put("knownLimits", agent.knownLimits)
            obj.put("addHint", agent.addHint)
            obj.put("loginState", agent.loginState.name)
            obj.put("lastTestedAtEpochSeconds", agent.lastTestedAtEpochSeconds ?: JSONObject.NULL)
            obj.put("lastTestSummary", agent.lastTestSummary)
            obj.put("createdAtEpochSeconds", agent.createdAtEpochSeconds)
            obj.put("updatedAtEpochSeconds", agent.updatedAtEpochSeconds)
            // 成员镜像字段（C3 第二阶段成员框架；只存显示用，权威在成员表）。
            obj.put("memberRole", agent.memberRole?.name)
            obj.put("memberConnectionState", agent.memberConnectionState.name)
            val memberProjects = JSONArray()
            agent.authorizedProjectIds.forEach { memberProjects.put(it) }
            obj.put("authorizedProjectIds", memberProjects)
            obj.put(
                "lastMemberStateUpdatedAtEpochSeconds",
                agent.lastMemberStateUpdatedAtEpochSeconds ?: JSONObject.NULL,
            )
            // 阶段 B：远程设备归属（仅 DEVICE 远程 Agent 有值；缺省为 null，老数据照常读）。
            obj.put("deviceId", agent.deviceId)
            obj.put("deviceFolder", agent.deviceFolder)
            // 平台 Agent 字段（仅 PLATFORM_AGENT 有值；缺省为 null/false，老数据照常读）。
            obj.put("platformAgentId", agent.platformAgentId)
            obj.put("platformHost", agent.platformHost)
            obj.put("platformChannelId", agent.platformChannelId)
            obj.put("webhookUrl", agent.webhookUrl)
            obj.put("webhookSecretRef", agent.webhookSecretRef)
            obj.put("directGithubRead", agent.directGithubRead)
            // 云端 Agent 字段（仅 CLOUD_AGENT 有值；缺省为空/true/null，老数据照常读）。
            val cloudRoles = JSONArray()
            agent.cloudRoles.forEach { cloudRoles.put(it) }
            obj.put("cloudRoles", cloudRoles)
            obj.put("canDispatch", agent.canDispatch)
            obj.put("cloudHomeDeviceId", agent.cloudHomeDeviceId)
            array.put(obj)
        }
        prefs.edit().putString(KEY_AGENTS, array.toString()).apply()
    }

    private fun parseAgent(obj: JSONObject?): AgentRecord? {
        if (obj == null) return null
        return try {
            val caps = mutableListOf<String>()
            val capsArray = obj.optJSONArray("capabilities")
            if (capsArray != null) {
                for (i in 0 until capsArray.length()) caps += capsArray.optString(i, "")
            }
            AgentRecord(
                agentId = obj.optString("agentId", ""),
                displayName = obj.optString("displayName", ""),
                kind = AgentKind.valueOf(obj.optString("kind", "CLOUD")),
                access = AgentAccess.valueOf(obj.optString("access", "API")),
                brainKind = BrainKind.valueOf(obj.optString("brainKind", "MODEL_API")),
                adapterId = obj.optString("adapterId", ""),
                binary = if (obj.isNull("binary")) null else obj.optString("binary", ""),
                baseUrl = if (obj.isNull("baseUrl")) null else obj.optString("baseUrl", ""),
                model = if (obj.isNull("model")) null else obj.optString("model", ""),
                authRef = if (obj.isNull("authRef")) null else obj.optString("authRef", ""),
                capabilities = caps.filter { it.isNotEmpty() },
                quotaRule = if (obj.isNull("quotaRule")) null else obj.optString("quotaRule", ""),
                dispatchable = obj.optBoolean("dispatchable", true),
                // 开关（2026-11-12）：老记录没有该字段时按启用读，绝不因升级被误关。
                enabled = obj.optBoolean("enabled", true),
                knownLimits = if (obj.isNull("knownLimits")) null else obj.optString("knownLimits", ""),
                addHint = if (obj.isNull("addHint")) null else obj.optString("addHint", ""),
                loginState = try {
                    AgentLoginState.valueOf(obj.optString("loginState", "UNKNOWN"))
                } catch (_: Exception) {
                    AgentLoginState.UNKNOWN
                },
                lastTestedAtEpochSeconds = if (obj.isNull("lastTestedAtEpochSeconds")) null
                else obj.optLong("lastTestedAtEpochSeconds", 0).takeIf { it != 0L },
                lastTestSummary = if (obj.isNull("lastTestSummary")) null else obj.optString("lastTestSummary", ""),
                memberRole = if (obj.isNull("memberRole")) null else runCatching {
                    wherevercode.agent.core.team.MemberRole.valueOf(obj.optString("memberRole", ""))
                }.getOrNull(),
                memberConnectionState = try {
                    wherevercode.agent.core.team.MemberConnectionState.valueOf(
                        obj.optString("memberConnectionState", "NOT_CONNECTED"),
                    )
                } catch (_: Exception) {
                    wherevercode.agent.core.team.MemberConnectionState.NOT_CONNECTED
                },
                authorizedProjectIds = run {
                    val ids = mutableListOf<String>()
                    val arr = obj.optJSONArray("authorizedProjectIds")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val id = arr.optString(i, "")
                            if (id.isNotEmpty()) ids += id
                        }
                    }
                    ids
                },
                lastMemberStateUpdatedAtEpochSeconds =
                    if (obj.isNull("lastMemberStateUpdatedAtEpochSeconds")) null
                    else obj.optLong("lastMemberStateUpdatedAtEpochSeconds", 0).takeIf { it != 0L },
                deviceId = if (obj.isNull("deviceId")) null else obj.optString("deviceId", ""),
                deviceFolder = if (obj.isNull("deviceFolder")) null else obj.optString("deviceFolder", ""),
                platformAgentId = if (obj.isNull("platformAgentId")) null else obj.optString("platformAgentId", ""),
                platformHost = if (obj.isNull("platformHost")) null else obj.optString("platformHost", ""),
                platformChannelId = if (obj.isNull("platformChannelId")) null else obj.optString("platformChannelId", ""),
                webhookUrl = if (obj.isNull("webhookUrl")) null else obj.optString("webhookUrl", ""),
                webhookSecretRef = if (obj.isNull("webhookSecretRef")) null else obj.optString("webhookSecretRef", ""),
                directGithubRead = obj.optBoolean("directGithubRead", false),
                cloudRoles = run {
                    val roles = mutableListOf<String>()
                    val arr = obj.optJSONArray("cloudRoles")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val role = arr.optString(i, "")
                            if (role.isNotEmpty()) roles += role
                        }
                    }
                    roles
                },
                canDispatch = obj.optBoolean("canDispatch", true),
                cloudHomeDeviceId = if (obj.isNull("cloudHomeDeviceId")) null else obj.optString("cloudHomeDeviceId", ""),
                createdAtEpochSeconds = obj.optLong("createdAtEpochSeconds", 0),
                updatedAtEpochSeconds = obj.optLong("updatedAtEpochSeconds", 0),
            )
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val PREFS_NAME: String = "wherevercode_agents"
        const val KEY_AGENTS: String = "agents_json"
    }
}

/** 内存实现（单测用；与 SharedPreferences 版同一接口）。 */
class InMemoryAgentStore : AgentStore {
    private val lock = Any()
    private val agents = LinkedHashMap<String, AgentRecord>()

    override fun listAgents(): List<AgentRecord> = synchronized(lock) { agents.values.toList() }

    /**
     * 2026-11-12 回复归属修复：精确匹配不到时回落大小写不敏感匹配
     * （与 SharedPreferences 版同口径；见其注释）。
     */
    override fun getAgent(agentId: String): AgentRecord? = synchronized(lock) {
        agents[agentId] ?: agents.entries.firstOrNull { it.key.equals(agentId, ignoreCase = true) }?.value
    }

    override fun saveAgent(record: AgentRecord) {
        val errors = record.validate()
        require(errors.isEmpty()) { "Agent 记录非法：${errors.joinToString("；")}" }
        val now = System.currentTimeMillis() / 1000
        val existing = synchronized(lock) { agents[record.agentId] }
        val stamped = record.copy(
            createdAtEpochSeconds = existing?.createdAtEpochSeconds
                ?.takeIf { it != 0L }
                ?: record.createdAtEpochSeconds.takeIf { it != 0L } ?: now,
            updatedAtEpochSeconds = now,
        )
        synchronized(lock) { agents[record.agentId] = stamped }
    }

    override fun deleteAgent(agentId: String) {
        synchronized(lock) { agents.remove(agentId) }
    }
}
