package wherevercode.agent.ui

import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import wherevercode.agent.R
import wherevercode.agent.core.project.InMemoryProjectStore
import wherevercode.agent.core.project.Project
import wherevercode.agent.core.project.ProjectLocation
import java.io.File

/**
 * 项目页交互（2026-11-11，maintainer 三句 + 两张老项目参照）：
 * 点项目干净打开（不弹管理堆），增删改添在长按里，本机家显示“本机”，
 * GitHub 家显示“GitHub”，Agent 注册路径独立。
 * Robolectric，CI 跑。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class ProjectScreenControllerTest {

    private class Harness(
        val store: InMemoryProjectStore = InMemoryProjectStore(),
        val opened: MutableList<String> = mutableListOf(),
        /** 点选开关走这里：选中态按它画（调用方绑定 chat_project_id）。 */
        var selectedId: String? = null,
        val toggled: MutableList<String> = mutableListOf(),
        val toasts: MutableList<String> = mutableListOf(),
        /** 管理单里删项目后走这里：调用方清绑定（与 MainActivity 同款）。 */
        val deleted: MutableList<String> = mutableListOf(),
    ) {
        // 带 App 主题的上下文：管理对话框是 AppCompat AlertDialog，裸 Application
        // 上下文没有主题属性，show 时会抛 Resources$NotFoundException（CI 曾红）。
        val context = ContextThemeWrapper(
            RuntimeEnvironment.getApplication(),
            R.style.Theme_WhereverCode,
        )
        val rootLabel = TextView(context)
        val treePane = LinearLayout(context)
        val treeList = LinearLayout(context)
        val treeScroll = ScrollView(context)
        val emptyView = TextView(context)
        val searchInput = EditText(context)
        val treeMoreButton = TextView(context)
        val codePane = LinearLayout(context)
        val codePath = TextView(context)
        val codeMeta = TextView(context)
        val codeContent = TextView(context)
        val codeEditor = EditText(context)
        val buttonCodeEdit = TextView(context)
        val buttonCodeSave = TextView(context)
        val buttonCodeUndo = TextView(context)
        val buttonCodeKeep = TextView(context)
        val buttonCodeMore = TextView(context)
        val buttonCodeCopy = TextView(context)
        val projectListPane = LinearLayout(context)
        val projectList = LinearLayout(context)
        val projectListEmpty = TextView(context)
        val buttonAddProject = TextView(context)

        // 显式类型声明：onToggleSelectProject 回调里要调 controller.showProjectList()
        // 重画列表；若 controller 在构造表达式里直接初始化，Kotlin 类型推断会报
        // “recursive problem”。用 lateinit var 先声明类型再赋值，避开这点。
        lateinit var controller: ProjectScreenController

        init {
            controller = ProjectScreenController(
            context = context,
            rootLabel = rootLabel,
            treePane = treePane,
            treeList = treeList,
            treeScroll = treeScroll,
            emptyView = emptyView,
            searchInput = searchInput,
            treeMoreButton = treeMoreButton,
            codePane = codePane,
            codePath = codePath,
            codeMeta = codeMeta,
            codeContent = codeContent,
            codeEditor = codeEditor,
            buttonCodeEdit = buttonCodeEdit,
            buttonCodeSave = buttonCodeSave,
            buttonCodeUndo = buttonCodeUndo,
            buttonCodeKeep = buttonCodeKeep,
            buttonCodeMore = buttonCodeMore,
            buttonCodeCopy = buttonCodeCopy,
            onAskAgent = {},
            onCopyText = {},
            onToast = { toasts += it },
            projectListPane = projectListPane,
            projectList = projectList,
            projectListEmpty = projectListEmpty,
            buttonAddProject = buttonAddProject,
            projectStore = store,
            deviceDisplayName = { "设备-$it" },
            onAddProject = {},
            onAssignHome = {},
            onOpenProject = { opened += it.projectId },
            // 点选开关与生产调用方同款：切绑定后重画列表，选中态即时更新。
            chatProjectId = { selectedId },
            onToggleSelectProject = { project: Project ->
                toggled += project.projectId
                selectedId = if (selectedId == project.projectId) null else project.projectId
                controller.showProjectList()
            },
            // 删除回调与生产调用方同款：删的是当前选中项目时清绑定。
            onDeleteProject = { project: Project ->
                deleted += project.projectId
                if (selectedId == project.projectId) selectedId = null
            },
            )
        }

        fun projectRow(name: String): LinearLayout? {
            for (i in 0 until projectList.childCount) {
                val child = projectList.getChildAt(i)
                if (child is LinearLayout && collectText(child).contains(name)) return child
            }
            return null
        }
    }

    private fun project(id: String, name: String = id, repoUrl: String? = null) =
        Project(projectId = id, name = name, repoUrl = repoUrl)

    @Test
    fun `manage actions without home offer assign rename delete and no browse`() {
        assertEquals(
            listOf("指定家…", "改名…", "删除项目"),
            ProjectScreenController.manageActions(null),
        )
    }

    @Test
    fun `manage actions with home offer change home rename delete and no browse`() {
        val deviceHome = ProjectLocation.create("p", "remote-device", "/workspace/example-project")
        assertEquals(
            listOf("换家…", "改名…", "删除项目"),
            ProjectScreenController.manageActions(deviceHome),
        )
        val githubHome = ProjectLocation.createGithub("p", "o", "r")
        assertEquals(
            listOf("换家…", "改名…", "删除项目"),
            ProjectScreenController.manageActions(githubHome),
        )
        val localHome = ProjectLocation.createLocal("p", "my-project")
        assertEquals(
            listOf("换家…", "改名…", "删除项目"),
            ProjectScreenController.manageActions(localHome),
        )
    }

    @Test
    fun `tap on project selects it instead of opening`() {
        // 点选（2026-11-11，maintainer：点一下选、再点一下取消，直接在列表上）：
        // 点行 = 选定，不打开、不弹管理单；打开走选中行上的“进入”。
        val h = Harness()
        h.store.saveProject(project("wherevercode-agent"))
        h.controller.showProjectList()
        val row = h.projectRow("wherevercode-agent") ?: throw AssertionError("找不到项目行")
        row.performClick()
        assertEquals(listOf("wherevercode-agent"), h.toggled)
        assertEquals("wherevercode-agent", h.selectedId)
        assertTrue(h.opened.isEmpty())
        assertTrue(ShadowDialog.getShownDialogs().isEmpty())
    }

    @Test
    fun `selected row shows check mark highlight and enter button`() {
        val h = Harness()
        h.store.saveProject(project("wherevercode-agent"))
        h.store.saveProject(project("other-proj"))
        h.selectedId = "wherevercode-agent"
        h.controller.showProjectList()
        val row = h.projectRow("wherevercode-agent") ?: throw AssertionError("找不到项目行")
        // 行结构：行 = [对勾, textCol(标题, 副标题), “进入”, “···”]。
        val check = row.getChildAt(0) as TextView
        assertEquals("✓", check.text.toString())
        assertEquals(View.VISIBLE, check.visibility)
        val enter = findEnterButton(row) ?: throw AssertionError("选中行没有“进入”")
        assertEquals(View.VISIBLE, enter.visibility)
        assertTrue(row.background != null)
        // 未选中的行：对勾占位不可见、“进入”不占位、无高亮底。
        val other = h.projectRow("other-proj") ?: throw AssertionError("找不到另一行")
        assertEquals(View.INVISIBLE, (other.getChildAt(0) as TextView).visibility)
        assertEquals(View.GONE, findEnterButton(other)?.visibility)
        assertTrue(other.background == null)
    }

    @Test
    fun `tap on selected project deselects it`() {
        val h = Harness()
        h.store.saveProject(project("wherevercode-agent"))
        h.controller.showProjectList()
        val row = h.projectRow("wherevercode-agent") ?: throw AssertionError("找不到项目行")
        row.performClick()
        assertEquals("wherevercode-agent", h.selectedId)
        // 重画后行已更新：再点一下取消选定。
        val row2 = h.projectRow("wherevercode-agent") ?: throw AssertionError("找不到项目行")
        row2.performClick()
        assertEquals(listOf("wherevercode-agent", "wherevercode-agent"), h.toggled)
        assertEquals(null, h.selectedId)
        assertTrue(h.opened.isEmpty())
        val row3 = h.projectRow("wherevercode-agent") ?: throw AssertionError("找不到项目行")
        assertEquals(View.INVISIBLE, (row3.getChildAt(0) as TextView).visibility)
        assertEquals(View.GONE, findEnterButton(row3)?.visibility)
        assertTrue(row3.background == null)
    }

    @Test
    fun `enter button on selected row opens the project without toggling again`() {
        val h = Harness()
        h.store.saveProject(project("wherevercode-agent"))
        h.selectedId = "wherevercode-agent"
        h.controller.showProjectList()
        val row = h.projectRow("wherevercode-agent") ?: throw AssertionError("找不到项目行")
        val enter = findEnterButton(row) ?: throw AssertionError("选中行没有“进入”")
        enter.performClick()
        assertEquals(listOf("wherevercode-agent"), h.opened)
        assertTrue(h.toggled.isEmpty())
        assertTrue(ShadowDialog.getShownDialogs().isEmpty())
    }

    @Test
    fun `project list shows no device sources section no register row no workspace row`() {
        // 可见增删改添第二轮（2026-11-11，maintainer）：主页面不再列任何设备
        // 文件夹来源，只留项目列表 + 一个“＋ 添加项目…”入口（底部按钮，
        // 不在列表里）；不留“＋ 注册项目…”、不留空节头“本机工作区”。
        val h = Harness()
        h.store.saveProject(project("wherevercode-agent"))
        h.controller.showProjectList()
        val text = collectText(h.projectList)
        assertFalse(text.contains("设备上的项目来源"))
        assertFalse(text.contains("注册项目"))
        assertFalse(text.contains("本机工作区"))
        assertFalse(text.contains("暂无可打开的项目来源"))
        // 列表里只有项目行：每行都有名 + 家副标题 + 行尾“···”。
        assertTrue(text.contains("wherevercode-agent"))
        assertTrue(text.contains("未指定家"))
        assertTrue(text.contains("···"))
    }

    @Test
    fun `long press on project shows manage dialog`() {
        val h = Harness()
        h.store.saveProject(project("wherevercode-agent"))
        h.controller.showProjectList()
        val row = h.projectRow("wherevercode-agent") ?: throw AssertionError("找不到项目行")
        assertTrue(row.performLongClick())
        assertTrue(h.opened.isEmpty())
        assertFalse(ShadowDialog.getShownDialogs().isEmpty())
    }

    @Test
    fun `list shows local home as local and github home as github`() {
        val h = Harness()
        h.store.saveProject(project("local-proj"))
        h.store.saveLocation(ProjectLocation.createLocal("local-proj", "my-project"))
        h.store.saveProject(project("gh-proj"))
        h.store.saveLocation(ProjectLocation.createGithub("gh-proj", "o", "r"))
        h.controller.showProjectList()
        val text = collectText(h.projectList)
        assertTrue(text.contains("本机 · my-project"))
        assertTrue(text.contains("GitHub · o/r"))
    }

    @Test
    fun `github home subtitle shows repo address at prominent size`() {
        // 2026-11-11：两行同名项目只靠副标题区分，副标题太小看不见就点错。
        val h = Harness()
        h.store.saveProject(project("gh-proj", "wherevercode-agent"))
        h.store.saveLocation(
            ProjectLocation.createGithub("gh-proj", "huaerdaidai-gif", "wherevercode-agent"),
        )
        h.controller.showProjectList()
        val row = h.projectRow("wherevercode-agent") ?: throw AssertionError("找不到项目行")
        // 行结构（点选，2026-11-11）：行 = [对勾, textCol(标题, 副标题),
        // “进入”, “···”]，副标题是 textCol 的第 2 个子 View。
        val textCol = row.getChildAt(1) as LinearLayout
        val subtitle = textCol.getChildAt(1) as TextView
        assertEquals("GitHub · huaerdaidai-gif/wherevercode-agent", subtitle.text.toString())
        val expectedPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            12f,
            h.context.resources.displayMetrics,
        )
        assertEquals(expectedPx, subtitle.textSize, 0.01f)
    }

    @Test
    fun `two same-name projects render distinguishable rows`() {
        // 注册重名后显示名自动加后缀（wherevercode-agent 2），行名本身就分得清；
        // GitHub 行的副标题还摆着仓库地址。
        val h = Harness()
        h.store.saveProject(project("wherevercode-agent", "wherevercode-agent"))
        h.store.saveProject(project("wherevercode-agent-2", "wherevercode-agent 2"))
        h.store.saveLocation(
            ProjectLocation.createGithub("wherevercode-agent-2", "huaerdaidai-gif", "wherevercode-agent"),
        )
        h.controller.showProjectList()
        val names = (0 until h.projectList.childCount).mapNotNull { i ->
            val child = h.projectList.getChildAt(i) as? LinearLayout ?: return@mapNotNull null
            // 行 = [对勾, textCol(标题, 副标题), “进入”, “···”]，标题在 textCol 里。
            val textCol = child.getChildAt(1) as? LinearLayout ?: return@mapNotNull null
            (textCol.getChildAt(0) as? TextView)?.text?.toString()
        }.filter { it == "wherevercode-agent" || it == "wherevercode-agent 2" }
        assertEquals(setOf("wherevercode-agent", "wherevercode-agent 2"), names.toSet())
        assertEquals(2, names.size)
        assertTrue(collectText(h.projectList).contains("GitHub · huaerdaidai-gif/wherevercode-agent"))
    }

    @Test
    fun `openLocalFolder shows tree with the folder expanded`() {
        val h = Harness()
        val folder = File(File(h.context.filesDir, "workspace"), "ux-test-proj").apply { mkdirs() }
        File(folder, "hello.txt").writeText("hi")
        h.controller.openLocalFolder("ux-test-proj")
        assertEquals(View.VISIBLE, h.treePane.visibility)
        assertEquals(View.GONE, h.projectListPane.visibility)
        assertTrue(h.rootLabel.text.toString().contains("ux-test-proj"))
        assertTrue(collectText(h.treeList).contains("hello.txt"))
    }

    // ---- 项目页清理（2026-11-11）：管理入口看得见、死来源灰显、啰嗦文案删掉 ----

    @Test
    fun `manage button on project row opens manage dialog`() {
        // 增删改添以前只藏在长按里：每行行尾加可见的“···”，点它就打开
        // 与长按同一张管理单（指定家/换家、改名、删除）。
        val h = Harness()
        h.store.saveProject(project("wherevercode-agent"))
        h.controller.showProjectList()
        val row = h.projectRow("wherevercode-agent") ?: throw AssertionError("找不到项目行")
        val button = findManageButton(row) ?: throw AssertionError("行尾没有“···”管理入口")
        button.performClick()
        assertTrue(h.opened.isEmpty())
        assertFalse(ShadowDialog.getShownDialogs().isEmpty())
    }

    @Test
    fun `tap on manage button does not open the project`() {
        val h = Harness()
        h.store.saveProject(project("wherevercode-agent"))
        h.controller.showProjectList()
        val row = h.projectRow("wherevercode-agent") ?: throw AssertionError("找不到项目行")
        val button = findManageButton(row) ?: throw AssertionError("行尾没有“···”管理入口")
        button.performClick()
        assertTrue(h.opened.isEmpty())
    }

    @Test
    fun `project list no longer shows placeholder copy`() {
        val h = Harness()
        h.store.saveProject(project("wherevercode-agent"))
        h.controller.showProjectList()
        val text = collectText(h.projectList)
        assertFalse(text.contains("只注册逻辑项目"))
        assertFalse(text.contains("filesDir"))
    }

    // ---- 项目增删改修复（2026-11-11，maintainer 19:37：管理单是空的）----

    @Test
    fun `manage dialog shows detail and all actions at once`() {
        // 根因回归：setMessage + setItems 同用时动作列表不显示，管理单
        // 只剩详情 + 关闭，指定家/改名/删除全看不见。ListDialog 下详情
        // 和动作必须同时出现。
        val h = Harness()
        h.store.saveProject(project("wherevercode-agent"))
        h.controller.showProjectList()
        val row = h.projectRow("wherevercode-agent") ?: throw AssertionError("找不到项目行")
        val button = findManageButton(row) ?: throw AssertionError("行尾没有“···”管理入口")
        button.performClick()
        val dialog = showingDialog() ?: throw AssertionError("管理单没显示")
        val text = dialogText(dialog)
        assertTrue(text.contains("家：未指定"))
        assertTrue(text.contains("创建时间："))
        assertTrue(text.contains("指定家…"))
        assertTrue(text.contains("改名…"))
        assertTrue(text.contains("删除项目"))
        assertTrue(text.contains("关闭"))
    }

    @Test
    fun `manage dialog with home shows change home not assign home`() {
        val h = Harness()
        h.store.saveProject(project("gh-proj", "wherevercode-agent"))
        h.store.saveLocation(
            ProjectLocation.createGithub("gh-proj", "huaerdaidai-gif", "wherevercode-agent"),
        )
        h.controller.showProjectList()
        val row = h.projectRow("wherevercode-agent") ?: throw AssertionError("找不到项目行")
        findManageButton(row)?.performClick()
        val dialog = showingDialog() ?: throw AssertionError("管理单没显示")
        val text = dialogText(dialog)
        assertTrue(text.contains("家：GitHub · huaerdaidai-gif/wherevercode-agent"))
        assertTrue(text.contains("换家…"))
        assertFalse(text.contains("指定家…"))
        assertTrue(text.contains("改名…"))
        assertTrue(text.contains("删除项目"))
    }

    @Test
    fun `delete project from manage dialog asks twice and removes it`() {
        // 删项目要二次确认：管理单点“删除项目”→ 确认框 → 点“删除”才删；
        // 只删 App 里的记录，删完列表里没有它，删除回调也触发。
        val h = Harness()
        h.store.saveProject(project("wherevercode-agent"))
        h.store.saveProject(project("other-proj"))
        h.controller.showProjectList()
        val row = h.projectRow("wherevercode-agent") ?: throw AssertionError("找不到项目行")
        findManageButton(row)?.performClick()
        val manage = showingDialog() ?: throw AssertionError("管理单没显示")
        findTextView(manage, "删除项目")?.performClick()
        val confirm = showingDialog() ?: throw AssertionError("删除确认框没显示")
        assertTrue(dialogText(confirm).contains("删除项目“wherevercode-agent”？"))
        assertTrue(dialogText(confirm).contains("不会删除设备上的文件夹"))
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        // AppCompat 对话框的按钮点击经 Handler 消息分发：Robolectric 的
        // 主 looper 默认暂停，不 idle 的话删除监听不会跑。
        ShadowLooper.idleMainLooper()
        assertEquals(listOf("wherevercode-agent"), h.deleted)
        assertTrue(h.store.listProjects().none { it.projectId == "wherevercode-agent" })
        assertTrue(h.store.listProjects().any { it.projectId == "other-proj" })
        assertFalse(collectText(h.projectList).contains("wherevercode-agent\n"))
    }

    @Test
    fun `deleting the selected project clears the selection`() {
        // 删的是当前选中项目：选中态与绑定一起清，不留野绑定。
        val h = Harness()
        h.store.saveProject(project("wherevercode-agent"))
        h.selectedId = "wherevercode-agent"
        h.controller.showProjectList()
        val row = h.projectRow("wherevercode-agent") ?: throw AssertionError("找不到项目行")
        findManageButton(row)?.performClick()
        val manage = showingDialog() ?: throw AssertionError("管理单没显示")
        findTextView(manage, "删除项目")?.performClick()
        val confirm = showingDialog() ?: throw AssertionError("删除确认框没显示")
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        // AppCompat 对话框的按钮点击经 Handler 消息分发：Robolectric 的
        // 主 looper 默认暂停，不 idle 的话删除监听不会跑。
        ShadowLooper.idleMainLooper()
        assertEquals(null, h.selectedId)
        assertEquals(listOf("wherevercode-agent"), h.deleted)
    }

    @Test
    fun `cancel on delete confirm keeps the project`() {
        val h = Harness()
        h.store.saveProject(project("wherevercode-agent"))
        h.controller.showProjectList()
        val row = h.projectRow("wherevercode-agent") ?: throw AssertionError("找不到项目行")
        findManageButton(row)?.performClick()
        val manage = showingDialog() ?: throw AssertionError("管理单没显示")
        findTextView(manage, "删除项目")?.performClick()
        val confirm = showingDialog() ?: throw AssertionError("删除确认框没显示")
        confirm.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertFalse(confirm.isShowing)
        assertTrue(h.deleted.isEmpty())
        assertTrue(h.store.listProjects().any { it.projectId == "wherevercode-agent" })
    }
}

/** 递归收一个 View 下全部 TextView 的文字（Harness 与用例共用，故放文件级）。 */
private fun collectText(view: View): String {
    val out = StringBuilder()
    if (view is TextView) out.append(view.text.toString()).append('\n')
    if (view is ViewGroup) {
        for (i in 0 until view.childCount) out.append(collectText(view.getChildAt(i)))
    }
    return out.toString()
}

/** 在一行里找行尾的“···”管理入口（没有返回 null）。 */
private fun findManageButton(view: View): TextView? {
    if (view is TextView && view.text.toString() == "···") return view
    if (view is ViewGroup) {
        for (i in 0 until view.childCount) {
            findManageButton(view.getChildAt(i))?.let { return it }
        }
    }
    return null
}

/** 在一行里找选中行上的“进入”（没有返回 null）。 */
private fun findEnterButton(view: View): TextView? {
    if (view is TextView && view.text.toString() == "进入") return view
    if (view is ViewGroup) {
        for (i in 0 until view.childCount) {
            findEnterButton(view.getChildAt(i))?.let { return it }
        }
    }
    return null
}

/** 当前正在显示的对话框（没有返回 null；已关掉的不算）。 */
private fun showingDialog(): AlertDialog? =
    ShadowDialog.getShownDialogs().lastOrNull { it.isShowing } as? AlertDialog

/** 一个对话框里全部 TextView 的文字（标题 + 说明 + 选项行）。 */
private fun dialogText(dialog: AlertDialog): String {
    val decor = dialog.window?.decorView ?: return ""
    return collectText(decor)
}

/** 在对话框里按文字找一行（ListDialog 的选项行，没有返回 null）。 */
private fun findTextView(dialog: AlertDialog, text: String): TextView? {
    val decor = dialog.window?.decorView ?: return null
    return findTextView(decor, text)
}

private fun findTextView(view: View, text: String): TextView? {
    if (view is TextView && view.text.toString() == text) return view
    if (view is ViewGroup) {
        for (i in 0 until view.childCount) {
            findTextView(view.getChildAt(i), text)?.let { return it }
        }
    }
    return null
}
