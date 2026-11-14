import Foundation
import SQLite3

public enum SQLiteValue: Equatable, Sendable {
    case int(Int64)
    case double(Double)
    case text(String)
    case blob(Data)
    case null

    public var stringValue: String? {
        if case .text(let s) = self { return s }
        return nil
    }

    public var int64Value: Int64? {
        if case .int(let v) = self { return v }
        return nil
    }
}

public enum SQLiteError: Error, LocalizedError, Equatable {
    case open(String)
    case prepare(String)
    case step(String)
    case closed

    public var errorDescription: String? {
        switch self {
        case .open(let msg): return "sqlite open failed: \(msg)"
        case .prepare(let msg): return "sqlite prepare failed: \(msg)"
        case .step(let msg): return "sqlite step failed: \(msg)"
        case .closed: return "sqlite database is closed"
        }
    }
}

public final class SQLiteDatabase {
    private var handle: OpaquePointer?
    private let queue = DispatchQueue(label: "wherevercode.agent.sqlite")
    private let queueKey = DispatchSpecificKey<Bool>()
    private var transactionDepth: Int = 0

    public init(path: String) throws {
        queue.setSpecific(key: queueKey, value: true)
        var db: OpaquePointer?
        let flags = SQLITE_OPEN_CREATE | SQLITE_OPEN_READWRITE | SQLITE_OPEN_FULLMUTEX
        if sqlite3_open_v2(path, &db, flags, nil) != SQLITE_OK {
            let msg = db.flatMap { String(cString: sqlite3_errmsg($0)) } ?? "unknown"
            if let db { sqlite3_close(db) }
            throw SQLiteError.open(msg)
        }
        self.handle = db
        try execute("PRAGMA journal_mode = WAL")
        try execute("PRAGMA foreign_keys = ON")
    }

    deinit {
        if let handle {
            sqlite3_close(handle)
        }
    }

    public func execute(_ sql: String, parameters: [SQLiteValue] = []) throws {
        _ = try query(sql, parameters: parameters)
    }

    public func query(_ sql: String, parameters: [SQLiteValue] = []) throws -> [[String: SQLiteValue]] {
        if DispatchQueue.getSpecific(key: queueKey) == true {
            return try queryLocked(sql, parameters: parameters)
        }
        return try queue.sync {
            try queryLocked(sql, parameters: parameters)
        }
    }

    public func inTransaction<T>(_ block: () throws -> T) throws -> T {
        if DispatchQueue.getSpecific(key: queueKey) == true {
            return try inTransactionLocked(block)
        }
        return try queue.sync {
            try inTransactionLocked(block)
        }
    }

    private func inTransactionLocked<T>(_ block: () throws -> T) throws -> T {
        if transactionDepth == 0 {
            try execute("BEGIN IMMEDIATE TRANSACTION")
        }
        transactionDepth += 1
        do {
            let result = try block()
            transactionDepth -= 1
            if transactionDepth == 0 {
                try execute("COMMIT")
            }
            return result
        } catch {
            transactionDepth -= 1
            if transactionDepth == 0 {
                try? execute("ROLLBACK")
            }
            throw error
        }
    }

    private func queryLocked(_ sql: String, parameters: [SQLiteValue]) throws -> [[String: SQLiteValue]] {
        guard let handle else { throw SQLiteError.closed }
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) != SQLITE_OK {
            throw SQLiteError.prepare(String(cString: sqlite3_errmsg(handle)))
        }
        defer { sqlite3_finalize(stmt) }

        for (index, param) in parameters.enumerated() {
            let idx = Int32(index + 1)
            switch param {
            case .int(let v):
                sqlite3_bind_int64(stmt, idx, v)
            case .double(let d):
                sqlite3_bind_double(stmt, idx, d)
            case .text(let s):
                let ns = s as NSString
                sqlite3_bind_text(stmt, idx, ns.utf8String, -1, unsafeBitCast(-1, to: sqlite3_destructor_type.self))
            case .blob(let data):
                _ = data.withUnsafeBytes { raw in
                    sqlite3_bind_blob(stmt, idx, raw.baseAddress, Int32(data.count), unsafeBitCast(-1, to: sqlite3_destructor_type.self))
                }
            case .null:
                sqlite3_bind_null(stmt, idx)
            }
        }

        var rows: [[String: SQLiteValue]] = []
        let colCount = sqlite3_column_count(stmt)
        while true {
            let rc = sqlite3_step(stmt)
            if rc == SQLITE_ROW {
                var row: [String: SQLiteValue] = [:]
                for c in 0..<colCount {
                    let name = String(cString: sqlite3_column_name(stmt, c))
                    let colType = sqlite3_column_type(stmt, c)
                    switch colType {
                    case SQLITE_INTEGER:
                        row[name] = .int(sqlite3_column_int64(stmt, c))
                    case SQLITE_FLOAT:
                        row[name] = .double(sqlite3_column_double(stmt, c))
                    case SQLITE_TEXT:
                        if let ptr = sqlite3_column_text(stmt, c) {
                            row[name] = .text(String(cString: ptr))
                        } else {
                            row[name] = .null
                        }
                    case SQLITE_BLOB:
                        let len = Int(sqlite3_column_bytes(stmt, c))
                        if let ptr = sqlite3_column_blob(stmt, c), len > 0 {
                            row[name] = .blob(Data(bytes: ptr, count: len))
                        } else {
                            row[name] = .blob(Data())
                        }
                    default:
                        row[name] = .null
                    }
                }
                rows.append(row)
            } else if rc == SQLITE_DONE {
                break
            } else {
                throw SQLiteError.step(String(cString: sqlite3_errmsg(handle)))
            }
        }
        return rows
    }
}
