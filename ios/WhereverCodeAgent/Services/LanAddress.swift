// WhereverCode Agent — iOS
//
// 内网地址判定（频道三层二期·内网层，与安卓 PlatformAgent.isLanAddress
// 同口径）：私有 IPv4 段（10/8、172.16/12、192.168/16）、运营商级 NAT
// 段 100.64/10（Tailscale 等尾网）、环回（127/8、localhost）与 .local
// 局域网名。只认字面形状，解析不了的一律按公网处理（走 https），
// 不给拼错的地址开 http 口子。

import Foundation

public enum LanAddress {
    /// 判定一个平台地址（主机名或主机名:端口）是否内网地址。
    public static func isLanAddress(_ raw: String?) -> Bool {
        let hostname = (raw ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false)
            .first.map(String.init) ?? ""
        if hostname.isEmpty { return false }
        if hostname.caseInsensitiveCompare("localhost") == .orderedSame { return true }
        if hostname.lowercased().hasSuffix(".local") { return true }
        let parts = hostname.split(separator: ".", omittingEmptySubsequences: false)
        if parts.count != 4 { return false }
        var octets: [Int] = []
        for part in parts {
            // 与安卓 toIntOrNull 同口径：纯十进制解析，失败即非内网。
            guard let value = Int(part), value >= 0, value <= 255 else { return false }
            octets.append(value)
        }
        switch (octets[0], octets[1]) {
        case (10, _): return true
        case (127, _): return true
        case (172, let second) where (16...31).contains(second): return true
        case (192, 168): return true
        case (100, let second) where (64...127).contains(second): return true
        default: return false
        }
    }

    /// 端点协议：内网 http、公网 https。
    public static func httpScheme(forHost host: String) -> String {
        isLanAddress(host) ? "http" : "https"
    }

    /// 频道 WS 协议：内网 ws、公网 wss。
    public static func webSocketScheme(forHost host: String) -> String {
        isLanAddress(host) ? "ws" : "wss"
    }
}
