// WhereverCode Agent — local data layer tests (checklist group A)
//
// Proves the Swift data layer implements the Android baseline
// contracts (main @4496658e): ConversationStore + Room mapping
// rules, TaskStates transitions/canonical mapping, the local task
// store with its audit trail, the Agent registry's JSON store
// shape, the project store's deterministic location ids and slug
// rules, and the key-value stores behind cursors/prefs.

import Foundation
import XCTest
@testable import WhereverCodeAgent

final class DataLayerTests: XCTestCase {

    private func makeData() throws -> LocalData {
        try LocalData.inMemory()
    }

    private func message(
        _ id: String, conversation: String, role: String = "user",
        content: String = "hi", at seconds: Int64, taskId: String? = nil
    ) -> ConversationMessage {
        ConversationMessage(
            id: id, conversationId: conversation, role: role, content: content,
            source: "test", createdAtEpochSeconds: seconds, taskId: taskId)
    }

    // MARK: - Conversations

    func testConversationCreateGetListAndBrainSwitch() throws {
        let data = try makeData()
        let first = try data.conversations.createConversation(brainId: "brain-a", title: "第一")
        let second = try data.conversations.createConversation(brainId: nil, title: "第二")
        XCTAssertEqual(try data.conversations.getConversation(id: first.id)?.title, "第一")
        XCTAssertEqual(try data.conversations.listConversations().count, 2)
        try data.conversations.updateConversationBrain(id: second.id, brainId: "brain-b")
        XCTAssertEqual(try data.conversations.getConversation(id: second.id)?.brainId, "brain-b")
    }

    func testGetOrCreateReusesCallerId() throws {
        let data = try makeData()
        let created = try data.conversations.getOrCreateConversation(id: "ui-session-1", brainId: nil, title: "会话")
        XCTAssertEqual(created.id, "ui-session-1")
        let again = try data.conversations.getOrCreateConversation(id: "ui-session-1", brainId: "x", title: "别的标题")
        XCTAssertEqual(again.id, "ui-session-1")
        XCTAssertEqual(try data.conversations.listConversations().count, 1)
    }

    func testMessagesOrderingLimitAndTaskBinding() throws {
        let data = try makeData()
        let conversation = try data.conversations.createConversation(brainId: nil, title: "t")
        try data.conversations.addMessage(message("m2", conversation: conversation.id, at: 200))
        try data.conversations.addMessage(message("m1", conversation: conversation.id, at: 100))
        try data.conversations.addMessage(message("m3", conversation: conversation.id, at: 300, taskId: "task-9"))
        let all = try data.conversations.listMessages(conversationId: conversation.id)
        XCTAssertEqual(all.map(\.id), ["m1", "m2", "m3"])
        let lastTwo = try data.conversations.listMessages(conversationId: conversation.id, limit: 2)
        XCTAssertEqual(lastTwo.map(\.id), ["m2", "m3"])
        XCTAssertEqual(try data.conversations.listMessagesForTask(taskId: "task-9").map(\.id), ["m3"])
    }

    func testAddMessageToUnknownConversationThrows() throws {
        let data = try makeData()
        XCTAssertThrowsError(try data.conversations.addMessage(message("x", conversation: "nope", at: 1))) { error in
            XCTAssertEqual(error as? ConversationStoreError, .unknownConversation("nope"))
        }
    }

    func testInvalidRoleRejected() throws {
        let data = try makeData()
        let conversation = try data.conversations.createConversation(brainId: nil, title: "t")
        XCTAssertThrowsError(try data.conversations.addMessage(
            message("x", conversation: conversation.id, role: "robot", at: 1))) { error in
            XCTAssertEqual(error as? ConversationStoreError, .invalidRole("robot"))
        }
    }

    func testDeleteConversationCascadesMessages() throws {
        let data = try makeData()
        let conversation = try data.conversations.createConversation(brainId: nil, title: "t")
        try data.conversations.addMessage(message("m1", conversation: conversation.id, at: 1))
        XCTAssertTrue(try data.conversations.deleteConversation(id: conversation.id))
        XCTAssertEqual(try data.conversations.listMessages(conversationId: conversation.id).count, 0)
        XCTAssertFalse(try data.conversations.deleteConversation(id: conversation.id))
    }

    func testToolCallsRoundTripAndCorruptCellTolerance() throws {
        let data = try makeData()
        let conversation = try data.conversations.createConversation(brainId: nil, title: "t")
        var withCalls = message("a1", conversation: conversation.id, role: "assistant", at: 10)
        withCalls.toolCalls = [ChatToolCall(id: "call-1", name: "read_file", argumentsJson: "{\"path\":\"a.txt\"}")]
        try data.conversations.addMessage(withCalls)
        let read = try data.conversations.listMessages(conversationId: conversation.id)
        XCTAssertEqual(read.first?.toolCalls.first?.name, "read_file")
        // Corrupt the stored JSON directly: reads must degrade to
        // an empty call list, never fail (baseline rule).
        try data.database.execute(
            "UPDATE conversation_messages SET toolCallsJson = '{broken' WHERE id = 'a1'")
        let degraded = try data.conversations.listMessages(conversationId: conversation.id)
        XCTAssertEqual(degraded.first?.toolCalls.count, 0)
        XCTAssertEqual(degraded.first?.content, "hi")
    }

    func testPersistenceAcrossReopen() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("wc-data-test-\(UUID().uuidString)")
        let path = directory.appendingPathComponent("wherevercode-tasks.db").path
        defer { try? FileManager.default.removeItem(at: directory) }
        do {
            let db = try SQLiteDatabase(path: path)
            let data = try LocalData(database: db)
            let conversation = try data.conversations.createConversation(brainId: nil, title: "持久")
            try data.conversations.addMessage(message("m1", conversation: conversation.id, at: 5))
        }
        let reopened = try LocalData(database: SQLiteDatabase(path: path))
        XCTAssertEqual(try reopened.conversations.listConversations().first?.title, "持久")
        XCTAssertEqual(try reopened.conversations.listMessages(conversationId: try XCTUnwrap(
            reopened.conversations.listConversations().first?.id)).count, 1)
    }

    // MARK: - TaskStates

    func testTaskStatesLegalAndIllegalTransitions() throws {
        XCTAssertNoThrow(try TaskStates.checkTransition(fromState: nil, toState: "queued"))
        XCTAssertThrowsError(try TaskStates.checkTransition(fromState: nil, toState: "working"))
        XCTAssertNoThrow(try TaskStates.checkTransition(fromState: "queued", toState: "assigned"))
        XCTAssertNoThrow(try TaskStates.checkTransition(fromState: "review", toState: "rework"))
        XCTAssertThrowsError(try TaskStates.checkTransition(fromState: "review", toState: "working")) { error in
            XCTAssertEqual(error as? TaskStates.TaskStateError, .invalidTransition(from: "review", to: "working"))
        }
        XCTAssertThrowsError(try TaskStates.checkTransition(fromState: "completed", toState: "queued"))
        XCTAssertThrowsError(try TaskStates.checkTransition(fromState: "bogus", toState: "queued")) { error in
            XCTAssertEqual(error as? TaskStates.TaskStateError, .unknownCanonicalState("bogus"))
        }
    }

    func testTaskStatesCanonicalMapping() throws {
        XCTAssertEqual(try TaskStates.canonical(system: "task_assignment", rawState: "waiting").state, "queued")
        XCTAssertEqual(try TaskStates.canonical(system: "task_assignment", rawState: "stopped").state, "cancelled")
        XCTAssertEqual(try TaskStates.canonical(system: "conversation_task", rawState: "running").state, "working")
        XCTAssertEqual(try TaskStates.canonical(system: "execution_host", rawState: "cancelled").state, "cancelled")
        XCTAssertThrowsError(try TaskStates.canonical(system: "nope", rawState: "queued"))
        XCTAssertThrowsError(try TaskStates.canonical(system: "task_assignment", rawState: "nope"))
        XCTAssertTrue(TaskStates.isTerminal("failed"))
        XCTAssertFalse(TaskStates.isTerminal("working"))
        XCTAssertTrue(TaskStates.isClaimable("rework"))
        XCTAssertTrue(TaskStates.isClaimable("requeued"))
        XCTAssertFalse(TaskStates.isClaimable("assigned"))
    }

    // MARK: - Local task store

    func testLocalTaskLifecycleWithAuditTrail() throws {
        let data = try makeData()
        var task = LocalTask(taskId: "t-1")
        task.title = "实现数据层"
        task.projectId = "p-1"
        let created = try data.tasks.createTask(task)
        XCTAssertEqual(created.state, "queued")
        XCTAssertEqual(created.version, 1)
        let assigned = try data.tasks.transition(taskId: "t-1", to: "assigned")
        XCTAssertEqual(assigned.version, 2)
        let working = try data.tasks.transition(taskId: "t-1", to: "working")
        XCTAssertNotNil(working.startedAt)
        XCTAssertThrowsError(try data.tasks.transition(taskId: "t-1", to: "completed"))
        try data.tasks.transition(taskId: "t-1", to: "review")
        let done = try data.tasks.transition(taskId: "t-1", to: "completed")
        XCTAssertNotNil(done.finishedAt)
        let events = try data.tasks.listEvents(taskId: "t-1")
        XCTAssertEqual(events.map(\.kind), ["created", "state", "state", "state", "state"])
        XCTAssertEqual(events.last?.toState, "completed")
    }

    func testLocalTaskCreateRejectsNonQueuedAndDuplicates() throws {
        let data = try makeData()
        var bad = LocalTask(taskId: "t-bad")
        bad.state = "working"
        XCTAssertThrowsError(try data.tasks.createTask(bad))
        try data.tasks.createTask(LocalTask(taskId: "t-1"))
        XCTAssertThrowsError(try data.tasks.createTask(LocalTask(taskId: "t-1"))) { error in
            XCTAssertEqual(error as? LocalTaskStoreError, .duplicateTask("t-1"))
        }
        XCTAssertThrowsError(try data.tasks.transition(taskId: "ghost", to: "assigned")) { error in
            XCTAssertEqual(error as? LocalTaskStoreError, .unknownTask("ghost"))
        }
    }

    func testLocalTaskListFiltersAndUpdateBumpsVersion() throws {
        let data = try makeData()
        var first = LocalTask(taskId: "t-1"); first.projectId = "p-1"; first.sessionId = "s-1"
        var second = LocalTask(taskId: "t-2"); second.projectId = "p-2"
        try data.tasks.createTask(first)
        try data.tasks.createTask(second)
        XCTAssertEqual(try data.tasks.listTasks().count, 2)
        XCTAssertEqual(try data.tasks.listTasks(projectId: "p-1").map(\.taskId), ["t-1"])
        XCTAssertEqual(try data.tasks.listTasks(sessionId: "s-1").map(\.taskId), ["t-1"])
        var edited = try XCTUnwrap(data.tasks.getTask(taskId: "t-2"))
        edited.title = "改名"
        edited.dependencies = ["t-1"]
        try data.tasks.updateTask(edited)
        let reread = try XCTUnwrap(data.tasks.getTask(taskId: "t-2"))
        XCTAssertEqual(reread.title, "改名")
        XCTAssertEqual(reread.dependencies, ["t-1"])
        XCTAssertEqual(reread.version, 2)
    }

    // MARK: - Agent registry

    func testAgentRegistryCrudToggleAndPlatformLookup() throws {
        let data = try makeData()
        let platform = AgentRecord.platformAgent(
            agentId: "platform-main", displayName: "平台",
            platformAgentId: "ios-app", platformHost: "v5.cococolo.xyz")
        try data.agents.saveAgent(platform)
        var cli = AgentRecord(
            agentId: "codex-mac", displayName: "Codex", kind: .device,
            access: .cli, brainKind: "MEMBERSHIP", adapterId: "cli-agent")
        cli.deviceId = "mac-1"
        try data.agents.saveAgent(cli)
        XCTAssertEqual(try data.agents.listAgents().count, 2)
        XCTAssertEqual(try data.agents.platformAgent()?.agentId, "platform-main")
        XCTAssertEqual(try data.agents.platformAgent()?.platformChannelId, nil)
        try data.agents.setEnabled(agentId: "codex-mac", enabled: false)
        XCTAssertEqual(try data.agents.getAgent(agentId: "codex-mac")?.enabled, false)
        try data.agents.deleteAgent(agentId: "codex-mac")
        XCTAssertEqual(try data.agents.listAgents().count, 1)
        // Secrets rule: only references are stored, never values.
        XCTAssertNil(try data.agents.getAgent(agentId: "platform-main")?.authRef)
    }

    // MARK: - Projects

    func testProjectSlugAndLocationIdRules() {
        XCTAssertEqual(slugProjectId("WhereverCode Agent"), "wherevercode-agent")
        XCTAssertEqual(slugProjectId("  Hello   World! "), "hello-world")
        XCTAssertEqual(slugProjectId("纯中文名"), "project")
        XCTAssertEqual(normalizeFolderPath(" /a/b/ "), "/a/b")
        XCTAssertEqual(normalizeFolderPath("/"), "/")
        let id = projectLocationId(projectId: "p", hostId: "mac", workspacePath: "/a/b/")
        XCTAssertTrue(id.hasPrefix("loc-"))
        XCTAssertEqual(id.count, 4 + 32)
        XCTAssertEqual(id, projectLocationId(projectId: "p", hostId: "mac", workspacePath: "/a/b"))
        XCTAssertNotEqual(id, projectLocationId(projectId: "p", hostId: "mac", workspacePath: "/a/c"))
        XCTAssertTrue(isValidLocalWorkspacePath("a/b"))
        XCTAssertFalse(isValidLocalWorkspacePath("/a/b"))
        XCTAssertFalse(isValidLocalWorkspacePath("a/../b"))
        XCTAssertFalse(isValidLocalWorkspacePath("a//b"))
    }

    func testProjectStoreHomesAndActiveGitHome() throws {
        let data = try makeData()
        let project = Project(projectId: slugProjectId("Demo Repo"), name: "Demo Repo", repoUrl: "https://github.com/example/demo")
        try data.projects.saveProject(project)
        let deviceHome = ProjectLocation(projectId: project.projectId, hostId: "mac-1", workspacePath: "/Users/x/demo/", homeKind: "DEVICE")
        try data.projects.addLocation(deviceHome)
        var gitHome = ProjectLocation(projectId: project.projectId, hostId: "github", workspacePath: "example/demo", homeKind: "GITHUB")
        gitHome.sourceType = "GIT_HOST"
        try data.projects.addLocation(gitHome)
        var loaded = try XCTUnwrap(data.projects.getProject(projectId: project.projectId))
        XCTAssertEqual(loaded.homes.count, 2)
        XCTAssertEqual(loaded.activeHome?.homeKind, "DEVICE")
        XCTAssertNil(activeGitHome(loaded))
        try data.projects.setActiveHome(projectId: project.projectId, locationId: gitHome.locationId)
        loaded = try XCTUnwrap(data.projects.getProject(projectId: project.projectId))
        XCTAssertEqual(loaded.activeHome?.homeKind, "GITHUB")
        XCTAssertEqual(activeGitHome(loaded)?.1.workspacePath, "example/demo")
        try data.projects.deleteProject(projectId: project.projectId)
        XCTAssertNil(try data.projects.getProject(projectId: project.projectId))
    }

    // MARK: - Key-value stores

    func testKeyValueCursorsAndChatPrefs() throws {
        let data = try makeData()
        try data.kv.set("k", "v")
        XCTAssertEqual(try data.kv.get("k"), "v")
        try data.kv.set("k", nil)
        XCTAssertNil(try data.kv.get("k"))
        try data.cursors.setLastSeenMessageId(channelId: "chan-1", messageId: "msg-42")
        XCTAssertEqual(try data.cursors.lastSeenMessageId(channelId: "chan-1"), "msg-42")
        try data.chatPrefs.setSessionProjects(["s-1": "p-1"])
        XCTAssertEqual(try data.chatPrefs.sessionProjects(), ["s-1": "p-1"])
        data.chatPrefs.currentSessionId = "s-1"
        XCTAssertEqual(data.chatPrefs.currentSessionId, "s-1")
    }
}
