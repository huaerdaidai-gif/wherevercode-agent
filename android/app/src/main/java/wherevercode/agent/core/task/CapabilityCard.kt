package wherevercode.agent.core.task

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import wherevercode.agent.core.agent.AgentKind
import wherevercode.agent.core.agent.AgentLoginState
import wherevercode.agent.core.agent.AgentRecord
import wherevercode.agent.core.project.ProjectHomeKind
import wherevercode.agent.core.project.ProjectLocation
import wherevercode.agent.core.transport.RemoteDevice
import wherevercode.agent.core.transport.RemoteHello

/**
 * P1 确定性自动派活——能力卡（2026-11-11，maintainer 19:24“全都开始迭代升级”）。
 *
 * 来源（只学思路、不抄代码）：
 * - 调研报告《多 Agent 协作执行》P1：确定性能力卡 + 打分路由 + eval
 *   日志；路由是代码查表，不用 LLM 路由器。
 * - 老项目对照 P1：匹配四层（roster 绑定 → 8 条门禁 → 候选排序 →
 *   失败冷却）+ “记分板只读不参政”——历史成功率只上卡展示，
 *   绝不进路由打分（见 [CapabilityRouter] 与
 *   CapabilityRouterTest.success rate 不影响分数）。
 *
 * 卡片 = 一个 Agent 在当前这一刻能不能接活的全部事实：
 * 设备/CLI/会员或模型、已检出仓库（分支/commit）、可用工具、
 * 在线（最后心跳）、当前负载、按任务类型的历史成功率（展示用）。
 *
 * 两条诚实线：
 * - 在线 = 最后一次 hello 成功的时间（RemoteDevice.lastSeen），
 *   0 = 从未在线；云端/本机模型没有“设备在线”概念，卡上记 0，
 *   路由按“随时可达”算，绝不在界面上写“在线”。
 * - 分支/commit 只有 Hand hello 广播的才是实测值（source="hand"）；
 *   本机 ProjectStore 只知道“某设备上有某项目的位置”，不知道分
 *   支/commit（source="phone"，branch/commit 为 null，不编造）。
 */
enum class CostClass {
    /** 自有设备上的会员 CLI / 本地模型：App 视角无边际 API 计费。 */
    FREE,

    /** 云端 Agent / 模型 API：按量计费，须 allowMetered 显式允许才派。 */
    METERED,
}

data class RepoCheckout(
    val path: String,
    val branch: String? = null,
    val commit: String? = null,
    /** "hand" = Hand hello 实测；"phone" = 本机位置记录（分支/commit 未知）。 */
    val source: String = "phone",
)

data class CapabilityCard(
    val agentId: String,
    val displayName: String,
    val deviceId: String?,
    val deviceDisplayName: String?,
    val kind: AgentKind,
    val cliBinary: String?,
    /** 模型 id 或 CLI 二进制名（展示用；可空）。 */
    val modelOrMembership: String?,
    /** 自报能力（AgentRecord.capabilities 原样；未知为空，不编造）。 */
    val tools: List<String>,
    val loginState: AgentLoginState,
    val dispatchable: Boolean,
    /** 已授权项目 id 列表（空 = 未设授权限制，不拦）。 */
    val authorizedProjectIds: List<String> = emptyList(),
    val costClass: CostClass,
    /** 最后一次 hello 成功的时间（秒）；0 = 从未在线（DEVICE）或不适用（CLOUD/LOCAL_MODEL）。 */
    val lastSeenEpochSeconds: Long,
    val pairingConfirmed: Boolean,
    /** 该 Agent 所在设备上已检出的仓库（路径 + 分支/commit，若已知）。 */
    val repos: List<RepoCheckout>,
    /** 该 Agent 名下正被派活的项目（hostId == deviceId 的 DEVICE 位置）。 */
    val hostedProjectIds: Set<String>,
    /** 名下正在执行（assigned/working）的任务数。 */
    val activeTaskCount: Int,
    /**
     * 按任务类型的历史成功率（0..1，展示用）。
     * 路由打分绝不读它——见 CapabilityRouterTest 的不参政回归测试。
     */
    val successRateByTaskType: Map<String, Double> = emptyMap(),
    val updatedAtEpochSeconds: Long,
)

/**
 * Hand hello 快照存储：手机存卡的持久化一腿。
 *
 * 只存 Hand 广播过的东西（deviceId → agentId → 仓库分支/commit +
 * 见到时间）；负载、成功率、位置、在线时间由 [CapabilityCards.build]
 * 每次从各权威 store 现算，不另存副本、不与 store 打架。
 * 7 天没再见过的快照在读时丢弃（设备删了、Agent 卸了都不留僵尸卡）。
 */
interface CapabilityCardStore {
    fun noteHello(deviceId: String, hello: RemoteHello)
    fun helloRepos(): Map<String, Map<String, List<RepoCheckout>>>
}

class InMemoryCapabilityCardStore : CapabilityCardStore {
    private val snapshot = mutableMapOf<String, MutableMap<String, List<RepoCheckout>>>()
    private val seenAt = mutableMapOf<String, Long>()

    override fun noteHello(deviceId: String, hello: RemoteHello) {
        val perAgent = mutableMapOf<String, List<RepoCheckout>>()
        for (agent in hello.agents) {
            val repos = agent.repos.map {
                RepoCheckout(path = it.path, branch = it.branch, commit = it.commit, source = "hand")
            }
            if (repos.isNotEmpty()) perAgent[agent.agentId] = repos
        }
        if (perAgent.isNotEmpty()) {
            snapshot[deviceId] = perAgent
            seenAt[deviceId] = System.currentTimeMillis() / 1000
        }
    }

    override fun helloRepos(): Map<String, Map<String, List<RepoCheckout>>> {
        val cutoff = System.currentTimeMillis() / 1000 - STALE_SECONDS
        return snapshot.filterKeys { (seenAt[it] ?: 0L) >= cutoff }
    }

    companion object {
        const val STALE_SECONDS: Long = 7 * 24 * 3600
    }
}

class SharedPreferencesCapabilityCardStore(context: Context) : CapabilityCardStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun noteHello(deviceId: String, hello: RemoteHello) {
        val current = readAll().toMutableMap()
        val perAgent = mutableMapOf<String, List<RepoCheckout>>()
        for (agent in hello.agents) {
            val repos = agent.repos.map {
                RepoCheckout(path = it.path, branch = it.branch, commit = it.commit, source = "hand")
            }
            if (repos.isNotEmpty()) perAgent[agent.agentId] = repos
        }
        if (perAgent.isEmpty()) return
        current[deviceId] = HelloSnapshot(
            perAgent = perAgent,
            seenAtEpochSeconds = System.currentTimeMillis() / 1000,
        )
        persist(current)
    }

    override fun helloRepos(): Map<String, Map<String, List<RepoCheckout>>> {
        val cutoff = System.currentTimeMillis() / 1000 - InMemoryCapabilityCardStore.STALE_SECONDS
        return readAll()
            .filterValues { it.seenAtEpochSeconds >= cutoff }
            .mapValues { it.value.perAgent }
    }

    private data class HelloSnapshot(
        val perAgent: Map<String, List<RepoCheckout>>,
        val seenAtEpochSeconds: Long,
    )

    private fun readAll(): Map<String, HelloSnapshot> {
        val json = prefs.getString(KEY_HELLO, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(json)
            val out = mutableMapOf<String, HelloSnapshot>()
            val deviceIds = obj.keys()
            while (deviceIds.hasNext()) {
                val deviceId = deviceIds.next()
                val entry = obj.optJSONObject(deviceId) ?: continue
                val perAgent = mutableMapOf<String, List<RepoCheckout>>()
                val agentsObj = entry.optJSONObject("agents") ?: continue
                val agentIds = agentsObj.keys()
                while (agentIds.hasNext()) {
                    val agentId = agentIds.next()
                    val repos = mutableListOf<RepoCheckout>()
                    val arr = agentsObj.optJSONArray(agentId) ?: continue
                    for (i in 0 until arr.length()) {
                        val repo = arr.optJSONObject(i) ?: continue
                        val path = repo.optString("path", "")
                        if (path.isBlank()) continue
                        repos += RepoCheckout(
                            path = path,
                            branch = repo.optString("branch", "").ifBlank { null },
                            commit = repo.optString("commit", "").ifBlank { null },
                            source = "hand",
                        )
                    }
                    if (repos.isNotEmpty()) perAgent[agentId] = repos
                }
                if (perAgent.isNotEmpty()) {
                    out[deviceId] = HelloSnapshot(
                        perAgent = perAgent,
                        seenAtEpochSeconds = entry.optLong("seenAt", 0),
                    )
                }
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun persist(all: Map<String, HelloSnapshot>) {
        val obj = JSONObject()
        for ((deviceId, snapshot) in all) {
            val entry = JSONObject()
            entry.put("seenAt", snapshot.seenAtEpochSeconds)
            val agentsObj = JSONObject()
            for ((agentId, repos) in snapshot.perAgent) {
                val arr = JSONArray()
                for (repo in repos) {
                    val repoObj = JSONObject()
                    repoObj.put("path", repo.path)
                    repo.branch?.let { repoObj.put("branch", it) }
                    repo.commit?.let { repoObj.put("commit", it) }
                    arr.put(repoObj)
                }
                agentsObj.put(agentId, arr)
            }
            entry.put("agents", agentsObj)
            obj.put(deviceId, entry)
        }
        prefs.edit().putString(KEY_HELLO, obj.toString()).apply()
    }

    companion object {
        const val PREFS_NAME: String = "wherevercode_capability_cards"
        const val KEY_HELLO: String = "hello_repos_json"
    }
}

/**
 * 纯函数组装器：AgentStore + RemoteDeviceStore + ProjectStore +
 * TaskStore + EvalLog + hello 快照 → 每 Agent 一张卡。
 * 不读 SharedPreferences、不碰网络，单测直接喂数据。
 */
object CapabilityCards {
    fun build(
        agents: List<AgentRecord>,
        devices: List<RemoteDevice>,
        pairedDeviceIds: Set<String>,
        /** 全部项目的全部位置（只取 homeKind == DEVICE 的当检出仓库）。 */
        allLocations: List<ProjectLocation>,
        tasks: List<TaskRecord>,
        evalEntries: List<EvalLogEntry>,
        helloRepos: Map<String, Map<String, List<RepoCheckout>>> = emptyMap(),
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
    ): List<CapabilityCard> {
        val deviceById = devices.associateBy { it.deviceId }
        val locationsByDevice = allLocations
            .filter { it.homeKind == ProjectHomeKind.DEVICE }
            .groupBy { it.hostId }
        val activeByAgent = tasks
            .filter { it.state == "assigned" || it.state == "working" }
            .groupBy { it.assignedAgentId }
        val successRates = EvalScorecard.successRateByTaskType(evalEntries)
        return agents.map { agent ->
            val device = agent.deviceId?.let { deviceById[it] }
            val deviceLocations = agent.deviceId?.let { locationsByDevice[it] } ?: emptyList()
            val handRepos = agent.deviceId?.let { helloRepos[it]?.get(agent.agentId) } ?: emptyList()
            val handByPath = handRepos.associateBy { it.path }
            // 位置是“有这个仓库”，hello 快照是“分支/commit 是什么”：
            // 同路径以后者覆盖（实测值），只有快照里有的路径才进卡。
            val repos = deviceLocations.map { loc ->
                handByPath[loc.workspacePath]
                    ?: RepoCheckout(path = loc.workspacePath, source = "phone")
            } + handRepos.filter { hand -> deviceLocations.none { it.workspacePath == hand.path } }
            CapabilityCard(
                agentId = agent.agentId,
                displayName = agent.displayName,
                deviceId = agent.deviceId,
                deviceDisplayName = device?.displayName,
                kind = agent.kind,
                cliBinary = agent.binary,
                modelOrMembership = agent.model ?: agent.binary,
                tools = agent.capabilities,
                loginState = agent.loginState,
                dispatchable = agent.dispatchable,
                authorizedProjectIds = agent.authorizedProjectIds,
                costClass = if (agent.kind == AgentKind.CLOUD) CostClass.METERED else CostClass.FREE,
                lastSeenEpochSeconds = if (agent.kind == AgentKind.DEVICE) {
                    device?.lastSeenEpochSeconds ?: 0L
                } else {
                    0L
                },
                pairingConfirmed = if (agent.kind == AgentKind.DEVICE) {
                    agent.deviceId != null && pairedDeviceIds.contains(agent.deviceId)
                } else {
                    true
                },
                repos = repos,
                hostedProjectIds = deviceLocations.map { it.projectId }.toSet(),
                activeTaskCount = activeByAgent[agent.agentId]?.size ?: 0,
                successRateByTaskType = successRates[agent.agentId] ?: emptyMap(),
                updatedAtEpochSeconds = nowEpochSeconds,
            )
        }
    }
}
