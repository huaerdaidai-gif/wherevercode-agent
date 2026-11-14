import Foundation
import SQLite3

/// Minimal SQLite3 wrapper for the local data layer.
///
/// Mirrors the role Room plays on Android (baseline
/// `wherevercode-tasks.db`): one shared database file, synchronous
/// DAO-style calls, serialized access. Android's rule applies here
/// too: callers use these APIs off the main thread (the stores are
/// synchronous by design, exactly like the Room stores).
public enum SQLiteValue: Equatable {
    case null
    case int(Int64)
    case double(Double)
    case text(String)
    case blob(Data)

    public var textValue: String? {
        if case .text(let value) = self { return value }
        if case .int(let value) = self { return String(value) }
        return nil
    }

    public var intValue: Int64? {
        if case .int(let value) = self { return value }
        if case .text(let value) = self { return Int64(value) }
        return nil
    }

    public var isNull: Bool {
        if case .null = self { return true }
        return false
    }
}

public enum SQLiteError: Error, CustomStringConvertible {
    case open(String)
    case prepare(String)
    case step(String)
    case closed

    public var description: String {
        switch self {
        case .open(let message): return "sqlite open failed: \(message)"
        case .prepare(let message): return "sqlite prepare failed: \(message)"
        case .step(let message): return "sqlite step failed: \(message)"
        case .closed: return "sqlite database is closed"
        }
    }
}

public final class SQLiteDatabase {
    private var handle: OpaquePointer?
    private let queue = DispatchQueue(label: "wherevercode.agent.sqlite")
    private let queueKey = DispatchSpecificKey<Void>()
    private var transactionDepth = 0

    /// Runs `work` on the serial queue, inline when already there
    /// (keeps transactions reentrant: store calls inside a
    /// transaction body must not deadlock the queue).
    private func onQueue<T>(_ work: () throws -> T) rethrows -> T {
        if DispatchQueue.getSpecific(key: queueKey) != nil {
            return try work()
        }
        return try queue.sync(execute: work)
    }

    /// Opens (creating if needed) the database at `path`.
    /// `":memory:"` gives a private in-memory database (tests).
    public init(path: String) throws {
        if path != ":memory:" {
            let directory = (path as NSString).deletingLastPathComponent
            if !directory.isEmpty {
                try FileManager.default.createDirectory(
                    atPath: directory, withIntermediateDirectories: true)
            }
        }
        var opened: OpaquePointer?
        let rc = sqlite3_open_v2(path, &opened, SQLITE_OPEN_CREATE | SQLITE_OPEN_READWRITE | SQLITE_OPEN_FULLMUTEX, nil)
        guard rc == SQLITE_OK, let opened else {
            let message = opened.map { String(cString: sqlite3_errmsg($0)) } ?? "unknown"
            if let opened { sqlite3_close(opened) }
            throw SQLiteError.open(message)
        }
        handle = opened
        queue.setSpecific(key: queueKey, value: ())
        try execute("PRAGMA journal_mode = WAL")
        try execute("PRAGMA foreign_keys = ON")
    }

    public static func inMemory() throws -> SQLiteDatabase {
        try SQLiteDatabase(path: ":memory:")
    }

    deinit {
        if let handle { sqlite3_close(handle) }
    }

    private func lastErrorMessage() -> String {
        guard let handle else { return "closed" }
        return String(cString: sqlite3_errmsg(handle))
    }

    private func bind(_ statement: OpaquePointer?, _ params: [SQLiteValue]) throws {
        for (index, param) in params.enumerated() {
            let position = Int32(index + 1)
            let rc: Int32
            switch param {
            case .null:
                rc = sqlite3_bind_null(statement, position)
            case .int(let value):
                rc = sqlite3_bind_int64(statement, position, value)
            case .double(let value):
                rc = sqlite3_bind_double(statement, position, value)
            case .text(let value):
                rc = sqlite3_bind_text(statement, position, value, -1, SQLITE_TRANSIENT)
            case .blob(let value):
                rc = value.withUnsafeBytes { buffer in
                    sqlite3_bind_blob(statement, position, buffer.baseAddress, Int32(value.count), SQLITE_TRANSIENT)
                }
            }
            guard rc == SQLITE_OK else { throw SQLiteError.step(lastErrorMessage()) }
        }
    }

    private func withStatement<T>(
        _ sql: String, _ params: [SQLiteValue], _ body: (OpaquePointer) throws -> T
    ) throws -> T {
        guard let handle else { throw SQLiteError.closed }
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(handle, sql, -1, &statement, nil) == SQLITE_OK, let statement else {
            throw SQLiteError.prepare(lastErrorMessage())
        }
        defer { sqlite3_finalize(statement) }
        try bind(statement, params)
        return try body(statement)
    }

    /// Runs a statement that returns no rows.
    public func execute(_ sql: String, _ params: [SQLiteValue] = []) throws {
        try onQueue {
            try withStatement(sql, params) { statement in
                let rc = sqlite3_step(statement)
                guard rc == SQLITE_DONE || rc == SQLITE_ROW else {
                    throw SQLiteError.step(lastErrorMessage())
                }
            }
        }
    }

    /// Runs a query and returns rows as column-name → value maps.
    public func query(_ sql: String, _ params: [SQLiteValue] = []) throws -> [[String: SQLiteValue]] {
        try onQueue {
            try withStatement(sql, params) { statement in
                var rows: [[String: SQLiteValue]] = []
                while true {
                    let rc = sqlite3_step(statement)
                    if rc == SQLITE_DONE { break }
                    guard rc == SQLITE_ROW else { throw SQLiteError.step(lastErrorMessage()) }
                    var row: [String: SQLiteValue] = [:]
                    let count = sqlite3_column_count(statement)
                    for index in 0..<count {
                        let name = String(cString: sqlite3_column_name(statement, index))
                        switch sqlite3_column_type(statement, index) {
                        case SQLITE_INTEGER:
                            row[name] = .int(sqlite3_column_int64(statement, index))
                        case SQLITE_FLOAT:
                            row[name] = .double(sqlite3_column_double(statement, index))
                        case SQLITE_TEXT:
                            row[name] = .text(String(cString: sqlite3_column_text(statement, index)))
                        case SQLITE_BLOB:
                            let bytes = sqlite3_column_blob(statement, index)
                            let length = Int(sqlite3_column_bytes(statement, index))
                            row[name] = .blob(bytes.map { Data(bytes: $0, count: length) } ?? Data())
                        default:
                            row[name] = .null
                        }
                    }
                    rows.append(row)
                }
                return rows
            }
        }
    }

    /// Runs `body` inside a transaction; nested calls join the outer
    /// transaction (same shape as Room's runInTransaction usage).
    public func transaction<T>(_ body: () throws -> T) throws -> T {
        try onQueue {
            if transactionDepth == 0 {
                try execute("BEGIN IMMEDIATE")
            }
            transactionDepth += 1
            do {
                let value = try body()
                transactionDepth -= 1
                if transactionDepth == 0 { try execute("COMMIT") }
                return value
            } catch {
                transactionDepth -= 1
                if transactionDepth == 0 { try? execute("ROLLBACK") }
                throw error
            }
        }
    }

    public var userVersion: Int32 {
        get { (try? query("PRAGMA user_version").first?["user_version"]?.intValue).map(Int32.init) ?? 0 }
        set { try? execute("PRAGMA user_version = \(newValue)") }
    }
}

// SQLITE_TRANSIENT is not exported to Swift as a constant on all
// SDKs; use the canonical destructor value.
private let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
