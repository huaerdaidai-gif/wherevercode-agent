package wherevercode.agent.ui

/**
 * V5 首页模型（Phase B C2，骨架）。
 *
 * 依据：V5-UI.md §二（默认聊天首页，严格）与 §七验收红线。
 * - 顶部：汉堡 / 标题 / 新建（本模型不管 View，只管首页状态）；
 * - 中部：初次问候可关闭，开始聊天后转为消息流，去掉欢迎装饰；
 * - 底部：固定输入框，左侧 + 号，右侧发送 / 麦克风；无五栏底部导航；
 * - 任务过程在对话内：紧凑状态卡，默认折叠。
 *
 * 纯 JVM 逻辑，不含 Android View，不接线进 MainActivity——接线是
 * C2-批 2 的事，且必须等 C1 首批落定后（C1 正在改 MainActivity，
 * 见实施计划 §C2 与 C1 的边界）。
 *
 * 本文件只写模型，不复用任何第三方代码。问候文案固定“你好”
 * （见 [greeting]，R4c 隐私修正：不读已存身份、不显示真人名字）。
 */
class HomeScreenController {

    /**
     * 输入框左侧 + 号打开的上下文操作（V5-UI.md §二）。
     * 底部抽屉“添加到对话”分两组（V5 按主流改，maintainer 2026-11-11）：
     * - 加东西（大格子：相机 / 照片 / 文件；行：网页搜索开关）——
     *   往对话里加内容，对照 ChatGPT 的菜单；
     * - 切换上下文（大格子：项目 / 选 Agent / 选设备；行：工具与连接）——
     *   V5 独有的上下文切换，保留不动。
     * 附件、终端、参数与权限在本分支没有接线的不进菜单——不摆死选项
     * （V5 输入框 + 菜单重做，Claude 参照，maintainer 2026-11-11）。
     * 网页搜索是真实能力（Bing 网页搜索，无 Key），不是死选项。
     */
    enum class PlusMenuItem(val isTile: Boolean) {
        CAMERA(true),
        PHOTO(true),
        FILE(true),
        WEB_SEARCH(false),
        PROJECT(true),
        SELECT_AGENT(true),
        SELECT_DEVICE(true),
        TOOL(false),
    }

    /** 首页状态快照（值对象，便于测试与接线层比对）。 */
    data class HomeState(
        /** 问候是否被用户手动关闭过。 */
        val greetingDismissed: Boolean = false,
        /** 上下文建议是否被用户手动关闭过。 */
        val suggestionsDismissed: Boolean = false,
        /** 是否已开始聊天（开始后首页转为消息流，问候不再出现）。 */
        val conversationStarted: Boolean = false,
        val inputText: String = "",
        val isSending: Boolean = false,
        val plusMenuOpen: Boolean = false,
        /** 对话内任务状态卡是否折叠；默认折叠（V5-UI.md §二）。 */
        val taskCardCollapsed: Boolean = true,
        /**
         * 当前工作模式 id（见 [WorkMode]）。默认对话协作；后续批次的
         * 切换 UI 只写合法 id（非法 id 由 [setWorkModeId] 拒绝）。
         */
        val workModeId: String = WorkMode.CHAT_COLLAB.id,
    )

    /** + 号菜单项 → 接线动作（纯映射，供 MainActivity 与单测共用）。 */
    enum class PlusMenuAction {
        OPEN_PROJECTS,
        OPEN_TOOLS,
        SELECT_AGENT,
        SELECT_DEVICE,
        TAKE_PHOTO,
        PICK_IMAGE,
        PICK_FILE,
        TOGGLE_WEB_SEARCH,
    }

    companion object {
        /**
         * V5 C2-3-R4c（隐私修正，maintainer 05:16/05:22 拍板）：问候文案固定
         * “你好”，不读已存身份、不显示真人名字；真人实名不出现在
         * 界面文案、代码注释与测试数据里（项目走向开源）。
         */
        fun greeting(): String = "你好"

        /** + 号菜单的操作（显示顺序 = 加东西组在上、切换上下文组在下）。 */
        val PLUS_MENU_ITEMS: List<PlusMenuItem> = listOf(
            PlusMenuItem.CAMERA,
            PlusMenuItem.PHOTO,
            PlusMenuItem.FILE,
            PlusMenuItem.WEB_SEARCH,
            PlusMenuItem.PROJECT,
            PlusMenuItem.SELECT_AGENT,
            PlusMenuItem.SELECT_DEVICE,
            PlusMenuItem.TOOL,
        )

        /** “添加到对话”组（往对话里加东西）：相机 / 照片 / 文件 / 网页搜索。 */
        val PLUS_MENU_ADD_ITEMS: List<PlusMenuItem> = listOf(
            PlusMenuItem.CAMERA,
            PlusMenuItem.PHOTO,
            PlusMenuItem.FILE,
            PlusMenuItem.WEB_SEARCH,
        )

        /** “切换上下文”组（V5 独有，保留）：项目 / 选 Agent / 选设备 / 工具与连接。 */
        val PLUS_MENU_CONTEXT_ITEMS: List<PlusMenuItem> = listOf(
            PlusMenuItem.PROJECT,
            PlusMenuItem.SELECT_AGENT,
            PlusMenuItem.SELECT_DEVICE,
            PlusMenuItem.TOOL,
        )

        /** + 号菜单项到接线动作的映射（V5 C2 批 2，接线层与单测共用）。 */
        fun actionFor(item: PlusMenuItem): PlusMenuAction = when (item) {
            PlusMenuItem.CAMERA -> PlusMenuAction.TAKE_PHOTO
            PlusMenuItem.PHOTO -> PlusMenuAction.PICK_IMAGE
            PlusMenuItem.FILE -> PlusMenuAction.PICK_FILE
            PlusMenuItem.WEB_SEARCH -> PlusMenuAction.TOGGLE_WEB_SEARCH
            PlusMenuItem.PROJECT -> PlusMenuAction.OPEN_PROJECTS
            PlusMenuItem.SELECT_AGENT -> PlusMenuAction.SELECT_AGENT
            PlusMenuItem.SELECT_DEVICE -> PlusMenuAction.SELECT_DEVICE
            PlusMenuItem.TOOL -> PlusMenuAction.OPEN_TOOLS
        }
    }

    private var state = HomeState()

    fun state(): HomeState = state

    /** 问候是否可见：未手动关闭、且尚未开始聊天。 */
    fun greetingVisible(): Boolean =
        !state.greetingDismissed && !state.conversationStarted

    /** 上下文建议是否可见：未手动关闭、且尚未开始聊天。 */
    fun suggestionsVisible(): Boolean =
        !state.suggestionsDismissed && !state.conversationStarted

    /** 输入框是否可发送：有非空文本、且不在发送中。 */
    fun canSend(allowEmptyText: Boolean = false): Boolean =
        (state.inputText.isNotBlank() || allowEmptyText) && !state.isSending

    /** 任务状态卡是否折叠（默认 true）。 */
    fun taskCardCollapsed(): Boolean = state.taskCardCollapsed

    fun plusMenuOpen(): Boolean = state.plusMenuOpen

    /** 用户手动关闭问候；开始聊天后问候本就不再显示，关闭是无操作。 */
    fun dismissGreeting() {
        state = state.copy(greetingDismissed = true)
    }

    /**
     * 恢复问候（仅在尚未开始聊天时有效；开始聊天后首页已是消息流，
     * 问候不再回来）。
     */
    fun restoreGreeting(): Boolean {
        if (state.conversationStarted) return false
        state = state.copy(greetingDismissed = false)
        return true
    }

    /** 用户手动关闭上下文建议（问候不受影响）。 */
    fun dismissSuggestions() {
        state = state.copy(suggestionsDismissed = true)
    }

    fun setInput(text: String) {
        state = state.copy(inputText = text)
    }

    fun clearInput() {
        state = state.copy(inputText = "")
    }

    /**
     * 开始发送：仅当 [canSend] 时进入发送中，返回 true；
     * 空输入或已在发送中时返回 false，不改状态。
     * [allowEmptyText]：只发附件（无文本）时也允许发送。
     */
    fun beginSend(allowEmptyText: Boolean = false): Boolean {
        if (!canSend(allowEmptyText)) return false
        state = state.copy(isSending = true, plusMenuOpen = false)
        return true
    }

    /**
     * 发送结束：清空输入、退出发送中，并标记会话已开始
     * （首页从此转为消息流，问候不再出现）。
     */
    fun finishSend() {
        state = state.copy(
            inputText = "",
            isSending = false,
            conversationStarted = true,
        )
    }

    /** 发送失败/取消：退出发送中，保留输入文本，会话不算开始。 */
    fun cancelSend() {
        state = state.copy(isSending = false)
    }

    fun togglePlusMenu() {
        state = state.copy(plusMenuOpen = !state.plusMenuOpen)
    }

    fun closePlusMenu() {
        state = state.copy(plusMenuOpen = false)
    }

    /** 进入已有会话：首页转为消息流，问候与建议不再出现，+ 号菜单关闭。 */
    fun onConversationStarted() {
        state = state.copy(
            conversationStarted = true,
            plusMenuOpen = false,
        )
    }

    /** 展开 / 折叠对话内任务状态卡。 */
    fun toggleTaskCard() {
        state = state.copy(taskCardCollapsed = !state.taskCardCollapsed)
    }

    /** 当前工作模式（非法存量回默认，绝不崩）。 */
    fun workMode(): WorkMode = WorkMode.fromId(state.workModeId) ?: WorkMode.DEFAULT

    /**
     * 设置工作模式 id（接线口子，供第二步的切换 UI 用）。
     * 非法 id 返回 false、不改状态；合法但未实现的模式照样存（切换 UI
     * 负责提示未实现），本模型不拦。
     */
    fun setWorkModeId(id: String): Boolean {
        if (WorkMode.fromId(id) == null) return false
        state = state.copy(workModeId = id)
        return true
    }

    /** 回到全新首页（新对话）：问候与建议恢复，输入清空，任务卡折回。 */
    fun reset() {
        // 工作模式是用户偏好，不是单轮状态：新对话不重置模式。
        state = HomeState(workModeId = state.workModeId)
    }
}
