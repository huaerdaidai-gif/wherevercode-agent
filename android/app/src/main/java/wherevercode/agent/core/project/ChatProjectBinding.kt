package wherevercode.agent.core.project

/**
 * 聊天绑定的项目（2026-11-11，maintainer：新对话悄悄清空绑定是 bug）。
 *
 * 规则（与对话/任务/项目主流分支 ae21dffc 的 session→project 绑定同
 * 向，本分支基底 update5 是全局单值模型，这里先按全局模型修）：
 * - 聊天项目 = 最后在项目页打开的项目（打开项目时 [setProject]）。
 * - 新对话保留上次打开的项目（[onNewConversation] 故意不清空），
 *   横幅照常显示；想换项目用聊天里的选项目入口，不用回项目页。
 * - 持久化只存项目 id（SharedPreferences `wherevercode_chat` 的
 *   `chat_project_id`，重启后仍记得）；读/写失败由调用方兜底。
 *
 * 合包注意（见 DELIVERY §6）：ae21dffc 的 per-session 模型里，
 * ChatViewModel.newConversation() 同样不能再把 currentProjectId 置
 * 空——新会话创建时要继承上次的项目绑定，否则合包后这个 bug 回来。
 */
class ChatProjectBinding(
    load: () -> String?,
    private val save: (String?) -> Unit,
) {
    var projectId: String? = load()
        private set

    /** 切换聊天项目（点项目行 / 聊天选项目入口都走这里）：立即生效并持久化。 */
    fun setProject(projectId: String?) {
        this.projectId = projectId
        save(projectId)
    }

    /** 主动清除绑定（目前无调用方保留给以后用；新对话不走这里）。 */
    fun clear() = setProject(null)

    /**
     * 开新对话：保留上次打开的项目，不清空。
     * （2026-11-11 之前这里调 clear()，maintainer 开新对话发测试消息时绑
     * 定被悄悄清掉，聊天里又没选项目入口，直接卡死。）
     */
    fun onNewConversation() {
        // 故意不清空：新对话继承上次的项目绑定。
    }
}

/**
 * 注册重名时显示名自动加数字后缀（与 uniqueProjectSlug 的 -2/-3 对照，
 * 只看显示名是否撞上，不动 id 的生成规则）。
 * 例：已有 "wherevercode-agent" 时再注册同名 → "wherevercode-agent 2"。
 */
fun uniqueProjectDisplayName(name: String, existingNames: Collection<String>): String {
    if (name !in existingNames) return name
    var suffix = 2
    while ("$name $suffix" in existingNames) suffix++
    return "$name $suffix"
}

/**
 * 聊天选项目对话框的条目：每项是（显示行，项目 id）。
 * 显示行 = 显示名（家/仓库标签），GitHub 家直接摆仓库地址；不编假项目，
 * 空列表时返回空（调用方给“去项目页添加”入口，不列假条目）。
 */
fun chatProjectPickerItems(
    projects: List<Project>,
    homeLabel: (Project) -> String,
): List<Pair<String, String>> =
    projects.map { project -> "${project.name}（${homeLabel(project)}）" to project.projectId }
