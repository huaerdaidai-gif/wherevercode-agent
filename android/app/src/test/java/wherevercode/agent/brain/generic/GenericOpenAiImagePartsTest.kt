package wherevercode.agent.brain.generic

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.brain.ChatImage
import wherevercode.agent.brain.ChatMessage
import wherevercode.agent.brain.ChatRequest

/**
 * OpenAI 兼容请求的图片 parts（V5 按主流改，maintainer 2026-11-11）：
 * 带图片的 user 消息在 wire 上是 content parts 数组
 * `[{type:text,…},{type:image_url,…}]`（OpenAI / xAI 同款格式）；
 * 没图片的消息保持原来的纯字符串 content，一字不动。
 */
class GenericOpenAiImagePartsTest {

    @Test
    fun `message with images becomes content parts array`() {
        val request = ChatRequest(
            messages = listOf(
                ChatMessage(
                    role = "user",
                    content = "这张图里有什么",
                    images = listOf(ChatImage("data:image/jpeg;base64,AAA")),
                ),
            ),
            model = "grok-4.7",
        )

        val body = GenericOpenAiApiClient.buildGenericRequestBody(request)
        val messages = body.getJSONArray("messages")
        val content = messages.getJSONObject(0).get("content")

        assertTrue(content is JSONArray)
        val parts = content as JSONArray
        assertEquals(2, parts.length())
        assertEquals("text", parts.getJSONObject(0).getString("type"))
        assertEquals("这张图里有什么", parts.getJSONObject(0).getString("text"))
        assertEquals("image_url", parts.getJSONObject(1).getString("type"))
        assertEquals(
            "data:image/jpeg;base64,AAA",
            parts.getJSONObject(1).getJSONObject("image_url").getString("url"),
        )
    }

    @Test
    fun `message without images keeps plain string content`() {
        val request = ChatRequest(
            messages = listOf(ChatMessage(role = "user", content = "在吗")),
            model = "grok-4.7",
        )

        val body = GenericOpenAiApiClient.buildGenericRequestBody(request)
        val messages = body.getJSONArray("messages")

        assertEquals("在吗", messages.getJSONObject(0).getString("content"))
    }
}
