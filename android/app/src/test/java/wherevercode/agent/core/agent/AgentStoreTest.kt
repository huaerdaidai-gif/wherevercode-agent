package wherevercode.agent.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.brain.BrainKind

/**
 * Agent 记录校验 + 内存存储（阶段 3，A1 P1）。
 *
 * 回归覆盖（maintainer 2026-11-09 12:53 / GPT d268a6ae B 线要求）：
 * 旧项目只借了 agent_directory.py 的字段形状与 quota_rule 文案
 * 语义（见 AgentRecord.kt 头注释），校验与存储均为新写；本文件
 * 覆盖“该段功能 + 与新 App 全局的兼容性”（校验规则与存储口）。
 * SharedPreferences 版见 SharedPreferencesAgentStoreTest（Robolectric）。
 */
class AgentStoreTest {

    private fun apiRecord(
        agentId: String = "deepseek-1",
        baseUrl: String? = "https://api.deepseek.com",
        model: String? = "deepseek-flash",
        authRef: String? = "key:deepseek-1",
    ) = AgentRecord(
        agentId = agentId,
        displayName = "DeepSeek 闪电",
        kind = AgentKind.CLOUD,
        access = AgentAccess.API,
        brainKind = BrainKind.MODEL_API,
        adapterId = "deepseek",
        baseUrl = baseUrl,
        model = model,
        authRef = authRef,
    )

    @Test
    fun `valid MODEL_API record passes validation`() {
        assertTrue(apiRecord().validate().isEmpty())
    }

    @Test
    fun `blank id and bad id are rejected`() {
        assertTrue(apiRecord(agentId = "").validate().any { it.contains("id 不能为空") })
        assertTrue(apiRecord(agentId = "DeepSeek").validate().any { it.contains("只能用小写字母") })
        assertTrue(apiRecord(agentId = "-bad").validate().any { it.contains("只能用小写字母") })
        assertTrue(apiRecord(agentId = "ok-id_2").validate().isEmpty())
    }

    @Test
    fun `blank name and adapter are rejected`() {
        assertTrue(apiRecord().copy(displayName = "  ").validate().any { it.contains("名字不能为空") })
        assertTrue(apiRecord().copy(adapterId = "").validate().any { it.contains("适配器 id 不能为空") })
    }

    @Test
    fun `MODEL_API requires baseUrl and model`() {
        assertTrue(apiRecord(baseUrl = null).validate().any { it.contains("需要填写服务地址") })
        assertTrue(apiRecord(model = null).validate().any { it.contains("需要填写模型") })
    }

    @Test
    fun `MODEL_API rejects plain http except local model urls`() {
        val errors = apiRecord(baseUrl = "http://192.168.1.7:8080").validate()
        assertTrue(errors.any { it.contains("只允许 https") })
        assertTrue(apiRecord(baseUrl = "http://127.0.0.1:8080").validate().isEmpty())
        assertTrue(apiRecord(baseUrl = "http://localhost:8080").validate().isEmpty())
        assertTrue(apiRecord(baseUrl = "https://api.deepseek.com").validate().isEmpty())
    }

    @Test
    fun `SUBSCRIPTION must not carry baseUrl or authRef`() {
        val sub = AgentRecord(
            agentId = "chatgpt-sub",
            displayName = "ChatGPT 会员",
            kind = AgentKind.CLOUD,
            access = AgentAccess.APP,
            brainKind = BrainKind.SUBSCRIPTION,
            adapterId = "subscription",
            baseUrl = "https://chatgpt.com",
        )
        assertTrue(sub.validate().any { it.contains("没有服务地址") })
        assertTrue(sub.copy(baseUrl = null, authRef = "key:x").validate().any { it.contains("没有密钥输入框") })
        assertTrue(sub.copy(baseUrl = null).validate().isEmpty())
    }

    @Test
    fun `AGENT_RUNTIME and CLI access require a binary`() {
        val cli = AgentRecord(
            agentId = "codebuddy-cli",
            displayName = "CodeBuddy",
            kind = AgentKind.DEVICE,
            access = AgentAccess.CLI,
            brainKind = BrainKind.AGENT_RUNTIME,
            adapterId = "codebuddy",
            binary = null,
        )
        val errors = cli.validate()
        assertTrue(errors.any { it.contains("需要填写 CLI 二进制名") })
        assertTrue(cli.copy(binary = "codebuddy").validate().isEmpty())
    }

    @Test
    fun `authRef must be a reference name never a secret value`() {
        assertTrue(
            apiRecord(authRef = "sk-" + "test-fixture-value").validate()
                .any { it.contains("只能是引用名") },
        )
        assertTrue(
            apiRecord(authRef = "Bearer abcdefghijklmnop").validate()
                .any { it.contains("只能是引用名") },
        )
        assertTrue(apiRecord(authRef = "key:deepseek-1").validate().isEmpty())
        assertTrue(apiRecord(authRef = "").validate().any { it.contains("不能为空字符串") })
    }

    @Test
    fun `defaultQuotaRule reuses the old directory wording`() {
        assertEquals("按厂商额度", AgentRecord.defaultQuotaRule(AgentKind.CLOUD))
        assertEquals("设备本地，不占云端额度", AgentRecord.defaultQuotaRule(AgentKind.DEVICE))
        assertEquals("设备本地，不占云端额度", AgentRecord.defaultQuotaRule(AgentKind.LOCAL_MODEL))
    }

    @Test
    fun `isLocalModelUrl matches only loopback hosts`() {
        assertTrue(AgentRecord.isLocalModelUrl("http://127.0.0.1:8080"))
        assertTrue(AgentRecord.isLocalModelUrl("http://localhost"))
        assertTrue(AgentRecord.isLocalModelUrl("http://127.0.0.1/models"))
        assertTrue(!AgentRecord.isLocalModelUrl("https://api.deepseek.com"))
        assertTrue(!AgentRecord.isLocalModelUrl("http://192.168.1.7:8080"))
        assertTrue(!AgentRecord.isLocalModelUrl("http://127.0.0.1.evil.com"))
    }

    @Test
    fun `inMemory store round trip keeps createdAt on overwrite`() {
        val store = InMemoryAgentStore()
        assertTrue(store.listAgents().isEmpty())
        store.saveAgent(apiRecord())
        val saved = store.getAgent("deepseek-1")
        assertEquals("DeepSeek 闪电", saved?.displayName)
        assertTrue((saved?.createdAtEpochSeconds ?: 0) > 0)
        assertTrue((saved?.updatedAtEpochSeconds ?: 0) > 0)

        store.saveAgent(apiRecord().copy(displayName = "DeepSeek 改名"))
        val updated = store.getAgent("deepseek-1")
        assertEquals("DeepSeek 改名", updated?.displayName)
        assertEquals(saved?.createdAtEpochSeconds, updated?.createdAtEpochSeconds)
        assertEquals(1, store.listAgents().size)

        store.deleteAgent("deepseek-1")
        assertNull(store.getAgent("deepseek-1"))
        assertTrue(store.listAgents().isEmpty())
    }

    @Test
    fun `inMemory getAgent falls back to case-insensitive match`() {
        // 2026-11-12 回复归属修复：注册 id 强制小写（"codex"），派活
        // 方常传显示名（"Codex"）——精确匹配不到就当 Agent 不存在，
        // 任务静默排队永不被认领（20106890 真机事故）。回落只读、
        // 不改注册的小写 id；返回的仍是注册记录本身。
        val store = InMemoryAgentStore()
        store.saveAgent(apiRecord(agentId = "codex").copy(displayName = "Codex"))
        assertEquals("codex", store.getAgent("Codex")?.agentId)
        assertEquals("Codex", store.getAgent("Codex")?.displayName)
        assertEquals("codex", store.getAgent("CODEX")?.agentId)
        assertEquals("codex", store.getAgent("codex")?.agentId)
        assertNull(store.getAgent("unknown"))
    }

    @Test
    fun `inMemory store refuses invalid records`() {
        val store = InMemoryAgentStore()
        assertThrows(IllegalArgumentException::class.java) {
            store.saveAgent(apiRecord(agentId = "Bad Id"))
        }
        assertTrue(store.listAgents().isEmpty())
    }

    @Test
    fun `platform and cloud record fields round trip through the store`() {
        val store = InMemoryAgentStore()
        val platform = AgentRecord(
            agentId = "example-agent",
            displayName = "Example Agent",
            kind = AgentKind.CLOUD,
            access = AgentAccess.WEBHOOK,
            brainKind = BrainKind.PLATFORM_AGENT,
            adapterId = "platform-agent",
            baseUrl = "https://example.invalid",
            platformAgentId = "example-agent",
            platformHost = "example.invalid",
            platformChannelId = "chan-1",
            webhookUrl = "https://example.com/hook",
            webhookSecretRef = "platform-agent:example-agent:webhook-secret",
            directGithubRead = true,
        )
        store.saveAgent(platform)
        val savedPlatform = store.getAgent("example-agent")
        assertEquals("example-agent", savedPlatform?.platformAgentId)
        assertEquals("example.invalid", savedPlatform?.platformHost)
        assertEquals("chan-1", savedPlatform?.platformChannelId)
        assertEquals("https://example.com/hook", savedPlatform?.webhookUrl)
        assertEquals("platform-agent:example-agent:webhook-secret", savedPlatform?.webhookSecretRef)
        assertEquals(true, savedPlatform?.directGithubRead)

        val cloud = AgentRecord(
            agentId = "grok-cloud",
            displayName = "Grok 云端",
            kind = AgentKind.CLOUD,
            access = AgentAccess.CONNECTOR,
            brainKind = BrainKind.CLOUD_AGENT,
            adapterId = "cloud-agent",
            authRef = "cloud-agent:grok-cloud:mcp-token",
            cloudRoles = listOf("dev", "review", "deliver"),
            canDispatch = false,
            cloudHomeDeviceId = "mac",
        )
        store.saveAgent(cloud)
        val savedCloud = store.getAgent("grok-cloud")
        assertEquals(listOf("dev", "review", "deliver"), savedCloud?.cloudRoles)
        assertEquals(false, savedCloud?.canDispatch)
        assertEquals("mac", savedCloud?.cloudHomeDeviceId)
        assertEquals(null, savedCloud?.deviceId)
    }

    @Test
    fun `platform record without a target id and cloud record without roles are rejected`() {
        val platform = AgentRecord(
            agentId = "example-agent",
            displayName = "Example Agent",
            kind = AgentKind.CLOUD,
            access = AgentAccess.WEBHOOK,
            brainKind = BrainKind.PLATFORM_AGENT,
            adapterId = "platform-agent",
            baseUrl = "https://example.invalid",
            platformAgentId = null,
        )
        assertTrue(platform.validate().any { it.contains("平台 Agent ID") })
        val cloud = AgentRecord(
            agentId = "grok-cloud",
            displayName = "Grok 云端",
            kind = AgentKind.CLOUD,
            access = AgentAccess.CONNECTOR,
            brainKind = BrainKind.CLOUD_AGENT,
            adapterId = "cloud-agent",
            authRef = "cloud-agent:grok-cloud:mcp-token",
            cloudRoles = emptyList(),
        )
        assertTrue(cloud.validate().any { it.contains("至少要勾一个角色") })
    }

    @Test
    fun `member mirror fields round trip through the store`() {
        val store = InMemoryAgentStore()
        val record = apiRecord().copy(
            memberRole = wherevercode.agent.core.team.MemberRole.DEVELOPER,
            memberConnectionState =
                wherevercode.agent.core.team.MemberConnectionState.NOT_CONNECTED,
            authorizedProjectIds = listOf("proj-a"),
            lastMemberStateUpdatedAtEpochSeconds = 1_700_000_000L,
        )
        store.saveAgent(record)
        val saved = store.getAgent("deepseek-1")
        assertEquals(
            wherevercode.agent.core.team.MemberRole.DEVELOPER,
            saved?.memberRole,
        )
        assertEquals(listOf("proj-a"), saved?.authorizedProjectIds)
        assertEquals(1_700_000_000L, saved?.lastMemberStateUpdatedAtEpochSeconds)
        // 旧 JSON 缺这四个字段时读回默认值（向后兼容），不崩。
        assertTrue(apiRecord().copy(authorizedProjectIds = listOf(" ")).validate()
            .any { it.contains("授权项目 id 不能为空字符串") })
    }
}
