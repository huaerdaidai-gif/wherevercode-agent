// WhereverCode Agent — iOS
//
// 网页版 Agent 席位的管理界面（Agent 页进入）：添加席位、在内嵌
// 浏览器里登录、选它服务的频道、看它在线还是休息中。席位背后的
// 逻辑在 WebSeat.swift / WebSeatEngine.swift。

import SwiftUI
import WebKit

/// Wraps an existing WKWebView (owned by a WebSeatSession) for
/// SwiftUI. The same web view is hosted hidden by WebSeatHostView
/// and shown here for login; UIKit moves it between the two.
struct WebViewBox: UIViewRepresentable {
    let webView: WKWebView
    func makeUIView(context: Context) -> WKWebView { webView }
    func updateUIView(_ uiView: WKWebView, context: Context) {}
}

/// Keeps every seat's web view attached to the hierarchy (2x2,
/// nearly invisible, no touches) so page scripts keep running
/// while the App is in the foreground.
public struct WebSeatHostView: View {
    @ObservedObject var appState: AppState

    public init(appState: AppState) {
        self.appState = appState
    }

    public var body: some View {
        ZStack {
            if let engine = appState.webSeatEngine {
                ForEach(engine.seats.filter { $0.enabled }) { seat in
                    WebViewBox(webView: engine.session(for: seat).webView)
                }
            }
        }
        .frame(width: 2, height: 2)
        .opacity(0.01)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .id(appState.webSeatHostGeneration)
    }
}

public struct WebSeatManagerView: View {
    @ObservedObject var appState: AppState
    @State private var loginSeat: WebSeatRecord?
    @State private var errorText: String?

    public init(appState: AppState) {
        self.appState = appState
    }

    public var body: some View {
        Group {
            if let engine = appState.webSeatEngine {
                WebSeatManagerContent(
                    appState: appState, engine: engine, loginSeat: $loginSeat)
            } else if appState.settings.mcpEndpoint == nil {
                Text("先在「设置 → 连接管理」里连接平台，网页版 Agent 才能工作。")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .padding()
            } else {
                ProgressView("正在准备…")
            }
        }
        .navigationTitle("网页版 Agent")
        .task {
            appState.startWebSeats()
            await appState.refreshConnectionStatus()
        }
        .sheet(item: $loginSeat, onDismiss: { appState.bumpWebSeatHost() }) { seat in
            if let engine = appState.webSeatEngine {
                WebSeatBrowserView(engine: engine, seat: seat)
            }
        }
    }
}

private struct WebSeatManagerContent: View {
    @ObservedObject var appState: AppState
    @ObservedObject var engine: WebSeatEngine
    @Binding var loginSeat: WebSeatRecord?
    @State private var errorText: String?
    @State private var pendingDelete: WebSeatRecord?

    var body: some View {
        List {
            if let errorText {
                Section {
                    Text(errorText)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            Section {
                if engine.seats.isEmpty {
                    Text("还没有网页版 Agent。在下面添加一个，然后在打开的网页里登录你的账号。")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                ForEach(engine.seats) { seat in
                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            Text(seat.displayName)
                                .font(.subheadline.weight(.semibold))
                            Spacer()
                            Text(engine.statuses[seat.seatId]?.label ?? "休息中")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        HStack(spacing: 14) {
                            Button("打开 / 登录") { loginSeat = seat }
                                .font(.caption)
                            Button("重新检测") {
                                Task { await engine.refreshLogin(seat) }
                            }
                            .font(.caption)
                            NavigationLink("服务的频道") {
                                WebSeatChannelPicker(appState: appState, engine: engine, seat: seat)
                            }
                            .font(.caption)
                        }
                        Toggle("启用", isOn: Binding(
                            get: { seat.enabled },
                            set: { engine.setEnabled(seat, enabled: $0) }))
                            .font(.caption)
                    }
                    .swipeActions {
                        Button("删除", role: .destructive) { pendingDelete = seat }
                    }
                }
            } header: {
                Text("已添加的席位").textCase(nil)
            } footer: {
                Text("席位只在 App 打开时在线；关掉 App 就休息中，不会冒充常驻。")
            }

            Section {
                ForEach(WebSeatSite.all, id: \.driverId) { site in
                    if engine.seats.contains(where: { $0.driverId == site.driverId }) {
                        HStack {
                            Text(site.displayName)
                            Spacer()
                            Text("已添加")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    } else {
                        Button("＋ 添加 \(site.displayName)") {
                            Task {
                                let (record, error) = await engine.addSeat(
                                    site: site,
                                    channelIds: appState.availableChannels.map { $0.id })
                                if let error {
                                    errorText = error
                                } else {
                                    loginSeat = record
                                }
                            }
                        }
                    }
                }
            } header: {
                Text("添加席位").textCase(nil)
            } footer: {
                Text("登录在官网页面里完成，密码只输在网页里，不经过 App。添加后席位会加入你现有的全部频道，可在「服务的频道」里改。")
            }
        }
        .confirmationDialog(
            "删除这个席位？它的登录状态会一并清掉。",
            isPresented: Binding(
                get: { pendingDelete != nil },
                set: { if !$0 { pendingDelete = nil } }),
            presenting: pendingDelete
        ) { seat in
            Button("删除", role: .destructive) {
                engine.deleteSeat(seat)
                pendingDelete = nil
            }
            Button("取消", role: .cancel) { pendingDelete = nil }
        }
    }
}

private struct WebSeatChannelPicker: View {
    @ObservedObject var appState: AppState
    @ObservedObject var engine: WebSeatEngine
    let seat: WebSeatRecord

    var body: some View {
        List {
            if appState.availableChannels.isEmpty {
                Text("还没有频道列表。先打开一次频道页让列表加载出来。")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            ForEach(appState.availableChannels) { channel in
                Toggle(channel.name.isEmpty ? channel.id : channel.name, isOn: Binding(
                    get: { currentSeat?.channelIds.contains(channel.id) ?? false },
                    set: { on in
                        guard let current = currentSeat else { return }
                        var ids = current.channelIds
                        if on {
                            if !ids.contains(channel.id) { ids.append(channel.id) }
                        } else {
                            ids.removeAll { $0 == channel.id }
                        }
                        engine.setChannels(current, channelIds: ids)
                    }))
            }
        }
        .navigationTitle("服务的频道")
    }

    private var currentSeat: WebSeatRecord? {
        engine.seats.first { $0.seatId == seat.seatId }
    }
}

private struct WebSeatBrowserView: View {
    @ObservedObject var engine: WebSeatEngine
    let seat: WebSeatRecord
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            WebViewBox(webView: engine.session(for: seat).webView)
                .ignoresSafeArea(edges: .bottom)
                .navigationTitle(seat.displayName)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("完成") { dismiss() }
                    }
                    ToolbarItem(placement: .primaryAction) {
                        Button("检测登录") {
                            Task { await engine.refreshLogin(seat) }
                        }
                    }
                }
                .onAppear {
                    engine.session(for: seat).loadHome()
                }
        }
    }
}

/// 「已添加的 Agent」里的网页席位行（Agent 页块二，与普通 Agent 同列）：
/// 名称＋状态＋启用开关，点「管理」进管理页（登录/频道/删除都在那里）。
public struct WebSeatAddedRows: View {
    @ObservedObject var engine: WebSeatEngine
    var onOpen: () -> Void

    public init(engine: WebSeatEngine, onOpen: @escaping () -> Void) {
        self.engine = engine
        self.onOpen = onOpen
    }

    public var body: some View {
        ForEach(engine.seats) { seat in
            HStack {
                Image(systemName: "globe").foregroundStyle(.secondary)
                VStack(alignment: .leading, spacing: 2) {
                    Text(seat.displayName).font(.subheadline.weight(.semibold))
                    Text(engine.statuses[seat.seatId]?.label ?? (seat.enabled ? "休息中" : "已停用"))
                        .font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Toggle("启用", isOn: Binding(
                    get: { seat.enabled },
                    set: { engine.setEnabled(seat, enabled: $0) }
                )).labelsHidden()
                Button("管理") { onOpen() }.font(.caption)
            }
        }
    }
}
