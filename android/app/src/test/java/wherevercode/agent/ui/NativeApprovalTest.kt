package wherevercode.agent.ui

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import wherevercode.agent.AndroidApprovalGate
import wherevercode.agent.R
import wherevercode.agent.brain.Action

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NativeApprovalTest {
    private lateinit var host: Activity
    private lateinit var gate: AndroidApprovalGate
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Before fun setup() {
        AndroidApprovalGate.resetForTests()
        host = Robolectric.buildActivity(Activity::class.java).setup().get()
        host.setTheme(R.style.Theme_WhereverCode)
        gate = AndroidApprovalGate.get(host)
        gate.attach(host)
    }
    @After fun close() {
        gate.detach(host)
        AndroidApprovalGate.resetForTests()
        shadowOf(Looper.getMainLooper()).idle()
        scope.cancel()
        host.finish()
    }
    private fun text(view: View): String = (if (view is TextView) view.text.toString() else "") +
        if (view is ViewGroup) (0 until view.childCount).joinToString("\n") { text(view.getChildAt(it)) } else ""

    @Test fun nativeApprovalShowsDeadlineWithoutFileBodyAndRevokesOnDetach() = runBlocking {
        val action = Action(actionId = "approval", tool = "write_file", args = mapOf("path" to "sample.txt", "content" to "PRIVATE_FILE_BODY"), idempotencyKey = "once")
        val result = scope.async { gate.requestApproval(action) }
        val until = System.currentTimeMillis() + 4000
        var dialog: AlertDialog? = null
        while (dialog == null && System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            dialog = ShadowDialog.getLatestDialog() as? AlertDialog
            if (dialog == null) Thread.sleep(10)
        }
        assertNotNull(dialog)
        val shown = dialog!!
        assertTrue(text(shown.window!!.decorView).contains("秒后自动拒绝"))
        assertFalse(text(shown.window!!.decorView).contains("PRIVATE_FILE_BODY"))
        assertEquals("允许一次", shown.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        shown.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(withTimeout(4000) { result.await() })
        assertTrue(gate.isApprovalStillValid(action))
        gate.detach(host)
        assertFalse(gate.isApprovalStillValid(action))
    }
}
