package wherevercode.agent.ui

import android.graphics.Typeface
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
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

/**
 * ListDialog（2026-11-11，maintainer 19:37 三张截图的根因回归）：
 * AlertDialog 的 setMessage + setItems 同用时列表直接不显示——添加项目
 * 弹框、项目管理单、大脑管理单、选设备/选文件夹弹框全是空的，只剩说明
 * 文字 + 取消/关闭。ListDialog 用自定义 view 拼说明 + 可点选项行，
 * 说明和选项必须同时可见、点选项必须回调正确的下标、关掉后不再显示。
 * Robolectric，CI 跑。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class ListDialogTest {

    private val context = ContextThemeWrapper(
        RuntimeEnvironment.getApplication(),
        R.style.Theme_WhereverCode,
    )

    @Test
    fun `message and all items are visible at once`() {
        // 真机 bug 的原样：说明 + 选项必须同时出现（以前是添加项目弹框只剩
        // 说明文字，选项全被 setMessage 顶掉）。样本用“打开文件夹”入口单子
        // 的三个位置文案。
        val items = listOf("本机文件夹", "远程设备上的文件夹", "git 仓库地址")
        ListDialog.show(
            context,
            "链到设备上的检出？",
            "以下设备上有与仓库同名的文件夹，确认是该仓库的检出再点它链过去。",
            items,
        ) {}
        val dialog = showingDialog() ?: throw AssertionError("弹框没显示")
        val text = collectText(dialog.window?.decorView ?: throw AssertionError("弹框没有 decorView"))
        assertTrue(text.contains("与仓库同名的文件夹"))
        assertTrue(text.contains("本机文件夹"))
        assertTrue(text.contains("远程设备上的文件夹"))
        assertTrue(text.contains("git 仓库地址"))
        assertTrue(text.contains("取消"))
    }

    @Test
    fun `selected index renders a checkmark on that row only`() {
        // 2026-11-12 远程文件夹选择器：当前家置顶并默认选中——选中态是
        // 真画出来的（行首 ✓ + 加粗），不是只换文案假装选中；没选中的
        // 行不带 ✓；点选中的行仍回调它的下标。
        val picked = mutableListOf<Int>()
        val dialog = ListDialog.show(
            context,
            "已开放文件夹",
            "选一个文件夹作为项目之家。",
            listOf("当前家 · /Users/example-user/Projects/wherevercode-agent", "Codex · /Users/example-user/v5-codex-work"),
            selectedIndex = 0,
        ) { picked += it }
        val text = collectText(dialog.window?.decorView ?: throw AssertionError("弹框没有 decorView"))
        assertTrue(text.contains("✓ 当前家 · /Users/example-user/Projects/wherevercode-agent"))
        assertTrue(text.contains("Codex · /Users/example-user/v5-codex-work"))
        assertFalse(text.contains("✓ Codex"))
        val row = findTextView(dialog, "✓ 当前家 · /Users/example-user/Projects/wherevercode-agent")
            ?: throw AssertionError("当前家行没显示出来")
        assertEquals(Typeface.BOLD, row.typeface.style)
        row.performClick()
        assertEquals(listOf(0), picked)
        assertFalse(dialog.isShowing)
    }

    @Test
    fun `no selected index renders no checkmark`() {
        val dialog = ListDialog.show(
            context,
            "已开放文件夹",
            "选一个文件夹作为项目之家。",
            listOf("Codex · /Users/example-user/v5-codex-work", "Claude · /Users/example-user/v5-claude-work"),
        ) {}
        val text = collectText(dialog.window?.decorView ?: throw AssertionError("弹框没有 decorView"))
        assertFalse(text.contains("✓"))
    }

    @Test
    fun `tapping an item calls onPick with its index and dismisses`() {
        val picked = mutableListOf<Int>()
        val dialog = ListDialog.show(
            context,
            "选一台设备",
            "选一台设备，下一步选一个文件夹作为项目之家。",
            listOf("设备 A · 1.2.3.4:18743 · 已配对", "设备 B · 5.6.7.8:18743 · 已配对"),
        ) { picked += it }
        val row = findTextView(dialog, "设备 B · 5.6.7.8:18743 · 已配对")
            ?: throw AssertionError("第二个选项没显示出来")
        row.performClick()
        assertEquals(listOf(1), picked)
        assertFalse(dialog.isShowing)
    }

    @Test
    fun `descriptions show name and description lines at once`() {
        // “打开文件夹”唯一入口的单子（2026-11-12，maintainer：项目来源收成一个）：
        // 每项有名 + 一行说明，名和说明必须同时可见。
        ListDialog.showWithDescriptions(
            context,
            "打开文件夹",
            "选一个位置打开它作为项目之家。",
            listOf(
                "本机文件夹" to "本机工作区里的文件夹；挂载、网盘同步下来的也是文件夹。",
                "远程设备上的文件夹" to "连一台已配对设备，再打开上面的文件夹。",
                "git 仓库地址" to "粘 GitHub / GitLab / Gitee 登记位置，经 API 读。",
            ),
        ) {}
        val dialog = showingDialog() ?: throw AssertionError("弹框没显示")
        val text = collectText(dialog.window?.decorView ?: throw AssertionError("弹框没有 decorView"))
        assertTrue(text.contains("选一个位置打开它作为项目之家。"))
        assertTrue(text.contains("本机文件夹"))
        assertTrue(text.contains("挂载、网盘同步下来的也是文件夹。"))
        assertTrue(text.contains("远程设备上的文件夹"))
        assertTrue(text.contains("连一台已配对设备"))
        assertTrue(text.contains("git 仓库地址"))
        assertTrue(text.contains("粘 GitHub / GitLab / Gitee 登记位置"))
        assertTrue(text.contains("取消"))
    }

    @Test
    fun `tapping a description row calls onPick with its index`() {
        // 两行行整行可点：点名那行（子 TextView）的父级行，回调下标并关掉。
        val picked = mutableListOf<Int>()
        val dialog = ListDialog.showWithDescriptions(
            context,
            "打开文件夹",
            "选一个位置打开它作为项目之家。",
            listOf(
                "本机文件夹" to "本机工作区里的文件夹。",
                "远程设备上的文件夹" to "连一台已配对设备，再打开上面的文件夹。",
                "git 仓库地址" to "粘仓库地址登记位置。",
            ),
        ) { picked += it }
        val nameView = findTextView(dialog, "远程设备上的文件夹")
            ?: throw AssertionError("第二个选项的名没显示出来")
        val row = nameView.parent as? View
            ?: throw AssertionError("选项行没有父级")
        row.performClick()
        assertEquals(listOf(1), picked)
        assertFalse(dialog.isShowing)
    }

    @Test
    fun `no message still shows items`() {
        val picked = mutableListOf<Int>()
        val dialog = ListDialog.show(
            context,
            "切换当前家",
            null,
            listOf("GitHub · o/r", "本机 · my-project"),
        ) { picked += it }
        val text = collectText(dialog.window?.decorView ?: throw AssertionError("弹框没有 decorView"))
        assertTrue(text.contains("GitHub · o/r"))
        assertTrue(text.contains("本机 · my-project"))
        findTextView(dialog, "本机 · my-project")?.performClick()
        assertEquals(listOf(1), picked)
    }

    @Test
    fun `negative button dismisses without picking`() {
        val picked = mutableListOf<Int>()
        val dialog = ListDialog.show(
            context,
            "添加项目",
            "选一个位置开始。",
            listOf("本机文件夹", "远程设备上的文件夹", "git 仓库地址"),
        ) { picked += it }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        // AppCompat 对话框的按钮点击经 Handler 消息分发：Robolectric 的
        // 主 looper 默认暂停，不 idle 的话监听与 dismiss 都不会跑。
        ShadowLooper.idleMainLooper()
        assertTrue(picked.isEmpty())
        assertFalse(dialog.isShowing)
    }

    private fun showingDialog(): AlertDialog? =
        ShadowDialog.getShownDialogs().lastOrNull { it.isShowing } as? AlertDialog

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

    private fun collectText(view: View): String {
        val out = StringBuilder()
        if (view is TextView) out.append(view.text.toString()).append('\n')
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) out.append(collectText(view.getChildAt(i)))
        }
        return out.toString()
    }
}
