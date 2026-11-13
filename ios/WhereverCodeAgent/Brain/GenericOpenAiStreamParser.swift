import Foundation

/// Normalised events from one generic OpenAI-compatible stream
/// (Kotlin `GenericOpenAiEvent`). Same shape as [DeepSeekEvent];
/// Android keeps two faithful clones, iOS shares one implementation
/// parameterized by vendor wording.
public typealias GenericOpenAiEvent = DeepSeekEvent

/// Stateful SSE parser for any OpenAI-compatible provider. Faithful
/// port of Android `GenericOpenAiStreamParser.kt` on main
/// @4496658e: same framing rules, same accumulation by call index,
/// same terminal rules as the DeepSeek parser — only the wording is
/// vendor-neutral ("模型 API") and the fallback call-id prefix is
/// `generic-call-N`.
public final class GenericOpenAiStreamParser {
    private let core: DeepSeekStreamParser

    public init(onMalformedLine: @escaping (String) -> Void = { _ in }) {
        self.core = DeepSeekStreamParser(
            onMalformedLine: onMalformedLine, vendorLabel: "模型 API", callIdPrefix: "generic")
    }

    /// True after `data: [DONE]`.
    public var terminal: Bool { core.terminal }

    public func feedLine(_ line: String) -> [GenericOpenAiEvent] {
        core.feedLine(line)
    }

    /// Call at EOF. A stream without `[DONE]` is a retryable failure.
    public func finish() -> [GenericOpenAiEvent] {
        core.finish()
    }

    /// Convenience for tests and non-stream responses.
    public static func parseSseText(
        _ text: String,
        onMalformedLine: @escaping (String) -> Void = { _ in }
    ) -> [GenericOpenAiEvent] {
        let parser = GenericOpenAiStreamParser(onMalformedLine: onMalformedLine)
        var events: [GenericOpenAiEvent] = []
        for line in text.split(separator: "\n", omittingEmptySubsequences: false) {
            events += parser.feedLine(String(line))
        }
        events += parser.finish()
        return events
    }
}
