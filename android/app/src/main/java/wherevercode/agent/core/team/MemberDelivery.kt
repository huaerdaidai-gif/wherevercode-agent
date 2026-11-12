package wherevercode.agent.core.team

import org.json.JSONArray
import wherevercode.agent.core.task.PipelineRunnerHook
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskStore

/**
 * 成员回传（V5 Phase B C3 第二阶段，成员框架）。
 *
 * 回传路径按老项目 `delivery_record.py` 的交付记录 8 键形
 * （build_delivery_record）：
 * `{branch, commit, pr_url, total_pages, pages_received, truncated,
 * files, summary}`；分页上限 2MB/64K 页/200 页的规则在以后接真实
 * 分页上传时执行，本框架只校验形状（files ≤ 200 条、页数 ≤ 200、
 * pages_received ≤ total_pages）。
 *
 * 三样东西分开记，绝不混为一谈：
 * - 交付记录（8 键）：改动本身（分支/commit/PR/页/文件清单/摘要）；
 * - 日志 / diff 摘要：执行过程的记录，与 artifact 分开存；
 * - 测试 verdict（PASSED / FAILED / NOT_RUN）：不在 artifact 里，
 *   在审查 verdict 里（老项目 `task_review.py` 的两值 verdict）。
 *   “artifact 已上传”绝不等于“测试已通过”。
 *
 * 诚实拒绝：成员未连接时回传入口一律拒绝；attempt 不匹配（旧
 * attempt 的迟到回传）一律拒绝；任务已终态（尤其已取消）时一律
 * 拒绝——取消后 artifact 不再附加（老项目 record_artifact 只接受
 * running 态任务），但取消前已落盘的回传记录保留、可读。
 *
 * 四项回传的落点（maintainer 07:31 自我开发验证闭环）：
 * - 状态：[MemberReturnReceiver.finishTask] 推任务终态
 *   （completed/failed，走 TaskStates 的合法路径）+ 任务行本身；
 * - 日志：[MemberTaskReturn.logs]；
 * - diff：[MemberTaskReturn.diffSummary] + 交付记录的 files /
 *   branch / commit（摘要级；完整 patch 体以后进 artifact store，
 *   不在本表，本批后续项）；
 * - 测试结果：[MemberTaskReturn.testVerdict]/[MemberTaskReturn.testSummary]。
 *
 * 本批只做框架：回传记录只落本地 Room，不发 Hand、不调真实 CLI。
 */
data class MemberDeliveryRecord(
    val branch: String? = null,
    val commit: String? = null,
    val prUrl: String? = null,
    val totalPages: Int? = null,
    val pagesReceived: Int? = null,
    val truncated: Boolean = false,
    val files: List<String> = emptyList(),
    val summary: String? = null,
) {
    /** 交付记录形状校验（老项目 delivery_record.py 的分页/文件上限）。 */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (files.size > 200) errors += "交付文件不能超过 200 条"
        if (totalPages != null && (totalPages < 0 || totalPages > 200)) {
            errors += "交付总页数须在 0–200 之间"
        }
        if (pagesReceived != null && totalPages != null && pagesReceived > totalPages) {
            errors += "已收页数不能超过总页数"
        }
        if (pagesReceived != null && pagesReceived < 0) errors += "已收页数不能为负"
        return errors
    }
}

/** 测试 verdict：与 artifact 分开记，不在交付记录里。 */
enum class MemberTestVerdict {
    PASSED,
    FAILED,
    NOT_RUN,
}

/** 一次成员回传：交付记录 + 日志/diff/测试结果分开记。 */
data class MemberTaskReturn(
    val taskId: String,
    val memberId: String,
    val attemptId: String,
    val delivery: MemberDeliveryRecord? = null,
    val logs: List<String> = emptyList(),
    val diffSummary: String? = null,
    val testVerdict: MemberTestVerdict = MemberTestVerdict.NOT_RUN,
    val testSummary: String? = null,
)

enum class MemberReturnRefusal {
    MEMBER_NOT_FOUND,
    MEMBER_NOT_CONNECTED,
    TASK_NOT_FOUND,
    MEMBER_MISMATCH,
    ATTEMPT_MISMATCH,
    TASK_NOT_RUNNING,
    TASK_TERMINAL,
}

sealed class MemberReturnResult {
    data class Recorded(val record: MemberReturnEntity) : MemberReturnResult()
    /**
     * P0 幂等：同一 attempt 的重复投递不重执行、不报错，直接返回
     * 已存记录（第一次写赢）。调用方拿到的是已存结果，不是错误。
     */
    data class Duplicate(val record: MemberReturnEntity) : MemberReturnResult()
    data class Refused(val reason: MemberReturnRefusal, val detail: String) : MemberReturnResult()
}

/** 成员任务的终态（状态回传）：只有 completed / failed 两种。 */
enum class MemberFinishState {
    COMPLETED,
    FAILED,
}

sealed class MemberFinishResult {
    data class Finished(val task: TaskRecord) : MemberFinishResult()
    data class Refused(val reason: MemberReturnRefusal, val detail: String) : MemberFinishResult()
}

class MemberReturnReceiver(
    private val taskStore: TaskStore,
    private val memberStore: TeamMemberStore,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    /**
     * P2 流水线钩子（2026-11-11）：默认 null（老调用方与测试不动，
     * 行为与 P0 完全一致）；成员回传的完成态经它停在 review、不自动
     * completed（[finishTask] 查它）。生产接线待成员回传有真实调用方
     * 后再注入（今天 MemberReturnReceiver 在主代码里尚无生产构造处，
     * 只有测试在用——见 DELIVERY 边界）。
     */
    private val pipelineHook: PipelineRunnerHook? = null,
) {
    fun submitReturn(ret: MemberTaskReturn): MemberReturnResult {
        val member = memberStore.getMember(ret.memberId)
            ?: return MemberReturnResult.Refused(
                MemberReturnRefusal.MEMBER_NOT_FOUND,
                "成员不存在：${ret.memberId}",
            )
        if (!member.canDispatch()) {
            return MemberReturnResult.Refused(
                MemberReturnRefusal.MEMBER_NOT_CONNECTED,
                "成员 ${member.displayName} 未连接（等待安排）：回传已拒绝，未伪装成功。",
            )
        }
        val task = taskStore.getTask(ret.taskId)
            ?: return MemberReturnResult.Refused(
                MemberReturnRefusal.TASK_NOT_FOUND,
                "任务不存在：${ret.taskId}",
            )
        if (task.memberId != ret.memberId) {
            return MemberReturnResult.Refused(
                MemberReturnRefusal.MEMBER_MISMATCH,
                "任务 ${ret.taskId} 不属于成员 ${ret.memberId}（当前认领者 ${task.memberId ?: "无"}）。",
            )
        }
        if (task.attemptId != ret.attemptId) {
            return MemberReturnResult.Refused(
                MemberReturnRefusal.ATTEMPT_MISMATCH,
                "attempt 不匹配：任务当前 attemptId 与回传的不一致，旧 attempt 的回传已拒绝。",
            )
        }
        if (task.state == "cancelled") {
            return MemberReturnResult.Refused(
                MemberReturnRefusal.TASK_TERMINAL,
                "任务 ${ret.taskId} 已取消：取消后 artifact 不再附加，已落盘的改动保留。",
            )
        }
        // P0 幂等：同一 attempt 已有回传记录时，直接返回已存记录
        // （第一次写赢），不重执行。终态（completed/failed）且无已存
        // 记录的回传仍拒绝（终态后不再附加）；取消见上（恒拒绝）。
        val existing = memberStore.getReturn(ret.taskId, ret.attemptId)
        if (existing != null) {
            return MemberReturnResult.Duplicate(existing)
        }
        if (task.state == "completed" || task.state == "failed") {
            return MemberReturnResult.Refused(
                MemberReturnRefusal.TASK_TERMINAL,
                "任务 ${ret.taskId} 已终态（${task.state}），不能再回传。",
            )
        }
        if (task.state != "assigned" && task.state != "working") {
            return MemberReturnResult.Refused(
                MemberReturnRefusal.TASK_NOT_RUNNING,
                "任务 ${ret.taskId} 不在执行中（当前 ${task.state}），回传已拒绝。",
            )
        }
        val deliveryErrors = ret.delivery?.validate() ?: emptyList()
        require(deliveryErrors.isEmpty()) {
            "交付记录非法：${deliveryErrors.joinToString("；")}"
        }
        val delivery = ret.delivery
        val filesJson = JSONArray().apply { delivery?.files?.forEach { put(it) } }.toString()
        val logsJson = JSONArray().apply { ret.logs.forEach { put(it) } }.toString()
        val entity = MemberReturnEntity(
            taskId = ret.taskId,
            attemptId = ret.attemptId,
            memberId = ret.memberId,
            branch = delivery?.branch,
            commit = delivery?.commit,
            prUrl = delivery?.prUrl,
            totalPages = delivery?.totalPages,
            pagesReceived = delivery?.pagesReceived,
            truncated = delivery?.truncated ?: false,
            filesJson = filesJson,
            summary = delivery?.summary,
            logsJson = logsJson,
            diffSummary = ret.diffSummary,
            testVerdict = ret.testVerdict.name,
            testSummary = ret.testSummary,
            recordedAtEpochSeconds = nowSeconds(),
        )
        memberStore.saveReturn(entity)
        return MemberReturnResult.Recorded(entity)
    }

    /**
     * 状态回传：把成员任务推到终态（completed/failed），结果/错误
     * 写进任务行。走 TaskStates 的合法路径，不跳步：
     * assigned → working → review → completed；
     * assigned/working → failed（经 working）。
     * review 是计划层的门：完成必须先过 review，失败可直达。
     *
     * 绑定核查与 [submitReturn] 相同（成员/连接/任务/认领者/
     * attempt）；终态任务、非执行中任务一律拒绝。状态回传与交付
     * 记录是两步：先 submitReturn（日志/diff/测试结果），再
     * finishTask（状态）；失败也可以没有交付记录。
     */
    fun finishTask(
        taskId: String,
        memberId: String,
        attemptId: String,
        terminalState: MemberFinishState,
        result: String? = null,
        error: String? = null,
    ): MemberFinishResult {
        val member = memberStore.getMember(memberId)
            ?: return MemberFinishResult.Refused(
                MemberReturnRefusal.MEMBER_NOT_FOUND,
                "成员不存在：$memberId",
            )
        if (!member.canDispatch()) {
            return MemberFinishResult.Refused(
                MemberReturnRefusal.MEMBER_NOT_CONNECTED,
                "成员 ${member.displayName} 未连接（等待安排）：状态回传已拒绝，未伪装成功。",
            )
        }
        val task = taskStore.getTask(taskId)
            ?: return MemberFinishResult.Refused(
                MemberReturnRefusal.TASK_NOT_FOUND,
                "任务不存在：$taskId",
            )
        if (task.memberId != memberId) {
            return MemberFinishResult.Refused(
                MemberReturnRefusal.MEMBER_MISMATCH,
                "任务 $taskId 不属于成员 $memberId（当前认领者 ${task.memberId ?: "无"}）。",
            )
        }
        if (task.attemptId != attemptId) {
            return MemberFinishResult.Refused(
                MemberReturnRefusal.ATTEMPT_MISMATCH,
                "attempt 不匹配：任务当前 attemptId 与回传的不一致，旧 attempt 的回传已拒绝。",
            )
        }
        if (task.state == "cancelled") {
            return MemberFinishResult.Refused(
                MemberReturnRefusal.TASK_TERMINAL,
                "任务 $taskId 已取消，不能再推终态。",
            )
        }
        if (task.state == "completed" || task.state == "failed") {
            // P0 幂等：同 attempt 的重复终态推返回已存任务（第一次写赢），
            // 不重执行。取消是人工控制决定，不在此列（见上，仍拒绝）。
            return MemberFinishResult.Finished(task)
        }
        if (task.state != "assigned" && task.state != "working") {
            return MemberFinishResult.Refused(
                MemberReturnRefusal.TASK_NOT_RUNNING,
                "任务 $taskId 不在执行中（当前 ${task.state}），状态回传已拒绝。",
            )
        }
        return try {
            var current = taskStore.getTask(taskId)
                ?: return MemberFinishResult.Refused(
                    MemberReturnRefusal.TASK_NOT_FOUND,
                    "任务不存在：$taskId",
                )
            if (current.state == "assigned") {
                current = step(current, "working")
                    ?: return MemberFinishResult.Refused(
                        MemberReturnRefusal.TASK_NOT_RUNNING,
                        "任务 $taskId 在推终态时消失。",
                    )
            }
            val finished = when (terminalState) {
                MemberFinishState.COMPLETED -> {
                    val review = step(current, "review")
                        ?: return MemberFinishResult.Refused(
                            MemberReturnRefusal.TASK_NOT_RUNNING,
                            "任务 $taskId 在推终态时消失。",
                        )
                    // P2：流水线任务的编码完成停在 review，不自动
                    // completed——审查走完才由协调器推 completed。
                    // 回传的 result 照常落到任务行上（审查时看得到）；
                    // 写不上也不影响 hold（best-effort）。
                    if (pipelineHook?.shouldHoldAtReview(taskId) == true) {
                        pipelineHook.onCodeFinished(taskId)
                        val withResult = if (result != null) {
                            try {
                                taskStore.updateTask(
                                    review.taskId,
                                    mapOf("result" to result.take(4000)),
                                    review.version, review.goalVersion, review.authEpoch,
                                )
                            } catch (_: Exception) {
                                null
                            }
                        } else {
                            null
                        }
                        return MemberFinishResult.Finished(withResult ?: review)
                    }
                    step(review, "completed", result, error)
                }
                MemberFinishState.FAILED ->
                    step(current, "failed", result, error)
            } ?: return MemberFinishResult.Refused(
                MemberReturnRefusal.TASK_NOT_RUNNING,
                "任务 $taskId 在推终态时消失。",
            )
            MemberFinishResult.Finished(finished)
        } catch (e: Exception) {
            // 并发改动（StaleTaskUpdate / ControlVersionStale / 非法
            // 跃迁）：任务被别人先动了，不写半截状态，调用方重读重试。
            MemberFinishResult.Refused(
                MemberReturnRefusal.TASK_NOT_RUNNING,
                "任务 $taskId 在推终态时被并发改动，请重读后重试：${e.message}",
            )
        }
    }

    private fun step(
        task: TaskRecord,
        target: String,
        result: String? = null,
        error: String? = null,
    ): TaskRecord? {
        val changes = mutableMapOf<String, Any?>("state" to target)
        if (result != null) changes["result"] = result
        if (error != null) changes["error"] = error
        return taskStore.updateTask(
            task.taskId,
            changes,
            task.version,
            task.goalVersion,
            task.authEpoch,
        )
    }

    /** 读回某次回传的交付记录（8 键）；没有回传时返回 null。 */
    fun deliveryRecordOf(taskId: String, attemptId: String): MemberDeliveryRecord? {
        val entity = memberStore.getReturn(taskId, attemptId) ?: return null
        val files = mutableListOf<String>()
        try {
            val array = JSONArray(entity.filesJson)
            for (i in 0 until array.length()) {
                val file = array.optString(i, "")
                if (file.isNotEmpty()) files += file
            }
        } catch (_: Exception) {
            // 坏 cell 当作空清单，不毁掉整行读。
        }
        return MemberDeliveryRecord(
            branch = entity.branch,
            commit = entity.commit,
            prUrl = entity.prUrl,
            totalPages = entity.totalPages,
            pagesReceived = entity.pagesReceived,
            truncated = entity.truncated,
            files = files,
            summary = entity.summary,
        )
    }
}
