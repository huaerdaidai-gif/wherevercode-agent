package wherevercode.agent.ui

import wherevercode.agent.R

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import wherevercode.agent.core.project.Project
import wherevercode.agent.core.project.ProjectHomeKind
import wherevercode.agent.core.project.ProjectLocation
import wherevercode.agent.core.project.ProjectStore
import wherevercode.agent.core.project.gitRepoDisplayLabel
import wherevercode.agent.core.workspace.FileWorkspaceAdapter

/**
 * 项目页控制器（阶段 3，U2/批 4）：文件树 + 代码查看/编辑。
 *
 * 编辑是真的编辑：走 [WorkspaceEditSession]（WorkspaceAdapter 写轮次，
 * 保存前二次确认，改动记录 + 行级 diff，undo()/keep() 真实生效），
 * 不是只读视图上贴的假按钮。查看仍默认只读，点“编辑”才进编辑态。
 * 行号 + 基础语法高亮（[CodeHighlight]）画在代码视图上；文件树
 * 支持搜索；大文件分 512 KB / 2 MB 两档加载，大目录分批加载。
 */
class ProjectScreenController(
    private val context: Context,
    private val rootLabel: TextView,
    private val treePane: View,
    private val treeList: LinearLayout,
    private val treeScroll: ScrollView,
    private val emptyView: TextView,
    private val searchInput: EditText,
    private val treeMoreButton: TextView,
    private val codePane: View,
    private val codePath: TextView,
    private val codeMeta: TextView,
    private val codeContent: TextView,
    private val codeEditor: EditText,
    private val buttonCodeEdit: TextView,
    private val buttonCodeSave: TextView,
    private val buttonCodeUndo: TextView,
    private val buttonCodeKeep: TextView,
    private val buttonCodeMore: TextView,
    private val buttonCodeCopy: TextView,
    private val onAskAgent: (String) -> Unit,
    private val onCopyText: (String) -> Unit,
    private val onToast: (String) -> Unit,
    // 项目列表（V5 项目添加，2026-11-11）：项目住在设备上，列表在先，
    // 本机工作区文件树退为列表里的一行入口（现有树/查看器不动）。
    private val projectListPane: LinearLayout,
    private val projectList: LinearLayout,
    private val projectListEmpty: TextView,
    private val buttonAddProject: TextView,
    private val projectStore: ProjectStore,
    /** 家设备 id → 显示名（本机工作区不走这里）。 */
    private val deviceDisplayName: (String) -> String,
    /** 旧的单一加项目回调（回退用；新调用方走下面的“打开文件夹”唯一入口回调）。 */
    private val onAddProject: () -> Unit,
    /** “打开文件夹”：唯一入口，点开是按位置种类列三项的对话框（本机 / 远程 / git）。 */
    private val onOpenFolder: () -> Unit = onAddProject,
    /** 长按管理里的“指定家…”/“换家…”：走指定家单子（只存位置）。 */
    private val onAssignHome: (Project) -> Unit,
    /**
     * 选中行上的“进入”：打开项目（有家就浏览家内容，无家就去指定家）。
     * 点行本身不是打开——点行是选定/取消选定（见 [onToggleSelectProject]）。
     */
    private val onOpenProject: (Project) -> Unit,
    /** 当前聊天项目 id（MainActivity 的 ChatProjectBinding）：选中态按它画。 */
    private val chatProjectId: () -> String? = { null },
    /**
     * 点行 = 选定/取消选定（2026-11-11，maintainer：点一下选、再点一下取消，
     * 直接在列表上做开关，不弹窗、不分步）。选定即生效为当前聊天项目
     * （调用方绑定 chat_project_id）；取消选定即清除绑定。
     */
    private val onToggleSelectProject: (Project) -> Unit = {},
    /**
     * 项目被删除后（用户在管理单里删的）：调用方清掉可能留下的绑定——
     * 删的是当前选中项目时，选中态与聊天绑定一起清，不留野绑定。
     */
    private val onDeleteProject: (Project) -> Unit = {},
) {
    companion object {
        /** 大文件首档：只读前 512 KB；点“加载更多”后读前 2 MB。 */
        const val READ_TIER_SMALL: Int = 512 * 1024
        const val READ_TIER_LARGE: Int = 2 * 1024 * 1024

        /** 大目录首批条目数；点“加载更多”每次追加一批。 */
        const val TREE_PAGE_SIZE: Int = 400

        /**
         * 长按管理的操作列表（纯函数，单测覆盖）：无家时“指定家…”、有家时
         * “换家…”，再加“改名…”与“删除项目”。“浏览”不在这里——点开
         * 项目本身就是浏览，管理单里不再重复。
         */
        fun manageActions(home: ProjectLocation?): List<String> {
            val actions = mutableListOf<String>()
            actions += if (home != null) "换家…" else "指定家…"
            actions += "改名…"
            actions += "删除项目"
            return actions
        }
    }

    private val browser = WorkspaceBrowser(
        java.io.File(context.filesDir, "workspace").apply { mkdirs() },
    )
    private val editSession: WorkspaceEditSession by lazy {
        val workspaceDir = java.io.File(context.filesDir, "workspace").apply { mkdirs() }
        val taskRootDir = java.io.File(context.filesDir, "tasks").apply { mkdirs() }
        WorkspaceEditSession(FileWorkspaceAdapter(workspaceDir, taskRootDir), browser)
    }
    private val expanded = LinkedHashSet<String>()
    private var currentFile: String? = null
    private var currentRead: WorkspaceRead? = null
    private var readLimit: Int = READ_TIER_SMALL
    private var treeLimit: Int = TREE_PAGE_SIZE
    private var searchQuery: String = ""
    private var editing: Boolean = false
    /** 代码查看器是否从项目列表打开（任务详情链接）：是则返回时回列表，否则回文件树。 */
    private var codeOpenedFromList: Boolean = false

    init {
        codePane.visibility = View.GONE
        rootLabel.text = "本机工作区 · ${browser.rootPath}"
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString().orEmpty()
                render()
            }
        })
        treeMoreButton.setOnClickListener {
            treeLimit += TREE_PAGE_SIZE
            render()
        }
        buttonCodeEdit.setOnClickListener { startEdit() }
        buttonCodeSave.setOnClickListener { saveEdit() }
        buttonCodeUndo.setOnClickListener { undoEdit() }
        buttonCodeKeep.setOnClickListener { keepEdit() }
        buttonCodeMore.setOnClickListener { loadMoreFile() }
        buttonCodeCopy.setOnClickListener { copyCode() }
        updateEditButtons()
        // “打开文件夹”是唯一入口（2026-11-12，maintainer：项目来源收成一个）：
        // 列表底部只有这一颗按钮，点开是一个对话框，里面按位置种类列三项，
        // 每项有名 + 一行说明；不再有三个并列入口散在页面底部。
    }

    /**
     * 项目页默认视图 = 项目列表（V5 项目添加，2026-11-11；老项目模型收口）。
     * 列表每行是真实存过的逻辑项目 + 它的家位置（名 · 家设备 · 文件夹）。
     * 没有位置的项目也展示（“未指定家”，老项目浏览器的做法）。
     *
     * 点选（2026-11-11，maintainer：点一下选、再点一下取消，直接在列表上）：
     * 点行 = 选定/取消选定（选中态对勾 + 高亮，选定即当前聊天项目）；
     * 选中行上出现可见的“进入”，点它才打开项目（浏览家内容，无家去
     * 指定家）。“···”与长按出管理（指定家/换家、改名、删除）。
     *
     * 主页面只留项目列表 + 底部一个“打开文件夹”入口（2026-11-12，maintainer：
     * 项目来源收成一个）：点开是一个对话框，里面按位置种类列三项（本机
     * 文件夹、远程设备上的文件夹、git 仓库地址），每项有名 + 一行说明。
     * “本机工作区”文件树仍可从本机家项目的浏览进入（openLocalFolder），
     * 不在主页面占一行。
     */
    fun refresh() {
        showProjectList()
    }

    fun showProjectList() {
        exitEditMode(discard = true)
        currentFile = null
        currentRead = null
        codePane.visibility = View.GONE
        treePane.visibility = View.GONE
        searchInput.visibility = View.GONE
        projectListPane.visibility = View.VISIBLE
        val projects = try {
            projectStore.listProjects()
        } catch (_: Exception) {
            emptyList()
        }
        rootLabel.text = if (projects.isEmpty()) "还没有项目" else "${projects.size} 个项目"
        projectList.removeAllViews()
        projectListEmpty.visibility = if (projects.isEmpty()) View.VISIBLE else View.GONE
        if (projects.isEmpty()) {
            projectListEmpty.text =
                "还没有项目。\n打开一个文件夹就能加项目：本机工作区里的文件夹，或已配对设备上的文件夹；也可以粘一个 GitHub / GitLab / Gitee 仓库地址登记位置，经 API 读。\n局域网 NAS 与网盘同步文件夹不用单独选：NAS 挂在设备上就是远程设备文件夹，同步下来的就是本机文件夹。"
        }
        projects.forEach { project ->
            projectList.addView(projectRow(project))
        }
        buttonAddProject.setOnClickListener { onOpenFolder() }
    }

    /** 本机工作区文件树（原来的项目页视图；从项目列表点“本机工作区”进来）。 */
    fun showWorkspace() {
        expanded.clear()
        projectListPane.visibility = View.GONE
        searchInput.visibility = View.VISIBLE
        rootLabel.text = "本机工作区 · ${browser.rootPath}"
        showTree()
        render()
    }

    /**
     * 打开本机家文件夹：切到本机工作区文件树，把该文件夹一路展开。
     * 只展开这一路，不动别的文件夹；文件夹不存在时树如实显示空态，
     * 不编假目录。
     */
    fun openLocalFolder(relativePath: String) {
        expanded.clear()
        projectListPane.visibility = View.GONE
        searchInput.visibility = View.VISIBLE
        rootLabel.text = "本机 · $relativePath"
        var prefix = ""
        relativePath.split("/").filter { it.isNotEmpty() }.forEach { part ->
            prefix = if (prefix.isEmpty()) part else "$prefix/$part"
            expanded.add(prefix)
        }
        showTree()
        render()
    }

    /** 项目的当前家（active home；未设过时为第一个家；没有 = 未指定家）。 */
    private fun homeOf(project: Project) = try {
        projectStore.getActiveHome(project.projectId)
    } catch (_: Exception) {
        null
    }

    /**
     * 项目行（2026-11-11 项目页清理，maintainer：增删改添要看得见）：
     * 点行 = 选定/取消选定（选中态：行首对勾 + 高亮底，选中即当前聊天
     * 项目）；选中行上出现可见的“进入”，点它才打开项目；行尾“···”=
     * 管理（指定家/换家、改名、删除，与长按同一张单子）；长按行同样出
     * 管理。管理以前只藏在长按里，页面上没有任何提示，maintainer 连续两次
     * 找不到——藏着的等于没有。
     *
     * 长按链路核查（同批）：行本身 isClickable + isFocusable，
     * setOnClickListener（选定）+ setOnLongClickListener（管理，
     * 返回 true）；两个子 TextView 默认不吞事件；父级是 ScrollView，
     * 只拦截滑动手势、不拦截静止长按；Robolectric“长按出管理对话
     * 框”测试通过——链路本身正常，问题只是入口不可见，本轮只补
     * 可见入口，不动链路。
     */
    /** 项目的全部家（主流模型：家的列表）。 */
    private fun homesOf(project: Project): List<ProjectLocation> = try {
        projectStore.locationsForProject(project.projectId)
    } catch (_: Exception) {
        emptyList()
    }

    private fun projectRow(project: Project): LinearLayout {
        val selected = try {
            chatProjectId() == project.projectId
        } catch (_: Exception) {
            false
        }
        val row = UiKit.horizontal(context).apply {
            isClickable = true
            isFocusable = true
            setPadding(
                UiKit.dp(context, 14),
                UiKit.dp(context, 12),
                UiKit.dp(context, 14),
                UiKit.dp(context, 12),
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            // 选中态：对勾 + 高亮底；未选中时底清掉、不留残影。
            if (selected) setBackgroundResource(R.drawable.bg_nav_selected)
            else setBackgroundResource(0)
            // 点行 = 选定/取消选定（直接在列表上做开关）；打开走“进入”。
            setOnClickListener { onToggleSelectProject(project) }
            setOnLongClickListener { showProjectManage(project); true }
        }
        // 选中标记：选中时显示对勾，未选中时占位不可见（行内文字不左右跳）。
        row.addView(
            UiKit.text(context, "✓", 16f, R.color.accent, bold = true).apply {
                setPadding(0, UiKit.dp(context, 8), UiKit.dp(context, 8), UiKit.dp(context, 8))
                visibility = if (selected) View.VISIBLE else View.INVISIBLE
            },
        )
        val textCol = UiKit.vertical(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            )
        }
        textCol.addView(
            UiKit.text(context, project.name, 14f, R.color.text_primary, bold = true),
        )
        val home = homeOf(project)
        val subtitle = if (home != null) {
            homeSubtitle(home)
        } else {
            "未指定家"
        }
        // 家/仓库摆在显眼处（2026-11-11：两行同名项目只靠副标题区分，
        // 副标题太小看不见就点错；GitHub 家直接显示仓库地址）。
        textCol.addView(
            UiKit.text(
                context,
                subtitle,
                12f,
                R.color.text_secondary,
                mono = home != null,
            ).apply {
                setPadding(0, UiKit.dp(context, 4), 0, 0)
            },
        )
        row.addView(textCol)
        // 前进：只在选中行上出现。点它打开项目（有家浏览家内容，无家去
        // 指定家）。未选中时不占位，列表保持干净。
        row.addView(
            UiKit.text(context, "进入", 13f, R.color.accent, bold = true).apply {
                setPadding(UiKit.dp(context, 12), UiKit.dp(context, 8), UiKit.dp(context, 12), UiKit.dp(context, 8))
                visibility = if (selected) View.VISIBLE else View.GONE
                setOnClickListener { onOpenProject(project) }
            },
        )
        // 可见的管理入口：点它就打开与长按同一张管理单（指定家/换家、
        // 改名、删除）。子 View 可点按后只接自己的点击，不触发行的选定。
        row.addView(
            UiKit.text(context, "···", 18f, R.color.text_secondary, bold = true).apply {
                setPadding(UiKit.dp(context, 12), UiKit.dp(context, 8), UiKit.dp(context, 12), UiKit.dp(context, 8))
                setOnClickListener { showProjectManage(project) }
            },
        )
        return row
    }

    /** 家的显示行：git 家 "GitHub/GitLab/Gitee · 仓库地址"，本机家 "本机 · 相对文件夹"，设备家 "设备 · 文件夹"。 */
    private fun homeSubtitle(home: ProjectLocation): String =
        when (home.homeKind) {
            ProjectHomeKind.GITHUB -> "GitHub · ${home.workspacePath}"
            ProjectHomeKind.GITLAB -> "GitLab · ${home.workspacePath}"
            ProjectHomeKind.GITEE -> "Gitee · ${home.workspacePath}"
            ProjectHomeKind.LOCAL -> "本机 · ${home.workspacePath}"
            ProjectHomeKind.DEVICE -> "${deviceDisplayName(home.hostId)} · ${home.workspacePath}"
        }


    /**
     * 长按管理（2026-11-11，maintainer：点项目不能 dumped 出一堆管理动作）：
     * 名、家设备、文件夹、仓库地址 + 管理动作。增删改添都在这里，不在
     * 主点击上——点行是选定/取消选定，选中行上的“进入”才是打开项目
     * （浏览家内容，无家去指定家），“浏览”不在这张单子里。动作：无家
     * 时“指定家…”，有家时“换家…”；“改名…”只改显示名（id 不变）；
     * “删除项目”只删 App 里的记录，不删设备上的文件夹。
     */
    private fun showProjectManage(project: Project) {
        val home = homeOf(project)
        val detail = buildString {
            val homes = homesOf(project)
            if (home != null) {
                when (home.homeKind) {
                    ProjectHomeKind.GITHUB -> append("家：GitHub · ${home.workspacePath}\n")
                    ProjectHomeKind.GITLAB -> append("家：GitLab · ${home.workspacePath}\n")
                    ProjectHomeKind.GITEE -> append("家：Gitee · ${home.workspacePath}\n")
                    ProjectHomeKind.LOCAL -> append("家：本机文件夹 · ${home.workspacePath}\n")
                    ProjectHomeKind.DEVICE -> {
                        append("家设备：${deviceDisplayName(home.hostId)}\n")
                        append("文件夹：${home.workspacePath}\n")
                    }
                }
                // 多家时列出全部家，当前家标“当前”。
                if (homes.size > 1) {
                    append("全部家（${homes.size} 个，★为当前）：\n")
                    for (h in homes) {
                        val mark = if (h.locationId == home.locationId) "★ " else "　 "
                        val label = h.gitHost()?.let { "${it.displayName} · ${h.workspacePath}" }
                            ?: "${deviceDisplayName(h.hostId)} · ${h.workspacePath}"
                        append("$mark$label\n")
                    }
                }
            } else {
                append("家：未指定\n")
            }
            append("仓库：${project.repoUrl?.takeIf { it.isNotBlank() }?.let { gitRepoDisplayLabel(it) } ?: "未填"}\n")
            append("创建时间：${UiKit.shortTime(project.createdAtEpochSeconds)}")
        }
        // 管理单用 manageActions（纯函数，单测覆盖）；多家时再加“切换家…”。
        // setMessage + setItems 会让动作列表不显示（见 ListDialog）：
        // 2026-11-11 真机实证就是这张单只剩详情 + 关闭，指定家/改名/
        // 删除全看不见。走 ListDialog，详情和动作同时可见。
        val actions = manageActions(home).toMutableList()
        if (homesOf(project).size > 1) actions += "切换家…"
        ListDialog.show(
            context,
            project.name,
            detail,
            actions,
            negativeLabel = "关闭",
        ) { which ->
            when (actions[which]) {
                "指定家…" -> onAssignHome(project)
                "换家…" -> onAssignHome(project)
                "切换家…" -> showSwitchHomeDialog(project)
                "改名…" -> showRenameProjectDialog(project)
                "删除项目" -> confirmDeleteProject(project)
            }
        }
    }

    /**
     * 切换当前家（主流模型：家的列表里选一个当当前家）。
     * 只改“当前是哪个家”，不删任何家、不动任务与对话；
     * 切换后浏览用新家，任务与对话按 id 存，不丢。
     */
    private fun showSwitchHomeDialog(project: Project) {
        val homes = homesOf(project)
        val active = homeOf(project)
        if (homes.size < 2) {
            onToast("该项目只有一个家，无需切换。")
            return
        }
        val labels = homes.map { h ->
            val mark = if (active != null && h.locationId == active.locationId) "★ 当前 · " else ""
            val body = h.gitHost()?.let { "${it.displayName} · ${h.workspacePath}" }
                ?: "${deviceDisplayName(h.hostId)} · ${h.workspacePath}"
            mark + body
        }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle("切换“${project.name}”的当前家")
            .setItems(labels) { _, which ->
                val chosen = homes[which]
                try {
                    projectStore.setActiveHome(project.projectId, chosen.locationId)
                    onToast("当前家已切换：${labels[which].removePrefix("★ 当前 · ")}")
                } catch (e: Exception) {
                    onToast("切换家失败：${e.message}")
                }
                showProjectList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 改名：只改显示名，项目 id 与家都不变（store 的 saveProject 是同 id 更新）。 */
    private fun showRenameProjectDialog(project: Project) {
        val nameInput = EditText(context).apply {
            hint = "项目名"
            setText(project.name)
            setSingleLine(true)
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(context, 20), UiKit.dp(context, 8), UiKit.dp(context, 20), UiKit.dp(context, 8))
            addView(nameInput)
        }
        AlertDialog.Builder(context)
            .setTitle("改名")
            .setMessage("只改显示名，不动项目 id 与家。")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isBlank()) {
                    onToast("项目名不能为空。")
                    return@setPositiveButton
                }
                try {
                    projectStore.saveProject(project.copy(name = name))
                    onToast("项目已改名：$name")
                } catch (e: Exception) {
                    onToast("改名失败：${e.message}")
                }
                showProjectList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDeleteProject(project: Project) {
        AlertDialog.Builder(context)
            .setTitle("删除项目“${project.name}”？")
            .setMessage("只删除 App 里的项目记录，不会删除设备上的文件夹。")
            .setPositiveButton("删除") { _, _ ->
                try {
                    projectStore.deleteProject(project.projectId)
                    onToast("已删除项目“${project.name}”。")
                    // 删的是当前选中项目时，调用方清掉绑定，不留野绑定。
                    onDeleteProject(project)
                } catch (e: Exception) {
                    onToast("删除失败：${e.message}")
                }
                showProjectList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun showTree() {
        exitEditMode(discard = true)
        currentFile = null
        currentRead = null
        codePane.visibility = View.GONE
        projectListPane.visibility = View.GONE
        treePane.visibility = View.VISIBLE
    }

    fun hasCodeOpen(): Boolean = codePane.visibility == View.VISIBLE

    /** 是否正在浏览项目文件夹树（本机家打开后）：系统返回键先回项目列表。 */
    fun hasTreeOpen(): Boolean = treePane.visibility == View.VISIBLE

    fun closeCode() {
        if (codeOpenedFromList) {
            codeOpenedFromList = false
            showProjectList()
        } else {
            showTree()
        }
    }

    /**
     * 阶段 3（U3/批 3a）：任务详情页产物链接的入口——切到项目页并
     * 打开指定工作区相对路径的文件（不存在/不可读时查看器如实报错）。
     */
    fun openFileAt(relativePath: String) {
        openFile(relativePath)
    }

    fun askAgentAboutCurrentFile() {
        val file = currentFile ?: return
        onAskAgent(file)
    }

    private fun render() {
        treeList.removeAllViews()
        if (searchQuery.isNotBlank()) {
            renderSearch()
            return
        }
        val listing = browser.list("", treeLimit)
        if (listing.error != null) {
            emptyView.visibility = View.VISIBLE
            emptyView.text = "工作区读取失败：${listing.error}"
            treeMoreButton.visibility = View.GONE
            return
        }
        if (listing.entries.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            emptyView.text = "工作区为空。\nAgent 写入文件后会出现在这里；也可以直接在聊天里让它新建项目文件。"
            treeMoreButton.visibility = View.GONE
            return
        }
        emptyView.visibility = View.GONE
        addDirectoryRows("", 0)
        if (listing.truncated) {
            val total = try {
                java.io.File(browser.rootPath).listFiles()?.size ?: listing.entries.size
            } catch (_: Exception) {
                listing.entries.size
            }
            treeMoreButton.visibility = View.VISIBLE
            treeMoreButton.text = "加载更多（已显示 ${listing.entries.size} / 共 $total 项）"
        } else {
            treeMoreButton.visibility = View.GONE
        }
        treeScroll.post { treeScroll.fullScroll(View.FOCUS_UP) }
    }

    private fun renderSearch() {
        val results = browser.search(searchQuery)
        if (results.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            emptyView.text = "没有找到包含“$searchQuery”的文件。"
        } else {
            emptyView.visibility = View.VISIBLE
            emptyView.text = "找到 ${results.size} 个文件（按文件名匹配）。"
        }
        treeMoreButton.visibility = View.GONE
        results.forEach { entry ->
            treeList.addView(row(entry, 0))
        }
    }

    private fun addDirectoryRows(relativePath: String, depth: Int) {
        if (depth > 10) return
        val listing = browser.list(relativePath)
        if (listing.error != null) {
            treeList.addView(hint(listing.error))
            return
        }
        listing.entries.forEach { entry ->
            treeList.addView(row(entry, depth))
            if (entry.isDirectory && entry.relativePath in expanded) {
                val children = browser.list(entry.relativePath)
                if (children.error != null) {
                    treeList.addView(hint(children.error, depth + 1))
                } else if (children.entries.isEmpty()) {
                    treeList.addView(hint("（空目录）", depth + 1))
                } else {
                    addDirectoryRows(entry.relativePath, depth + 1)
                    if (children.truncated) {
                        treeList.addView(hint("条目过多，已截断。", depth + 1))
                    }
                }
            }
        }
    }

    private fun row(entry: WorkspaceEntry, depth: Int): LinearLayout {
        val row = UiKit.horizontal(context).apply {
            isClickable = true
            isFocusable = true
            setPadding(
                UiKit.dp(context, 8 + depth * 15),
                UiKit.dp(context, 7),
                UiKit.dp(context, 8),
                UiKit.dp(context, 7),
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        val icon = UiKit.icon(
            context,
            if (entry.isDirectory) R.drawable.ic_folder else R.drawable.ic_file,
            18,
            R.color.text_secondary,
        )
        row.addView(icon)
        val name = UiKit.text(
            context,
            entry.name,
            13f,
            R.color.text_primary,
            bold = entry.isDirectory,
            mono = !entry.isDirectory,
        ).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(UiKit.dp(context, 8), 0, UiKit.dp(context, 6), 0)
        }
        row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (!entry.isDirectory) {
            row.addView(
                UiKit.text(context, WorkspaceBrowser.formatSize(entry.sizeBytes), 10f, R.color.text_tertiary),
            )
        }
        if (entry.isDirectory) {
            val chevron = UiKit.icon(context, R.drawable.ic_chevron, 16, R.color.text_tertiary)
            chevron.rotation = if (entry.relativePath in expanded) 90f else 0f
            row.addView(chevron)
        }
        row.setOnClickListener {
            if (entry.isDirectory) {
                if (!expanded.add(entry.relativePath)) expanded.remove(entry.relativePath)
                render()
            } else {
                openFile(entry.relativePath)
            }
        }
        return row
    }

    private fun openFile(relativePath: String) {
        exitEditMode(discard = true)
        currentFile = relativePath
        readLimit = READ_TIER_SMALL
        val read = browser.read(relativePath, readLimit)
        currentRead = read
        if (read.kind == WorkspaceReadKind.TEXT) {
            editSession.open(relativePath)
        }
        renderCodeView(read, relativePath)
        codeOpenedFromList = projectListPane.visibility == View.VISIBLE
        projectListPane.visibility = View.GONE
        treePane.visibility = View.GONE
        codePane.visibility = View.VISIBLE
        updateEditButtons()
    }

    /** 大文件“加载更多”：512 KB 档 → 2 MB 档；已是最大档时如实提示。 */
    private fun loadMoreFile() {
        val path = currentFile ?: return
        if (readLimit >= READ_TIER_LARGE) {
            onToast("已加载到最大档位（2 MB），更大文件请用电脑打开。")
            return
        }
        readLimit = READ_TIER_LARGE
        val read = browser.read(path, readLimit)
        currentRead = read
        if (read.kind == WorkspaceReadKind.TEXT) {
            editSession.open(path)
        }
        renderCodeView(read, path)
        updateEditButtons()
    }

    private fun renderCodeView(read: WorkspaceRead, relativePath: String) {
        codePath.text = relativePath
        val lastDiff = editSession.lastDiff
        codeMeta.text = when (read.kind) {
            WorkspaceReadKind.TEXT -> buildString {
                append(WorkspaceBrowser.formatSize(read.sizeBytes))
                append(" · ")
                append(read.text.count { it == '\n' } + if (read.text.isEmpty()) 0 else 1)
                append(" 行 · 只读")
                if (read.truncated) append(" · 已截断（仅显示前 ${readLimit / 1024} KB）")
                if (lastDiff != null && lastDiff.path == relativePath) {
                    append(" · 上次保存：+${lastDiff.insertions} −${lastDiff.deletions} 行")
                }
            }
            else -> WorkspaceBrowser.formatSize(read.sizeBytes)
        }
        codeContent.typeface = Typeface.MONOSPACE
        // Let the code card fill the viewport on a short file, while long
        // lines still expand it horizontally inside the HorizontalScrollView.
        codeContent.minWidth = (
            context.resources.displayMetrics.widthPixels - UiKit.dp(context, 20)
            ).coerceAtLeast(UiKit.dp(context, 240))
        if (read.kind == WorkspaceReadKind.TEXT) {
            codeContent.text = renderCodeWithLineNumbers(read.text, relativePath)
            codeContent.setTextColor(UiKit.color(context, R.color.code_text))
        } else {
            codeContent.text = when (read.kind) {
                WorkspaceReadKind.BINARY -> read.error ?: "二进制文件"
                WorkspaceReadKind.MISSING, WorkspaceReadKind.OUTSIDE, WorkspaceReadKind.ERROR ->
                    read.error ?: "无法读取该文件"
                else -> read.error ?: "无法读取该文件"
            }
            codeContent.setTextColor(UiKit.color(context, R.color.text_secondary))
        }
        buttonCodeMore.visibility =
            if (read.kind == WorkspaceReadKind.TEXT && read.truncated) View.VISIBLE else View.GONE
    }

    /**
     * 行号 + 基础语法高亮。行号是每行前的 `NNN │ ` 前缀（纯文本，
     * 复制时不带行号——复制走 [copyCode]，直接取原文）；高亮 span
     * 按前缀长度偏移后落在代码字符上。
     */
    private fun renderCodeWithLineNumbers(text: String, path: String): CharSequence {
        if (text.isEmpty()) return "（空文件）"
        val lines = text.split('\n')
        val width = lines.size.toString().length
        val out = SpannableStringBuilder()
        val language = CodeHighlight.languageFor(path)
        val spans = CodeHighlight.highlight(text, language)
        val lineStartOffsets = IntArray(lines.size)
        var offset = 0
        for ((index, line) in lines.withIndex()) {
            lineStartOffsets[index] = offset
            val prefix = String.format("%${width}d │ ", index + 1)
            val start = out.length
            out.append(prefix)
            out.append(line)
            out.setSpan(
                ForegroundColorSpan(UiKit.color(context, R.color.text_tertiary)),
                start, start + prefix.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            if (index < lines.size - 1) out.append('\n')
            offset += line.length + 1
        }
        val keywordColor = UiKit.color(context, R.color.accent)
        val stringColor = UiKit.color(context, R.color.success)
        val commentColor = UiKit.color(context, R.color.text_tertiary)
        val numberColor = UiKit.color(context, R.color.warning)
        for (span in spans) {
            // 跨行 span（块注释、三引号字符串）按行偏移无法准确定位：
            // 基础高亮直接跳过，不画错位置。
            if (text.substring(span.start, span.end).contains('\n')) continue
            val lineIndex = lineStartOffsets.indexOfLast { it <= span.start }.coerceAtLeast(0)
            val prefixLength = width + 3
            val shiftedStart = span.start + (lineIndex + 1) * prefixLength + lineIndex
            val shiftedEnd = span.end + (lineIndex + 1) * prefixLength + lineIndex
            if (shiftedStart < 0 || shiftedEnd > out.length || shiftedStart >= shiftedEnd) continue
            when (span.kind) {
                CodeHighlight.Kind.KEYWORD -> {
                    out.setSpan(
                        ForegroundColorSpan(keywordColor), shiftedStart, shiftedEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    out.setSpan(
                        android.text.style.StyleSpan(Typeface.BOLD), shiftedStart, shiftedEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                CodeHighlight.Kind.STRING -> out.setSpan(
                    ForegroundColorSpan(stringColor), shiftedStart, shiftedEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                CodeHighlight.Kind.COMMENT -> out.setSpan(
                    ForegroundColorSpan(commentColor), shiftedStart, shiftedEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                CodeHighlight.Kind.NUMBER -> out.setSpan(
                    ForegroundColorSpan(numberColor), shiftedStart, shiftedEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        return out
    }

    private fun startEdit() {
        val path = currentFile ?: return
        if (currentRead?.kind != WorkspaceReadKind.TEXT) {
            onToast("该文件不能编辑（非文本或未读到内容）。")
            return
        }
        val draft = editSession.draft() ?: editSession.open(path).text
        editing = true
        codeEditor.setText(draft)
        codeEditor.typeface = Typeface.MONOSPACE
        codeEditor.visibility = View.VISIBLE
        codeContent.visibility = View.GONE
        updateEditButtons()
    }

    private fun exitEditMode(discard: Boolean) {
        if (!editing) return
        if (discard) editSession.discardDraft()
        editing = false
        codeEditor.visibility = View.GONE
        codeContent.visibility = View.VISIBLE
        updateEditButtons()
    }

    /** 保存：二次确认弹窗展示行级 diff 摘要，确认后才走写轮次落盘。 */
    private fun saveEdit() {
        val path = currentFile ?: return
        if (!editing) return
        editSession.updateDraft(codeEditor.text.toString())
        val diff = editSession.diff()
        if (diff == null || !editSession.hasChanges()) {
            onToast("没有改动，无需保存。")
            return
        }
        val summary = buildString {
            append("文件：$path\n")
            append("新增 ${diff.insertions} 行，删除 ${diff.deletions} 行\n")
            append("保存后可撤销；确认保存吗？")
            val preview = diff.lines.filter {
                it.kind == DiffLineKind.ADD || it.kind == DiffLineKind.DELETE
            }.take(10)
            if (preview.isNotEmpty()) {
                append("\n\n")
                preview.forEach { line ->
                    val sign = if (line.kind == DiffLineKind.ADD) "+" else "-"
                    val number = line.newLine ?: line.oldLine
                    append("$sign ${number ?: ""} ${line.text}\n")
                }
                if (diff.insertions + diff.deletions > preview.size) append("…（仅显示前 10 行改动）")
            }
        }
        AlertDialog.Builder(context)
            .setTitle("保存前确认")
            .setMessage(summary)
            .setPositiveButton("保存") { _, _ ->
                try {
                    val result = editSession.save(confirm = true)
                    onToast("已保存：+${result.diff.insertions} −${result.diff.deletions} 行")
                    exitEditMode(discard = false)
                    currentFile?.let { openFile(it) }
                } catch (e: Exception) {
                    onToast("保存失败：${e.message ?: "未知错误"}")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun undoEdit() {
        try {
            val result = editSession.undo()
            val message = if (result.alreadyUndone) {
                "该轮次已撤销过，无需重复撤销。"
            } else {
                buildString {
                    append("已撤销：恢复 ${result.restored.size} 个文件，删除 ${result.deleted.size} 个新增文件")
                    if (result.notRestored.isNotEmpty()) {
                        append("；${result.notRestored.size} 个文件无法恢复（改动未备份）")
                    }
                }
            }
            onToast(message)
            currentFile?.let { openFile(it) }
        } catch (e: Exception) {
            onToast("撤销失败：${e.message ?: "未知错误"}")
        }
    }

    private fun keepEdit() {
        try {
            editSession.keep()
            onToast("已保留改动，备份已清理（之后不可再撤销该轮）。")
            updateEditButtons()
            currentFile?.let { path ->
                currentRead?.let { renderCodeView(it, path) }
            }
        } catch (e: Exception) {
            onToast("保留失败：${e.message ?: "未知错误"}")
        }
    }

    private fun copyCode() {
        val text = if (editing) codeEditor.text.toString() else editSession.draft()
        if (text.isNullOrEmpty()) {
            onToast("没有可复制的内容。")
            return
        }
        onCopyText(text)
    }

    private fun updateEditButtons() {
        val hasTextFile = currentFile != null && currentRead?.kind == WorkspaceReadKind.TEXT
        buttonCodeEdit.visibility = if (hasTextFile && !editing) View.VISIBLE else View.GONE
        buttonCodeSave.visibility = if (editing) View.VISIBLE else View.GONE
        val hasSavedRound = editSession.lastRoundId != null
        buttonCodeUndo.visibility = if (hasSavedRound && !editing) View.VISIBLE else View.GONE
        buttonCodeKeep.visibility = if (hasSavedRound && !editing) View.VISIBLE else View.GONE
        buttonCodeCopy.visibility = if (hasTextFile) View.VISIBLE else View.GONE
        if (!hasTextFile) buttonCodeMore.visibility = View.GONE
    }

    private fun hint(value: String, depth: Int = 0): TextView = UiKit.text(
        context,
        value,
        11f,
        R.color.text_tertiary,
    ).apply {
        setPadding(UiKit.dp(context, 10 + depth * 15), UiKit.dp(context, 8), 0, UiKit.dp(context, 8))
        gravity = Gravity.START
    }
}
