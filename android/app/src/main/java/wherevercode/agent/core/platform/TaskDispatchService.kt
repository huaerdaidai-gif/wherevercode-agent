package wherevercode.agent.core.platform

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 派单侧建单时查 Agent 是否存在、取显示名（start_task 的 agent/reviewer 校验）。 */
fun interface TaskAgentDirectory {
    fun agentName(agentId: String): String?
}

/**
 * Agent 名册口：角色查询（attach 的 pack 角色门、submit 找 pack
 * Agent）。生产由 LCS 的 Agent 注册表提供；未接名册时 pack 链
 * 不可用（submit 走无 pack 路径、attach 如实报 pack_role_required）。
 */
interface TaskAgentRoster {
    /** 角色列表；Agent 不存在返回 null。 */
    fun agentRoles(agentId: String): List<String>?

    /** 注册顺序里第一个带该角色的 Agent（id, name）；没有返回 null。 */
    fun firstAgentWithRole(role: String): Pair<String, String>?
}

/**
 * 任务派工服务（spec §2）：16 个工具的统一入口，由
 * [LocalChannelService.dispatch] 的一行钩子按工具名路由进来，
 * 内网门面经频道服务转发、同一条路径。
 *
 * 作用域（spec §6）：owner 见全部 16 个工具；agent 只见干活侧
 * 9 个，调派单侧工具按「未知工具」拒绝（与工具不存在同口径，
 * 不泄露存在性）。tools/list 的名单由 [toolNames] 按作用域给，
 * 门面（进程内/内网）共用这一份。
 *
 * 语义基线 = v5_platform.py 现行行为（A 组夹具对拍）：校验顺序、
 * version +1 时机、事件文案逐项照基线。失败回滚口径也照基线
 * 事务边界：租约清扫先行落盘（基线单独 commit），其余变更在
 * 工具成功后才落盘，抛错时回到清扫后的快照。
 *
 * 落地进度：W4/W5 = 租约清扫 + start/get/list/decide + 干活侧
 * 读与领活链（list_my/claim/release/progress/heartbeat/
 * report_status）；W6 = 交付链（submit/attach/get_delivery_diff）；
 * W9 = update/withdraw/reopen。16 工具已全量落地。
 */
class TaskDispatchService(
    val store: TaskDispatchStore,
    private val directory: TaskAgentDirectory,
    private val bridge: TaskChannelBridge?,
    private val clock: () -> Double,
    private val leaseSeconds: Double = TaskDispatchSpec.LEASE_SECONDS,
    private val roster: TaskAgentRoster? = null,
) {
    companion object {
        /** 干活侧 9 个（spec §6 的顺序即 tools/list 顺序）。 */
        val WORKER_TOOLS = listOf(
            "list_my_tasks", "claim_task", "release_task", "report_progress",
            "report_status", "submit_result", "attach_delivery_diff",
            "get_delivery_diff", "heartbeat",
        )

        /** 派单侧 7 个（owner 作用域）。 */
        val OWNER_TOOLS = listOf(
            "start_task", "get_task", "list_tasks", "decide_task",
            "update_task", "withdraw_task", "reopen_task",
        )

        val ALL_TOOLS: Set<String> = (WORKER_TOOLS + OWNER_TOOLS).toSet()

        const val SCOPE_OWNER = "owner"
        const val SCOPE_AGENT = "agent"

        private const val WORK_INSTRUCTIONS =
            "完成 goal，不要直接推 main。中途用 report_progress 报进度（同时续租 2 小时），" +
                "也可以用 heartbeat 只续租不报进度；完成后用 submit_result 交付：" +
                "summary 写做了什么、怎么验证的，并给出 branch、commit 或 pr_url 其中至少一个作为证据。"

        // spec §5 写回事件清单（verdict 的 changes_requested 形态
        // 记在 work 任务上、同名 kind 一并写回）。
        private val WRITEBACK_KINDS = setOf(
            "created", "claimed", "submitted", "verdict", "approved",
            "changes_requested", "decided", "withdrawn", "reopened",
            "reassigned", "lease_expired", "rounds_exceeded",
        )

        // 基线 _REF_RE / _SHA_RE / _URL_RE（证据格式门）。
        private val REF_RE = Regex("^[A-Za-z0-9._/-]{1,200}$")
        private val SHA_RE = Regex("^[0-9a-f]{7,64}$")
        private val URL_RE = Regex("^https?://\\S{1,2000}$")

        fun isTaskTool(tool: String): Boolean = tool in ALL_TOOLS

        /** tools/list 的名单：owner = 干活 9 + 派单 7（顺序同 spec §6）。 */
        fun toolNames(callerScope: String): List<String> =
            if (callerScope == SCOPE_OWNER) WORKER_TOOLS + OWNER_TOOLS else WORKER_TOOLS
    }

    /** 失败回滚点：dispatch 入口先置为当前快照，租约清扫落盘后前移。 */
    private var rollbackPoint: String = store.snapshotText()

    @Synchronized
    fun dispatch(
        tool: String,
        args: JSONObject,
        callerId: String,
        callerName: String,
        callerScope: String,
    ): JSONObject {
        if (!isTaskTool(tool)) throw TaskDispatchException("未知工具")
        if (tool in OWNER_TOOLS && callerScope != SCOPE_OWNER) {
            throw TaskDispatchException("未知工具")
        }
        // 基线在分发层先验 task_id 类型（对所有带 task_id 的工具一致）。
        if (args.has("task_id") && !args.isNull("task_id") && args.get("task_id") !is String) {
            throw TaskDispatchException("task_id 格式无效")
        }
        rollbackPoint = store.snapshotText()
        val seqBefore = store.eventSeq
        try {
            val result = when (tool) {
                "list_my_tasks" -> listMyTasks(callerId)
                "claim_task" -> claimTask(args, callerId, callerName)
                "release_task" -> releaseTask(args, callerId, callerName)
                "report_progress" -> reportProgress(args, callerId, callerName)
                "heartbeat" -> heartbeat(args, callerId)
                "report_status" -> reportStatus(args, callerId)
                "submit_result" -> submitResult(args, callerId, callerName)
                "attach_delivery_diff" -> attachDelivery(args, callerId, callerName)
                "get_delivery_diff" -> getDeliveryDiff(args, callerId)
                "start_task" -> startTask(args, callerId)
                "get_task" -> getTask(args)
                "list_tasks" -> listTasks(args)
                "decide_task" -> decideTask(args)
                "update_task" -> updateTask(args, callerName)
                "withdraw_task" -> withdrawTask(args, callerName)
                "reopen_task" -> reopenTask(args, callerName)
                else -> throw TaskDispatchException("任务派工尚未启用")
            }
            store.persist()
            writeBackEvents(seqBefore)
            return result
        } catch (e: TaskDispatchException) {
            store.restoreFromText(rollbackPoint)
            throw e
        }
    }

    /**
     * spec §5 事件写回：本次调用新落账本的事件里，命中写回清单且
     * 任务绑了频道的，反向写成频道消息（子任务经 parent 归属父任务
     * 的频道，引用用父任务 id）。写回失败不回滚任务操作。
     */
    private fun writeBackEvents(afterSeq: Long) {
        val bridge = bridge ?: return
        for (event in store.events) {
            if (event.seq <= afterSeq) continue
            if (event.kind !in WRITEBACK_KINDS) continue
            val task = store.tasks[event.taskId] ?: continue
            val channelId = task.channelId
                ?: task.parentId?.let { store.tasks[it]?.channelId }
                ?: continue
            val rootId = if (task.channelId != null) {
                task.taskId
            } else {
                task.parentId ?: task.taskId
            }
            runCatching {
                bridge.postTaskEvent(channelId, rootId, event.kind, event.text)
            }
        }
    }

    /** UI 软删除（spec §2.17）：不是 MCP 工具，只供任务页调用。W11 落地。 */
    @Synchronized
    fun uiDeleteTask(args: JSONObject, callerId: String): JSONObject {
        throw TaskDispatchException("任务派工尚未启用")
    }

    // ---- 基础件 ----------------------------------------------------------

    private fun now(): Double = clock()

    private fun newId(): String = UUID.randomUUID().toString().replace("-", "")

    private fun label(state: String): String = TaskDispatchSpec.STATE_LABELS[state] ?: state

    /** 基线 _update：version +1、updated_at 刷新。 */
    private fun touch(task: TaskRecord) {
        task.version += 1
        task.updatedAt = now()
    }

    private fun event(taskId: String, who: String?, kind: String, text: String) {
        store.appendEvent(taskId, now(), who, kind, text)
    }

    private fun rawArg(args: JSONObject, key: String): Any? =
        if (args.has(key) && !args.isNull(key)) args.get(key) else null

    private fun stringArg(args: JSONObject, key: String): String? = rawArg(args, key) as? String

    /** 基线 _text：必填须非空字符串，长度按原文计，返回去空白后的值；
     *  可选时原样返回（不去空白），非字符串同样报错。 */
    private fun textArg(
        args: JSONObject,
        key: String,
        field: String,
        maxLen: Int,
        required: Boolean = true,
    ): String? {
        val raw = rawArg(args, key)
        if (raw == null) {
            if (required) throw TaskDispatchException("$field 不能为空")
            return null
        }
        if (raw !is String || (required && raw.isBlank())) {
            throw TaskDispatchException("$field 不能为空")
        }
        if (raw.length > maxLen) {
            throw TaskDispatchException("$field 不能超过 $maxLen 个字符")
        }
        return if (required) raw.trim() else raw
    }

    private fun attemptIdArg(args: JSONObject): String? {
        val raw = rawArg(args, "attempt_id") ?: return null
        if (raw !is String || raw.length > 120) {
            throw TaskDispatchException("attempt_id 格式无效")
        }
        return raw.ifEmpty { null }
    }

    private fun idemKey(task: TaskRecord, action: String, attemptId: String): String =
        "${task.taskId}|${task.epoch}|$action|$attemptId"

    /**
     * 基线 _check_fencing（顺序不可换，spec §4.2）：先验
     * expected_version（类型、再比对），后查 attempt_id 幂等回放。
     * 命中回放时返回首次结果，调用方原样返回、不再执行。
     */
    private fun checkFencing(task: TaskRecord, args: JSONObject, action: String): JSONObject? {
        val versionRaw = rawArg(args, "expected_version")
        if (versionRaw != null) {
            val expected = when (versionRaw) {
                is Int -> versionRaw
                is Long -> versionRaw.toInt()
                else -> throw TaskDispatchException("expected_version 必须是整数")
            }
            if (expected != task.version) {
                throw TaskDispatchException(
                    "版本已变（当前 v${task.version}，你给的是 v$expected）：请重新读取任务后再试",
                    409, "stale_version",
                )
            }
        }
        val attemptId = attemptIdArg(args) ?: return null
        val stored = store.idempotency[idemKey(task, action, attemptId)] ?: return null
        return JSONObject(stored)
    }

    private fun recordAttempt(task: TaskRecord, action: String, attemptId: String?, result: JSONObject) {
        if (attemptId == null) return
        val key = idemKey(task, action, attemptId)
        if (key !in store.idempotency) {
            store.idempotency[key] = result.toString()
        }
    }

    /** 基线 _owned_open：不归我按不存在；状态不对 409；租约过期 409。 */
    private fun ownedOpen(taskId: String?, agentId: String, states: Set<String>): TaskRecord {
        val task = taskId?.let { store.tasks[it] }
        if (task == null || (task.assigneeId != agentId && task.claimedById != agentId)) {
            throw TaskDispatchException("任务不存在", 404, "task_not_found")
        }
        if (task.state !in states) {
            throw TaskDispatchException(
                "任务当前状态是${label(task.state)}，做不了这一步",
                409, "task_state_${task.state}",
            )
        }
        if (task.state == TaskDispatchSpec.CLAIMED && task.leaseUntil != null &&
            task.leaseUntil!! <= now()
        ) {
            throw TaskDispatchException("租约已过期，请重新领取", 409, "stale_attempt")
        }
        return task
    }

    // ---- 租约清扫（W4，基线 expire_leases） -------------------------------

    private fun expireLeases() {
        val now = now()
        for (task in store.tasks.values.toList()) {
            if (task.state == TaskDispatchSpec.CLAIMED && task.leaseUntil != null &&
                task.leaseUntil!! < now
            ) {
                val who = task.claimedByName ?: task.assigneeName
                task.state = TaskDispatchSpec.QUEUED
                task.leaseUntil = null
                task.claimedById = null
                task.claimedByName = null
                touch(task)
                event(task.taskId, "平台", "lease_expired", "$who 超过 2 小时没有进展，任务回到等待接手")
            }
        }
        // 预留过期：基线是直接 UPDATE，不 +version、不写事件。
        for (task in store.tasks.values) {
            if (task.reservedUntil != null && task.reservedUntil!! < now) {
                task.reservedForId = null
                task.reservedUntil = null
            }
        }
        // pack 排队超时跳过：取消子任务，父 work 直接开审查或转裁定。
        for (task in store.tasks.values.toList()) {
            if (task.kind != "pack" || task.state != TaskDispatchSpec.QUEUED ||
                task.createdAt >= now - TaskDispatchSpec.PACK_TIMEOUT_SECONDS
            ) {
                continue
            }
            task.state = TaskDispatchSpec.CANCELLED
            task.leaseUntil = null
            task.claimedById = null
            task.claimedByName = null
            touch(task)
            event(task.taskId, "平台", "pack_skipped", "递代码活排队超过 30 分钟没人领，已跳过递代码")
            val work = task.parentId?.let { store.tasks[it] } ?: continue
            if (work.state != TaskDispatchSpec.REVIEWING) continue
            val hasReview = store.tasks.values.any { it.parentId == work.taskId && it.kind == "review" }
            if (hasReview) continue
            if (work.reviewerId != null) {
                openReview(work, work.summary, work.branch, work.commitSha, work.prUrl)
                event(work.taskId, "平台", "pack_skipped", "没有在线的递代码 Agent，直接交给审查")
            } else {
                work.state = TaskDispatchSpec.NEEDS_DECISION
                work.decisionReason = "no_pack_agent"
                touch(work)
                event(work.taskId, "平台", "pack_skipped", "没有在线的递代码 Agent，转为需要你决定")
            }
        }
        // 基线清扫单独 commit：落盘并前移回滚点，其后失败不回滚清扫。
        store.persist()
        rollbackPoint = store.snapshotText()
    }

    /** 基线 _open_review：给 work 开审查子任务，证据快照在开审时落子任务。 */
    private fun openReview(
        work: TaskRecord,
        summary: String?,
        branch: String?,
        commit: String?,
        prUrl: String?,
    ): TaskRecord {
        val review = TaskRecord(
            taskId = newId(),
            projectId = work.projectId,
            kind = "review",
            parentId = work.taskId,
            assigneeId = work.reviewerId!!,
            assigneeName = work.reviewerName ?: "",
            reviewerId = null,
            reviewerName = null,
            title = "审查：" + work.title.take(110),
            goal = work.goal,
            state = TaskDispatchSpec.QUEUED,
            round = work.round,
            maxRounds = work.maxRounds,
            leaseUntil = null,
            summary = summary,
            branch = branch,
            commitSha = commit,
            prUrl = prUrl,
            verdict = null,
            feedback = null,
            decisionReason = null,
            claimedById = null,
            claimedByName = null,
            reservedForId = null,
            reservedUntil = null,
            createdAt = now(),
            updatedAt = now(),
        )
        store.tasks[review.taskId] = review
        return review
    }

    // ---- 视图（基线 agent_view / owner_view） ------------------------------

    private fun taskEvents(taskId: String, limit: Int): List<TaskEventRecord> =
        store.eventsOf(taskId).takeLast(limit)

    private fun eventJson(e: TaskEventRecord): JSONObject = JSONObject().apply {
        put("seq", e.seq)
        put("at", e.at)
        put("who", e.who ?: JSONObject.NULL)
        put("kind", e.kind)
        put("text", e.text)
    }

    private class DeliveryInfo(
        val totalPages: Int,
        val pagesReceived: Int,
        val truncated: Boolean,
        val changedFiles: List<String>,
    )

    /** 基线 delivery_info：页数与改动文件清单（delivery_files 优先，
     *  否则从已收页的 diff 头解析）。 */
    private fun deliveryInfo(workId: String?): DeliveryInfo {
        val work = workId?.let { store.tasks[it] }
            ?: return DeliveryInfo(0, 0, false, emptyList())
        val pages = store.deliveryPages[work.taskId] ?: emptyMap()
        var files = parseFilesJson(work.deliveryFiles)
        if (files.isEmpty()) {
            val joined = pages.values.sortedBy { it.page }.joinToString("") { it.text }
            files = parseDiffFiles(joined)
        }
        return DeliveryInfo(work.deliveryTotalPages, pages.size, work.deliveryTruncated, files)
    }

    private fun parseFilesJson(text: String?): List<String> {
        if (text.isNullOrEmpty()) return emptyList()
        return try {
            val arr = JSONArray(text)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 基线 _parse_diff_files：从 unified diff 的 +++ 头取改动文件。 */
    private fun parseDiffFiles(text: String): List<String> {
        val files = mutableListOf<String>()
        for (line in text.split("\n")) {
            val name = when {
                line.startsWith("+++ b/") -> line.substring(6).trim()
                line.startsWith("+++ ") -> line.substring(4).trim()
                else -> continue
            }
            if (name.isNotEmpty() && name != "/dev/null" && name !in files) {
                files += name
            }
        }
        return files.take(500)
    }

    private fun putNullable(view: JSONObject, key: String, value: Any?) {
        view.put(key, value ?: JSONObject.NULL)
    }

    private fun agentView(task: TaskRecord): JSONObject {
        val view = JSONObject()
        view.put("task_id", task.taskId)
        view.put("kind", task.kind)
        view.put("project_id", task.projectId)
        view.put("title", task.title)
        view.put("goal", task.goal)
        view.put("state", task.state)
        view.put("round", task.round)
        view.put("max_rounds", task.maxRounds)
        view.put("source", "v5_platform")
        view.put("task_ref", "v5:" + task.taskId)
        view.put("version", task.version)
        view.put("attempt", task.attempt)
        putNullable(view, "attempt_id", task.attemptId)
        view.put("cancel_pending", false)
        view.put("assignee_name", task.assigneeName)
        putNullable(view, "claimed_by_name", task.claimedByName)
        putNullable(view, "lease_until", task.leaseUntil)
        view.put("created_at", task.createdAt)
        view.put("updated_at", task.updatedAt)
        putNullable(view, "summary", task.summary)
        putNullable(view, "branch", task.branch)
        putNullable(view, "commit", task.commitSha)
        putNullable(view, "pr_url", task.prUrl)
        putNullable(view, "feedback", task.feedback)
        putNullable(view, "verdict", task.verdict)
        view.put("messages", JSONArray().apply {
            for (e in taskEvents(task.taskId, 50)) {
                if (e.kind == "input") put(eventJson(e))
            }
        })
        var instructions: String? = null
        if (task.kind == "work" && task.round > 1) {
            view.put("previous_delivery", JSONObject().apply {
                putNullable(this, "summary", task.summary)
                putNullable(this, "branch", task.branch)
                putNullable(this, "commit", task.commitSha)
                putNullable(this, "pr_url", task.prUrl)
            })
            putNullable(view, "review_feedback", task.feedback)
            instructions = "这是返工轮：先逐条处理 review_feedback，再交付。"
        }
        if (task.kind == "pack") {
            val info = deliveryInfo(task.parentId)
            putNullable(view, "work_id", task.parentId)
            view.put("delivery", JSONObject().apply {
                putNullable(this, "summary", task.summary)
                putNullable(this, "branch", task.branch)
                putNullable(this, "commit", task.commitSha)
                putNullable(this, "pr_url", task.prUrl)
                put("pages_received", info.pagesReceived)
                put("total_pages", info.totalPages)
            })
            instructions = "递代码：用 attach_delivery_diff(task_id, page, total_pages, text) " +
                "把这次交付的 diff 按页上传，task_id 就是本任务的 task_id；" +
                "全部页到齐后平台自动开审查，你不用再 submit_result。"
        }
        if (task.kind == "review") {
            if (task.parentId == null) {
                instructions = "独立审查任务：没有递代码，不用 get_delivery_diff。" +
                    "按 goal 里写的分支/提交，直接去仓库看真实改动，按需运行相关测试。" +
                    "用 submit_result 交 verdict：verdict=approve 或 changes_requested，" +
                    "summary 写具体理由和要改的地方。不要写代码、不要推分支。"
            } else {
                val work = store.tasks[task.parentId]
                val info = deliveryInfo(task.parentId)
                view.put("delivery", JSONObject().apply {
                    putNullable(this, "implementer", work?.claimedByName ?: work?.assigneeName)
                    putNullable(this, "summary", task.summary)
                    putNullable(this, "branch", task.branch)
                    putNullable(this, "commit", task.commitSha)
                    putNullable(this, "pr_url", task.prUrl)
                    put("total_pages", info.totalPages)
                    put("pages_received", info.pagesReceived)
                    put("changed_files", JSONArray(info.changedFiles))
                    put("delivery_truncated", info.truncated)
                })
                if (info.truncated) {
                    view.put("delivery_note", "改动过大，只给了前 ${info.pagesReceived} 页")
                }
                instructions = "审查这次交付是否满足 goal：读分支上的真实改动，按需运行相关测试。" +
                    "有递代码时用 get_delivery_diff(task_id, page) 按页读改动，" +
                    "task_id 可填审查任务或原开发任务的 task_id。" +
                    "用 submit_result 交结论：verdict=approve 或 changes_requested，" +
                    "summary 写具体理由和要改的地方（会原样转给实现方）。"
            }
        }
        if (task.kind != "review") {
            view.put("instructions", instructions ?: WORK_INSTRUCTIONS)
        } else if (instructions != null) {
            view.put("instructions", instructions)
        }
        return view
    }

    private fun ownerView(task: TaskRecord, withEvents: Boolean = false): JSONObject {
        val view = JSONObject()
        view.put("task_id", task.taskId)
        view.put("kind", task.kind)
        view.put("project_id", task.projectId)
        view.put("title", task.title)
        view.put("goal", task.goal)
        view.put("state", task.state)
        view.put("state_label", label(task.state))
        view.put("round", task.round)
        view.put("max_rounds", task.maxRounds)
        view.put("assignee_id", task.assigneeId)
        view.put("assignee_name", task.assigneeName)
        putNullable(view, "reviewer_id", task.reviewerId)
        putNullable(view, "reviewer_name", task.reviewerName)
        putNullable(view, "claimed_by_id", task.claimedById)
        putNullable(view, "claimed_by_name", task.claimedByName)
        putNullable(view, "lease_until", task.leaseUntil)
        putNullable(view, "summary", task.summary)
        putNullable(view, "branch", task.branch)
        putNullable(view, "commit", task.commitSha)
        putNullable(view, "pr_url", task.prUrl)
        putNullable(view, "verdict", task.verdict)
        putNullable(view, "feedback", task.feedback)
        putNullable(view, "decision_reason", task.decisionReason)
        view.put("version", task.version)
        view.put("epoch", task.epoch)
        view.put("created_at", task.createdAt)
        view.put("updated_at", task.updatedAt)
        if (task.deletedAt != null) {
            view.put("deleted_at", task.deletedAt!!)
            putNullable(view, "deleted_by", task.deletedBy)
        }
        if (withEvents) {
            view.put("events", JSONArray().apply {
                for (e in taskEvents(task.taskId, 10)) put(eventJson(e))
            })
        }
        return view
    }

    // ---- 干活侧（W5） ------------------------------------------------------

    private fun listMyTasks(callerId: String): JSONObject {
        expireLeases()
        val tasks = store.tasks.values
            .filter {
                (it.state == TaskDispatchSpec.QUEUED || it.state == TaskDispatchSpec.CLAIMED) &&
                    (it.assigneeId == callerId || it.claimedById == callerId) &&
                    it.deletedAt == null
            }
            .sortedBy { it.createdAt }
        return JSONObject().apply {
            put("tasks", JSONArray().apply { for (t in tasks) put(agentView(t)) })
            put("note", "排队的先领；领取后租约 2 小时，超过 2 小时没有动静会回到队伍。")
        }
    }

    private fun claimResult(task: TaskRecord): JSONObject = JSONObject().apply {
        put("task", agentView(task))
        put("lease_seconds", leaseSeconds.toInt())
    }

    private fun claimTask(args: JSONObject, callerId: String, callerName: String): JSONObject {
        expireLeases()
        val task = stringArg(args, "task_id")?.let { store.tasks[it] }
            ?: throw TaskDispatchException("任务不存在", 404, "task_not_found")
        checkFencing(task, args, "claim_task")?.let { return it }
        if (task.state == TaskDispatchSpec.CLAIMED) {
            if (task.claimedById == callerId || task.assigneeId == callerId) {
                task.leaseUntil = now() + leaseSeconds
                touch(task)
                val result = claimResult(task)
                recordAttempt(task, "claim_task", attemptIdArg(args), result)
                return result
            }
            throw TaskDispatchException("任务已被别人领走", 409, "task_state_claimed")
        }
        if (task.state != TaskDispatchSpec.QUEUED) {
            throw TaskDispatchException(
                "任务当前状态是${label(task.state)}，领不了",
                409, "task_state_${task.state}",
            )
        }
        if (task.assigneeId != callerId) {
            throw TaskDispatchException("任务不存在", 404, "task_not_found")
        }
        if (task.reservedForId != null && task.reservedUntil != null &&
            task.reservedUntil!! > now() && task.reservedForId != callerId
        ) {
            throw TaskDispatchException(
                "这轮返工只留给上次的实现方，租约到期后才回到队伍",
                409, "task_reserved",
            )
        }
        task.state = TaskDispatchSpec.CLAIMED
        task.leaseUntil = now() + leaseSeconds
        task.claimedById = callerId
        task.claimedByName = callerName
        touch(task)
        event(
            task.taskId, callerName, "claimed",
            "$callerName 接手了" + if (task.round > 1) "（第 ${task.round} 轮）" else "",
        )
        val result = claimResult(task)
        recordAttempt(task, "claim_task", attemptIdArg(args), result)
        return result
    }

    private fun reportProgress(args: JSONObject, callerId: String, callerName: String): JSONObject {
        val text = textArg(args, "text", "text", TaskDispatchSpec.MAX_PROGRESS_CHARS)!!
        val task = ownedOpen(stringArg(args, "task_id"), callerId, setOf(TaskDispatchSpec.CLAIMED))
        checkFencing(task, args, "report_progress")?.let { return it }
        task.leaseUntil = now() + leaseSeconds
        touch(task)
        event(task.taskId, callerName, "progress", text)
        val result = JSONObject().apply {
            put("ok", true)
            put("lease_seconds", leaseSeconds.toInt())
        }
        recordAttempt(task, "report_progress", attemptIdArg(args), result)
        return result
    }

    private fun heartbeat(args: JSONObject, callerId: String): JSONObject {
        val task = ownedOpen(stringArg(args, "task_id"), callerId, setOf(TaskDispatchSpec.CLAIMED))
        checkFencing(task, args, "heartbeat")?.let { return it }
        task.leaseUntil = now() + leaseSeconds
        touch(task)
        val result = JSONObject().apply {
            put("ok", true)
            put("lease_seconds", leaseSeconds.toInt())
        }
        recordAttempt(task, "heartbeat", attemptIdArg(args), result)
        return result
    }

    private fun releaseTask(args: JSONObject, callerId: String, callerName: String): JSONObject {
        val reason = textArg(
            args, "reason", "reason", TaskDispatchSpec.MAX_PROGRESS_CHARS, required = false,
        )
        val task = ownedOpen(stringArg(args, "task_id"), callerId, setOf(TaskDispatchSpec.CLAIMED))
        checkFencing(task, args, "release_task")?.let { return it }
        when (task.kind) {
            "work" -> {
                task.state = TaskDispatchSpec.NEEDS_DECISION
                task.decisionReason = "released"
                clearClaim(task)
                touch(task)
            }
            "pack" -> {
                task.state = TaskDispatchSpec.QUEUED
                clearClaim(task)
                touch(task)
            }
            else -> {
                task.state = TaskDispatchSpec.CANCELLED
                clearClaim(task)
                touch(task)
                val parent = task.parentId?.let { store.tasks[it] }
                if (parent != null) {
                    parent.state = TaskDispatchSpec.NEEDS_DECISION
                    parent.decisionReason = "review_released"
                    touch(parent)
                }
            }
        }
        // 基线口径：事件记在父任务上（无父记自己）——pack 释放也记父 work。
        event(
            task.parentId ?: task.taskId, callerName, "released",
            "$callerName 放弃了这个任务" + if (reason != null) "：$reason" else "",
        )
        val result = JSONObject().apply { put("ok", true) }
        recordAttempt(task, "release_task", attemptIdArg(args), result)
        return result
    }

    private fun clearClaim(task: TaskRecord) {
        task.leaseUntil = null
        task.claimedById = null
        task.claimedByName = null
    }

    private fun reportStatus(args: JSONObject, callerId: String): JSONObject {
        val textRaw = rawArg(args, "quota_text")
        val resetRaw = rawArg(args, "quota_reset_at")
        val text = if (textRaw is String) textRaw.trim() else null
        val reset = if (resetRaw is String) resetRaw.trim() else null
        if (text != null && text.length > TaskDispatchSpec.MAX_QUOTA_TEXT_CHARS) {
            throw TaskDispatchException("quota_text 不能超过 500 个字符")
        }
        if (reset != null && reset.length > TaskDispatchSpec.MAX_QUOTA_RESET_CHARS) {
            throw TaskDispatchException("quota_reset_at 不能超过 120 个字符")
        }
        if (text.isNullOrEmpty() && reset.isNullOrEmpty()) {
            throw TaskDispatchException("quota_text 和 quota_reset_at 至少填一个")
        }
        val existing = store.statusReports[callerId]
        val record = AgentStatusRecord(
            quotaText = text ?: existing?.quotaText,
            quotaResetAt = reset ?: existing?.quotaResetAt,
            reportedAt = now(),
        )
        store.statusReports[callerId] = record
        return JSONObject().apply {
            put("ok", true)
            putNullable(this, "quota_text", record.quotaText)
            putNullable(this, "quota_reset_at", record.quotaResetAt)
            put("reported_at", record.reportedAt)
        }
    }

    // ---- 交付链（W6，基线 submit_work / submit_verdict /
    //      attach_delivery / get_delivery_page） ----------------------------

    private fun recordAttemptRaw(
        taskId: String?,
        epoch: Int,
        action: String,
        attemptId: String?,
        result: JSONObject,
    ) {
        if (attemptId == null || taskId == null) return
        val key = "$taskId|$epoch|$action|$attemptId"
        if (key !in store.idempotency) {
            store.idempotency[key] = result.toString()
        }
    }

    /** 基线 _opt_ref / _opt_url：给定时须是字符串且格式合规。 */
    private fun optRef(args: JSONObject, key: String, field: String, regex: Regex): String? {
        val raw = rawArg(args, key) ?: return null
        if (raw !is String || !regex.matches(raw)) {
            throw TaskDispatchException("$field 格式无效")
        }
        return raw
    }

    private fun submitResult(args: JSONObject, callerId: String, callerName: String): JSONObject {
        val verdictRaw = rawArg(args, "verdict")
        if (verdictRaw != null) {
            if (verdictRaw != "approve" && verdictRaw != "changes_requested") {
                throw TaskDispatchException("verdict 只能是 approve 或 changes_requested")
            }
            return submitVerdict(args, callerId, callerName, verdictRaw as String)
        }
        return submitWork(args, callerId, callerName)
    }

    private fun submitWork(args: JSONObject, callerId: String, callerName: String): JSONObject {
        val summary = textArg(args, "summary", "summary", TaskDispatchSpec.MAX_SUMMARY_CHARS)!!
        val branch = optRef(args, "branch", "branch", REF_RE)
        val commit = optRef(args, "commit", "commit", SHA_RE)
        val prUrl = optRef(args, "pr_url", "pr_url", URL_RE)
        if (branch == null && commit == null && prUrl == null) {
            throw TaskDispatchException(
                "交付需要证据：请带上 branch、commit 或 pr_url 其中至少一个",
                422, "evidence_required",
            )
        }
        val task = ownedOpen(stringArg(args, "task_id"), callerId, setOf(TaskDispatchSpec.CLAIMED))
        checkFencing(task, args, "submit_result")?.let { return it }
        if (task.kind == "pack") {
            throw TaskDispatchException(
                "递代码任务请用 attach_delivery_diff 递 diff，不用 submit_result",
                409, "use_attach_for_pack_tasks",
            )
        }
        if (task.kind != "work") {
            throw TaskDispatchException(
                "审查任务请用 verdict 交结论",
                409, "use_verdict_for_review_tasks",
            )
        }
        val delivered = "交付" +
            (if (branch != null) "：分支 $branch" else "") +
            (if (commit != null) " 提交 ${commit.take(12)}" else "")
        event(task.taskId, callerName, "submitted", delivered + "\n" + summary)
        // 提交即清本任务旧 delivery_pages（基线 DELETE 同步清计数）。
        store.deliveryPages.remove(task.taskId)
        task.summary = summary
        task.branch = branch
        task.commitSha = commit
        task.prUrl = prUrl
        task.leaseUntil = null
        task.deliveryTotalPages = 0
        task.deliveryPages = 0
        task.deliveryTruncated = false
        task.deliveryFiles = null
        val packAgent = roster?.firstAgentWithRole("pack")
        val note: String
        if (packAgent != null && branch != null) {
            task.state = TaskDispatchSpec.REVIEWING
            touch(task)
            openPack(task, packAgent)
            event(task.taskId, "平台", "pack_opened", "已排递代码活，改动递到后自动开审查")
            note = "已排递代码，改动递到后自动交给审查方。"
        } else if (task.reviewerId != null) {
            task.state = TaskDispatchSpec.REVIEWING
            touch(task)
            openReview(task, summary, branch, commit, prUrl)
            event(task.taskId, "平台", "review_opened", "已交给 ${task.reviewerName} 审查")
            note = "已交给审查方，结论出来后会自动通知；要返工时这个任务会重新出现在 list_my_tasks。"
        } else {
            task.state = TaskDispatchSpec.NEEDS_DECISION
            task.decisionReason = "no_reviewer"
            touch(task)
            note = "已交付，等项目负责人查看。"
        }
        val result = JSONObject().apply {
            put("ok", true)
            put("state", task.state)
            put("note", note)
        }
        recordAttempt(task, "submit_result", attemptIdArg(args), result)
        return result
    }

    /** 基线 pack 子任务开单（submit 带 branch 且有 pack 角色 Agent 时）。 */
    private fun openPack(work: TaskRecord, packAgent: Pair<String, String>): TaskRecord {
        val pack = TaskRecord(
            taskId = newId(),
            projectId = work.projectId,
            kind = "pack",
            parentId = work.taskId,
            assigneeId = packAgent.first,
            assigneeName = packAgent.second,
            reviewerId = null,
            reviewerName = null,
            title = "递代码：" + work.title.take(110),
            goal = work.goal,
            state = TaskDispatchSpec.QUEUED,
            round = work.round,
            maxRounds = work.maxRounds,
            leaseUntil = null,
            summary = work.summary,
            branch = work.branch,
            commitSha = work.commitSha,
            prUrl = work.prUrl,
            verdict = null,
            feedback = null,
            decisionReason = null,
            claimedById = null,
            claimedByName = null,
            reservedForId = null,
            reservedUntil = null,
            createdAt = now(),
            updatedAt = now(),
        )
        store.tasks[pack.taskId] = pack
        return pack
    }

    private fun submitVerdict(
        args: JSONObject,
        callerId: String,
        callerName: String,
        verdict: String,
    ): JSONObject {
        val summary = textArg(args, "summary", "summary", TaskDispatchSpec.MAX_SUMMARY_CHARS)!!
        val task = ownedOpen(stringArg(args, "task_id"), callerId, setOf(TaskDispatchSpec.CLAIMED))
        checkFencing(task, args, "submit_result")?.let { return it }
        if (task.kind != "review") {
            throw TaskDispatchException(
                "开发任务请用 submit_result 交 summary + branch/commit，不要用 verdict",
                409, "use_submit_for_work_tasks",
            )
        }
        task.state = TaskDispatchSpec.DONE
        task.verdict = verdict
        task.feedback = summary
        clearClaim(task)
        touch(task)
        event(task.taskId, callerName, "verdict", "审查结论：$verdict\n$summary")
        val work = task.parentId?.let { store.tasks[it] }
        var note = "结论已记录，团队流程会据此继续。"
        if (work != null) {
            when {
                work.state != TaskDispatchSpec.REVIEWING -> {
                    event(work.taskId, callerName, "verdict_ignored", "任务已不在审查中，结论未生效")
                }
                verdict == "approve" -> {
                    work.state = TaskDispatchSpec.APPROVED
                    work.verdict = verdict
                    work.feedback = summary
                    work.decisionReason = null
                    work.claimedById = null
                    work.claimedByName = null
                    work.reservedForId = null
                    work.reservedUntil = null
                    touch(work)
                    event(work.taskId, callerName, "approved", "审查通过\n$summary")
                }
                work.round < work.maxRounds -> {
                    val reserved = work.claimedById
                    val implementer = work.claimedByName ?: work.assigneeName
                    work.state = TaskDispatchSpec.QUEUED
                    work.verdict = verdict
                    work.feedback = summary
                    work.round += 1
                    work.claimedById = null
                    work.claimedByName = null
                    work.reservedForId = reserved
                    work.reservedUntil = if (reserved != null) now() + leaseSeconds else null
                    touch(work)
                    event(
                        work.taskId, callerName, "changes_requested",
                        "审查要求修改，自动打回给 $implementer（第 ${work.round} 轮）\n$summary",
                    )
                    note = "结论已转给实现方，任务已打回返工。"
                }
                else -> {
                    work.state = TaskDispatchSpec.NEEDS_DECISION
                    work.verdict = verdict
                    work.feedback = summary
                    work.decisionReason = "rounds_exceeded"
                    work.claimedById = null
                    work.claimedByName = null
                    work.reservedForId = null
                    work.reservedUntil = null
                    touch(work)
                    event(work.taskId, callerName, "rounds_exceeded", "返工轮数已用完，转为需要项目负责人决定")
                    note = "返工轮数已用完，转为需要项目负责人决定。"
                }
            }
        }
        val result = JSONObject().apply {
            put("ok", true)
            put("state", TaskDispatchSpec.DONE)
            put("note", note)
        }
        recordAttempt(task, "submit_result", attemptIdArg(args), result)
        return result
    }

    /** 严格整数参数（基线 isinstance int 且非 bool 的口径）。 */
    private fun intStrict(args: JSONObject, key: String): Int? =
        when (val value = rawArg(args, key)) {
            is Int -> value
            is Long -> value.toInt()
            else -> null
        }

    private fun attachDelivery(args: JSONObject, callerId: String, callerName: String): JSONObject {
        val page = intStrict(args, "page")
        val totalPages = intStrict(args, "total_pages")
        if (page == null || totalPages == null ||
            !(1 <= page && page <= totalPages && totalPages <= TaskDispatchSpec.MAX_DELIVERY_PAGES)
        ) {
            throw TaskDispatchException("page/total_pages 无效（1 <= page <= total_pages <= 200）")
        }
        val textRaw = rawArg(args, "text")
        if (textRaw !is String || textRaw.length > TaskDispatchSpec.MAX_PAGE_CHARS) {
            throw TaskDispatchException("一页 diff 不能超过 65536 个字符")
        }
        val truncatedRaw = rawArg(args, "truncated")
        val truncated = truncatedRaw != null && truncatedRaw != false
        val argTaskId = stringArg(args, "task_id")
        var row = argTaskId?.let { store.tasks[it] }
            ?: throw TaskDispatchException("任务不存在", 404, "task_not_found")
        if (row.kind == "work") {
            // 填 work id 时自动定位其最新 pack 子任务（基线口径）。
            row = store.tasks.values
                .filter { it.parentId == row.taskId && it.kind == "pack" }
                .sortedBy { it.createdAt }
                .lastOrNull()
                ?: throw TaskDispatchException("任务不存在", 404, "task_not_found")
        }
        if (row.kind != "pack") {
            throw TaskDispatchException(
                "只有递代码任务能递 diff",
                409, "use_attach_for_pack_tasks",
            )
        }
        val roles = roster?.agentRoles(callerId)
        if (roles == null || "pack" !in roles) {
            throw TaskDispatchException(
                "只有带递代码角色的 Agent 能递 diff",
                409, "pack_role_required",
            )
        }
        if (row.state != TaskDispatchSpec.CLAIMED ||
            (row.assigneeId != callerId && row.claimedById != callerId)
        ) {
            throw TaskDispatchException("任务不存在", 404, "task_not_found")
        }
        checkFencing(row, args, "attach_delivery_diff")?.let { return it }
        val workId = row.parentId!!
        val work = store.tasks[workId]
            ?: throw TaskDispatchException("任务不存在", 404, "task_not_found")
        val pages = store.deliveryPages.getOrPut(workId) { mutableMapOf() }
        for (old in pages.values) {
            if (old.totalPages != totalPages) {
                throw TaskDispatchException("total_pages 与前面几页不一致")
            }
        }
        // 基线按字符数计总量（len(text)），不是字节数。
        val storedChars = pages.values.filter { it.page != page }.sumOf { it.text.length } +
            textRaw.length
        if (storedChars > TaskDispatchSpec.MAX_DELIVERY_BYTES) {
            throw TaskDispatchException("整份 diff 不能超过 2 MB")
        }
        pages[page] = DeliveryPageRecord(page, totalPages, textRaw)
        val received = pages.size
        val files = parseDiffFiles(
            pages.values.sortedBy { it.page }.joinToString("") { it.text },
        )
        work.deliveryTotalPages = totalPages
        work.deliveryPages = received
        if (truncated) work.deliveryTruncated = true
        work.deliveryFiles = JSONArray(files).toString()
        touch(work)
        if (received < totalPages) {
            event(workId, callerName, "pack_progress", "递代码 $received/$totalPages 页")
            val result = JSONObject().apply {
                put("ok", true)
                put("work_id", workId)
                put("pack_task_id", row.taskId)
                put("pages_received", received)
                put("total_pages", totalPages)
                put("complete", false)
            }
            recordAttemptRaw(argTaskId, row.epoch, "attach_delivery_diff", attemptIdArg(args), result)
            return result
        }
        row.state = TaskDispatchSpec.DONE
        clearClaim(row)
        touch(row)
        if (work.reviewerId != null) {
            openReview(work, work.summary, work.branch, work.commitSha, work.prUrl)
            event(
                workId, "平台", "review_opened",
                "递代码完成（$totalPages 页），已交给 ${work.reviewerName} 审查",
            )
        } else {
            work.state = TaskDispatchSpec.NEEDS_DECISION
            work.decisionReason = "no_reviewer"
            touch(work)
            event(workId, "平台", "pack_done", "递代码完成（$totalPages 页），等你查看")
        }
        val result = JSONObject().apply {
            put("ok", true)
            put("work_id", workId)
            put("pack_task_id", row.taskId)
            put("pages_received", received)
            put("total_pages", totalPages)
            put("complete", true)
        }
        recordAttemptRaw(argTaskId, row.epoch, "attach_delivery_diff", attemptIdArg(args), result)
        return result
    }

    private fun getDeliveryDiff(args: JSONObject, callerId: String): JSONObject {
        val page = intStrict(args, "page")
        if (page == null || page < 1) {
            throw TaskDispatchException("page 无效")
        }
        val row = stringArg(args, "task_id")?.let { store.tasks[it] }
            ?: throw TaskDispatchException("任务不存在", 404, "task_not_found")
        if (row.kind == "review" && row.parentId == null) {
            throw TaskDispatchException(
                "独立审查任务没有递代码",
                409, "no_delivery_diff",
            )
        }
        val workId = if (row.kind == "review") row.parentId!! else row.taskId
        val work = store.tasks[workId]
        if (work == null || work.kind != "work") {
            throw TaskDispatchException("任务不存在", 404, "task_not_found")
        }
        val review = store.tasks.values
            .filter { it.parentId == workId && it.kind == "review" }
            .sortedBy { it.createdAt }
            .lastOrNull()
        val allowed = review != null &&
            (review.assigneeId == callerId || review.claimedById == callerId)
        if (!allowed) {
            throw TaskDispatchException("任务不存在", 404, "task_not_found")
        }
        val pages = store.deliveryPages[workId] ?: emptyMap()
        val found = pages[page]
            ?: throw TaskDispatchException("这一页 diff 还没到", 404, "delivery_page_missing")
        return JSONObject().apply {
            put("ok", true)
            put("work_id", workId)
            put("page", page)
            put("total_pages", found.totalPages)
            put("pages_received", pages.size)
            put("truncated", work.deliveryTruncated)
            put("changed_files", JSONArray(parseFilesJson(work.deliveryFiles)))
            put("text", found.text)
        }
    }

    // ---- 派单侧（W5 读 + start/decide；update/withdraw/reopen 在 W9） ------

    private fun startTask(args: JSONObject, callerId: String): JSONObject {
        val agentRaw = rawArg(args, "agent")
        if (agentRaw !is String || agentRaw.isEmpty()) {
            throw TaskDispatchException("agent 不能为空")
        }
        val assigneeName = directory.agentName(agentRaw)
            ?: throw TaskDispatchException("agent $agentRaw 不存在")
        var reviewerId: String? = null
        var reviewerName: String? = null
        val reviewerRaw = rawArg(args, "reviewer")
        if (reviewerRaw != null) {
            reviewerName = (reviewerRaw as? String)?.let { directory.agentName(it) }
                ?: throw TaskDispatchException("reviewer $reviewerRaw 不存在")
            reviewerId = reviewerRaw as String
        }
        val goal = textArg(args, "goal", "goal", TaskDispatchSpec.MAX_GOAL_CHARS)!!
        val projectId = textArg(args, "project_id", "project_id", 120)!!
        val titleRaw = rawArg(args, "title")
        val title = if (titleRaw is String && titleRaw.isNotBlank()) {
            titleRaw.trim().take(TaskDispatchSpec.MAX_TITLE_CHARS)
        } else {
            goal.split("\n").first().take(TaskDispatchSpec.MAX_TITLE_CHARS)
        }
        val channelId = stringArg(args, "channel_id")
        val idemKey = stringArg(args, "idempotency_key")
        // spec §2.10：幂等作用域收紧为 (channel_id, key)，同 key 内容不同 409。
        val scopedKey = if (idemKey != null) "start_task|${channelId ?: ""}|$idemKey" else null
        if (scopedKey != null) {
            val storedText = store.idempotency[scopedKey]
            if (storedText != null) {
                val stored = JSONObject(storedText)
                val storedReviewer =
                    if (stored.isNull("reviewer")) null else stored.optString("reviewer")
                val same = stored.optString("agent") == agentRaw &&
                    stored.optString("goal") == goal &&
                    stored.optString("project_id") == projectId &&
                    storedReviewer == reviewerId &&
                    stored.optString("title") == title
                if (!same) {
                    throw TaskDispatchException(
                        "idempotency_key 已用于不同内容的任务",
                        409, "idempotency_conflict",
                    )
                }
                val existing = store.tasks[stored.optString("task_id")]
                if (existing != null) {
                    return JSONObject().apply {
                        put("task", ownerView(existing))
                        put("state", TaskDispatchSpec.QUEUED)
                    }
                }
            }
        }
        val task = TaskRecord(
            taskId = newId(),
            projectId = projectId,
            kind = "work",
            parentId = null,
            assigneeId = agentRaw,
            assigneeName = assigneeName,
            reviewerId = reviewerId,
            reviewerName = reviewerName,
            title = title,
            goal = goal,
            state = TaskDispatchSpec.QUEUED,
            round = 1,
            maxRounds = TaskDispatchSpec.DEFAULT_MAX_ROUNDS,
            leaseUntil = null,
            summary = null,
            branch = null,
            commitSha = null,
            prUrl = null,
            verdict = null,
            feedback = null,
            decisionReason = null,
            claimedById = null,
            claimedByName = null,
            reservedForId = null,
            reservedUntil = null,
            channelId = channelId,
            createdBy = callerId,
            createdAt = now(),
            updatedAt = now(),
        )
        store.tasks[task.taskId] = task
        event(task.taskId, callerId, "created", "任务已派给 $assigneeName")
        if (scopedKey != null) {
            store.idempotency[scopedKey] = JSONObject().apply {
                put("task_id", task.taskId)
                put("agent", agentRaw)
                put("goal", goal)
                put("project_id", projectId)
                put("reviewer", reviewerId ?: JSONObject.NULL)
                put("title", title)
            }.toString()
        }
        return JSONObject().apply {
            put("task", ownerView(task))
            put("state", TaskDispatchSpec.QUEUED)
        }
    }

    private fun getTask(args: JSONObject): JSONObject {
        val task = stringArg(args, "task_id")?.let { store.tasks[it] }
            ?: throw TaskDispatchException("任务不存在", 404, "task_not_found")
        val includeEvents = (rawArg(args, "include_events") as? Boolean) == true
        return JSONObject().apply { put("task", ownerView(task, includeEvents)) }
    }

    private fun listTasks(args: JSONObject): JSONObject {
        expireLeases()
        val projectId = stringArg(args, "project_id")
        val channelId = stringArg(args, "channel_id")
        val limit = ((rawArg(args, "limit") as? Number)?.toInt() ?: 50).coerceIn(1, 200)
        val offset = ((rawArg(args, "offset") as? Number)?.toInt() ?: 0).coerceAtLeast(0)
        val tasks = store.tasks.values
            .filter { it.kind == "work" && it.deletedAt == null }
            .filter { projectId.isNullOrEmpty() || it.projectId == projectId }
            .filter { channelId == null || it.channelId == channelId }
            .sortedByDescending { it.updatedAt }
            .drop(offset)
            .take(limit)
        return JSONObject().apply {
            put("tasks", JSONArray().apply { for (t in tasks) put(ownerView(t)) })
        }
    }

    private fun decideTask(args: JSONObject): JSONObject {
        val action = stringArg(args, "action")
        if (action != "done" && action != "cancel") {
            throw TaskDispatchException("action 只能是 done（确认完成）或 cancel（取消）")
        }
        val reason = textArg(args, "reason", "reason", TaskDispatchSpec.MAX_PROGRESS_CHARS)!!
        val task = stringArg(args, "task_id")?.let { store.tasks[it] }
            ?: throw TaskDispatchException("任务不存在", 404, "task_not_found")
        if (task.state != TaskDispatchSpec.NEEDS_DECISION) {
            throw TaskDispatchException(
                "任务当前状态是${label(task.state)}，不需要决定",
                409, "task_state_${task.state}",
            )
        }
        task.state = if (action == "done") TaskDispatchSpec.DONE else TaskDispatchSpec.CANCELLED
        task.decisionReason = null
        clearClaim(task)
        touch(task)
        val verb = if (action == "done") "确认完成" else "取消"
        event(task.taskId, "项目负责人", "decided", "已$verb：$reason")
        return JSONObject().apply {
            put("ok", true)
            put("task", ownerView(task))
            put("state", task.state)
        }
    }

    // ---- 派单侧新工具（W9，spec §2.14–2.16） ------------------------------

    private fun ownerTask(args: JSONObject): TaskRecord =
        stringArg(args, "task_id")?.let { store.tasks[it] }
            ?: throw TaskDispatchException("任务不存在", 404, "task_not_found")

    private fun stateGuard(task: TaskRecord, allowed: Set<String>) {
        if (task.state !in allowed) {
            throw TaskDispatchException(
                "任务当前状态是${label(task.state)}，做不了这一步",
                409, "task_state_${task.state}",
            )
        }
    }

    private fun updateTask(args: JSONObject, callerName: String): JSONObject {
        val task = ownerTask(args)
        if (task.kind != "work") {
            throw TaskDispatchException("只有工作任务可以更新")
        }
        val expectedRaw = rawArg(args, "expected_version")
            ?: throw TaskDispatchException("expected_version 必填")
        val expected = when (expectedRaw) {
            is Int -> expectedRaw
            is Long -> expectedRaw.toInt()
            else -> throw TaskDispatchException("expected_version 必须是整数")
        }
        if (expected != task.version) {
            throw TaskDispatchException(
                "版本已变（当前 v${task.version}，你给的是 v$expected）：请重新读取任务后再试",
                409, "stale_version",
            )
        }
        stateGuard(task, setOf(TaskDispatchSpec.QUEUED, TaskDispatchSpec.CLAIMED))
        val channelRaw = rawArg(args, "channel_id")
        if (channelRaw != null && channelRaw != task.channelId) {
            throw TaskDispatchException("channel_id 不可修改")
        }
        // 先全量校验再落地（任一字段不合法整单不动）。
        val newTitle = if (rawArg(args, "title") != null) {
            val rawTitle = rawArg(args, "title")
            if (rawTitle !is String || rawTitle.isBlank()) {
                throw TaskDispatchException("title 不能为空")
            }
            rawTitle.trim().take(100)
        } else {
            null
        }
        val newGoal = if (rawArg(args, "goal") != null) {
            textArg(args, "goal", "goal", TaskDispatchSpec.MAX_GOAL_CHARS)
        } else {
            null
        }
        var reviewerTouched = false
        var newReviewerId: String? = null
        var newReviewerName: String? = null
        if (args.has("reviewer")) {
            reviewerTouched = true
            val reviewerRaw = rawArg(args, "reviewer")
            if (reviewerRaw != null) {
                if (reviewerRaw !is String) throw TaskDispatchException("reviewer 格式无效")
                newReviewerName = directory.agentName(reviewerRaw)
                    ?: throw TaskDispatchException("reviewer $reviewerRaw 不存在")
                newReviewerId = reviewerRaw
            }
        }
        var reassignTo: Pair<String, String>? = null
        val agentRaw = rawArg(args, "agent")
        if (agentRaw != null) {
            if (agentRaw !is String) throw TaskDispatchException("agent 格式无效")
            val name = directory.agentName(agentRaw)
                ?: throw TaskDispatchException("agent $agentRaw 不存在")
            if (agentRaw != task.assigneeId) reassignTo = agentRaw to name
        }
        if (newTitle != null) task.title = newTitle
        if (newGoal != null) task.goal = newGoal
        if (reviewerTouched) {
            task.reviewerId = newReviewerId
            task.reviewerName = newReviewerName
        }
        if (reassignTo != null) {
            task.assigneeId = reassignTo.first
            task.assigneeName = reassignTo.second
            task.state = TaskDispatchSpec.QUEUED
            clearClaim(task)
            task.reservedForId = reassignTo.first
            task.reservedUntil = now() + leaseSeconds
            touch(task)
            event(
                task.taskId, callerName, "reassigned",
                "改派给 ${reassignTo.second}，原执行人已释放",
            )
        } else {
            touch(task)
            event(task.taskId, callerName, "updated", "任务内容已更新")
        }
        return JSONObject().apply {
            put("ok", true)
            put("task", ownerView(task))
        }
    }

    private fun withdrawTask(args: JSONObject, callerName: String): JSONObject {
        val task = ownerTask(args)
        if (task.kind != "work") {
            throw TaskDispatchException("只有工作任务可以撤回")
        }
        if (task.state == TaskDispatchSpec.CANCELLED) {
            // 幂等重放：已取消直接成功，不 +version、不写事件（spec §1）。
            return JSONObject().apply {
                put("ok", true)
                put("state", TaskDispatchSpec.CANCELLED)
                put("task", ownerView(task))
            }
        }
        stateGuard(
            task,
            setOf(
                TaskDispatchSpec.QUEUED, TaskDispatchSpec.CLAIMED,
                TaskDispatchSpec.REVIEWING,
            ),
        )
        val reason = textArg(
            args, "reason", "reason", TaskDispatchSpec.MAX_PROGRESS_CHARS,
            required = false,
        )?.trim()
        val text = "任务已撤回" + (if (!reason.isNullOrBlank()) "：$reason" else "")
        // 打开着的子任务同步取消（各 +1、各记 withdrawn）。
        for (child in store.tasks.values) {
            if (child.parentId != task.taskId) continue
            if (child.state == TaskDispatchSpec.DONE ||
                child.state == TaskDispatchSpec.CANCELLED ||
                child.state == TaskDispatchSpec.APPROVED
            ) {
                continue
            }
            child.state = TaskDispatchSpec.CANCELLED
            clearClaim(child)
            child.reservedForId = null
            child.reservedUntil = null
            touch(child)
            event(child.taskId, callerName, "withdrawn", text)
        }
        task.state = TaskDispatchSpec.CANCELLED
        clearClaim(task)
        task.reservedForId = null
        task.reservedUntil = null
        touch(task)
        event(task.taskId, callerName, "withdrawn", text)
        return JSONObject().apply {
            put("ok", true)
            put("state", TaskDispatchSpec.CANCELLED)
            put("task", ownerView(task))
        }
    }

    private fun reopenTask(args: JSONObject, callerName: String): JSONObject {
        val task = ownerTask(args)
        if (task.kind != "work") {
            throw TaskDispatchException("只有工作任务可以重开")
        }
        stateGuard(
            task,
            setOf(
                TaskDispatchSpec.APPROVED, TaskDispatchSpec.DONE,
                TaskDispatchSpec.CANCELLED,
            ),
        )
        val snapshot = "上一轮：状态 ${label(task.state)}、第 ${task.round} 轮" +
            (task.verdict?.let { "、结论 $it" } ?: "") +
            (task.summary?.let { "、交付摘要：${it.take(100)}" } ?: "")
        task.state = TaskDispatchSpec.QUEUED
        task.round = 1
        task.epoch += 1
        task.verdict = null
        task.feedback = null
        task.summary = null
        task.branch = null
        task.commitSha = null
        task.prUrl = null
        task.decisionReason = null
        clearClaim(task)
        task.reservedForId = null
        task.reservedUntil = null
        task.deliveryTotalPages = 0
        task.deliveryPages = 0
        task.deliveryTruncated = false
        task.deliveryFiles = null
        store.deliveryPages.remove(task.taskId)
        touch(task)
        event(task.taskId, callerName, "reopened", "任务已重开（$snapshot）")
        return JSONObject().apply {
            put("ok", true)
            put("task", ownerView(task))
        }
    }
}
