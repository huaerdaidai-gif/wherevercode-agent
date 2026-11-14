import Foundation

/// Skill-memory seam for the brain engine, from the Android baseline
/// `brain/SkillMemory.kt` on main @4496658e.
///
/// Scope note: the full skill store (Skill record, capture on task
/// completion, retrieval scoring, the SQLite implementation) is the
/// A8 batch. The engine only ever calls `summarizeForPrompt`: per
/// turn it pulls the skills relevant to the task text (project skills
/// first, global skills as backfill; none relevant -> "" so no tokens
/// are burned) into the context.
public protocol SkillStore {
    /// The relevant skills rendered as one prompt-ready block; ""
    /// when nothing is relevant.
    func summarizeForPrompt(projectId: String, taskText: String) -> String
}

public enum SkillMemory {
    /// Project id of the global (project-independent) skill scope.
    public static let globalProjectId = ""

    public static func projectIdOrGlobal(_ projectId: String?) -> String {
        projectId ?? globalProjectId
    }
}
