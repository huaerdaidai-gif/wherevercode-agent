package wherevercode.agent.core.search

/**
 * 全局搜索纯逻辑（核心功能推进第 1 项，2026-11-15；纯 Kotlin，可单测）。
 *
 * 搜索范围（与 UI 文案、注册表 note 同一口径，不夸大）：
 * - 会话：本机会话的标题与消息正文（用户与助手消息，调用方组装时已过滤）；
 * - 项目：项目名、项目 id、仓库地址、家文件夹路径；
 * - 任务：任务标题、任务描述、任务 id。
 * 频道消息走平台远端实时流（ChannelFeed），本机没有可枚举的消息缓存，
 * 不在本搜索范围内；服务端搜索也不存在，故不假装能搜频道。
 *
 * 匹配与排序规则：
 * - 查询词 trim 后为空 → 三组皆空（UI 显示引导文案，不假装有结果）。
 * - 不区分大小写的子串包含匹配（lowercase 后 contains）。
 * - 会话：标题命中优先于仅正文命中；同级按 updatedAt 倒序（最近优先）。
 * - 项目：名称命中优先于仅详情命中；同级按创建时间倒序。
 * - 任务：标题命中优先于仅描述 / id 命中；同级按 updatedAt 倒序。
 * - 每组上限 [MAX_PER_GROUP] 条，截断发生在排序之后（保留最靠前的）。
 *
 * 本类只做匹配与排序，不读任何存储；数据由调用方用各 Store 的真实
 * 列出 API 组装（见 MainActivity 的 buildSearchable* 组装函数）。
 */

/** 可搜索的会话：标题 + 正文文本（消息内容）+ 更新时间。 */
data class SearchableConversation(
    /** 会话账本的 sessionId（导航用：打开会话的唯一键）。 */
    val sessionId: String,
    val title: String,
    /** 正文候选文本（用户与助手消息原文）；标题不在此列，单独优先匹配。 */
    val bodyTexts: List<String>,
    val updatedAtEpochSeconds: Long,
)

/** 可搜索的项目：名称 + 详情文本（id / 仓库地址 / 家文件夹路径）。 */
data class SearchableProject(
    val projectId: String,
    val name: String,
    val details: List<String>,
    val createdAtEpochSeconds: Long,
)

/** 可搜索的任务：标题 + 描述 + 状态文案（状态只展示，不参与匹配）。 */
data class SearchableTask(
    val taskId: String,
    val title: String,
    val description: String?,
    val stateLabel: String,
    val updatedAtEpochSeconds: Long,
)

enum class SearchGroup {
    CONVERSATION,
    PROJECT,
    TASK,
}

/** 一条搜索命中：id 是导航键，snippet 是命中摘录（或标题命中时的副信息）。 */
data class GlobalSearchHit(
    val group: SearchGroup,
    /** 会话 = sessionId；项目 = projectId；任务 = taskId。 */
    val id: String,
    val title: String,
    val snippet: String,
    /** true = 标题（主字段）命中；false = 仅详情 / 正文命中。 */
    val titleMatched: Boolean,
)

/** 分组搜索结果（组内已排序并截断）。 */
data class GlobalSearchResults(
    val conversations: List<GlobalSearchHit>,
    val projects: List<GlobalSearchHit>,
    val tasks: List<GlobalSearchHit>,
) {
    val isEmpty: Boolean
        get() = conversations.isEmpty() && projects.isEmpty() && tasks.isEmpty()

    val totalCount: Int
        get() = conversations.size + projects.size + tasks.size
}

object GlobalSearch {

    /** 每组最多返回条数。 */
    const val MAX_PER_GROUP: Int = 20

    /** 摘录时命中点前后各保留的字符数。 */
    const val SNIPPET_RADIUS: Int = 30

    fun search(
        query: String,
        conversations: List<SearchableConversation>,
        projects: List<SearchableProject>,
        tasks: List<SearchableTask>,
    ): GlobalSearchResults {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) {
            return GlobalSearchResults(emptyList(), emptyList(), emptyList())
        }
        return GlobalSearchResults(
            conversations = searchConversations(needle, conversations),
            projects = searchProjects(needle, projects),
            tasks = searchTasks(needle, tasks),
        )
    }

    private fun searchConversations(
        needle: String,
        conversations: List<SearchableConversation>,
    ): List<GlobalSearchHit> {
        val hits = mutableListOf<Pair<GlobalSearchHit, Long>>()
        for (conversation in conversations) {
            val titleMatched = conversation.title.lowercase().contains(needle)
            val bodyMatch = conversation.bodyTexts.firstOrNull {
                it.lowercase().contains(needle)
            }
            if (!titleMatched && bodyMatch == null) continue
            val snippet = when {
                bodyMatch != null -> excerpt(bodyMatch, needle)
                else -> preview(conversation.bodyTexts.firstOrNull().orEmpty())
            }
            hits += GlobalSearchHit(
                group = SearchGroup.CONVERSATION,
                id = conversation.sessionId,
                title = conversation.title,
                snippet = snippet,
                titleMatched = titleMatched,
            ) to conversation.updatedAtEpochSeconds
        }
        return hits
            .sortedWith(
                compareBy<Pair<GlobalSearchHit, Long>> { if (it.first.titleMatched) 0 else 1 }
                    .thenByDescending { it.second },
            )
            .take(MAX_PER_GROUP)
            .map { it.first }
    }

    private fun searchProjects(
        needle: String,
        projects: List<SearchableProject>,
    ): List<GlobalSearchHit> {
        val hits = mutableListOf<Pair<GlobalSearchHit, Long>>()
        for (project in projects) {
            val titleMatched = project.name.lowercase().contains(needle)
            val detailMatch = project.details.firstOrNull {
                it.lowercase().contains(needle)
            }
            if (!titleMatched && detailMatch == null) continue
            val snippet = when {
                detailMatch != null && !titleMatched -> detailMatch
                else -> project.details.firstOrNull().orEmpty()
            }
            hits += GlobalSearchHit(
                group = SearchGroup.PROJECT,
                id = project.projectId,
                title = project.name,
                snippet = snippet,
                titleMatched = titleMatched,
            ) to project.createdAtEpochSeconds
        }
        return hits
            .sortedWith(
                compareBy<Pair<GlobalSearchHit, Long>> { if (it.first.titleMatched) 0 else 1 }
                    .thenByDescending { it.second },
            )
            .take(MAX_PER_GROUP)
            .map { it.first }
    }

    private fun searchTasks(
        needle: String,
        tasks: List<SearchableTask>,
    ): List<GlobalSearchHit> {
        val hits = mutableListOf<Pair<GlobalSearchHit, Long>>()
        for (task in tasks) {
            val titleMatched = task.title.lowercase().contains(needle)
            val description = task.description.orEmpty()
            val bodyMatched = description.lowercase().contains(needle) ||
                task.taskId.lowercase().contains(needle)
            if (!titleMatched && !bodyMatched) continue
            val snippet = when {
                !titleMatched && description.lowercase().contains(needle) ->
                    excerpt(description, needle)
                !titleMatched -> task.taskId
                description.isNotBlank() ->
                    "${task.stateLabel} · ${preview(description)}"
                else -> task.stateLabel
            }
            hits += GlobalSearchHit(
                group = SearchGroup.TASK,
                id = task.taskId,
                title = task.title,
                snippet = snippet,
                titleMatched = titleMatched,
            ) to task.updatedAtEpochSeconds
        }
        return hits
            .sortedWith(
                compareBy<Pair<GlobalSearchHit, Long>> { if (it.first.titleMatched) 0 else 1 }
                    .thenByDescending { it.second },
            )
            .take(MAX_PER_GROUP)
            .map { it.first }
    }

    /** 命中点前后文摘录：折叠空白，命中点前后各留 [SNIPPET_RADIUS] 字，越界加省略号。 */
    fun excerpt(text: String, needle: String): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        val idx = flat.lowercase().indexOf(needle)
        if (idx < 0) return preview(flat)
        val start = maxOf(0, idx - SNIPPET_RADIUS)
        val end = minOf(flat.length, idx + needle.length + SNIPPET_RADIUS)
        return buildString {
            if (start > 0) append("…")
            append(flat.substring(start, end))
            if (end < flat.length) append("…")
        }
    }

    /** 无命中点时的预览：折叠空白后截前 72 字。 */
    fun preview(text: String): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= 72) flat else flat.take(72) + "…"
    }
}
