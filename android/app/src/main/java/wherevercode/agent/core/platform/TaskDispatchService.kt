package wherevercode.agent.core.platform

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 派单侧建单时查 Agent 是否存在、取显示名（start_task 的 agent/reviewer 校验）。 */
fun interface TaskAgentDirectory {
    fun agentName(agentId: String): String?
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
 * report_status）；submit/attach/get_delivery_diff 在 W6；
 * update/withdraw/reopen 在 W9。未落地的如实抛「尚未启用」。
 */
class TaskDispatchService(
    val store: TaskDispatchStore,
    private val directory: TaskAgentDirectory,
    private val bridge: TaskChannelBridge?,
    private val clock: () -> Double,
    private val leaseSeconds: Double = TaskDispatchSpec.LEASE_SECONDS,
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
        try {
            val result = when (tool) {
                "list_my_tasks" -> listMyTasks(callerId)
                "claim_task" -> claimTask(args, callerId, callerName)
                "release_task" -> releaseTask(args, callerId, callerName)
                "report_progress" -> reportProgress(args, callerId, callerName)
                "heartbeat" -> heartbeat(args, callerId)
                "report_status" -> reportStatus(args, callerId)
                "start_task" -> startTask(args, callerId)
                "get_task" -> getTask(args)
                "list_tasks" -> listTasks(args)
                "decide_task" -> decideTask(args)
                else -> throw TaskDispatchException("任务派工尚未启用")
            }
            store.persist()
            return result
        } catch (e: TaskDispatchException) {
            store.restoreFromText(rollbackPoint)
            throw e
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
}
