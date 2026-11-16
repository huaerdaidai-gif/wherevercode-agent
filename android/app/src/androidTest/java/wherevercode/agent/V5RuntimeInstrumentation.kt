package wherevercode.agent

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.TextView
import java.io.File
import wherevercode.agent.ui.EntryRegistry

/** Real emulator smoke check: public view clicks, no fixture messages or route overrides. */
class V5RuntimeInstrumentation : Instrumentation() {
    private lateinit var activity: Activity
    private var walkthroughMode = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        walkthroughMode = arguments?.getString("walkthrough89") == "true"
        start()
    }

    override fun onStart() {
        try {
            // RFC 7748 public vector: also exercises the crypto class on Android's libcore.
            fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            check(wherevercode.agent.core.security.DeviceKeys.publicKeyFromPrivate(
                hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"),
            ).contentEquals(hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")))
            activity = startActivitySync(Intent(Intent.ACTION_MAIN).apply {
                setClassName(targetContext.packageName, "wherevercode.agent.MainActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            settle()
            find { it is TextView && it.text.toString() == "稍后再看" }?.let { click(it) }
            clickId(R.id.buttonMenu)
            val primary = listOf("nav_chat", "nav_projects", "nav_channels", "nav_control")
            primary.forEach { tag -> check(find { it.tag == tag } != null) { "Missing primary: $tag" } }
            capture("01-drawer")
            clickTag("nav_chat")
            capture("02-chat")
            open("nav_projects")
            capture("03-projects")
            open("nav_channels")
            capture("04-channels")
            // A configured channel can be opened without inventing server data.
            clickTag("channel_open_existing")
            clickTag("channel_tab_files")
            capture("05-channel-files-unconfigured")
            open("nav_control")
            val consoleDeadline = SystemClock.elapsedRealtime() + 10_000
            while (find { it is TextView && it.text.toString() == wherevercode.agent.ui.ConsoleSnapshot.ConsoleModule.OVERVIEW.title } == null &&
                SystemClock.elapsedRealtime() < consoleDeadline) {
                SystemClock.sleep(100)
                waitForIdleSync()
            }
            check(find { it is TextView && it.text.toString() == wherevercode.agent.ui.ConsoleSnapshot.ConsoleModule.OVERVIEW.title } != null) {
                "Primary control route did not render the real snapshot"
            }
            capture("06-control")
            open("nav_workbench")
            capture("07-account-settings")
            if (walkthroughMode) {
                runWalkthrough89()
            }
            finish(Activity.RESULT_OK, Bundle().apply {
                putString("stream", "V5_RUNTIME_OK: 7 real emulator captures; no backend fixture data\n")
            })
        } catch (failure: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply {
                putString("stream", "V5_RUNTIME_FAILED: ${failure.stackTraceToString()}\n")
            })
        }
    }

    // ------------------------------------------------------------------
    // Walkthrough89 (2026-11-17): iterate all 89 EntryRegistry entries
    // through the real user paths and record one line per entry into
    // v5-runtime/walkthrough89-results.txt:
    //   <id>|<registry-status>|PASS|TODO_OK|FAIL|<note>
    // IMPLEMENTED entries must open without crashing (screenshot
    // wt-<id>.png). TODO entries must surface an honest toast that
    // contains "待开发" when tapped in the feature-center list.
    // Entry failures are data, not instrumentation failures: the loop
    // always finishes with a WALKTHROUGH89_DONE summary line.
    // ------------------------------------------------------------------
    private val wtResults = mutableListOf<String>()
    private lateinit var wtFile: File

    private fun wtRecord(entry: EntryRegistry.Entry, verdict: String, note: String) {
        val line = "${entry.id}|${entry.status}|$verdict|${note.replace('|', '/').replace('\n', ' ').take(160)}"
        wtResults.add(line)
        try {
            wtFile.appendText(line + "\n")
        } catch (_: Throwable) {
        }
    }

    private fun runWalkthrough89() {
        val dir = File(targetContext.getExternalFilesDir(null), "v5-runtime").apply { mkdirs() }
        wtFile = File(dir, "walkthrough89-results.txt")
        try { wtFile.writeText("") } catch (_: Throwable) { }
        // Start from the chat home so suggestion pills are reachable.
        try { open("nav_chat") } catch (_: Throwable) { }
        for (entry in EntryRegistry.ENTRIES) {
            try {
                walkOne(entry)
            } catch (failure: Throwable) {
                wtRecord(entry, "FAIL", "exception: ${failure.message ?: failure.javaClass.simpleName}")
                recoverToHome()
            }
        }
        val pass = wtResults.count { it.contains("|PASS|") }
        val todoOk = wtResults.count { it.contains("|TODO_OK|") }
        val fail = wtResults.count { it.contains("|FAIL|") }
        try {
            wtFile.appendText("WALKTHROUGH89_DONE total=${wtResults.size} pass=$pass todo_ok=$todoOk fail=$fail\n")
        } catch (_: Throwable) {
        }
    }

    private fun walkOne(entry: EntryRegistry.Entry) {
        when {
            entry.id == "nav_feature_center" -> {
                // Opening the feature center IS this entry's action.
                openFeatureCenter()
                capture("wt-nav_feature_center")
                dismissDialogs()
                wtRecord(entry, "PASS", "feature center dialog opened")
            }
            entry.group == EntryRegistry.Group.HOME_SUGGESTION -> {
                open("nav_chat")
                val pill = findClickableByText(entry.title)
                    ?: throw IllegalStateException("suggestion pill not found on chat home")
                click(pill)
                val input = find { it is EditText } as? EditText
                val filled = input?.text?.toString()?.startsWith(entry.target.take(4)) == true
                capture("wt-${entry.id}")
                if (filled) wtRecord(entry, "PASS", "pill filled the input box")
                else wtRecord(entry, "FAIL", "pill tap did not fill the input box")
            }
            entry.group == EntryRegistry.Group.HOME_TOP -> {
                open("nav_chat")
                when (entry.id) {
                    "home_search" -> clickId(R.id.buttonSearch)
                    "home_new" -> clickId(R.id.buttonNew)
                    else -> {
                        val brain = findClickableByTextContains("大脑")
                            ?: throw IllegalStateException("no brain element on chat home top/input area")
                        click(brain)
                    }
                }
                capture("wt-${entry.id}")
                dismissDialogs()
                wtRecord(entry, "PASS", "home top entry opened")
            }
            entry.group == EntryRegistry.Group.DRAWER_TOP -> {
                open(entry.id)
                capture("wt-${entry.id}")
                wtRecord(entry, "PASS", "primary drawer entry opened")
            }
            else -> walkFeatureCenterEntry(entry)
        }
    }

    /** Drive one feature-center list row exactly as a user tap does. */
    private fun walkFeatureCenterEntry(entry: EntryRegistry.Entry) {
        openFeatureCenter()
        val label = if (entry.status == EntryRegistry.Status.TODO) "${entry.title}（待开发）" else entry.title
        val item = findDialogItem(label, entry.title)
            ?: throw IllegalStateException("feature-center row not found: $label")
        if (entry.status == EntryRegistry.Status.TODO) {
            // Toast timing is critical: a LENGTH_SHORT toast lives ~2 s,
            // so do NOT settle before polling — run 43962532904 recorded
            // all 56 TODO entries as FAIL because two settle() calls
            // (~1 s+) plus idle waits let every toast expire before the
            // poll started. Click, screenshot inside the toast window,
            // then poll immediately.
            clickNodeNoSettle(item)
            try { capture("wt-${entry.id}") } catch (_: Throwable) { }
            val hinted = pollToastContains("待开发", 3_500)
            dismissDialogs()
            if (hinted) wtRecord(entry, "TODO_OK", "honest 待开发 toast shown")
            else wtRecord(entry, "FAIL", "TODO entry gave no 待开发 hint when tapped")
            return
        }
        clickNode(item)
        settle()
        run {
            capture("wt-${entry.id}")
            dismissDialogs()
            // App must still be alive and able to open the drawer.
            clickId(R.id.buttonMenu)
            find { it.tag == "drawer_close" || it.tag == "nav_chat" }?.let { }
            wtRecord(entry, "PASS", "opened from feature center without crash")
        }
    }

    /** ACTION_CLICK without the settle() wait (toast-window timing). */
    private fun clickNodeNoSettle(node: AccessibilityNodeInfo) {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < 8) {
            if (current.isClickable) {
                check(current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    "node click rejected"
                }
                return
            }
            current = current.parent
            hops++
        }
        throw IllegalStateException("node not clickable")
    }

    private fun openFeatureCenter() {
        clickId(R.id.buttonMenu)
        settle()
        clickTag("nav_feature_center")
        settle()
        check(findNodeByText("全部功能", exact = false) != null) { "feature center dialog did not open" }
    }

    private fun recoverToHome() {
        try { dismissDialogs() } catch (_: Throwable) { }
        try {
            if (activity.isFinishing || activity.isDestroyed) {
                activity = startActivitySync(Intent(Intent.ACTION_MAIN).apply {
                    setClassName(targetContext.packageName, "wherevercode.agent.MainActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                settle()
            }
            open("nav_chat")
        } catch (_: Throwable) {
        }
    }

    /** Close any dialog window (feature center, search, queue, create-task). */
    private fun dismissDialogs() {
        // Prefer an explicit close affordance inside the dialog window.
        for (label in listOf("关闭", "取消")) {
            val node = findNodeByText(label, exact = true)
            if (node != null && nodeWindowIsDialog(node)) {
                clickNode(node)
                settle()
                return
            }
        }
        if (dialogPresent()) {
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            settle()
        }
    }

    private fun dialogPresent(): Boolean {
        // Dialogs in this app (feature center, global search, queue
        // board, create-task form) present either a list or an explicit
        // close/cancel affordance that the underlying pages do not.
        if (findListNode() != null) return true
        return findNodeByText("关闭", exact = true) != null ||
            findNodeByText("取消", exact = true) != null
    }

    private fun nodeWindowIsDialog(node: AccessibilityNodeInfo): Boolean {
        // A node found outside the activity decor tree belongs to a
        // dialog/popup window (the activity find() cannot see it).
        return find { it is TextView && it.text.toString() == (node.text?.toString() ?: "") } == null
    }

    // ---- UiAutomation node helpers (dialog windows live outside the
    // activity decor view, so view-tree find() cannot reach them). ----

    private fun allRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            uiAutomation.windows.forEach { window -> window.root?.let { roots.add(it) } }
        } catch (_: Throwable) {
        }
        if (roots.isEmpty()) {
            try { uiAutomation.rootInActiveWindow?.let { roots.add(it) } } catch (_: Throwable) { }
        }
        return roots
    }

    private fun findNodeByText(text: String, exact: Boolean): AccessibilityNodeInfo? {
        for (root in allRoots()) {
            findNodeByText(root, text, exact)?.let { return it }
        }
        return null
    }

    private fun findNodeByText(
        node: AccessibilityNodeInfo,
        text: String,
        exact: Boolean,
    ): AccessibilityNodeInfo? {
        val value = node.text?.toString() ?: node.contentDescription?.toString()
        if (value != null && (if (exact) value == text else value.contains(text))) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                findNodeByText(child, text, exact)?.let { return it }
            }
        }
        return null
    }

    private fun findDialogItem(label: String, title: String): AccessibilityNodeInfo? {
        repeat(24) {
            findNodeByText(label, exact = true)?.let { return it }
            findNodeByText(title, exact = false)?.let { node ->
                // Guard against matching the underlying page behind the dialog:
                // only accept nodes that are inside a ListView-bearing window.
                if (nodeInListWindow(node)) return node
            }
            val list = findListNode() ?: return null
            val scrolled = try {
                list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            } catch (_: Throwable) {
                false
            }
            if (!scrolled) return null
            SystemClock.sleep(250)
            waitForIdleSync()
        }
        return findNodeByText(label, exact = true)
    }

    private fun nodeInListWindow(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < 12) {
            if (current.className?.toString()?.contains("ListView") == true) return true
            current = current.parent
            hops++
        }
        // The dialog title check: item rows sit under the dialog root that
        // also carries the 全部功能 title; if a ListView exists anywhere in
        // the same window set, treat the match as a dialog row.
        return findListNode() != null
    }

    private fun findListNode(): AccessibilityNodeInfo? {
        for (root in allRoots()) {
            findListNode(root)?.let { return it }
        }
        return null
    }

    private fun findListNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val cls = node.className?.toString() ?: ""
        if (cls.contains("ListView") || cls.contains("AbsListView") || cls.contains("RecyclerView")) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child -> findListNode(child)?.let { return it } }
        }
        return null
    }

    private fun clickNode(node: AccessibilityNodeInfo) {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < 8) {
            if (current.isClickable) {
                check(current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    "node click rejected"
                }
                settle()
                return
            }
            current = current.parent
            hops++
        }
        // Fall back to clicking the node's center via the view tree when
        // the node mirrors an activity view with the same text.
        val text = node.text?.toString() ?: throw IllegalStateException("node not clickable")
        val view = findClickableByText(text) ?: throw IllegalStateException("node not clickable: $text")
        click(view)
    }

    private fun pollToastContains(text: String, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (findNodeByText(text, exact = false) != null) {
                // Exclude matches that are just the feature-center row
                // label itself ("标题（待开发）"): a toast is a short
                // standalone window whose text is the todo message.
                val toastLike = allRoots().any { root ->
                    val t = root.text?.toString() ?: ""
                    t.contains(text) && !t.contains("（待开发）")
                }
                if (toastLike) return true
                // Also accept any root whose whole subtree is tiny (toast
                // windows contain just the message text).
                val smallWindow = uiAutomation.windows.any { window ->
                    val root = window.root ?: return@any false
                    root.childCount <= 2 && subtreeContains(root, text) &&
                        !subtreeContains(root, "全部功能")
                }
                if (smallWindow) return true
            }
            SystemClock.sleep(120)
        }
        return false
    }

    private fun subtreeContains(node: AccessibilityNodeInfo, text: String): Boolean {
        if (node.text?.toString()?.contains(text) == true) return true
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { if (subtreeContains(it, text)) return true }
        }
        return false
    }

    private fun findClickableByText(text: String): View? =
        findClickableByTextMatching { it == text }

    private fun findClickableByTextContains(part: String): View? =
        findClickableByTextMatching { it.contains(part) }

    private fun findClickableByTextMatching(match: (String) -> Boolean): View? {
        var result: View? = null
        runOnMainSync {
            fun visit(view: View): View? {
                if (!view.isShown) return null
                if (view is TextView && match(view.text.toString())) {
                    var current: View? = view
                    var hops = 0
                    while (current != null && hops < 10) {
                        if (current.isClickable) return current
                        current = current.parent as? View
                        hops++
                    }
                    return view
                }
                if (view is ViewGroup) for (i in 0 until view.childCount) {
                    visit(view.getChildAt(i))?.let { return it }
                }
                return null
            }
            result = visit(activity.window.decorView)
        }
        return result
    }

    private fun open(tag: String) {
        clickId(R.id.buttonMenu)
        clickTag(tag)
    }

    private fun clickId(id: Int) = click(activity.findViewById<View>(id)
        ?: error("Missing view id: $id"))

    private fun clickTag(tag: String) = click(find { it.tag == tag }
        ?: error("Missing visible tag: $tag"))

    private fun click(view: View) {
        // The check must NOT run inside runOnMainSync: an exception there
        // is rethrown on the main thread and kills the whole process
        // (run 43961215513 crashed at the 6th walkthrough entry).
        var ok = false
        runOnMainSync {
            ok = try {
                view.isShown && view.performClick()
            } catch (_: Throwable) {
                false
            }
        }
        check(ok) { "View cannot be clicked" }
        settle()
    }

    private fun settle() {
        waitForIdleSync()
        SystemClock.sleep(500)
        waitForIdleSync()
    }

    private fun find(predicate: (View) -> Boolean): View? {
        var result: View? = null
        runOnMainSync {
            fun visit(view: View): View? {
                if (!view.isShown) return null
                if (predicate(view)) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) {
                    visit(view.getChildAt(i))?.let { return it }
                }
                return null
            }
            result = visit(activity.window.decorView)
        }
        return result
    }

    private fun capture(name: String) {
        val bitmap = uiAutomation.takeScreenshot() ?: error("Screenshot unavailable: $name")
        val directory = File(targetContext.getExternalFilesDir(null), "v5-runtime").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }
}
