package wherevercode.agent.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.brain.BrainKind

/**
 * Agent 连接测试（阶段 3，A1 P1）：全部用假探针，不发真实请求。
 *
 * 诚实分级是本文件的核心断言：/models 200 才是已验证；CLI 的
 * --version 成功只证明可运行，登录态恒 UNKNOWN；订阅授权无法
 * 自动探测。
 */
class AgentConnectionTesterTest {

    private fun apiRecord(
        baseUrl: String? = "https://api.deepseek.com",
        authRef: String? = "key:deepseek-1",
        model: String = "deepseek-flash",
    ) = AgentRecord(
        agentId = "deepseek-1",
        displayName = "DeepSeek 闪电",
        kind = AgentKind.CLOUD,
        access = AgentAccess.API,
        brainKind = BrainKind.MODEL_API,
        adapterId = "deepseek",
        baseUrl = baseUrl,
        model = model,
        authRef = authRef,
    )

    private fun cliRecord(binary: String? = "codebuddy") = AgentRecord(
        agentId = "codebuddy-cli",
        displayName = "CodeBuddy",
        kind = AgentKind.DEVICE,
        access = AgentAccess.CLI,
        brainKind = BrainKind.AGENT_RUNTIME,
        adapterId = "codebuddy",
        binary = binary,
    )

    private fun subscriptionRecord() = AgentRecord(
        agentId = "chatgpt-sub",
        displayName = "ChatGPT 会员",
        kind = AgentKind.CLOUD,
        access = AgentAccess.APP,
        brainKind = BrainKind.SUBSCRIPTION,
        adapterId = "subscription",
    )

    @Test
    fun `platform agent channel readable is UNKNOWN not LOGGED_IN`() {
        // 频道可读只证明凭证有效、频道已加入，不证明对方在线或会回复，
        // 所以登录态仍为 UNKNOWN（需一次真实往返确认，与 CLI 同口径）。
        val tester = AgentConnectionTester(
            versionRunner = { null },
            platformProbe = { PlatformProbeResult.ChannelReadable },
        )
        val result = tester.test(
            AgentRecord(
                agentId = "example-agent",
                displayName = "Example Agent",
                kind = AgentKind.CLOUD,
                access = AgentAccess.WEBHOOK,
                brainKind = BrainKind.PLATFORM_AGENT,
                adapterId = "platform-agent",
                baseUrl = "https://example.invalid",
                platformAgentId = "example-agent",
            ),
        )
        assertEquals(AgentLoginState.UNKNOWN, result.loginState)
        assertTrue(result.summary.contains("平台通道可读"))
        assertTrue(result.summary.contains("不证明"))
    }

    @Test
    fun `platform agent unauthorized is NOT_LOGGED_IN`() {
        val tester = AgentConnectionTester(
            versionRunner = { null },
            platformProbe = { PlatformProbeResult.Unauthorized },
        )
        val result = tester.test(
            AgentRecord(
                agentId = "example-agent",
                displayName = "Example Agent",
                kind = AgentKind.CLOUD,
                access = AgentAccess.WEBHOOK,
                brainKind = BrainKind.PLATFORM_AGENT,
                adapterId = "platform-agent",
                baseUrl = "https://example.invalid",
                platformAgentId = "example-agent",
            ),
        )
        assertEquals(AgentLoginState.NOT_LOGGED_IN, result.loginState)
        assertTrue(result.summary.contains("凭证被拒绝"))
    }

    @Test
    fun `platform agent without a wired probe says so honestly`() {
        val tester = AgentConnectionTester(versionRunner = { null })
        val result = tester.test(
            AgentRecord(
                agentId = "example-agent",
                displayName = "Example Agent",
                kind = AgentKind.CLOUD,
                access = AgentAccess.WEBHOOK,
                brainKind = BrainKind.PLATFORM_AGENT,
                adapterId = "platform-agent",
                baseUrl = "https://example.invalid",
                platformAgentId = "example-agent",
            ),
        )
        assertEquals(AgentLoginState.UNKNOWN, result.loginState)
        assertTrue(result.summary.contains("未接线"))
    }

    @Test
    fun `cloud agent test says the mcp endpoint is not open yet`() {
        val tester = AgentConnectionTester(versionRunner = { null })
        val result = tester.test(
            AgentRecord(
                agentId = "grok-cloud",
                displayName = "Grok 云端",
                kind = AgentKind.CLOUD,
                access = AgentAccess.CONNECTOR,
                brainKind = BrainKind.CLOUD_AGENT,
                adapterId = "cloud-agent",
                authRef = "cloud-agent:grok-cloud:mcp-token",
                cloudRoles = listOf("dev", "review"),
            ),
        )
        assertEquals(AgentLoginState.UNKNOWN, result.loginState)
        assertTrue(result.summary.contains("MCP 端点尚未开通"))
    }

    @Test
    fun `models 200 with count is LOGGED_IN`() {
        val tester = AgentConnectionTester(
            modelsProbe = { _, _ -> ModelsProbeResult.Ok(3) },
            versionRunner = { null },
        )
        val result = tester.test(apiRecord(), secretResolver = { "secret-value" })
        assertEquals(AgentLoginState.LOGGED_IN, result.loginState)
        assertTrue(result.summary.contains("3 个模型"))
        assertTrue(result.testedAtEpochSeconds > 0)
    }

    @Test
    fun `models 200 unparsable still LOGGED_IN`() {
        val tester = AgentConnectionTester(
            modelsProbe = { _, _ -> ModelsProbeResult.Ok(null) },
            versionRunner = { null },
        )
        val result = tester.test(apiRecord(), secretResolver = { "secret-value" })
        assertEquals(AgentLoginState.LOGGED_IN, result.loginState)
        assertTrue(result.summary.contains("无法解析"))
    }

    @Test
    fun `models 401 is NOT_LOGGED_IN`() {
        val tester = AgentConnectionTester(
            modelsProbe = { _, _ -> ModelsProbeResult.Unauthorized },
            versionRunner = { null },
        )
        val result = tester.test(apiRecord(), secretResolver = { "bad-secret" })
        assertEquals(AgentLoginState.NOT_LOGGED_IN, result.loginState)
        assertTrue(result.summary.contains("401/403"))
    }

    @Test
    fun `models 500 is UNKNOWN not NOT_LOGGED_IN`() {
        val tester = AgentConnectionTester(
            modelsProbe = { _, _ -> ModelsProbeResult.HttpError(500, "Internal Server Error") },
            versionRunner = { null },
        )
        val result = tester.test(apiRecord(), secretResolver = { "secret-value" })
        assertEquals(AgentLoginState.UNKNOWN, result.loginState)
        assertTrue(result.summary.contains("500"))
    }

    @Test
    fun `network error is UNKNOWN`() {
        val tester = AgentConnectionTester(
            modelsProbe = { _, _ -> ModelsProbeResult.NetworkError("timeout") },
            versionRunner = { null },
        )
        val result = tester.test(apiRecord(), secretResolver = { "secret-value" })
        assertEquals(AgentLoginState.UNKNOWN, result.loginState)
        assertTrue(result.summary.contains("timeout"))
    }

    @Test
    fun `missing baseUrl is UNKNOWN`() {
        val tester = AgentConnectionTester(
            modelsProbe = { _, _ -> ModelsProbeResult.Ok(1) },
            versionRunner = { null },
        )
        val result = tester.test(apiRecord(baseUrl = null), secretResolver = { "secret-value" })
        assertEquals(AgentLoginState.UNKNOWN, result.loginState)
        assertTrue(result.summary.contains("未填服务地址"))
    }

    @Test
    fun `authRef with no stored secret is UNKNOWN`() {
        val tester = AgentConnectionTester(
            modelsProbe = { _, _ -> ModelsProbeResult.Ok(1) },
            versionRunner = { null },
        )
        val result = tester.test(apiRecord(), secretResolver = { null })
        assertEquals(AgentLoginState.UNKNOWN, result.loginState)
        assertTrue(result.summary.contains("没有值"))
    }

    @Test
    fun `local model url probes without a secret`() {
        var seenSecret: String? = "not-called"
        val tester = AgentConnectionTester(
            modelsProbe = { _, secret -> seenSecret = secret; ModelsProbeResult.Ok(2) },
            versionRunner = { null },
        )
        val local = apiRecord(baseUrl = "http://127.0.0.1:8080", authRef = "key:local-1")
        val result = tester.test(local, secretResolver = { "must-not-be-used" })
        assertEquals(AgentLoginState.LOGGED_IN, result.loginState)
        assertNull("本地模型不传密钥", seenSecret)
    }

    @Test
    fun `cli version success proves runnable only login stays UNKNOWN`() {
        val tester = AgentConnectionTester(
            modelsProbe = { _, _ -> ModelsProbeResult.Ok(1) },
            versionRunner = { "codebuddy 1.2.3" },
        )
        val result = tester.test(cliRecord())
        assertEquals(AgentLoginState.UNKNOWN, result.loginState)
        assertTrue(result.summary.contains("登录状态仍未验证"))
    }

    @Test
    fun `cli binary missing is UNKNOWN`() {
        val tester = AgentConnectionTester(
            modelsProbe = { _, _ -> ModelsProbeResult.Ok(1) },
            versionRunner = { null },
        )
        val result = tester.test(cliRecord())
        assertEquals(AgentLoginState.UNKNOWN, result.loginState)
        assertTrue(result.summary.contains("不可运行"))
    }

    @Test
    fun `cli missing binary name is UNKNOWN`() {
        val tester = AgentConnectionTester(
            modelsProbe = { _, _ -> ModelsProbeResult.Ok(1) },
            versionRunner = { null },
        )
        val result = tester.test(cliRecord(binary = null))
        assertEquals(AgentLoginState.UNKNOWN, result.loginState)
        assertTrue(result.summary.contains("未填 CLI 二进制名"))
    }

    @Test
    fun `subscription cannot be auto probed`() {
        val tester = AgentConnectionTester(
            modelsProbe = { _, _ -> ModelsProbeResult.Ok(1) },
            versionRunner = { null },
        )
        val result = tester.test(subscriptionRecord())
        assertEquals(AgentLoginState.UNKNOWN, result.loginState)
        assertTrue(result.summary.contains("无法自动探测"))
    }

    @Test
    fun `subscription cli-membership brain cannot be auto probed either`() {
        // Boundary (C3-D): the connection tester has no real-run probe, so
        // even the CLI membership brain stays UNKNOWN here. Its only probe
        // is CliMembershipBrainAdapter.validateCredentials(), which runs
        // one real CLI call when the user taps "验证一次".
        val tester = AgentConnectionTester(
            modelsProbe = { _, _ -> ModelsProbeResult.Ok(1) },
            versionRunner = { "codebuddy 1.2.3" },
        )
        val record = AgentRecord(
            agentId = "cli-membership",
            displayName = "CLI 会员额度",
            kind = AgentKind.DEVICE,
            access = AgentAccess.CLI,
            brainKind = BrainKind.SUBSCRIPTION,
            adapterId = "cli-membership",
            binary = "codebuddy",
        )
        val result = tester.test(record)
        assertEquals(AgentLoginState.UNKNOWN, result.loginState)
        assertTrue(result.summary.contains("无法自动探测"))
    }

    @Test
    fun `testAndUpdate writes UNKNOWN back for a CLI record`() {
        val tester = AgentConnectionTester(
            modelsProbe = { _, _ -> ModelsProbeResult.Ok(1) },
            versionRunner = { "codebuddy 1.2.3" },
        )
        val updated = tester.testAndUpdate(cliRecord())
        assertEquals(AgentLoginState.UNKNOWN, updated.loginState)
        assertTrue(updated.lastTestSummary?.contains("登录状态仍未验证") == true)
        assertTrue((updated.lastTestedAtEpochSeconds ?: 0) > 0)
    }

    @Test
    fun `testAndUpdate writes state summary and timestamp back`() {
        val tester = AgentConnectionTester(
            modelsProbe = { _, _ -> ModelsProbeResult.Ok(5) },
            versionRunner = { null },
        )
        val updated = tester.testAndUpdate(apiRecord(), secretResolver = { "secret-value" })
        assertEquals(AgentLoginState.LOGGED_IN, updated.loginState)
        assertTrue(updated.lastTestSummary?.contains("5 个模型") == true)
        assertTrue((updated.lastTestedAtEpochSeconds ?: 0) > 0)
        // 原记录不被改写（copy 语义）。
        assertEquals(AgentLoginState.UNKNOWN, apiRecord().loginState)
        assertNull(apiRecord().lastTestSummary)
    }

    @Test
    fun `modelsUrl rules match DeepSeekApiClient`() {
        assertEquals("https://api.deepseek.com/models", modelsUrl("https://api.deepseek.com"))
        assertEquals("https://api.deepseek.com/models", modelsUrl("https://api.deepseek.com/models"))
        assertEquals(
            "https://api.deepseek.com/models",
            modelsUrl("https://api.deepseek.com/chat/completions"),
        )
        assertEquals("https://api.deepseek.com/models", modelsUrl("https://api.deepseek.com/"))
    }

    // ---- Manus 404 修复（V5 Phase B C3，GPT 8d8ed67e）----
    // Manus 是任务型 API，没有 /models 端点；旧探针 GET {baseUrl}/models
    // 在 api.manus.ai 上恒为 404。Manus 记录改走 GET /v2/user.me +
    // x-manus-api-key 只读验证；旧的 /models 探针在 Manus 记录上不再被调用。

    private fun manusRecord(
        baseUrl: String? = "https://api.manus.ai",
        adapterId: String = "manus",
        model: String = "Manus 2.0 Lite",
        authRef: String? = "key:manus-1",
    ) = AgentRecord(
        agentId = "manus-1",
        displayName = "Manus",
        kind = AgentKind.CLOUD,
        access = AgentAccess.API,
        brainKind = BrainKind.MODEL_API,
        adapterId = adapterId,
        baseUrl = baseUrl,
        model = model,
        authRef = authRef,
    )

    private fun manusTester(
        modelsProbe: (String, String?) -> ModelsProbeResult = { _, _ ->
            throw AssertionError("Manus 记录绝不能调用 /models 探针")
        },
        manusProbe: (String, String?) -> ManusResult<ManusAuthInfo> = { _, _ ->
            ManusResult.Success(ManusAuthInfo("u_123", null))
        },
    ) = AgentConnectionTester(
        modelsProbe = modelsProbe,
        manusProbe = manusProbe,
        versionRunner = { null },
    )

    @Test
    fun `manus record does not call models probe`() {
        val result = manusTester().test(manusRecord(), secretResolver = { "secret-value" })
        assertEquals(AgentLoginState.LOGGED_IN, result.loginState)
        assertTrue(result.summary.contains("认证通过"))
        // 成功只写“认证通过”，并明确写“未验证可执行”，不冒充可执行已验证。
        assertTrue(result.summary.contains("未验证可执行"))
        assertFalse(result.summary.contains("可执行已验证"))
    }

    @Test
    fun `manus record with v2 baseUrl also routes to manus probe`() {
        val result = manusTester().test(
            manusRecord(baseUrl = "https://api.manus.ai/v2"),
            secretResolver = { "secret-value" },
        )
        assertEquals(AgentLoginState.LOGGED_IN, result.loginState)
        assertTrue(result.summary.contains("认证通过"))
    }

    @Test
    fun `manus record detected by host even with deepseek adapterId`() {
        // 旧记录：用户按通用表单添加，adapterId 仍是 deepseek、model 仍是
        // "Manus 2.0 Lite"，只要 baseUrl 主机是 api.manus.ai 就改走 Manus 探针。
        val result = manusTester().test(
            manusRecord(adapterId = "deepseek"),
            secretResolver = { "secret-value" },
        )
        assertEquals(AgentLoginState.LOGGED_IN, result.loginState)
    }

    @Test
    fun `manus 401 is NOT_LOGGED_IN`() {
        val tester = manusTester(
            manusProbe = { _, _ -> ManusResult.Failure(ManusError.Unauthorized) },
        )
        val result = tester.test(manusRecord(), secretResolver = { "bad-secret" })
        assertEquals(AgentLoginState.NOT_LOGGED_IN, result.loginState)
        assertTrue(result.summary.contains("401"))
    }

    @Test
    fun `manus 403 is NOT_LOGGED_IN`() {
        val tester = manusTester(
            manusProbe = { _, _ -> ManusResult.Failure(ManusError.Forbidden) },
        )
        val result = tester.test(manusRecord(), secretResolver = { "secret-value" })
        assertEquals(AgentLoginState.NOT_LOGGED_IN, result.loginState)
        assertTrue(result.summary.contains("403"))
    }

    @Test
    fun `manus 404 is UNKNOWN mentioning user_me`() {
        val tester = manusTester(
            manusProbe = { _, _ -> ManusResult.Failure(ManusError.NotFound) },
        )
        val result = tester.test(manusRecord(), secretResolver = { "secret-value" })
        assertEquals(AgentLoginState.UNKNOWN, result.loginState)
        assertTrue(result.summary.contains("404"))
        assertTrue(result.summary.contains("/v2/user.me"))
    }

    @Test
    fun `manus 429 is UNKNOWN mentioning rate limit`() {
        val tester = manusTester(
            manusProbe = { _, _ -> ManusResult.Failure(ManusError.RateLimited) },
        )
        val result = tester.test(manusRecord(), secretResolver = { "secret-value" })
        assertEquals(AgentLoginState.UNKNOWN, result.loginState)
        assertTrue(result.summary.contains("429"))
    }

    @Test
    fun `manus network error is UNKNOWN`() {
        val tester = manusTester(
            manusProbe = { _, _ -> ManusResult.Failure(ManusError.NetworkError("timeout")) },
        )
        val result = tester.test(manusRecord(), secretResolver = { "secret-value" })
        assertEquals(AgentLoginState.UNKNOWN, result.loginState)
        assertTrue(result.summary.contains("timeout"))
    }

    @Test
    fun `manus record without stored secret does not call probe`() {
        val tester = manusTester(
            manusProbe = { _, _ -> throw AssertionError("无密钥时不应调用探针") },
        )
        val result = tester.test(manusRecord(), secretResolver = { null })
        assertEquals(AgentLoginState.UNKNOWN, result.loginState)
        assertTrue(result.summary.contains("没有值"))
    }

    @Test
    fun `non-manus record still uses models probe`() {
        var modelsCalled = false
        val tester = AgentConnectionTester(
            modelsProbe = { _, _ -> modelsCalled = true; ModelsProbeResult.Ok(3) },
            manusProbe = { _, _ -> throw AssertionError("非 Manus 记录不应调用 Manus 探针") },
            versionRunner = { null },
        )
        val result = tester.test(apiRecord(), secretResolver = { "secret-value" })
        assertEquals(AgentLoginState.LOGGED_IN, result.loginState)
        assertTrue(modelsCalled)
    }

    @Test
    fun `manus testAndUpdate writes auth passed back`() {
        val updated = manusTester().testAndUpdate(manusRecord(), secretResolver = { "secret-value" })
        assertEquals(AgentLoginState.LOGGED_IN, updated.loginState)
        assertTrue(updated.lastTestSummary?.contains("认证通过") == true)
        assertTrue((updated.lastTestedAtEpochSeconds ?: 0) > 0)
    }

    // ---- 远端 DEVICE Agent 测试修复（V5，2026-11-11）----
    // 根因：test() 只按 brainKind 分派，AGENT_RUNTIME 一律走本机
    // `<binary> --version` 沙箱探针。远端 DEVICE Agent（deviceId
    // 非空，如 VPS 上的 Example Agent）在手机沙箱里根本没有二进制，
    // 于是每个远端 Agent 都假失败（2026-11-11 真机：
    // “二进制不可运行：沙箱内未找到或 --version 无输出”）。
    // 修复：kind == DEVICE 且 deviceId 非空的记录改走 deviceProbe
    //（问设备上的 Hand hello，不问本机沙箱）；本机 CLI（deviceId
    // 为 null）仍走本地探针，不变。

    /** 真机 example-agent 记录的形状（K70 wherevercode_agents.xml）。 */
    private fun deviceRecord(deviceId: String? = "remote-device") = AgentRecord(
        agentId = "example-agent",
        displayName = "Example Agent",
        kind = AgentKind.DEVICE,
        access = AgentAccess.CLI,
        brainKind = BrainKind.AGENT_RUNTIME,
        adapterId = "example-agent",
        binary = "example-agent",
        deviceId = deviceId,
        deviceFolder = "/workspace/example-project",
    )

    private fun deviceTester(
        deviceProbe: (AgentRecord) -> DeviceProbeResult = { _ ->
            DeviceProbeResult.Available("remote-host", "Example Agent", "/workspace/example-project")
        },
        versionRunner: (String) -> String? = { _ ->
            throw AssertionError("远端 Agent 绝不能调用本机 --version 探针")
        },
    ) = AgentConnectionTester(
        modelsProbe = { _, _ -> throw AssertionError("DEVICE 记录不应调用 /models 探针") },
        versionRunner = versionRunner,
        deviceProbe = deviceProbe,
    )

    @Test
    fun `device agent with available remote probe does not consult local sandbox`() {
        val result = deviceTester().test(deviceRecord())
        assertEquals(AgentLoginState.UNKNOWN, result.loginState)
        assertTrue(result.summary.contains("远端可用"))
        assertTrue(result.summary.contains("remote-host"))
        assertFalse(result.summary.contains("沙箱"))
        assertFalse(result.summary.contains("不可运行"))
    }

    @Test
    fun `device agent unreachable reports hand error not sandbox message`() {
        val tester = deviceTester(
            deviceProbe = { _ ->
                DeviceProbeResult.Unreachable("设备 remote-host 未配对：请先在设备页完成配对，配对后才能测试。")
            },
        )
        val result = tester.test(deviceRecord())
        assertEquals(AgentLoginState.UNKNOWN, result.loginState)
        assertTrue(result.summary.contains("未配对"))
        assertFalse(result.summary.contains("沙箱"))
        assertFalse(result.summary.contains("不可运行"))
    }

    @Test
    fun `device agent missing from hello list`() {
        val tester = deviceTester(
            deviceProbe = { _ -> DeviceProbeResult.AgentMissing("remote-host") },
        )
        val result = tester.test(deviceRecord())
        assertEquals(AgentLoginState.UNKNOWN, result.loginState)
        assertTrue(result.summary.contains("远端无此 Agent"))
        assertFalse(result.summary.contains("沙箱"))
    }

    @Test
    fun `device agent unavailable on remote`() {
        val tester = deviceTester(
            deviceProbe = { _ ->
                DeviceProbeResult.AgentUnavailable("remote-host", "/workspace/example-project")
            },
        )
        val result = tester.test(deviceRecord())
        assertEquals(AgentLoginState.UNKNOWN, result.loginState)
        assertTrue(result.summary.contains("不可用"))
        assertFalse(result.summary.contains("沙箱"))
    }

    @Test
    fun `device agent pending approval is not reported available`() {
        // V5 修复（2026-11-11，hello 通、run 就 reset）：hello 通但
        // 远端尚未批准此手机（PENDING）时，探测绝不能报“远端可用”，
        // 必须单独报“尚未批准”，否则用户一发消息就 reset。
        val tester = deviceTester(
            deviceProbe = { _ -> DeviceProbeResult.PendingApproval("remote-host") },
        )
        val result = tester.test(deviceRecord())
        assertEquals(AgentLoginState.UNKNOWN, result.loginState)
        assertTrue(result.summary.contains("尚未批准"))
        assertTrue(result.summary.contains("PENDING"))
        assertFalse(result.summary.contains("远端可用"))
        assertFalse(result.summary.contains("沙箱"))
    }

    @Test
    fun `device agent with no probe wired reports not wired never sandbox`() {
        val tester = AgentConnectionTester(
            modelsProbe = { _, _ -> throw AssertionError("DEVICE 记录不应调用 /models 探针") },
            versionRunner = { _ ->
                throw AssertionError("远端 Agent 绝不能调用本机 --version 探针")
            },
            deviceProbe = null,
        )
        val result = tester.test(deviceRecord())
        assertEquals(AgentLoginState.UNKNOWN, result.loginState)
        assertTrue(result.summary.contains("未接线"))
        assertFalse(result.summary.contains("沙箱"))
        assertFalse(result.summary.contains("不可运行"))
    }

    @Test
    fun `device agent with null deviceId still uses local cli probe`() {
        // 本机 CLI 的 DEVICE 记录（deviceId 为 null）不受影响：仍走本地探针。
        val tester = AgentConnectionTester(
            modelsProbe = { _, _ -> ModelsProbeResult.Ok(1) },
            versionRunner = { "codebuddy 1.2.3" },
            deviceProbe = { _ -> throw AssertionError("本机 CLI 不应调用远端探针") },
        )
        val result = tester.test(deviceRecord(deviceId = null))
        assertEquals(AgentLoginState.UNKNOWN, result.loginState)
        assertTrue(result.summary.contains("登录状态仍未验证"))
    }

    @Test
    fun `testAndUpdate writes device available back`() {
        val updated = deviceTester().testAndUpdate(deviceRecord())
        assertEquals(AgentLoginState.UNKNOWN, updated.loginState)
        assertTrue(updated.lastTestSummary?.contains("远端可用") == true)
        assertTrue((updated.lastTestedAtEpochSeconds ?: 0) > 0)
    }

    @Test
    fun `device agent already verified stays verified on retest`() {
        // V5 修复（2026-11-11，能用的 Agent 显示“未验证”）：记录已是
        // LOGGED_IN（一次真实往返成功后标上的）时，hello 亦确认执行入口
        // 可用，本次测试只确认、绝不把已验证降回 UNKNOWN——否则点一次
        // “测试”就把刚回过话的 Agent 打回“未验证”。
        val result = deviceTester().test(deviceRecord().copy(loginState = AgentLoginState.LOGGED_IN))
        assertEquals(AgentLoginState.LOGGED_IN, result.loginState)
        assertTrue(result.summary.contains("认证通过"))
    }

    @Test
    fun `testAndUpdate keeps verified device agent verified`() {
        val updated = deviceTester().testAndUpdate(deviceRecord().copy(loginState = AgentLoginState.LOGGED_IN))
        assertEquals(AgentLoginState.LOGGED_IN, updated.loginState)
        assertTrue(updated.lastTestSummary?.contains("认证通过") == true)
        assertTrue((updated.lastTestedAtEpochSeconds ?: 0) > 0)
    }
}
