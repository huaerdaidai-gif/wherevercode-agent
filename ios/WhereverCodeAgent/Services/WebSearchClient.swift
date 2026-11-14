import Foundation

/// Web search, ported from the Android baseline
/// `brain/WebSearchClient.kt` on main @4496658e: the "+" menu's web
/// search toggle. When the toggle is on, the turn searches first and
/// answers with the results in context (the mainstream ChatGPT-style
/// shape).
///
/// There is no search API key and no self-hosted search service, so
/// this reads Bing web search (`bing.com/search`, no key) — real web
/// results, not fake data.
///
/// Honesty boundaries (in code, not just comments):
/// - Bing is HTML scraping with no stability guarantee: when the page
///   yields no parseable results the caller reports exactly that and
///   never invents results; network failures report their reason;
/// - results enter only the current turn's context as a system
///   message, never persisted into conversation history;
/// - when a search fails, the turn still answers, but must say the
///   search results were not used.
public struct WebSearchHit: Equatable {
    public var title: String
    public var url: String
    public var snippet: String

    public init(title: String, url: String, snippet: String) {
        self.title = title
        self.url = url
        self.snippet = snippet
    }
}

public struct WebSearchResult: Equatable {
    public var query: String
    public var hits: [WebSearchHit]
    public var source: String = "Bing"

    public init(query: String, hits: [WebSearchHit], source: String = "Bing") {
        self.query = query
        self.hits = hits
        self.source = source
    }
}

public enum WebSearchError: Error, Equatable {
    case emptyQuery
    case httpStatus(Int)
    case transport(String)

    public var message: String {
        switch self {
        case .emptyQuery: return "搜索内容为空"
        case .httpStatus(let code): return "Bing 搜索返回 HTTP \(code)"
        case .transport(let reason): return reason
        }
    }
}

public final class WebSearchClient {
    public static let defaultMaxResults = 5
    public static let searchTimeoutSeconds: TimeInterval = 10

    private static let userAgent =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /// Injectable page fetcher (query -> HTML), for tests.
    public typealias Fetcher = (String) async throws -> String

    private let fetcher: Fetcher
    private let maxResults: Int

    public init(fetcher: Fetcher? = nil, maxResults: Int = WebSearchClient.defaultMaxResults) {
        self.fetcher = fetcher ?? Self.defaultFetch
        self.maxResults = maxResults
    }

    /// Default fetch: Bing web search (no key).
    public static func defaultFetch(query: String) async throws -> String {
        var components = URLComponents(string: "https://www.bing.com/search")
        components?.queryItems = [
            URLQueryItem(name: "q", value: query),
            URLQueryItem(name: "count", value: "10"),
            URLQueryItem(name: "mkt", value: "zh-CN"),
        ]
        guard let url = components?.url else {
            throw WebSearchError.transport("搜索地址无效")
        }
        var request = URLRequest(url: url, timeoutInterval: searchTimeoutSeconds)
        request.httpMethod = "GET"
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("zh-CN,zh;q=0.9,en;q=0.8", forHTTPHeaderField: "Accept-Language")
        let (data, response): (Data, URLResponse)
        do {
            (data, response) = try await URLSession.shared.data(for: request)
        } catch {
            throw WebSearchError.transport(error.localizedDescription)
        }
        if let http = response as? HTTPURLResponse, http.statusCode != 200 {
            throw WebSearchError.httpStatus(http.statusCode)
        }
        return String(data: data, encoding: .utf8)
            ?? String(decoding: data, as: UTF8.self)
    }

    /// Parse a Bing results page: each `<li class="b_algo">` block is
    /// one result; the title is in `<a href="..."><h2>...</h2></a>`,
    /// the snippet in the first `<p>`. Pure function, unit-testable;
    /// returns an empty list when nothing parses (the caller reports
    /// that honestly, never invents results).
    public static func parseBingHtml(
        _ html: String, maxResults: Int = WebSearchClient.defaultMaxResults
    ) -> [WebSearchHit] {
        var hits: [WebSearchHit] = []
        let blocks = html.components(separatedBy: "<li class=\"b_algo\"").dropFirst()
        for block in blocks {
            if hits.count >= maxResults { break }
            guard let link = firstMatch(linkPattern, in: block) else { continue }
            let url = group(link, 1, in: block).trimmingCharacters(in: .whitespaces)
            if url.isEmpty || url.hasPrefix("/") || url.contains("bing.com") { continue }
            let title = cleanHtml(group(link, 2, in: block))
                .trimmingCharacters(in: .whitespaces)
            if title.isEmpty { continue }
            let snippet = firstMatch(snippetPattern, in: block)
                .map { cleanHtml(group($0, 1, in: block)) }?
                .trimmingCharacters(in: .whitespaces) ?? ""
            hits.append(WebSearchHit(title: title, url: url, snippet: snippet))
        }
        return hits
    }

    private static let linkPattern = try! NSRegularExpression(
        pattern: "<a[^>]*href=\"([^\"]+)\"[^>]*>\\s*<h2>(.*?)</h2>",
        options: [.dotMatchesLineSeparators])
    private static let snippetPattern = try! NSRegularExpression(
        pattern: "<p[^>]*>(.*?)</p>", options: [.dotMatchesLineSeparators])
    private static let tagPattern = try! NSRegularExpression(pattern: "<[^>]+>", options: [])
    private static let numericEntityPattern = try! NSRegularExpression(
        pattern: "&#(\\d+);", options: [])

    private static func firstMatch(
        _ regex: NSRegularExpression, in text: String
    ) -> NSTextCheckingResult? {
        regex.firstMatch(in: text, range: NSRange(text.startIndex..., in: text))
    }

    private static func group(_ match: NSTextCheckingResult, _ index: Int, in text: String) -> String {
        guard index < match.numberOfRanges,
              let range = Range(match.range(at: index), in: text)
        else { return "" }
        return String(text[range])
    }

    /// Strip tags + decode the common HTML entities.
    public static func cleanHtml(_ raw: String) -> String {
        var text = tagPattern.stringByReplacingMatches(
            in: raw, range: NSRange(raw.startIndex..., in: raw), withTemplate: "")
        text = text.replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&#39;", with: "'")
            .replacingOccurrences(of: "&apos;", with: "'")
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&nbsp;", with: " ")
            .replacingOccurrences(of: "&#174;", with: "®")
        // Numeric entities (same order as Android: after the named
        // ones, so &#39; above is not double-decoded).
        let matches = numericEntityPattern.matches(
            in: text, range: NSRange(text.startIndex..., in: text))
        if !matches.isEmpty {
            var result = ""
            var cursor = text.startIndex
            for match in matches {
                guard let range = Range(match.range, in: text) else { continue }
                result += text[cursor..<range.lowerBound]
                let codeText = group(match, 1, in: text)
                if let code = Int(codeText), code >= 1, code <= 0x10FFFF,
                   let scalar = Unicode.Scalar(UInt32(code)) {
                    result += String(Character(scalar))
                } else {
                    result += text[range]
                }
                cursor = range.upperBound
            }
            result += text[cursor...]
            text = result
        }
        return text
    }

    /// The result block handed to the model: numbered, with titles,
    /// snippets and links.
    public static func formatForModel(_ result: WebSearchResult) -> String {
        var lines = "联网搜索结果（来源：\(result.source)，查询：“\(result.query)”）：\n"
        for (index, hit) in result.hits.enumerated() {
            lines += "\(index + 1). \(hit.title)\n"
            if !hit.snippet.trimmingCharacters(in: .whitespaces).isEmpty {
                lines += "   \(hit.snippet)\n"
            }
            lines += "   \(hit.url)\n"
        }
        lines += "回答时优先使用以上最新搜索结果，并注明来源；如果搜索结果与问题无关，直接说搜到的内容不相关，不要硬编。"
        return lines
    }

    /// Search once; failures come back as a Result (the caller
    /// reports the reason honestly, never swallows it).
    public func search(query: String) async -> Result<WebSearchResult, WebSearchError> {
        let trimmed = query.trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty { return .failure(.emptyQuery) }
        do {
            let html = try await fetcher(trimmed)
            let hits = Self.parseBingHtml(html, maxResults: maxResults)
            return .success(WebSearchResult(query: trimmed, hits: hits))
        } catch let error as WebSearchError {
            return .failure(error)
        } catch {
            return .failure(.transport(error.localizedDescription))
        }
    }
}
