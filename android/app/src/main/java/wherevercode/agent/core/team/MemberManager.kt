package wherevercode.agent.core.team

import wherevercode.agent.core.agent.AgentRecord
import wherevercode.agent.core.security.AuditEntry
import wherevercode.agent.core.security.AuditLog

/**
 * 成员管理（V5 Phase B C3 第二阶段，成员框架）。
 *
 * 管三件事：成员增删改查、成员 ↔ 项目绑定/解绑、连接与连通性
 * 状态写回。授权检查的权威是 [TeamMemberStore] 的绑定表
 * （member_project_grants），成员行上的 [TeamMember.authorizedProjectIds]
 * 只是显示镜像；派发时一律读绑定表，不读镜像。
 *
 * 审计：绑定/解绑/连接状态变化/连通性核查结果都经 [AuditLog] 留
 * 痕（只记元数据：成员 id、项目 id、动作、成败；文件内容、密钥、
 * 任务正文永不进日志）。审计失败不破坏本次操作（try/catch 吞掉，
 * 与 ToolRegistry/HandServer 同一做法）。
 *
 * 项目来源（maintainer 07:31，§21）：绑定里的 projectId 指向项目来源
 * 的记录（仓库/本地文件夹等多来源导入、来源的增删改查）；来源连
 * 接那一半不在本框架的文件集里，本框架不建来源模型、不占位项
 * 目，只做授权绑定这一半。来源连接是后续项（另派项目来源工人），
 * 届时绑定接到真实的来源记录上。
 *
 * 本类不做任何真实连接、不 spawn 进程、不调 Hand、不调 CLI：
 * [setConnectionState] 只是写状态位，CONNECTED 只能由以后的 Hand
 * 配对层在真实配对成功后调用；且形态未核实（UNVERIFIED）的成员
 * 拒绝置 CONNECTED——连接的前提是接法已核实。
 */
class MemberManager(
    private val store: TeamMemberStore,
    private val auditLog: AuditLog? = null,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    fun addMember(member: TeamMember): TeamMember {
        val errors = member.validate()
        require(errors.isEmpty()) { "成员记录非法：${errors.joinToString("；")}" }
        require(store.getMember(member.memberId) == null) {
            "成员已存在：${member.memberId}"
        }
        val now = nowSeconds()
        val stamped = member.copy(
            createdAtEpochSeconds = if (member.createdAtEpochSeconds == 0L) now
            else member.createdAtEpochSeconds,
            updatedAtEpochSeconds = now,
        )
        store.saveMember(stamped)
        audit("member.add", stamped.memberId, null, true, null)
        return stamped
    }

    fun getMember(memberId: String): TeamMember? = store.getMember(memberId)

    fun listMembers(): List<TeamMember> = store.listMembers()

    fun removeMember(memberId: String) {
        val member = store.getMember(memberId)
            ?: throw IllegalArgumentException("成员不存在：$memberId")
        store.deleteMember(memberId)
        audit("member.remove", member.memberId, null, true, null)
    }

    /**
     * 授权项目：成员只能操作已授权项目。绑定记录持久化（绑定表），
     * 无授权即拒绝分派（见 MemberDispatcher）。
     * [grantedBy] 记实际授予方（如 "user"），不留默认值冒充来源。
     */
    fun grantProject(memberId: String, projectId: String, grantedBy: String): TeamMember {
        require(projectId.isNotBlank()) { "项目 id 不能为空" }
        require(grantedBy.isNotBlank()) { "授予方不能为空" }
        val member = store.getMember(memberId)
            ?: throw IllegalArgumentException("成员不存在：$memberId")
        val now = nowSeconds()
        store.grantProject(memberId, projectId, grantedBy, now)
        audit("member.project.grant", memberId, projectId, true, null)
        return (store.getMember(memberId) ?: member).copy(
            lastStateUpdatedAtEpochSeconds = now,
            updatedAtEpochSeconds = now,
        ).also { store.saveMember(it) }
    }

    /**
     * 解绑：无此绑定即报错（区分“撤销成功”与“本来就没有”，见
     * R3-05 的 bug 提醒）；解绑留审计记录。
     */
    fun revokeProject(memberId: String, projectId: String): TeamMember {
        val member = store.getMember(memberId)
            ?: throw IllegalArgumentException("成员不存在：$memberId")
        if (!store.isAuthorized(memberId, projectId)) {
            audit("member.project.revoke", memberId, projectId, false, "member_project_not_granted")
            throw IllegalStateException("成员 $memberId 本来就没有授权项目 $projectId")
        }
        val now = nowSeconds()
        store.revokeProject(memberId, projectId)
        audit("member.project.revoke", memberId, projectId, true, null)
        return (store.getMember(memberId) ?: member).copy(
            lastStateUpdatedAtEpochSeconds = now,
            updatedAtEpochSeconds = now,
        ).also { store.saveMember(it) }
    }

    fun listProjects(memberId: String): List<String> {
        require(store.getMember(memberId) != null) { "成员不存在：$memberId" }
        return store.listProjects(memberId)
    }

    fun isAuthorized(memberId: String, projectId: String): Boolean =
        store.isAuthorized(memberId, projectId)

    /**
     * 写连接状态。CONNECTED 要求形态已核实（shapeStatus VERIFIED），
     * 否则拒绝——连接的前提是接法已核实；未核实的成员（Workbuddy、
     * Manus/LeChat/Grok 等待核实项）永远到不了 CONNECTED。
     * 本方法不建立任何连接，只写状态位。
     */
    fun setConnectionState(memberId: String, state: MemberConnectionState): TeamMember {
        val member = store.getMember(memberId)
            ?: throw IllegalArgumentException("成员不存在：$memberId")
        if (state == MemberConnectionState.CONNECTED &&
            member.shapeStatus != MemberShapeStatus.VERIFIED
        ) {
            audit("member.connection", memberId, null, false, "member_shape_unverified")
            throw IllegalStateException(
                "成员 $memberId 形态未核实，不能标为已连接：先核实接法，再配对连接。",
            )
        }
        val now = nowSeconds()
        val updated = member.copy(
            connectionState = state,
            lastStateUpdatedAtEpochSeconds = now,
            updatedAtEpochSeconds = now,
        )
        store.saveMember(updated)
        audit("member.connection", memberId, null, true, null)
        return updated
    }

    /**
     * 写连通性核查结果（“先验证哪条通”的记录位）。只写位，不改
     * 连接状态：核查通过 ≠ 已连接，连接另须配对。
     */
    fun recordConnectivityCheck(memberId: String, connectivity: MemberConnectivity): TeamMember {
        val member = store.getMember(memberId)
            ?: throw IllegalArgumentException("成员不存在：$memberId")
        val now = nowSeconds()
        val updated = member.copy(
            connectivity = connectivity,
            lastStateUpdatedAtEpochSeconds = now,
            updatedAtEpochSeconds = now,
        )
        store.saveMember(updated)
        audit("member.connectivity", memberId, null, true, null)
        return updated
    }

    /**
     * 写免费额度状态（maintainer 07:20 规则）。FREE_EXHAUSTED 只记状态，
     * 不静默切付费；分派时见 MemberDispatcher 的 QUOTA_EXHAUSTED
     * 拒绝。数额绝不在此写死——以 maintainer 账号 Limits 页为准。
     */
    fun updateQuotaState(
        memberId: String,
        state: MemberQuotaState,
        quotaNote: String? = null,
    ): TeamMember {
        val member = store.getMember(memberId)
            ?: throw IllegalArgumentException("成员不存在：$memberId")
        val now = nowSeconds()
        val updated = member.copy(
            quotaState = state,
            quotaNote = quotaNote ?: member.quotaNote,
            lastStateUpdatedAtEpochSeconds = now,
            updatedAtEpochSeconds = now,
        )
        store.saveMember(updated)
        audit("member.quota", memberId, null, true, null)
        return updated
    }

    /**
     * 写形态核实状态（以后在设备上由发现确定接法后调用；
     * [endpointNote] 只接受发现确定的备注，不写死路径与接口名）。
     */
    fun updateShapeStatus(
        memberId: String,
        status: MemberShapeStatus,
        endpointNote: String? = null,
    ): TeamMember {
        val member = store.getMember(memberId)
            ?: throw IllegalArgumentException("成员不存在：$memberId")
        val now = nowSeconds()
        val updated = member.copy(
            shapeStatus = status,
            endpointNote = endpointNote ?: member.endpointNote,
            lastStateUpdatedAtEpochSeconds = now,
            updatedAtEpochSeconds = now,
        )
        val errors = updated.validate()
        require(errors.isEmpty()) { "成员记录非法：${errors.joinToString("；")}" }
        store.saveMember(updated)
        audit("member.shape", memberId, null, true, null)
        return updated
    }

    private fun audit(
        action: String,
        memberId: String,
        projectId: String?,
        ok: Boolean,
        errorCode: String?,
    ) {
        val log = auditLog ?: return
        try {
            log.record(
                AuditEntry(
                    ts = nowSeconds().toString(),
                    taskId = "",
                    agentId = memberId,
                    action = action,
                    path = projectId,
                    command = null,
                    ok = ok,
                    errorCode = errorCode,
                    result = null,
                ),
            )
        } catch (_: Exception) {
            // 审计失败不破坏本次操作。
        }
    }
}

/**
 * 把成员状态镜像回 [AgentRecord] 的成员字段（纯函数，供以后 UI
 * 或目录显示用）。派发与授权永远不读 AgentRecord 的镜像字段，
 * 只读 [TeamMemberStore]——镜像只是显示，权威在成员表。
 */
fun TeamMember.syncToAgentRecord(record: AgentRecord): AgentRecord =
    record.copy(
        memberRole = role,
        memberConnectionState = connectionState,
        authorizedProjectIds = authorizedProjectIds,
        lastMemberStateUpdatedAtEpochSeconds = lastStateUpdatedAtEpochSeconds,
    )
