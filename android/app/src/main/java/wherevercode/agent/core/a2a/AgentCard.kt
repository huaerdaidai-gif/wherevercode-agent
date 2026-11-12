package wherevercode.agent.core.a2a

import org.json.JSONObject

/**
 * A2A Agent 卡：V5 对外发布自己的发现文档（最小形态）。
 *
 * 只有名字、能力、端点、认证方式——不含任何秘密；卡片本身是公开文档，
 * 任何第三方 Agent 不用令牌就能取（GET /.well-known/agent.json）。
 * 这只是 A2A 的“形状”：没有注册中心、没有签名卡、没有其它绑定
 * （gRPC 等），也不宣称整套 A2A 规范合规。
 */
data class AgentCard(
    val name: String,
    val description: String,
    val url: String,
    val version: String,
    val streaming: Boolean,
    val pushNotifications: Boolean,
    val skills: List<AgentSkill>,
    val authSchemes: List<String>,
)

data class AgentSkill(
    val id: String,
    val name: String,
    val description: String,
    val tags: List<String>,
)

object AgentCardParser {
    /**
     * 解析一张 Agent 卡。缺 `name` 或 `url` 直接报错——没有名字和地址
     * 的卡发现不了，没法将就着用。
     */
    fun parse(json: JSONObject): AgentCard {
        val name = json.optString("name", "").trim()
        require(name.isNotBlank()) { "Agent 卡缺少 name" }
        val url = json.optString("url", "").trim()
        require(url.isNotBlank()) { "Agent 卡缺少 url" }
        val capabilities = json.optJSONObject("capabilities") ?: JSONObject()
        val skills = mutableListOf<AgentSkill>()
        val skillsJson = json.optJSONArray("skills")
        if (skillsJson != null) {
            for (i in 0 until skillsJson.length()) {
                val skill = skillsJson.optJSONObject(i) ?: continue
                val tags = mutableListOf<String>()
                val tagsJson = skill.optJSONArray("tags")
                if (tagsJson != null) {
                    for (j in 0 until tagsJson.length()) {
                        tags += tagsJson.optString(j, "")
                    }
                }
                skills += AgentSkill(
                    id = skill.optString("id", ""),
                    name = skill.optString("name", ""),
                    description = skill.optString("description", ""),
                    tags = tags.filter { it.isNotBlank() },
                )
            }
        }
        val auth = json.optJSONObject("authentication") ?: JSONObject()
        val schemes = mutableListOf<String>()
        val schemesJson = auth.optJSONArray("schemes")
        if (schemesJson != null) {
            for (i in 0 until schemesJson.length()) {
                schemes += schemesJson.optString(i, "")
            }
        }
        return AgentCard(
            name = name,
            description = json.optString("description", ""),
            url = url,
            version = json.optString("version", ""),
            streaming = capabilities.optBoolean("streaming", false),
            pushNotifications = capabilities.optBoolean("pushNotifications", false),
            skills = skills,
            authSchemes = schemes.filter { it.isNotBlank() },
        )
    }

    fun parse(text: String): AgentCard = parse(JSONObject(text))
}
