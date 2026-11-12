package wherevercode.agent.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebSearchClient: Bing HTML 解析 + 搜索调用（V5 按主流改，maintainer 2026-11-11）。
 *
 * 只用 canned HTML 断言解析逻辑——测试不碰网络（Bing 真实页面结构
 * 2026-11-11 已用 curl 实测：`<li class="b_algo">` + `<a href><h2>` +
 * 第一个 `<p>` 摘要）。网络层由调用方（BrainEngine）用 Result 包着，
 * 失败如实报，不进这里。
 */
class WebSearchClientTest {

    private val cannedHtml: String = """
        <html><body><ol id="b_results">
        <li class="b_algo"><div><a href="https://example.com/a" h="ID=SERP"><h2>第一条 <strong>标题</strong></h2></a></div><div class="b_caption"><p>第一条摘要内容 &amp; 更多</p></div></li>
        <li class="b_algo"><div><a href="https://example.com/b"><h2>第二条标题</h2></a></div><div class="b_caption"><p>第二条摘要</p></div></li>
        <li class="b_algo"><div><a href="/search?q=x"><h2>站内链接</h2></a></div><div class="b_caption"><p>站内摘要</p></div></li>
        <li class="b_algo"><div class="b_caption"><p>没有标题链接的块</p></div></li>
        </ol></body></html>
    """.trimIndent()

    @Test
    fun `parseBingHtml extracts title url snippet`() {
        val hits = WebSearchClient.parseBingHtml(cannedHtml)

        assertEquals(2, hits.size)
        assertEquals("第一条 标题", hits[0].title)
        assertEquals("https://example.com/a", hits[0].url)
        assertEquals("第一条摘要内容 & 更多", hits[0].snippet)
        assertEquals("第二条标题", hits[1].title)
        assertEquals("https://example.com/b", hits[1].url)
    }

    @Test
    fun `parseBingHtml skips internal links and title-less blocks`() {
        val hits = WebSearchClient.parseBingHtml(cannedHtml)

        assertTrue(hits.none { it.url.contains("bing.com") || it.url.startsWith("/") })
        assertTrue(hits.none { it.title == "站内链接" || it.title.isBlank() })
    }

    @Test
    fun `parseBingHtml respects maxResults`() {
        val hits = WebSearchClient.parseBingHtml(cannedHtml, maxResults = 1)

        assertEquals(1, hits.size)
        assertEquals("第一条 标题", hits[0].title)
    }

    @Test
    fun `parseBingHtml returns empty for unparseable pages`() {
        assertTrue(WebSearchClient.parseBingHtml("").isEmpty())
        assertTrue(WebSearchClient.parseBingHtml("<html>bot check</html>").isEmpty())
    }

    @Test
    fun `cleanHtml strips tags and decodes entities`() {
        val cleaned = WebSearchClient.cleanHtml(
            "<strong>你好</strong> &amp; &quot;世界&quot; &#39;再见&#39; &#174;",
        )

        assertEquals("你好 & \"世界\" '再见' ®", cleaned)
    }

    @Test
    fun `formatForModel lists hits with sources`() {
        val result = WebSearchResult(
            query = "今天北京天气",
            hits = listOf(
                WebSearchHit("晴转多云", "https://example.com/a", "最高 25 度"),
                WebSearchHit("空气质量良", "https://example.com/b", ""),
            ),
        )

        val formatted = WebSearchClient.formatForModel(result)

        assertTrue(formatted.contains("今天北京天气"))
        assertTrue(formatted.contains("1. 晴转多云"))
        assertTrue(formatted.contains("最高 25 度"))
        assertTrue(formatted.contains("https://example.com/a"))
        assertTrue(formatted.contains("2. 空气质量良"))
        assertTrue(formatted.contains("注明来源"))
    }
}
