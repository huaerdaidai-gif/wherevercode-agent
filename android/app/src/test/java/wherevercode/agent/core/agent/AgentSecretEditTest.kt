package wherevercode.agent.core.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 添加/编辑 Agent 表单的密钥处理（Manus 404 修复，GPT 8d8ed67e）：
 * 编辑时密钥值留空 = 保留旧 Key，不可清空、不可写空值进 Keystore。
 * 只有填了非空值时才写入新 Key。
 */
class AgentSecretEditTest {

    @Test
    fun `blank secret input keeps old key`() {
        assertFalse(AgentSecretEdit.shouldWriteSecret(""))
        assertFalse(AgentSecretEdit.shouldWriteSecret("   "))
    }

    @Test
    fun `non-blank secret input writes new key`() {
        assertTrue(AgentSecretEdit.shouldWriteSecret("new-key-value"))
        assertTrue(AgentSecretEdit.shouldWriteSecret("  new-key-value  "))
    }
}
