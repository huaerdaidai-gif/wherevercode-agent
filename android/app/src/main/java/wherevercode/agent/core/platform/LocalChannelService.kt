package wherevercode.agent.core.platform

import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地平台调用失败（频道三层一期·本地层）：消息即给用户看的真实
 * 原因，[LocalFirstPlatformHttpClient] 把它原样包成 MCP 错误结果，
 * 与远程平台报错同一条呈现路径，绝不假成功。
 */
class LocalServiceException(message: String) : Exception(message)

/** 本地频道账本的持久化口：生产实现写 App 私有文件，测试用内存实现。 */
interface LocalChannelPersistence {
    fun load(): String?
    fun save(text: String)
}

/** 内存持久化（测试与无文件环境兜底；进程退出即丢，只用于这两处）。 */
class InMemoryLocalChannelPersistence : LocalChannelPersistence {
    @Volatile
    var stored: String? = null
    override fun load(): String? = stored
    override fun save(text: String) {
        stored = text
    }
}

/**
 * 本地频道账本（频道三层一期·本地层，方案便签 №11）：把平台服务端的
 * 频道语义搬进 App 本机——频道/成员/角色/消息/已读标记/反应/置顶/
 * 撤回/Webhook 记录全在本机库里，零配置、零网络可用。
 *
 * 语义与平台服务端（v5_platform.py）逐项对齐，包括：发帖自动入群
 * （不刷新 joined_at）、读消息的成员门禁（非成员 403 同文案）、私聊
 * 禁自行加入、历史不可见按 joined_at 截断、@所有人 仅特权角色生效、
 * effect_key 幂等、角色设置的 owner 专属与最后一个 owner 保护。
 * 服务端尚未实现的二/三期操作（撤回/删除/置顶/反应/已读名单/
 * pinned_only）在这里按 App 客户端既有调用口径补齐——本地模式下
 * 这些功能是真可用的，不是摆设。
 *
 * 线程：全部公开方法同一把锁；每次变更后整表快照落盘（本地账本
 * 体量小，快照写足够；格式见 [snapshotJson]）。
 */
class LocalChannelService(
    private val persistence: LocalChannelPersistence,
    taskPersistence: TaskDispatchPersistence = InMemoryTaskDispatchPersistence(),
    private val clock: () -> Double = { System.currentTimeMillis() / 1000.0 },
) {
    private data class Agent(
        val id: String,
        var name: String,
        /** 任务角色（W12）：dev/review/pack，来自 App 注册表
         * capabilities 的同名项；供任务服务的 pack 名册查询。 */
        var roles: List<String> = emptyList(),
    )

    private data class Channel(
        val id: String,
        var name: String,
        var kind: String,
        var archived: Boolean,
        var historyVisible: Boolean,
        val createdAt: Double,
    )

    private data class Member(
        val agentId: String,
        var agentName: String,
        var role: String,
        val joinedAt: Double,
    )

    private data class Message(
        val id: String,
        val channelId: String,
        val agentId: String,
        val agentName: String,
        var text: String,
        val replyTo: String?,
        val createdAt: Double,
        val seq: Long,
        val mentionsAll: Boolean,
        val attachmentRef: String?,
        val forwardOf: String?,
        var pinned: Boolean = false,
        var recalled: Boolean = false,
    )

    private data class Webhook(
        val id: String,
        val channelId: String,
        val name: String,
        val secretHash: String,
        val createdBy: String,
        val createdAt: Double,
    )

    private val agents = LinkedHashMap<String, Agent>()
    private val channels = LinkedHashMap<String, Channel>()
    private val members = HashMap<String, MutableList<Member>>()
    private val messages = HashMap<String, MutableList<Message>>()
    private val reactions = HashMap<String, LinkedHashMap<String, LinkedHashSet<String>>>()
    private val readMarkers = HashMap<String, String>()
    private val webhooks = mutableListOf<Webhook>()
    private val effectKeys = HashMap<String, String>()
    private var seqCounter = 0L
    private var storedDefaultChannelId: String = ""
    private var seedCleanupV1Done: Boolean = false

    private val listeners = HashMap<String, MutableList<(JSONObject) -> Unit>>()

    init {
        restore()
        ensureSeed()
    }

    // ---- 身份与种子 -------------------------------------------------------

    /** 登记一位本机已知 Agent（本机 App 自己 + 注册表里的 Agent）。 */
    @Synchronized
    fun registerAgent(
        agentId: String,
        agentName: String,
        roles: List<String> = emptyList(),
    ) {
        val id = agentId.trim()
        if (id.isBlank()) return
        val name = agentName.trim().ifBlank { id }
        val cleanRoles = roles.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val existing = agents[id]
        if (existing == null) {
            agents[id] = Agent(id, name, cleanRoles)
            persist()
        } else if (existing.name != name || existing.roles != cleanRoles) {
            existing.name = name
            existing.roles = cleanRoles
            persist()
        }
    }

    @Synchronized
    fun isRegisteredAgent(agentId: String): Boolean = agents.containsKey(agentId)

    /** 旧种子频道的 id（种子已取消：仅当该频道仍存在时返回，否则空串）。 */
    @Synchronized
    fun defaultChannelId(): String =
        storedDefaultChannelId.takeIf { it.isNotBlank() && channels.containsKey(it) } ?: ""

    @Synchronized
    fun channelExists(channelId: String): Boolean = channels.containsKey(channelId)

    /** 某 Agent 是否某频道成员（内网层 WS 订阅门禁用，与远程成员门禁同口径）。 */
    @Synchronized
    fun isChannelMember(channelId: String, agentId: String): Boolean =
        memberOf(channelId, agentId) != null

    private fun ensureSeed() {
        registerAgent(LocalPlatform.APP_AGENT_ID, LocalPlatform.APP_AGENT_NAME)
        cleanupSeededChannelIfNeeded()
    }

    /**
     * 一次性清理（Issue #22）：旧版本自动种下的默认频道，若仍叫默认名且零消息，
     * 静默删除；有消息的一律保留。迁移标记防重复执行。
     */
    private fun cleanupSeededChannelIfNeeded() {
        if (seedCleanupV1Done) return
        seedCleanupV1Done = true
        val seededId = storedDefaultChannelId
        val channel = channels[seededId]
        if (seededId.isNotBlank() && channel != null &&
            channel.name == DEFAULT_CHANNEL_NAME &&
            messages[seededId].isNullOrEmpty()
        ) {
            channels.remove(seededId)
            members.remove(seededId)
            messages.remove(seededId)
            webhooks.removeAll { it.channelId == seededId }
            readMarkers.keys.removeAll { it.startsWith(seededId + "|") }
            storedDefaultChannelId = ""
        }
        persist()
    }

    /** 派单落点规则（Issue #22）：显式指定 ＞ 唯一本地频道 ＞ 挂起待落地（返回 null）。 */
    private fun resolveLandingChannelId(requested: String?): String? {
        val req = requested?.trim().orEmpty()
        if (req.isNotEmpty() && channels.containsKey(req)) return req
        val locals = channels.values.filter { it.kind == "channel" && !it.archived }
        return if (locals.size == 1) locals[0].id else null
    }

    /** 新建频道重名自动加序号（Issue #22）：「本地频道」→「本地频道 2」→「本地频道 3」…… */
    private fun uniqueChannelName(name: String): String {
        val taken = channels.values.filter { !it.archived }.map { it.name }.toSet()
        if (name !in taken) return name
        var n = 2
        while ("$name $n" in taken) n++
        return "$name $n"
    }

    // ---- 实时推送（本地总线，喂给频道页的 WS 形状事件） --------------------

    @Synchronized
    fun addMessageListener(channelId: String, listener: (JSONObject) -> Unit) {
        listeners.getOrPut(channelId) { mutableListOf() } += listener
    }

    @Synchronized
    fun removeMessageListener(channelId: String, listener: (JSONObject) -> Unit) {
        listeners[channelId]?.remove(listener)
    }

    private fun fireMessage(channelId: String, messageJson: JSONObject) {
        val targets = listeners[channelId]?.toList().orEmpty()
        if (targets.isEmpty()) return
        val event = JSONObject().apply {
            put("type", "message")
            put("channel_id", channelId)
            put("message", messageJson)
        }
        for (target in targets) {
            try {
                target(event)
            } catch (_: Exception) {
            }
        }
    }

    // ---- 任务派工（contracts/task-dispatch/spec.md） -------------------
    // 工具名命中任务派工时由 dispatch 的一行钩子路由到
    // TaskDispatchService；其错误在钩子处转成本地账本同一口径的
    // LocalServiceException（门面只带文案，status/code 留在任务
    // 服务层供对拍夹具核验）。bridge 在 W8 接线（事件写回频道）。
    private val taskBridge = object : TaskChannelBridge {
        override fun postTaskEvent(channelId: String, taskId: String, kind: String, text: String) {
            postTaskEventMessage(channelId, taskId, text)
        }
    }

    /**
     * spec §5 写回落点：以本机 App 身份把任务事件写成频道消息，
     * 正文末尾带 [task:<id>] 引用行（W11 任务卡按它关联渲染）。
     * 频道不存在/已归档时静默跳过——写回失败不回滚任务操作。
     * 与 postMessage 同语义：发帖自动入群（App 作为成员出现）。
     */
    @Synchronized
    private fun postTaskEventMessage(channelId: String, taskId: String, text: String) {
        val channel = channels[channelId] ?: return
        if (channel.archived) return
        val list = members.getOrPut(channel.id) { mutableListOf() }
        if (list.none { it.agentId == LocalPlatform.APP_AGENT_ID }) {
            list += Member(
                LocalPlatform.APP_AGENT_ID, LocalPlatform.APP_AGENT_NAME,
                "member", clock(),
            )
        }
        val message = Message(
            id = newId(),
            channelId = channel.id,
            agentId = LocalPlatform.APP_AGENT_ID,
            agentName = LocalPlatform.APP_AGENT_NAME,
            text = text + "\n[task:" + taskId + "]",
            replyTo = null,
            createdAt = clock(),
            seq = ++seqCounter,
            mentionsAll = false,
            attachmentRef = null,
            forwardOf = null,
        )
        messages.getOrPut(channel.id) { mutableListOf() } += message
        persist()
        fireMessage(channel.id, messageJson(message, LocalPlatform.APP_AGENT_ID))
    }

    /** 任务角色名册（W12）：读 LCS 的 Agent 登记（roles 来自 App
     * 注册表 capabilities 的 dev/review/pack 同名项）。 */
    private val taskRoster = object : TaskAgentRoster {
        override fun agentRoles(agentId: String): List<String>? =
            agents[agentId]?.roles

        override fun firstAgentWithRole(role: String): Pair<String, String>? =
            agents.entries.firstOrNull { role in it.value.roles }
                ?.let { it.key to it.value.name }
    }

    private val taskDispatch = TaskDispatchService(
        store = TaskDispatchStore(taskPersistence, clock),
        directory = TaskAgentDirectory { id -> agents[id]?.name },
        bridge = taskBridge,
        clock = clock,
        roster = taskRoster,
        channelOwnerChecker = { channelId, agentId ->
            members[channelId]?.firstOrNull { it.agentId == agentId }?.role == "owner"
        },
    )

    // ---- 三期上行（云端派单落本地 / 事件回流） -----------------------------

    /** 非归档、非私聊的本地频道 id 列表（seat_register 上报用）。 */
    @Synchronized
    fun localChannelIds(): List<String> =
        channels.values.filter { !it.archived && it.kind != "dm" }.map { it.id }

    /** 本地任务事件出口（仅云端派单任务；见 [TaskDispatchService.eventSink]）。 */
    fun setTaskEventSink(sink: ((TaskEventRecord, TaskRecord) -> Unit)?) {
        taskDispatch.eventSink = sink
    }

    /**
     * 云端 task_dispatch 落本地：同一 task_id 建单（queued），绑定到默认本地频道
     * （无默认频道则取第一个本地频道）；已存在返回 false（幂等）。
     */
    @Synchronized
    fun ingestCloudTask(cloudTask: JSONObject): Boolean {
        val target = resolveLandingChannelId(cloudTask.optString("local_channel_id", "").ifBlank { null })
        return taskDispatch.ingestCloudTask(
            cloudTask, target, LocalPlatform.APP_AGENT_ID, LocalPlatform.APP_AGENT_NAME,
        )
    }

    /** UI 软删除任务（spec §2.17）：任务页专用入口，不进 MCP 工具表。 */
    @Synchronized
    fun uiDeleteTask(taskId: String): JSONObject =
        taskDispatch.uiDeleteTask(
            JSONObject().put("task_id", taskId),
            LocalPlatform.APP_AGENT_ID,
            LocalPlatform.APP_AGENT_NAME,
        )

    // ---- 工具分发（与平台 MCP 工具同名同参同结果形状） ---------------------

    /**
     * 执行一个平台频道工具，返回工具结果 JSON（即 MCP content 文本的
     * 解析对象）。失败抛 [LocalServiceException]，文案与服务端同口径。
     */
    @Synchronized
    fun dispatch(tool: String, args: JSONObject, callerId: String, callerName: String): JSONObject {
        if (TaskDispatchService.isTaskTool(tool)) {
            try {
                // 作用域：本机 App 身份 = owner，其余调用方 = agent；
                // 内网 owner 令牌的作用域识别在 W12 接注册表后补。
                val scope = if (callerId == LocalPlatform.APP_AGENT_ID) {
                    TaskDispatchService.SCOPE_OWNER
                } else {
                    TaskDispatchService.SCOPE_AGENT
                }
                return taskDispatch.dispatch(tool, args, callerId, callerName, scope)
            } catch (e: TaskDispatchException) {
                throw LocalServiceException(e.message ?: "任务派工调用失败")
            }
        }
        return when (tool) {
            "agent_channel_list" -> JSONObject().put("channels", JSONArray().apply {
                for (channel in channels.values) put(channelJson(channel, callerId))
            })
            "agent_channel_join" -> joinChannel(args, callerId, callerName)
            "agent_channel_open_dm" -> openDm(args, callerId, callerName)
            "agent_channel_post" -> postMessage(args, callerId, callerName)
            "agent_channel_messages" -> JSONObject().put(
                "messages",
                JSONArray().apply { for (m in listMessages(args, callerId)) put(m) },
            )
            "agent_channel_create" -> createChannel(args, callerId, callerName)
            "agent_channel_rename" -> renameChannel(args, callerId)
            "agent_channel_set_archived" -> setArchived(args, callerId)
            "agent_channel_set_history_visible" -> setHistoryVisible(args, callerId)
            "agent_channel_delete" -> deleteChannel(args, callerId)
            "agent_channel_add_member" -> addMember(args, callerId)
            "agent_channel_remove_member" -> removeMember(args, callerId)
            "agent_channel_set_member_role" -> setMemberRole(args, callerId)
            "agent_channel_create_webhook" -> createWebhook(args, callerId)
            "agent_channel_list_webhooks" -> listWebhooks(args, callerId)
            "agent_channel_delete_webhook" -> deleteWebhook(args, callerId)
            "agent_channel_recall" -> recallMessage(args, callerId)
            "agent_channel_delete_message" -> deleteMessage(args, callerId)
            "agent_channel_pin" -> pinMessage(args, callerId)
            "agent_channel_react" -> reactMessage(args, callerId)
            "agent_channel_readers" -> channelReaders(args, callerId)
            else -> throw LocalServiceException("本地平台没有这个工具：$tool")
        }
    }

    // ---- 频道与成员 --------------------------------------------------------

    private fun channelJson(channel: Channel, viewerId: String?): JSONObject {
        val participants = JSONArray()
        for (member in members[channel.id].orEmpty()) {
            participants.put(JSONObject().apply {
                put("agent_id", member.agentId)
                // 规范化：本机 App Agent 的显示名以现行产品名为准（存量
                // 账本里的旧名在读出时归一，不让旧称谓继续出现在界面）。
                put(
                    "agent_name",
                    if (member.agentId == LocalPlatform.APP_AGENT_ID) {
                        LocalPlatform.APP_AGENT_NAME
                    } else {
                        member.agentName
                    },
                )
                put("role", member.role)
                put("is_self", viewerId != null && member.agentId == viewerId)
                // 全量对齐批（8.3）：与平台口径同字段——成员是否注册在册
                // Agent（供切换器行「N Agent」计数读真值，不编造）。
                put("is_agent", agents.containsKey(member.agentId))
            })
        }
        return JSONObject().apply {
            put("id", channel.id)
            put("name", channel.name)
            put("kind", channel.kind)
            put("archived", channel.archived)
            put("history_visible", channel.historyVisible)
            put("participants", participants)
            // 全量对齐批（8.3）：最后消息时间与正文（切换器行尾时间与
            // 预览行读真值；无消息不带这两个字段）。已撤回消息正文
            // 按已撤回处理、不作预览。
            val lastMessage = messages[channel.id].orEmpty().maxByOrNull { it.createdAt }
            if (lastMessage != null) {
                put("last_message_at", lastMessage.createdAt)
                if (!lastMessage.recalled) put("last_message_text", lastMessage.text)
            }
        }
    }

    private fun requireChannel(channelId: String): Channel =
        channels[channelId] ?: throw LocalServiceException("频道不存在")

    private fun memberOf(channelId: String, agentId: String): Member? =
        members[channelId]?.firstOrNull { it.agentId == agentId }

    private fun requireMember(channelId: String, agentId: String): Member =
        memberOf(channelId, agentId)
            ?: throw LocalServiceException("你不是该频道成员")

    private fun joinChannel(args: JSONObject, callerId: String, callerName: String): JSONObject {
        val channel = requireChannel(reqText(args, "channel_id", 120))
        if (channel.kind == "dm") throw LocalServiceException("私聊不能自行加入")
        if (memberOf(channel.id, callerId) == null) {
            members.getOrPut(channel.id) { mutableListOf() } +=
                Member(callerId, callerName, "member", clock())
            persist()
        }
        return channelJson(channel, callerId)
    }

    private fun openDm(args: JSONObject, callerId: String, callerName: String): JSONObject {
        val targetId = reqText(args, "agent_id", 120)
        if (targetId == callerId) throw LocalServiceException("不能与自己私聊")
        val target = agents[targetId]
            ?: throw LocalServiceException("agent $targetId 不存在")
        val existing = channels.values.firstOrNull { channel ->
            channel.kind == "dm" && run {
                val ids = members[channel.id].orEmpty().map { it.agentId }.toSet()
                ids == setOf(callerId, targetId)
            }
        }
        if (existing != null) return channelJson(existing, callerId)
        val channel = Channel(newId(), target.name, "dm", false, true, clock())
        channels[channel.id] = channel
        members[channel.id] = mutableListOf(
            Member(callerId, callerName, "member", clock()),
            Member(targetId, target.name, "member", clock()),
        )
        messages[channel.id] = mutableListOf()
        persist()
        return channelJson(channel, callerId)
    }

    private fun createChannel(args: JSONObject, callerId: String, callerName: String): JSONObject {
        val name = uniqueChannelName(reqText(args, "name", 100))
        val channel = Channel(newId(), name, "channel", false, true, clock())
        channels[channel.id] = channel
        members[channel.id] = mutableListOf(Member(callerId, callerName, "owner", clock()))
        messages[channel.id] = mutableListOf()
        persist()
        return channelJson(channel, callerId)
    }

    private fun renameChannel(args: JSONObject, callerId: String): JSONObject {
        val channel = requireChannel(reqText(args, "channel_id", 120))
        requireMember(channel.id, callerId)
        channel.name = reqText(args, "name", 100)
        persist()
        return channelJson(channel, callerId)
    }

    private fun setArchived(args: JSONObject, callerId: String): JSONObject {
        val channel = requireChannel(reqText(args, "channel_id", 120))
        requireMember(channel.id, callerId)
        channel.archived = args.optBoolean("archived", false)
        persist()
        return channelJson(channel, callerId)
    }

    private fun setHistoryVisible(args: JSONObject, callerId: String): JSONObject {
        val channel = requireChannel(reqText(args, "channel_id", 120))
        requireMember(channel.id, callerId)
        channel.historyVisible = args.optBoolean("visible", true)
        persist()
        return channelJson(channel, callerId)
    }

    private fun deleteChannel(args: JSONObject, callerId: String): JSONObject {
        val channel = requireChannel(reqText(args, "channel_id", 120))
        if (memberOf(channel.id, callerId)?.role != "owner") {
            throw LocalServiceException("只有频道 owner 才能删除频道")
        }
        channels.remove(channel.id)
        members.remove(channel.id)
        val removed = messages.remove(channel.id).orEmpty()
        for (message in removed) reactions.remove(message.id)
        readMarkers.keys.removeAll { it.startsWith(channel.id + "|") }
        webhooks.removeAll { it.channelId == channel.id }
        persist()
        return JSONObject().put("deleted", true).put("channel_id", channel.id)
    }

    private fun addMember(args: JSONObject, callerId: String): JSONObject {
        val channel = requireChannel(reqText(args, "channel_id", 120))
        val targetId = reqText(args, "agent_id", 120)
        val target = agents[targetId]
            ?: throw LocalServiceException("agent $targetId 不存在")
        val list = members.getOrPut(channel.id) { mutableListOf() }
        val existing = list.firstOrNull { it.agentId == targetId }
        if (existing == null) {
            list += Member(targetId, target.name, "member", clock())
        } else {
            existing.agentName = target.name
        }
        persist()
        return channelJson(channel, callerId)
    }

    private fun removeMember(args: JSONObject, callerId: String): JSONObject {
        val channel = requireChannel(reqText(args, "channel_id", 120))
        val targetId = reqText(args, "agent_id", 120)
        members[channel.id]?.removeAll { it.agentId == targetId }
        persist()
        return channelJson(channel, callerId)
    }

    private fun setMemberRole(args: JSONObject, callerId: String): JSONObject {
        val channel = requireChannel(reqText(args, "channel_id", 120))
        val memberId = reqText(args, "member_id", 120)
        val role = normalizeRole(args.optString("role", ""))
        if (memberOf(channel.id, callerId)?.role != "owner") {
            throw LocalServiceException("只有频道 owner 才能设置成员角色")
        }
        val target = memberOf(channel.id, memberId)
            ?: throw LocalServiceException("目标不是该频道成员")
        val previous = target.role
        if (previous == "owner" && memberId != callerId) {
            throw LocalServiceException("owner 的角色不能被他人更改")
        }
        if (previous == "owner" && role != "owner") {
            val owners = members[channel.id].orEmpty().count { it.role == "owner" }
            if (owners <= 1) {
                throw LocalServiceException("频道至少要保留一个 owner，最后一个 owner 不能降级")
            }
        }
        target.role = role
        persist()
        return JSONObject().apply {
            put("channel_id", channel.id)
            put("member_id", memberId)
            put("role", role)
            put("role_label", ROLE_LABELS[role] ?: role)
            put("previous_role", previous)
        }
    }

    private fun isPrivileged(role: String?): Boolean =
        role == "owner" || role == "coordinator"

    private fun normalizeRole(raw: String): String {
        val role = ROLE_ALIASES[raw.trim()] ?: raw.trim()
        if (role !in ROLES) {
            throw LocalServiceException("role 只能是 owner / coordinator(协调者) / member(成员)")
        }
        return role
    }

    // ---- 消息 ---------------------------------------------------------------

    private fun messageJson(message: Message, viewerId: String): JSONObject {
        val obj = JSONObject().apply {
            put("id", message.id)
            put("channel_id", message.channelId)
            put("agent_id", message.agentId)
            // 规范化：同 channelJson——本机 App Agent 显示名读出时归一
            // 为现行产品名，存量消息快照里的旧名不继续上屏。
            put(
                "agent_name",
                if (message.agentId == LocalPlatform.APP_AGENT_ID) {
                    LocalPlatform.APP_AGENT_NAME
                } else {
                    message.agentName
                },
            )
            put("text", if (message.recalled) "" else message.text)
            put("created_at", message.createdAt)
            if (message.replyTo != null) put("reply_to", message.replyTo)
            if (message.mentionsAll) put("mentions_all", true)
            if (!message.attachmentRef.isNullOrBlank()) put("attachment_ref", message.attachmentRef)
            if (!message.forwardOf.isNullOrBlank()) put("forward_of", message.forwardOf)
            if (message.recalled) put("recalled", true)
            if (message.pinned) put("pinned", true)
        }
        val byEmoji = reactions[message.id]
        if (!byEmoji.isNullOrEmpty()) {
            val array = JSONArray()
            for ((emoji, agentIds) in byEmoji) {
                if (agentIds.isEmpty()) continue
                array.put(JSONObject().apply {
                    put("emoji", emoji)
                    put("count", agentIds.size)
                    put("mine", viewerId in agentIds)
                })
            }
            if (array.length() > 0) obj.put("reactions", array)
        }
        return obj
    }

    private fun postMessage(args: JSONObject, callerId: String, callerName: String): JSONObject {
        val channel = requireChannel(reqText(args, "channel_id", 120))
        val text = reqText(args, "text", MAX_POST_CHARS)
        val attachmentRef = args.optString("attachment_ref", "").trim().take(500).ifBlank { null }
        val replyTo = args.optString("reply_to", "").takeIf { it.isNotBlank() }?.also {
            if (it.length > 120) throw LocalServiceException("reply_to 格式无效")
        }
        val forwardOf = args.optString("forward_of", "").trim().ifBlank { null }
        val effectKey = args.optString("effect_key", "").trim()
        if (effectKey.isNotBlank()) {
            if (!EFFECT_KEY_RE.matches(effectKey)) {
                throw LocalServiceException("effect_key 格式无效")
            }
            val priorId = effectKeys["${channel.id}|$callerId|$effectKey"]
            val prior = priorId?.let { id -> messages[channel.id]?.firstOrNull { it.id == id } }
            if (prior != null) return messageJson(prior, callerId)
        }
        // 发帖自动入群（与服务端同语义；入群时间只记第一次，
        // 历史不可见频道的截断线靠它，不能被发帖刷新）。
        val list = members.getOrPut(channel.id) { mutableListOf() }
        val existing = list.firstOrNull { it.agentId == callerId }
        if (existing == null) {
            list += Member(callerId, callerName, "member", clock())
        } else {
            existing.agentName = callerName
        }
        val role = memberOf(channel.id, callerId)?.role
        val message = Message(
            id = newId(),
            channelId = channel.id,
            agentId = callerId,
            agentName = callerName,
            text = text,
            replyTo = replyTo,
            createdAt = clock(),
            seq = ++seqCounter,
            mentionsAll = detectMentionsAll(text) && isPrivileged(role),
            attachmentRef = attachmentRef,
            forwardOf = forwardOf,
        )
        messages.getOrPut(channel.id) { mutableListOf() } += message
        if (effectKey.isNotBlank()) {
            effectKeys["${channel.id}|$callerId|$effectKey"] = message.id
        }
        persist()
        val json = messageJson(message, callerId)
        fireMessage(channel.id, json)
        return json
    }

    private fun listMessages(args: JSONObject, callerId: String): List<JSONObject> {
        val channel = requireChannel(reqText(args, "channel_id", 120))
        val me = memberOf(channel.id, callerId)
            ?: throw LocalServiceException("你不是该频道成员，无法读取频道消息")
        val limit = (if (args.has("limit")) args.optInt("limit", 20) else 20).coerceIn(1, 100)
        val onlyUnread = args.optBoolean("only_unread", false)
        val markRead = args.optBoolean("mark_read", false)
        val pinnedOnly = args.optBoolean("pinned_only", false)
        var pool = messages[channel.id].orEmpty().toList()
        if (!channel.historyVisible) {
            pool = pool.filter { it.createdAt >= me.joinedAt }
        }
        if (pinnedOnly) pool = pool.filter { it.pinned }
        val sender = args.optString("sender", "").trim()
        if (sender.isNotBlank()) {
            pool = pool.filter { it.agentId == sender || it.agentName == sender }
        }
        if (args.has("from_ts")) {
            val from = args.optDouble("from_ts", 0.0)
            pool = pool.filter { it.createdAt >= from }
        }
        if (args.has("to_ts")) {
            val to = args.optDouble("to_ts", 0.0)
            pool = pool.filter { it.createdAt < to }
        }
        val kind = args.optString("kind", "").trim().lowercase()
        if (kind.isNotBlank()) {
            pool = when (kind) {
                "link" -> pool.filter { it.text.contains("http") }
                "image" -> pool.filter { it.attachmentRef != null && isImageRef(it.attachmentRef) }
                "file" -> pool.filter { it.attachmentRef != null && !isImageRef(it.attachmentRef) }
                else -> throw LocalServiceException("kind 只能是 image / file / link")
            }
        }
        val query = args.optString("q", "").trim()
        if (query.isNotBlank()) {
            if (query.length > 200) throw LocalServiceException("q 不能超过 200 个字符")
            pool = pool.filter { it.text.contains(query) }
        }
        var afterCursor: Message? = null
        if (onlyUnread) {
            val markerId = readMarkers["${channel.id}|$callerId"]
            if (markerId != null) {
                afterCursor = messages[channel.id]?.firstOrNull { it.id == markerId }
            }
        } else if (args.optString("after_id", "").isNotBlank()) {
            afterCursor = cursorOf(channel.id, args.optString("after_id", ""))
        }
        var beforeCursor: Message? = null
        if (args.optString("before_id", "").isNotBlank()) {
            beforeCursor = cursorOf(channel.id, args.optString("before_id", ""))
        }
        val after = afterCursor
        if (after != null) {
            pool = pool.filter { isAfter(it, after) }
        }
        val before = beforeCursor
        if (before != null) {
            pool = pool.filter { isBefore(it, before) }
        }
        val ascending = beforeCursor == null && (afterCursor != null || args.has("from_ts"))
        val picked = if (ascending) {
            pool.sortedWith(MESSAGE_ORDER).take(limit)
        } else {
            pool.sortedWith(MESSAGE_ORDER).takeLast(limit)
        }
        if ((markRead || onlyUnread) && picked.isNotEmpty()) {
            readMarkers["${channel.id}|$callerId"] = picked.last().id
            persist()
        }
        return picked.map { messageJson(it, callerId) }
    }

    private fun cursorOf(channelId: String, messageId: String): Message =
        messages[channelId]?.firstOrNull { it.id == messageId }
            ?: throw LocalServiceException("消息不存在")

    private fun isAfter(message: Message, cursor: Message): Boolean =
        message.createdAt > cursor.createdAt ||
            (message.createdAt == cursor.createdAt && message.seq > cursor.seq)

    private fun isBefore(message: Message, cursor: Message): Boolean =
        message.createdAt < cursor.createdAt ||
            (message.createdAt == cursor.createdAt && message.seq < cursor.seq)

    private fun requireMessage(channelId: String, messageId: String): Message =
        messages[channelId]?.firstOrNull { it.id == messageId }
            ?: throw LocalServiceException("消息不存在")

    private fun recallMessage(args: JSONObject, callerId: String): JSONObject {
        val channel = requireChannel(reqText(args, "channel_id", 120))
        val message = requireMessage(channel.id, reqText(args, "message_id", 120))
        if (message.agentId != callerId) {
            throw LocalServiceException("只能撤回自己发的消息")
        }
        message.recalled = true
        message.text = ""
        persist()
        return messageJson(message, callerId)
    }

    private fun deleteMessage(args: JSONObject, callerId: String): JSONObject {
        val channel = requireChannel(reqText(args, "channel_id", 120))
        val message = requireMessage(channel.id, reqText(args, "message_id", 120))
        val role = memberOf(channel.id, callerId)?.role
        if (message.agentId != callerId && !isPrivileged(role)) {
            throw LocalServiceException("只能删除自己发的消息，或由频道管理员删除")
        }
        messages[channel.id]?.removeAll { it.id == message.id }
        reactions.remove(message.id)
        persist()
        return JSONObject().put("deleted", true).put("message_id", message.id)
    }

    private fun pinMessage(args: JSONObject, callerId: String): JSONObject {
        val channel = requireChannel(reqText(args, "channel_id", 120))
        requireMember(channel.id, callerId)
        val message = requireMessage(channel.id, reqText(args, "message_id", 120))
        message.pinned = args.optBoolean("pinned", false)
        persist()
        return messageJson(message, callerId)
    }

    private fun reactMessage(args: JSONObject, callerId: String): JSONObject {
        val channel = requireChannel(reqText(args, "channel_id", 120))
        requireMember(channel.id, callerId)
        val message = requireMessage(channel.id, reqText(args, "message_id", 120))
        val emoji = args.optString("emoji", "").trim()
        if (emoji.isBlank() || emoji.length > 16) {
            throw LocalServiceException("emoji 格式无效")
        }
        val byEmoji = reactions.getOrPut(message.id) { LinkedHashMap() }
        val set = byEmoji.getOrPut(emoji) { LinkedHashSet() }
        if (args.optBoolean("on", true)) set += callerId else set -= callerId
        if (set.isEmpty()) byEmoji.remove(emoji)
        persist()
        return messageJson(message, callerId)
    }

    private fun channelReaders(args: JSONObject, callerId: String): JSONObject {
        val channel = requireChannel(reqText(args, "channel_id", 120))
        requireMember(channel.id, callerId)
        val message = requireMessage(channel.id, reqText(args, "message_id", 120))
        val readers = JSONArray()
        for (member in members[channel.id].orEmpty()) {
            val markerId = readMarkers["${channel.id}|${member.agentId}"]
            val marker = markerId?.let { id -> messages[channel.id]?.firstOrNull { it.id == id } }
            val read = marker != null && !isBefore(marker, message)
            readers.put(JSONObject().apply {
                put("agent_id", member.agentId)
                put("agent_name", member.agentName)
                put("read", read)
            })
        }
        return JSONObject().apply {
            put("channel_id", channel.id)
            put("message_id", message.id)
            put("readers", readers)
        }
    }

    // ---- Webhook 记录（入帖端点随内网层开通；记录与权限语义先与服务端一致） ----

    private fun requirePrivileged(channelId: String, callerId: String) {
        requireChannel(channelId)
        if (!isPrivileged(memberOf(channelId, callerId)?.role)) {
            throw LocalServiceException("只有频道 owner / 协调者才能管理 Webhook")
        }
    }

    private fun createWebhook(args: JSONObject, callerId: String): JSONObject {
        val channel = requireChannel(reqText(args, "channel_id", 120))
        requirePrivileged(channel.id, callerId)
        val name = reqText(args, "name", 100)
        val webhookId = "whk_" + UUID.randomUUID().toString().replace("-", "").take(16)
        val secret = UUID.randomUUID().toString().replace("-", "") +
            UUID.randomUUID().toString().replace("-", "")
        webhooks += Webhook(webhookId, channel.id, name, sha256Hex(secret), callerId, clock())
        persist()
        return JSONObject().apply {
            put("webhook_id", webhookId)
            put("channel_id", channel.id)
            put("name", name)
            put("secret", secret)
            put("url_path", WEBHOOK_PATH_PREFIX + webhookId)
            put("signature", "请求头 X-Webhook-Signature: sha256=HMAC_SHA256(key=sha256_hex(secret), body)")
            put("note", "secret 只在这一次返回，库里只存哈希，丢了只能删掉重建。本地模式的入帖端点随内网层（三层二期）开通。")
        }
    }

    private fun listWebhooks(args: JSONObject, callerId: String): JSONObject {
        val channel = requireChannel(reqText(args, "channel_id", 120))
        requirePrivileged(channel.id, callerId)
        val array = JSONArray()
        for (webhook in webhooks.filter { it.channelId == channel.id }.sortedBy { it.createdAt }) {
            array.put(JSONObject().apply {
                put("webhook_id", webhook.id)
                put("name", webhook.name)
                put("created_by", webhook.createdBy)
                put("created_at", webhook.createdAt)
                put("url_path", WEBHOOK_PATH_PREFIX + webhook.id)
            })
        }
        return JSONObject().put("channel_id", channel.id).put("webhooks", array)
    }

    private fun deleteWebhook(args: JSONObject, callerId: String): JSONObject {
        val channel = requireChannel(reqText(args, "channel_id", 120))
        requirePrivileged(channel.id, callerId)
        val webhookId = reqText(args, "webhook_id", 120)
        webhooks.removeAll { it.id == webhookId && it.channelId == channel.id }
        persist()
        return JSONObject().put("deleted", true).put("webhook_id", webhookId)
    }

    // ---- 工具函数 -------------------------------------------------------------

    /** 与服务端 _text 同口径：去空白、必填、长度上限；返回收拾后的文本。 */
    private fun reqText(args: JSONObject, field: String, maxLen: Int): String {
        val value = args.optString(field, "").trim()
        if (value.isBlank()) throw LocalServiceException("$field 不能为空")
        if (value.length > maxLen) throw LocalServiceException("$field 不能超过 $maxLen 个字符")
        return value
    }

    private fun newId(): String = UUID.randomUUID().toString().replace("-", "")

    private fun isImageRef(ref: String): Boolean {
        val lower = ref.lowercase()
        return IMAGE_EXTS.any { lower.endsWith(it) }
    }

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    // ---- 持久化（整表快照 JSON） ----------------------------------------------

    private fun persist() {
        try {
            persistence.save(snapshotJson().toString())
        } catch (_: Exception) {
            // 落盘失败不拖垮当次操作；内存状态仍在，下次变更会再存。
        }
    }

    private fun snapshotJson(): JSONObject {
        val root = JSONObject()
        root.put("version", 1)
        root.put("default_channel_id", storedDefaultChannelId)
        root.put("seed_cleanup_v1_done", seedCleanupV1Done)
        root.put("seq", seqCounter)
        root.put("agents", JSONArray().apply {
            for (agent in agents.values) {
                put(JSONObject().apply {
                    put("id", agent.id)
                    put("name", agent.name)
                    put("roles", JSONArray(agent.roles))
                })
            }
        })
        root.put("channels", JSONArray().apply {
            for (channel in channels.values) {
                put(JSONObject().apply {
                    put("id", channel.id)
                    put("name", channel.name)
                    put("kind", channel.kind)
                    put("archived", channel.archived)
                    put("history_visible", channel.historyVisible)
                    put("created_at", channel.createdAt)
                })
            }
        })
        root.put("members", JSONArray().apply {
            for ((channelId, list) in members) {
                for (member in list) {
                    put(JSONObject().apply {
                        put("channel_id", channelId)
                        put("agent_id", member.agentId)
                        put("agent_name", member.agentName)
                        put("role", member.role)
                        put("joined_at", member.joinedAt)
                    })
                }
            }
        })
        root.put("messages", JSONArray().apply {
            for ((_, list) in messages) {
                for (message in list) {
                    put(JSONObject().apply {
                        put("id", message.id)
                        put("channel_id", message.channelId)
                        put("agent_id", message.agentId)
                        put("agent_name", message.agentName)
                        put("text", message.text)
                        if (message.replyTo != null) put("reply_to", message.replyTo)
                        put("created_at", message.createdAt)
                        put("seq", message.seq)
                        put("mentions_all", message.mentionsAll)
                        if (message.attachmentRef != null) put("attachment_ref", message.attachmentRef)
                        if (message.forwardOf != null) put("forward_of", message.forwardOf)
                        put("pinned", message.pinned)
                        put("recalled", message.recalled)
                    })
                }
            }
        })
        root.put("reactions", JSONArray().apply {
            for ((messageId, byEmoji) in reactions) {
                for ((emoji, agentIds) in byEmoji) {
                    for (agentId in agentIds) {
                        put(JSONObject().apply {
                            put("message_id", messageId)
                            put("emoji", emoji)
                            put("agent_id", agentId)
                        })
                    }
                }
            }
        })
        root.put("read_markers", JSONArray().apply {
            for ((key, messageId) in readMarkers) {
                val parts = key.split("|", limit = 2)
                if (parts.size != 2) continue
                put(JSONObject().apply {
                    put("channel_id", parts[0])
                    put("agent_id", parts[1])
                    put("message_id", messageId)
                })
            }
        })
        root.put("webhooks", JSONArray().apply {
            for (webhook in webhooks) {
                put(JSONObject().apply {
                    put("id", webhook.id)
                    put("channel_id", webhook.channelId)
                    put("name", webhook.name)
                    put("secret_hash", webhook.secretHash)
                    put("created_by", webhook.createdBy)
                    put("created_at", webhook.createdAt)
                })
            }
        })
        root.put("effect_keys", JSONArray().apply {
            for ((key, messageId) in effectKeys) {
                put(JSONObject().put("key", key).put("message_id", messageId))
            }
        })
        return root
    }

    private fun restore() {
        val raw = try {
            persistence.load()
        } catch (_: Exception) {
            null
        } ?: return
        val root = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return
        }
        storedDefaultChannelId = root.optString("default_channel_id", "")
        seedCleanupV1Done = root.optBoolean("seed_cleanup_v1_done", false)
        seqCounter = root.optLong("seq", 0)
        val agentRows = root.optJSONArray("agents") ?: JSONArray()
        for (i in 0 until agentRows.length()) {
            val row = agentRows.optJSONObject(i) ?: continue
            val id = row.optString("id", "")
            if (id.isBlank()) continue
            val roles = mutableListOf<String>()
            val rawRoles = row.optJSONArray("roles")
            if (rawRoles != null) {
                for (j in 0 until rawRoles.length()) {
                    rawRoles.optString(j, "").takeIf { it.isNotBlank() }?.let { roles += it }
                }
            }
            agents[id] = Agent(id, row.optString("name", id), roles)
        }
        val channelRows = root.optJSONArray("channels") ?: JSONArray()
        for (i in 0 until channelRows.length()) {
            val row = channelRows.optJSONObject(i) ?: continue
            val id = row.optString("id", "")
            if (id.isBlank()) continue
            channels[id] = Channel(
                id = id,
                name = row.optString("name", ""),
                kind = row.optString("kind", "channel").ifBlank { "channel" },
                archived = row.optBoolean("archived", false),
                historyVisible = row.optBoolean("history_visible", true),
                createdAt = row.optDouble("created_at", 0.0),
            )
            members[id] = mutableListOf()
            messages[id] = mutableListOf()
        }
        val memberRows = root.optJSONArray("members") ?: JSONArray()
        for (i in 0 until memberRows.length()) {
            val row = memberRows.optJSONObject(i) ?: continue
            val channelId = row.optString("channel_id", "")
            val agentId = row.optString("agent_id", "")
            if (channelId.isBlank() || agentId.isBlank() || !channels.containsKey(channelId)) continue
            members.getOrPut(channelId) { mutableListOf() } += Member(
                agentId = agentId,
                agentName = row.optString("agent_name", agentId),
                role = row.optString("role", "member").ifBlank { "member" },
                joinedAt = row.optDouble("joined_at", 0.0),
            )
        }
        val messageRows = root.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until messageRows.length()) {
            val row = messageRows.optJSONObject(i) ?: continue
            val channelId = row.optString("channel_id", "")
            val id = row.optString("id", "")
            if (channelId.isBlank() || id.isBlank() || !channels.containsKey(channelId)) continue
            messages.getOrPut(channelId) { mutableListOf() } += Message(
                id = id,
                channelId = channelId,
                agentId = row.optString("agent_id", ""),
                agentName = row.optString("agent_name", ""),
                text = row.optString("text", ""),
                replyTo = row.optString("reply_to", "").ifBlank { null },
                createdAt = row.optDouble("created_at", 0.0),
                seq = row.optLong("seq", 0),
                mentionsAll = row.optBoolean("mentions_all", false),
                attachmentRef = row.optString("attachment_ref", "").ifBlank { null },
                forwardOf = row.optString("forward_of", "").ifBlank { null },
                pinned = row.optBoolean("pinned", false),
                recalled = row.optBoolean("recalled", false),
            )
        }
        val reactionRows = root.optJSONArray("reactions") ?: JSONArray()
        for (i in 0 until reactionRows.length()) {
            val row = reactionRows.optJSONObject(i) ?: continue
            val messageId = row.optString("message_id", "")
            val emoji = row.optString("emoji", "")
            val agentId = row.optString("agent_id", "")
            if (messageId.isBlank() || emoji.isBlank() || agentId.isBlank()) continue
            reactions.getOrPut(messageId) { LinkedHashMap() }
                .getOrPut(emoji) { LinkedHashSet() } += agentId
        }
        val markerRows = root.optJSONArray("read_markers") ?: JSONArray()
        for (i in 0 until markerRows.length()) {
            val row = markerRows.optJSONObject(i) ?: continue
            val channelId = row.optString("channel_id", "")
            val agentId = row.optString("agent_id", "")
            val messageId = row.optString("message_id", "")
            if (channelId.isBlank() || agentId.isBlank() || messageId.isBlank()) continue
            readMarkers["$channelId|$agentId"] = messageId
        }
        val webhookRows = root.optJSONArray("webhooks") ?: JSONArray()
        for (i in 0 until webhookRows.length()) {
            val row = webhookRows.optJSONObject(i) ?: continue
            val id = row.optString("id", "")
            if (id.isBlank()) continue
            webhooks += Webhook(
                id = id,
                channelId = row.optString("channel_id", ""),
                name = row.optString("name", ""),
                secretHash = row.optString("secret_hash", ""),
                createdBy = row.optString("created_by", ""),
                createdAt = row.optDouble("created_at", 0.0),
            )
        }
        val effectRows = root.optJSONArray("effect_keys") ?: JSONArray()
        for (i in 0 until effectRows.length()) {
            val row = effectRows.optJSONObject(i) ?: continue
            val key = row.optString("key", "")
            val messageId = row.optString("message_id", "")
            if (key.isBlank() || messageId.isBlank()) continue
            effectKeys[key] = messageId
        }
    }

    companion object {
        const val DEFAULT_CHANNEL_NAME: String = "本地频道"
        const val MAX_POST_CHARS: Int = 4000
        const val WEBHOOK_PATH_PREFIX: String = "/api/open/v1/channel-webhooks/"

        private val ROLES = setOf("owner", "coordinator", "member")
        private val PRIVILEGED_ROLES = setOf("owner", "coordinator")
        private val ROLE_LABELS = mapOf(
            "owner" to "频道主",
            "coordinator" to "协调者",
            "member" to "成员",
        )
        private val ROLE_ALIASES = mapOf(
            "频道主" to "owner",
            "协调者" to "coordinator",
            "成员" to "member",
        )
        private val IMAGE_EXTS = listOf(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".heic", ".bmp", ".svg",
        )
        private val MESSAGE_ORDER: Comparator<Message> =
            compareBy<Message> { it.createdAt }.thenBy { it.seq }
        private val EFFECT_KEY_RE = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,119}$")
        private val AT_ALL_CODE_RE = Regex(
            "```.*?(?:```|\\z)|`[^`\\n]*`",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val AT_ALL_RE = Regex(
            "(?<![A-Za-z0-9_.+\\-/@])@(?:all(?![A-Za-z0-9_]|[.\\-][A-Za-z0-9])|所有人)",
        )

        /** @所有人 检测（与服务端同规则：先剥代码段，大小写敏感）。 */
        fun detectMentionsAll(text: String): Boolean =
            AT_ALL_RE.containsMatchIn(AT_ALL_CODE_RE.replace(text, " "))
    }
}
