// WhereverCode Agent — iOS
//
// 频道三期·跨设备层：席位 WebSocket 帧编解码。
// 契约字段见 wc-phase3-CONTRACT.md。每帧都带 type，便于对端分发；
// task_event 另带单调 seq（服务端按 (task_id, seq) 幂等）。
// seat_register / task_ack 的契约示例没写 type 与 seq，这里显式补上，
// 疑问记在交付说明里，不改契约正文。

import Foundation

public enum SeatUplinkSpec {
    /// 契约生产席位地址。运行时可用平台基址派生或用覆盖项替换。
    public static let productionURL = URL(string: "wss://v5.cococolo.xyz/api/open/v1/seat")!
    public static let heartbeatInterval: TimeInterval = 30
    public static let reconnectBase: TimeInterval = 1
    public static let reconnectCap: TimeInterval = 60
    public static let platformIOS = "ios"
    /// 开发期把席位地址指到本地 mock：UserDefaults 这一键优先于平台基址。
    public static let seatURLOverrideKey = "wherevercode.seatWebSocketURL"
    public static let appInstanceDefaultsKey = "wherevercode.seat.appInstanceId"

    /// 要经上行回流的本地状态迁移（契约第 3 条）。
    public static let eventKinds: Set<String> = [
        "claimed", "progress", "submitted", "decided", "withdrawn", "lease_expired",
    ]
}

public struct SeatFrameError: Error, Equatable {
    public let message: String
    public init(_ message: String) { self.message = message }
}

/// 一条待上行的本地账本事件（seq 用账本序号，跨进程稳定）。
public struct LedgerUplinkEvent: Equatable {
    public let seq: Int64
    public let taskId: String
    public let kind: String
    public let actor: String
    public let at: Double
    public let text: String

    public init(seq: Int64, taskId: String, kind: String, actor: String, at: Double, text: String) {
        self.seq = seq
        self.taskId = taskId
        self.kind = kind
        self.actor = actor
        self.at = at
        self.text = text
    }
}

public enum SeatFrame {
    case seatRegister(appInstanceId: String, agentId: String, channels: [String], platform: String, appVersion: String)
    case taskDispatch(task: [String: Any])
    case taskAck(taskId: String)
    case taskEvent(taskId: String, seq: Int64, kind: String, actor: String, at: Double, payload: [String: Any])
    case taskEventAck(taskId: String, seq: Int64)
    case ignored(type: String)
}

public enum SeatFrameCodec {
    public static func encode(_ frame: SeatFrame) throws -> String {
        let object = try object(for: frame)
        let data = try JSONSerialization.data(withJSONObject: object, options: [])
        guard let text = String(data: data, encoding: .utf8) else {
            throw SeatFrameError("帧无法编码为 UTF-8")
        }
        return text
    }

    public static func decode(_ text: String) throws -> SeatFrame {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw SeatFrameError("空帧") }
        guard let data = trimmed.data(using: .utf8),
              let parsed = try? JSONSerialization.jsonObject(with: data),
              let object = parsed as? [String: Any] else {
            throw SeatFrameError("帧不是 JSON 对象")
        }
        guard let type = object["type"] as? String, !type.isEmpty else {
            throw SeatFrameError("帧缺少 type")
        }
        switch type {
        case "seat_register":
            let appInstanceId = try requiredString(object, "app_instance_id")
            let agentId = try requiredString(object, "agent_id")
            let platform = try requiredString(object, "platform")
            let appVersion = try requiredString(object, "app_version")
            guard let rawChannels = object["channels"] as? [Any] else {
                throw SeatFrameError("seat_register.channels 必须是数组")
            }
            var channels: [String] = []
            for item in rawChannels {
                guard let channel = item as? String else {
                    throw SeatFrameError("seat_register.channels 含非字符串")
                }
                channels.append(channel)
            }
            return .seatRegister(
                appInstanceId: appInstanceId, agentId: agentId, channels: channels,
                platform: platform, appVersion: appVersion)
        case "task_dispatch":
            guard let task = object["task"] as? [String: Any] else {
                throw SeatFrameError("task_dispatch.task 必须是对象")
            }
            return .taskDispatch(task: task)
        case "task_ack":
            return .taskAck(taskId: try requiredString(object, "task_id"))
        case "task_event":
            let payload = object["payload"] as? [String: Any] ?? [:]
            if object["payload"] != nil && !(object["payload"] is [String: Any]) {
                throw SeatFrameError("task_event.payload 必须是对象")
            }
            guard let seq = jsonInt64(object["seq"]), seq > 0 else {
                throw SeatFrameError("task_event.seq 必须是正整数")
            }
            guard let at = jsonDouble(object["at"]) else {
                throw SeatFrameError("task_event.at 必须是数字")
            }
            return .taskEvent(
                taskId: try requiredString(object, "task_id"),
                seq: seq,
                kind: try requiredString(object, "kind"),
                actor: (object["actor"] as? String) ?? "",
                at: at,
                payload: payload)
        case "task_event_ack":
            guard let seq = jsonInt64(object["seq"]), seq > 0 else {
                throw SeatFrameError("task_event_ack.seq 必须是正整数")
            }
            return .taskEventAck(taskId: try requiredString(object, "task_id"), seq: seq)
        default:
            return .ignored(type: type)
        }
    }

    private static func object(for frame: SeatFrame) throws -> [String: Any] {
        switch frame {
        case .seatRegister(let appInstanceId, let agentId, let channels, let platform, let appVersion):
            return [
                "type": "seat_register",
                "app_instance_id": appInstanceId,
                "agent_id": agentId,
                "channels": channels,
                "platform": platform,
                "app_version": appVersion,
            ]
        case .taskDispatch(let task):
            return ["type": "task_dispatch", "task": task]
        case .taskAck(let taskId):
            return ["type": "task_ack", "task_id": taskId]
        case .taskEvent(let taskId, let seq, let kind, let actor, let at, let payload):
            return [
                "type": "task_event",
                "task_id": taskId,
                "seq": seq,
                "kind": kind,
                "actor": actor,
                "at": at,
                "payload": payload,
            ]
        case .taskEventAck(let taskId, let seq):
            return ["type": "task_event_ack", "task_id": taskId, "seq": seq]
        case .ignored(let type):
            return ["type": type]
        }
    }

    private static func requiredString(_ object: [String: Any], _ key: String) throws -> String {
        guard let value = object[key] as? String, !value.isEmpty else {
            throw SeatFrameError("\(key) 不能为空")
        }
        return value
    }
}

func jsonInt64(_ any: Any?) -> Int64? {
    guard let number = any as? NSNumber,
          CFGetTypeID(number) != CFBooleanGetTypeID() else { return nil }
    let double = number.doubleValue
    guard double == double.rounded(), abs(double) < 9.0e15 else { return nil }
    return number.int64Value
}

func jsonDouble(_ any: Any?) -> Double? {
    guard let number = any as? NSNumber,
          CFGetTypeID(number) != CFBooleanGetTypeID() else { return nil }
    return number.doubleValue
}
