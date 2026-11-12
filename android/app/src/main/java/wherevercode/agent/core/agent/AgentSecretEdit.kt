package wherevercode.agent.core.agent

/**
 * 添加/编辑 Agent 表单的密钥处理（Manus 404 修复，GPT 8d8ed67e）：
 * 密钥输入框留空 = 保留旧 Key，绝不清空、绝不写空值进 Keystore；
 * 只有填了非空值时才写入新 Key。调用方（MainActivity.showAgentForm）
 * 只在 [shouldWriteSecret] 为真时调 BrainKeyStore.putSecret。
 */
object AgentSecretEdit {
    fun shouldWriteSecret(secretInput: String): Boolean = secretInput.isNotBlank()
}
