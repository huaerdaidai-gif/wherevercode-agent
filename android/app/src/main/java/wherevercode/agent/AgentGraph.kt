package wherevercode.agent

import android.content.Context
import wherevercode.agent.brain.ApprovalGate
import wherevercode.agent.brain.siwc.BrainBootstrap
import wherevercode.agent.core.mcp.RemoteControlSender
import wherevercode.agent.core.mcp.RemoteTarget
import wherevercode.agent.core.project.Project
import wherevercode.agent.core.project.ProjectLocation
import wherevercode.agent.core.task.PipelineAgentCaller
import wherevercode.agent.core.task.PipelineOrchestrator

/**
 * 2026-11-09：进程级依赖图。
 *
 * 为什么必须是进程级：
 * - 旧实现每次 Activity.onCreate 都 `BrainBootstrap.build()`，旋转屏幕就会
 *   造出第二个 `FileWorkspaceAdapter`，与正在执行的旧轮次抢同一个工作区锁；
 *   同时启动恢复会扫到旧轮次目录（把活着的轮次当成遗留）。
 * - 现在整个进程只构建一次：一个工作区适配器、一个写轮次状态机、一个
 *   引擎、一个 Python 运行时；Activity 旋转只 attach/detach 审批门。
 *
 * 失败不缓存：构建抛异常时下次调用会重试（用户可回到界面再试）。
 */
object AgentGraph {

    @Volatile
    private var components: BrainBootstrap.BrainComponents? = null

    fun getOrBuild(
        context: Context,
        approvalGate: ApprovalGate,
        /**
         * 阶段 B（V5 紧急开发）：远端控制接线（可选；只在首次构建时
         * 生效，构建后再次调用直接返回已建好的 components）。
         */
        remoteTargetResolver: ((String?) -> RemoteTarget?)? = null,
        remoteControlSender: RemoteControlSender? = null,
        /**
         * git 家（2026-11-11）：当前聊天项目的 git 家（可选；
         * 只在首次构建时生效）。由调用方按打开的项目提供。
         */
        gitHostProjectContext: (() -> Pair<Project, ProjectLocation>?)? = null,
        /**
         * V5 跨设备开发（2026-11-12，A1/D5）：当前聊天绑定的项目 id
         * （可选；只在首次构建时生效）。由调用方按打开的项目提供。
         */
        currentProjectId: (() -> String?)? = null,
        /**
         * P2 自动执行（2026-11-11）：远端成员调用方 + 远端审查者候选
         * （可选；只在首次构建时生效）。见 BrainBootstrap.build()。
         */
        pipelineRemoteCaller: PipelineAgentCaller? = null,
        pipelineRemoteReviewerCandidates: (() -> List<PipelineOrchestrator.ReviewerCandidate>)? = null,
        /**
         * MCP 通用化（2026-11-12）：MCP OAuth 授权时开浏览器的回调
         * （可选；只在首次构建时生效）。见 BrainBootstrap.build()。
         */
        openOAuthUrl: ((String) -> Unit)? = null,
    ): BrainBootstrap.BrainComponents {
        components?.let { return it }
        synchronized(this) {
            components?.let { return it }
            val built = BrainBootstrap.build(
                context.applicationContext,
                approvalGate,
                remoteTargetResolver = remoteTargetResolver,
                remoteControlSender = remoteControlSender,
                gitHostProjectContext = gitHostProjectContext,
                currentProjectId = currentProjectId,
                pipelineRemoteCaller = pipelineRemoteCaller,
                pipelineRemoteReviewerCandidates = pipelineRemoteReviewerCandidates,
                openOAuthUrl = openOAuthUrl,
            )
            components = built
            return built
        }
    }

    /** 测试用：丢弃已构建的依赖图。 */
    fun resetForTests() {
        synchronized(this) {
            components = null
        }
    }
}
