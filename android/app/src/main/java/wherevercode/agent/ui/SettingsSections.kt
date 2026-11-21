package wherevercode.agent.ui

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 设置页分节（照片批 F）：把原先一整页长表单按标题锚点切成块，
 * 供设置首页的菜单行按节打开。**只切可见性，不动任何一块的内容、
 * 文案与接线**——尤其大脑块（标题「大脑」到高级配置容器）原样保留，
 * 只是从「页首长表单的第一块」变成「设置首页里的一节」。
 *
 * 锚点 = 根 LinearLayout 直接子级里的分节标题 TextView。找不到任一
 * 锚点时整体降级为全显，绝不因匹配失败把功能藏没。
 */
object SettingsSections {

    /** 内容块（与布局里出现的先后一致）。 */
    enum class Block { BRAIN, REPOS, TOKEN, WORKSPACE, AUTONOMY, HAND, ABOUT, DEV }

    /** 设置首页一行菜单打开的目标节：一节可含一块或多块。 */
    enum class Section(val title: String, val blocks: Set<Block>) {
        BRAIN("大脑", setOf(Block.BRAIN)),
        REPOS("仓库授权", setOf(Block.REPOS)),
        TOKEN("平台连接与令牌签发", setOf(Block.TOKEN)),
        CONNECTIONS("连接管理", setOf(Block.REPOS, Block.TOKEN)),
        WORKSPACE("工作区", setOf(Block.WORKSPACE)),
        AUTONOMY("自主度", setOf(Block.AUTONOMY)),
        HAND("跨端同步", setOf(Block.HAND)),
        ABOUT("关于", setOf(Block.ABOUT)),
        DEV("开发者选项", setOf(Block.DEV)),
    }

    /** 主页容器在根布局里的 tag：分组逻辑永远跳过它（它由调用方自己管可见性）。 */
    const val HOME_TAG = "settings_home"

    private val ANCHORS: List<Pair<Block, String>> = listOf(
        Block.BRAIN to "大脑",
        Block.REPOS to "GitHub 授权",
        Block.TOKEN to "平台令牌",
        Block.WORKSPACE to "工作区",
        Block.AUTONOMY to "自主度（大脑总管）",
        Block.HAND to "Hand 同步",
        Block.ABOUT to "关于",
        Block.DEV to "开发者诊断",
    )

    /** 计算每块在根布局直接子级里的 [起, 止) 区间；锚点不全时返回 null（调用方应全显）。 */
    fun blockRanges(root: LinearLayout): Map<Block, IntRange>? {
        val starts = mutableMapOf<Block, Int>()
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child.tag == HOME_TAG || child !is TextView) continue
            val text = child.text?.toString() ?: continue
            for ((block, anchor) in ANCHORS) {
                if (block !in starts && text == anchor) starts[block] = i
            }
        }
        if (starts.size != ANCHORS.size) return null
        val ordered = ANCHORS.map { it.first }
        val ranges = mutableMapOf<Block, IntRange>()
        for ((index, block) in ordered.withIndex()) {
            val start = starts.getValue(block)
            val end = if (index + 1 < ordered.size) starts.getValue(ordered[index + 1]) else root.childCount
            ranges[block] = start until end
        }
        return ranges
    }

    /**
     * 按节显隐根布局的直接子级。section == null 表示全显（旧长表单形态，
     * 只在锚点降级时用）。页头 = 第一个非主页容器的子级（恒显，标题由
     * 调用方换）；跳转 chips 行 = HorizontalScrollView 子级（分节形态
     * 下恒隐——首页菜单取代它）。按类型/位置认，不按写死下标认：
     * 主页容器插在根布局首位时下标会整体移位。
     */
    fun apply(root: LinearLayout, section: Section?) {
        val ranges = blockRanges(root)
        val headerIndex = (0 until root.childCount)
            .firstOrNull { root.getChildAt(it).tag != HOME_TAG } ?: -1
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child.tag == HOME_TAG) continue
            child.visibility = when {
                i == headerIndex -> View.VISIBLE
                child is android.widget.HorizontalScrollView ->
                    if (section == null) View.VISIBLE else View.GONE
                section == null || ranges == null -> View.VISIBLE
                else -> {
                    val block = ranges.entries.firstOrNull { i in it.value }?.key
                    if (block != null && block in section.blocks) View.VISIBLE else View.GONE
                }
            }
        }
    }

    /** 首页形态：除页头（首页自带页头，旧页头也隐）与主页容器外全隐。 */
    fun applyHome(root: LinearLayout) {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            child.visibility = if (child.tag == HOME_TAG) View.VISIBLE else View.GONE
        }
    }
}
