package wherevercode.agent.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import wherevercode.agent.R
import wherevercode.agent.ui.HomeScreenController.PlusMenuAction

/**
 * 输入框 + 号菜单：底部抽屉（Claude 参照，maintainer 2026-11-11；V5 按主流改，
 * maintainer 2026-11-11：第一批补相机 / 照片 / 文件 + 网页搜索）。
 *
 * 分两组，界限清楚，不混在一起：
 * - “添加到对话”（往对话里加东西，对照 ChatGPT 的菜单）：相机 / 照片 /
 *   文件三个大格子 + 网页搜索开关行。相机拍照、相册选图、选文件都是
 *   真实接线；网页搜索开关开了之后大脑回答前先联网搜（Bing 网页搜索，
 *   无 Key，真实结果），关了不搜。
 * - “切换上下文”（V5 独有，保留不动）：项目 / 选 Agent / 选设备三个
 *   大格子 + 工具与连接行。
 *
 * 不放终端 / 参数与权限（旧菜单里的死选项，点按只给 toast）——死选项
 * 不进菜单。
 *
 * 纯 View 搭建，不用 Material（app 无 material 依赖）；颜色只用
 * 语义 token；圆角只在抽屉顶部。内容与 Dialog 壳分开：
 * [buildContent] 不建 Dialog，供 Robolectric 单测直接断言。
 */
object PlusMenuSheet {

    /** 大格子：文案 + 图标 + 接线动作。 */
    private data class Tile(
        val label: String,
        val iconRes: Int,
        val desc: String,
        val action: PlusMenuAction,
    )

    /** “添加到对话”组的大格子：相机 / 照片 / 文件。 */
    private val ADD_TILES = listOf(
        Tile("相机", R.drawable.ic_camera, "plus_tile_camera", PlusMenuAction.TAKE_PHOTO),
        Tile("照片", R.drawable.ic_photo, "plus_tile_photo", PlusMenuAction.PICK_IMAGE),
        Tile("文件", R.drawable.ic_file, "plus_tile_file", PlusMenuAction.PICK_FILE),
    )

    /** “切换上下文”组的大格子：项目 / 选 Agent / 选设备。 */
    private val CONTEXT_TILES = listOf(
        Tile("项目", R.drawable.ic_folder, "plus_tile_project", PlusMenuAction.OPEN_PROJECTS),
        Tile("选 Agent", R.drawable.ic_agent, "plus_tile_agent", PlusMenuAction.SELECT_AGENT),
        Tile("选设备", R.drawable.ic_device, "plus_tile_device", PlusMenuAction.SELECT_DEVICE),
    )

    /** 抽屉内容（不含 Dialog 壳）。点格子/行先 onDismiss 再 onAction；点 × 只 onDismiss。 */
    fun buildContent(
        context: Context,
        onDismiss: () -> Unit,
        onAction: (PlusMenuAction) -> Unit,
        /** 网页搜索开关当前状态（菜单每次打开时由调用方传入最新值）。 */
        webSearchEnabled: Boolean = false,
    ): View {
        val root = UiKit.vertical(context).apply {
            background = topRoundedBackground(context)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setPadding(
                UiKit.dp(context, 20),
                UiKit.dp(context, 8),
                UiKit.dp(context, 20),
                UiKit.dp(context, 20),
            )
        }
        // 顶部把手
        root.addView(
            View(context).apply {
                background = UiKit.rounded(context, R.color.surface_strong, 2)
                layoutParams = LinearLayout.LayoutParams(
                    UiKit.dp(context, 40),
                    UiKit.dp(context, 4),
                ).apply { gravity = Gravity.CENTER_HORIZONTAL }
            },
        )
        // 标题行：× | 添加到对话 | 占位（左右平衡）
        root.addView(
            UiKit.horizontal(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                UiKit.margin(this, topDp = 12)
                addView(
                    UiKit.text(context, "✕", 20f, R.color.text_primary).apply {
                        setPadding(
                            UiKit.dp(context, 8),
                            UiKit.dp(context, 4),
                            UiKit.dp(context, 8),
                            UiKit.dp(context, 4),
                        )
                        contentDescription = "plus_close"
                        setOnClickListener { onDismiss() }
                    },
                )
                addView(
                    UiKit.text(context, "添加到对话", 17f, R.color.text_primary, bold = true).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f,
                        )
                        gravity = Gravity.CENTER
                    },
                )
                addView(
                    View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            UiKit.dp(context, 36),
                            UiKit.dp(context, 1),
                        )
                    },
                )
            },
        )
        // 第一组：添加到对话——相机 / 照片 / 文件
        root.addView(sectionLabel(context, "添加到对话"))
        root.addView(
            UiKit.horizontal(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                UiKit.margin(this, topDp = 8)
                for (tile in ADD_TILES) {
                    addView(tileView(context, tile, onDismiss, onAction))
                }
            },
        )
        // 网页搜索开关行（圆角卡片里；点按切换开/关，不关抽屉外跳）
        root.addView(
            UiKit.vertical(context).apply {
                background = UiKit.rounded(context, R.color.surface_alt, 16)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                UiKit.margin(this, topDp = 12)
                addView(
                    toggleRowView(
                        context,
                        "网页搜索",
                        R.drawable.ic_search,
                        "plus_row_web_search",
                        enabled = webSearchEnabled,
                    ) {
                        onDismiss()
                        onAction(PlusMenuAction.TOGGLE_WEB_SEARCH)
                    },
                )
            },
        )
        // 第二组：切换上下文——项目 / 选 Agent / 选设备
        root.addView(sectionLabel(context, "切换上下文"))
        root.addView(
            UiKit.horizontal(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                UiKit.margin(this, topDp = 8)
                for (tile in CONTEXT_TILES) {
                    addView(tileView(context, tile, onDismiss, onAction))
                }
            },
        )
        // 工具与连接行（圆角卡片里）
        root.addView(
            UiKit.vertical(context).apply {
                background = UiKit.rounded(context, R.color.surface_alt, 16)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                UiKit.margin(this, topDp = 16)
                addView(
                    rowView(context, "工具与连接", R.drawable.ic_tools, "plus_row_tools") {
                        onDismiss()
                        onAction(PlusMenuAction.OPEN_TOOLS)
                    },
                )
            },
        )
        return root
    }

    /** 以底部抽屉形式弹出；返回 Dialog，调用方 show()。 */
    fun show(
        activity: Activity,
        onDismiss: () -> Unit,
        onAction: (PlusMenuAction) -> Unit,
        webSearchEnabled: Boolean = false,
    ): Dialog {
        val dialog = Dialog(activity)
        dialog.setContentView(
            buildContent(
                activity,
                onDismiss = { dialog.dismiss() },
                onAction = { action ->
                    dialog.dismiss()
                    onAction(action)
                },
                webSearchEnabled = webSearchEnabled,
            ),
        )
        dialog.setOnDismissListener { onDismiss() }
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.let { window ->
            window.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            window.setGravity(Gravity.BOTTOM)
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        return dialog
    }

    /** 分组小标题（副文色，左对齐）。 */
    private fun sectionLabel(context: Context, text: String): View =
        UiKit.text(context, text, 13f, R.color.text_secondary, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            UiKit.margin(this, topDp = 16)
        }

    private fun tileView(
        context: Context,
        tile: Tile,
        onDismiss: () -> Unit,
        onAction: (PlusMenuAction) -> Unit,
    ): LinearLayout {
        val tileRoot = UiKit.vertical(context).apply {
            background = UiKit.rounded(context, R.color.surface_alt, 16)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                0,
                UiKit.dp(context, 104),
                1f,
            ).apply {
                marginStart = UiKit.dp(context, 6)
                marginEnd = UiKit.dp(context, 6)
            }
            setPadding(0, UiKit.dp(context, 16), 0, UiKit.dp(context, 12))
            contentDescription = tile.desc
            isClickable = true
            setOnClickListener {
                onDismiss()
                onAction(tile.action)
            }
        }
        tileRoot.addView(UiKit.icon(context, tile.iconRes, 28, R.color.text_primary))
        tileRoot.addView(
            UiKit.text(context, tile.label, 14f, R.color.text_primary).apply {
                gravity = Gravity.CENTER
                UiKit.margin(this, topDp = 8)
            },
        )
        return tileRoot
    }

    private fun rowView(
        context: Context,
        label: String,
        iconRes: Int,
        desc: String,
        onClick: () -> Unit,
    ): LinearLayout {
        val row = UiKit.horizontal(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                UiKit.dp(context, 56),
            )
            setPadding(UiKit.dp(context, 16), 0, UiKit.dp(context, 12), 0)
            contentDescription = desc
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(UiKit.icon(context, iconRes, 24, R.color.text_secondary))
        row.addView(
            UiKit.text(context, label, 16f, R.color.text_primary).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                ).apply { marginStart = UiKit.dp(context, 12) }
            },
        )
        row.addView(UiKit.icon(context, R.drawable.ic_chevron, 20, R.color.text_tertiary))
        return row
    }

    /** 开关行：左侧图标 + 文案，右侧显示当前状态（已开启 / 已关闭），点按切换。 */
    private fun toggleRowView(
        context: Context,
        label: String,
        iconRes: Int,
        desc: String,
        enabled: Boolean,
        onClick: () -> Unit,
    ): LinearLayout {
        val row = UiKit.horizontal(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                UiKit.dp(context, 56),
            )
            setPadding(UiKit.dp(context, 16), 0, UiKit.dp(context, 12), 0)
            contentDescription = desc
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(UiKit.icon(context, iconRes, 24, R.color.text_secondary))
        row.addView(
            UiKit.text(context, label, 16f, R.color.text_primary).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                ).apply { marginStart = UiKit.dp(context, 12) }
            },
        )
        row.addView(
            UiKit.text(
                context,
                if (enabled) "已开启" else "已关闭",
                14f,
                if (enabled) R.color.accent else R.color.text_secondary,
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
            },
        )
        return row
    }

    /** 抽屉背景：白色，顶部两角圆 20dp，底部直角。 */
    private fun topRoundedBackground(context: Context): GradientDrawable {
        val radius = UiKit.dp(context, 20).toFloat()
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(UiKit.color(context, R.color.surface))
            cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        }
    }
}
