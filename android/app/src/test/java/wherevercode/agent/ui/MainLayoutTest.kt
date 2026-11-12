package wherevercode.agent.ui

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import wherevercode.agent.R

/**
 * Inflates the real UI V2 shell for a K70-class portrait screen in both
 * themes. This catches missing resources/IDs and AppCompat-attribute problems
 * without booting the whole Activity (Room, service, network threads).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class MainLayoutTest {

    private fun themed(): ContextThemeWrapper = ContextThemeWrapper(
        RuntimeEnvironment.getApplication(),
        R.style.Theme_WhereverCode,
    )

    @Test
    fun `shell has v5 home chrome and inflates in light theme`() {
        val inflater = LayoutInflater.from(themed())
        val root = inflater.inflate(R.layout.activity_main, null)
        // V5 C2-3-R4 (v4.3-01)：顶栏是品牌行——汉堡 / 紫色 ∞ 标志 /
        // WhereverCode / V5 / 搜索 / 铃铛；抽屉宿主与遮罩在根 FrameLayout
        // 里叠加。会话标题、大脑 pill 与新建已删（见 activity_main.xml）。
        assertNotNull(root.findViewById<View>(R.id.buttonMenu))
        assertNotNull(root.findViewById<View>(R.id.topBrandLogo))
        assertNotNull(root.findViewById<View>(R.id.topBrandName))
        assertNotNull(root.findViewById<View>(R.id.topBrandVersion))
        assertEquals(
            "∞",
            root.findViewById<android.widget.TextView>(R.id.topBrandLogo).text.toString(),
        )
        assertEquals(
            "WhereverCode",
            root.findViewById<android.widget.TextView>(R.id.topBrandName).text.toString(),
        )
        assertEquals(
            "V5",
            root.findViewById<android.widget.TextView>(R.id.topBrandVersion).text.toString(),
        )
        assertNotNull(root.findViewById<View>(R.id.buttonSearch))
        assertNotNull(root.findViewById<View>(R.id.buttonBell))
        assertNotNull(root.findViewById<View>(R.id.drawerContainer))
        assertNotNull(root.findViewById<View>(R.id.drawerScrim))
        assertNotNull(root.findViewById<View>(R.id.screenContainer))
        // 已删 id 不存在（已删 id 无法再被 R.id 引用，故用 getIdentifier
        // 断言它不存在；buttonTheme 沿用批 3 的同款写法）。
        assertIdAbsent("buttonTheme")
        assertIdAbsent("topTitle")
        assertIdAbsent("topSubtitle")
        assertIdAbsent("brainChip")
        assertIdAbsent("buttonNewConversation")

        val screens = listOf(
            R.layout.screen_chat,
            R.layout.screen_projects,
            R.layout.screen_tasks,
            R.layout.screen_agents,
            R.layout.screen_settings,
        )
        screens.forEach { layout ->
            val view = inflater.inflate(layout, null)
            assertNotNull(view)
        }
        val chat = inflater.inflate(R.layout.screen_chat, null)
        assertNotNull(chat.findViewById<View>(R.id.buttonPlus))
    }

    @Test
    @Config(qualifiers = "night")
    fun `shell inflates in dark theme`() {
        val inflater = LayoutInflater.from(themed())
        val root = inflater.inflate(R.layout.activity_main, null)
        val context = themed()
        val darkBg = UiKit.color(context, R.color.bg)
        val darkSurface = UiKit.color(context, R.color.surface)
        assertTrue(darkBg != darkSurface)
        assertEquals(
            0,
            RuntimeEnvironment.getApplication().resources.getIdentifier(
                "buttonTheme",
                "id",
                RuntimeEnvironment.getApplication().packageName,
            ),
        )
    }

    /**
     * V5 C2-3-R1：输入行收紧（照 V5 原型图）——输入框最小高度与字号有界，
     * 控制行补齐改目标/控制台，输入行有 Agent 选择器。
     */
    @Test
    fun `chat home input row is compact with agent selector and full control row`() {
        val inflater = LayoutInflater.from(themed())
        val chat = inflater.inflate(R.layout.screen_chat, null)
        val density = RuntimeEnvironment.getApplication().resources.displayMetrics.density
        val input = chat.findViewById<android.widget.EditText>(R.id.inputPrompt)
        assertNotNull(input)
        assertTrue(
            "input minHeight should be compact (<=36dp)",
            input.minHeight <= (36 * density + 0.5f).toInt(),
        )
        assertEquals(14f, input.textSize / density, 0.01f)
        // V5 聊天清理 20261111：输入框 hint 改干净；设备/Agent 胶囊常驻
        // 输入框（默认“团队 ⌄”，文案随路由刷新），点按开选 Agent 对话框。
        assertEquals("输入消息…", input.hint.toString())
        assertEquals(
            "团队 ⌄",
            chat.findViewById<android.widget.TextView>(R.id.buttonAgentSelect).text.toString(),
        )
        assertNotNull(chat.findViewById<View>(R.id.buttonAgentSelect))
        assertNotNull(chat.findViewById<View>(R.id.buttonChatChangeGoal))
        assertNotNull(chat.findViewById<View>(R.id.buttonChatConsole))
        // V5 C2-3-R4：双层输入区——上层只有输入框，下层一行含 + /
        // 团队下拉 / 发送；输入框与按钮不在同一行。附件 / 文件夹 /
        // 参数·权限三个按钮已随“+”菜单重做删掉（无接线的死按钮），
        // id 应不存在（见 assertIdAbsent；已删 id 无法再被 R.id 引用）。
        val inputBox = chat.findViewById<View>(R.id.inputBox)
        val lowerRow = chat.findViewById<View>(R.id.inputLowerRow)
        assertNotNull(inputBox)
        assertNotNull(lowerRow)
        assertEquals(inputBox, input.parent)
        assertEquals(lowerRow, chat.findViewById<View>(R.id.buttonPlus).parent)
        assertEquals(lowerRow, chat.findViewById<View>(R.id.buttonAgentSelect).parent)
        assertEquals(lowerRow, chat.findViewById<View>(R.id.buttonSend).parent)
        assertIdAbsent("buttonAttach")
        assertIdAbsent("buttonFolder")
        assertIdAbsent("buttonParams")
        val pauseParams = chat.findViewById<View>(R.id.buttonChatPause).layoutParams
            as android.widget.LinearLayout.LayoutParams
        assertEquals(1f, pauseParams.weight, 0.01f)
        val stopParams = chat.findViewById<View>(R.id.buttonChatStop).layoutParams
            as android.widget.LinearLayout.LayoutParams
        assertEquals(1f, stopParams.weight, 0.01f)
    }

    /**
     * 阶段 3（U4/U6，批 3b）：审批等待条、聊天页内控制行、
     * 回到底部按钮、键盘发送键、任务页审批历史、设置分节字段齐全。
     * 只断言结构存在与初始隐藏，不启动 Activity（不碰 Room/服务）。
     * V5 Phase B C2（批 2，第 4 步）：底部导航徽标已随四 Tab 删除；
     * 运行中指示由聊天页内控制行、审批等待由两处等待条承接。
     */
    @Test
    fun `u4 and u6 chrome exists and starts hidden`() {
        val inflater = LayoutInflater.from(themed())
        val shell = inflater.inflate(R.layout.activity_main, null)
        assertNotNull(shell.findViewById<View>(R.id.drawerContainer))

        val chat = inflater.inflate(R.layout.screen_chat, null)
        assertNotNull(chat.findViewById<View>(R.id.approvalBanner))
        assertTrue(chat.findViewById<View>(R.id.approvalBanner).visibility == View.GONE)
        assertNotNull(chat.findViewById<View>(R.id.chatControls))
        assertTrue(chat.findViewById<View>(R.id.chatControls).visibility == View.GONE)
        assertNotNull(chat.findViewById<View>(R.id.buttonChatPause))
        assertNotNull(chat.findViewById<View>(R.id.buttonChatStop))
        assertNotNull(chat.findViewById<View>(R.id.buttonChatResume))
        assertNotNull(chat.findViewById<View>(R.id.buttonChatChangeGoal))
        assertNotNull(chat.findViewById<View>(R.id.buttonChatConsole))
        assertNotNull(chat.findViewById<View>(R.id.buttonScrollBottom))
        assertTrue(chat.findViewById<View>(R.id.buttonScrollBottom).visibility == View.GONE)
        val input = chat.findViewById<android.widget.EditText>(R.id.inputPrompt)
        assertNotNull(input)
        assertTrue(
            "键盘发送键应为 actionSend",
            input.imeOptions and android.view.inputmethod.EditorInfo.IME_MASK_ACTION ==
                android.view.inputmethod.EditorInfo.IME_ACTION_SEND,
        )

        val tasks = inflater.inflate(R.layout.screen_tasks, null)
        assertNotNull(tasks.findViewById<View>(R.id.taskApprovalBanner))
        assertTrue(tasks.findViewById<View>(R.id.taskApprovalBanner).visibility == View.GONE)
        assertNotNull(tasks.findViewById<View>(R.id.approvalHistoryList))

        val settings = inflater.inflate(R.layout.screen_settings, null)
        assertNotNull(settings.findViewById<View>(R.id.workspacePath))
        assertNotNull(settings.findViewById<View>(R.id.buttonAdvancedInfo))
        assertEquals(View.GONE, settings.findViewById<View>(R.id.workspaceAdvancedDetails).visibility)
        assertNotNull(settings.findViewById<View>(R.id.aboutVersion))
    }

    /**
     * V5 C2-3-R4：下层在 320dp 窄屏下不溢出——整屏按 320dp 精确宽度
     * 实测，下层实测宽 ≤ 320dp，固定 36dp 的控件实测宽均为 36dp。
     */
    @Test
    @Config(qualifiers = "w320dp-h800dp-xhdpi")
    fun `chat input lower row fits a 320dp narrow screen`() {
        val inflater = LayoutInflater.from(themed())
        val chat = inflater.inflate(R.layout.screen_chat, null)
        val density = RuntimeEnvironment.getApplication().resources.displayMetrics.density
        val widthPx = (320 * density + 0.5f).toInt()
        chat.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(
                widthPx,
                android.view.View.MeasureSpec.EXACTLY,
            ),
            android.view.View.MeasureSpec.makeMeasureSpec(
                0,
                android.view.View.MeasureSpec.UNSPECIFIED,
            ),
        )
        val lowerRow = chat.findViewById<View>(R.id.inputLowerRow)
        assertTrue("lower row must fit 320dp", lowerRow.measuredWidth <= widthPx)
        val fixedPx = (36 * density + 0.5f).toInt()
        listOf(
            R.id.buttonPlus,
            R.id.buttonSend,
        ).forEach { id ->
            assertEquals(fixedPx, chat.findViewById<View>(id).measuredWidth)
        }
        // 已删的三个死按钮 id 不存在（附件 / 文件夹 / 参数·权限）。
        assertIdAbsent("buttonAttach")
        assertIdAbsent("buttonFolder")
        assertIdAbsent("buttonParams")
        assertTrue(chat.findViewById<View>(R.id.inputPrompt).measuredWidth > 0)
    }

    /** 已删的资源 id 不存在（getIdentifier 返回 0）。 */
    private fun assertIdAbsent(name: String) {
        assertEquals(
            0,
            RuntimeEnvironment.getApplication().resources.getIdentifier(
                name,
                "id",
                RuntimeEnvironment.getApplication().packageName,
            ),
        )
    }
}
