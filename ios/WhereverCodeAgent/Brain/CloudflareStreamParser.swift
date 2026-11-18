import Foundation

/// Token usage: only real numbers the server returned, never
/// estimated.
public struct CloudflareUsage: Equatable {
    public var inputTokens: Int64?
    public var outputTokens: Int64?
    public var totalTokens: Int64?

    public init(inputTokens: Int64?, outputTokens: Int64?, totalTokens: Int64?) {
        self.inputTokens = inputTokens
        self.outputTokens = outputTokens
        self.totalTokens = totalTokens
    }
}

/// Normalized events of a Cloudflare stream (Kotlin
/// `CloudflareStreamEvent`).
///
/// Invariant: when Failed appears, any TextDelta already emitted is
/// partial text — the bridge must discard it, not present it as a
/// successful reply.
public enum CloudflareStreamEvent: Equatable {
    case textDelta(String)
    /// A complete tool call; arguments is the full JSON string (the
    /// caller validates it). callId is the server's real id, with
    /// the `cf-call-N` client fallback only when the server sent
    /// none.
    case toolCallDone(name: String, arguments: String, callId: String?)
    case completed(usage: CloudflareUsage?, fullText: String?)
    case failed(code: String, message: String, retryable: Bool)
}

/// Workers AI SSE parser for the official OpenAI-compatible
/// endpoint. Ported from Android `CloudflareStreamParser.kt` on
/// main @4496658e — same accumulation discipline as the DeepSeek
/// parser (tool-call fragments accumulate per index and are emitted
/// whole at finish), with Cloudflare's own terminal semantics:
///
/// - unknown shapes / missing fields / invalid JSON always go to
///   `onMalformedLine` — never guessed, never silently dropped;
/// - only real server text becomes a TextDelta;
/// - `reasoning_content` is ignored (the default 8B model never
///   sends it);
/// - completion ALWAYS ends with a Completed event (no
///   empty-response failure cases — unlike DeepSeek);
/// - a stream that ends before `data: [DONE]` finishes with a
///   `stream_ended_without_terminal` failure; partial text is not a
///   success.
public final class CloudflareStreamParser {
    private struct PendingCall {
        var id: String?
        var name: String = ""
        var arguments: String = ""
    }

    private let onMalformedLine: (String) -> Void

    /// The assembled reply text (real deltas only).
    public private(set) var accumulatedText: String = ""
    /// Whether the `data: [DONE]` sentinel was received.
    public private(set) var sawTerminal: Bool = false

    private var usage: CloudflareUsage?
    private var pendingData: String?
    private var calls: [Int: PendingCall] = [:]
    private var callOrder: [Int] = []
    private var emittedCalls = false
    private var finished = false
    private var sawFinishReason = false
    private var callCounter = 0

    public init(onMalformedLine: @escaping (String) -> Void = { _ in }) {
        self.onMalformedLine = onMalformedLine
    }

    /// Convenience: parse a whole SSE text (including the finish()
    /// truncation check).
    public static func parseSseText(
        _ text: String,
        onMalformedLine: @escaping (String) -> Void = { _ in }
    ) -> [CloudflareStreamEvent] {
        let parser = CloudflareStreamParser(onMalformedLine: onMalformedLine)
        var events: [CloudflareStreamEvent] = []
        for line in text.components(separatedBy: "\n") {
            events += parser.feedLine(line)
        }
        events += parser.finish()
        return events
    }

    /// Feed one SSE line (without the newline). A blank line ends
    /// one SSE event and triggers dispatch.
    public func feedLine(_ line: String) -> [CloudflareStreamEvent] {
        if sawTerminal { return [] }
        let trimmed = line.trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty { return dispatchPending() }
        if trimmed.hasPrefix(":") { return [] }
        if trimmed.hasPrefix("event:") {
            onMalformedLine(line)
            return []
        }
        if !trimmed.hasPrefix("data:") {
            onMalformedLine(line)
            return []
        }
        let payload = String(trimmed.dropFirst("data:".count))
            .trimmingCharacters(in: .whitespaces)
        if payload == "[DONE]" {
            let flushed = pendingData != nil ? dispatchPending() : []
            sawTerminal = true
            if finished { return flushed }
            finished = true
            return flushed + finishEvents()
        }
        var buffer = pendingData ?? ""
        if !buffer.isEmpty { buffer += "\n" }
        buffer += payload
        pendingData = buffer
        if (try? JSONSerialization.jsonObject(with: Data(buffer.utf8))) != nil {
            return dispatchPending()
        }
        return []
    }

    /// Call at end of stream (EOF / connection closed). Without a
    /// prior `[DONE]`, returns a truncation failure.
    public func finish() -> [CloudflareStreamEvent] {
        if finished && pendingData == nil { return [] }
        let flushed = dispatchPending()
        finished = true
        if sawTerminal || sawFinishReason { return flushed }
        let failure = CloudflareStreamEvent.failed(
            code: "stream_ended_without_terminal",
            message: "Cloudflare 流在未收到 data: [DONE] 前结束（可能为网络中断或代理截断），" +
                "已收到的文本不作成功回复。",
            retryable: true)
        return flushed + [failure]
    }

    private func dispatchPending() -> [CloudflareStreamEvent] {
        let data = pendingData
        pendingData = nil
        guard let data, !data.trimmingCharacters(in: .whitespaces).isEmpty else { return [] }
        if data == "[DONE]" {
            sawTerminal = true
            if finished { return [] }
            finished = true
            return finishEvents()
        }
        guard let object = (try? JSONSerialization.jsonObject(with: Data(data.utf8)))
                as? [String: Any] else {
            onMalformedLine("data: \(data)")
            return []
        }
        var events: [CloudflareStreamEvent] = []

        if let error = object["error"] as? [String: Any] {
            let code = (error["code"] as? String).flatMap { $0.isEmpty ? nil : $0 }
                ?? "provider_error"
            let message = (error["message"] as? String).flatMap { $0.isEmpty ? nil : $0 }
                ?? "Cloudflare 返回错误"
            events.append(.failed(code: code, message: message, retryable: false))
            finished = true
            return events
        }

        if let usageJson = object["usage"] as? [String: Any] {
            usage = Self.parseUsage(usageJson)
        }

        if finished { return events }
        guard let choices = object["choices"] as? [Any], !choices.isEmpty else {
            if object["usage"] == nil { onMalformedLine("data: \(data)") }
            return events
        }
        guard let choice = choices[0] as? [String: Any] else {
            onMalformedLine("data: \(data)")
            return events
        }
        let delta = (choice["delta"] as? [String: Any]) ?? (choice["message"] as? [String: Any])
        if let delta {
            if let content = delta["content"] as? String, !content.isEmpty {
                accumulatedText += content
                events.append(.textDelta(content))
            }
            // reasoning_content is ignored by design (see header).
            if let toolDeltas = delta["tool_calls"] as? [Any] {
                for (i, rawCall) in toolDeltas.enumerated() {
                    guard let call = rawCall as? [String: Any] else { continue }
                    let index = (call["index"] as? NSNumber)?.intValue ?? i
                    var pending = calls[index] ?? PendingCall()
                    if calls[index] == nil { callOrder.append(index) }
                    if let id = call["id"] as? String, !id.isEmpty { pending.id = id }
                    if let fn = call["function"] as? [String: Any] {
                        if let name = fn["name"] as? String, !name.isEmpty { pending.name = name }
                        if let rawArgs = fn["arguments"] {
                            if let argsString = rawArgs as? String {
                                pending.arguments += argsString
                            } else if !(rawArgs is NSNull) {
                                pending.arguments += Self.fragmentString(rawArgs)
                            }
                        }
                    }
                    calls[index] = pending
                }
            }
        }
        let finishReason = choice["finish_reason"] as? String ?? ""
        if !finishReason.isEmpty {
            sawFinishReason = true
            events += finishEvents()
            finished = true
        }
        return events
    }

    private func finishEvents() -> [CloudflareStreamEvent] {
        var out: [CloudflareStreamEvent] = []
        if !emittedCalls && !calls.isEmpty {
            emittedCalls = true
            for index in callOrder {
                guard let pending = calls[index] else { continue }
                if pending.name.trimmingCharacters(in: .whitespaces).isEmpty {
                    onMalformedLine("tool_calls[\(index)] missing function name")
                    continue
                }
                let id: String
                if let pendingId = pending.id,
                   !pendingId.trimmingCharacters(in: .whitespaces).isEmpty {
                    id = pendingId
                } else {
                    callCounter += 1
                    id = "cf-call-\(callCounter)"
                }
                let args = pending.arguments.isEmpty ? "{}" : pending.arguments
                out.append(.toolCallDone(name: pending.name, arguments: args, callId: id))
            }
        }
        out.append(.completed(
            usage: usage,
            fullText: accumulatedText.isEmpty ? nil : accumulatedText))
        return out
    }

    /// Re-serialize a non-string arguments fragment (org.json's
    /// `toString()` on the raw value).
    private static func fragmentString(_ value: Any) -> String {
        if let data = try? JSONSerialization.data(withJSONObject: value),
           let string = String(data: data, encoding: .utf8) {
            return string
        }
        return "\(value)"
    }

    private static func parseUsage(_ usageJson: [String: Any]) -> CloudflareUsage {
        func long(_ key: String) -> Int64? {
            (usageJson[key] as? NSNumber)?.int64Value
        }
        let input: Int64?
        if usageJson["prompt_tokens"] != nil {
            input = long("prompt_tokens")
        } else if usageJson["input_tokens"] != nil {
            input = long("input_tokens")
        } else {
            input = nil
        }
        let output: Int64?
        if usageJson["completion_tokens"] != nil {
            output = long("completion_tokens")
        } else if usageJson["output_tokens"] != nil {
            output = long("output_tokens")
        } else {
            output = nil
        }
        return CloudflareUsage(
            inputTokens: input, outputTokens: output, totalTokens: long("total_tokens"))
    }
}
