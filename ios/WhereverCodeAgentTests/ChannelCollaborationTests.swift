import XCTest
@testable import WhereverCodeAgent

final class ChannelCollaborationTests: XCTestCase {

    func testChannelMemberManagerPersistence() throws {
        let channelId = "ch-unit-test-members"
        
        // 1. 验证默认成员加载
        let initialMembers = ChannelMemberManager.loadMembers(channelId: channelId)
        XCTAssertGreaterThanOrEqual(initialMembers.count, 2)
        XCTAssertTrue(initialMembers.contains { $0.id == "user-me" })
        XCTAssertTrue(initialMembers.contains { $0.name == "Codex" })
        
        // 2. 邀请新 Agent 入驻
        let newAgent = AgentRecord(
            agentId: "agent-test-worker",
            displayName: "Test Worker Agent",
            kind: .cloud,
            access: .api,
            brainKind: "MODEL_API",
            lastTestSummary: "Verified",
            memberConnectionState: "CONNECTED"
        )
        let updatedMembers = ChannelMemberManager.addAgentMember(newAgent, channelId: channelId)
        XCTAssertTrue(updatedMembers.contains { $0.name == "Test Worker Agent" })
        
        // 3. 验证持久化重新加载
        let reloaded = ChannelMemberManager.loadMembers(channelId: channelId)
        XCTAssertEqual(reloaded.count, updatedMembers.count)
        XCTAssertTrue(reloaded.contains { $0.name == "Test Worker Agent" })
        
        // 4. 移除成员
        let memberToRemove = reloaded.first { $0.name == "Test Worker Agent" }!
        let afterRemoval = ChannelMemberManager.removeMember(memberId: memberToRemove.id, channelId: channelId)
        XCTAssertFalse(afterRemoval.contains { $0.name == "Test Worker Agent" })
    }

    func testChannelMultiAgentDispatchClaimReviewReworkLifecycle() throws {
        let taskStore = LocalData.shared.tasks
        let channelId = "ch-collab-e2e"
        let taskId = "task-collab-\(UUID().uuidString.prefix(6))"

        // 1. 派工创建：queued
        let task = LocalTask(
            taskId: taskId,
            sessionId: channelId,
            title: "实现多 Agent 协同代码审查机制",
            description: "来自频道自然语言派发任务",
            state: "queued",
            assignedAgentId: "codex",
            priority: 1
        )
        let created = try taskStore.createTask(task)
        XCTAssertEqual(created.state, "queued")

        // 2. Agent 认领：queued -> assigned
        let assigned = try taskStore.transition(taskId: taskId, to: "assigned", note: "Agent Codex 认领任务")
        XCTAssertEqual(assigned.state, "assigned")

        // 3. 开始执行：assigned -> working
        let working = try taskStore.transition(taskId: taskId, to: "working", note: "Agent Codex 开始执行")
        XCTAssertEqual(working.state, "working")
        XCTAssertNotNil(working.startedAt)

        // 4. 提交审查：working -> review
        let review = try taskStore.transition(taskId: taskId, to: "review", note: "Agent 提交产物，等待人工审查")
        XCTAssertEqual(review.state, "review")

        // 5. 审查不通过，打回返工：review -> rework -> assigned -> working
        let rework = try taskStore.transition(taskId: taskId, to: "rework", note: "发现逻辑漏洞，打回返工")
        XCTAssertEqual(rework.state, "rework")

        let reAssigned = try taskStore.transition(taskId: taskId, to: "assigned", note: "重新指派给 Codex")
        XCTAssertEqual(reAssigned.state, "assigned")

        let reWorking = try taskStore.transition(taskId: taskId, to: "working", note: "Codex 进入返工修复阶段")
        XCTAssertEqual(reWorking.state, "working")

        // 6. 二次提交审查与最终批准交付：working -> review -> completed
        _ = try taskStore.transition(taskId: taskId, to: "review", note: "返工完毕，重新提交审查")
        let completed = try taskStore.transition(taskId: taskId, to: "completed", note: "项目主管批准交付")
        XCTAssertEqual(completed.state, "completed")
        XCTAssertNotNil(completed.finishedAt)

        // 7. 离线恢复验证：按 sessionId 查询完整任务与审计
        let channelTasks = try taskStore.listTasks(sessionId: channelId)
        XCTAssertTrue(channelTasks.contains { $0.taskId == taskId })
        let finalTask = channelTasks.first { $0.taskId == taskId }!
        XCTAssertEqual(finalTask.state, "completed")
    }

    func testMultiAgentConcurrentTasksInSameChannel() throws {
        let taskStore = LocalData.shared.tasks
        let channelId = "ch-concurrent-collab"
        let task1Id = "task-codex-\(UUID().uuidString.prefix(6))"
        let task2Id = "task-claude-\(UUID().uuidString.prefix(6))"

        // Agent 1 (Codex) 认领任务 1
        _ = try taskStore.createTask(LocalTask(
            taskId: task1Id, sessionId: channelId, title: "Codex 后端优化", state: "queued", assignedAgentId: "codex"
        ))
        _ = try taskStore.transition(taskId: task1Id, to: "assigned")
        _ = try taskStore.transition(taskId: task1Id, to: "working")

        // Agent 2 (Claude) 认领任务 2
        _ = try taskStore.createTask(LocalTask(
            taskId: task2Id, sessionId: channelId, title: "Claude UI 审查", state: "queued", assignedAgentId: "claude-code"
        ))
        _ = try taskStore.transition(taskId: task2Id, to: "assigned")
        _ = try taskStore.transition(taskId: task2Id, to: "working")

        // 验证两任务并发处于 working 状态且互不干扰
        let t1 = try taskStore.getTask(taskId: task1Id)
        let t2 = try taskStore.getTask(taskId: task2Id)
        XCTAssertEqual(t1?.state, "working")
        XCTAssertEqual(t1?.assignedAgentId, "codex")
        XCTAssertEqual(t2?.state, "working")
        XCTAssertEqual(t2?.assignedAgentId, "claude-code")

        // Agent 1 提前提交审查，不影响 Agent 2 继续执行
        _ = try taskStore.transition(taskId: task1Id, to: "review")
        XCTAssertEqual((try taskStore.getTask(taskId: task1Id))?.state, "review")
        XCTAssertEqual((try taskStore.getTask(taskId: task2Id))?.state, "working")
    }
}
