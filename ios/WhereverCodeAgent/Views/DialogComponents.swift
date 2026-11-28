// WhereverCode Agent — iOS
//
// 对话组件收敛（2026-11-20）：单 Agent 对话页与频道页共用的对话
// 核心组件——消息列表脚手架 DialogMessageListView 与输入区
// DialogComposerView。两页此前各写一套（LocalChatHomeView 的
// ScrollView 消息流 + ChatComposerBar；ChannelDetailWorkspaceView
// 的 messagesTab + composerSection），列表脚手架、输入框与发送
// 状态呈现口径一致，抽出共用，不再各改各的。
//
// 经插槽/参数注入的外壳差异：消息内容渲染（单聊气泡 vs 频道
// 卡片行——消息模型不同）、页面铬（频道 Tab/成员/发送方式选择，
// 单聊问候区/项目来源 chip/附件条）、发送语义（本地模型 vs 平台
// transport）。组件只管结构与状态呈现，不碰发送语义。

import SwiftUI
import UIKit

extension View {
    /// 条件式无障碍标识：nil 时不设置（保持系统默认）。
    @ViewBuilder
    func dialogAccessibilityID(_ id: String?) -> some View {
        if let id {
            self.accessibilityIdentifier(id)
        } else {
            self
        }
    }

    /// 条件式无障碍标签：nil 时不设置。
    @ViewBuilder
    func dialogAccessibilityLabel(_ label: String?) -> some View {
        if let label {
            self.accessibilityLabel(label)
        } else {
            self
        }
    }

    /// 点内容区回调（单聊用来收键盘）：nil 时不装手势、不改命中区。
    @ViewBuilder
    func dialogTapContent(_ action: (() -> Void)?) -> some View {
        if let action {
            self.contentShape(Rectangle()).onTapGesture { action() }
        } else {
            self
        }
    }

    /// 文本变化回调（单聊用来持久化草稿）：nil 时不装观察。
    @ViewBuilder
    func dialogOnTextChange(_ text: String, _ action: (() -> Void)?) -> some View {
        if let action {
            #if compiler(>=6.0)
            self.onChange(of: text) { _, _ in action() }
            #else
            self.onChange(of: text) { _ in action() }
            #endif
        } else {
            self
        }
    }
}

// MARK: - 消息列表脚手架

/// 对话消息列表脚手架：ScrollViewReader + ScrollView +
/// （Lazy）VStack，统一两页的列表结构与滚底口径。
/// - 单聊：VStack、消息数变化时滚到最后一条；问候/发送中/错误行
///   经 content / trailing 插槽进来。
/// - 频道：LazyVStack、scrollTargetID 传 nil（不无条件滚底）；
///   showsScrollToBottomButton 开时另有口径：贴底时新消息保持
///   贴底、离底时右下角出 ↓ 拉底按钮（滚到 bottomTargetID）。
/// ↓ 拉底按钮的几何量测通道（DialogMessageListView 内部用）。

/// 贴底探针：挂在滚动内容背景上，沿 superview 链找到底层
/// UIScrollView，KVO 观察 contentOffset 与 contentSize，
/// offset + 视口高 >= 内容高 - 24 即贴底（内容比视口矮时恒贴底）。
/// 只在值变化时回报。SwiftUI 上报通道在本列表全灭后的兜底硬信号
/// （见 DialogMessageListView.isAtBottom 的教训记录）。
private struct DialogScrollProbe: UIViewRepresentable {
    var onAtBottom: (Bool) -> Void
    var onMetrics: ((String) -> Void)? = nil

    func makeUIView(context: Context) -> UIView {
        let view = UIView(frame: .zero)
        view.isUserInteractionEnabled = false
        view.backgroundColor = .clear
        context.coordinator.onAtBottom = onAtBottom
        context.coordinator.onMetrics = onMetrics
        DispatchQueue.main.async { context.coordinator.attach(from: view) }
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        context.coordinator.onAtBottom = onAtBottom
        context.coordinator.onMetrics = onMetrics
        if context.coordinator.scrollView == nil {
            DispatchQueue.main.async { context.coordinator.attach(from: uiView) }
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator {
        var onAtBottom: ((Bool) -> Void)?
        var onMetrics: ((String) -> Void)?
        weak var scrollView: UIScrollView?
        private weak var probeView: UIView?
        private var retryPending = false
        private var attachAttempts = 0
        private var offsetObservation: NSKeyValueObservation?
        private var sizeObservation: NSKeyValueObservation?
        private var lastValue: Bool?

        func attach(from view: UIView) {
            guard scrollView == nil else { return }
            probeView = view
            var node: UIView? = view.superview
            while let current = node {
                if let sv = current as? UIScrollView {
                    scrollView = sv
                    let handler: (UIScrollView, Any) -> Void = { [weak self] sv, _ in
                        self?.evaluate(sv)
                    }
                    offsetObservation = sv.observe(\.contentOffset, options: [.initial, .new], changeHandler: handler)
                    sizeObservation = sv.observe(\.contentSize, options: [.initial, .new], changeHandler: handler)
                    return
                }
                node = current.superview
            }
            // 没找到：链还没建好，自重试（0.3s 一次、上限 40 次），
            // 不依赖父视图再渲染——静止后没人再调 updateUIView。
            guard !retryPending, attachAttempts < 40 else { return }
            retryPending = true
            attachAttempts += 1
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in
                guard let self else { return }
                self.retryPending = false
                if self.scrollView == nil, let view = self.probeView {
                    self.attach(from: view)
                }
            }
        }

        private func evaluate(_ sv: UIScrollView) {
            // 指标回报不可省：它的状态写入让父视图在滚动期间持续
            // 重渲染，updateUIView 的 attach 重试才不会饿死——去掉
            // 回报通道后探针会偶发整体哑火（2026-11-27 二分实测：
            // 带回报 = 双向正确，去回报 = 按钮常显）。
            let dbg = "off \(Int(sv.contentOffset.y)) b \(Int(sv.bounds.height)) cs \(Int(sv.contentSize.height))"
            let dbgCallback = onMetrics
            DispatchQueue.main.async { dbgCallback?(dbg) }
            let atBottom = sv.contentOffset.y + sv.bounds.height >= sv.contentSize.height - 24
            guard atBottom != lastValue else { return }
            lastValue = atBottom
            let callback = onAtBottom
            DispatchQueue.main.async { callback?(atBottom) }
        }
    }
}

public struct DialogMessageListView<Content: View, Trailing: View>: View {
    public var lazy: Bool
    public var spacing: CGFloat
    public var horizontalPadding: CGFloat
    public var topPadding: CGFloat
    public var bottomPadding: CGFloat
    /// 变化时触发滚底的计数（通常是消息数）。
    public var scrollTrigger: Int
    /// 滚底目标（最后一条消息的 id）；nil = 不滚。
    public var scrollTargetID: String?
    /// 点内容区回调（单聊收键盘用）；nil = 不装手势。
    public var onTapContent: (() -> Void)?
    /// ↓ 拉底按钮开关（频道用，2026-11-27）：开时栈尾放哨兵跟踪
    /// 是否贴底，离底显示按钮、贴底时新消息自动跟随。默认关。
    public var showsScrollToBottomButton: Bool
    /// 拉底目标（最后一条消息的 id）；按钮与贴底跟随都滚到它。
    public var bottomTargetID: String?
    /// 贴底判定由底层 UIScrollView 的 contentOffset/contentSize
    /// 实测（DialogScrollProbe 的 KVO 回报），不用 SwiftUI 上报
    /// 通道——教训（2026-11-27，iOS 17.2 模拟器逐项实测）：
    /// PreferenceKey 两个键恒不投递（状态恒 0）、scrollPosition
    /// (id:) 绑定恒为 nil（加 scrollTargetLayout 也不投递）、哨兵
    /// onAppear 按 LazyVStack 预实现窗口触发会误判。四路皆不可靠，
    /// 只剩 UIKit 层 KVO 是硬信号。
    @State private var probeAtBottom = false
    @State private var probeMetrics = ""

    private var isAtBottom: Bool { probeAtBottom }
    public var content: () -> Content
    public var trailing: () -> Trailing

    public init(
        lazy: Bool = false,
        spacing: CGFloat,
        horizontalPadding: CGFloat,
        topPadding: CGFloat = 0,
        bottomPadding: CGFloat = 0,
        scrollTrigger: Int,
        scrollTargetID: String? = nil,
        onTapContent: (() -> Void)? = nil,
        showsScrollToBottomButton: Bool = false,
        bottomTargetID: String? = nil,
        @ViewBuilder content: @escaping () -> Content,
        @ViewBuilder trailing: @escaping () -> Trailing
    ) {
        self.lazy = lazy
        self.spacing = spacing
        self.horizontalPadding = horizontalPadding
        self.topPadding = topPadding
        self.bottomPadding = bottomPadding
        self.scrollTrigger = scrollTrigger
        self.scrollTargetID = scrollTargetID
        self.onTapContent = onTapContent
        self.showsScrollToBottomButton = showsScrollToBottomButton
        self.bottomTargetID = bottomTargetID
        self.content = content
        self.trailing = trailing
    }

    public var body: some View {
        // 依赖锚（不可删）：body 必须真读一次 probeMetrics，探针的
        // 指标写入才会触发重渲染、updateUIView 的 attach 重试才不
        // 会饿死。只写不读的 @State 在 SwiftUI 里不使视图失效——
        // 2026-11-27 二分实测：去掉这次读，探针整体哑火、按钮常显。
        let _ = probeMetrics
        return ScrollViewReader { proxy in
            ZStack(alignment: .bottomTrailing) {
                scrollView
                if showsScrollToBottomButton, !isAtBottom, bottomTargetID != nil {
                    Button {
                        scrollToBottom(proxy)
                    } label: {
                        Image(systemName: "arrow.down")
                            .font(.system(size: 15, weight: .bold))
                            .foregroundStyle(.white)
                            .frame(width: 40, height: 40)
                            .background(Color(red: 0.16, green: 0.15, blue: 0.14), in: Circle())
                            .shadow(color: .black.opacity(0.25), radius: 4, y: 2)
                    }
                    .buttonStyle(.plain)
                    .padding(.trailing, 16)
                    .padding(.bottom, 12)
                    .accessibilityIdentifier("dialog.scrollToBottom")
                }
            }
            #if compiler(>=6.0)
            .onChange(of: scrollTrigger) { _, _ in scrollToTarget(proxy) }
            #else
            .onChange(of: scrollTrigger) { _ in scrollToTarget(proxy) }
            #endif
        }
    }

    /// 消息滚动区：拉底按钮开启时挂 scrollPosition 跟踪贴底
    /// （判定见 isAtBottom）；关闭时就是普通滚动区，零影响。
    @ViewBuilder
    private var scrollView: some View {
        if showsScrollToBottomButton {
            ScrollView {
                paddedStack
                    .background {
                        DialogScrollProbe(onAtBottom: { atBottom in
                            if probeAtBottom != atBottom { probeAtBottom = atBottom }
                        }, onMetrics: { probeMetrics = $0 })
                    }
            }
        } else {
            ScrollView { paddedStack }
        }
    }

    private var paddedStack: some View {
        stack
            .padding(.horizontal, horizontalPadding)
            .padding(.top, topPadding)
            .padding(.bottom, bottomPadding)
            .dialogTapContent(onTapContent)
    }

    @ViewBuilder
    private var stack: some View {
        if lazy {
            LazyVStack(spacing: spacing) {
                content()
                trailing()
                bottomSentinel
            }
        } else {
            VStack(spacing: spacing) {
                content()
                trailing()
                bottomSentinel
            }
        }
    }

    /// 栈尾哨兵（仅拉底按钮开启时）：只作滚底落点（scrollTo 目标），
    /// 不再承担贴底量测（量测改走栈帧，见 isAtBottom 的教训记录）。
    @ViewBuilder
    private var bottomSentinel: some View {
        if showsScrollToBottomButton {
            Color.clear
                .frame(height: 1)
                .id("dialog.bottomSentinel")
        }
    }

    private func scrollToTarget(_ proxy: ScrollViewProxy) {
        if let scrollTargetID {
            withAnimation { proxy.scrollTo(scrollTargetID, anchor: .bottom) }
        } else if showsScrollToBottomButton, isAtBottom {
            // 频道口径：贴底时新消息保持贴底；离底不打扰，
            // 由 ↓ 按钮等用户点（与主流聊天软件一致）。
            scrollToBottom(proxy)
        }
    }

    private func scrollToBottom(_ proxy: ScrollViewProxy) {
        // 滚哨兵本身而不是最后一条消息：消息底边对齐视口下沿时哨兵
        // 还差一截间距未入视野，到位后按钮迟迟不消失（2026-11-27 实测）。
        withAnimation { proxy.scrollTo("dialog.bottomSentinel", anchor: .bottom) }
    }
}

// MARK: - 输入区

/// 对话输入区核心：文本框 + 发送状态钮状态机 + 上/下/前置/尾随
/// 插槽。两页排布是既有家规，作排布参数保留，不抹平：
/// - stacked（单聊）：附件条（above 插槽）+ 纵向文本框 + 底行
///   （前置控件 / 尾随控件 / 状态钮）；白色圆角卡片容器由页面给。
/// - inline（频道）：above 插槽（发送方式/回复横幅/附件条）+
///   单行（前置 + 菜单 + 文本框 + 状态钮，行铬沿用频道现状）+
///   below 插槽（超限提示/边界文案）。
///
/// 发送状态钮状态机（两页共用）：发送中且有 onStop → 停止钮；
/// 草稿为空且无附件且有 onEmptyAction → 空态动作钮（如单聊语音）；
/// 其余 → 发送钮，禁用条件 =（草稿为空且无附件）或超字数上限。
/// 按钮外观两套（blackCircle 单聊黑圆 / accentGlyph 频道大字形），
/// 按页既有样式，不互相迁就。
public struct DialogComposerView<Above: View, Leading: View, Accessory: View, Below: View>: View {
    public enum Layout { case stacked, inline }
    public enum SendButtonStyle { case blackCircle, accentGlyph }

    @Binding public var text: String
    public var placeholder: String
    public var layout: Layout
    public var sendButtonStyle: SendButtonStyle
    public var isSending: Bool
    public var hasAttachment: Bool
    public var maxLength: Int?
    public var focused: FocusState<Bool>.Binding?
    public var onTextChange: (() -> Void)?
    public var onSend: () -> Void
    public var onStop: (() -> Void)?
    public var onEmptyAction: (() -> Void)?
    public var emptyActionIcon: String
    public var emptyActionLabel: String?
    public var inputAccessibilityID: String?
    public var sendAccessibilityID: String?
    public var stopAccessibilityID: String?
    public var above: () -> Above
    public var leading: () -> Leading
    public var accessory: () -> Accessory
    public var below: () -> Below

    public init(
        text: Binding<String>,
        placeholder: String,
        layout: Layout,
        sendButtonStyle: SendButtonStyle,
        isSending: Bool = false,
        hasAttachment: Bool = false,
        maxLength: Int? = nil,
        focused: FocusState<Bool>.Binding? = nil,
        onTextChange: (() -> Void)? = nil,
        onSend: @escaping () -> Void,
        onStop: (() -> Void)? = nil,
        onEmptyAction: (() -> Void)? = nil,
        emptyActionIcon: String = "waveform",
        emptyActionLabel: String? = nil,
        inputAccessibilityID: String? = nil,
        sendAccessibilityID: String? = nil,
        stopAccessibilityID: String? = nil,
        @ViewBuilder above: @escaping () -> Above,
        @ViewBuilder leading: @escaping () -> Leading,
        @ViewBuilder accessory: @escaping () -> Accessory,
        @ViewBuilder below: @escaping () -> Below
    ) {
        self._text = text
        self.placeholder = placeholder
        self.layout = layout
        self.sendButtonStyle = sendButtonStyle
        self.isSending = isSending
        self.hasAttachment = hasAttachment
        self.maxLength = maxLength
        self.focused = focused
        self.onTextChange = onTextChange
        self.onSend = onSend
        self.onStop = onStop
        self.onEmptyAction = onEmptyAction
        self.emptyActionIcon = emptyActionIcon
        self.emptyActionLabel = emptyActionLabel
        self.inputAccessibilityID = inputAccessibilityID
        self.sendAccessibilityID = sendAccessibilityID
        self.stopAccessibilityID = stopAccessibilityID
        self.above = above
        self.leading = leading
        self.accessory = accessory
        self.below = below
    }

    private var trimmedText: String {
        text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var overLimit: Bool {
        guard let maxLength else { return false }
        return text.count > maxLength
    }

    private var sendDisabled: Bool {
        (trimmedText.isEmpty && !hasAttachment) || overLimit
    }

    public var body: some View {
        switch layout {
        case .stacked:
            VStack(alignment: .leading, spacing: 6) {
                above()
                field
                HStack(spacing: 10) {
                    leading()
                    Spacer()
                    accessory()
                    stateButton
                }
                .foregroundStyle(Color(red: 0.35, green: 0.34, blue: 0.32))
            }
        case .inline:
            VStack(spacing: 4) {
                above()
                HStack(spacing: 8) {
                    leading()
                    field
                    stateButton
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .background(Color(red: 0.95, green: 0.94, blue: 0.92))
                below()
            }
        }
    }

    @ViewBuilder
    private var field: some View {
        switch layout {
        case .stacked:
            Group {
                if let focused {
                    TextField(placeholder, text: $text, axis: .vertical)
                        .lineLimit(1...8)
                        .font(.system(size: 16))
                        .focused(focused)
                } else {
                    TextField(placeholder, text: $text, axis: .vertical)
                        .lineLimit(1...8)
                        .font(.system(size: 16))
                }
            }
            .dialogAccessibilityID(inputAccessibilityID)
            .dialogOnTextChange(text, onTextChange)
        case .inline:
            Group {
                if let focused {
                    TextField(placeholder, text: $text)
                        .focused(focused)
                } else {
                    TextField(placeholder, text: $text)
                }
            }
                .font(.system(size: 14))
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Color.white, in: RoundedRectangle(cornerRadius: 18))
                .overlay(
                    RoundedRectangle(cornerRadius: 18)
                        .stroke(Color(red: 0.88, green: 0.87, blue: 0.85), lineWidth: 1)
                )
                .dialogAccessibilityID(inputAccessibilityID)
                .dialogOnTextChange(text, onTextChange)
        }
    }

    @ViewBuilder
    private var stateButton: some View {
        if isSending, let onStop {
            Button { onStop() } label: { stateIcon(systemName: "stop.fill", fontSize: 12) }
                .dialogAccessibilityID(stopAccessibilityID)
        } else if trimmedText.isEmpty && !hasAttachment, let onEmptyAction {
            Button { onEmptyAction() } label: { stateIcon(systemName: emptyActionIcon, fontSize: 13) }
                .dialogAccessibilityLabel(emptyActionLabel)
        } else {
            Button { onSend() } label: { sendIcon }
                .disabled(sendDisabled)
                .dialogAccessibilityID(sendAccessibilityID)
        }
    }

    @ViewBuilder
    private var sendIcon: some View {
        switch sendButtonStyle {
        case .blackCircle:
            stateIcon(systemName: "arrow.up", fontSize: 13)
        case .accentGlyph:
            Image(systemName: "arrow.up.circle.fill")
                .font(.system(size: 32))
                .foregroundStyle(
                    sendDisabled
                        ? Color.secondary.opacity(0.3)
                        : Color(red: 0.82, green: 0.49, blue: 0.35)
                )
        }
    }

    /// 状态钮图标：blackCircle = 白字形黑圆（单聊）；accentGlyph =
    /// 大字形 accent 色（频道，仅发送态在用，停止/空态为预留同族样式）。
    @ViewBuilder
    private func stateIcon(systemName: String, fontSize: CGFloat) -> some View {
        switch sendButtonStyle {
        case .blackCircle:
            Image(systemName: systemName)
                .font(.system(size: fontSize, weight: .bold))
                .foregroundStyle(.white)
                .frame(width: 32, height: 32)
                .background(Color.black, in: Circle())
        case .accentGlyph:
            Image(systemName: systemName)
                .font(.system(size: 32))
                .foregroundStyle(Color(red: 0.82, green: 0.49, blue: 0.35))
        }
    }
}


// MARK: - 218 搬家：原 ChatListView.swift 内的共享件
// ChatListView 与 ChannelChatView 已于 218 删除（频道页收敛到
// ChannelDetailWorkspaceView）；这两件被 TasksView / DevicesView
// 继续使用，随此文件保留。

struct PlatformConnectionCardSection: View {
    let onGoSettings: () -> Void

    var body: some View {
        Section {
            VStack(alignment: .leading, spacing: 8) {
                Label("平台未连接", systemImage: "wifi.slash")
                    .font(.headline)
                Text("团队频道与平台任务需要先连接平台。平台只是本 App 的一个连接项，在「设置」里完成：地址已预填；平台凭证由你的平台管理员签发后填入，或用已有 owner 凭证在设置里生成。")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Button("去设置") { onGoSettings() }
                    .buttonStyle(.borderedProminent)
            }
            .padding(.vertical, 4)
        } header: { Text("平台连接").textCase(nil) }
    }
}

enum AgentUI {
    private static func adaptive(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(uiColor: UIColor { traits in
            let value = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: CGFloat((value >> 16) & 255) / 255,
                green: CGFloat((value >> 8) & 255) / 255,
                blue: CGFloat(value & 255) / 255, alpha: 1)
        })
    }
    static let background = adaptive(0xFAF8F5, 0x0B0E1D)
    static let card = adaptive(0xFFFFFF, 0x171B31)
    static let ink = adaptive(0x15181E, 0xF2F4FF)
    static let secondary = adaptive(0x5A6270, 0x9AA3C7)
    static let border = adaptive(0xE6E1D8, 0x2E3560)
    static let warning = adaptive(0xA86200, 0xF5A524)
    static let warningBackground = adaptive(0xFFF1D8, 0x3A2C14)
    static let accent = Color(red: 123/255, green: 92/255, blue: 1)
    static let userBubble = adaptive(0x2E63E8, 0x2E63E8)
}

/// The trailing @token of `text` when the @ starts a word (start of
/// text or after whitespace) and the token has no whitespace yet;
/// nil otherwise (e.g. an email address, or the mention is done).
/// （218：原在已删除的 ChannelChatView.swift，单测 ChannelParsingTests
/// 仍在用，迁入本共享文件保存。）
func mentionToken(in text: String) -> String? {
    guard let at = text.lastIndex(of: "@") else { return nil }
    let before = text[..<at]
    if let last = before.last, last != " ", last != "\n" { return nil }
    let token = text[text.index(after: at)...]
    if token.contains(" ") || token.contains("\n") { return nil }
    return String(token)
}

/// Members matching the trailing @token (at most 8).
func mentionMatches(in text: String, memberNames: [String]) -> [String] {
    guard let token = mentionToken(in: text) else { return [] }
    // 只打了「@」还没输名字时列全员（与安卓 updateMentionBar 同口径；
    // localizedCaseInsensitiveContains("") 恒为 false，必须特判）。
    if token.isEmpty {
        return Array(memberNames.filter { !$0.isEmpty }.prefix(8))
    }
    return Array(
        memberNames.filter {
            $0.localizedCaseInsensitiveContains(token) &&
                $0.caseInsensitiveCompare(token) != .orderedSame
        }.prefix(8)
    )
}

/// Replace the trailing @token with `@<name> `.
func applyingMention(_ name: String, to text: String) -> String {
    guard let at = text.lastIndex(of: "@"), mentionToken(in: text) != nil else { return text }
    return String(text[..<at]) + "@" + name + " "
}
