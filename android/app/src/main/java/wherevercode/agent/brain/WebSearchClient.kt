package wherevercode.agent.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 网页搜索（V5 按主流改，maintainer 2026-11-11）：“+”菜单里的网页搜索开关。
 *
 * 主流做法对照：ChatGPT 的搜索开关开了之后，回答前先联网查，再带着
 * 搜索结果回答。V5 没有搜索 API Key 可用，也没有自建搜索服务——
 * 所以走 Bing 网页搜索（`bing.com/search`，无 Key 可直接读），
 * 结果是真实的网页搜索结果，不是假数据。
 *
 * 诚实边界（写进代码里，不只写注释）：
 * - DuckDuckGo Lite / html 端点从本机网络实测返回 bot 检查页（202），
 *   DDG Instant Answer API 对实时问题基本返回空——这两个源不用；
 * - Bing HTML 是 2026-11-11 实测可用的源（200 + 正常结果块），所以用它；
 * - Bing 是 HTML 抓取，没有官方搜索 API 的稳定性保证：解析不到结果时
 *   如实报“搜到了页面但没解析出结果”，不编结果；网络失败如实报原因；
 * - 搜索结果只以 system 消息形式进本轮上下文，不持久化进对话历史；
 * - 搜索失败时本轮照常回答，但要明说“没用上搜索结果”。
 */
data class WebSearchHit(
    val title: String,
    val url: String,
    val snippet: String,
)

data class WebSearchResult(
    val query: String,
    val hits: List<WebSearchHit>,
    val source: String = "Bing",
)

class WebSearchClient(
    private val fetcher: (String) -> String = ::defaultFetch,
    private val maxResults: Int = DEFAULT_MAX_RESULTS,
) {
    companion object {
        const val DEFAULT_MAX_RESULTS: Int = 5
        const val SEARCH_TIMEOUT_MILLIS: Int = 10_000

        private val USER_AGENT: String =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /** 默认抓取：Bing 网页搜索（无 Key）。调用方在 IO 线程调。 */
        fun defaultFetch(query: String): String {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://www.bing.com/search?q=$encoded&count=10&mkt=zh-CN")
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                connection.connectTimeout = SEARCH_TIMEOUT_MILLIS
                connection.readTimeout = SEARCH_TIMEOUT_MILLIS
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    throw IOException("Bing 搜索返回 HTTP $code")
                }
                return connection.inputStream.bufferedReader(Charsets.UTF_8).readText()
            } finally {
                connection.disconnect()
            }
        }

        /**
         * 解析 Bing 搜索结果页：每个 `<li class="b_algo">` 是一条结果，
         * 标题在 `<a href="…"><h2>…</h2></a>`，摘要在第一个 `<p>`。
         * 纯函数，可单测；解析不到就返回空列表（调用方如实报，不编）。
         */
        fun parseBingHtml(html: String, maxResults: Int = DEFAULT_MAX_RESULTS): List<WebSearchHit> {
            val hits = mutableListOf<WebSearchHit>()
            val blocks = html.split("<li class=\"b_algo\"").drop(1)
            for (block in blocks) {
                if (hits.size >= maxResults) break
                val link = LINK_PATTERN.find(block) ?: continue
                val url = link.groupValues[1].trim()
                if (url.isBlank() || url.startsWith("/") || url.contains("bing.com")) continue
                val title = cleanHtml(link.groupValues[2]).trim()
                if (title.isBlank()) continue
                val snippetMatch = SNIPPET_PATTERN.find(block)
                val snippet = cleanHtml(snippetMatch?.groupValues?.getOrNull(1).orEmpty()).trim()
                hits += WebSearchHit(title = title, url = url, snippet = snippet)
            }
            return hits
        }

        private val LINK_PATTERN: Regex =
            Regex("<a[^>]*href=\"([^\"]+)\"[^>]*>\\s*<h2>(.*?)</h2>", RegexOption.DOT_MATCHES_ALL)
        private val SNIPPET_PATTERN: Regex =
            Regex("<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)

        /** 去标签 + 解常见 HTML 实体。 */
        fun cleanHtml(raw: String): String {
            var text = raw.replace(Regex("<[^>]+>"), "")
            text = text.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ")
                .replace("&#174;", "®")
            text = NUMERIC_ENTITY_PATTERN.replace(text) { match ->
                val code = match.groupValues[1].toIntOrNull()
                if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else match.value
            }
            return text
        }

        private val NUMERIC_ENTITY_PATTERN: Regex = Regex("&#(\\d+);")

        /** 发给模型的搜索结果块：带序号、标题、摘要、链接。 */
        fun formatForModel(result: WebSearchResult): String {
            val lines = StringBuilder()
            lines.append("联网搜索结果（来源：${result.source}，查询：“${result.query}”）：\n")
            result.hits.forEachIndexed { index, hit ->
                lines.append("${index + 1}. ${hit.title}\n")
                if (hit.snippet.isNotBlank()) lines.append("   ${hit.snippet}\n")
                lines.append("   ${hit.url}\n")
            }
            lines.append("回答时优先使用以上最新搜索结果，并注明来源；如果搜索结果与问题无关，直接说搜到的内容不相关，不要硬编。")
            return lines.toString()
        }
    }

    /** 搜一次：失败用 Result 包着（调用方按失败原因如实报，不吞掉）。 */
    suspend fun search(query: String): Result<WebSearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = query.trim()
            require(trimmed.isNotEmpty()) { "搜索内容为空" }
            val html = fetcher(trimmed)
            val hits = parseBingHtml(html, maxResults)
            WebSearchResult(query = trimmed, hits = hits)
        }
    }
}
