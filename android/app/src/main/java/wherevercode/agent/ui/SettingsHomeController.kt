package wherevercode.agent.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import wherevercode.agent.R

/**
 * 设置首页（照片批 F，与 iOS 设置页 AccountSettingsView 同构）：
 * 用户卡 + 分节菜单行，点一行进对应分节（旧长表单的对应块，
 * 内容一字不动，见 [SettingsSections]）或跳到独立页。
 * 没有真实功能的行点开给诚实「待开发」提示（与 iOS 同口径：
 * 如 Webhook 在 iOS 也是待开发提示）。
 *
 * 本控制器只管首页这一屏的呈现与点击路由，不读任何 store；
 * 平台状态文案由调用方以 [platformStatus] 现取传入。
 */
class SettingsHomeController(
    private val context: Context,
    private val onBack: () -> Unit,
    private val onOpenSection: (SettingsSections.Section) -> Unit,
    private val onOpenScreen: (String) -> Unit,
    private val onCycleTheme: () -> Unit,
    private val onTodo: (String) -> Unit,
    private val platformStatus: () -> String,
    private val platformAddress: () -> String = { "" },
) {
    val root: LinearLayout = UiKit.vertical(context).apply {
        tag = SettingsSections.HOME_TAG
        setPadding(dp(16), dp(8), dp(16), dp(20))
        visibility = View.GONE
    }

    private val statusText: TextView =
        UiKit.text(context, "", 12f, R.color.text_secondary)

    private val addressText = UiKit.text(context, "", 14f, R.color.text_primary)
    private val addressState = UiKit.text(context, "", 14f, R.color.text_primary)

    private sealed interface Dest {
        data class Section(val section: SettingsSections.Section) : Dest
        data class Screen(val entryId: String) : Dest
        data class Todo(val title: String) : Dest
        object Theme : Dest
    }

    private data class Row(val title: String, val dest: Dest)

    private data class SectionGroup(val title: String, val rows: List<Row>)

    private val groups: List<SectionGroup> = listOf(
        SectionGroup(
            "平台连接",
            emptyList(),
        ),
        SectionGroup(
            "账户",
            listOf(
                Row("个人资料", Dest.Todo("个人资料")),
                Row("连接管理", Dest.Section(SettingsSections.Section.CONNECTIONS)),
                Row("隐私与安全", Dest.Todo("隐私与安全")),
                Row("跨端同步", Dest.Section(SettingsSections.Section.HAND)),
                Row("备份与恢复", Dest.Section(SettingsSections.Section.ABOUT)),
                Row("用量与预算", Dest.Todo("用量与预算")),
            ),
        ),
        SectionGroup(
            "应用",
            listOf(
                Row("模型配置", Dest.Screen(DrawerController.ID_MODELS)),
                Row("平台连接与令牌签发", Dest.Section(SettingsSections.Section.TOKEN)),
                Row("仓库授权", Dest.Section(SettingsSections.Section.REPOS)),
                Row("插件与工具", Dest.Screen(DrawerController.ID_MCP_PLUGINS)),
                Row("Webhook 唤醒与自动触发", Dest.Todo("Webhook 唤醒与自动触发")),
                Row("语音与多模态", Dest.Todo("语音与多模态")),
                Row("消息与通知", Dest.Todo("消息与通知")),
                Row("长期记忆", Dest.Todo("长期记忆")),
                Row("项目上下文规则", Dest.Todo("项目上下文规则")),
                Row("Agent 偏好与技能", Dest.Todo("Agent 偏好与技能")),
                Row("自主度", Dest.Section(SettingsSections.Section.AUTONOMY)),
                Row("工作区", Dest.Section(SettingsSections.Section.WORKSPACE)),
                Row("切换深色模式", Dest.Theme),
                Row("首次使用指南", Dest.Screen(DrawerController.ID_HELP)),
                Row("开发者选项", Dest.Section(SettingsSections.Section.DEV)),
                Row("关于", Dest.Section(SettingsSections.Section.ABOUT)),
                // 大脑是已砍下放一边的分节：入口留在应用节最末（角落），
                // 分节内容与文案一字不动。
                Row("大脑", Dest.Section(SettingsSections.Section.BRAIN)),
            ),
        ),
    )

    init {
        val header = UiKit.horizontal(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(UiKit.text(context, "设置", 20f, R.color.text_primary, bold = true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(UiKit.text(context, "完成", 16f, R.color.text_primary).apply {
            setPadding(dp(12), dp(8), dp(4), dp(8))
            setOnClickListener { onBack() }
        })
        root.addView(header)

        val card = UiKit.vertical(context).apply {
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12); bottomMargin = dp(4) }
        }
        card.addView(UiKit.text(context, "WhereverCode 用户", 14f, R.color.text_primary, bold = true))
        card.addView(UiKit.text(context, "本地优先 · 无需强制登录即可使用本地对话与项目工作区", 12f, R.color.text_secondary))
        root.addView(card)

        groups.forEach { group ->
            root.addView(
                UiKit.text(context, group.title, 12f, R.color.text_secondary, bold = true).apply {
                    setPadding(0, dp(14), 0, dp(6))
                },
            )
            val cardRows = UiKit.vertical(context).apply {
                setBackgroundResource(R.drawable.bg_card)
                setPadding(dp(14), dp(4), dp(14), dp(4))
            }
            if (group.title == "平台连接") {
                cardRows.addView(addressText)
                cardRows.addView(addressState)
                cardRows.addView(statusText)
            }
            group.rows.forEach { row -> cardRows.addView(rowView(row)) }
            root.addView(cardRows)
        }
        // 注意：这里不调 render()。platformStatus() 会读 Keystore 凭证，
        // 而 MainActivity 启动期（inflateScreens）Robolectric 下 AndroidKeyStore
        // 尚不可用——构造时现取会让 10 个与设置无关的启动测试在 create() 里
        // 同错 KeyStoreException。状态只在进设置首页时由 showSettingsHome()
        // 经 render() 现取，首页根布局初始即 GONE，用户看不到空文案。
    }

    /** 每次进首页时刷新平台状态文案（现取，不缓存）。 */
    fun render() {
        val address = platformAddress()
        addressText.text = "平台地址　" + address.ifBlank { "未配置" }
        addressState.text = "地址状态　" + if (address.isBlank()) "未配置" else "已配置"
        statusText.text = platformStatus()
    }

    private fun rowView(row: Row): LinearLayout {
        val line = UiKit.horizontal(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(11), 0, dp(11))
            setOnClickListener {
                when (val dest = row.dest) {
                    is Dest.Section -> onOpenSection(dest.section)
                    is Dest.Screen -> onOpenScreen(dest.entryId)
                    is Dest.Todo -> onTodo(dest.title)
                    Dest.Theme -> onCycleTheme()
                }
            }
        }
        line.addView(
            UiKit.text(context, row.title, 14f, R.color.text_primary),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        line.addView(UiKit.text(context, "›", 16f, R.color.text_tertiary))
        return line
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
