package wherevercode.agent.ui.flagship

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/** MainActivity 据此 extra 打开既有注册表入口（70 屏深链回既有真实屏）。 */
const val EXTRA_FLAGSHIP_ENTRY = "wherevercode.agent.extra.FLAGSHIP_ENTRY"

/**
 * 70 屏旗舰导航的宿主 Activity（双端组装第一轮）。
 *
 * 只承载 [FlagshipApp] 壳 + [AppFlagshipCapabilities] 适配器；不替代
 * MainActivity，既有真实功能仍在既有屏里运行，P0 路由经深链打开。
 */
class FlagshipActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FlagshipApp(provider = AppFlagshipCapabilities(this)) }
    }
}
