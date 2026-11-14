package wherevercode.agent.ui

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import wherevercode.agent.ui.flagship.AppFlagshipCapabilities
import wherevercode.agent.ui.flagship.FeatureState
import wherevercode.agent.ui.flagship.FeatureStatus
import wherevercode.agent.ui.flagship.FlagshipActivity
import wherevercode.agent.ui.flagship.FlagshipRoutes

/**
 * 70 屏旗舰导航（双端组装第一轮）模拟器验收：
 * 壳可启动、抽屉可打开并含 70 路由搜索、待开发页如实显示「待开发」、
 * P0 路由（tasks）经「打开功能」深链回既有 MainActivity 真实屏。
 * 不建任务、不清数据、不伪造执行结果。
 *
 * 组装第二轮（assembly-r2）新增 [zzFlagshipAllRoutesReachable]：遍历全部
 * 70 条路由，逐条从抽屉打开详情页，断言标题与状态徽标（徽标期望值由
 * 与 App 同源的 [AppFlagshipCapabilities] 声明推导，不在测试里另抄
 * 一份状态表）。UNVERIFIED / PLANNED 路由的「打开功能」按钮是禁用的，
 * 遍历中绝不点它，只断言详情页呈现。方法名以 zz 开头并配合
 * NAME_ASCENDING 排序，保证既有深链测试先跑、遍历最后跑，
 * 遍历的长时间操作不污染既有测试。
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class FlagshipNavTest {

    private fun outputDir(context: Context): File =
        File(context.getExternalFilesDir(null), "issue8-native-evidence").apply { mkdirs() }

    private fun shot(device: UiDevice, output: File, name: String) {
        assertTrue(device.takeScreenshot(File(output, "$name.png")))
    }

    @Test
    fun flagshipShellNavigatesAndDeepLinks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val output = outputDir(context)

        context.startActivity(
            Intent(context, FlagshipActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        assertTrue(
            "flagship home headline",
            device.wait(Until.hasObject(By.text("今天想做些什么？")), 30_000),
        )
        shot(device, output, "flagship-home")

        device.findObject(By.text("☰"))?.click()
        assertTrue(
            "flagship drawer search",
            device.wait(Until.hasObject(By.textContains("查找 70 个页面")), 15_000),
        )
        shot(device, output, "flagship-drawer")

        // 待开发路由：全局搜索（search）无既有实现，必须如实显示「待开发」。
        device.findObject(By.text("全局搜索"))?.click()
        assertTrue(
            "planned route shows 待开发",
            device.wait(Until.hasObject(By.text("待开发")), 15_000),
        )
        shot(device, output, "flagship-planned")

        // 底部主导航切到 tasks（P0，已验证）页。
        device.findObject(By.text("任务"))?.click()
        assertTrue(
            "tasks route page",
            device.wait(Until.hasObject(By.text("任务中心")), 15_000),
        )
        assertTrue(
            "tasks route shows 已验证",
            device.wait(Until.hasObject(By.text("已验证")), 15_000),
        )
        shot(device, output, "flagship-tasks")

        // 「打开功能」深链回既有 MainActivity（首页问候语与旗舰壳不同字）。
        device.findObject(By.text("打开功能"))?.click()
        val reached = device.wait(Until.hasObject(By.text("今天想做什么？")), 30_000)
        if (!reached) {
            // 首次启动可能有系统通知权限弹窗挡路，关掉后重试等待。
            (device.findObject(By.text("Don’t allow")) ?: device.findObject(By.text("Don't allow")))?.click()
            assertTrue(
                "deep link reaches existing MainActivity home",
                device.wait(Until.hasObject(By.text("今天想做什么？")), 30_000),
            )
        }
        shot(device, output, "flagship-deeplink")

        File(output, "flagship-nav.txt").writeText(
            "routes visited: chat(home), search(planned->待开发), tasks(READY_VERIFIED)\n" +
                "deep link: tasks -> MainActivity home reached\n",
        )
    }

    /**
     * 详情页状态徽标的期望文案，与 FlagshipApp 详情页的渲染口径逐字一致：
     * READY 且证据非空才显示「已验证」；UNVERIFIED 显示「待验收」；
     * 其余（PLANNED 等）显示「待开发」。
     */
    private fun expectedBadge(status: FeatureStatus): String {
        val verified = status.state == FeatureState.READY_VERIFIED &&
            !status.evidence.isNullOrBlank()
        return if (verified) {
            "已验证"
        } else {
            when (status.state) {
                FeatureState.IMPLEMENTED_UNVERIFIED -> "待验收"
                FeatureState.DISCONNECTED -> "未连接"
                FeatureState.FAILED -> "运行失败"
                FeatureState.UNSUPPORTED -> "此平台暂不支持"
                else -> "待开发"
            }
        }
    }

    @Test
    fun zzFlagshipAllRoutesReachable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val output = outputDir(context)
        // 期望状态的唯一事实来源：与 FlagshipActivity 注入 App 的是同一
        // 份 AppFlagshipCapabilities 声明（同一静态映射），测试不另抄表。
        val provider = AppFlagshipCapabilities(context)
        val routes = FlagshipRoutes.all
        assertTrue("flagship route count is 70, was ${routes.size}", routes.size == 70)

        context.startActivity(
            Intent(context, FlagshipActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        assertTrue(
            "flagship home headline",
            device.wait(Until.hasObject(By.text("今天想做些什么？")), 30_000),
        )

        val drawerSearch = By.textContains("查找 70 个页面")
        val failures = mutableListOf<String>()
        val lines = mutableListOf<String>()
        for (route in routes) {
            val expected = expectedBadge(provider.status(route.id))
            // 归一化：上一轮若把抽屉留在打开状态，先关掉再重开，
            // 保证每轮都从详情页 + 关闭的抽屉开始。
            if (device.hasObject(drawerSearch)) {
                device.findObject(By.text("☰"))?.click()
                device.wait(Until.gone(drawerSearch), 5_000)
            }
            device.wait(Until.findObject(By.text("☰")), 15_000)?.click()
            if (!device.wait(Until.hasObject(drawerSearch), 15_000)) {
                failures += "${route.id}: drawer did not open"
                lines += "${route.id} ${route.title} expected=$expected result=FAIL(drawer)"
                continue
            }
            // 用抽屉自带搜索过滤定位条目，不滚动 LazyColumn：
            // scrollIntoView 两轮实测均不稳定（只向下搜、抽屉保留
            // 滚动位置，两轮各 54–56 条 FAIL(item)）。搜索框按
            // title/id 子串过滤（FlagshipApp 抽屉实现），填入 route.id
            // 后列表只剩少数匹配项，目标条目必在视口内。条目标题用
            // textContains 按完整标题匹配（70 个标题互不为子串）。
            val searchBox = device.wait(
                Until.findObject(By.clazz("android.widget.EditText")), 10_000,
            )
            if (searchBox == null) {
                failures += "${route.id}: drawer search box not found"
                lines += "${route.id} ${route.title} expected=$expected result=FAIL(search)"
                device.findObject(By.text("☰"))?.click()
                device.wait(Until.gone(drawerSearch), 5_000)
                continue
            }
            searchBox.click()
            searchBox.setText(route.id)
            val item = device.wait(
                Until.findObject(By.textContains(route.title)), 10_000,
            )
            if (item == null) {
                failures += "${route.id}: drawer item not found (${route.title})"
                lines += "${route.id} ${route.title} expected=$expected result=FAIL(item)"
                device.findObject(By.text("☰"))?.click()
                device.wait(Until.gone(drawerSearch), 5_000)
                continue
            }
            item.click()
            // 点中条目后抽屉应自动关闭；若点到的是顶栏同名标题等
            // 无导航效果的目标（抽屉仍开着），点 ☰ 关抽屉——此时
            // 详情页显示的正是当前路由，断言仍成立。
            if (!device.wait(Until.gone(drawerSearch), 10_000)) {
                device.findObject(By.text("☰"))?.click()
                device.wait(Until.gone(drawerSearch), 10_000)
            }
            // 只断言详情页呈现：顶栏/标题与路由标题一致、状态徽标与
            // AppFlagshipCapabilities 声明一致。不点「打开功能」按钮
            // （UNVERIFIED / PLANNED 时该按钮禁用，点了也无效果）。
            val titleOk = device.wait(Until.hasObject(By.text(route.title)), 15_000)
            val badgeOk = device.wait(Until.hasObject(By.text(expected)), 15_000)
            if (titleOk && badgeOk) {
                lines += "${route.id} ${route.title} expected=$expected result=PASS"
            } else {
                failures += "${route.id}: title=$titleOk badge($expected)=$badgeOk"
                lines += "${route.id} ${route.title} expected=$expected " +
                    "result=FAIL(title=$titleOk,badge=$badgeOk)"
            }
            // 截图只是佐证：单张失败不中断遍历（结果以汇总文件为准）。
            try {
                shot(device, output, "flagship-route-${route.id}")
            } catch (e: AssertionError) {
                lines += "${route.id} screenshot failed: ${e.message}"
            }
        }

        File(output, "flagship-all-routes.txt").writeText(
            "routes total: ${routes.size}\n" +
                "routes passed: ${routes.size - failures.size}\n" +
                lines.joinToString("\n") + "\n",
        )
        assertTrue(
            "70-route traversal failures (${failures.size}):\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }
}
