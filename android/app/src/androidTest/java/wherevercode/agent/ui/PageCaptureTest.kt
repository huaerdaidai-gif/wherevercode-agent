package wherevercode.agent.ui

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import wherevercode.agent.BuildConfig
import wherevercode.agent.MainActivity
import wherevercode.agent.ui.flagship.EXTRA_FLAGSHIP_ENTRY

/**
 * 逐页截图验收（task/trae-android-ui-capture-20261115）。
 *
 * 修复目标：旧流程（FlagshipNavTest 的 unified-ui-evidence）出现过
 * “10 张截图全是同一张欢迎页”却 CI 全绿。根因是旧循环只检查
 * buttonMenu 存在——而首页壳在引导遮罩之下——且从不验证目标页身份。
 *
 * 本测试的硬规矩：
 * 1. 每次截图前必须通过页面身份断言（稳定 resource-id / 文本标记），
 *    且断言全屏遮罩（引导页、权限弹窗）已消失。
 * 2. 每页独立文件名，截图后立即计算 SHA-256；两页哈希相同 =
 *    截图冒充，测试末尾整体失败（页面身份是主判据，哈希重复是
 *    额外闸门：状态栏时钟等动态元素让真不同页几乎不可能同哈希，
 *    所以同哈希几乎必然是同一画面）。
 * 3. 每页写入 manifest（页面 id、标记断言结果、哈希、设备信息、
 *    构建commit），供 CI 端 verify_page_capture.py 复核。
 * 4. 失败时留存 dumpUiTexts + UI 层级 XML，不含任何凭据。
 *
 * 导航方式：EXTRA_FLAGSHIP_ENTRY 深链（EntryRegistry 稳定 id，
 * App 自身的导航路径，非坐标、非固定 sleep）；侧边栏与“模型”跳转
 * 用真实 UI 点击，点击后同样验证页面确实变化。
 */
@RunWith(AndroidJUnit4::class)
class PageCaptureTest {

    companion object {
        /** 引导遮罩独有的文本；任一可见 = 遮罩仍在，禁止截图。 */
        private val ONBOARDING_MARKERS = listOf(
            "开始使用 →",          // OnboardingController.WELCOME_START
            "稍后再看",            // WELCOME_LATER
            "选择你的使用方向",     // DIRECTION_TITLE
            "解锁手机执行能力",     // ADB_TITLE
            "准备完成",            // DONE_TITLE
        )

        /** 系统权限弹窗按钮 resource-id（中英文镜像都处理）。 */
        private val PERMISSION_DENY_IDS = listOf(
            "com.android.permissioncontroller:id/permission_deny_button",
            "com.android.permissioncontroller:id/permission_allow_no_button",
        )

        /** 系统 ANR 弹窗（System UI / App 无响应）的“等待”按钮文本。 */
        private val ANR_WAIT_TEXTS = listOf("Wait", "等待")

        private const val OUTPUT_DIR_NAME = "page-capture"
    }

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    // 证据写内部存储（filesDir）：API 30+ 的 FUSE 会拒绝 shell 直读
    // /sdcard/Android/data（连 run-as 也被拒），内部目录可用
    // `adb exec-out run-as <pkg> tar -c files/page-capture` 稳定取回。
    private val output = File(context.filesDir, OUTPUT_DIR_NAME).apply { mkdirs() }
    private val manifest = JSONObject()
        .put("gitSha", BuildConfig.COMMIT_SHA)
        .put("buildTime", BuildConfig.BUILD_TIME)
        .put("deviceModel", Build.MODEL)
        .put("apiLevel", Build.VERSION.SDK_INT)
        .put("packageName", context.packageName)
    private val pages = JSONArray()
    private val seenHashes = mutableMapOf<String, String>()

    private fun persistManifest() {
        manifest.put("pages", pages)
        File(output, "manifest.json").writeText(manifest.toString(2))
    }

    private fun dumpUiTexts(name: String) {
        val texts = mutableListOf<String>()
        for (clazz in listOf("android.widget.TextView", "android.widget.Button", "android.widget.EditText")) {
            for (obj in device.findObjects(By.clazz(clazz))) {
                val t = obj.text?.trim().orEmpty()
                if (t.isNotEmpty()) texts += "$clazz: $t"
            }
        }
        File(output, "$name-ui.txt").writeText(texts.joinToString("\n").ifEmpty { "(no visible text)" })
        runCatching { device.dumpWindowHierarchy(File(output, "$name-ui.xml")) }
    }

    /** 权限弹窗在场时按用户方式拒绝；返回是否点了。 */
    private fun dismissPermissionDialogIfAny(): Boolean {
        for (res in PERMISSION_DENY_IDS) {
            val btn = device.findObject(By.res(res))
            if (btn != null) {
                btn.click()
                device.waitForIdle()
                return true
            }
        }
        // System UI / App ANR：点“等待”，保留现场继续验收。
        for (text in ANR_WAIT_TEXTS) {
            val wait = device.findObject(By.text(text))
            if (wait != null) {
                wait.click()
                device.waitForIdle()
                return true
            }
        }
        return false
    }

    /** 任何引导遮罩标记仍可见则返回该标记。 */
    private fun visibleOnboardingMarker(): String? =
        ONBOARDING_MARKERS.firstOrNull { device.hasObject(By.text(it)) }

    /**
     * 启动 App 并确保引导遮罩与权限弹窗真正消失。
     * 唯一可靠的“已进入首页”信号：遮罩标记连续两次（间隔 1s）确认
     * GONE + 首页问候语在场（首页壳存在于遮罩之下，仅凭 buttonMenu
     * 判定是旧流程假绿的根因；标记单次查询缺席也可能是 a11y 刷新窗口）。
     */
    private fun launchToChatHome() {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
        context.startActivity(launchIntent.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        val deadline = System.currentTimeMillis() + 120_000
        var lastOverlay: String? = null
        var stableHits = 0
        while (System.currentTimeMillis() < deadline) {
            if (dismissPermissionDialogIfAny()) continue
            val marker = visibleOnboardingMarker()
            if (marker != null) {
                lastOverlay = marker
                stableHits = 0
                // 各步的“跳过”按钮：稍后再看 / 跳过 / 跳过，先体验基础功能。
                val skip = device.findObject(By.text("稍后再看"))
                    ?: device.findObject(By.text("跳过，先体验基础功能"))
                    ?: device.findObject(By.text("跳过"))
                if (skip != null) {
                    skip.click()
                    // 等遮罩真的推进/消失（标记缺席才算点击生效），
                    // 未生效则下一轮重试，不用固定 sleep 猜。
                    device.wait(Until.gone(By.text(marker)), 5_000)
                }
                device.waitForIdle()
                continue
            }
            device.waitForIdle()
            val homeReady = device.currentPackageName == context.packageName &&
                device.hasObject(By.res(context.packageName, "buttonMenu")) &&
                device.hasObject(By.text("今天想做什么？"))
            if (homeReady) {
                // 双确认：a11y 树刷新窗口内遮罩标记可能瞬时查不到，
                // 而首页标记又能“看穿”遮罩——必须连续两次（间隔 1s）
                // 确认“遮罩缺席 + 首页在场”才算真正进入首页。
                stableHits++
                if (stableHits >= 2) {
                    persistLaunchState(dismissed = true)
                    return
                }
                Thread.sleep(1_000)
                continue
            }
            stableHits = 0
            Thread.sleep(500)
        }
        persistLaunchState(dismissed = false)
        dumpUiTexts("launch-blocked")
        error(
            "Onboarding/permission overlay did not clear within 120s " +
                "(lastOverlay=$lastOverlay, pkg=${device.currentPackageName}). " +
                "See launch-blocked-ui.txt",
        )
    }

    private fun persistLaunchState(dismissed: Boolean) {
        manifest.put("overlayDismissed", dismissed)
        manifest.put("wmSize", device.executeShellCommand("wm size").trim())
        manifest.put("wmDensity", device.executeShellCommand("wm density").trim())
    }

    /** 遮罩/弹窗在场即视为页面证据无效。 */
    private fun assertNoBlockingOverlay() {
        val marker = visibleOnboardingMarker()
        assertTrue(
            "Onboarding overlay still visible (marker=$marker) while capturing — evidence would be the welcome page again",
            marker == null,
        )
        assertFalse(
            "System permission dialog is blocking the page",
            PERMISSION_DENY_IDS.any { device.hasObject(By.res(it)) },
        )
        assertFalse(
            "System ANR dialog is blocking the page",
            ANR_WAIT_TEXTS.any { device.hasObject(By.clazz("android.widget.Button").textContains(it)) },
        )
    }

    /** 目标视图的可见区域与屏幕相交（防止“节点存在但在视口外”的假绿）。 */
    private fun isOnScreen(obj: UiObject2): Boolean {
        val display = Rect(0, 0, device.displayWidth, device.displayHeight)
        return obj.visibleBounds.intersect(display)
    }

    private fun waitForId(id: String, timeoutMs: Long = 20_000): UiObject2 {
        val obj = device.wait(Until.findObject(By.res(context.packageName, id)), timeoutMs)
            ?: run {
                dumpUiTexts("missing-$id")
                error("Page marker not found: $id (see missing-$id-ui.txt)")
            }
        return obj
    }

    private fun capture(pageId: String, fileName: String, marker: String) {
        assertNoBlockingOverlay()
        device.waitForIdle()
        val png = File(output, fileName)
        assertTrue("takeScreenshot returned false for $fileName", device.takeScreenshot(png))
        val bytes = png.readBytes()
        assertTrue("$fileName is empty", bytes.size > 0)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val duplicateOf = seenHashes[sha]
        seenHashes[sha] = fileName
        pages.put(
            JSONObject()
                .put("page", pageId)
                .put("file", fileName)
                .put("sha256", sha)
                .put("bytes", bytes.size)
                .put("identityMarker", marker)
                .put("duplicateOf", duplicateOf ?: JSONObject.NULL)
                .put("capturedAtEpochMs", System.currentTimeMillis()),
        )
        persistManifest()
        runCatching { device.dumpWindowHierarchy(File(output, fileName.removeSuffix(".png") + "-hierarchy.xml")) }
        if (duplicateOf != null) {
            // 不立即中止：先把余下页面也留证，测试末尾统一失败。
            System.err.println("DUPLICATE-SHA256: $fileName identical to $duplicateOf")
        }
    }

    private fun deepLinkTo(entryId: String) {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_FLAGSHIP_ENTRY, entryId)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
        )
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        device.waitForIdle()
    }

    private fun openSidebarViaMenuButton() {
        waitForId("buttonMenu").click()
        // 抽屉行文本（DrawerController）：打开成功 = 行可见。
        val drawerRow = device.wait(Until.findObject(By.text("工具与连接")), 15_000)
            ?: run {
                dumpUiTexts("sidebar-open-blocked")
                error("Drawer row 工具与连接 not visible after menu tap (see sidebar-open-blocked-ui.txt)")
            }
        assertTrue("Drawer row not on screen", isOnScreen(drawerRow))
    }

    private fun closeSidebarAndVerify() {
        device.pressBack()
        device.waitForIdle()
        assertTrue(
            "Drawer did not close after Back (sidebar row still visible)",
            device.wait(Until.gone(By.text("工具与连接")), 10_000) == true,
        )
    }

    @Test
    fun captureSevenAcceptancePages() {
        assertTrue(
            "Only the isolated debug package may run this capture",
            context.packageName == "wherevercode.agent.ci",
        )
        try {
            captureAllPages()
        } finally {
            persistManifest()
        }
    }

    private fun captureAllPages() {
        launchToChatHome()

        // 01 聊天首页：问候语 + 顶栏菜单，且无遮罩。
        capture("01-chat-home", "01-chat-home.png", "text:今天想做什么？")

        // 02 侧边栏展开（真实点击）+ 截图后关闭并验证已关闭。
        openSidebarViaMenuButton()
        capture("02-sidebar", "02-sidebar.png", "text:工具与连接 (drawer row)")
        closeSidebarAndVerify()

        // 03 任务页：入口按钮在屏，首页问候语已不可见（屏幕真的切换了）。
        deepLinkTo("nav_running_tasks")
        waitForId("buttonCreateTask")
        assertFalse(
            "Tasks page shows the chat-home greeting; screen did not switch",
            device.hasObject(By.text("今天想做什么？")),
        )
        capture("03-tasks", "03-tasks.png", "res:buttonCreateTask")

        // 04 项目页。
        deepLinkTo("nav_projects")
        waitForId("buttonImportLocal")
        assertFalse(
            "Projects page shows the task-create button; screen did not switch",
            device.hasObject(By.res(context.packageName, "buttonCreateTask")),
        )
        capture("04-projects", "04-projects.png", "res:buttonImportLocal")

        // 05 设备页：设备分节标题在屏，且 Agent 专属控件已被隐藏（设备视图真切换）。
        deepLinkTo("nav_devices")
        waitForId("deviceSectionTitle")
        assertFalse(
            "Devices page still shows the agents-only header; screen did not switch to devices view",
            device.hasObject(By.res(context.packageName, "agentHeader")),
        )
        capture("05-devices", "05-devices.png", "res:deviceSectionTitle")

        // 07 设置页：设置专属返回按钮 + 跳转 chips 行在屏。
        // 先截设置页顶部（首次打开，滚动位置在页首），
        // 再点“模型”跳转 chip 采 06，两页视口不同，截图才真实可区分。
        deepLinkTo("nav_workbench")
        waitForId("buttonSettingsBack")
        waitForId("settingsJumpAbout")
        capture("07-settings", "07-settings.png", "res:buttonSettingsBack + settingsJumpAbout")

        // 06 模型选择：设置页工具与连接分节，再真实点击“模型”跳转 chip，
        // 等模型列表容器滚到视口内后再截图。
        deepLinkTo("nav_tools")
        val modelChip = waitForId("settingsJumpModels")
        assertTrue("模型 jump chip not on screen", isOnScreen(modelChip))
        modelChip.click()
        val brainList = device.wait(Until.findObject(By.res(context.packageName, "brainListContainer")), 15_000)
            ?: run {
                dumpUiTexts("missing-model-section")
                error("Model section (brainListContainer) not found after 模型 chip tap")
            }
        val onScreenDeadline = System.currentTimeMillis() + 15_000
        while (!isOnScreen(brainList) && System.currentTimeMillis() < onScreenDeadline) {
            Thread.sleep(300)
            device.waitForIdle()
        }
        assertTrue(
            "Model list section did not scroll into view after 模型 chip tap",
            isOnScreen(brainList),
        )
        capture("06-model-selection", "06-model-selection.png", "res:brainListContainer on-screen after 模型 chip")

        manifest.put("device", JSONObject().put("model", Build.MODEL).put("brand", Build.BRAND))
        persistManifest()

        val duplicates = pages
            .let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }
            .filter { !it.isNull("duplicateOf") }
            .joinToString(", ") { "${it.getString("file")}==${it.getString("duplicateOf")}" }
        assertTrue(
            "Screenshot evidence contains identical images across pages (fake capture): $duplicates",
            duplicates.isEmpty(),
        )
    }
}
