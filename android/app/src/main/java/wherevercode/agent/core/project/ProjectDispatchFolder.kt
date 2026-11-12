package wherevercode.agent.core.project

import wherevercode.agent.core.agent.AgentRecord
import wherevercode.agent.core.task.TaskRecord

/**
 * 派活文件夹来源（V5 跨设备开发，2026-11-12）：
 * - PROJECT：任务的项目在目标设备上有 DEVICE 家检出链接，派活
 *   文件夹就是该检出文件夹（工作目录由任务/项目定，不由 Agent 定）。
 * - AGENT_FALLBACK：无可用检出链接，回落到 Agent 自己的文件夹
 *   （今日行为）；回落必须如实记事件、详情与对话里标明，绝不静默。
 */
enum class DispatchFolderSource {
    PROJECT,
    AGENT_FALLBACK,
}

/** 一次派活文件夹解析的结果：文件夹 + 它是哪来的。 */
data class DispatchFolder(
    val folder: String,
    val source: DispatchFolderSource,
)

/**
 * 派活文件夹解析结果（V5 跨设备开发，2026-11-12，D7 新口径）：
 * - [Resolved]：能派，文件夹 + 来源；
 * - [NotDispatchable]：不能派——项目家是 git 家且目标设备上无
 *   DEVICE 检出，代码不在设备上；调用方须把任务留 queued +
 *   needs_attention 并如实写原因，绝不派到空目录。
 */
sealed interface DispatchFolderResult {
    data class Resolved(val folder: DispatchFolder) : DispatchFolderResult
    data class NotDispatchable(val reason: String) : DispatchFolderResult
}

/**
 * 解析任务在目标设备上的派活文件夹（纯函数）。
 *
 * 口径（测绘 MAP §2.3，父代理已定）：当前家优先用
 * [ProjectStore.getActiveHome]——用户显式切过家才有意义；只认
 * DEVICE 家（`homeKind == DEVICE && hostId == agent.deviceId`）。
 * git 家（GITHUB/GITLAB/GITEE）本身不是设备文件夹，不算命中；
 * LOCAL 家是本机文件夹，也不是目标设备上的文件夹，不算命中。
 *
 * 判定顺序：
 * 1. 当前家是目标设备上的 DEVICE 家 → [DispatchFolderResult.Resolved] (PROJECT)。
 * 2. 当前家不是，但该项目在目标设备上另有 DEVICE 家检出 → 同样
 *    Resolved (PROJECT)。D7 的“无 DEVICE 检出”指一个都没有；检出
 *    还在设备上时不拦——哪怕当前家已被切到 git 家，代码仍在设备上。
 * 3. 当前家是 git 家、且目标设备上没有任何 DEVICE 家 → [DispatchFolderResult.NotDispatchable]（D7）。
 * 4. 其余（无项目 / 无家 / 当前家是 LOCAL 家等）→ 回落 Agent
 *    文件夹 [DispatchFolderSource.AGENT_FALLBACK]；Agent 也没有
 *    文件夹时返回 null（上层按“远程目标不可达”处理，不派活）。
 */
fun resolveDispatchFolder(
    projectStore: ProjectStore?,
    task: TaskRecord,
    agent: AgentRecord,
): DispatchFolderResult? = resolveDispatchFolderForProject(projectStore, task.projectId, agent)

/** 与 [resolveDispatchFolder] 同口径，按 projectId 直接解析（无任务行时用，如远端聊天）。 */
fun resolveDispatchFolderForProject(
    projectStore: ProjectStore?,
    projectId: String?,
    agent: AgentRecord,
): DispatchFolderResult? {
    val deviceId = agent.deviceId
    if (projectId != null && deviceId != null && projectStore != null) {
        val activeHome = try {
            projectStore.getActiveHome(projectId)
        } catch (_: Exception) {
            null
        }
        if (activeHome != null &&
            activeHome.homeKind == ProjectHomeKind.DEVICE &&
            activeHome.hostId == deviceId
        ) {
            val folder = activeHome.workspacePath.takeIf { it.isNotBlank() }
            if (folder != null) {
                return DispatchFolderResult.Resolved(
                    DispatchFolder(folder, DispatchFolderSource.PROJECT),
                )
            }
        }
        // 当前家不是目标设备上的 DEVICE 家：再看该项目在该设备上有
        // 没有任何 DEVICE 家检出（D7 的“无 DEVICE 检出”按有没有算，
        // 不按当前家算）。
        val deviceHome = try {
            projectStore.locationsForProject(projectId).firstOrNull {
                it.homeKind == ProjectHomeKind.DEVICE && it.hostId == deviceId &&
                    it.workspacePath.isNotBlank()
            }
        } catch (_: Exception) {
            null
        }
        if (deviceHome != null) {
            return DispatchFolderResult.Resolved(
                DispatchFolder(deviceHome.workspacePath, DispatchFolderSource.PROJECT),
            )
        }
        // D7：项目家是 git 家、且目标设备上无 DEVICE 检出 → 不派。
        // 原因写清项目名、git 家地址（workspacePath 是 owner/repo
        // 形地址，如 huaerdaidai-gif/wherevercode-agent）、目标设备与
        // “代码不在设备上”；详情错误区与 not_dispatchable 事件都用它。
        if (activeHome != null && activeHome.gitHost() != null) {
            val projectName = try {
                projectStore.getProject(projectId)?.name
            } catch (_: Exception) {
                null
            } ?: projectId
            return DispatchFolderResult.NotDispatchable(
                "项目 $projectName 在设备 $deviceId 上无检出链接（只有 git 家 ${activeHome.workspacePath}），代码不在设备上，未派活",
            )
        }
    }
    val agentFolder = agent.deviceFolder?.takeIf { it.isNotBlank() } ?: return null
    return DispatchFolderResult.Resolved(DispatchFolder(agentFolder, DispatchFolderSource.AGENT_FALLBACK))
}

/**
 * 任务是否不可派（D7）：[resolveDispatchFolder] 为
 * [DispatchFolderResult.NotDispatchable] 时返回其原因，否则 null。
 * 泵用它在认领前拦下任务（留 queued + needs_attention + 原因）。
 */
fun dispatchBlockedReason(
    projectStore: ProjectStore?,
    task: TaskRecord,
    agent: AgentRecord,
): String? =
    when (val result = resolveDispatchFolder(projectStore, task, agent)) {
        is DispatchFolderResult.NotDispatchable -> result.reason
        is DispatchFolderResult.Resolved -> null
        null -> null
    }

/**
 * 回落事件的 reason 原文（D4：详情行从事件反查，reason 须写清项目名、
 * 目标设备与回落到的文件夹；解析侧用同一模板抽文件夹）。无绑定任务
 * 没有项目名，如实写“未绑定项目”，不编一个。
 */
fun folderFallbackReason(
    projectName: String?,
    deviceId: String,
    agentFolder: String,
): String =
    if (projectName.isNullOrBlank()) {
        "任务未绑定项目，在设备 $deviceId 上无项目检出链接，使用 Agent 文件夹 $agentFolder 执行"
    } else {
        "项目 $projectName 在设备 $deviceId 上无检出链接，使用 Agent 文件夹 $agentFolder 执行"
    }

/**
 * 项目文件夹命中事件的 reason 原文（D4）：与回落事件同一模板族，
 * 详情行从事件反查时按“使用检出文件夹 … 执行”抽文件夹。
 */
fun folderResolvedReason(
    projectName: String?,
    deviceId: String,
    folder: String,
): String {
    val name = projectName?.takeIf { it.isNotBlank() } ?: "未命名项目"
    return "项目 $name 在设备 $deviceId 上使用检出文件夹 $folder 执行"
}
