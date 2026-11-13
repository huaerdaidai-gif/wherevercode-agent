// WhereverCode Agent — iOS MVP
//
// Reserved capability boundaries: approval, pause/resume/stop and
// retargeting. These exist in the product design and in the Android
// app, but they are NOT implemented in this iOS MVP — there is no
// server tool for them yet and no local stand-in.
//
// This file is the single place that records that boundary, so no
// other code can silently fake these actions. Every reserved feature
// throws `FeatureError.notImplemented`; the UI shows the same state
// as a disabled, explicitly labelled control — never as a working one.

import Foundation

/// A feature the product design reserves but this build does not do.
public enum ReservedFeature: String, CaseIterable {
    /// A human approves a plan / result before the agent proceeds.
    case approval
    /// Pause a running task (resumable).
    case pauseTask
    /// Resume a paused task.
    case resumeTask
    /// Stop a running task outright.
    case stopTask
    /// Change a task's goal/target mid-flight.
    case retargetTask
    /// Device management (add / pair / remove a device).
    case deviceManagement
    /// Brain chat: talk to the configured brain directly.
    case brainChat
    /// One-to-one chat with a single agent.
    case singleAgentChat

    public var label: String {
        switch self {
        case .approval: return "审批"
        case .pauseTask: return "暂停任务"
        case .resumeTask: return "恢复任务"
        case .stopTask: return "停止任务"
        case .retargetTask: return "改目标"
        case .deviceManagement: return "设备管理"
        case .brainChat: return "大脑对话"
        case .singleAgentChat: return "单兵对话"
        }
    }

    /// Short honest note shown next to the disabled control.
    public var note: String {
        switch self {
        case .approval:
            return "未实现：审批流程尚未接入，任务提交前请在频道里人工确认。"
        case .pauseTask, .resumeTask, .stopTask:
            return "未实现：任务暂停 / 恢复 / 停止尚未接入，当前只能领取、汇报、交活或放手。"
        case .retargetTask:
            return "未实现：改目标尚未接入，请放手任务后由调度重新派发。"
        case .deviceManagement:
            return "未实现：设备添加与配对尚未接入，设备需先在 Android 端或平台侧添加。"
        case .brainChat:
            return "未实现：大脑对话尚未接入，先用团队频道与任务页。"
        case .singleAgentChat:
            return "未实现：单兵对话尚未接入，先用团队频道与任务页。"
        }
    }
}

public enum FeatureError: Error, Equatable {
    case notImplemented(ReservedFeature)

    public var message: String {
        switch self {
        case .notImplemented(let feature):
            return "\(feature.label)未实现。\(feature.note)"
        }
    }
}

/// The single gate for reserved actions. Calling it always fails
/// loudly with `.notImplemented` — it exists so future work has one
/// place to replace, and current code cannot pretend the action ran.
public enum CapabilityGate {
    public static func require(_ feature: ReservedFeature) throws -> Never {
        throw FeatureError.notImplemented(feature)
    }
}
