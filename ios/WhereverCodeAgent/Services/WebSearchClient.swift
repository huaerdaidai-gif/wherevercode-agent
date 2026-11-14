import Foundation

public struct WebSearchHit: Codable, Equatable, Identifiable, Sendable {
    public var id: String { url }
    public let title: String
    public let url: String
    public let snippet: String

    public init(title: String, url: String, snippet: String) {
        self.title = title
        self.url = url
        self.snippet = snippet
    }
}

public enum WebSearchError: Error, LocalizedError, Equatable {
    case emptyQuery
    case invalidURL
    case httpStatus(Int)

    public var errorDescription: String? {
        switch self {
        case .emptyQuery: return "搜索内容为空"
        case .invalidURL: return "搜索地址无效"
        case .httpStatus(let code): return "Bing 搜索返回 HTTP \(code)"
        }
    }
}

public final class WebSearchClient: Sendable {
    public let maxResults: Int

    public init(maxResults: Int = 5) {
        self.maxResults = maxResults
    }

    public func search(query: String) async throws -> [WebSearchHit] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw WebSearchError.emptyQuery }
        var comps = URLComponents(string: "https://www.bing.com/search")
        comps?.queryItems = [URLQueryItem(name: "q", value: trimmed)]
        guard let url = comps?.url else { throw WebSearchError.invalidURL }
        var req = URLRequest(url: url)
        req.setValue("zh-CN,zh;q=0.9,en;q=0.8", forHTTPHeaderField: "Accept-Language")
        let (data, resp) = try await URLSession.shared.data(for: req)
        if let http = resp as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw WebSearchError.httpStatus(http.statusCode)
        }
        let html = String(data: data, encoding: .utf8) ?? ""
        return Self.parseBingHtml(html, maxResults: maxResults)
    }

    public static func parseBingHtml(_ html: String, maxResults: Int = 5) -> [WebSearchHit] {
        var hits: [WebSearchHit] = []
        let blocks = html.components(separatedBy: "<li class=\"b_algo\"")
        for block in blocks.dropFirst() {
            guard hits.count < maxResults else { break }
            if let hrefRange = block.range(of: "href=\""),
               let endHref = block[hrefRange.upperBound...].range(of: "\"") {
                let url = String(block[hrefRange.upperBound..<endHref.lowerBound])
                hits.append(WebSearchHit(title: url, url: url, snippet: "Web search result"))
            }
        }
        return hits
    }
}
