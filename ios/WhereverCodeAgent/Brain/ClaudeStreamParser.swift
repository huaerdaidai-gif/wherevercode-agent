import Foundation

/// Normalised events from one Claude stream.
public enum ClaudeEvent: Equatable {
    case text(String)
    case toolCall(id: String, name: String, argumentsJson: String)
    case usage(inputTokens: Int?, outputTokens: Int?)
    case finished(reason: String)
    case failed(code: String, message: String, retryable: Bool)
}

/// Stateful SSE parser for Anthropic's Messages API stream.
/// Ported from Android `ClaudeStreamParser.kt` on main @4496658e.
///
/// Wire facts (Anthropic Messages API, public docs):
/// - The stream is SSE: `event: <type>` lines plus `data: {...}`
///   lines, events separated by blank lines; there is NO `[DONE]`
///   sentinel — the stream ends with a `message_stop` event.
/// - `message_start` carries input usage; `content_block_start`
///   opens a text or tool_use block; `content_block_delta` carries
///   `text_delta` or `input_json_delta` fragments (tool input is
///   accumulated by content-block index); `message_delta` carries
///   stop_reason and output usage.
///
/// Honesty rules (same as the DeepSeek parser): malformed lines go
/// to `onMalformedLine`, never guessed; a stream that ends without
/// `message_stop`/`message_delta` is a failure, and partial tool
/// calls are never executed as if the turn had completed.
public final class ClaudeStreamParser {
    private struct PendingCall {
        var id: String?
        var name: String = ""
        var arguments: String = ""
    }

    private let onMalformedLine: (String) -> Void

    private var calls: [Int: PendingCall] = [:]
    private var callOrder: [Int] = []
    private var pendingData: String?
    private var emittedCalls = false
    private var emittedFinished = false
    private var finished = false
    private var sawTerminal = false
    private var sawFinishReason = false
    private var sawAnyEvent = false
    private var sawText = false
    private var emittedToolCall = false
    private var callCounter = 0
    private var lastStopReason = "end_turn"

    /// True after `message_stop`.
    public var terminal: Bool { sawTerminal }

    public init(onMalformedLine: @escaping (String) -> Void = { _ in }) {
        self.onMalformedLine = onMalformedLine
    }

    /// Convenience: parse a whole SSE text (including finish()).
    public static func parseSseText(
        _ text: String,
        onMalformedLine: @escaping (String) -> Void = { _ in }
    ) -> [ClaudeEvent] {
        let parser = ClaudeStreamParser(onMalformedLine: onMalformedLine)
        var events: [ClaudeEvent] = []
        for line in text.components(separatedBy: "\n") {
            events += parser.feedLine(line)
        }
        events += parser.finish()
        return events
    }

    /// Feed one physical line (without the newline). Blank lines
    /// dispatch a pending SSE event; `data:` lines are buffered so
    /// a JSON payload split over several lines still parses as one
    /// event.
    public func feedLine(_ line: String) -> [ClaudeEvent] {
        let trimmed = line.trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty { return dispatchPending() }
        if trimmed.hasPrefix(":") { return [] } // heartbeat/comment
        if trimmed.hasPrefix("event:") { return [] } // type also in data JSON
        if !trimmed.hasPrefix("data:") {
            onMalformedLine(line)
            return []
        }
        let payload = String(trimmed.dropFirst("data:".count))
            .trimmingCharacters(in: .whitespaces)
        var buffer = pendingData ?? ""
        if !buffer.isEmpty { buffer += "\n" }
        buffer += payload
        pendingData = buffer
        if (try? JSONSerialization.jsonObject(with: Data(buffer.utf8))) != nil {
            return dispatchPending()
        }
        return []
    }

    /// Call at EOF. A stream without `message_stop` is a retryable
    /// failure.
    public func finish() -> [ClaudeEvent] {
        if finished && pendingData == nil { return [] }
        // Dispatch the pending chunk BEFORE marking finished: a
        // final message_delta chunk must still be interpreted at
        // EOF.
        let flushed = dispatchPending()
        finished = true
        if sawTerminal || sawFinishReason { return flushed }
        if sawAnyEvent {
            return flushed + [.failed(
                code: "stream_ended_without_terminal",
                message: "Claude 流在收到 message_stop 前结束（网络中断或代理截断），已收到的内容不作为成功回复。",
                retryable: true)]
        }
        return flushed + [.failed(
            code: "stream_empty",
            message: "Claude 未返回任何内容（流为空）。",
            retryable: true)]
    }

    private func dispatchPending() -> [ClaudeEvent] {
        let data = pendingData
        pendingData = nil
        guard let data, !data.trimmingCharacters(in: .whitespaces).isEmpty else { return [] }
        guard let json = (try? JSONSerialization.jsonObject(with: Data(data.utf8)))
                as? [String: Any] else {
            onMalformedLine("data: \(data)")
            return []
        }
        var out: [ClaudeEvent] = []

        // An error event can arrive mid-stream (e.g. overloaded).
        if let error = json["error"] as? [String: Any] {
            let type = (error["type"] as? String).flatMap { $0.isEmpty ? nil : $0 }
                ?? "provider_error"
            let message = (error["message"] as? String).flatMap { $0.isEmpty ? nil : $0 }
                ?? "Claude 返回错误"
            out.append(.failed(code: type, message: message, retryable: type == "overloaded_error"))
            finished = true
            return out
        }

        let type = json["type"] as? String ?? ""
        switch type {
        case "message_start":
            let usage = (json["message"] as? [String: Any])?["usage"] as? [String: Any]
            if let input = (usage?["input_tokens"] as? NSNumber)?.intValue {
                out.append(.usage(inputTokens: input, outputTokens: nil))
                sawAnyEvent = true
            }
        case "content_block_start":
            let index = (json["index"] as? NSNumber)?.intValue ?? 0
            guard let block = json["content_block"] as? [String: Any] else { return out }
            if (block["type"] as? String) == "tool_use" {
                var pending = calls[index] ?? PendingCall()
                if calls[index] == nil { callOrder.append(index) }
                if let id = block["id"] as? String, !id.isEmpty { pending.id = id }
                if let name = block["name"] as? String, !name.isEmpty { pending.name = name }
                calls[index] = pending
                sawAnyEvent = true
            }
        case "content_block_delta":
            if finished { return out }
            let index = (json["index"] as? NSNumber)?.intValue ?? 0
            guard let delta = json["delta"] as? [String: Any] else { return out }
            switch delta["type"] as? String ?? "" {
            case "text_delta":
                let text = delta["text"] as? String ?? ""
                if !text.isEmpty {
                    out.append(.text(text))
                    if !text.trimmingCharacters(in: .whitespaces).isEmpty { sawText = true }
                    sawAnyEvent = true
                }
            case "input_json_delta":
                let fragment = delta["partial_json"] as? String ?? ""
                if !fragment.isEmpty {
                    var pending = calls[index] ?? PendingCall()
                    if calls[index] == nil { callOrder.append(index) }
                    pending.arguments += fragment
                    calls[index] = pending
                    sawAnyEvent = true
                }
            default:
                // thinking_delta / signature_delta: extended
                // thinking is not enabled in our requests; ignore.
                break
            }
        case "content_block_stop":
            // All fragments for this block have arrived; the call is
            // emitted at the terminal event so an abruptly cut
            // stream never executes a partial call.
            break
        case "message_delta":
            let delta = json["delta"] as? [String: Any]
            let usage = json["usage"] as? [String: Any]
            if let output = (usage?["output_tokens"] as? NSNumber)?.intValue {
                out.append(.usage(inputTokens: nil, outputTokens: output))
                sawAnyEvent = true
            }
            let stopReason = delta?["stop_reason"] as? String ?? ""
            if !stopReason.isEmpty {
                lastStopReason = stopReason
                sawFinishReason = true
                out += finishEvents(stopReason)
                finished = true
            }
        case "message_stop":
            sawTerminal = true
            out += finishEvents(lastStopReason)
            finished = true
        case "ping":
            break
        default:
            // Unknown future event types are ignored silently:
            // Anthropic may add event types, and an unknown type
            // carries no text or tool call we could act on.
            break
        }
        return out
    }

    /// Emit complete calls once, then the terminal event. Called at
    /// `message_delta` (stop_reason) and again at `message_stop`;
    /// `emittedFinished` makes it idempotent.
    private func finishEvents(_ reason: String = "end_turn") -> [ClaudeEvent] {
        if emittedFinished { return [] }
        emittedFinished = true
        var out: [ClaudeEvent] = []
        if !emittedCalls && !calls.isEmpty {
            emittedCalls = true
            for index in callOrder {
                guard let pending = calls[index] else { continue }
                if pending.name.trimmingCharacters(in: .whitespaces).isEmpty {
                    onMalformedLine("tool_use[\(index)] missing name")
                    continue
                }
                let id: String
                if let pendingId = pending.id,
                   !pendingId.trimmingCharacters(in: .whitespaces).isEmpty {
                    id = pendingId
                } else {
                    callCounter += 1
                    id = "claude-call-\(callCounter)"
                }
                let args = pending.arguments.isEmpty ? "{}" : pending.arguments
                out.append(.toolCall(id: id, name: pending.name, argumentsJson: args))
                emittedToolCall = true
            }
        }
        // A successful terminal event requires a user-visible answer
        // (text) or at least one executable tool call. A stream that
        // only carries usage/stop_reason is NOT a successful empty
        // reply.
        if sawText || emittedToolCall {
            out.append(.finished(reason: reason))
        } else {
            out.append(.failed(
                code: "empty_response",
                message: "Claude 返回了空回复（没有文字或工具调用）。已停止本轮。",
                retryable: true))
        }
        return out
    }
}
