package wherevercode.agent.core.team

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import wherevercode.agent.brain.BrainKind
import wherevercode.agent.core.agent.AgentAccess
import wherevercode.agent.core.agent.AgentKind
import wherevercode.agent.core.agent.AgentRecord

/**
 * P3 团队频道规则测试（2026-11-11）：发帖校验、扇出选枝、只读简报、
 * 频道成员表（全部 Agent）、@ 中转简报。
 * 全是纯函数，无 Android 依赖、无 IO。
 */
@RunWith(RobolectricTestRunner::class)
class TeamChannelTest {

    private fun record(
        agentId: String,
        displayName: String = agentId,
        kind: AgentKind = AgentKind.DEVICE,
        brainKind: BrainKind = BrainKind.AGENT_RUNTIME,
        adapterId: String = "test-adapter",
    ) = AgentRecord(
        agentId = agentId,
        displayName = displayName,
        kind = kind,
        access = AgentAccess.CLI,
        brainKind = brainKind,
        adapterId = adapterId,
        binary = "test",
    )

    @Test
    fun `validatePost rejects blank and overlong text`() {
        assertEquals("消息不能为空。", TeamChatRules.validatePost("", 0))
        assertEquals("消息不能为空。", TeamChatRules.validatePost("   ", 0))
        val overlong = "x".repeat(TeamChatLimits.MAX_MESSAGE_CHARS + 1)
        assertTrue(TeamChatRules.validatePost(overlong, 0)!!.contains("消息太长"))
        assertEquals(TeamChatLimits.MAX_MESSAGE_CHARS, 500)
    }

    @Test
    fun `validatePost enforces the round cap`() {
        assertNull(TeamChatRules.validatePost("在吗", 0))
        assertNull(TeamChatRules.validatePost("在吗", TeamChatLimits.MAX_ROUNDS - 1))
        assertTrue(
            TeamChatRules.validatePost("在吗", TeamChatLimits.MAX_ROUNDS)!!
                .contains("已到 3 轮上限"),
        )
    }

    @Test
    fun `selectFanoutTargets prefers one branch per device then fills in order`() {
        val candidates = listOf(
            FanoutCandidate("codex", "mac", "Mac 上的 Codex"),
            FanoutCandidate("claude", "mac", "Mac 上的 Claude"),
            FanoutCandidate("codebuddy", "remote-device", "VPS 上的 CodeBuddy"),
            FanoutCandidate("example-agent", "remote-device", "VPS 上的 Example Agent"),
            FanoutCandidate("agy", "xiaomi12", "小米 12 上的 agy"),
        )
        val selection = TeamChatRules.selectFanoutTargets(candidates, maxBranches = 3)
        // 先每设备一个（mac、remote-device、xiaomi12），不重复扇同一台设备。
        assertEquals(
            listOf("codex", "codebuddy", "agy"),
            selection.selected.map { it.agentId },
        )
        assertEquals(2, selection.dropped)
    }

    @Test
    fun `selectFanoutTargets caps at five and reports dropped`() {
        val candidates = (1..7).map {
            FanoutCandidate("agent-$it", "device-$it", "Agent $it")
        }
        val selection = TeamChatRules.selectFanoutTargets(candidates)
        assertEquals(TeamChatLimits.MAX_FANOUT_BRANCHES, selection.selected.size)
        assertEquals(2, selection.dropped)
    }

    @Test
    fun `selectFanoutTargets treats null device as its own group`() {
        val candidates = listOf(
            FanoutCandidate("a", null, "A"),
            FanoutCandidate("b", null, "B"),
            FanoutCandidate("c", "mac", "C"),
        )
        val selection = TeamChatRules.selectFanoutTargets(candidates, maxBranches = 2)
        assertEquals(listOf("a", "c"), selection.selected.map { it.agentId })
    }

    @Test
    fun `selectFanoutTargets with zero cap selects nothing`() {
        val selection = TeamChatRules.selectFanoutTargets(
            listOf(FanoutCandidate("a", "d1", "A")),
            maxBranches = 0,
        )
        assertTrue(selection.selected.isEmpty())
        assertEquals(1, selection.dropped)
    }

    @Test
    fun `buildFanoutBrief carries the read-only instruction first`() {
        val brief = TeamChatRules.buildFanoutBrief(
            "这个方案行不行？",
            "修登录闪退",
            "点登录不再闪退",
        )
        assertTrue(brief.startsWith("[只读咨询] 这个方案行不行？"))
        assertTrue(brief.contains("任务“修登录闪退”：点登录不再闪退"))
        assertTrue(brief.contains("不要改任何文件、不要认领任务、不要提交结果"))
        assertTrue(brief.contains("不超过 500 字的摘要"))
    }

    @Test
    fun `buildFanoutBrief without a task says so honestly`() {
        val brief = TeamChatRules.buildFanoutBrief("在吗", null, null)
        assertTrue(brief.contains("没有关联任务"))
    }

    @Test
    fun `buildChannelMembers lists every registered agent with local app first`() {
        // maintainer 2026-11-11 22:19：全部 Agent 拉入频道，不只 2–3 个。
        val agents = listOf(
            record("manus", "Manus", kind = AgentKind.CLOUD, brainKind = BrainKind.CLOUD_AGENT, adapterId = "manus"),
            record("codebuddy", "CodeBuddy"),
            record("opencode", "OpenCode"),
            record("claude", "Claude"),
            record("example-agent", "Example Agent"),
            record("agy", "agy"),
            record("codex", "Codex"),
            record("example-agent", "Example Agent", kind = AgentKind.CLOUD, brainKind = BrainKind.PLATFORM_AGENT, adapterId = "platform-agent"),
        )
        val members = buildChannelMembers(agents)
        assertEquals(8, members.size)
        assertEquals("local-app", members.first().agentId)
        assertTrue(members.first().isLocalApp)
        assertEquals(
            listOf("manus", "codebuddy", "opencode", "claude", "example-agent", "agy", "codex"),
            members.drop(1).map { it.agentId },
        )
        // 只有平台 Agent 标出来（端点直连，中转时跳过它）。
        assertEquals(emptyList<String>(), members.filter { it.isPlatformAgent }.map { it.agentId })
    }

    @Test
    fun `buildChannelMembers dedupes by agentId`() {
        val members = buildChannelMembers(
            listOf(record("codex", "Codex"), record("codex", "Codex 副本"), record("agy", "agy")),
        )
        assertEquals(listOf("local-app", "codex", "agy"), members.map { it.agentId })
    }

    @Test
    fun `buildChannelMembers excludes disabled agents`() {
        // Agent 开关（2026-11-12）：停用的 Agent 不进频道；重新启用后
        // 下一刷新即回来（热插拔，成员表每次现算）。
        val members = buildChannelMembers(
            listOf(
                record("codex", "Codex"),
                record("agy", "agy").copy(enabled = false),
                record("example-agent", "Example Agent", kind = AgentKind.CLOUD, brainKind = BrainKind.PLATFORM_AGENT, adapterId = "platform-agent"),
            ),
        )
        assertEquals(listOf("local-app", "codex", "example-agent"), members.map { it.agentId })
    }

    @Test
    fun `buildRelayBrief says it is a relay not a dispatch`() {
        val brief = TeamChatRules.buildRelayBrief("Example Agent", "@codex 这个方案行不行？")
        assertTrue(brief.contains("@ 转达"))
        assertTrue(brief.contains("Example Agent 在频道里 @ 了你"))
        assertTrue(brief.contains("@codex 这个方案行不行？"))
        assertTrue(brief.contains("不要改任何文件、不要建任务、不要认领派活"))
        assertTrue(brief.contains("你的回复会原样贴回频道"))
    }

    // 2026-11-12 团队对话分串 + 发言权（maintainer 06:38/06:40 定）：
    // 讨论多声音、各署名；任务汇报只走协调者一个声音；消息按任务成串。

    private fun threadMsg(
        id: String,
        senderId: String,
        senderName: String,
        text: String,
        taskId: String? = null,
        taskTitle: String? = null,
        kind: TeamMessageKind = TeamMessageKind.DISCUSSION,
        at: Double = 0.0,
    ) = TeamThreadMessage(
        messageId = id,
        senderId = senderId,
        senderName = senderName,
        text = text,
        taskId = taskId,
        taskTitle = taskTitle,
        kind = kind,
        createdAtSeconds = at,
    )

    @Test
    fun `groupTeamMessages keeps two tasks in separate threads`() {
        // 两个任务的对话不串：同一任务的消息进同一串，别的任务的
        // 一条也混不进来；串内发言者各带署名。
        val messages = listOf(
            threadMsg("m1", "codex", "Codex", "登录闪退我看了，是空指针。", "task-a", "修登录闪退", at = 1.0),
            threadMsg("m2", "claude", "Claude", "包我编好了，等你测。", "task-b", "编包", at = 2.0),
            threadMsg("m3", "codex", "Codex", "已补判空，测试过了。", "task-a", "修登录闪退", at = 3.0),
            threadMsg("m4", "example-agent", "Example Agent", "包已上传 Drive。", "task-b", "编包", at = 4.0),
        )
        val threads = groupTeamMessages(messages)
        assertEquals(2, threads.size)
        val login = threads.first { it.taskId == "task-a" }
        val pack = threads.first { it.taskId == "task-b" }
        assertEquals(listOf("m1", "m3"), login.messages.map { it.messageId })
        assertEquals(listOf("m2", "m4"), pack.messages.map { it.messageId })
        assertEquals("任务 task-a · 修登录闪退", teamThreadTitle(login))
        assertEquals("任务 task-b · 编包", teamThreadTitle(pack))
        // 每条都带发送者（讨论多声音、各署名）。
        assertEquals(listOf("Codex", "Codex"), login.messages.map { it.senderName })
    }

    @Test
    fun `groupTeamMessages puts unattributed messages in the general thread`() {
        val messages = listOf(
            threadMsg("m1", "codex", "Codex", "在吗", at = 1.0),
            threadMsg("m2", "codex", "Codex", "登录闪退修好了。", "task-a", "修登录闪退", at = 2.0),
            threadMsg("m3", "example-agent", "Example Agent", "大家早。", at = 3.0),
        )
        val threads = groupTeamMessages(messages)
        assertEquals(2, threads.size)
        val general = threads.first { it.taskId == null }
        assertEquals("综合讨论", teamThreadTitle(general))
        assertEquals(listOf("m1", "m3"), general.messages.map { it.messageId })
        assertEquals(listOf("m2"), threads.first { it.taskId == "task-a" }.messages.map { it.messageId })
    }

    @Test
    fun `groupTeamMessages orders threads by first message and keeps time order inside`() {
        val messages = listOf(
            threadMsg("m2", "claude", "Claude", "第二条", "task-b", "编包", at = 5.0),
            threadMsg("m1", "codex", "Codex", "第一条", "task-a", "修登录闪退", at = 1.0),
            threadMsg("m3", "codex", "Codex", "第三条", "task-a", "修登录闪退", at = 9.0),
            threadMsg("m4", "codex", "Codex", "第四条", "task-a", "修登录闪退", at = 2.0),
        )
        val threads = groupTeamMessages(messages)
        assertEquals(listOf("task-a", "task-b"), threads.map { it.taskId })
        assertEquals(listOf("m1", "m4", "m3"), threads[0].messages.map { it.messageId })
    }

    @Test
    fun `teamAttributionHeader and parse round-trip`() {
        val header = teamAttributionHeader("Codex", "abc12345-def0", "修登录闪退")
        assertEquals("【Codex · 任务 abc12345 · 修登录闪退】", header)
        val parsed = parseTeamAttribution("$header\n行，先补测试。")
        assertEquals("Codex", parsed?.senderName)
        assertEquals("abc12345", parsed?.taskId)
        assertEquals("修登录闪退", parsed?.taskTitle)
        // 无任务时只写发送者。
        assertEquals("【Codex】", teamAttributionHeader("Codex", null, null))
        assertEquals("Codex", parseTeamAttribution("【Codex】\n在。")?.senderName)
        assertNull(parseTeamAttribution("【Codex】\n在。")?.taskId)
        // 无头返回 null。
        assertNull(parseTeamAttribution("大家在吗"))
        // 正文去头后只剩内容。
        assertEquals("行，先补测试。", stripTeamAttributionHeader("$header\n行，先补测试。"))
    }

    @Test
    fun `parseTeamAttribution reads the legacy bracket prefix`() {
        // 2026-11-12 之前的中转前缀 "[显示名] "：只有发送者、无任务，
        // 读回时不丢发送者归属。
        val parsed = parseTeamAttribution("[Codex] 行，但要先补测试。")
        assertEquals("Codex", parsed?.senderName)
        assertNull(parsed?.taskId)
        assertEquals("行，但要先补测试。", stripTeamAttributionHeader("[Codex] 行，但要先补测试。"))
    }

    @Test
    fun `discussion allows direct agent voices, report does not`() {
        // maintainer 2026-11-12 06:40 定稿：讨论多声音、各署名；任务汇报
        // 只走协调者一个声音。
        assertTrue(TeamVoice.directAgentVoiceAllowed(TeamMessageKind.DISCUSSION))
        assertTrue(!TeamVoice.directAgentVoiceAllowed(TeamMessageKind.REPORT))
        assertEquals("local-app", TeamVoice.COORDINATOR_ID)
        assertEquals("协调者", TeamVoice.COORDINATOR_NAME)
    }

    @Test
    fun `routeReportToCoordinator re-attributes an agent report to the coordinator`() {
        // 任务汇报只走协调者一个声音：Agent 的汇报转成协调者口径，
        // 发送者改协调者，正文前加“代 <Agent> 汇报”，任务归属保留。
        val report = threadMsg(
            "m1", "codex", "Codex", "包已编好，测试全过。",
            "task-b", "编包", kind = TeamMessageKind.REPORT, at = 1.0,
        )
        val routed = TeamVoice.routeReportToCoordinator(report)
        assertEquals(TeamVoice.COORDINATOR_ID, routed.senderId)
        assertEquals(TeamVoice.COORDINATOR_NAME, routed.senderName)
        assertEquals("代 Codex 汇报：包已编好，测试全过。", routed.text)
        assertEquals("task-b", routed.taskId)
        assertEquals("编包", routed.taskTitle)
        assertEquals(TeamMessageKind.REPORT, routed.kind)
        // 讨论消息不动（多声音、各署名）。
        val discussion = threadMsg("m2", "codex", "Codex", "这个方案行。", "task-a", "修登录闪退")
        assertEquals(discussion, TeamVoice.routeReportToCoordinator(discussion))
        // 协调者本人的汇报不动。
        val own = threadMsg(
            "m3", TeamVoice.COORDINATOR_ID, TeamVoice.COORDINATOR_NAME, "进度正常。",
            "task-b", "编包", kind = TeamMessageKind.REPORT,
        )
        assertEquals(own, TeamVoice.routeReportToCoordinator(own))
    }

    @Test
    fun `teamMessageFromChannel parses the header for sender and task`() {
        val stamped = wherevercode.agent.core.agent.PlatformChannelMessage(
            id = "m1",
            agentId = "local-app",
            agentName = "本机 App",
            text = "【Codex · 任务 abc12345 · 修登录闪退】\n行，先补测试。",
            createdAtSeconds = 10.0,
        )
        val view = teamMessageFromChannel(stamped)
        assertEquals("Codex", view.senderName)
        assertEquals("abc12345", view.taskId)
        assertEquals("修登录闪退", view.taskTitle)
        assertEquals("行，先补测试。", view.text)
        assertEquals(TeamMessageKind.DISCUSSION, view.kind)
        // 无头消息：用消息自带的发送者、无任务归属。
        val plain = wherevercode.agent.core.agent.PlatformChannelMessage(
            id = "m2",
            agentId = "example-agent",
            agentName = "Example Agent",
            text = "大家早。",
            createdAtSeconds = 11.0,
        )
        val plainView = teamMessageFromChannel(plain)
        assertEquals("Example Agent", plainView.senderName)
        assertNull(plainView.taskId)
        assertEquals("大家早。", plainView.text)
    }
}
