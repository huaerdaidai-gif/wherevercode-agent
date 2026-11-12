package wherevercode.agent.ui

import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import wherevercode.agent.R

/**
 * MCP / 插件屏（V5 MCP + 插件，第一版，2026-11-11）。
 *
 * 只渲染状态，不自己连服务器：列表、启用开关、连接状态与工具数
 * 全部来自 MainActivity 传进来的 [McpPluginScreenState]（注册表 +
 * MCP 管理器的真实快照）。连不上的服务器如实标“未连接/连接失败：
 * 原因”，绝不标在线；没连过的服务器不列工具数，只写“未连接”。
 *
 * 纯渲染 + 回调：开关与“连接”按钮只调回调，真正的注册表写盘与
 * MCP 建连在 MainActivity 的后台线程里做。增删改与导入的表单
 * 对话框也在此屏（只收字段、做格式校验），写注册表同样走回调。
 */
class McpPluginScreenController(
    private val context: Context,
    private val onToggleServer: (String, Boolean) -> Unit,
    private val onConnectServer: (String) -> Unit,
    private val onTogglePlugin: (String, Boolean) -> Unit,
    private val onRefresh: () -> Unit,
    private val onEditServer: (String) -> Unit = {},
    private val onDeleteServer: (String) -> Unit = {},
    private val onSaveServer: (McpServerFormData, String?) -> Unit = { _, _ -> },
    private val onImportServers: (String) -> Unit = {},
) {
    /**
     * MCP 服务器表单（添加/编辑共用；existingId 为空 = 添加，
     * 否则 = 编辑该 id 的行，名称不可改——改名请删了重加）。
     * headers 是“请求头名 → 值”（值可以是 `keystore:<引用名>`，
     * 绝不在此屏显示已存的明文 key）。
     */
    data class McpServerFormData(
        val name: String,
        /** 注册表 transport 值：`http`（Streamable HTTP）/`sse`/`stdio`。 */
        val transport: String,
        val url: String = "",
        val command: String = "",
        val headers: Map<String, String> = emptyMap(),
        /** 走 OAuth 2.1 登录（401 时开浏览器授权；只对远程有效）。 */
        val oauth: Boolean = false,
        val oauthClientId: String = "",
        val oauthScopes: String = "",
    )
    data class McpServerRow(
        val id: String,
        val displayName: String,
        val transportLabel: String,
        /** 已连接时的工具数；未连接时 null（不编数字）。 */
        val toolCount: Int?,
        /** 已连接 / 未连接 / 连接失败：原因 / 未启用。 */
        val statusText: String,
        val enabled: Boolean,
        val detail: String,
    )

    data class PluginRow(
        val id: String,
        val displayName: String,
        val version: String?,
        val toolNames: List<String>,
        val enabled: Boolean,
        val detail: String,
    )

    data class McpPluginScreenState(
        val servers: List<McpServerRow>,
        val plugins: List<PluginRow>,
        /** 七类扩展各自的登记数（与 Hand 侧同一套分类）。 */
        val kindCounts: Map<String, Int>,
        val note: String? = null,
    )

    val root: ScrollView = ScrollView(context).apply {
        visibility = View.GONE
        isFillViewport = true
    }

    private val content: LinearLayout = UiKit.vertical(context).apply {
        setPadding(dp(16), dp(8), dp(16), dp(20))
    }

    private val body: LinearLayout = UiKit.vertical(context)

    private var loading = false

    init {
        root.addView(content)
        content.addView(
            UiKit.text(context, "MCP / 插件", 20f, R.color.text_primary, bold = true).apply {
                setPadding(0, dp(4), 0, dp(2))
            },
        )
        content.addView(
            UiKit.text(
                context,
                "MCP 服务器与插件：大脑能调的扩展工具都在这里。只列真实连通的工具，" +
                    "没连上的如实标出，不伪装在线。",
                11f,
                R.color.text_tertiary,
            ).apply { setPadding(0, 0, 0, dp(8)) },
        )
        val refreshRow = UiKit.horizontal(context).apply { setPadding(0, 0, 0, dp(10)) }
        refreshRow.addView(
            UiKit.text(context, "刷新", 12f, R.color.accent, bold = true).apply {
                setBackgroundResource(R.drawable.bg_chip_accent)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener { onRefresh() }
            },
        )
        refreshRow.addView(
            UiKit.text(context, "启用后大脑下一轮就能调用；写类工具执行前仍会弹审批。", 10f, R.color.text_tertiary).apply {
                setPadding(dp(10), 0, 0, 0)
            },
        )
        content.addView(refreshRow)
        content.addView(body)
    }

    /** 大脑未建好时：只说暂不可读，不伪造空列表。 */
    fun renderLoading() {
        loading = true
        body.removeAllViews()
        body.addView(UiKit.text(context, "大脑加载中，扩展列表稍后可读…", 13f, R.color.text_secondary))
    }

    fun render(state: McpPluginScreenState) {
        loading = false
        body.removeAllViews()
        state.note?.let {
            body.addView(
                UiKit.text(context, it, 11f, R.color.text_tertiary).apply {
                    setPadding(0, 0, 0, dp(10))
                },
            )
        }
        body.addView(sectionCard("MCP 服务器") {
            val actions = UiKit.horizontal(context)
            actions.addView(actionButton("＋ 添加服务器") { showServerForm(null) })
            actions.addView(actionButton("导入配置") { showImportDialog() })
            it.addView(actions)
            it.addView(
                UiKit.text(
                    context,
                    "添加：填地址就能连，支持 Streamable HTTP（主流）、SSE（老版）与 stdio（本机命令）；" +
                        "导入：粘贴标准 mcpServers JSON（Claude Desktop / VS Code 同款格式）。",
                    11f,
                    R.color.text_tertiary,
                ).apply { setPadding(0, dp(6), 0, dp(4)) },
            )
            if (state.servers.isEmpty()) {
                it.addView(UiKit.text(context, "暂无 MCP 服务器。", 12f, R.color.text_secondary))
            } else {
                state.servers.forEach { server -> it.addView(serverRow(server)) }
            }
        })
        body.addView(sectionCard("插件") {
            if (state.plugins.isEmpty()) {
                it.addView(UiKit.text(context, "暂无插件。", 12f, R.color.text_secondary))
            } else {
                state.plugins.forEach { plugin -> it.addView(pluginRow(plugin)) }
            }
        })
        body.addView(sectionCard("扩展注册表（七类，与 Hand 侧同一套分类）") {
            if (state.kindCounts.isEmpty()) {
                it.addView(UiKit.text(context, "暂无登记。", 12f, R.color.text_secondary))
            } else {
                val summary = state.kindCounts.entries
                    .sortedBy { (kind, _) -> kind }
                    .joinToString(" · ") { (kind, count) -> "$kind $count 个" }
                it.addView(UiKit.text(context, summary, 12f, R.color.text_secondary))
                it.addView(
                    UiKit.text(
                        context,
                        "MCP 服务器与插件在两边是同一套身份（同名、同类）；其余五类先只登记，" +
                            "不在此屏提供操作。",
                        11f,
                        R.color.text_tertiary,
                    ).apply { setPadding(0, dp(6), 0, 0) },
                )
            }
        })
    }

    private fun sectionCard(title: String, fill: (LinearLayout) -> Unit): LinearLayout {
        val section = UiKit.vertical(context).apply {
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }
        section.addView(UiKit.text(context, title, 15f, R.color.text_primary, bold = true))
        fill(section)
        return section
    }

    private fun serverRow(server: McpServerRow): LinearLayout {
        val row = UiKit.vertical(context).apply {
            setPadding(0, dp(8), 0, dp(8))
        }
        val head = UiKit.horizontal(context)
        head.addView(
            UiKit.text(context, server.displayName, 13f, R.color.text_primary, bold = true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        head.addView(
            UiKit.text(context, server.statusText, 11f, R.color.text_secondary),
        )
        row.addView(head)
        // 工具数只在状态行（statusText）里出现一次（已连接时 MainActivity
        // 已把“已连接 · N 个工具”写进 statusText）；这里不再重复，避免同一行
        // 出现两次“2 个工具”。
        val meta = buildString {
            append(server.transportLabel)
            if (server.detail.isNotEmpty()) append("\n${server.detail}")
        }
        row.addView(
            UiKit.text(context, meta, 11f, R.color.text_tertiary).apply {
                setPadding(0, dp(2), 0, dp(6))
            },
        )
        val actions = UiKit.horizontal(context)
        val toggle = CheckBox(context).apply {
            text = if (server.enabled) "已启用" else "已停用"
            isChecked = server.enabled
            setOnCheckedChangeListener { _, checked -> onToggleServer(server.id, checked) }
        }
        actions.addView(toggle)
        if (server.enabled) {
            // 已连接时按钮叫“测连通”（重连一次验证），未连接时叫“连接”；
            // 两个都是真实走 initialize → tools/list 的连通测试。
            val connected = server.toolCount != null
            actions.addView(actionButton(if (connected) "测连通" else "连接") { onConnectServer(server.id) })
        }
        actions.addView(actionButton("编辑") { onEditServer(server.id) })
        actions.addView(actionButton("删除") { confirmDeleteServer(server.id, server.displayName) })
        row.addView(actions)
        return row
    }

    private fun pluginRow(plugin: PluginRow): LinearLayout {
        val row = UiKit.vertical(context).apply {
            setPadding(0, dp(8), 0, dp(8))
        }
        val head = UiKit.horizontal(context)
        val title = plugin.displayName + (plugin.version?.let { " v$it" } ?: "")
        head.addView(
            UiKit.text(context, title, 13f, R.color.text_primary, bold = true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        head.addView(
            UiKit.text(context, if (plugin.enabled) "已启用" else "已停用", 11f, R.color.text_secondary),
        )
        row.addView(head)
        val toolsText = if (plugin.toolNames.isEmpty()) "无工具" else "工具：" + plugin.toolNames.joinToString("、")
        val meta = toolsText + (if (plugin.detail.isNotEmpty()) "\n${plugin.detail}" else "")
        row.addView(
            UiKit.text(context, meta, 11f, R.color.text_tertiary).apply {
                setPadding(0, dp(2), 0, dp(6))
            },
        )
        val toggle = CheckBox(context).apply {
            text = if (plugin.enabled) "已启用" else "已停用"
            isChecked = plugin.enabled
            setOnCheckedChangeListener { _, checked -> onTogglePlugin(plugin.id, checked) }
        }
        row.addView(toggle)
        return row
    }

    private fun actionButton(label: String, onClick: () -> Unit): TextView {
        return UiKit.text(context, label, 11f, R.color.text_primary, bold = true).apply {
            setBackgroundResource(R.drawable.bg_chip)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(8) }
        }
    }

    private fun dp(value: Int): Int = UiKit.dp(context, value)

    // -- 添加/编辑/导入/删除（表单只收字段、做格式校验，写盘走回调） ----

    /** 添加（existing=null）或编辑一台 MCP 服务器的表单。 */
    fun showServerForm(existing: McpServerFormData?) {
        val isEdit = existing != null
        val form = UiKit.vertical(context).apply {
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        val errorText = UiKit.text(context, "", 12f, R.color.danger).apply {
            setPadding(0, dp(6), 0, 0)
        }
        val nameInput = EditText(context).apply {
            hint = "服务器名（唯一，如 filesystem）"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(existing?.name.orEmpty())
            isEnabled = !isEdit // 编辑时名称不可改（id 就是名称）。
        }
        val transportLabels = arrayOf("Streamable HTTP（主流）", "SSE（老版兼容）", "stdio（本机命令）")
        val transportValues = arrayOf("http", "sse", "stdio")
        val transportSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, transportLabels)
            setSelection(transportValues.indexOf(existing?.transport ?: "http").coerceAtLeast(0))
        }
        val addressInput = EditText(context).apply {
            hint = "https://…/mcp"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(existing?.url?.ifBlank { existing?.command }.orEmpty())
        }
        val headersInput = EditText(context).apply {
            hint = "请求头（可选，一行一个，如 Authorization: Bearer keystore:xxx）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            setText(
                existing?.headers?.entries?.joinToString("\n") { (k, v) -> "$k: $v" }.orEmpty(),
            )
        }
        val oauthCheck = CheckBox(context).apply {
            text = "走 OAuth 2.1 登录（401 时开浏览器授权）"
            isChecked = existing?.oauth == true
        }
        val oauthClientInput = EditText(context).apply {
            hint = "OAuth 客户端 id（可空，默认 wherevercode-agent）"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(existing?.oauthClientId.orEmpty())
        }
        val oauthScopesInput = EditText(context).apply {
            hint = "OAuth scope（可选，空格分隔）"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(existing?.oauthScopes.orEmpty())
        }
        val oauthBox = UiKit.vertical(context).apply {
            addView(oauthCheck)
            addView(oauthClientInput)
            addView(oauthScopesInput)
        }
        fun refreshAddressHint() {
            val selected = transportValues[transportSpinner.selectedItemPosition]
            addressInput.hint = when (selected) {
                "sse" -> "https://…/sse"
                "stdio" -> "启动命令，如 npx -y @modelcontextprotocol/server-filesystem /tmp"
                else -> "https://…/mcp"
            }
            oauthBox.visibility = if (selected == "stdio") View.GONE else View.VISIBLE
        }
        transportSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                refreshAddressHint()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        refreshAddressHint()
        form.addView(UiKit.text(context, "名称", 12f, R.color.text_secondary))
        form.addView(nameInput)
        form.addView(UiKit.text(context, "传输", 12f, R.color.text_secondary).apply { setPadding(0, dp(8), 0, 0) })
        form.addView(transportSpinner)
        form.addView(UiKit.text(context, "地址 / 命令", 12f, R.color.text_secondary).apply { setPadding(0, dp(8), 0, 0) })
        form.addView(addressInput)
        form.addView(UiKit.text(context, "请求头", 12f, R.color.text_secondary).apply { setPadding(0, dp(8), 0, 0) })
        form.addView(headersInput)
        form.addView(oauthBox)
        form.addView(errorText)
        val dialog = AlertDialog.Builder(context)
            .setTitle(if (isEdit) "编辑 MCP 服务器" else "添加 MCP 服务器")
            .setView(form)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = nameInput.text.toString().trim()
            val transport = transportValues[transportSpinner.selectedItemPosition]
            val address = addressInput.text.toString().trim()
            val headers = parseHeadersText(headersInput.text.toString())
            val fail: (String) -> Unit = { message ->
                errorText.text = message
            }
            when {
                name.isEmpty() -> fail("请填服务器名。")
                transport != "stdio" && address.isEmpty() -> fail("请填服务器地址。")
                transport == "stdio" && address.isEmpty() -> fail("请填启动命令。")
                headers == null -> fail("请求头格式不对：每行写成“头名: 值”。")
                else -> {
                    onSaveServer(
                        McpServerFormData(
                            name = name,
                            transport = transport,
                            url = if (transport == "stdio") "" else address,
                            command = if (transport == "stdio") address else "",
                            headers = headers,
                            oauth = oauthCheck.isChecked && transport != "stdio",
                            oauthClientId = oauthClientInput.text.toString().trim(),
                            oauthScopes = oauthScopesInput.text.toString().trim(),
                        ),
                        existing?.name,
                    )
                    dialog.dismiss()
                }
            }
        }
    }

    /** 粘贴标准 mcpServers JSON 导入（解析与写盘在 MainActivity）。 */
    fun showImportDialog() {
        val input = EditText(context).apply {
            hint = "{\"mcpServers\": {\"filesystem\": {\"command\": \"npx\", \"args\": [\"-y\", \"…\"]}}}"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
        }
        val box = UiKit.vertical(context).apply {
            setPadding(dp(20), dp(8), dp(20), dp(4))
            addView(
                UiKit.text(
                    context,
                    "粘贴标准 mcpServers JSON（Claude Desktop / VS Code 同款格式）。" +
                        "同名服务器会覆盖配置，启用开关保留。",
                    11f,
                    R.color.text_tertiary,
                ).apply { setPadding(0, 0, 0, dp(8)) },
            )
            addView(input)
        }
        AlertDialog.Builder(context)
            .setTitle("导入 MCP 服务器")
            .setView(box)
            .setPositiveButton("导入") { _, _ -> onImportServers(input.text.toString()) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDeleteServer(id: String, displayName: String) {
        AlertDialog.Builder(context)
            .setTitle("删除 MCP 服务器")
            .setMessage("删除 MCP 服务器“$displayName”？已连接时会先断开。内置服务器删不掉（只能停用）。")
            .setPositiveButton("删除") { _, _ -> onDeleteServer(id) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 请求头文本 → map（一行“头名: 值”，空行跳过；格式不对返回 null）。 */
    private fun parseHeadersText(text: String): Map<String, String>? {
        val out = LinkedHashMap<String, String>()
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val idx = line.indexOf(':')
            if (idx <= 0) return null
            val name = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            if (name.isEmpty() || value.isEmpty()) return null
            out[name] = value
        }
        return out
    }
}
