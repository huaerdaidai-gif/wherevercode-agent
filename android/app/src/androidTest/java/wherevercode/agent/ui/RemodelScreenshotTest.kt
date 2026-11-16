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

/**
 * 安卓照 iOS 改（2026-11-16）逐页截图验收。
 *
 * 逐页身份断言 + 哈希去重 + manifest 的硬规矩直接沿用 Trae 的
 * PageCaptureTest（同一失败模式：多张截图实为同一页却 CI 全绿），
 * 但导航改走本分支 MainActivity 自身的真实路径（抽屉行点击），
 * 本分支没有 FlagshipActivity 深链（无 ui/flagship），也不用
 * PageCaptureTest 的设置页标记（settingsJumpAbout 等只在
 * 总装线 MainActivity 里有）。
 *
 * 六页：01 对话首页 / 02 抽屉 / 03 项目页 / 04 频道页 /
 * 05 控制页 / 06 全部功能弹窗。证据写内部存储 files/page-capture，
 * 由 android-emulator.yml 用 run-as tar 取回，verify_page_capture.py
 * 按传入的六个文件名复核。
 */
@RunWith(AndroidJUnit4::class)
class RemodelScreenshotTest {

    companion object {
        /** 引导遮罩独有的文本；任一可见 = 遮罩仍在，禁止截图。 */
        private val ONBOARDING_MARKERS = listOf(
            "开始使用 →",
            "稍后再看",
            "选择你的使用方向",
            "解锁手机执行能力",
            "准备完成",
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
     * GONE + 首页问候语在场。
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
                val skip = device.findObject(By.text("稍后再看"))
                    ?: device.findObject(By.text("跳过，先体验基础功能"))
                    ?: device.findObject(By.text("跳过"))
                if (skip != null) {
                    skip.click()
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

    private fun waitForText(text: String, timeoutMs: Long = 20_000): UiObject2 {
        val obj = device.wait(Until.findObject(By.text(text)), timeoutMs)
            ?: run {
                dumpUiTexts("missing-text")
                error("Page marker not found: text=$text (see missing-text-ui.txt)")
            }
        return obj
    }

    private fun waitForTextContains(part: String, timeoutMs: Long = 20_000): UiObject2 {
        val obj = device.wait(Until.findObject(By.textContains(part)), timeoutMs)
            ?: run {
                dumpUiTexts("missing-textcontains")
                error("Page marker not found: textContains=$part (see missing-textcontains-ui.txt)")
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
            System.err.println("DUPLICATE-SHA256: $fileName identical to $duplicateOf")
        }
    }

    /** 点顶栏 ☰ 打开抽屉，等“全部功能”行在场即算打开。 */
    private fun openDrawer() {
        waitForId("buttonMenu").click()
        device.waitForIdle()
        val drawer = device.wait(
            Until.findObject(By.res(context.packageName, "drawerContainer")),
            10_000,
        ) ?: error("Drawer container not found after tapping buttonMenu")
        val row = drawer.findObject(By.textStartsWith("全部功能"))
            ?: run {
                dumpUiTexts("drawer-missing")
                error("Drawer row '全部功能' not found (see drawer-missing-ui.txt)")
            }
        assertTrue("Drawer row '全部功能' is off-screen", isOnScreen(row))
    }

    /** 在已打开的抽屉里点一行（精确文本），等抽屉关闭。 */
    private fun tapDrawerRow(label: String) {
        val drawer = device.wait(
            Until.findObject(By.res(context.packageName, "drawerContainer")),
            10_000,
        ) ?: error("Drawer container not found while tapping '$label'")
        val row = drawer.findObject(By.text(label))
            ?: run {
                dumpUiTexts("drawer-missing-$label")
                error("Drawer row '$label' not found (see drawer-missing-$label-ui.txt)")
            }
        row.click()
        device.waitForIdle()
    }

    /** 在已打开的抽屉里点“全部功能”行（标签带注册表实时条目数，前缀匹配）。 */
    private fun tapDrawerFeatureCenter() {
        val drawer = device.wait(
            Until.findObject(By.res(context.packageName, "drawerContainer")),
            10_000,
        ) ?: error("Drawer container not found while tapping feature center")
        val row = drawer.findObject(By.textStartsWith("全部功能"))
            ?: run {
                dumpUiTexts("drawer-missing-feature")
                error("Drawer row '全部功能' not found (see drawer-missing-feature-ui.txt)")
            }
        row.click()
        device.waitForIdle()
    }

    @Test
    fun captureSixRemodelPages() {
        assertTrue(
            "Only the isolated debug package may run this capture",
            context.packageName == "wherevercode.agent.ci",
        )
        launchToChatHome()
        captureAllPages()
        val duplicates = pages.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val e = arr.getJSONObject(i)
                if (!e.isNull("duplicateOf")) "${e.getString("file")} == ${e.getString("duplicateOf")}" else null
            }
        }
        assertTrue(
            "Screenshot evidence contains identical images across pages (fake capture): $duplicates",
            duplicates.isEmpty(),
        )
    }

    private fun captureAllPages() {
        // 01 对话首页：新问候 + 四 pill + 来源胶囊。
        capture("01-chat-home", "01-chat-home.png", "text:今天想做什么？")

        // 02 抽屉：四父级 + 全部功能行。
        openDrawer()
        capture("02-sidebar", "02-sidebar.png", "textStartsWith:全部功能 (drawer row)")

        // 03 项目页：新建 pill + 搜索 + 筛选 chip + 卡片行。
        tapDrawerRow("项目")
        waitForId("buttonAddProject")
        capture("03-projects", "03-projects.png", "res:buttonAddProject")

        // 04 频道页：标题 + 分段 + chip 行 + 频道卡片。
        openDrawer()
        tapDrawerRow("频道")
        waitForText("频道与协作")
        capture("04-channels", "04-channels.png", "text:频道与协作")

        // 05 控制页：标题 + 真实本机快照卡片。
        openDrawer()
        tapDrawerRow("控制")
        waitForTextContains("数据全部来自本机真实记录")
        capture("05-console", "05-console.png", "textContains:数据全部来自本机真实记录")

        // 06 全部功能弹窗：标题 + 注册表全表（含待开发标注）。
        openDrawer()
        tapDrawerFeatureCenter()
        waitForText("全部功能")
        capture("06-feature-center", "06-feature-center.png", "text:全部功能 (dialog title)")
        device.findObject(By.text("关闭"))?.click()
        device.waitForIdle()
    }
}
