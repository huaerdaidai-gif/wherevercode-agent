import Foundation

/// Normalised events from one DeepSeek stream (Kotlin
/// `DeepSeekEvent`).
public enum DeepSeekEvent: Equatable {
    case text(String)
    case reasoning(String)
    case toolCall(id: String, name: String, argumentsJson: String)
    case usage(inputTokens: Int?, outputTokens: Int?)
    case finished(reason: String)
    case failed(code: String, message: String, retryable: Bool)
}

/// Stateful SSE parser for the OpenAI-compatible `/chat/completions`
/// stream DeepSeek serves. Ported line-for-line from Android
/// `DeepSeekStreamParser.kt` on main @4496658e.
///
/// Why a dedicated parser: tool calls arrive fragmented across
/// chunks and must be accumulated by call index; a stream that ends
/// without `[DONE]` is a failure, and no partial tool call is ever
/// emitted as if the turn had completed. Malformed lines are
/// reported through `onMalformedLine`, never guessed.
public final class DeepSeekStreamParser {
    private struct PendingCall {
        var id: String? = nil
        var name: String = ""
        var arguments: String = ""
    }

    private let onMalformedLine: (String) -> Void
    private var calls: [Int: PendingCall] = [:]
    private var callOrder: [Int] = []
    private var pendingData: String? = nil
    private var emittedCalls = false
    private var finished = false
    private var sawTerminal = false
    private var sawFinishReason = false
    private var sawAnyEvent = false
    private var sawText = false
    private var sawReasoning = false
    private var emittedToolCall = false
    private var callCounter = 0

    public init(onMalformedLine: @escaping (String) -> Void = { _ in }) {
        self.onMalformedLine = onMalformedLine
    }

    /// True after `data: [DONE]`.
    public var terminal: Bool { sawTerminal }

    /// Feed one physical line (without the newline). Blank lines
    /// dispatch a pending SSE event; `data:` lines are buffered so a
    /// JSON payload split over several lines is still parsed as one
    /// event.
    public func feedLine(_ line: String) -> [DeepSeekEvent] {
        let trimmed = line.trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty { return dispatchPending() }
        if trimmed.hasPrefix(":") { return [] } // heartbeat/comment
        guard trimmed.hasPrefix("data:") else {
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
        var buf = pendingData ?? ""
        if !buf.isEmpty { buf += "\n" }
        buf += payload
        pendingData = buf
        // Standard SSE separates events with a blank line, but
        // several OpenAI-compatible gateways omit it. If the buffered
        // payload is already a complete JSON object, dispatch it now;
        // otherwise keep buffering for a genuinely multi-line payload.
        if Self.parseJsonObject(buf) != nil {
            return dispatchPending()
        }
        return []
    }

    /// Call at EOF. A stream without `[DONE]` is a retryable failure.
    public func finish() -> [DeepSeekEvent] {
        if finished && pendingData == nil { return [] }
        // Dispatch the pending chunk BEFORE marking finished: a final
        // finish_reason/tool-call chunk must still be interpreted at
        // EOF.
        let flushed = dispatchPending()
        finished = true
        if sawTerminal || sawFinishReason { return flushed }
        if sawAnyEvent {
            return flushed + [.failed(
                code: "stream_ended_without_terminal",
                message: "DeepSeek 流在收到 [DONE] 前结束（网络中断或代理截断），已收到的内容不作为成功回复。",
                retryable: true)]
        }
        return flushed + [.failed(
            code: "stream_empty",
            message: "DeepSeek 未返回任何内容（流为空）。",
            retryable: true)]
    }

    private func dispatchPending() -> [DeepSeekEvent] {
        let data = pendingData
        pendingData = nil
        guard let data, !data.trimmingCharacters(in: .whitespaces).isEmpty else { return [] }
        if data == "[DONE]" {
            sawTerminal = true
            finished = true
            return finishEvents()
        }
        guard let json = Self.parseJsonObject(data) else {
            onMalformedLine("data: \(data)")
            return []
        }
        var out: [DeepSeekEvent] = []

        if let error = json["error"] as? [String: Any] {
            let code = Self.stringOrEmpty(error, "code")
            let message = Self.stringOrEmpty(error, "message")
            out.append(.failed(
                code: code.isEmpty ? "provider_error" : code,
                message: message.isEmpty ? "DeepSeek 返回错误" : message,
                retryable: false))
            finished = true
            return out
        }

        if let usage = json["usage"] as? [String: Any] {
            let input = Self.intOrNil(usage, "prompt_tokens") ?? Self.intOrNil(usage, "input_tokens")
            let output = Self.intOrNil(usage, "completion_tokens") ?? Self.intOrNil(usage, "output_tokens")
            if input != nil || output != nil {
                out.append(.usage(inputTokens: input, outputTokens: output))
                sawAnyEvent = true
            }
        }

        // A finish_reason already ended the turn; the only valid late
        // chunk is the usage-only chunk providers send before [DONE].
        if finished { return out }
        guard let choices = json["choices"] as? [Any], !choices.isEmpty else {
            // A usage-only final chunk is valid; anything else is
            // unknown.
            if json["usage"] == nil { onMalformedLine("data: \(data)") }
            return out
        }
        guard let choice = choices[0] as? [String: Any] else {
            onMalformedLine("data: \(data)")
            return out
        }
        let delta = (choice["delta"] as? [String: Any]) ?? (choice["message"] as? [String: Any])
        if let delta {
            let reasoning = Self.stringOrEmpty(delta, "reasoning_content")
            if !reasoning.isEmpty {
                out.append(.reasoning(reasoning))
                sawReasoning = true
                sawAnyEvent = true
            }
            let content = Self.stringOrEmpty(delta, "content")
            if !content.isEmpty {
                out.append(.text(content))
                if !content.trimmingCharacters(in: .whitespaces).isEmpty { sawText = true }
                sawAnyEvent = true
            }
            if let toolDeltas = delta["tool_calls"] as? [Any] {
                for (i, rawCall) in toolDeltas.enumerated() {
                    guard let call = rawCall as? [String: Any] else { continue }
                    let index = Self.intOrNil(call, "index") ?? i
                    var pending = calls[index] ?? PendingCall()
                    if calls[index] == nil { callOrder.append(index) }
                    let callId = Self.stringOrEmpty(call, "id")
                    if !callId.isEmpty { pending.id = callId }
                    if let fn = call["function"] as? [String: Any] {
                        let name = Self.stringOrEmpty(fn, "name")
                        if !name.isEmpty { pending.name = name }
                        if let rawArgs = fn["arguments"], !(rawArgs is NSNull) {
                            if let s = rawArgs as? String {
                                pending.arguments += s
                            } else if let obj = rawArgs as? [String: Any],
                                      let text = Self.serializeJson(obj) {
                                pending.arguments += text
                            } else if let n = rawArgs as? NSNumber {
                                pending.arguments += n.stringValue
                            } else if let arr = rawArgs as? [Any],
                                      let data = try? JSONSerialization.data(withJSONObject: arr),
                                      let text = String(data: data, encoding: .utf8) {
                                pending.arguments += text
                            }
                        }
                    }
                    calls[index] = pending
                    sawAnyEvent = true
                }
            }
        }
        // finish_reason is JSON null in every non-terminal streaming
        // chunk; stringOrEmpty maps null to "", so only a real reason
        // terminates here (the K70 blank-bubble bug on Android came
        // from treating null as the literal string "null").
        let finishReason = Self.stringOrEmpty(choice, "finish_reason")
        if !finishReason.isEmpty {
            sawFinishReason = true
            out += finishEvents(reason: finishReason)
            finished = true
        }
        return out
    }

    /// Emit complete calls once, then the terminal event. Called at
    /// `finish_reason` and again at `[DONE]`; `emittedCalls` makes it
    /// idempotent.
    private func finishEvents(reason: String = "stop") -> [DeepSeekEvent] {
        var out: [DeepSeekEvent] = []
        if !emittedCalls && !calls.isEmpty {
            emittedCalls = true
            for index in callOrder {
                guard let pending = calls[index] else { continue }
                if pending.name.trimmingCharacters(in: .whitespaces).isEmpty {
                    onMalformedLine("tool_calls[\(index)] missing function name")
                    continue
                }
                let id: String
                if let existing = pending.id,
                   !existing.trimmingCharacters(in: .whitespaces).isEmpty {
                    id = existing
                } else {
                    callCounter += 1
                    id = "deepseek-call-\(callCounter)"
                }
                let args = pending.arguments.isEmpty ? "{}" : pending.arguments
                out.append(.toolCall(id: id, name: pending.name, argumentsJson: args))
                emittedToolCall = true
            }
        }
        // A successful terminal event requires a user-visible answer
        // (text) or at least one executable tool call. A stream that
        // only carries reasoning/usage/finish_reason is NOT a
        // successful empty reply.
        if sawText || emittedToolCall {
            out.append(.finished(reason: reason))
        } else if sawReasoning {
            out.append(.failed(
                code: "reasoning_only",
                message: "DeepSeek 只返回了推理内容，没有返回可显示的回答。已停止本轮。",
                retryable: true))
        } else {
            out.append(.failed(
                code: "empty_response",
                message: "DeepSeek 返回了空回复（没有文字或工具调用）。已停止本轮。",
                retryable: true))
        }
        return out
    }

    /// Convenience for tests and non-stream responses.
    public static func parseSseText(
        _ text: String,
        onMalformedLine: @escaping (String) -> Void = { _ in }
    ) -> [DeepSeekEvent] {
        let parser = DeepSeekStreamParser(onMalformedLine: onMalformedLine)
        var events: [DeepSeekEvent] = []
        for line in text.split(separator: "\n", omittingEmptySubsequences: false) {
            events += parser.feedLine(String(line))
        }
        events += parser.finish()
        return events
    }

    // MARK: - JSON helpers

    static func parseJsonObject(_ text: String) -> [String: Any]? {
        guard let data = text.data(using: .utf8),
              let raw = try? JSONSerialization.jsonObject(with: data),
              let dict = raw as? [String: Any] else { return nil }
        return dict
    }

    static func serializeJson(_ object: [String: Any]) -> String? {
        guard let data = try? JSONSerialization.data(withJSONObject: object) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// JSON null / missing → ""; strings pass through; numbers are
    /// stringified (mirrors org.json `optString` with the isNull
    /// guard).
    static func stringOrEmpty(_ dict: [String: Any], _ key: String) -> String {
        guard let value = dict[key], !(value is NSNull) else { return "" }
        if let s = value as? String { return s }
        if let n = value as? NSNumber { return n.stringValue }
        return ""
    }

    static func intOrNil(_ dict: [String: Any], _ key: String) -> Int? {
        guard let value = dict[key], !(value is NSNull) else { return nil }
        if let n = value as? NSNumber { return n.intValue }
        return nil
    }
}
