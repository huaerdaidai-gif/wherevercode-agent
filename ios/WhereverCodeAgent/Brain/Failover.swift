import Foundation

public struct FailoverPolicy: Codable, Equatable, Sendable {
    public var maxSameBrainRetries: Int
    public var fallbackBrainIds: [String]

    public init(maxSameBrainRetries: Int = 1, fallbackBrainIds: [String] = []) {
        self.maxSameBrainRetries = maxSameBrainRetries
        self.fallbackBrainIds = fallbackBrainIds
    }
}

public final class FailoverRunner {
    public let policy: FailoverPolicy

    public init(policy: FailoverPolicy = FailoverPolicy()) {
        self.policy = policy
    }
}
