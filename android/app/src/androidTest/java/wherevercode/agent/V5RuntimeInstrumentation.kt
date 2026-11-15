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
            find { it.tag == "channel_open_existing" }?.let { click(it) }
            find { it.tag == "channel_tab_files" }?.let { click(it) }
            capture("05-channel-files-unconfigured")
            open("nav_control")
            capture("06-control")
            open("nav_workbench")
            capture("07-account-settings")
            finish(Activity.RESULT_OK, Bundle().apply {
                putString("stream", "V5_RUNTIME_OK: 7 real emulator captures; no backend fixture data\n")
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

    private fun capture(name: String) {
        val bitmap = uiAutomation.takeScreenshot() ?: error("Screenshot unavailable: $name")
        val directory = File(targetContext.getExternalFilesDir(null), "v5-runtime").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }
}
