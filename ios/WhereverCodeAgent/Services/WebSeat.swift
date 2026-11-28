// WhereverCode Agent — iOS
//
// 网页版 Agent 席位（chat 模式）：席位住在 App 内嵌浏览器里，用户在
// 官网页面里登录自己的账号（密码只输在官网页面，App 不经手），
// App 打开时席位在线、被 @ 即应声；App 关了席位休息，界面如实
// 标明状态。方案定版见方案便签 №21（2026-11-28 Funk 定标）。
//
// 本文件：站点目录、席位记录与存储、触发闸（纯函数）、Claude 驱动
// 的页面脚本与 WebView 会话。频道轮询与回帖在 WebSeatEngine.swift。
//
// 触发纪律与 Mac 上的网页腿桥一致（Funk 2026-11-28 立规）：席位
// 之间可以交流，但必须带 @——@ 相当于按门铃，谁按都算数；不带 @
// 的消息只有机主发的能触发（私聊与话题内跟进），其他席位不带 @
// 的发言永不触发，杜绝机器人互接话。

import Foundation
import WebKit

// MARK: - Site catalog

/// One web-seat site. Drivers are per-site and small; adding a site
/// is one catalog entry plus one driver implementation.
public struct WebSeatSite: Equatable, Sendable {
    public let driverId: String
    public let shortName: String
    public let displayName: String
    public let homeURL: URL
    /// Handles that address this seat in channel text.
    public let mentionHandles: [String]
    /// Platform agent id the seat posts as. The agent must exist on
    /// the platform; the App issues its token with the owner's
    /// credential (PlatformTokenIssuer) and keeps it in the Keychain.
    public let platformAgentId: String

    public static let claude = WebSeatSite(
        driverId: "claude-web",
        shortName: "Claude",
        displayName: "Claude（网页版）",
        homeURL: URL(string: "https://claude.ai/new")!,
        mentionHandles: ["@Claude Chat", "@claude-chat", "@Claude", "@Claude（网页版）", "@Claude(网页版)", "@所有人"],
        platformAgentId: "claude-web")

    public static let all: [WebSeatSite] = [.claude]

    public static func site(driverId: String) -> WebSeatSite? {
        all.first { $0.driverId == driverId }
    }

    /// Handles of every other known seat (web seats plus the
    /// channel's other regulars), for the gate's "addressed
    /// elsewhere" check. Mirrors the Mac bridges' OTHER_HANDLES.
    public static let knownHandles: [String] = [
        "@GPT Chat", "@gpt-chat", "@ChatGPT", "@GPT",
        "@Gemini Chat", "@gemini-chat", "@Gemini",
        "@Grok Chat", "@grok-chat", "@Grok",
        "@Claude Chat", "@claude-chat", "@Claude",
        "@Codex", "@Muse", "@Kimi", "@AGY", "@Manus", "@LeChat",
        "@千问", "@Qwen", "@豆包", "@Kiro", "@Cursor",
    ]

    public func otherHandles() -> [String] {
        let own = Set(mentionHandles)
        return WebSeatSite.knownHandles.filter { !own.contains($0) && $0 != "@所有人" }
    }
}

// MARK: - Seat record & store

public struct WebSeatRecord: Codable, Equatable, Identifiable, Sendable {
    public var id: String { seatId }
    public var seatId: String
    public var driverId: String
    public var displayName: String
    public var platformAgentId: String
    /// WKWebsiteDataStore identifier (UUID string): the seat's
    /// private, persistent browser profile — its login lives here
    /// and nowhere else.
    public var dataStoreId: String
    public var channelIds: [String] = []
    public var enabled: Bool = true
    public var createdAtEpochSeconds: Int64 = 0

    public init(seatId: String, site: WebSeatSite, dataStoreId: String) {
        self.seatId = seatId
        self.driverId = site.driverId
        self.displayName = site.displayName
        self.platformAgentId = site.platformAgentId
        self.dataStoreId = dataStoreId
    }

    public var site: WebSeatSite? { WebSeatSite.site(driverId: driverId) }
}

/// Seat persistence: records as one JSON list in the kv table
/// (same shape as AgentRegistryStore); tokens ONLY in the Keychain,
/// one item per seat.
public final class WebSeatStore {
    private let kv: KeyValueStore
    private let storageKey = "webseats.records.v1"

    public init(kv: KeyValueStore) {
        self.kv = kv
    }

    public func listSeats() throws -> [WebSeatRecord] {
        try kv.getJSON(storageKey, as: [WebSeatRecord].self) ?? []
    }

    public func saveSeat(_ record: WebSeatRecord) throws {
        var record = record
        if record.createdAtEpochSeconds == 0 {
            record.createdAtEpochSeconds = Int64(Date().timeIntervalSince1970)
        }
        var seats = try listSeats()
        if let index = seats.firstIndex(where: { $0.seatId == record.seatId }) {
            seats[index] = record
        } else {
            seats.append(record)
        }
        try kv.setJSON(storageKey, seats)
    }

    public func deleteSeat(seatId: String) throws {
        var seats = try listSeats()
        seats.removeAll { $0.seatId == seatId }
        try kv.setJSON(storageKey, seats)
        try? tokenStore(seatId: seatId).deleteToken()
    }

    // MARK: Token (Keychain only)

    private func tokenStore(seatId: String) -> KeychainStore {
        KeychainStore(service: "wherevercode.agent", account: "webseat-token-\(seatId)")
    }

    public func saveToken(_ token: String, seatId: String) throws {
        try tokenStore(seatId: seatId).saveToken(token)
    }

    public func loadToken(seatId: String) -> String? {
        try? tokenStore(seatId: seatId).loadToken()
    }

    public func hasToken(seatId: String) -> Bool {
        tokenStore(seatId: seatId).hasToken
    }
}

// MARK: - Trigger gate (pure; unit-tested)

public enum WebSeatGate {
    /// Doorbell rule (Funk, 2026-11-28): seats may talk to each
    /// other, but an inter-seat message must carry an explicit @ of
    /// this seat — the @ is the doorbell, whoever sends it. A
    /// message without an @ triggers only from the owner (the App's
    /// own platform agent id): his DMs always count, and his reply
    /// inside a thread the seat is part of continues the
    /// conversation unless it @s only someone else. @-less messages
    /// from other seats never trigger — no chain-talking.
    public static func shouldRespond(
        senderId: String,
        ownerAgentId: String,
        seatAgentId: String,
        text: String,
        threadRoot: String?,
        isDM: Bool,
        handles: [String],
        otherHandles: [String]
    ) -> Bool {
        if senderId == seatAgentId { return false }
        let mentioned = handles.contains { text.contains($0) }
        if mentioned { return true }
        if senderId != ownerAgentId { return false }
        if isDM { return true }
        let otherOnly = otherHandles.contains { text.contains($0) }
        return threadRoot != nil && !otherOnly
    }

    /// Remove this seat's @ handles from the question text before it
    /// is handed to the web page (mirrors the Mac bridges).
    public static func stripMentions(_ text: String, handles: [String]) -> String {
        var out = text
        for handle in handles { out = out.replacingOccurrences(of: handle, with: "") }
        return out.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

// MARK: - Driver scripts (Claude)

/// The claude.ai page contract, ported from the Mac claude-chat
/// bridge's verified driver (ask_webclaude.js, v2.2): composer is
/// `div[contenteditable="true"]`, message rows carry
/// `data-testid="assistant-message"`, reply text lives in the
/// `.standard-markdown` block of the NEW assistant row.
public enum ClaudeSeatScripts {
    public static let probe = """
        (() => { const ce = !!document.querySelector('div[contenteditable="true"]'); \
        const msgs = document.querySelectorAll('[data-testid="assistant-message"], [data-testid="user-message"]').length; \
        return JSON.stringify({href: location.href, composer: ce, msgs: msgs}); })()
        """

    public static let assistantCount = """
        document.querySelectorAll('[data-testid="assistant-message"]').length
        """

    public static func insert(question: String) -> String {
        let json = jsonStringLiteral(question)
        return """
            (() => { const ce = document.querySelector('div[contenteditable="true"]'); \
            if (!ce) return 'no-ce'; ce.focus(); \
            const ok = document.execCommand('insertText', false, \(json)); \
            return ok ? 'ok' : 'insert-failed'; })()
            """
    }

    public static let send = """
        (() => { const b = Array.from(document.querySelectorAll('button'))\
        .find(x => /发送消息|send message/i.test(x.getAttribute('aria-label') || '')); \
        if (b && !b.disabled) { b.click(); return 'clicked'; } return 'no-send-btn'; })()
        """

    public static let sendByEnter = """
        (() => { const ce = document.querySelector('div[contenteditable="true"]'); \
        if (!ce) return 'no-ce'; ce.focus(); \
        ce.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true})); \
        return 'enter'; })()
        """

    public static func extract(beforeCount: Int) -> String {
        """
        (() => { const stop = Array.from(document.querySelectorAll('button'))\
        .some(x => /stop|停止/i.test(x.getAttribute('aria-label') || '')); \
        const asst = document.querySelectorAll('[data-testid="assistant-message"]'); \
        let last = ''; \
        if (asst.length > \(beforeCount)) { const row = asst[\(beforeCount)]; \
        const md = row.querySelector('[class*="standard-markdown"]') || row.querySelector('.prose'); \
        last = ((md ? md.innerText : '') || '').trim(); } \
        return JSON.stringify({stop: stop, text: last, href: location.href}); })()
        """
    }

    /// Logged-in heuristic on the /new page: a composer is present
    /// and we were not bounced to a login URL.
    public static func probeLooksLoggedIn(href: String, composer: Bool) -> Bool {
        composer && !href.contains("/login") && !href.contains("auth")
    }

    private static func jsonStringLiteral(_ value: String) -> String {
        let data = try! JSONSerialization.data(withJSONObject: [value], options: [])
        let text = String(data: data, encoding: .utf8) ?? "[\"\"]"
        return String(text.dropFirst().dropLast())
    }
}

public struct WebSeatProbe: Equatable {
    public var href: String
    public var composer: Bool
    public var messageCount: Int
}

public enum WebSeatError: Error, Equatable {
    case pageNotVerified
    case insertFailed(String)
    case timeoutNoReply
    case notLoggedIn

    public var message: String {
        switch self {
        case .pageNotVerified: return "页面未就绪（新对话校验未通过）。"
        case .insertFailed(let why): return "输入问题失败（\(why)）。"
        case .timeoutNoReply: return "等待回复超时。"
        case .notLoggedIn: return "尚未登录。"
        }
    }
}

// MARK: - WebView session (one seat, one browser profile)

/// Owns the seat's WKWebView. The view must live in the app's view
/// hierarchy while the seat works (the management UI attaches it,
/// hidden, for background operation and visible for login); the
/// session itself is pure logic over the page.
///
/// The ask flow mirrors the Mac bridge's verified v2.2 discipline:
/// navigate, then VERIFY the page is really the intended
/// conversation before typing (blank message list for a new
/// conversation, href match to continue one — two consecutive
/// probes), and accept the reply only from the NEW assistant row
/// that appears after sending.
@MainActor
public final class WebSeatSession {
    public let seat: WebSeatRecord
    public let webView: WKWebView

    public init(seat: WebSeatRecord) {
        self.seat = seat
        let config = WKWebViewConfiguration()
        config.websiteDataStore = WKWebsiteDataStore(
            forIdentifier: UUID(uuidString: seat.dataStoreId) ?? UUID())
        self.webView = WKWebView(
            frame: CGRect(x: 0, y: 0, width: 390, height: 844), configuration: config)
    }

    public func loadHome() {
        webView.load(URLRequest(url: seat.site?.homeURL ?? URL(string: "https://claude.ai/new")!))
    }

    private func load(_ urlString: String) {
        guard let url = URL(string: urlString) else { return }
        webView.load(URLRequest(url: url))
    }

    private func evalRaw(_ js: String) async throws -> Any? {
        try await webView.evaluateJavaScript(js)
    }

    private func evalString(_ js: String) async -> String? {
        (try? await webView.evaluateJavaScript(js)) as? String
    }

    public func probe() async -> WebSeatProbe? {
        guard let raw = await evalString(ClaudeSeatScripts.probe),
              let data = raw.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data),
              let dict = obj as? [String: Any] else { return nil }
        return WebSeatProbe(
            href: dict["href"] as? String ?? "",
            composer: dict["composer"] as? Bool ?? false,
            messageCount: (dict["msgs"] as? NSNumber)?.intValue ?? 0)
    }

    private func assistantCount() async -> Int {
        let value = try? await evalRaw(ClaudeSeatScripts.assistantCount)
        return (value as? NSNumber)?.intValue ?? 0
    }

    /// True when the seat's browser profile is logged in at
    /// claude.ai (composer reachable on /new, no login bounce).
    public func probeLogin() async -> Bool {
        load("https://claude.ai/new")
        for _ in 0..<10 {
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            guard let st = await probe() else { continue }
            if st.href.contains("/login") { return false }
            if ClaudeSeatScripts.probeLooksLoggedIn(href: st.href, composer: st.composer) {
                return true
            }
        }
        return false
    }

    private func verifyPage(target: String) async -> Bool {
        var streak = 0
        for _ in 0..<20 {
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            guard let st = await probe(), st.composer else { streak = 0; continue }
            let ok = (target == "new") ? (st.messageCount == 0) : st.href.hasPrefix(target)
            if ok {
                streak += 1
                if streak >= 2 { return true }
            } else {
                streak = 0
            }
        }
        return false
    }

    /// Ask one question. `target` is "new" for a fresh conversation
    /// or an existing conversation URL to continue it. Returns the
    /// reply text and the conversation URL it belongs to.
    public func ask(question: String, target: String) async throws -> (text: String, href: String) {
        let want = (target == "new") ? "https://claude.ai/new" : target
        load(want)
        if await verifyPage(target: target) == false {
            load(want)
            if await verifyPage(target: target) == false {
                throw WebSeatError.pageNotVerified
            }
        }
        let before = await assistantCount()
        let inserted = await evalString(ClaudeSeatScripts.insert(question: question))
        guard inserted == "ok" else {
            throw WebSeatError.insertFailed(inserted ?? "nil")
        }
        try? await Task.sleep(nanoseconds: 1_000_000_000)
        let sent = await evalString(ClaudeSeatScripts.send)
        if sent != "clicked" {
            _ = await evalString(ClaudeSeatScripts.sendByEnter)
        }
        var lastText = ""
        var stable = 0
        var href = want
        for _ in 0..<80 {
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            guard let raw = await evalString(ClaudeSeatScripts.extract(beforeCount: before)),
                  let data = raw.data(using: .utf8),
                  let obj = try? JSONSerialization.jsonObject(with: data),
                  let dict = obj as? [String: Any] else { continue }
            let text = dict["text"] as? String ?? ""
            let stop = dict["stop"] as? Bool ?? false
            if let h = dict["href"] as? String, !h.isEmpty { href = h }
            if !text.isEmpty && text == lastText && !stop {
                stable += 1
                if stable >= 2 { return (text, href) }
            } else {
                stable = 0
            }
            if !text.isEmpty { lastText = text }
        }
        if !lastText.isEmpty { return (lastText, href) }
        throw WebSeatError.timeoutNoReply
    }
}
