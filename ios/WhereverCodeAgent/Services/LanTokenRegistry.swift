// WhereverCode Agent — iOS
//
// 内网成员令牌注册表（频道三层二期·内网层，与安卓 LanTokenRegistry
// 同语义）：宿主 App 给同网的其他设备/Agent 签发加入令牌，令牌只
// 映射到一个登记身份（agentId + 显示名），LanPlatformServer 靠它
// 鉴权——没有令牌就没有内网身份，成员门禁仍由 LocalChannelService
// 按 agentId 执行，这里不另造权限逻辑。
//
// 持久化口与本地账本同款：生产实现写 App 私有文件
// （lan_platform_tokens.json），测试用内存回调。令牌是 32 位 hex
// 随机串，只存宿主本机；撤销即刻生效（服务端每请求现查注册表）。

import Foundation

public final class LanTokenRegistry {
    public struct Entry: Equatable {
        public let token: String
        public let agentId: String
        public let agentName: String
        public let createdAtMs: Int64
        /// 作用域（W12 角色化暴露）：agent = 干活侧；owner = 机主
        /// （agentId 即本机 App 身份，任务工具全开、tools/list 全量）。
        public var scope: String = "agent"
    }

    private let loadFn: () -> String?
    private let saveFn: (String) -> Void
    private let lock = NSLock()
    private var entries: [String: Entry] = [:]
    private var order: [String] = []

    public init(load: @escaping () -> String? = { nil }, save: @escaping (String) -> Void = { _ in }) {
        self.loadFn = load
        self.saveFn = save
        restore()
    }

    /// 给一位新成员签发令牌（名字是宿主给对方起的显示名）。
    public func issue(agentName: String) -> Entry {
        lock.lock(); defer { lock.unlock() }
        let name = agentName.trimmingCharacters(in: .whitespacesAndNewlines)
        let displayName = name.isEmpty ? "内网成员" : name
        var agentId: String
        repeat {
            agentId = "lan-" + Self.randomHex(bytes: 3)
        } while entries.values.contains { $0.agentId == agentId }
        let entry = Entry(
            token: Self.randomHex(bytes: 16),
            agentId: agentId,
            agentName: displayName,
            createdAtMs: Int64(Date().timeIntervalSince1970 * 1000)
        )
        entries[entry.token] = entry
        order.append(entry.token)
        persist()
        return entry
    }

    /// 机主令牌（W12）：全表至多一枚，身份固定为本机 App
    /// （LocalPlatform.appAgentID）——持它从别的设备连入即
    /// owner 作用域。已有则复用（重启不换令牌），被撤销后重签。
    public func issueOwner() -> Entry {
        lock.lock(); defer { lock.unlock() }
        if let existing = entries.values.first(where: { $0.scope == "owner" }) {
            return existing
        }
        let entry = Entry(
            token=<redacted>
            agentId: LocalPlatform.appAgentID,
            agentName: LocalPlatform.appAgentName,
            createdAtMs: Int64(Date().timeIntervalSince1970 * 1000),
            scope: "owner"
        )
        entries[entry.token] = entry
        order.append(entry.token)
        persist()
        return entry
    }

    /// 令牌换登记身份；未知/已撤销返回 nil。
    public func authenticate(token: String?) -> Entry? {
        lock.lock(); defer { lock.unlock() }
        let key = (token ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if key.isEmpty { return nil }
        return entries[key]
    }

    /// 撤销一枚令牌；返回是否确有这枚令牌。
    @discardableResult
    public func revoke(token: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        let key = token.trimmingCharacters(in: .whitespacesAndNewlines)
        guard entries.removeValue(forKey: key) != nil else { return false }
        order.removeAll { $0 == key }
        persist()
        return true
    }

    /// 当前全部登记（界面展示用，含令牌本身——只给宿主本机界面看）。
    public func list() -> [Entry] {
        lock.lock(); defer { lock.unlock() }
        return order.compactMap { entries[$0] }
    }

    private static func randomHex(bytes: Int) -> String {
        var raw = [UInt8](repeating: 0, count: bytes)
        for i in raw.indices {
            raw[i] = UInt8.random(in: UInt8.min...UInt8.max)
        }
        return raw.map { String(format: "%02x", $0) }.joined()
    }

    private func persist() {
        let rows: [[String: Any]] = order.compactMap { token in
            entries[token].map {
                [
                    "token": $0.token,
                    "agent_id": $0.agentId,
                    "agent_name": $0.agentName,
                    "created_at_ms": $0.createdAtMs,
                    "scope": $0.scope,
                ]
            }
        }
        guard let data = try? JSONSerialization.data(withJSONObject: ["entries": rows]),
              let text = String(data: data, encoding: .utf8) else { return }
        // 落盘失败不拖垮签发：内存表仍在，本进程内有效。
        saveFn(text)
    }

    private func restore() {
        guard let text = loadFn(),
              let data = text.data(using: .utf8),
              let parsed = try? JSONSerialization.jsonObject(with: data),
              let root = parsed as? [String: Any],
              let rows = root["entries"] as? [[String: Any]] else { return }
        for row in rows {
            let token = ((row["token"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let agentId = ((row["agent_id"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            if token.isEmpty || agentId.isEmpty { continue }
            let name = (row["agent_name"] as? String) ?? ""
            let created = (row["created_at_ms"] as? NSNumber)?.int64Value ?? 0
            entries[token] = Entry(
                token: token,
                agentId: agentId,
                agentName: name.isEmpty ? agentId : name,
                createdAtMs: created,
                scope: (row["scope"] as? String) ?? "agent"
            )
            order.append(token)
        }
    }
}
