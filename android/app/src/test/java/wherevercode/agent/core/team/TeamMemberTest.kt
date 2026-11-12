package wherevercode.agent.core.team

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.AgentDiscovery
import wherevercode.agent.core.security.InMemoryAuditLog

/**
 * 成员模型 + 发现接线 + 成员管理（V5 Phase B C3 第二阶段，成员框架）。
 *
 * 全程替身/纯逻辑：不跑真实 CLI、不 spawn 进程、不做真实连接。
 * 覆盖：三种成员形态的校验与诚实默认值、发现映射只消费已有探针
 * 结果、增删改查与授权绑定/解绑、连接状态门禁（形态未核实拒绝
 * CONNECTED）、连通性核查位只写位不改连接。
 */
class TeamMemberTest {

    private fun codexRemote() = TeamMember.remoteCliMember(
        memberId = "codex-mac",
        agentId = "codex-cli",
        displayName = "Mac 上的 Codex",
        transport = MemberTransport.LAN,
        endpointDeviceId = "mac-1",
        endpointLabel = "Mac",
        shapeStatus = MemberShapeStatus.VERIFIED,
    )

    @Test
    fun `new member is honestly NOT_CONNECTED and cannot dispatch`() {
        val member = codexRemote()
        assertEquals(MemberConnectionState.NOT_CONNECTED, member.connectionState)
        assertEquals("未连接（等待安排）", member.displayConnection())
        assertFalse(member.canDispatch())
        assertEquals(MemberConnectivity.NOT_CHECKED, member.connectivity)
        assertTrue(member.validate().isEmpty())
    }

    @Test
    fun `remote CLI member validation rejects bad ids and wrong transport`() {
        assertTrue(
            codexRemote().copy(memberId = "Bad Id").validate()
                .any { it.contains("只能用小写字母") },
        )
        assertTrue(
            codexRemote().copy(transport = MemberTransport.CLOUD_API).validate()
                .any { it.contains("不能走云端 API") },
        )
        assertTrue(
            codexRemote().copy(displayName = " ").validate()
                .any { it.contains("名字不能为空") },
        )
    }

    @Test
    fun `cloud chat member LeChat goes through the generic adapter shape`() {
        val lechat = TeamMember.cloudChatMember(
            memberId = "lechat-1",
            agentId = "mistral",
            displayName = "LeChat",
            authRef = "key:mistral-1",
            quotaNote = "免费额度待核实",
        )
        assertEquals(MemberKind.CLOUD_CHAT, lechat.kind)
        assertEquals(MemberTransport.CLOUD_API, lechat.transport)
        assertEquals(MemberAuthKind.API_KEY_REF, lechat.authKind)
        assertEquals(MemberShapeStatus.UNVERIFIED, lechat.shapeStatus)
        assertEquals(MemberConnectionState.NOT_CONNECTED, lechat.connectionState)
        assertFalse(lechat.canDispatch())
        assertTrue(lechat.validate().isEmpty())
        // 密钥本身绝不能进记录。
        assertTrue(
            TeamMember.cloudChatMember(
                memberId = "lechat-1",
                agentId = "mistral",
                displayName = "LeChat",
                authRef = "sk-" + "test-fixture-value",
            ).validate().any { it.contains("只能是引用名") },
        )
        // 云端成员带服务地址须是 CLOUD_API 形态；非云端成员不能带。
        assertTrue(
            codexRemote().copy(baseUrl = "https://example.com").validate()
                .any { it.contains("只属于云端成员") },
        )
    }

    @Test
    fun `cloud task member Manus is a separate kind never in the generic adapter`() {
        // baseUrl 是调用方核实官方文档后传入的值，框架里没有写死的
        // 厂商地址默认值；任务型 API 不进通用 OpenAI 兼容适配器，
        // 以后须单独做云端适配器——种类上就与 CLOUD_CHAT 分开。
        val manus = TeamMember.cloudTaskMember(
            memberId = "manus-1",
            agentId = "manus",
            displayName = "Manus",
            authRef = "key:manus-1",
            baseUrl = "https://api.manus.ai",
            quotaNote = "免费额度待核实",
        )
        assertEquals(MemberKind.CLOUD_TASK, manus.kind)
        assertFalse(manus.kind == MemberKind.CLOUD_CHAT)
        assertEquals(MemberTransport.CLOUD_API, manus.transport)
        assertEquals(MemberAuthKind.API_KEY_REF, manus.authKind)
        assertEquals(MemberShapeStatus.UNVERIFIED, manus.shapeStatus)
        assertFalse(manus.canDispatch())
        assertTrue(manus.validate().isEmpty())
    }

    @Test
    fun `desktop app member stays UNVERIFIED with no invented CLI or API`() {
        val workbuddy = TeamMember.desktopAppMember(
            memberId = "workbuddy-mac",
            agentId = "workbuddy",
            displayName = "Mac 端 Workbuddy",
            endpointDeviceId = "mac-1",
            endpointLabel = "Mac",
        )
        assertEquals(MemberKind.DESKTOP_APP, workbuddy.kind)
        assertEquals(MemberShapeStatus.UNVERIFIED, workbuddy.shapeStatus)
        assertEquals(MemberAuthKind.NONE, workbuddy.authKind)
        // 唯一受支持的驱动是内置 CodeBuddy CLI headless（-p）；
        // 不是独立 CLI、不是 WorkBuddy API，社区逆向方案一律不采用。
        assertEquals("codebuddy-cli", workbuddy.driverAgentId)
        assertEquals(null, workbuddy.endpointNote)
        assertEquals(null, workbuddy.baseUrl)
        assertEquals(null, workbuddy.authRef)
        assertFalse(workbuddy.canDispatch())
        assertTrue(workbuddy.validate().isEmpty())
        // 桌面 App 成员在接口核实前不能有认证形态、不能走远程传输。
        assertTrue(
            workbuddy.copy(authKind = MemberAuthKind.CLI_LOGIN).validate()
                .any { it.contains("没有认证形态") },
        )
        assertTrue(
            workbuddy.copy(transport = MemberTransport.REMOTE).validate()
                .any { it.contains("只走 LAN") },
        )
    }

    @Test
    fun `discovery mapping only consumes existing probe results`() {
        val reports = listOf(
            AgentDiscovery.AgentReport(
                agentId = "codex-cli",
                displayName = "Codex CLI",
                installed = true,
                version = "codex 1.0",
                loginState = AgentDiscovery.LoginState.UNKNOWN,
                unavailableReason = null,
            ),
            AgentDiscovery.AgentReport(
                agentId = "gemini-cli",
                displayName = "Gemini CLI",
                installed = false,
                version = null,
                loginState = AgentDiscovery.LoginState.UNKNOWN,
                unavailableReason = "沙箱内未发现",
            ),
            AgentDiscovery.AgentReport(
                agentId = "codebuddy-cli",
                displayName = "CodeBuddy",
                installed = true,
                version = "codebuddy 2.0",
                loginState = AgentDiscovery.LoginState.UNKNOWN,
                unavailableReason = null,
            ),
        )
        val candidates = MemberCandidates.fromDiscovery(reports)
        assertEquals(3, candidates.size)
        val codex = candidates.first { it.agentId == "codex-cli" }
        assertEquals(MemberKind.DEVICE_CLI, codex.kind)
        assertEquals(MemberTransport.LOCAL_SANDBOX, codex.transport)
        // codex 的调用方式已验证（CliConfigs VERIFIED 行），形态为 VERIFIED；
        // 但连接仍是 NOT_CONNECTED，派发照样拒绝。
        assertEquals(MemberShapeStatus.VERIFIED, codex.shapeStatus)
        assertEquals(MemberConnectionState.NOT_CONNECTED, codex.connectionState)
        assertFalse(codex.canDispatch())
        assertTrue(codex.authorizedProjectIds.isEmpty())
        // gemini 未验证调用方式：形态 UNVERIFIED，不编能力。
        val gemini = candidates.first { it.agentId == "gemini-cli" }
        assertEquals(MemberShapeStatus.UNVERIFIED, gemini.shapeStatus)
        assertFalse(gemini.canDispatch())
        // codebuddy 同样 VERIFIED（CliConfigs VERIFIED 行）。
        assertEquals(
            MemberShapeStatus.VERIFIED,
            candidates.first { it.agentId == "codebuddy-cli" }.shapeStatus,
        )
        assertEquals("codex-cli", MemberCandidates.codexCandidate(reports)?.memberId)
        // 发现里永远没有 Workbuddy / Manus 的行：它们不走 CLI 发现。
        assertTrue(candidates.none { it.agentId == "workbuddy" })
        assertTrue(candidates.none { it.agentId == "manus" })
    }

    @Test
    fun `member manager CRUD grant revoke and audit trail`() {
        val audit = InMemoryAuditLog()
        val manager = MemberManager(InMemoryTeamMemberStore(), audit)
        val added = manager.addMember(codexRemote())
        assertEquals("codex-mac", added.memberId)
        assertTrue(added.createdAtEpochSeconds > 0)
        assertEquals(1, manager.listMembers().size)

        val granted = manager.grantProject("codex-mac", "proj-a", "user")
        assertTrue(granted.isAuthorizedFor("proj-a"))
        assertTrue(manager.isAuthorized("codex-mac", "proj-a"))
        assertEquals(listOf("proj-a"), manager.listProjects("codex-mac"))
        assertFalse(manager.isAuthorized("codex-mac", "proj-b"))

        val revoked = manager.revokeProject("codex-mac", "proj-a")
        assertFalse(revoked.isAuthorizedFor("proj-a"))
        assertTrue(manager.listProjects("codex-mac").isEmpty())

        // 解绑不存在的授权要明确报错（区分“撤销成功”与“本来就没有”）。
        var failed = false
        try {
            manager.revokeProject("codex-mac", "proj-a")
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertTrue(failed)

        manager.removeMember("codex-mac")
        assertTrue(manager.listMembers().isEmpty())
        val actions = audit.readEntries().map { it.action }
        assertTrue(actions.contains("member.add"))
        assertTrue(actions.contains("member.project.grant"))
        assertTrue(actions.contains("member.project.revoke"))
        assertTrue(actions.contains("member.remove"))
        // 审计只记元数据，不含密钥与正文。
        assertTrue(audit.readEntries().none { it.action.contains("sk-") })
    }

    @Test
    fun `unverified member cannot be marked CONNECTED`() {
        val manager = MemberManager(InMemoryTeamMemberStore(), InMemoryAuditLog())
        manager.addMember(
            TeamMember.desktopAppMember(
                memberId = "workbuddy-mac",
                agentId = "workbuddy",
                displayName = "Mac 端 Workbuddy",
            ),
        )
        var failed = false
        try {
            manager.setConnectionState("workbuddy-mac", MemberConnectionState.CONNECTED)
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertTrue(failed)
        assertEquals(
            MemberConnectionState.NOT_CONNECTED,
            manager.getMember("workbuddy-mac")?.connectionState,
        )
        // 形态核实后才允许置 CONNECTED（仍只是写状态位，不建立连接）。
        manager.updateShapeStatus("workbuddy-mac", MemberShapeStatus.VERIFIED)
        val connected = manager.setConnectionState(
            "workbuddy-mac",
            MemberConnectionState.CONNECTED,
        )
        assertTrue(connected.canDispatch())
        assertEquals("已连接", connected.displayConnection())
    }

    @Test
    fun `connectivity check only records the bit never the connection`() {
        val manager = MemberManager(InMemoryTeamMemberStore(), InMemoryAuditLog())
        manager.addMember(codexRemote())
        val checked = manager.recordConnectivityCheck(
            "codex-mac",
            MemberConnectivity.LAN_REACHABLE,
        )
        assertEquals(MemberConnectivity.LAN_REACHABLE, checked.connectivity)
        // 核查通过 ≠ 已连接。
        assertEquals(MemberConnectionState.NOT_CONNECTED, checked.connectionState)
        assertFalse(checked.canDispatch())
        val failed = manager.recordConnectivityCheck(
            "codex-mac",
            MemberConnectivity.REMOTE_UNREACHABLE,
        )
        assertEquals(MemberConnectivity.REMOTE_UNREACHABLE, failed.connectivity)
        assertEquals(MemberConnectionState.NOT_CONNECTED, failed.connectionState)
    }

    @Test
    fun `duplicate member id is rejected`() {
        val manager = MemberManager(InMemoryTeamMemberStore(), InMemoryAuditLog())
        manager.addMember(codexRemote())
        var failed = false
        try {
            manager.addMember(codexRemote())
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
        assertEquals(1, manager.listMembers().size)
    }

    @Test
    fun `syncToAgentRecord mirrors member state for display only`() {
        val member = codexRemote().copy(
            authorizedProjectIds = listOf("proj-a"),
            lastStateUpdatedAtEpochSeconds = 1_700_000_000L,
        )
        val record = wherevercode.agent.core.agent.AgentRecord(
            agentId = "codex-cli",
            displayName = "Codex CLI",
            kind = wherevercode.agent.core.agent.AgentKind.DEVICE,
            access = wherevercode.agent.core.agent.AgentAccess.CLI,
            brainKind = wherevercode.agent.brain.BrainKind.AGENT_RUNTIME,
            adapterId = "codex",
            binary = "codex",
        )
        val mirrored = member.syncToAgentRecord(record)
        assertEquals(MemberRole.DEVELOPER, mirrored.memberRole)
        assertEquals(
            MemberConnectionState.NOT_CONNECTED,
            mirrored.memberConnectionState,
        )
        assertEquals(listOf("proj-a"), mirrored.authorizedProjectIds)
        assertEquals(1_700_000_000L, mirrored.lastMemberStateUpdatedAtEpochSeconds)
        // 原记录字段语义不变。
        assertEquals("codex", mirrored.adapterId)
        assertEquals("codex", mirrored.binary)
    }

    @Test
    fun `unverified device member for Xiaomi 12 agy keeps identity unconfirmed`() {
        // “agy”指什么未确认（是不是 Termux 里的 agent 待 maintainer 回）；
        // agentId 只是本地占位符，绝不能假设它就是老项目的 agy CLI。
        val agy = TeamMember.unverifiedDeviceMember(
            memberId = "agy-xiaomi12",
            displayName = "小米 12 上的 agy",
            transport = MemberTransport.LAN,
            endpointDeviceId = "xiaomi12",
            endpointLabel = "小米 12",
        )
        assertEquals(MemberKind.DEVICE_CLI, agy.kind)
        assertEquals("agy-xiaomi12", agy.agentId)
        assertEquals(MemberShapeStatus.UNVERIFIED, agy.shapeStatus)
        assertEquals(MemberConnectionState.NOT_CONNECTED, agy.connectionState)
        assertFalse(agy.canDispatch())
        assertTrue(agy.validate().isEmpty())
        // 形态未核实：标 CONNECTED 必须被拒。
        val manager = MemberManager(InMemoryTeamMemberStore(), InMemoryAuditLog())
        manager.addMember(agy)
        var failed = false
        try {
            manager.setConnectionState("agy-xiaomi12", MemberConnectionState.CONNECTED)
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun `grok CLI candidate is unverified and never assumed installed`() {
        // 没有 grok 探针结果时，发现里就不该有 grok 的行。
        val empty = MemberCandidates.fromDiscovery(emptyList())
        assertTrue(empty.none { it.agentId == "grok" })
        // 即使有探针结果，形态也是 UNVERIFIED、连接 NOT_CONNECTED：
        // Example Agent CLI 在任何设备上是否已装均未核实，不假设、也不装。
        val reports = listOf(
            AgentDiscovery.AgentReport(
                agentId = "grok",
                displayName = "Grok CLI",
                installed = true,
                version = "grok 0.1",
                loginState = AgentDiscovery.LoginState.UNKNOWN,
                unavailableReason = null,
            ),
        )
        val candidates = MemberCandidates.fromDiscovery(reports)
        assertEquals(1, candidates.size)
        val grok = candidates.first()
        assertEquals(MemberKind.DEVICE_CLI, grok.kind)
        assertEquals(MemberShapeStatus.UNVERIFIED, grok.shapeStatus)
        assertEquals(MemberConnectionState.NOT_CONNECTED, grok.connectionState)
        assertFalse(grok.canDispatch())
    }

    @Test
    fun `quota state starts UNKNOWN and exhaust is recorded explicitly`() {
        val audit = InMemoryAuditLog()
        val manager = MemberManager(InMemoryTeamMemberStore(), audit)
        manager.addMember(codexRemote())
        assertEquals(
            MemberQuotaState.UNKNOWN,
            manager.getMember("codex-mac")?.quotaState,
        )
        val exhausted = manager.updateQuotaState(
            "codex-mac",
            MemberQuotaState.FREE_EXHAUSTED,
            "免费额度已耗尽，待 maintainer 控制台核对",
        )
        assertEquals(MemberQuotaState.FREE_EXHAUSTED, exhausted.quotaState)
        assertTrue(audit.readEntries().any { it.action == "member.quota" })
        // 额度状态位本身不含任何未经核实的数字。
        assertTrue(exhausted.quotaNote?.contains("300") == false)
        val available = manager.updateQuotaState(
            "codex-mac",
            MemberQuotaState.FREE_AVAILABLE,
        )
        assertEquals(MemberQuotaState.FREE_AVAILABLE, available.quotaState)
    }
}
