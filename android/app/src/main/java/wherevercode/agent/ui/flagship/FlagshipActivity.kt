package wherevercode.agent.ui.flagship

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import wherevercode.agent.MainActivity

/** Legacy deep-link key retained for compatibility with previously issued intents. */
const val EXTRA_FLAGSHIP_ENTRY = "wherevercode.agent.extra.FLAGSHIP_ENTRY"

/**
 * Compatibility redirect: the standalone 70-screen Compose shell is not a second app.
 * Existing shortcuts open the real chat-first MainActivity, preserving its View-based
 * conversation, project, task and settings flows.
 */
class FlagshipActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
        finish()
    }
}
