package wherevercode.agent.ui

import wherevercode.agent.R

import android.content.Context
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import wherevercode.agent.core.agent.AgentLoginState
import wherevercode.agent.core.agent.AgentRecord
import wherevercode.agent.core.security.PairingRecord
import wherevercode.agent.core.security.PairingState
import wherevercode.agent.core.transport.DeviceListFormat
import wherevercode.agent.core.transport.DiscoveredPeer
import wherevercode.agent.core.transport.RemoteDevice
import wherevercode.agent.core.transport.RemoteDeviceRow

/**
 * Agent 页渲染（阶段 3，A1）。
 *
 * 只列用户已添加的 Agent（[AgentRecord]），每卡有对话 / 指派 / 测试 /
 * 编辑 / 移除入口；设备区另见 [renderDevices]。
 *
 * 本页不扫描本机沙箱、不提供一键安装与诊断探针（2026-11-11 移除：
 * 超级 Agent 是调度，调设备上已有的 Agent；在沙箱里装 Agent 是已
 * 废弃的错误方向）。远端设备上的 Agent 需先在设备页添加设备。
 *
 * 它从不编造在线设备、登录态与角色：能力自报与实测分级分开标，
 * 未知项一律显示“未知”。
 */
class AgentScreenController(
    private val context: Context,
    private val list: LinearLayout,
    private val deviceInfo: LinearLayout,
) {
    /** 每 Agent 操作的回调（由 MainActivity 接线；为 null 时不画操作行）。 */
    var actions: AgentCardActions? = null

    interface AgentCardActions {
        fun onChat(agent: AgentRecord)
        fun onAssign(agent: AgentRecord)
        fun onTest(agent: AgentRecord)
        fun onEdit(agent: AgentRecord)
        fun onRemove(agent: AgentRecord)
        /**
         * 开关（maintainer 2026-11-12：每个 Agent 都要有开/关，支持热插拔）。
         * 关掉只是停用，不是删除；调用方保存后立即重画列表（不用重启 App）。
         */
        fun onToggle(agent: AgentRecord, enabled: Boolean)
    }

    private var deviceRendered = false

    fun showLoading() {
        list.removeAllViews()
        list.addView(
            UiKit.text(context, "正在加载 Agent…", 12f, R.color.text_secondary).apply {
                setBackgroundResource(R.drawable.bg_card)
                setPadding(dp(14), dp(14), dp(14), dp(14))
            },
        )
    }

    /** Keep device management independent from the Agent discovery list. */
    fun showDevicesLoading() {
        list.removeAllViews()
        renderDevice(listOf("正在检查已配对设备…", "跨设备执行能力以设备实际支持为准。"))
    }

    /** 清空 Agent 列表（调用方随后调 renderAddedAgents）。 */
    fun clearAgents() {
        list.removeAllViews()
    }

    fun renderAddedAgents(agents: List<AgentRecord>) {
        list.addView(sectionTitle("已添加的 Agent（${agents.size}）"))
        if (agents.isEmpty()) {
            list.addView(
                UiKit.text(context, "还没有添加 Agent。点上方“添加 Agent”，可加云端模型 API、订阅授权 Agent、本机 CLI Agent 或设备上的 Agent。", 11f, R.color.text_tertiary).apply {
                    setBackgroundResource(R.drawable.bg_system_note)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                },
            )
            return
        }
        agents.forEach { list.addView(addedCard(it)) }
    }

    /**
     * 设备区（阶段 3，U5/批 3c；A1 P2 的 UI 入口部分；V5 加设备修复）。
     *
     * 只显示真实发现/配对/添加的设备：发现来自 LanTransport.discover()
     * （`_wherevercode._tcp`），配对来自 PairingManager（Room 持久化，
     * 重启仍在），远程设备来自 RemoteDeviceStore（用户手动添加的
     * VPS 等，附配对状态）。未发现时诚实标“未发现”，绝不编造在线
     * 设备；已配对的远程设备可用于跨设备对话与任务。
     */
    fun renderDevices(
        discovered: List<DiscoveredPeer>,
        pairings: List<PairingRecord>,
        onPair: (DiscoveredPeer) -> Unit,
        onConfirm: (PairingRecord) -> Unit,
        onReject: (PairingRecord) -> Unit,
        onRevoke: (PairingRecord) -> Unit,
        remoteDevices: List<RemoteDeviceRow> = emptyList(),
        /**
         * 未配对远程设备卡片上的“输入配对码完成配对”（V5，2026-11-11）：
         * 断点续配——不用重新发现，直接对该设备 hello → 指纹 + 配对码。
         * 注意：本参数放在 onRediscover 之前——onAddRemote 必须是最后一个
         * 函数参数，否则尾随 lambda 调用方（DeviceUnavailableLabelTest）
         * 会把“添加远程设备”的 lambda 绑到本参数上，添加入口直接消失。
         */
        onResumePairing: ((RemoteDevice) -> Unit)? = null,
        /**
         * 已配对远程设备卡片上的“重新发现 Agent”（V5，2026-11-11）：
         * 点后重拉 hello，按自动发现的去重口径补建缺失的 Agent 记录。
         * 场景：设备是在自动发现上线前加的，或服务端后来又装了新 Agent。
         * 注意：本参数放在 onAddRemote 之前——onAddRemote 必须是最后一个
         * 函数参数，否则尾随 lambda 调用方（DeviceUnavailableLabelTest）
         * 会把“添加远程设备”的 lambda 绑到本参数上，添加入口直接消失。
         */
        onRediscover: ((RemoteDevice) -> Unit)? = null,
        /**
         * 远程设备卡片上的“编辑地址”（V5 公网直连，2026-11-11）：
         * 点后进详情/编辑对话框，可看可改内网地址与公网直连地址。
         * 放在 onAddRemote 之前——onAddRemote 必须是最后一个函数
         * 参数，否则尾随 lambda 调用方会把“添加远程设备”的 lambda
         * 绑到别的参数上，添加入口直接消失（见 onRediscover 注释）。
         */
        onEditRemote: ((RemoteDevice) -> Unit)? = null,
        /**
         * 远程设备卡片上的“更新 Hand”（V5 Hand 自更新，2026-11-12）：
         * 远端 Hand 版本落后或未知（旧版）时出按钮，点后走确认 →
         * stage → apply → 验版本 → 失败回滚。放在 onAddRemote 之前——
         * onAddRemote 必须是最后一个函数参数（见 onRediscover 注释）。
         */
        onUpdateHand: ((RemoteDevice) -> Unit)? = null,
        onAddRemote: (() -> Unit)? = null,
    ) {
        list.addView(
            sectionTitle(
                "设备（${discovered.size} 已发现 · ${pairings.size} 配对记录 · ${remoteDevices.size} 远程）",
            ),
        )
        if (discovered.isEmpty() && pairings.isEmpty() && remoteDevices.isEmpty()) {
            list.addView(
                UiKit.text(context, "未发现设备：局域网内没有 _wherevercode._tcp 服务。", 12f, R.color.text_secondary).apply {
                    setBackgroundResource(R.drawable.bg_card)
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                },
            )
        }
        discovered.forEach { peer -> list.addView(deviceCard(peer, onPair)) }
        pairings.forEach { record ->
            list.addView(pairingCard(record, onConfirm, onReject, onRevoke))
        }
        // V5 加设备修复（2026-11-11）：远程设备单列区段——此前设备页
        // 根本不传远程设备，加完也看不见。状态文案与选设备对话框一致。
        if (remoteDevices.isNotEmpty()) {
            list.addView(sectionTitle("远程设备（手动添加，${remoteDevices.size}）"))
            remoteDevices.forEach { row -> list.addView(remoteDeviceCard(row, onResumePairing, onRediscover, onEditRemote, onUpdateHand)) }
        }
        if (onAddRemote != null) {
            val row = UiKit.horizontal(context).apply { setPadding(0, dp(10), 0, 0) }
            row.addView(actionButton("＋ 添加远程设备…") { onAddRemote() })
            list.addView(row)
        }
        list.addView(
            UiKit.text(
                context,
                "配对记录已持久化，重启后仍在；已配对的远程设备可用于跨设备对话与任务。",
                10f,
                R.color.text_tertiary,
            ).apply {
                setPadding(0, dp(6), 0, 0)
            },
        )
    }

    /**
     * 远程设备卡片（V5，2026-11-11 配对集成后）：
     * 状态芯片（配对状态）+ id + 地址 + 最后在线 + 能力数 + 对方批准状态。
     * 最后在线只写真实 hello 成功的时间，从未在线就写“从未在线”；
     * 没有心跳探测，不写假“在线”。离线行只灰显保留，永不自动删除
     * （删除只走用户显式操作，本卡不删记录）。
     */
    private fun remoteDeviceCard(
        row: RemoteDeviceRow,
        onResumePairing: ((RemoteDevice) -> Unit)? = null,
        onRediscover: ((RemoteDevice) -> Unit)? = null,
        onEditRemote: ((RemoteDevice) -> Unit)? = null,
        onUpdateHand: ((RemoteDevice) -> Unit)? = null,
    ): LinearLayout {
        val card = UiKit.vertical(context).apply {
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
        val top = UiKit.horizontal(context)
        top.addView(
            UiKit.text(context, row.device.displayName, 14f, R.color.text_primary, bold = true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        top.addView(
            UiKit.text(
                context,
                "配对：${if (row.serverStatus == "PENDING") "等待对方批准" else if (row.paired) "已完成" else "未完成"}",
                10f,
                R.color.text_secondary,
            ).apply {
                setBackgroundResource(R.drawable.bg_chip)
                setPadding(dp(9), dp(4), dp(9), dp(4))
            },
        )
        card.addView(top)
        val diagnostics = UiKit.vertical(context).apply { visibility = View.GONE }
        diagnostics.addView(
            UiKit.text(context, "id：${row.device.deviceId}", 10f, R.color.text_tertiary, mono = true).apply {
                setPadding(0, dp(5), 0, 0)
            },
        )
        diagnostics.addView(
            // V5 公网直连（2026-11-11）：列表只标走线（公网优先/内网）
            // 与端口，不贴完整 IP；两个地址在“编辑地址”对话框里看改。
            UiKit.text(context, "远程 · ${row.routeLabel} · 端口 ${row.device.port}", 11f, R.color.text_secondary).apply {
                setPadding(0, dp(4), 0, 0)
            },
        )
        card.addView(
            UiKit.text(
                context,
                "当前在线：未检测 · 最近连接成功：${DeviceListFormat.formatLastSeen(row.lastSeenEpochSeconds, System.currentTimeMillis() / 1000)}",
                11f,
                R.color.text_secondary,
            ).apply { setPadding(0, dp(4), 0, 0) },
        )
        diagnostics.addView(
            UiKit.text(
                context,
                if (row.capabilityCount > 0) {
                    "能力：${row.capabilityCount} 项（${row.capabilityNames.joinToString("、")}）"
                } else {
                    "能力：未知"
                },
                11f,
                R.color.text_secondary,
            ).apply { setPadding(0, dp(4), 0, 0) },
        )
        // Hand 自更新（2026-11-12）：版本行常驻（App 更新后旧 Hand
        // 会拒收新东西，版本必须看得见）；落后或未知（旧版）时出
        // “更新 Hand”按钮，最新时不出（没得更新）。
        diagnostics.addView(
            UiKit.text(
                context,
                when {
                    row.handVersion.isBlank() -> "Hand 版本：未知（旧版）"
                    row.handUpdateAvailable -> "Hand 版本：${row.handVersion}（可更新）"
                    else -> "Hand 版本：${row.handVersion}（最新）"
                },
                11f,
                R.color.text_secondary,
            ).apply { setPadding(0, dp(4), 0, 0) },
        )
        val diagnosticToggle = actionButton("查看高级诊断") {
            diagnostics.visibility = if (diagnostics.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        card.addView(diagnosticToggle)
        card.addView(diagnostics)
        // 服务端批准状态（hello 回包的 client_status）：PENDING 说明手机
        // 侧已发起、对方还没批准——配对没走完，不是“已配对”。
        if (row.serverStatus == "PENDING") {
            card.addView(
                UiKit.text(context, "对方待批准：请点“输入配对码完成配对”，填入目标设备上的 6 位配对码。", 11f, R.color.warning).apply {
                    setPadding(0, dp(4), 0, 0)
                },
            )
        }
        if (!row.paired) {
            card.addView(
                UiKit.text(context, "该设备未接入：配对未完成时不能对话、不能接任务。", 10f, R.color.text_tertiary).apply {
                    setPadding(0, dp(4), 0, 0)
                },
            )
            if (onResumePairing != null || onEditRemote != null) {
                val actions = UiKit.horizontal(context).apply { setPadding(0, dp(10), 0, 0) }
                if (onResumePairing != null) {
                    actions.addView(actionButton("输入配对码完成配对") { onResumePairing(row.device) })
                }
                if (onEditRemote != null) {
                    actions.addView(actionButton("编辑地址") { onEditRemote(row.device) })
                }
                card.addView(actions)
            }
        } else {
            // 编辑地址一直可点（改地址不改配对）；重新发现只在已配对时出。
            // V5 自动发现（2026-11-11）：已配对的设备可随时重拉 hello，
            // 补建服务端新增的 Agent 记录；已有的按去重口径保留不动。
            // Hand 自更新（2026-11-12）：“更新 Hand”只在已配对时出，
            // 落后或未知（旧版）时才出，最新时不出。
            if (onEditRemote != null || onRediscover != null || (onUpdateHand != null && (row.handUpdateAvailable || row.handVersion.isBlank()))) {
                val actions = UiKit.horizontal(context).apply { setPadding(0, dp(10), 0, 0) }
                if (onEditRemote != null) {
                    actions.addView(actionButton("编辑地址") { onEditRemote(row.device) })
                }
                if (onRediscover != null) {
                    actions.addView(actionButton("重新发现 Agent") { onRediscover(row.device) })
                }
                if (onUpdateHand != null && (row.handUpdateAvailable || row.handVersion.isBlank())) {
                    actions.addView(actionButton("更新 Hand") { onUpdateHand(row.device) })
                }
                card.addView(actions)
            }
        }
        return card
    }

    private fun deviceCard(peer: DiscoveredPeer, onPair: (DiscoveredPeer) -> Unit): LinearLayout {
        val card = UiKit.vertical(context).apply {
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
        card.addView(UiKit.text(context, peer.displayName, 14f, R.color.text_primary, bold = true))
        card.addView(
            UiKit.text(context, "id：${peer.deviceId}", 10f, R.color.text_tertiary, mono = true).apply {
                setPadding(0, dp(5), 0, 0)
            },
        )
        card.addView(
            UiKit.text(context, "${peer.host}:${peer.port}", 11f, R.color.text_secondary).apply {
                setPadding(0, dp(4), 0, 0)
            },
        )
        // V5 C2（批 2，第 5 步）：已发现未配对的设备一律标“未配对（不可用）”，
        // 绝不显示假在线态；跨设备对话尚未实现，见 MainActivity 的选设备对话框。
        card.addView(
            UiKit.text(context, "未配对（不可用）", 11f, R.color.text_secondary).apply {
                setPadding(0, dp(4), 0, 0)
            },
        )
        card.addView(
            UiKit.text(
                context,
                if (peer.capabilities.isNotEmpty()) {
                    "能力：${peer.capabilities.size} 项（${peer.capabilities.joinToString("、")}）"
                } else {
                    "能力：未知"
                },
                11f,
                R.color.text_secondary,
            ).apply { setPadding(0, dp(4), 0, 0) },
        )
        card.addView(
            UiKit.text(
                context,
                "指纹：${peer.fingerprintSha256.take(16)}…（配对时请与对方设备显示的指纹核对）",
                11f,
                R.color.text_secondary,
                mono = true,
            ).apply {
                setPadding(0, dp(4), 0, 0)
            },
        )
        val row = UiKit.horizontal(context).apply { setPadding(0, dp(10), 0, 0) }
        row.addView(actionButton("发起配对") { onPair(peer) })
        card.addView(row)
        return card
    }

    private fun pairingCard(
        record: PairingRecord,
        onConfirm: (PairingRecord) -> Unit,
        onReject: (PairingRecord) -> Unit,
        onRevoke: (PairingRecord) -> Unit,
    ): LinearLayout {
        val card = UiKit.vertical(context).apply {
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
        val top = UiKit.horizontal(context)
        top.addView(
            UiKit.text(context, record.displayName, 14f, R.color.text_primary, bold = true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        top.addView(
            UiKit.text(context, pairingStateLabel(record.state), 10f, R.color.text_secondary).apply {
                setBackgroundResource(R.drawable.bg_chip)
                setPadding(dp(9), dp(4), dp(9), dp(4))
            },
        )
        card.addView(top)
        card.addView(
            UiKit.text(context, "id：${record.deviceId}", 10f, R.color.text_tertiary, mono = true).apply {
                setPadding(0, dp(5), 0, 0)
            },
        )
        card.addView(
            UiKit.text(
                context,
                "指纹：${record.fingerprintSha256.take(16)}…",
                11f,
                R.color.text_secondary,
                mono = true,
            ).apply {
                setPadding(0, dp(4), 0, 0)
            },
        )
        record.reason?.takeIf { it.isNotBlank() }?.let { reason ->
            card.addView(
                UiKit.text(context, "原因：$reason", 11f, R.color.text_secondary).apply {
                    setPadding(0, dp(4), 0, 0)
                },
            )
        }
        val row = UiKit.horizontal(context).apply { setPadding(0, dp(10), 0, 0) }
        when (record.state) {
            PairingState.PENDING -> {
                // V5 配对集成（2026-11-11）：待确认不再是本机一点就完——
                // 须先在局域网内找到该设备，核对指纹后填入 6 位配对码，
                // 对方批准后才算配对完成。
                card.addView(
                    UiKit.text(context, "点“输入配对码完成配对”：先在局域网内找到该设备，核对指纹后填入 6 位配对码。", 10f, R.color.text_tertiary).apply {
                        setPadding(0, dp(6), 0, 0)
                    },
                )
                row.addView(actionButton("输入配对码完成配对") { onConfirm(record) })
                row.addView(actionButton("拒绝") { onReject(record) })
            }
            PairingState.CONFIRMED -> {
                row.addView(actionButton("撤销配对") { onRevoke(record) })
            }
            else -> {
                card.addView(
                    UiKit.text(context, "需重新发现该设备后再次发起配对。", 10f, R.color.text_tertiary).apply {
                        setPadding(0, dp(6), 0, 0)
                    },
                )
            }
        }
        if (row.childCount > 0) card.addView(row)
        return card
    }

    // V5 C2（批 2，第 5 步）：非“已配对”态一律缀“（不可用）”，与选设备
    // 对话框的标记一致；绝不显示假在线态。
    private fun pairingStateLabel(state: PairingState): String = when (state) {
        PairingState.PENDING -> "待确认（不可用）"
        PairingState.CONFIRMED -> "已配对"
        PairingState.REJECTED -> "已拒绝（不可用）"
        PairingState.REVOKED -> "已撤销（不可用）"
    }

    private fun sectionTitle(text: String): TextView =
        UiKit.text(context, text, 13f, R.color.text_primary, bold = true).apply {
            setPadding(0, dp(10), 0, dp(6))
        }

    fun renderDevice(lines: List<String>) {
        deviceInfo.removeAllViews()
        lines.forEachIndexed { index, line ->
            deviceInfo.addView(
                UiKit.text(context, line, 11f, R.color.text_secondary).apply {
                    setLineSpacing(dp(2).toFloat(), 1f)
                    if (index > 0) setPadding(0, dp(5), 0, 0)
                },
            )
        }
        deviceRendered = true
    }

    fun isDeviceRendered(): Boolean = deviceRendered

    private fun addedCard(record: AgentRecord): LinearLayout {
        val card = UiKit.vertical(context).apply {
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
        val top = UiKit.horizontal(context)
        top.addView(UiKit.icon(context, R.drawable.ic_agent, 20, R.color.text_secondary))
        val name = UiKit.text(context, record.displayName, 14f, R.color.text_primary, bold = true)
        name.setPadding(dp(9), 0, dp(6), 0)
        top.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(
            UiKit.text(context, kindLabel(record), 10f, R.color.text_secondary).apply {
                setBackgroundResource(R.drawable.bg_chip)
                setPadding(dp(9), dp(4), dp(9), dp(4))
            },
        )
        val login = loginLabel(record.loginState)
        top.addView(
            UiKit.text(context, login.first, 10f, login.second).apply {
                setBackgroundResource(
                    if (record.loginState == AgentLoginState.LOGGED_IN) R.drawable.bg_chip_success
                    else R.drawable.bg_chip,
                )
                setPadding(dp(9), dp(4), dp(9), dp(4))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(6) }
            },
        )
        // Agent 开关（2026-11-12）：停用的 Agent 卡片上标“已停用”，
        // 列表里仍看得到（不是删除），开关在下面操作行里。
        if (!record.enabled) {
            top.addView(
                UiKit.text(context, "已停用", 10f, R.color.danger).apply {
                    setBackgroundResource(R.drawable.bg_chip)
                    setPadding(dp(9), dp(4), dp(9), dp(4))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { marginStart = dp(6) }
                },
            )
        }
        card.addView(top)

        card.addView(
            UiKit.text(
                context,
                "可用能力：${record.capabilities.ifEmpty { null }?.joinToString("、") ?: "未知"}" +
                    " · 费用：${record.quotaRule ?: "未知"}",
                11f,
                R.color.text_secondary,
            ).apply { setPadding(0, dp(6), 0, 0) },
        )
        record.knownLimits?.takeIf { it.isNotBlank() }?.let { limits ->
            card.addView(
                UiKit.text(context, "已知限制：$limits", 11f, R.color.warning).apply {
                    setPadding(0, dp(4), 0, 0)
                },
            )
        }
        card.addView(
            UiKit.text(
                context,
                record.lastTestSummary?.takeIf { it.isNotBlank() }?.let { "最近测试：$it" }
                    ?: "最近测试：未测试",
                11f,
                R.color.text_secondary,
            ).apply { setPadding(0, dp(4), 0, 0) },
        )
        record.addHint?.takeIf { it.isNotBlank() }?.let { hint ->
            card.addView(
                UiKit.text(context, hint, 11f, R.color.text_tertiary).apply {
                    setPadding(0, dp(4), 0, 0)
                },
            )
        }
        val ops = actions
        if (ops != null) {
            // Agent 开关（2026-11-12）：每行一个可见开关，不藏在长按/
            // 手势后面；一点立即生效（调用方保存后重画列表，不用重启）。
            // 先设 checked 再挂监听——否则设初始态也会触发一次回调。
            val toggleRow = UiKit.horizontal(context).apply { setPadding(0, dp(10), 0, 0) }
            val toggle = Switch(context).apply {
                text = if (record.enabled) "启用中" else "已停用"
                isChecked = record.enabled
                setOnCheckedChangeListener { _, checked -> ops.onToggle(record, checked) }
            }
            toggleRow.addView(toggle)
            card.addView(toggleRow)
            val row = UiKit.horizontal(context).apply { setPadding(0, dp(10), 0, 0) }
            // 停用的 Agent 不给对话/指派/测试入口（点了也是拒绝，不如
            // 不出）；编辑与移除保留——修好配置再打开、或彻底删除都行。
            if (record.enabled) {
                row.addView(actionButton("对话") { ops.onChat(record) })
                if (record.dispatchable) {
                    row.addView(actionButton("指派") { ops.onAssign(record) })
                }
                row.addView(actionButton("测试") { ops.onTest(record) })
            }
            row.addView(actionButton("编辑") { ops.onEdit(record) })
            row.addView(actionButton("移除") { ops.onRemove(record) })
            card.addView(row)
            if (!record.enabled) {
                card.addView(
                    UiKit.text(context, "已停用：不在选 Agent 里出现，不进频道，不接派活；点上方开关可重新启用。", 10f, R.color.text_tertiary).apply {
                        setPadding(0, dp(6), 0, 0)
                    },
                )
            }
            if (!record.dispatchable) {
                card.addView(
                    UiKit.text(context, "该 Agent 不可派单，只对话、不接任务。", 10f, R.color.text_tertiary).apply {
                        setPadding(0, dp(6), 0, 0)
                    },
                )
            }
        }
        return card
    }

    private fun kindLabel(record: AgentRecord): String = when (record.kind) {
        wherevercode.agent.core.agent.AgentKind.CLOUD -> "云端"
        wherevercode.agent.core.agent.AgentKind.DEVICE -> "设备"
        wherevercode.agent.core.agent.AgentKind.LOCAL_MODEL -> "本机模型"
    }

    private fun loginLabel(state: AgentLoginState): Pair<String, Int> = when (state) {
        AgentLoginState.LOGGED_IN -> "认证通过" to R.color.success
        AgentLoginState.NOT_LOGGED_IN -> "认证失败" to R.color.danger
        AgentLoginState.UNKNOWN -> "未验证" to R.color.warning
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
}
