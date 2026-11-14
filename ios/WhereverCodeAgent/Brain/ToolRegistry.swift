import Foundation

public enum ToolRegistryRules {
    public static let roundOwner = "BrainEngine"
    public static let maxCapturedChars = 12_000
    public static let readOnlyCommands: Set<String> = ["ls", "cat", "head", "tail", "git status", "git log", "git diff", "pwd"]

    public static func isReadOnlyRun(_ argv: [String]?) -> Bool {
        guard let first = argv?.first else { return false }
        return readOnlyCommands.contains(first)
    }
}

public protocol ToolRegistry: AnyObject {
    var registeredToolNames: Set<String> { get }
    func execute(tool: String, args: [String: String]) async -> (ok: Bool, output: String)
}

public final class DefaultiOSToolRegistry: ToolRegistry {
    public let registeredToolNames: Set<String> = [
        "list_files",
        "read_file",
        "write_file",
        "web_search",
        "create_local_task",
        "query_task_status"
    ]

    public init() {}

    public func execute(tool: String, args: [String: String]) async -> (ok: Bool, output: String) {
        switch tool {
        case "list_files":
            let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first?.path ?? ""
            let items = (try? FileManager.default.contentsOfDirectory(atPath: docs)) ?? []
            return (true, "iOS 沙盒 Documents 目录文件 (\(items.count))：\(items.joined(separator: ", "))")
        default:
            return (false, "iOS 尚未接入工作区执行工具 (\(tool))：受 iOS 沙盒限制，本地不支持任意进程执行；请通过远端 Hand 设备或云端 MCP Agent 执行。")
        }
    }
}
