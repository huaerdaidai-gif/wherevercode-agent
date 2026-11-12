package wherevercode.agent.core.agent

import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 平台令牌签发（V5，2026-11-12，maintainer：App 没有 token 生成按钮）。
 * 纯逻辑测试：假 tools/call，不发真实调用、不碰 Keystore、不碰网络。
 * 令牌值只出现在 Issued 里；失败消息里绝不能出现令牌值。
 */
class PlatformTokenIssuerTest {

    private fun issuedJson(token: String = "v5_test-token-not-real"): JSONObject =
        JSONObject().apply {
            put("token", token)
            put("token_id", "v5tok_test")
            put("agent_id", "chatgpt")
            put("name", "App 生成")
        }

    @Test
    fun `issues a token for the given agent and returns it once`() {
        var calledName = ""
        var calledArgs: JSONObject? = null
        val issuer = PlatformTokenIssuer { name, args ->
            calledName = name
            calledArgs = args
            issuedJson()
        }
        val result = issuer.issueToken("chatgpt", "ChatGPT 登陆")

        assertTrue(result is IssueTokenResult.Issued)
        val issued = result as IssueTokenResult.Issued
        assertEquals("v5_test-token-not-real", issued.token)
        assertEquals("v5tok_test", issued.tokenId)
        assertEquals("chatgpt", issued.agentId)
        assertEquals("App 生成", issued.name)
        assertEquals("issue_agent_token", calledName)
        assertEquals("chatgpt", calledArgs?.optString("agent_id"))
        assertEquals("ChatGPT 登陆", calledArgs?.optString("name"))
    }

    @Test
    fun `blank agent id is rejected without calling the platform`() {
        var called = false
        val issuer = PlatformTokenIssuer { _, _ ->
            called = true
            issuedJson()
        }
        val result = issuer.issueToken("   ", "App 生成")

        assertTrue(result is IssueTokenResult.Failed)
        assertTrue(!called)
        assertTrue((result as IssueTokenResult.Failed).message.contains("Agent ID"))
    }

    @Test
    fun `agent id with illegal characters is rejected`() {
        var called = false
        val issuer = PlatformTokenIssuer { _, _ ->
            called = true
            issuedJson()
        }
        val result = issuer.issueToken("chat gpt!", "App 生成")

        assertTrue(result is IssueTokenResult.Failed)
        assertTrue(!called)
    }

    @Test
    fun `blank name is rejected without calling the platform`() {
        var called = false
        val issuer = PlatformTokenIssuer { _, _ ->
            called = true
            issuedJson()
        }
        val result = issuer.issueToken("chatgpt", "   ")

        assertTrue(result is IssueTokenResult.Failed)
        assertTrue(!called)
    }

    @Test
    fun `name over 100 chars is rejected`() {
        var called = false
        val issuer = PlatformTokenIssuer { _, _ ->
            called = true
            issuedJson()
        }
        val result = issuer.issueToken("chatgpt", "名".repeat(101))

        assertTrue(result is IssueTokenResult.Failed)
        assertTrue(!called)
    }

    @Test
    fun `missing platform credential maps to the not-connected hint`() {
        val issuer = PlatformTokenIssuer { _, _ ->
            throw IOException("未配置平台 Agent 凭证。")
        }
        val result = issuer.issueToken("chatgpt", "App 生成")

        assertTrue(result is IssueTokenResult.Failed)
        val message = (result as IssueTokenResult.Failed).message
        assertTrue(message.contains("未连接平台"))
        assertTrue(!message.contains("v5_"))
    }

    @Test
    fun `unknown tool maps to the endpoint-or-owner hint`() {
        val issuer = PlatformTokenIssuer { _, _ ->
            throw IOException("请求失败：未知工具")
        }
        val result = issuer.issueToken("chatgpt", "App 生成")

        assertTrue(result is IssueTokenResult.Failed)
        val message = (result as IssueTokenResult.Failed).message
        // 服务端把“非 owner”和“工具不存在”都回成未知工具，两者都写进提示。
        assertTrue(message.contains("不支持签发令牌"))
        assertTrue(message.contains("owner"))
    }

    @Test
    fun `unknown agent error is passed through`() {
        val issuer = PlatformTokenIssuer { _, _ ->
            throw IOException("Agent 不存在或已停用")
        }
        val result = issuer.issueToken("no-such-agent", "App 生成")

        assertTrue(result is IssueTokenResult.Failed)
        assertTrue((result as IssueTokenResult.Failed).message.contains("不存在"))
    }

    @Test
    fun `response without a token is a failure`() {
        val issuer = PlatformTokenIssuer { _, _ -> JSONObject() }
        val result = issuer.issueToken("chatgpt", "App 生成")

        assertTrue(result is IssueTokenResult.Failed)
        assertTrue((result as IssueTokenResult.Failed).message.contains("没有返回令牌"))
    }

    @Test
    fun `inputs are trimmed before the call`() {
        var calledArgs: JSONObject? = null
        val issuer = PlatformTokenIssuer { _, args ->
            calledArgs = args
            issuedJson()
        }
        issuer.issueToken("  chatgpt  ", "  App 生成  ")

        assertEquals("chatgpt", calledArgs?.optString("agent_id"))
        assertEquals("App 生成", calledArgs?.optString("name"))
    }
}
