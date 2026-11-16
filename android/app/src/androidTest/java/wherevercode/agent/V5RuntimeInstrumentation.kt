package wherevercode.agent

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File

/** Real emulator smoke check: public view clicks, no fixture messages or route overrides. */
class V5RuntimeInstrumentation : Instrumentation() {
    private lateinit var activity: Activity

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
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
            // Brain demote (2026-11-17): the chat main line carries no
            // brain entry — the brain capsule is display-only now.
            open("nav_chat")
            val capsule = activity.findViewById<View>(R.id.brainCapsule)
            check(capsule != null && !capsule.isClickable) {
                "Brain capsule is still a clickable main-line entry"
            }
            capture("08-chat-no-brain-entry")
            // The only brain door is the feature center (全部功能) in the
            // drawer: open it, scroll its dialog list to 大脑, then go
            // hub -> 大脑管理 and check the real management section renders.
            clickId(R.id.buttonMenu)
            clickTag("nav_feature_center")
            scrollDialogListTo("大脑")
            capture("09-feature-center-brain")
            clickDialogItem("大脑")
            capture("10-brain-hub")
            clickDialogItem("大脑管理")
            val manageDeadline = SystemClock.elapsedRealtime() + 10_000
            while (find { it is TextView && it.text.toString() == "已添加的大脑（点按一行切换）" } == null &&
                SystemClock.elapsedRealtime() < manageDeadline) {
                SystemClock.sleep(100)
                waitForIdleSync()
            }
            check(find { it is TextView && it.text.toString() == "已添加的大脑（点按一行切换）" } != null) {
                "Brain management page did not open from the feature center"
            }
            capture("11-brain-manage")
            // Self-pick mode (2026-11-17): the partner capsule on the chat
            // screen opens the partner picker （选择对话方）; on a fresh
            // install it honestly offers to add an Agent or configure a
            // model instead of inventing partners.
            open("nav_chat")
            clickId(R.id.buttonAgentSelect)
            val pickerDeadline = SystemClock.elapsedRealtime() + 10_000
            while (findNodeByText("选择对话方") == null &&
                SystemClock.elapsedRealtime() < pickerDeadline) {
                SystemClock.sleep(100)
                waitForIdleSync()
            }
            check(findNodeByText("选择对话方") != null) {
                "Partner picker did not open from the chat partner capsule"
            }
            capture("12-partner-picker")
            finish(Activity.RESULT_OK, Bundle().apply {
                putString("stream", "V5_RUNTIME_OK: 12 real emulator captures; no backend fixture data\n")
            })
        } catch (failure: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply {
                putString("stream", "V5_RUNTIME_FAILED: ${failure.stackTraceToString()}\n")
            })
        }
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
        runOnMainSync { check(view.isShown && view.performClick()) { "View cannot be clicked" } }
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

    /**
     * Dialog windows (AlertDialog) live outside the activity window and
     * UiAutomation exposes them as accessibility node trees, not Views:
     * search every window root's node tree by exact visible text.
     */
    private fun collectNodes(
        node: android.view.accessibility.AccessibilityNodeInfo,
        out: MutableList<android.view.accessibility.AccessibilityNodeInfo>,
    ) {
        out += node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectNodes(it, out) }
        }
    }

    private fun allWindowNodes(): List<android.view.accessibility.AccessibilityNodeInfo> {
        val out = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
        // getWindows() returns an empty list in this instrumentation
        // setup (diagnostics run: windows=0); the active window root —
        // the dialog while one is open — is the reliable tree.
        uiAutomation.rootInActiveWindow?.let { collectNodes(it, out) }
        if (out.isEmpty()) {
            for (window in uiAutomation.windows) {
                window.root?.let { collectNodes(it, out) }
            }
        }
        return out
    }

    private fun findNodeByText(text: String): android.view.accessibility.AccessibilityNodeInfo? =
        allWindowNodes().firstOrNull { it.text?.toString() == text }

    /**
     * Scroll the visible dialog list until the row with [text] is in the
     * tree. The dialog may need a moment to appear, and its ListView
     * accessibility node does not reliably flag itself scrollable, so:
     * wait for any ListView node across all windows (up to ~15 s), then
     * scroll it forward until the row shows up.
     */
    private fun scrollDialogListTo(text: String) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (findNodeByText(text) == null && SystemClock.elapsedRealtime() < deadline) {
            val list = allWindowNodes().firstOrNull {
                it.className?.toString() == "android.widget.ListView"
            }
            if (list != null) {
                runOnMainSync {
                    list.performAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
                    )
                }
            }
            settle()
        }
        check(findNodeByText(text) != null) { "Dialog row never appeared: $text; diag: ${dialogDiagnostics()}" }
    }

    /** Failure diagnostics: what the accessibility tree actually sees. */
    private fun dialogDiagnostics(): String {
        val nodes = allWindowNodes()
        val texts = nodes.mapNotNull { it.text?.toString() }.take(60)
        val classes = nodes.mapNotNull { it.className?.toString() }.distinct().take(20)
        return "windows=${uiAutomation.windows.size} activeRoot=${uiAutomation.rootInActiveWindow != null} nodes=${nodes.size} " +
            "hasListView=${nodes.any { it.className?.toString() == "android.widget.ListView" }} " +
            "classes=$classes texts=$texts"
    }

    private fun clickDialogItem(text: String) {
        scrollDialogListTo(text)
        var node = findNodeByText(text) ?: error("Dialog row not found: $text")
        while (!node.isClickable) {
            node = node.parent ?: error("Dialog row is not clickable: $text")
        }
        runOnMainSync {
            check(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
                "Dialog item cannot be clicked: $text"
            }
        }
        settle()
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
