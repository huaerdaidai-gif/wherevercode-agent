package wherevercode.agent.brain

import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import wherevercode.agent.core.autonomy.AutonomyDecision
import wherevercode.agent.core.autonomy.AutonomyGate
import wherevercode.agent.core.device.DeviceAdapter
import wherevercode.agent.core.extension.ExtensionToolProvider
import wherevercode.agent.core.project.GitHostId
import wherevercode.agent.core.project.GitHostProjectPort
import wherevercode.agent.core.project.GitRepoRef
import wherevercode.agent.core.project.Project
import wherevercode.agent.core.project.ProjectLocation
import wherevercode.agent.core.python.PythonProbe
import wherevercode.agent.core.python.PythonRunRequest
import wherevercode.agent.core.python.PythonRuntime
import wherevercode.agent.core.security.AuditEntry
import wherevercode.agent.core.security.AuditLog
import wherevercode.agent.core.security.CommandLimits
import wherevercode.agent.core.security.LimitError
import wherevercode.agent.core.security.SafePaths
import wherevercode.agent.core.security.dangerousArgumentReason
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskStore
import wherevercode.agent.core.workspace.ChangeSet
import wherevercode.agent.core.workspace.WorkspaceAdapter
import wherevercode.agent.core.workspace.WorkspaceException

/**
 * ToolRegistry: the tool set the brain may call (stage 2, audit section 4.7).
 *
 * A ToolDef is DATA: the brain only ever sees the list this registry
 * builds dynamically from the brain's capabilities and the current
 * authorization. A tool the brain cannot call, or is not authorized for,
 * is never listed to it.
 *
 * Stage-2 minimal set: read_file / write_file / run_command / list_files /
 * dispatch_task / ask_user.
 *
 * Validation pipeline (audit section 4.6, "model proposes, program
 * verifies and executes"):
 * 1. structure: tool known, required args present with the right types.
 * 2. permission: path root check / command allowlist / authorization.
 * 3. approval: write_file and dispatch_task always need confirmation;
 *    run_command needs confirmation unless it is a read-only/view command
 *    (ls/cat/grep/find/git/...); reads are automatic.
 * A denied action is NEVER executed; the denial (with a Chinese reason)
 * is fed back to the brain as the tool result.
 */
data class ToolContext(
    val taskId: String,
    val agentId: String,
    val approvalGate: ApprovalGate,
    val workspaceRoot: File,
    val deviceId: String,
    val folder: String,
    /**
     * git 家（2026-11-11）：当前聊天项目的 git 家——有且仅当
     * 聊天项目是 git 家时非空。git 家工具只在它非空时可用；读/写/
     * 推分支/开 PR 全走 [GitHostProjectPort]，不碰本机工作区、不克隆。
     */
    val gitHostProject: Project? = null,
    val gitHostLocation: ProjectLocation? = null,
)

data class ToolOutcome(
    val ok: Boolean,
    val summary: String,
    val data: String? = null,
    val errorCode: String? = null,
    // P1 多步骤（2026-11-08）：空目录三态的身份字段，全部可选，旧调用点不动。
    // 只由 handleList 与 handleRead 填充；其余 handler 保持 null。
    /** "empty" | "not_empty" | "not_configured" | "missing" | "not_readable" | "not_a_directory" */
    val workspaceState: String? = null,
    /** 脱敏相对路径："" = 工作区根；永不含绝对路径。 */
    val path: String? = null,
    /** 固定标签："app-workspace"（本机应用工作区），与“项目”区分。 */
    val workspaceLabel: String? = null,
    /** 执行宿主：ToolContext.deviceId（现状 "local"）。 */
    val host: String? = null,
)

/** 工作区状态（P1 §6.1）：handleList 首行判定的结果。 */
enum class WorkspaceState(val label: String) {
    OK("ok"),
    EMPTY("empty"),
    NOT_EMPTY("not_empty"),
    NOT_CONFIGURED("not_configured"),
    MISSING("missing"),
    NOT_READABLE("not_readable"),
    NOT_A_DIRECTORY("not_a_directory"),
}

class ToolRegistry(
    private val deviceAdapter: DeviceAdapter,
    private val workspaceAdapter: WorkspaceAdapter,
    private val limits: CommandLimits,
    private val taskStore: TaskStore,
    private val auditLog: AuditLog,
    /**
     * P1 §6.1：可选的事件接收器（kind, detail），供 handleList 在
     * listFiles()==null 时补记 canRead/canExecute。默认 null（生产接线
     * 不接，审计仍照常记 outcome）。测试可传入以断言权限位。
     */
    private val eventSink: ((String, String) -> Unit)? = null,
    /**
     * 2026-11-09 自主开发闭环：真实 Python 运行时（Chaquopy 内嵌 +
     * 系统 python3 回退在 app 层组合）。null = 本进程没有接入运行时，
     * python 调用会如实返回 python_unavailable，不模拟执行。
     */
    private val pythonRuntime: PythonRuntime? = null,
    /** 2026-11-09 写轮次可观测性：begin/finish/abandon/reclaim 事件。 */
    private val onRoundEvent: ((String, String) -> Unit)? = null,
    /**
     * 2026-11-11 MCP + 插件：扩展工具来源（插件 + MCP）。null = 不接
     * 扩展工具（旧调用点与测试不动）。接上后，扩展工具与内置工具走
     * 同一条 execute 流程（结构 → 审批 → 执行 → 审计）；扩展工具不
     * 碰本机工作区，跳过路径/命令权限，参数与执行边界由来源负责。
     */
    private val extensionTools: ExtensionToolProvider? = null,
    /**
     * git 家（2026-11-11）：可选的 git 家入口。null = 本进程
     * 没有接 git 家，git 家工具一律报 github_not_configured，不模拟执行。
     */
    private val gitHostProjectPort: GitHostProjectPort? = null,
    /**
     * 自主度门（2026-11-11，maintainer 22:43 两种模式）：null = 未接线，
     * 审批行为与原来完全一致；接上后写操作审批按 L0–L4 梯子与主动性
     * 派发门走，自动放行记审计（见 AutonomyGate）。
     */
    private val autonomyGate: AutonomyGate? = null,
    /**
     * a-3 交付卡：写轮次结算后把 (taskId, roundId, changeSet) 交给
     * 交付卡存储。默认 null（不接线时行为不变）；调用方抛错不得
     * 影响轮次结算，所以调用处包 try/catch。
     */
    private val onChangeSet: ((taskId: String, roundId: String, changeSet: ChangeSet) -> Unit)? = null,
    /**
     * V5 跨设备开发（2026-11-12，A1/D5）：当前聊天绑定的项目 id
     * （调用方 MainActivity 按打开的项目提供，与 gitHostProjectContext
     * 同款 provider；不传时 dispatch_task 的任务不带项目）。
     * dispatch_task 取项目时参数优先：工具参数 project_id 非空就用
     * 参数，两者冲突时参数赢；无参数时取当前聊天绑定。
     */
    private val currentProjectId: (() -> String?)? = null,
) {
    /**
     * P1 §6.1（2026-11-08）：删除了 a13f2529 的 `?: File("/")` 回退。
     * allowedRoots 为空时返回 null，调用方必须显式进入未配置态——
     * 静默退化成根目录会把系统根目录当工作区列出，误报“工作区是空的”。
     */
    val workspaceRoot: File? get() = deviceAdapter.allowedRoots.firstOrNull()

    companion object {
        const val WORKSPACE_LABEL: String = "app-workspace"
        const val MAX_OUTPUT_CHARS: Int = 20000
        const val MAX_TEXT_BYTES: Int = 2 * 1024 * 1024 // ported: execution_host_workspace_backup.MAX_TEXT_BYTES
        val ALL_TOOLS: List<String> = listOf(
            "read_file", "write_file", "run_command", "list_files", "dispatch_task", "ask_user",
        )
        /**
         * git 家（2026-11-11）：git 家的工具。不进 [ALL_TOOLS]——
         * 只有当前聊天项目是 git 家时才由 [gitHostToolsFor] 递给模型，
         * 模型看不到用不了的工具。
         */
        val GIT_HOST_TOOLS: List<String> = listOf(
            "list_github_files", "read_github_file", "write_github_file",
            "create_github_branch", "create_github_pr",
        )
        /** Tools that always need user confirmation (audit section 4.6 tiers). */
        val ALWAYS_NEEDS_APPROVAL: Set<String> = setOf(
            "write_file", "dispatch_task",
            "write_github_file", "create_github_branch", "create_github_pr",
        )
        /** run_command argv[0] values that are read-only/view: automatic, no confirmation. */
        val READ_ONLY_COMMANDS: Set<String> = setOf(
            "ls", "cat", "head", "tail", "grep", "find", "wc", "echo", "pwd", "git",
        )

        /** 2026-11-09：写轮次属主，用于区分 brain 工具与 hand 通道。 */
        const val ROUND_OWNER: String = "brain-tool"

        /** 单条命令/脚本保留的输出字符上限（防止 OOM）。 */
        const val MAX_CAPTURED_CHARS: Int = 1_000_000

        private val PYTHON_MODULE_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_.]*")

        private val PYTHON_NAMES: Set<String> = setOf("python", "python3")

        /** 忽略不改变语义的 python 开关（不涉及内联代码）。 */
        private val PYTHON_IGNORED_FLAGS: Set<String> = setOf("-u", "-B")

        /**
         * 只读命令判定（审批与只读缓存共用）：白名单命令，或
         * `python3 --version` 这种纯版本探测（不执行用户代码）。
         */
        fun isReadOnlyRun(argv: List<String>?): Boolean {
            if (argv.isNullOrEmpty()) return false
            val name = argv[0].substringAfterLast('/')
            if (name in READ_ONLY_COMMANDS) {
                // 2026-11-13 安全加固：只读白名单只看 argv[0] 时，
                // `find -delete` / `find -exec` / `git push` / `git diff --output=…`
                // 都会被当成只读、免审批直接跑；`cat /data/data/<包名>/shared_prefs/…`
                // 能免审批读到工作区外（含本机凭证）的文件。现在：
                // - 带危险参数的（见 dangerousArgumentReason）不算只读；
                // - git 只认只读子命令；
                // - 参数里有绝对路径 / `~` / `..`（可能出工作区）的不算只读——
                //   回落到正常审批，由用户确认，而不是静默放行。
                if (dangerousArgumentReason(argv) != null) return false
                if (name == "git" && (argv.size < 2 || argv[1] !in GIT_READ_ONLY_SUBCOMMANDS)) return false
                if (SafePaths.hasPathLikeEscapeArgs(argv)) return false
                return true
            }
            return isPythonVersionProbe(argv)
        }

        /** 免审批的 git 子命令（与 CommandLimits.GIT_ALLOWED_SUBCOMMANDS 同口径）。 */
        private val GIT_READ_ONLY_SUBCOMMANDS: Set<String> = setOf("status", "diff", "log", "show")

        private fun isPythonVersionProbe(argv: List<String>): Boolean {
            val name = argv[0].substringAfterLast('/')
            if (name !in PYTHON_NAMES) return false
            val rest = argv.drop(1).filter { it !in PYTHON_IGNORED_FLAGS }
            return rest == listOf("--version") || rest == listOf("-V")
        }

        fun toolDef(name: String): ToolDef = when (name) {
            "read_file" -> ToolDef(
                name = "read_file",
                description = "读取工作区内一个文本文件的内容（相对路径）。path 须取自已返回的 list_files 结果，不得与 list_files 同轮发出。",
                inputSchema = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "path" to mapOf(
                            "type" to "string",
                            "description" to "工作区内文件的相对路径，须取自已返回的 list_files 结果；不得与 list_files 同轮发出，须等 list_files 返回后再读。",
                        ),
                    ),
                    "required" to listOf("path"),
                ),
            )
            "write_file" -> ToolDef(
                name = "write_file",
                description = "在工作区内新建或覆盖一个文本文件（相对路径）。写前自动备份，可撤销。",
                inputSchema = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "path" to mapOf(
                            "type" to "string",
                            "description" to "工作区内文件的相对路径。",
                        ),
                        "content" to mapOf(
                            "type" to "string",
                            "description" to "要写入文件的完整文本内容。",
                        ),
                    ),
                    "required" to listOf("path", "content"),
                ),
            )
            "run_command" -> ToolDef(
                name = "run_command",
                description = "在工作区内运行一条命令（不用 shell，参数按列表传；仅白名单命令）。",
                inputSchema = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "argv" to mapOf(
                            "type" to "array",
                            "items" to mapOf("type" to "string"),
                            "description" to "要运行的命令与参数列表，首项须为白名单内的只读命令。",
                        ),
                        "cwd" to mapOf(
                            "type" to "string",
                            "description" to "命令的工作目录（相对路径，空表示工作区根）。",
                        ),
                        "timeout" to mapOf(
                            "type" to "number",
                            "description" to "超时秒数。",
                        ),
                    ),
                    "required" to listOf("argv"),
                ),
            )
            "list_files" -> ToolDef(
                name = "list_files",
                description = "列出工作区内文件夹里的文件和子文件夹（相对路径，空表示根目录）。",
                inputSchema = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "path" to mapOf(
                            "type" to "string",
                            "description" to "要列出的文件夹相对路径，空表示工作区根。",
                        ),
                    ),
                ),
            )
            "dispatch_task" -> ToolDef(
                name = "dispatch_task",
                description = "新建一个任务交给另一 Agent/设备（进入 queued 等待认领）。",
                inputSchema = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "goal" to mapOf(
                            "type" to "string",
                            "description" to "任务目标（非空字符串）。",
                        ),
                        "title" to mapOf(
                            "type" to "string",
                            "description" to "任务标题。",
                        ),
                        // 阶段 3（A1 P1）：指派给已添加的 Agent（AgentRecord.agentId）。
                        // 为空 = 不指定，由本地调度器认领；指定后本地执行器不代执行，
                        // 任务保持 queued 等该 Agent 认领（跨设备执行是 P2/下一阶段）。
                        "agent_id" to mapOf(
                            "type" to "string",
                            "description" to "指派的 Agent id（已添加的 Agent 记录）；为空表示不指定。",
                        ),
                        // V5 跨设备开发（2026-11-12，A1/D5 新口径）：目标项目。
                        // 有参数以参数为准，无参数取当前聊天绑定。
                        "project_id" to mapOf(
                            "type" to "string",
                            "description" to "目标项目 id；有值时以参数为准，无值时取当前聊天绑定的项目。",
                        ),
                    ),
                    "required" to listOf("goal"),
                ),
            )
            "ask_user" -> ToolDef(
                name = "ask_user",
                description = "向用户提问并结束本轮，等用户下一条消息回答。",
                inputSchema = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "question" to mapOf(
                            "type" to "string",
                            "description" to "向用户的完整问题内容（非空字符串）；提问即结束本轮。",
                        ),
                    ),
                    "required" to listOf("question"),
                ),
            )
            // git 家（2026-11-11）：只在当前聊天项目是 git 家时
            // 才递给模型。读/写/推分支/开 PR 全走该站 API，不克隆、
            // 不把项目文件下到任何设备上。写、推分支、开 PR 要用户确认。
            "list_github_files" -> ToolDef(
                name = "list_github_files",
                description = "列出当前 git 家项目仓库里的一个目录（相对路径，空表示根目录）。只读，不改仓库。",
                inputSchema = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "path" to mapOf(
                            "type" to "string",
                            "description" to "仓库内目录的相对路径，空表示根目录。",
                        ),
                        "ref" to mapOf(
                            "type" to "string",
                            "description" to "分支名或 commit，空表示项目默认分支。",
                        ),
                    ),
                ),
            )
            "read_github_file" -> ToolDef(
                name = "read_github_file",
                description = "读当前 git 家项目仓库里的一个文件（相对路径）。只读，不改仓库；读之前先用 list_github_files 确认路径存在。",
                inputSchema = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "path" to mapOf(
                            "type" to "string",
                            "description" to "仓库内文件的相对路径，须取自已返回的 list_github_files 结果。",
                        ),
                        "ref" to mapOf(
                            "type" to "string",
                            "description" to "分支名或 commit，空表示项目默认分支。",
                        ),
                    ),
                    "required" to listOf("path"),
                ),
            )
            "write_github_file" -> ToolDef(
                name = "write_github_file",
                description = "在当前 git 家项目仓库里新建或覆盖一个文件（相对路径），提交到指定分支。写操作要用户确认；改文件先读回现状再改，不要凭空写。",
                inputSchema = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "path" to mapOf(
                            "type" to "string",
                            "description" to "仓库内文件的相对路径。",
                        ),
                        "content" to mapOf(
                            "type" to "string",
                            "description" to "要写入文件的完整文本内容。",
                        ),
                        "message" to mapOf(
                            "type" to "string",
                            "description" to "提交信息，如“把 README 标题改成 XXX”。",
                        ),
                        "branch" to mapOf(
                            "type" to "string",
                            "description" to "写到哪个分支，空表示项目默认分支；改代码先建分支再写，不要直接写默认分支。",
                        ),
                    ),
                    "required" to listOf("path", "content", "message"),
                ),
            )
            "create_github_branch" -> ToolDef(
                name = "create_github_branch",
                description = "在当前 git 家项目仓库里，从默认分支建一个新分支。改代码前先建分支，再写文件、再开 PR（GitLab 叫合并请求）；绝不直接改默认分支。",
                inputSchema = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "branch" to mapOf(
                            "type" to "string",
                            "description" to "新分支名，如 fix/readme-title-20261111。",
                        ),
                    ),
                    "required" to listOf("branch"),
                ),
            )
            "create_github_pr" -> ToolDef(
                name = "create_github_pr",
                description = "在当前 git 家项目仓库里开一个 PR（GitLab 叫合并请求）：指定分支 → 项目默认分支。写完文件、确认无误后再开。",
                inputSchema = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "head" to mapOf(
                            "type" to "string",
                            "description" to "改动的分支名，如 fix/readme-title-20261111。",
                        ),
                        "title" to mapOf(
                            "type" to "string",
                            "description" to "PR 标题。",
                        ),
                        "body" to mapOf(
                            "type" to "string",
                            "description" to "PR 说明（改了什么、怎么验）。",
                        ),
                    ),
                    "required" to listOf("head", "title"),
                ),
            )
            else -> throw IllegalArgumentException("unknown tool: $name")
        }
    }

    fun allToolNames(): Set<String> =
        ALL_TOOLS.toSet() + (extensionTools?.toolNames() ?: emptySet())

    /** 扩展工具是否只读（供 BrainEngine 的 isReadOnlyToolCall 查询；内置工具不在此列）。 */
    fun isReadOnlyExtensionTool(toolName: String): Boolean =
        extensionTools?.isReadOnly(toolName) ?: false

    /**
     * Dynamic tool list for one brain: empty when the brain has no native
     * tool calling (the engine then falls back to text parsing, see
     * Failover), and filtered to the authorized set.
     */
    fun toolsForCapabilities(
        capabilities: BrainCapabilities?,
        authorizedTools: Set<String>,
    ): List<ToolDef> {
        if (capabilities != null && !capabilities.toolCalling) return emptyList()
        val builtin = ALL_TOOLS.filter { it in authorizedTools }.map { toolDef(it) }
        val extension = (extensionTools?.toolDefs() ?: emptyList())
            .filter { it.name in authorizedTools }
        return builtin + extension
    }

    /**
     * git 家（2026-11-11）：当前聊天项目的 git 家工具定义。
     * Port 未接、或项目不是 git 家时返回空——模型看不到用不了的工具。
     */
    fun gitHostToolsFor(project: Project?, location: ProjectLocation?): List<ToolDef> {
        val port = gitHostProjectPort ?: return emptyList()
        if (project == null || location == null) return emptyList()
        if (port.resolveRepoRef(project, location) == null) return emptyList()
        return GIT_HOST_TOOLS.map { toolDef(it) }
    }

    /** 当前 [ToolContext] 的 git 家坐标；无 git 家或 Port 未接时返回 null。 */
    fun gitHostRepoRef(context: ToolContext): GitRepoRef? {
        val port = gitHostProjectPort ?: return null
        val project = context.gitHostProject ?: return null
        val location = context.gitHostLocation ?: return null
        return port.resolveRepoRef(project, location)
    }

    /**
     * P1 §6.1：工作区根状态判定（供 handleList 与 UI 自检共用）。
     * 判定顺序：未配置（allowedRoots 为空）→ 目录不存在 → 不是目录 →
     * 其余（可读与否由 handleList 对目标目录再判定）。
     */
    fun workspaceState(): WorkspaceState {
        val root = workspaceRoot ?: return WorkspaceState.NOT_CONFIGURED
        if (!root.exists()) return WorkspaceState.MISSING
        if (!root.isDirectory) return WorkspaceState.NOT_A_DIRECTORY
        return WorkspaceState.OK
    }

    /** Full validation pipeline + execution. Denied actions are never executed. */
    suspend fun execute(
        action: Action,
        context: ToolContext,
        /**
         * P1 §5.4 第二道停止门（纵深，不依赖引擎）：一旦为真，直接返回
         * turn_stopped，不执行、不审批；审计仍记一条（除审计外不写任何东西）。
         * 由引擎在每次执行前传入控制态快照。
         */
        stopped: () -> Boolean = { false },
    ): ToolOutcome {
        if (stopped()) {
            val outcome = ToolOutcome(
                ok = false,
                summary = "已停止，未执行",
                errorCode = "turn_stopped",
            )
            audit(action, context, outcome, null)
            return outcome
        }
        // 1. Structure（扩展工具由来源自己校验参数形状）。
        val isExtension = extensionTools != null && action.tool !in ALL_TOOLS
        val structureError = if (isExtension) extensionTools!!.validate(action) else checkStructure(action)
        if (structureError != null) {
            return denied(action, context, "invalid_action", structureError)
        }
        // 2. Permission（扩展工具不碰本机工作区，跳过路径/命令权限）。
        val permissionError = if (isExtension) null else checkPermission(action, context)
        if (permissionError != null) {
            return denied(action, context, permissionError.first, permissionError.second)
        }
        // 2026-11-09 审批安全：审批与执行必须针对同一份参数快照。这里先
        // 冻结（深拷贝 Map/List），之后无论调用方怎么改原 args，执行的都是
        // 用户批准的那一份。
        val effectiveAction = ApprovalFingerprint.freeze(action)
        // 3. Approval（自主度门，2026-11-11）：接了门时由门决定这把
        // 工具要不要问用户（L0–L4 梯子 + 主动性派发门）；门不管的工具
        // （未知 / 扩展）回落到原有 needsApproval。Action 自带的
        // requiresApproval（plan 步骤显式标的）永远强制问用户——
        // 全权模式也不改写 plan 里写明的审批要求。
        val gateDecision = autonomyGate?.writeDecision(
            context.taskId,
            effectiveAction.tool,
            isReadOnlyRunAction(effectiveAction),
        )
        val needsUserApproval = effectiveAction.requiresApproval || when (gateDecision) {
            null -> needsApproval(effectiveAction)
            is AutonomyDecision.AutoApproved -> false
            is AutonomyDecision.NeedsUser -> true
        }
        if (needsUserApproval) {
            val approvedFingerprint = ApprovalFingerprint.of(effectiveAction)
            val approved = try {
                context.approvalGate.requestApproval(effectiveAction)
            } catch (e: CancellationException) {
                // 取消不是同意：向上传播，由引擎/UI 显示已取消。
                throw e
            } catch (_: Exception) {
                false
            }
            if (!approved) {
                return denied(
                    effectiveAction,
                    context,
                    "user_denied",
                    "用户拒绝了 ${effectiveAction.tool} 操作，未执行。",
                )
            }
            // 3a. 授权后参数指纹重算：不一致说明"批准的操作"与"要执行的操作"
            // 不是同一个，拒绝（TOCTOU）。
            if (ApprovalFingerprint.of(effectiveAction) != approvedFingerprint) {
                return denied(
                    effectiveAction,
                    context,
                    "approval_action_changed",
                    "审批后操作参数发生变化，已拒绝执行；请重新发起并确认。",
                )
            }
            // 3b. 授权后结构/权限重检：授权期间路径或授权状态可能已变化。
            val structureRecheck = if (isExtension) {
                extensionTools!!.validate(effectiveAction)
            } else {
                checkStructure(effectiveAction)
            }
            val recheck: Pair<String, String>? = if (structureRecheck != null) {
                "invalid_action" to structureRecheck
            } else if (isExtension) {
                null
            } else {
                checkPermission(effectiveAction, context)
            }
            if (recheck != null) {
                return denied(
                    effectiveAction,
                    context,
                    recheck.first,
                    "审批后复核未通过：${recheck.second}（未执行）",
                )
            }
            // 3c. 授权是否仍然有效：Activity 销毁/用户停止会让批准作废。
            if (!context.approvalGate.isApprovalStillValid(effectiveAction)) {
                return denied(
                    effectiveAction,
                    context,
                    "approval_revoked",
                    "授权已失效（界面已销毁或本轮已停止），未执行。",
                )
            }
        }
        // 3d. 2026-11-13 停止后写入防护：审批可能等了很久，期间用户点了
        // 停止/取消。执行前再看一次停止门——扩展工具（MCP/插件）的
        // execute 不一定自己检查 stopped，内置 handler 也不是每个都查。
        if (stopped()) {
            val outcome = ToolOutcome(
                ok = false,
                summary = "已停止，未执行",
                errorCode = "turn_stopped",
            )
            audit(effectiveAction, context, outcome, null)
            return outcome
        }
        // 4. Execute（扩展工具走来源执行，同样进审计）。
        val outcome = try {
            if (isExtension) {
                extensionTools!!.execute(effectiveAction, context, stopped)
            } else {
                runHandler(effectiveAction, context, stopped)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LimitError) {
            ToolOutcome(ok = false, summary = e.message ?: "命令受限", errorCode = e.code)
        } catch (e: WorkspaceException) {
            ToolOutcome(ok = false, summary = e.message ?: "工作区错误", errorCode = e.code)
        } catch (e: IllegalArgumentException) {
            ToolOutcome(ok = false, summary = e.message ?: "参数错误", errorCode = "invalid_argument")
        } catch (e: Exception) {
            ToolOutcome(ok = false, summary = "执行失败：${e.message ?: "未知错误"}", errorCode = "tool_failed")
        }
        audit(effectiveAction, context, outcome, null)
        return outcome
    }

    /**
     * P1 §3.2：只读重检，供引擎在缓存命中前调用。只跑结构 + 路径权限，
     * 不执行、不审批、不写审计。返回 null = 通过；返回 outcome = 拒绝原因
     * （引擎回填该拒绝结果而非缓存结果）。
     */
    fun validateOnly(action: Action, context: ToolContext): ToolOutcome? {
        if (action.tool !in ALL_TOOLS && extensionTools != null) {
            val error = extensionTools.validate(action)
            return if (error != null) {
                ToolOutcome(ok = false, summary = error, errorCode = "invalid_action")
            } else {
                null
            }
        }
        val structureError = checkStructure(action)
        if (structureError != null) {
            return ToolOutcome(ok = false, summary = structureError, errorCode = "invalid_action")
        }
        val permissionError = checkPermission(action, context)
        if (permissionError != null) {
            // P1 §6：未配置态的拒绝结果也带三态身份字段（与 handleList 一致）。
            val unconfigured = permissionError.first == "workspace_not_configured"
            return ToolOutcome(
                ok = false,
                summary = permissionError.second,
                errorCode = permissionError.first,
                workspaceState = if (unconfigured) workspaceState().label else null,
                path = if (unconfigured) "" else null,
                workspaceLabel = if (unconfigured) WORKSPACE_LABEL else null,
                host = if (unconfigured) context.deviceId else null,
            )
        }
        return null
    }

    private fun needsApproval(action: Action): Boolean {
        if (action.tool !in ALL_TOOLS) return extensionTools?.needsApproval(action.tool) ?: true
        if (action.tool in ALWAYS_NEEDS_APPROVAL) return true
        if (action.tool == "run_command") {
            val argv = parseArgv(action.args["argv"]) ?: return true
            return !isReadOnlyRun(argv)
        }
        return false
    }

    /** 自主度门用：run_command 是不是只读命令（与 needsApproval 同口径）。 */
    private fun isReadOnlyRunAction(action: Action): Boolean {
        if (action.tool != "run_command") return false
        val argv = parseArgv(action.args["argv"]) ?: return false
        return isReadOnlyRun(argv)
    }

    private fun checkStructure(action: Action): String? {
        if (action.tool !in ALL_TOOLS && action.tool !in GIT_HOST_TOOLS) {
            return "未知工具：${action.tool}"
        }
        fun needString(key: String): String? {
            val value = action.args[key]
            if (value !is String || value.isBlank()) return "缺少参数 $key（须为非空字符串）"
            return null
        }
        return when (action.tool) {
            "read_file" -> needString("path")
            "write_file" -> needString("path") ?: needString("content")
            "run_command" -> if (parseArgv(action.args["argv"]) == null) {
                "缺少参数 argv（须为字符串列表）"
            } else {
                null
            }
            "list_files" -> {
                val path = action.args["path"]
                if (path != null && path !is String) "参数 path 须为字符串" else null
            }
            "dispatch_task" -> needString("goal") ?: run {
                val projectId = action.args["project_id"]
                if (projectId != null && projectId !is String) "参数 project_id 须为字符串" else null
            }
            "ask_user" -> needString("question")
            "list_github_files" -> {
                val path = action.args["path"]
                val ref = action.args["ref"]
                if (path != null && path !is String) {
                    "参数 path 须为字符串"
                } else if (ref != null && ref !is String) {
                    "参数 ref 须为字符串"
                } else {
                    null
                }
            }
            "read_github_file" -> needString("path")
            "write_github_file" -> needString("path") ?: needString("content") ?: needString("message")
            "create_github_branch" -> needString("branch")
            "create_github_pr" -> needString("head") ?: needString("title")
            else -> "未知工具：${action.tool}"
        }
    }

    /** Returns (code, chinese reason) or null when permitted. */
    private fun checkPermission(action: Action, context: ToolContext): Pair<String, String>? {
        // git 家（2026-11-11）：git 家工具不碰本机工作区，不走工作区
        // 权限判定；只认当前聊天项目的 git 家 + 已接的 Port。无 git 家
        // 时如实拒绝，不回落到本机工作区、不编造。
        if (action.tool in GIT_HOST_TOOLS) {
            if (gitHostProjectPort == null || gitHostRepoRef(context) == null) {
                return "github_not_configured" to
                    "未配置 git 家项目：请先在项目页打开一个 git 家的项目，再让大脑经该站 API 干活（不克隆、不下到本地）。"
            }
            return null
        }
        // P1 §6.1：未配置 / 目录不存在先于路径解析报出，否则 resolveInWorkspace
        // 的 null 会把“未配置”误报成普通的 path_not_allowed，handleList 的
        // 三态判定也到不了。
        when (workspaceState()) {
            WorkspaceState.NOT_CONFIGURED -> return "workspace_not_configured" to
                "未配置工作区：请先在设置里配置工作区，再列文件"
            WorkspaceState.MISSING -> return "workspace_not_configured" to
                "工作区目录不存在（可能被删除），请检查后重试"
            else -> { /* 根目录正常，继续按工具判定 */ }
        }
        when (action.tool) {
            "read_file", "write_file", "list_files" -> {
                val path = action.args["path"] as? String ?: ""
                val resolved = resolveInWorkspace(path) ?: return "path_not_allowed" to
                    "不允许的路径：$path（不在授权工作区内）"
                if (resolved.second == null) return "path_not_allowed" to
                    "不允许的路径：$path（不在授权工作区内）"
                if (action.tool != "list_files" && (path.isEmpty() || path == ".")) {
                    return "path_not_allowed" to "不允许的路径：$path（根目录本身不是文件）"
                }
            }
            "run_command" -> {
                val argv = parseArgv(action.args["argv"]) ?: return "invalid_action" to "缺少参数 argv"
                try {
                    limits.checkCommand(argv)
                } catch (e: LimitError) {
                    return e.code to (e.message ?: "不允许的命令")
                }
                val cwd = action.args["cwd"] as? String ?: ""
                if (cwd.isNotEmpty() && resolveInWorkspace(cwd)?.second == null) {
                    return "path_not_allowed" to "不允许的路径：$cwd（不在授权工作区内）"
                }
            }
        }
        return null
    }

    /**
     * Resolve a workspace-relative path. Returns (relative, absolute) or null when outside the root.
     *
     * 2026-11-13 安全加固：词法/规范化解析之后，再用 [SafePaths] 按真实路径
     * 复核一次——悬空符号链接叶子、不存在中间目录之上的根外链接，都不能
     * 把读写带出工作区。
     */
    private fun resolveInWorkspace(rawPath: String): Pair<String, File>? {
        val root = workspaceRoot ?: return null
        val resolved = resolveInWorkspaceUnchecked(rawPath) ?: return null
        if (SafePaths.resolveInside(root, resolved.second) == null) return null
        return resolved
    }

    private fun resolveInWorkspaceUnchecked(rawPath: String): Pair<String, File>? {
        val root = workspaceRoot ?: return null
        val cleaned = rawPath.trim().replace('\\', '/')
        if (cleaned.isEmpty() || cleaned == ".") return "" to root
        if ('\u0000' in cleaned) return null
        val rootPath = root.canonicalPath
        if (cleaned.startsWith("/")) {
            // V5 C2 (batch 2, GPT 8393f336 safety gate): an absolute
            // input is validated AS an absolute path — same rule as
            // WorkspaceAdapter.resolveWorkspacePath (C1 guardWrite
            // fix). It must canonicalise inside the workspace root;
            // it is never silently reinterpreted as workspace-relative,
            // otherwise "/etc/passwd" would become "<root>/etc/passwd"
            // and read/write the wrong file. Outside -> null.
            val target = File(cleaned)
            val canonical = try {
                target.canonicalPath
            } catch (_: Exception) {
                // Missing final component: canonicalise the parent.
                val parent = target.parentFile?.canonicalPath ?: return null
                if (parent != rootPath && !parent.startsWith("$rootPath/")) return null
                val parentRel = if (parent == rootPath) "" else parent.removePrefix("$rootPath/")
                val rel = if (parentRel.isEmpty()) target.name else "$parentRel/${target.name}"
                return rel to File(parent, target.name)
            }
            if (canonical != rootPath && !canonical.startsWith("$rootPath/")) return null
            val rel = canonical.removePrefix(rootPath).trimStart('/')
            return rel to File(canonical)
        }
        val parts = cleaned.split("/").filter { it.isNotEmpty() && it != "." }
        if (".." in parts) return null
        var current = root
        for (part in parts) current = File(current, part)
        val targetPath = try {
            current.canonicalPath
        } catch (_: Exception) {
            // Missing final component: canonicalise the existing parent.
            val parent = current.parentFile?.canonicalPath ?: return null
            if (parent != rootPath && !parent.startsWith("$rootPath/")) return null
            return parts.joinToString("/") to File(parent, current.name)
        }
        if (targetPath != rootPath && !targetPath.startsWith("$rootPath/")) return null
        return parts.joinToString("/") to File(targetPath)
    }

    private fun parseArgv(raw: Any?): List<String>? = when (raw) {
        is List<*> -> {
            if (raw.isEmpty() || raw.any { it !is String || it.isEmpty() }) null
            else @Suppress("UNCHECKED_CAST") (raw as List<String>)
        }
        is String -> {
            if (raw.isBlank()) null else try {
                val array = JSONArray(raw)
                val list = (0 until array.length()).map { array.optString(it, "") }
                if (list.any { it.isEmpty() }) null else list
            } catch (_: Exception) {
                null
            }
        }
        else -> null
    }

    private suspend fun runHandler(
        action: Action,
        context: ToolContext,
        stopped: () -> Boolean,
    ): ToolOutcome = when (action.tool) {
        "read_file" -> handleRead(action, context)
        "list_files" -> handleList(action, context)
        "write_file" -> handleWrite(action, context, stopped)
        "run_command" -> handleRun(action, context, stopped)
        "dispatch_task" -> handleDispatch(action)
        "ask_user" -> handleAskUser(action)
        // git 家（2026-11-11）：读/写/推分支/开 PR 全走该站 API。
        "list_github_files" -> handleGitHostList(action, context)
        "read_github_file" -> handleGitHostRead(action, context)
        "write_github_file" -> handleGitHostWrite(action, context)
        "create_github_branch" -> handleGitHostBranch(action, context)
        "create_github_pr" -> handleGitHostPr(action, context)
        else -> ToolOutcome(ok = false, summary = "未知工具：${action.tool}", errorCode = "invalid_action")
    }

    /**
     * git 家：本轮的 git 家坐标（已由 checkPermission 核过非空，
     * 这里再取一次作防御；取不到就如实失败，不执行）。
     */
    private fun gitHostRefOrNull(context: ToolContext): GitRepoRef? = gitHostRepoRef(context)

    private fun gitHostPortOrNull(): GitHostProjectPort? = gitHostProjectPort

    private fun gitHostFailed(message: String?): ToolOutcome =
        ToolOutcome(
            ok = false,
            summary = message ?: "git 家操作失败",
            errorCode = "github_failed",
        )

    private suspend fun handleGitHostList(action: Action, context: ToolContext): ToolOutcome {
        val port = gitHostPortOrNull()
            ?: return ToolOutcome(ok = false, summary = "未接 git 家：本进程没有接 git 家入口。", errorCode = "github_not_configured")
        val project = context.gitHostProject
            ?: return ToolOutcome(ok = false, summary = "未配置 git 家项目：请先在项目页打开一个 git 家的项目。", errorCode = "github_not_configured")
        val location = context.gitHostLocation
            ?: return ToolOutcome(ok = false, summary = "未配置 git 家项目：请先在项目页打开一个 git 家的项目。", errorCode = "github_not_configured")
        val ref = gitHostRefOrNull(context)
            ?: return ToolOutcome(ok = false, summary = "该项目不是 git 家。", errorCode = "github_not_configured")
        val path = (action.args["path"] as? String).orEmpty()
        val branch = (action.args["ref"] as? String)?.takeIf { it.isNotBlank() }
        val result = port.listFiles(project, location, path, branch)
        return result.fold(
            onSuccess = { entries ->
                val lines = if (entries.isEmpty()) {
                    "（空目录）"
                } else {
                    entries.sortedWith(
                        compareBy({ it.type != "dir" }, { it.name.lowercase() }),
                    ).joinToString("\n") { entry ->
                        if (entry.type == "dir") "[目录] ${entry.path}/" else "[文件] ${entry.path}"
                    }
                }
                ToolOutcome(
                    ok = true,
                    summary = "${ref.coordinates.host.displayName} ${ref.coordinates.namespacePath}@${branch ?: ref.branch} /${path.ifBlank { "" }}：\n$lines",
                    data = lines,
                )
            },
            onFailure = { e -> gitHostFailed(e.message) },
        )
    }

    private suspend fun handleGitHostRead(action: Action, context: ToolContext): ToolOutcome {
        val port = gitHostPortOrNull()
            ?: return ToolOutcome(ok = false, summary = "未接 git 家：本进程没有接 git 家入口。", errorCode = "github_not_configured")
        val project = context.gitHostProject
            ?: return ToolOutcome(ok = false, summary = "未配置 git 家项目：请先在项目页打开一个 git 家的项目。", errorCode = "github_not_configured")
        val location = context.gitHostLocation
            ?: return ToolOutcome(ok = false, summary = "未配置 git 家项目：请先在项目页打开一个 git 家的项目。", errorCode = "github_not_configured")
        val ref = gitHostRefOrNull(context)
            ?: return ToolOutcome(ok = false, summary = "该项目不是 git 家。", errorCode = "github_not_configured")
        val path = (action.args["path"] as? String).orEmpty()
        val branch = (action.args["ref"] as? String)?.takeIf { it.isNotBlank() }
        val result = port.readFile(project, location, path, branch)
        return result.fold(
            onSuccess = { file ->
                val shown = file.text.take(MAX_OUTPUT_CHARS)
                val truncated = if (file.text.length > MAX_OUTPUT_CHARS) "\n（内容过长，只显示前 $MAX_OUTPUT_CHARS 字）" else ""
                ToolOutcome(
                    ok = true,
                    summary = "${ref.coordinates.host.displayName} ${ref.coordinates.namespacePath}@${branch ?: ref.branch} ${file.path}（${file.size} 字节）：\n$shown$truncated",
                    data = shown,
                )
            },
            onFailure = { e -> gitHostFailed(e.message) },
        )
    }

    private suspend fun handleGitHostWrite(action: Action, context: ToolContext): ToolOutcome {
        val port = gitHostPortOrNull()
            ?: return ToolOutcome(ok = false, summary = "未接 git 家：本进程没有接 git 家入口。", errorCode = "github_not_configured")
        val project = context.gitHostProject
            ?: return ToolOutcome(ok = false, summary = "未配置 git 家项目：请先在项目页打开一个 git 家的项目。", errorCode = "github_not_configured")
        val location = context.gitHostLocation
            ?: return ToolOutcome(ok = false, summary = "未配置 git 家项目：请先在项目页打开一个 git 家的项目。", errorCode = "github_not_configured")
        val ref = gitHostRefOrNull(context)
            ?: return ToolOutcome(ok = false, summary = "该项目不是 git 家。", errorCode = "github_not_configured")
        val path = (action.args["path"] as? String).orEmpty()
        val content = (action.args["content"] as? String).orEmpty()
        val message = (action.args["message"] as? String).orEmpty()
        val branch = (action.args["branch"] as? String)?.takeIf { it.isNotBlank() }
        val result = port.writeFile(project, location, path, content, message, branch)
        return result.fold(
            onSuccess = { write ->
                ToolOutcome(
                    ok = true,
                    summary = "已写 ${ref.coordinates.host.displayName} ${ref.coordinates.namespacePath}@${branch ?: ref.branch} ${write.path}${write.commitSha.takeIf { it.isNotBlank() }?.let { "（commit ${it.take(12)}）" } ?: ""}。",
                    data = write.commitSha,
                )
            },
            onFailure = { e -> gitHostFailed(e.message) },
        )
    }

    private suspend fun handleGitHostBranch(action: Action, context: ToolContext): ToolOutcome {
        val port = gitHostPortOrNull()
            ?: return ToolOutcome(ok = false, summary = "未接 git 家：本进程没有接 git 家入口。", errorCode = "github_not_configured")
        val project = context.gitHostProject
            ?: return ToolOutcome(ok = false, summary = "未配置 git 家项目：请先在项目页打开一个 git 家的项目。", errorCode = "github_not_configured")
        val location = context.gitHostLocation
            ?: return ToolOutcome(ok = false, summary = "未配置 git 家项目：请先在项目页打开一个 git 家的项目。", errorCode = "github_not_configured")
        val ref = gitHostRefOrNull(context)
            ?: return ToolOutcome(ok = false, summary = "该项目不是 git 家。", errorCode = "github_not_configured")
        val branch = (action.args["branch"] as? String).orEmpty()
        val result = port.createBranch(project, location, branch)
        return result.fold(
            onSuccess = { createdRef ->
                ToolOutcome(
                    ok = true,
                    summary = "已在 ${ref.coordinates.host.displayName} ${ref.coordinates.namespacePath} 上从 ${ref.branch} 建分支 $createdRef。",
                    data = createdRef,
                )
            },
            onFailure = { e -> gitHostFailed(e.message) },
        )
    }

    private suspend fun handleGitHostPr(action: Action, context: ToolContext): ToolOutcome {
        val port = gitHostPortOrNull()
            ?: return ToolOutcome(ok = false, summary = "未接 git 家：本进程没有接 git 家入口。", errorCode = "github_not_configured")
        val project = context.gitHostProject
            ?: return ToolOutcome(ok = false, summary = "未配置 git 家项目：请先在项目页打开一个 git 家的项目。", errorCode = "github_not_configured")
        val location = context.gitHostLocation
            ?: return ToolOutcome(ok = false, summary = "未配置 git 家项目：请先在项目页打开一个 git 家的项目。", errorCode = "github_not_configured")
        val ref = gitHostRefOrNull(context)
            ?: return ToolOutcome(ok = false, summary = "该项目不是 git 家。", errorCode = "github_not_configured")
        val head = (action.args["head"] as? String).orEmpty()
        val title = (action.args["title"] as? String).orEmpty()
        val body = (action.args["body"] as? String).orEmpty()
        val result = port.createPullRequest(project, location, head, title, body)
        return result.fold(
            onSuccess = { pr ->
                ToolOutcome(
                    ok = true,
                    summary = "已在 ${ref.coordinates.host.displayName} ${ref.coordinates.namespacePath} 开 ${if (ref.coordinates.host.id == GitHostId.GITLAB) "合并请求" else "PR"} #${pr.number}：${pr.title}（${pr.htmlUrl}）。",
                    data = pr.htmlUrl,
                )
            },
            onFailure = { e -> gitHostFailed(e.message) },
        )
    }

    private fun handleRead(action: Action, context: ToolContext): ToolOutcome {
        val path = action.args["path"] as String
        val (_, file) = resolveInWorkspace(path)!!
        if (!file.isFile) {
            return ToolOutcome(ok = false, summary = "文件不存在：$path", errorCode = "hand_not_found")
        }
        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            return ToolOutcome(ok = false, summary = "读取失败：$path（${e.message}）", errorCode = "hand_read_failed")
        }
        if (bytes.size > MAX_TEXT_BYTES || bytes.contains(0)) {
            return ToolOutcome(ok = false, summary = "不是文本文件或超过上限：$path", errorCode = "hand_not_text")
        }
        val content = bytes.toString(Charsets.UTF_8)
        return ToolOutcome(
            ok = true,
            summary = "已读取 $path（${bytes.size} 字节）",
            data = content.take(MAX_OUTPUT_CHARS),
            workspaceState = WorkspaceState.NOT_EMPTY.label,
            path = path,
            workspaceLabel = WORKSPACE_LABEL,
            host = context.deviceId,
        )
    }

    private fun handleList(action: Action, context: ToolContext): ToolOutcome {
        val path = (action.args["path"] as? String) ?: ""
        // P1 §6.1：工作区状态判定在任何 resolveInWorkspace 调用之前。
        // 顺序：未配置 → 目录不存在 → 不是目录 → 路径越权 → 不可读 → 空 → 非空。
        when (workspaceState()) {
            WorkspaceState.NOT_CONFIGURED -> return ToolOutcome(
                ok = false,
                summary = "未配置工作区：请先在设置里配置工作区，再列文件",
                errorCode = "workspace_not_configured",
                workspaceState = WorkspaceState.NOT_CONFIGURED.label,
                path = "",
                workspaceLabel = WORKSPACE_LABEL,
                host = context.deviceId,
            )
            WorkspaceState.MISSING -> return ToolOutcome(
                ok = false,
                summary = "工作区目录不存在（可能被删除），请检查后重试",
                errorCode = "workspace_not_configured",
                workspaceState = WorkspaceState.MISSING.label,
                path = "",
                workspaceLabel = WORKSPACE_LABEL,
                host = context.deviceId,
            )
            WorkspaceState.NOT_A_DIRECTORY -> return ToolOutcome(
                ok = false,
                summary = "不是文件夹：工作区根",
                errorCode = "hand_not_a_directory",
                workspaceState = WorkspaceState.NOT_A_DIRECTORY.label,
                path = "",
                workspaceLabel = WORKSPACE_LABEL,
                host = context.deviceId,
            )
            else -> { /* 根目录正常，继续对目标目录判定 */ }
        }
        val resolved = resolveInWorkspace(path)
            ?: return ToolOutcome(
                ok = false,
                summary = "不允许的路径：$path（不在授权工作区内）",
                errorCode = "path_not_allowed",
                path = path,
                workspaceLabel = WORKSPACE_LABEL,
                host = context.deviceId,
            )
        val (rel, dir) = resolved
        if (!dir.isDirectory) {
            return ToolOutcome(
                ok = false,
                summary = "不是文件夹：$path",
                errorCode = "hand_not_a_directory",
                workspaceState = WorkspaceState.NOT_A_DIRECTORY.label,
                path = rel,
                workspaceLabel = WORKSPACE_LABEL,
                host = context.deviceId,
            )
        }
        // 不可读目录与空目录必须区分：listFiles() 返回 null 说明目录
        // 无法读取（无权限或 I/O 错误），不再与空目录返回同一份成功文本，
        // 否则模型与用户都无法分辨“空”与“打不开”。
        val files = dir.listFiles()
            ?: run {
                eventSink?.invoke(
                    "workspace_not_readable",
                    "path=$rel canRead=${dir.canRead()} canExecute=${dir.canExecute()}",
                )
                return ToolOutcome(
                    ok = false,
                    summary = "无法读取该目录：$rel（无权限或不可读），不是空目录",
                    errorCode = "hand_not_readable",
                    workspaceState = WorkspaceState.NOT_READABLE.label,
                    path = rel,
                    workspaceLabel = WORKSPACE_LABEL,
                    host = context.deviceId,
                )
            }
        if (files.isEmpty()) {
            // P1 §6.2/§6.3：根目录摘要带“本机应用工作区”定语，禁止只写
            // “工作区是空的”后让用户误读为项目为空。
            val summary = if (rel.isEmpty()) {
                "已列出本机应用工作区（0 项）：工作区是空的"
            } else {
                "已列出本机应用工作区 $rel（0 项）：工作区是空的"
            }
            return ToolOutcome(
                ok = true,
                summary = summary,
                data = "",
                workspaceState = WorkspaceState.EMPTY.label,
                path = rel,
                workspaceLabel = WORKSPACE_LABEL,
                host = context.deviceId,
            )
        }
        val entries = files.sortedBy { it.name }.joinToString("\n") { entry ->
            (if (entry.isDirectory) "dir " else "file ") + entry.name
        }
        val summary = if (rel.isEmpty()) {
            "已列出本机应用工作区（${files.size} 项）"
        } else {
            "已列出本机应用工作区 $rel（${files.size} 项）"
        }
        return ToolOutcome(
            ok = true,
            summary = summary,
            data = entries,
            workspaceState = WorkspaceState.NOT_EMPTY.label,
            path = rel,
            workspaceLabel = WORKSPACE_LABEL,
            host = context.deviceId,
        )
    }

    private fun handleWrite(action: Action, context: ToolContext, stopped: () -> Boolean): ToolOutcome {
        val path = action.args["path"] as String
        val content = action.args["content"] as String
        if (content.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) {
            return ToolOutcome(ok = false, summary = "内容超过上限：$path", errorCode = "hand_too_large")
        }
        val (rel, file) = resolveInWorkspace(path)!!
        if (stopped()) return stoppedOutcome()
        // 2026-11-09 写轮次泄漏修复：轮次严格包住"guard → 写 → finish"，
        // 任何返回/异常/取消都走 finally，正常结束释放，异常则先 finish
        // 再失败；finish 本身失败时 abandon，绝不让轮次永久占锁。
        return withWriteRound(context.taskId, "write_file") {
            workspaceAdapter.guardWrite(file)
            // Write-time parent re-check: resolve again and require the same
            // absolute path, so a swapped-in symlink cannot redirect the write.
            val (_, file2) = resolveInWorkspace(path)!!
            if (file2 != file) {
                return@withWriteRound ToolOutcome(
                    ok = false,
                    summary = "写入路径在检查后发生变化：$path",
                    errorCode = "hand_parent_changed",
                )
            }
            if (stopped()) return@withWriteRound stoppedOutcome()
            try {
                file.parentFile?.mkdirs()
                file.writeText(content, Charsets.UTF_8)
            } catch (e: Exception) {
                return@withWriteRound ToolOutcome(
                    ok = false,
                    summary = "写入失败：$path（${e.message}）",
                    errorCode = "hand_write_failed",
                )
            }
            ToolOutcome(
                ok = true,
                summary = "已写入 $rel（${content.toByteArray(Charsets.UTF_8).size} 字节）",
                data = null,
            )
        }
    }

    private fun handleRun(action: Action, context: ToolContext, stopped: () -> Boolean): ToolOutcome {
        val argv = parseArgv(action.args["argv"])!!
        val cwdArg = action.args["cwd"] as? String ?: ""
        val (_, cwd) = resolveInWorkspace(cwdArg)!!
        if (!cwd.isDirectory) {
            return ToolOutcome(ok = false, summary = "不是文件夹：$cwdArg", errorCode = "hand_not_a_directory")
        }
        val timeout = try {
            val requested = (action.args["timeout"] as? Number)?.toDouble()
            limits.checkTimeout(requested)
        } catch (e: LimitError) {
            return ToolOutcome(ok = false, summary = e.message ?: "超时不合法", errorCode = e.code)
        }
        if (!limits.runSlots.tryAcquire()) {
            return ToolOutcome(
                ok = false,
                summary = "并发超限：同时运行的命令已达上限 ${limits.maxConcurrentRuns} 个",
                errorCode = "hand_concurrency_limited",
            )
        }
        try {
            if (stopped()) return stoppedOutcome()
            val python = pythonInvocation(argv)
            if (python is PythonInvocation.Probe) {
                return handlePythonProbe()
            }
            if (python != null) {
                if (python is PythonInvocation.Unsupported) {
                    return ToolOutcome(
                        ok = false,
                        summary = python.reason,
                        errorCode = "python_unsupported_args",
                    )
                }
                val prepared = preparePython(python, cwd, timeout)
                prepared.error?.let { return it }
                // Python 脚本可能写文件：与其它写命令一样进写轮次保护。
                return withWriteRound(context.taskId, "run_command:python") {
                    workspaceAdapter.guardCommand(argv.joinToString(" "))
                    runPython(prepared.request!!, timeout, stopped)
                }
            }
            if (isReadOnlyRun(argv)) {
                // 只读命令不建写轮次：不会改动工作区，也不该占用写锁。
                return executeProcess(argv, cwd, timeout, stopped)
            }
            return withWriteRound(context.taskId, "run_command") {
                workspaceAdapter.guardCommand(argv.joinToString(" "))
                executeProcess(argv, cwd, timeout, stopped)
            }
        } catch (e: WorkspaceException) {
            // 写轮次/工作区错误必须保留机器码，不能被下面的兜底吞成
            // hand_run_failed（旧实现让 workspace_round_active 看起来像
            // 命令本身失败，误导了排查）。
            throw e
        } catch (e: LimitError) {
            throw e
        } catch (e: Exception) {
            return ToolOutcome(ok = false, summary = "运行失败：${e.message ?: "未知错误"}", errorCode = "hand_run_failed")
        } finally {
            limits.runSlots.release()
        }
    }

    // -- Python：真实执行（2026-11-09 自主开发闭环） -------------------------

    private fun handlePythonProbe(): ToolOutcome {
        val runtime = pythonRuntime ?: return ToolOutcome(
            ok = false,
            summary = "Python 不可用：本进程没有接入 Python 运行时（未模拟执行）。",
            errorCode = "python_unavailable",
        )
        val probe: PythonProbe = runtime.probe()
        if (!probe.available) {
            return ToolOutcome(
                ok = false,
                summary = "Python 不可用：${probe.reason ?: "未知原因"}（未模拟执行）",
                errorCode = "python_unavailable",
                data = probe.rawOutput,
            )
        }
        val version = probe.version ?: "版本未知"
        return ToolOutcome(
            ok = true,
            summary = "Python 可用：${probe.label} · $version（真实探测）",
            data = buildString {
                append("runtime=").append(probe.label).append('\n')
                append("version=").append(version).append('\n')
                probe.executable?.let { append("executable=").append(it).append('\n') }
                probe.rawOutput?.let { append(it) }
            }.trim(),
            workspaceLabel = WORKSPACE_LABEL,
        )
    }

    private fun preparePython(
        invocation: PythonInvocation,
        cwd: File,
        timeoutSeconds: Double,
    ): PreparedPython {
        val timeoutMillis = (timeoutSeconds * 1000).toLong().coerceAtLeast(1_000)
        return when (invocation) {
            is PythonInvocation.Script -> {
                val resolved = resolvePythonScript(invocation.path, cwd)
                if (resolved == null) {
                    return PreparedPython(
                        error = ToolOutcome(
                            ok = false,
                            summary = "不允许的路径：${invocation.path}（不在授权工作区内）",
                            errorCode = "path_not_allowed",
                        ),
                    )
                }
                val (rel, file) = resolved
                if (!file.isFile) {
                    return PreparedPython(
                        error = ToolOutcome(
                            ok = false,
                            summary = "文件不存在：$rel",
                            errorCode = "hand_not_found",
                        ),
                    )
                }
                PreparedPython(
                    request = PythonRunRequest(
                        script = file,
                        args = invocation.args,
                        cwd = cwd,
                        timeoutMillis = timeoutMillis,
                    ),
                )
            }
            is PythonInvocation.Module -> {
                if (!PYTHON_MODULE_PATTERN.matches(invocation.name)) {
                    return PreparedPython(
                        error = ToolOutcome(
                            ok = false,
                            summary = "不允许的模块名：${invocation.name}",
                            errorCode = "python_unsupported_args",
                        ),
                    )
                }
                PreparedPython(
                    request = PythonRunRequest(
                        module = invocation.name,
                        args = invocation.args,
                        cwd = cwd,
                        timeoutMillis = timeoutMillis,
                    ),
                )
            }
            else -> PreparedPython(
                error = ToolOutcome(
                    ok = false,
                    summary = "Python 调用形式不受支持：${invocation::class.simpleName}",
                    errorCode = "python_unsupported_args",
                ),
            )
        }
    }

    /**
     * 2026-11-09：相对脚本路径优先按传入的 cwd 解析；cwd 下找不到时
     * 回退工作区根目录，保持原有根目录相对路径调用兼容。两条路径都走
     * resolveInWorkspace，越界、`..` 等仍会被拒绝。
     */
    private fun resolvePythonScript(rawPath: String, cwd: File): Pair<String, File>? {
        val root = workspaceRoot
        if (root != null && cwd != root && !File(rawPath).isAbsolute) {
            val cwdRelative = try {
                cwd.relativeTo(root).path.replace('\\', '/')
            } catch (_: IllegalArgumentException) {
                ""
            }
            if (cwdRelative.isNotEmpty()) {
                val fromCwd = resolveInWorkspace("$cwdRelative/$rawPath")
                if (fromCwd != null && fromCwd.second.isFile) return fromCwd
            }
        }
        return resolveInWorkspace(rawPath)
    }

    private fun runPython(
        request: PythonRunRequest,
        timeoutSeconds: Double,
        stopped: () -> Boolean,
    ): ToolOutcome {
        val runtime = pythonRuntime ?: return ToolOutcome(
            ok = false,
            summary = "Python 不可用：本进程没有接入 Python 运行时（未模拟执行）。",
            errorCode = "python_unavailable",
        )
        if (stopped()) return stoppedOutcome()
        val result = try {
            runtime.run(request)
        } catch (e: Exception) {
            return ToolOutcome(
                ok = false,
                summary = "Python 执行失败：${e.message ?: "未知错误"}（未模拟执行）",
                errorCode = "python_unavailable",
            )
        }
        if (!result.available) {
            return ToolOutcome(
                ok = false,
                summary = "Python 不可用：${result.failureReason ?: "运行时未就绪"}（未模拟执行）",
                errorCode = "python_unavailable",
                data = buildString {
                    append("stdout=").append(result.stdout).append('\n')
                    append("stderr=").append(result.stderr)
                },
            )
        }
        if (stopped()) return stoppedOutcome()
        val combined = combineOutput(result.stdout, result.stderr)
        val ok = !result.timedOut && result.exitCode == 0 && result.failureReason == null
        return ToolOutcome(
            ok = ok,
            summary = when {
                result.timedOut ->
                    "Python 执行超时（>${timeoutSeconds.toInt()} 秒，已强制结束）"
                result.failureReason != null ->
                    "Python 执行失败：${result.failureReason}"
                else ->
                    "Python 退出码 ${result.exitCode}（${result.runtimeLabel}，真实执行）"
            },
            data = combined.take(MAX_OUTPUT_CHARS),
            errorCode = when {
                result.timedOut -> "hand_timeout"
                !ok -> "hand_run_failed"
                else -> null
            },
        )
    }

    // -- 进程执行（命令输出并发排空，避免管道写满卡死） ---------------------

    private fun executeProcess(
        argv: List<String>,
        cwd: File,
        timeoutSeconds: Double,
        stopped: () -> Boolean,
    ): ToolOutcome {
        val process = try {
            ProcessBuilder(argv)
                .directory(cwd)
                .redirectErrorStream(false)
                .start()
        } catch (e: Exception) {
            return ToolOutcome(
                ok = false,
                summary = "运行失败：${e.message ?: "无法启动进程"}",
                errorCode = "hand_run_failed",
            )
        }
        val stdoutDrain = ProcessDrain(process.inputStream)
        val stderrDrain = ProcessDrain(process.errorStream)
        val stdoutThread = Thread(stdoutDrain, "tool-run-stdout").apply { isDaemon = true }
        val stderrThread = Thread(stderrDrain, "tool-run-stderr").apply { isDaemon = true }
        stdoutThread.start()
        stderrThread.start()
        val deadlineNanos = System.nanoTime() + (timeoutSeconds * 1_000_000_000.0).toLong()
        var timedOut = false
        var stopHit = false
        while (true) {
            // waitFor 轮询同时承接停止与超时：运行中的命令不会无视用户停止。
            if (process.waitFor(100, TimeUnit.MILLISECONDS)) break
            if (stopped()) {
                stopHit = true
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
                break
            }
            if (System.nanoTime() >= deadlineNanos) {
                timedOut = true
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
                break
            }
        }
        stdoutThread.join(5_000)
        stderrThread.join(5_000)
        val stdout = stdoutDrain.text()
        val stderr = stderrDrain.text()
        if (stopHit) return stoppedOutcome()
        if (timedOut) {
            return ToolOutcome(ok = false, summary = "命令超时", errorCode = "hand_timeout", data = combineOutput(stdout, stderr))
        }
        try {
            limits.checkOutput(stdout, stderr)
        } catch (e: LimitError) {
            return ToolOutcome(ok = false, summary = e.message ?: "输出超限", errorCode = e.code)
        }
        val exitCode = process.exitValue()
        return ToolOutcome(
            ok = exitCode == 0,
            summary = "命令退出码 $exitCode",
            data = combineOutput(stdout, stderr),
            errorCode = if (exitCode == 0) null else "hand_run_failed",
        )
    }

    private fun combineOutput(stdout: String, stderr: String): String = buildString {
        if (stdout.isNotEmpty()) append(stdout.take(MAX_OUTPUT_CHARS))
        if (stderr.isNotEmpty()) {
            if (isNotEmpty()) append("\n--- stderr ---\n")
            append(stderr.take(MAX_OUTPUT_CHARS))
        }
    }

    private class ProcessDrain(private val input: InputStream) : Runnable {
        private val builder = StringBuilder()
        private var truncated = false

        override fun run() {
            try {
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    if (builder.length < MAX_CAPTURED_CHARS) {
                        val room = MAX_CAPTURED_CHARS - builder.length
                        builder.append(String(buffer, 0, minOf(read, room), Charsets.UTF_8))
                        if (read > room) truncated = true
                    } else {
                        truncated = true
                    }
                }
            } catch (_: Exception) {
                // 进程被杀/流关闭：已读内容仍然有效。
            }
        }

        fun text(): String = if (truncated) {
            builder.toString() + "\n…（输出超过 $MAX_CAPTURED_CHARS 字符，已截断）"
        } else {
            builder.toString()
        }
    }

    // -- 写轮次生命周期（2026-11-09 泄漏修复） ------------------------------

    private val writeLock = ReentrantLock()

    /**
     * 一次"写轮次"严格包住一次可能改动工作区的工具调用。
     *
     * 旧实现的问题：`beginRound()` 之后从不 `finishRound()`，轮次在
     * ToolRegistry 的进程生命周期内永久活动；第二个任务/会话一调用
     * write_file 就收到 `workspace_round_active`，run_command 又被兜底
     * 改成 `hand_run_failed`，形成"第一轮能用、之后全被锁死"。
     *
     * 现在的保证：
     * - 正常返回 → finally 里 finishRound（保留改动，释放锁）；
     * - 异常/取消 → 同样 finishRound；finish 失败再 abandonRound；
     * - 轮次创建前，若发现同属主的遗留活动轮次（上次调用没走到
     *   finally 的残留），先定点回收再新建；
     * - 不同属主（如 hand 通道）的轮次永不回收，只报错 —— 并发保护
     *   保留，没有删除锁，也没有绕过审批。
     */
    private fun withWriteRound(taskId: String, op: String, block: () -> ToolOutcome): ToolOutcome {
        writeLock.lock()
        try {
            reclaimStaleRound(taskId)
            val ref = workspaceAdapter.beginRound(taskId = taskId, owner = ROUND_OWNER)
            roundEvent("workspace_round_begin", "round=${ref.roundId} task=$taskId op=$op")
            try {
                return block()
            } finally {
                var finished = false
                try {
                    val changeSet = workspaceAdapter.finishRound(ref.roundId)
                    finished = true
                    roundEvent(
                        "workspace_round_finish",
                        "round=${ref.roundId} task=$taskId op=$op changed=${changeSet.files.size}",
                    )
                    // a-3 交付卡：结算成功的轮次才记（空轮次由存储层丢弃，
                    // 不产生幻影交付卡）；记卡失败不影响轮次结算。
                    try {
                        onChangeSet?.invoke(taskId, ref.roundId, changeSet)
                    } catch (_: Exception) {
                    }
                } catch (e: Exception) {
                    roundEvent(
                        "workspace_round_finish_failed",
                        "round=${ref.roundId} op=$op reason=${e.message ?: "未知错误"}",
                    )
                } finally {
                    if (!finished) {
                        val abandoned = try {
                            workspaceAdapter.abandonRound(ref.roundId, "finish failed after $op")
                        } catch (_: Exception) {
                            false
                        }
                        roundEvent(
                            "workspace_round_abandon",
                            "round=${ref.roundId} op=$op abandoned=$abandoned",
                        )
                    }
                }
            }
        } finally {
            writeLock.unlock()
        }
    }

    private fun reclaimStaleRound(taskId: String) {
        val active = try {
            workspaceAdapter.activeRound()
        } catch (_: Exception) {
            null
        } ?: return
        if (active.owner != ROUND_OWNER) {
            throw WorkspaceException(
                "workspace_round_active",
                "工作区被其他组件占用：round=${active.roundId}（owner=${active.owner ?: "unknown"}）",
            )
        }
        // 已持有本进程写锁 ⇒ 同属主不可能还有正在执行的调用；这个
        // 活动轮次只可能是上次调用没走到 finally 的遗留。
        val reclaimed = workspaceAdapter.abandonRound(
            active.roundId,
            "stale $ROUND_OWNER round reclaimed before task=$taskId",
        )
        roundEvent(
            "workspace_round_reclaimed",
            "round=${active.roundId} prev_task=${active.taskId} new_task=$taskId reclaimed=$reclaimed",
        )
        if (!reclaimed) {
            throw WorkspaceException("workspace_round_active", "无法回收遗留写轮次：${active.roundId}")
        }
    }

    private fun roundEvent(kind: String, detail: String) {
        try {
            onRoundEvent?.invoke(kind, detail)
        } catch (_: Exception) {
            // 观测事件绝不能影响工具调用本身。
        }
    }

    private fun stoppedOutcome(): ToolOutcome = ToolOutcome(
        ok = false,
        summary = "已停止：未继续执行",
        errorCode = "turn_stopped",
    )

    private class PreparedPython(
        val request: PythonRunRequest? = null,
        val error: ToolOutcome? = null,
    )

    private sealed class PythonInvocation {
        object Probe : PythonInvocation()
        data class Script(val path: String, val args: List<String>) : PythonInvocation()
        data class Module(val name: String, val args: List<String>) : PythonInvocation()
        data class Unsupported(val reason: String) : PythonInvocation()
    }

    private fun handleDispatch(action: Action): ToolOutcome {
        val goal = action.args["goal"] as String
        val title = (action.args["title"] as? String)?.takeIf { it.isNotBlank() } ?: goal.take(40)
        // 阶段 3（A1 P1）：agent_id 指派（TaskScheduler.claimTask 本就
        // 写 assigned_agent_id + 90 秒租约；这里只把指派记进任务行）。
        val agentId = (action.args["agent_id"] as? String)?.takeIf { it.isNotBlank() }
        // V5 跨设备开发（2026-11-12，A1/D5 新口径）：任务建行时带 projectId。
        // 参数优先：工具参数 project_id 非空就用参数，两者冲突时参数赢；
        // 无参数时取当前聊天绑定；两者都没有时任务不带项目（允许派活，
        // 走回落 + folder_fallback 事件，不拦下）。provider 抛错时当
        // 无绑定处理，不让一次取绑定失败毁掉整次派任务。
        val paramProjectId = (action.args["project_id"] as? String)?.takeIf { it.isNotBlank() }
        val boundProjectId = try {
            currentProjectId?.invoke()?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
        val projectId = paramProjectId ?: boundProjectId
        val now = System.currentTimeMillis() / 1000
        val record = TaskRecord(
            taskId = java.util.UUID.randomUUID().toString(),
            title = title,
            description = goal,
            state = "queued",
            projectId = projectId,
            assignedAgentId = agentId,
            createdAt = now,
            updatedAt = now,
            version = 1,
        )
        return try {
            val created = taskStore.createTask(record)
            val assigned = if (agentId != null) "，已指派给 $agentId（等其认领，本地不代执行）" else ""
            ToolOutcome(ok = true, summary = "任务已创建，进入 queued：${created.taskId}$assigned", data = created.taskId)
        } catch (e: Exception) {
            ToolOutcome(ok = false, summary = "创建任务失败：${e.message}", errorCode = "dispatch_failed")
        }
    }

    private fun handleAskUser(action: Action): ToolOutcome {
        val question = action.args["question"] as String
        // F1（2026-11-08）：聊天路径上 taskId 是会话 id，不存在
        // 以其为键的任务行——提问不改任务状态。旧逻辑的“任务进入 blocked +
        // needs_attention”是虚假承诺（getTask 恒为 null，分支恒跳过），已删除。
        // 终结语义由引擎实现：成功即结束本轮，问题以文字交给用户。
        return ToolOutcome(ok = true, summary = "已向用户提问，等用户回答：$question", data = question)
    }

    /**
     * 解析 python 调用形式：仅支持版本探测、-m 模块、脚本加参数三种。
     * 不支持的参数明确报错，不猜、不静默改成别的执行方式。
     */
    private fun pythonInvocation(argv: List<String>): PythonInvocation? {
        if (argv.isEmpty()) return null
        val name = argv[0].substringAfterLast('/')
        if (name !in PYTHON_NAMES) return null
        var index = 1
        while (index < argv.size && argv[index] in PYTHON_IGNORED_FLAGS) index++
        if (index >= argv.size) {
            return PythonInvocation.Unsupported(
                "python 调用缺少脚本或模块：请用 [\"python3\", \"<工作区脚本>\"] " +
                    "或 [\"python3\", \"-m\", \"unittest\"]；不启动交互式解释器。",
            )
        }
        val token = argv[index]
        return when {
            token == "--version" || token == "-V" -> PythonInvocation.Probe
            token == "-m" -> {
                if (index + 1 >= argv.size) {
                    PythonInvocation.Unsupported("python -m 缺少模块名")
                } else {
                    PythonInvocation.Module(argv[index + 1], argv.drop(index + 2))
                }
            }
            token.startsWith("-") -> PythonInvocation.Unsupported(
                "不支持的 python 参数：$token（支持 --version / -V / -m / 脚本路径；" +
                    "内联 -c 一律拒绝）",
            )
            else -> PythonInvocation.Script(token, argv.drop(index + 1))
        }
    }

    private fun denied(
        action: Action,
        context: ToolContext,
        code: String,
        reason: String,
    ): ToolOutcome {
        // P1 §6：未配置态的拒绝结果也带三态身份字段（与 handleList 一致）。
        val unconfigured = code == "workspace_not_configured"
        val outcome = ToolOutcome(
            ok = false,
            summary = reason,
            errorCode = code,
            workspaceState = if (unconfigured) workspaceState().label else null,
            path = if (unconfigured) "" else null,
            workspaceLabel = if (unconfigured) WORKSPACE_LABEL else null,
            host = if (unconfigured) context.deviceId else null,
        )
        audit(action, context, outcome, null)
        return outcome
    }

    private fun audit(action: Action, context: ToolContext, outcome: ToolOutcome, path: String?) {
        val entry = AuditEntry(
            ts = auditTimestamp(),
            taskId = context.taskId,
            agentId = context.agentId,
            action = action.tool,
            path = path ?: (action.args["path"] as? String),
            command = if (action.tool == "run_command") parseArgv(action.args["argv"]) else null,
            ok = outcome.ok,
            errorCode = outcome.errorCode,
            result = null,
        )
        try {
            auditLog.record(entry)
        } catch (_: Exception) {
            // Audit must never break the tool call itself.
        }
    }

    private fun auditTimestamp(): String {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        format.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return format.format(java.util.Date())
    }
}
