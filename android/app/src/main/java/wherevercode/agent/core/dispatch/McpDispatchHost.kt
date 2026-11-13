package wherevercode.agent.core.dispatch

import android.content.Context
import java.io.File
import wherevercode.agent.AgentDiscovery
import wherevercode.agent.core.control.ControlPlane
import wherevercode.agent.core.control.RoomControlAuditLog
import wherevercode.agent.core.control.RoomControlStore
import wherevercode.agent.core.security.FileAuditLog
import wherevercode.agent.core.security.RoomAuthorizationStore
import wherevercode.agent.core.security.RoomRevocationStore
import wherevercode.agent.core.task.RoomTaskStore
import wherevercode.agent.core.task.TaskDatabase
import wherevercode.agent.core.task.TaskScheduler
import wherevercode.agent.core.task.TaskStore

/**
 * App 内托管：用 App 现有的 Room 库（`TaskDatabase`，与聊天任务、
 * 控制面同一个库）组装派工服务。任务、控制状态、控制审计、撤销都
 * 落在原来的表里；本模块只多两份文件：`dispatch-state.json`（令牌
 * 哈希 / 任务归属 / 幂等 / 进度备注）和 `dispatch-audit.jsonl`
 * （追加式调用审计）。
 *
 * 默认只绑 127.0.0.1。手机在 NAT 后面，云端 Agent 连不进来，公网
 * 访问要靠出站反向隧道/中继（见 docs/mcp-dispatch-20261113.md）。
 *
 * 注意：调用方须在后台线程调用 [start]（Room 禁止主线程访问）。
 * 本分支没有把它接进 BrainBootstrap / 设置页（那是 Codex/Muse 的
 * 热点文件），接线点见文档。
 */
class McpDispatchHost(
    val service: McpDispatchService,
    val server: McpDispatchHttpServer,
) {
    fun start(): McpDispatchHost { server.start(); return this }
    fun stop() = server.stop()

    companion object {
        fun buildService(
            appContext: Context,
            taskDb: TaskDatabase,
            stateDir: File,
            taskStore: TaskStore = RoomTaskStore(taskDb),
            controlPlane: ControlPlane? = null,
            remoteTargetResolver: ((String?) -> wherevercode.agent.core.mcp.RemoteTarget?)? = null,
            remoteControlSender: wherevercode.agent.core.mcp.RemoteControlSender? = null,
        ): McpDispatchService {
            val controlStore = RoomControlStore(taskDb)
            val revocationStore = RoomRevocationStore(taskDb)
            val plane = controlPlane ?: ControlPlane(
                taskStore,
                RoomAuthorizationStore(taskDb),
                revocationStore,
                controlStore = controlStore,
                auditLog = RoomControlAuditLog(taskDb.controlAuditDao()),
                actor = "mcp-dispatch",
                remoteTargetResolver = remoteTargetResolver,
                remoteControlSender = remoteControlSender,
            )
            val state = FileDispatchStateStore(File(stateDir, "dispatch-state.json"))
            val scheduler = TaskScheduler(taskStore, AgentDiscovery(appContext), controlStore)
            return McpDispatchService(
                taskStore = taskStore,
                controlPlane = plane,
                controlStore = controlStore,
                revocationStore = revocationStore,
                claimPort = SchedulerClaimPort(scheduler),
                tokens = DispatchTokenRegistry(state),
                state = state,
                audit = FileAuditLog(File(stateDir, "dispatch-audit.jsonl")),
            )
        }

        fun create(
            appContext: Context,
            taskDb: TaskDatabase,
            stateDir: File,
            port: Int = 0,
            bindHost: String = "127.0.0.1",
            controlPlane: ControlPlane? = null,
            remoteTargetResolver: ((String?) -> wherevercode.agent.core.mcp.RemoteTarget?)? = null,
            remoteControlSender: wherevercode.agent.core.mcp.RemoteControlSender? = null,
        ): McpDispatchHost {
            val service = buildService(
                appContext, taskDb, stateDir,
                controlPlane = controlPlane,
                remoteTargetResolver = remoteTargetResolver,
                remoteControlSender = remoteControlSender,
            )
            return McpDispatchHost(service, McpDispatchHttpServer(service, bindHost = bindHost, requestedPort = port))
        }
    }
}
