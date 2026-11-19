import SwiftUI
import CryptoKit

/// 设备管理与详情视图（覆盖 devices 与 devicedetail 路由）：
/// - 支持局域网自动发现 WhereverCode Device Host（mDNS 与标准 18743 探测）；
/// - 集成跨平台一键安装脚本与通用终端引导（Mac、Windows、Linux/VPS、Android）；
/// - 密码学双向验证配对（服务端公钥 SHA-256 指纹核对 + 6 位一次性配对码）；
/// - 凭据严格存储于 iOS Keychain (kSecAttrAccessibleWhenUnlockedThisDeviceOnly)；
/// - 真实设备能力闭环：真实在线心跳、项目目录读取、授权文件读写、Agent 能力查询与远程控制。
public struct DevicesView: View {
    public var onNavigateRoute: (String) -> Void
    @State private var devices: [AgentRecord] = []
    @State private var loadError: String?
    @State private var showAddSheet = false
    @State private var showInstallSheet = false
    @State private var selectedDevice: AgentRecord? = nil
    @State private var devicePendingDelete: AgentRecord? = nil
    @State private var showDeleteConfirm = false
    @State private var statusBanner: String? = nil
    @State private var discoveredPeers: [DiscoveredPeer] = []
    @State private var isScanning = false

    public init() {
        self.onNavigateRoute = { _ in }
    }

    public init(appState: AppState, onNavigateRoute: @escaping (String) -> Void = { _ in }) {
        self.onNavigateRoute = onNavigateRoute
    }

    public var body: some View {
        List {
            if let statusBanner {
                Section {
                    Label(statusBanner, systemImage: "info.circle.fill")
                        .font(.caption)
                        .foregroundStyle(.blue)
                }
            }

            discoveryAndInstallSection
            registeredDevicesSection
        }
        .navigationTitle("设备")
        .refreshable {
            reload()
            startLanScan()
        }
        .onAppear {
            reload()
            startLanScan()
        }
        .alert("确认删除设备", isPresented: $showDeleteConfirm) {
            Button("取消", role: .cancel) { devicePendingDelete = nil }
            Button("删除", role: .destructive) {
                if let dev = devicePendingDelete {
                    deleteDeviceRecord(device: dev)
                    devicePendingDelete = nil
                }
            }
        } message: {
            if let dev = devicePendingDelete {
                Text("确定删除设备「\(dev.displayName)」吗？只删这条设备记录：这台设备上的 Agent 记录、项目和会话都不删，之后可以重新添加这台设备。")
            }
        }
        .sheet(isPresented: $showAddSheet) {
            AddDeviceSheet(
                discoveredPeers: $discoveredPeers,
                isScanning: $isScanning,
                onScan: startLanScan,
                onSaved: { added in
                    reload()
                    startLanScan()
                    statusBanner = "已成功添加并配对设备「\(added.displayName)」，可在对话来源中选用。"
                }
            )
        }
        .sheet(isPresented: $showInstallSheet) {
            InstallGuideSheet()
        }
        .sheet(item: $selectedDevice) { dev in
            DeviceDetailSheet(
                device: dev,
                onUpdated: {
                    reload()
                    startLanScan()
                },
                onNavigateRoute: { r in
                    selectedDevice = nil
                    onNavigateRoute(r)
                }
            )
        }
    }

    // MARK: - 一、添加设备区

    @ViewBuilder
    private var discoveryAndInstallSection: some View {
        Section("添加设备") {
            Button {
                showAddSheet = true
            } label: {
                Label("＋ 添加设备", systemImage: "plus.circle.fill")
                    .font(.subheadline.weight(.semibold))
            }
            .accessibilityIdentifier("devices.addButton")

            Button {
                showInstallSheet = true
            } label: {
                HStack {
                    Label("在新设备上安装 Hand", systemImage: "arrow.down.circle")
                        .font(.subheadline)
                        .foregroundStyle(.primary)
                    Spacer()
                    Image(systemName: "chevron.right")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

        }
    }

    // MARK: - 二、已添加设备列表

    @ViewBuilder
    private var registeredDevicesSection: some View {
        Section("已添加设备") {
            if let loadError {
                Text(loadError).foregroundStyle(.red)
            }
            if devices.isEmpty {
                Text("还没有添加设备。")
                    .font(.subheadline)
                    .foregroundStyle(AgentUI.secondary)
            }
            ForEach(devices, id: \.agentId) { device in
                let devId = device.deviceId ?? device.agentId
                let hostedCount = (try? LocalData.shared.agents.listAgents())?.filter { $0.deviceId == devId && $0.agentId != devId }.count ?? 0
                Button {
                    selectedDevice = device
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: deviceIcon(for: device))
                            .font(.title2)
                            .foregroundStyle(statusColor(for: device.memberConnectionState))
                            .frame(width: 32)

                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text(device.displayName)
                                    .font(.subheadline.weight(.semibold))
                                    .foregroundStyle(.primary)
                                statusBadge(for: device.memberConnectionState)
                                Spacer()
                                Text("\(hostedCount) 个 Agent")
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                                    .padding(.horizontal, 6)
                                    .padding(.vertical, 2)
                                    .background(Color.secondary.opacity(0.12), in: Capsule())
                            }
                            Text("\(device.baseUrl ?? "未填地址") · \(deviceStatusSummary(device, hostedCount: hostedCount))")
                                .font(.caption)
                                .foregroundStyle(AgentUI.secondary)
                            if let folder = device.deviceFolder, !folder.isEmpty {
                                Text("授权路径：\(folder)")
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(.caption)
                            .foregroundStyle(.tertiary)
                    }
                }
            }
            .onDelete { offsets in
                if let idx = offsets.first, idx < devices.count {
                    devicePendingDelete = devices[idx]
                    showDeleteConfirm = true
                }
            }
        }
    }

    private func deviceStatusSummary(_ device: AgentRecord, hostedCount: Int) -> String {
        switch device.memberConnectionState {
        case "CONNECTED":
            return "已连接 · \(hostedCount) 个 Agent"
        case "PENDING":
            return "待配对 · \(hostedCount) 个 Agent"
        case "DENIED":
            return "权限不足 · \(hostedCount) 个 Agent"
        default:
            return "未连接 · \(hostedCount) 个 Agent"
        }
    }

    private func deleteDeviceRecord(device: AgentRecord) {
        let devId = device.deviceId ?? device.agentId
        if let raw = device.baseUrl, !raw.isEmpty {
            let (host, port) = parseHostAndPort(raw)
            Task {
                _ = try? await HandClient.shared.unpairDevice(host: host, port: port, targetDeviceId: devId)
            }
        }
        HandIdentityManager.deleteDeviceCredentials(deviceId: devId)
        if device.agentId != devId {
            HandIdentityManager.deleteDeviceCredentials(deviceId: device.agentId)
        }
        // 核心规则：删设备只删设备、删 Agent 只删 Agent，互不级联，不影响任务、项目或会话
        try? LocalData.shared.agents.deleteDeviceCascade(deviceId: devId)
        if device.agentId != devId {
            try? LocalData.shared.agents.deleteAgent(agentId: device.agentId)
        }
        // 删除后只刷新列表，不跑历史迁移，避免改写其它记录。
        reload(runLegacyMigration: false)
    }

    // MARK: - 辅助方法

    private func reload(runLegacyMigration: Bool = true) {
        do {
            // 删除回调不走历史迁移：删设备只动目标设备记录与配对信任。
            if runLegacyMigration {
                try? LocalData.shared.agents.migrateLegacyDeviceRecords()
            }
            devices = try LocalData.shared.agents.listAgents().filter { $0.isDeviceHost }
            loadError = nil
        } catch {
            loadError = error.localizedDescription
        }
    }

    private func startLanScan() {
        guard !isScanning else { return }
        isScanning = true
        Task {
            let registeredHosts = devices.compactMap { $0.baseUrl }.map { raw -> String in
                let clean = raw.replacingOccurrences(of: "http://", with: "").replacingOccurrences(of: "https://", with: "")
                return String(clean.split(separator: ":").first ?? "")
            }.filter { !$0.isEmpty }

            var probeHosts = ["192.168.1.4", "100.75.41.0", "127.0.0.1"]
            for h in registeredHosts {
                if !probeHosts.contains(h) { probeHosts.append(h) }
            }

            let peers = await HandClient.shared.discoverLanDevices(knownHosts: probeHosts)
            await MainActor.run {
                let activePeers = peers.filter { !LocalData.shared.agents.isDeviceDeleted(deviceId: $0.deviceId) }
                self.syncDiscoveredPeersWithRegisteredDevices(activePeers)
                self.isScanning = false
            }
        }
    }

    private func syncDiscoveredPeersWithRegisteredDevices(_ peers: [DiscoveredPeer]) {
        var unlinkedPeers: [DiscoveredPeer] = []
        var didUpdateRegistered = false

        for peer in peers {
            if let idx = devices.firstIndex(where: { HandClient.isSameDevice(record: $0, peer: peer) }) {
                // 已登记设备：精确同步最新 IP 地址与在线配对状态
                var dev = devices[idx]
                let targetBaseUrl = "\(peer.host):\(peer.port)"
                let ipChanged = dev.baseUrl != targetBaseUrl
                dev.baseUrl = targetBaseUrl
                dev.deviceId = peer.deviceId

                if peer.clientStatus == "APPROVED" {
                    dev.memberConnectionState = "CONNECTED"
                    dev.lastTestSummary = "已连接（局域网在线）"
                } else if peer.clientStatus == "PENDING" {
                    dev.memberConnectionState = "PENDING"
                    dev.lastTestSummary = "待配对"
                } else if peer.clientStatus == "REVOKED" || peer.clientStatus == "DENIED" {
                    dev.memberConnectionState = "DENIED"
                    dev.lastTestSummary = "权限不足"
                }

                try? LocalData.shared.agents.saveAgent(dev)
                didUpdateRegistered = true

                // IP 发生变化时，级联同步所属子 Agent 的 baseUrl 与连接状态
                if ipChanged, let all = try? LocalData.shared.agents.listAgents() {
                    for var child in all where child.kind == .device && child.deviceId == dev.deviceId && child.agentId != dev.agentId {
                        child.baseUrl = targetBaseUrl
                        child.memberConnectionState = dev.memberConnectionState
                        try? LocalData.shared.agents.saveAgent(child)
                    }
                }
            } else {
                // 仅未在已登记列表中出现的设备才作为新发现展示
                unlinkedPeers.append(peer)
            }
        }

        self.discoveredPeers = unlinkedPeers
        if didUpdateRegistered {
            reload()
        }
    }

    private func parseHostAndPort(_ raw: String) -> (String, Int) {
        let clean = raw.replacingOccurrences(of: "http://", with: "").replacingOccurrences(of: "https://", with: "")
        let parts = clean.split(separator: ":")
        if parts.count >= 2, let p = Int(parts[1]) {
            return (String(parts[0]), p)
        }
        return (clean, 18743)
    }

    private func deviceIcon(for device: AgentRecord) -> String {
        let name = device.displayName.lowercased()
        if name.contains("mac") { return "laptopcomputer" }
        if name.contains("vps") || name.contains("linux") { return "server.rack" }
        if name.contains("android") || name.contains("k70") || name.contains("小米") || name.contains("termux") { return "phone" }
        if name.contains("win") { return "desktopcomputer" }
        return "network"
    }

    private func statusColor(for state: String) -> Color {
        switch state {
        case "CONNECTED": return .green
        case "PENDING": return .orange
        case "DENIED": return .red
        default: return .gray
        }
    }

    @ViewBuilder
    private func statusBadge(for state: String) -> some View {
        let (text, color): (String, Color) = {
            switch state {
            case "CONNECTED": return ("已连接", .green)
            case "PENDING": return ("待配对", .orange)
            case "DENIED": return ("权限不足", .red)
            default: return ("离线", .secondary)
            }
        }()
        Text(text)
            .font(.system(size: 10, weight: .bold))
            .foregroundStyle(color)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(color.opacity(0.12), in: Capsule())
    }
}

// MARK: - 添加设备 Sheet

private struct AddDeviceSheet: View {
    @Binding var discoveredPeers: [DiscoveredPeer]
    @Binding var isScanning: Bool
    var onScan: () -> Void
    var onSaved: (AgentRecord) -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var name = ""
    @State private var host = ""
    @State private var portStr = "18743"
    @State private var publicHost = ""
    @State private var folder = "~/Projects"
    @State private var pairCode = ""
    @State private var showAdvanced = false
    @State private var selectedPeer: DiscoveredPeer?
    @State private var showInstallGuide = false

    @State private var isProbing = false
    @State private var probeHello: RemoteHello? = nil
    @State private var errorMessage: String? = nil
    @State private var isPairing = false
    @State private var isRefreshingCode = false
    @State private var refreshNotice: String? = nil

    var body: some View {
        NavigationStack {
            Form {
                if let probeHello {
                    pairingVerificationSection(hello: probeHello)
                } else {
                    initialDiscoveryAndInputSection
                }
            }
            .navigationTitle(probeHello == nil ? "添加设备" : "核对指纹与配对")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                if let hello = probeHello {
                    ToolbarItem(placement: .confirmationAction) {
                        Button(hello.clientStatus == "APPROVED" ? "确认添加" : "确认配对") {
                            executePairConfirm()
                        }
                        .disabled(isPairing || (hello.clientStatus != "APPROVED" && pairCode.trimmingCharacters(in: .whitespacesAndNewlines).count != 6))
                    }
                }
            }
            .onAppear(perform: onScan)
            .sheet(isPresented: $showInstallGuide) {
                InstallGuideSheet()
            }
        }
    }

    // 步骤一：发现设备或输入地址
    @ViewBuilder
    private var initialDiscoveryAndInputSection: some View {
        Section("附近的设备") {
            if discoveredPeers.isEmpty && !isScanning {
                Text("暂无发现的设备，可在下方手动输入地址。")
                    .foregroundStyle(.secondary)
            }
            if isScanning {
                ProgressView("正在查找附近的设备…")
            } else {
                Button("重新扫描", action: onScan)
            }
            ForEach(discoveredPeers) { peer in
                Button {
                    selectedPeer = peer
                    self.name = peer.displayName
                    self.host = peer.host
                    self.portStr = String(peer.port)
                    startProbe()
                } label: {
                    HStack {
                        Image(systemName: "desktopcomputer")
                            .foregroundStyle(.blue)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(peer.displayName).font(.subheadline.weight(.semibold)).foregroundStyle(.primary)
                            Text("\(peer.host):\(String(peer.port))").font(.caption2).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Text("连接")
                            .font(.caption.weight(.medium))
                            .padding(.horizontal, 10)
                            .padding(.vertical, 4)
                            .background(Color.blue.opacity(0.12), in: Capsule())
                    }
                }
                .disabled(isProbing)
            }
        }

        Section {
            Button {
                showInstallGuide = true
            } label: {
                Label("在新设备上安装 Hand", systemImage: "sparkles")
                    .font(.subheadline.weight(.medium))
            }
        } header: {
            Text("尚未安装 Hand？")
        } footer: {
            Text("支持输入局域网 IP、Tailscale 专网 IP (100.x.x.x) 或 Tailscale MagicDNS 域名。")
        }

        Section("手动连接信息") {
            TextField("设备名称（如 Mac Studio / K70-Termux）", text: $name)
            TextField("IP 或 Tailscale 主机名（如 100.x.x.x / 192.168.1.4）", text: $host)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()

            DisclosureGroup("高级设置（端口 / 公网直连 / 工作路径）", isExpanded: $showAdvanced) {
                TextField("端口（默认 18743）", text: $portStr)
                    .keyboardType(.numberPad)
                TextField("公网直连地址（可选）", text: $publicHost)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                TextField("默认授权目录（如 ~/Projects）", text: $folder)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            }
        }

        if let errorMessage {
            Section {
                Text(errorMessage)
                    .font(.caption)
                    .foregroundStyle(.red)
            }
        }

        Section {
            Button {
                startProbe()
            } label: {
                HStack {
                    Spacer()
                    if isProbing {
                        ProgressView().padding(.trailing, 6)
                        Text("正在连接…")
                    } else {
                        Text("下一步：核对指纹与配对")
                            .fontWeight(.semibold)
                    }
                    Spacer()
                }
            }
            .disabled(host.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || isProbing)
        }
    }

    // 步骤二：核对公钥指纹与输入 6 位配对码
    @ViewBuilder
    private func pairingVerificationSection(hello: RemoteHello) -> some View {
        Section("目标设备信息") {
            LabeledContent("设备名称", value: hello.displayName)
            LabeledContent("设备 ID", value: hello.deviceId)
            if hello.clientStatus == "APPROVED" {
                HStack(spacing: 6) {
                    Image(systemName: "checkmark.seal.fill")
                        .foregroundStyle(.green)
                    Text("当前设备已在目标机被批准（已授权）")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.green)
                }
            }
        }

        Section("核对服务端指纹（SHA-256）") {
            VStack(alignment: .leading, spacing: 6) {
                Text(hello.fingerprintSha256)
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(.primary)
                    .padding(8)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 6))
                    .textSelection(.enabled)
                Text("请与目标机终端上打印的 SHA-256 指纹比对，确保无中间人监听。")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }

        Section("输入配对码") {
            if hello.clientStatus == "APPROVED" {
                Text("此 iPhone 已被远端批准，可直接完成添加。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else {
                VStack(alignment: .leading, spacing: 6) {
                    TextField("6 位配对码（目标机终端输出）", text: $pairCode)
                        .keyboardType(.numberPad)
                        .font(.system(.title3, design: .monospaced).weight(.bold))
                        .multilineTextAlignment(.leading)
                    Text("目标机运行 Hand 时终端输出的 6 位一次性数字配对码。")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }

                if isLocallyAuthorizedPeer {
                    Button {
                        executeRemoteRefresh()
                    } label: {
                        HStack {
                            Label(isRefreshingCode ? "正在请求远端刷新…" : "免命令行远程刷新配对码", systemImage: "arrow.triangle.2.circlepath")
                                .font(.footnote)
                            if isRefreshingCode {
                                Spacer()
                                ProgressView().controlSize(.small)
                            }
                        }
                    }
                    .disabled(isRefreshingCode)

                    if let refreshNotice {
                        Text(refreshNotice)
                            .font(.caption2)
                            .foregroundStyle(.green)
                    }
                }
            }
        }

        if let errorMessage {
            Section {
                Text(errorMessage)
                    .font(.caption)
                    .foregroundStyle(.red)
            }
        }
    }

    private func startProbe() {
        let cleanName = name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if cleanName == "agy" || (selectedPeer?.displayName.lowercased() == "agy") {
            errorMessage = "agy 是 Agent，不得登记为设备。请在 Agent 页面添加。"
            return
        }

        let cleanHost = host.trimmingCharacters(in: .whitespacesAndNewlines)
        let port = Int(portStr.trimmingCharacters(in: .whitespacesAndNewlines)) ?? 18743
        guard !cleanHost.isEmpty else { return }

        isProbing = true
        errorMessage = nil

        Task {
            do {
                let hello = try await HandClient.shared.probeHello(host: cleanHost, port: port)
                await MainActor.run {
                    self.isProbing = false
                    if hello.displayName.lowercased() == "agy" {
                        self.errorMessage = "agy 是 Agent，不得登记为设备。请在 Agent 页面添加。"
                        return
                    }
                    self.probeHello = hello
                    if self.name.isEmpty {
                        self.name = hello.displayName
                    }
                    if let firstFolder = hello.agents.first?.folder, !firstFolder.isEmpty {
                        self.folder = firstFolder
                    }
                }
            } catch {
                await MainActor.run {
                    self.isProbing = false
                    self.errorMessage = "连接失败：\(error.localizedDescription)"
                }
            }
        }
    }

    private func executePairConfirm() {
        guard let hello = probeHello else { return }
        let cleanHost = host.trimmingCharacters(in: .whitespacesAndNewlines)
        let port = Int(portStr.trimmingCharacters(in: .whitespacesAndNewlines)) ?? 18743
        let cleanCode = pairCode.trimmingCharacters(in: .whitespacesAndNewlines)

        isPairing = true
        errorMessage = nil

        Task {
            do {
                let res = try await HandClient.shared.sendPairConfirm(host: cleanHost, port: port, hello: hello, code: cleanCode)
                await MainActor.run {
                    self.isPairing = false
                    switch res {
                    case .approved:
                        let cleanName = self.name.isEmpty ? hello.displayName : self.name
                        let fullHost = "\(cleanHost):\(port)"
                        let selectedFolder = folder.isEmpty ? (hello.agents.first?.folder ?? "") : folder
                        let rec = AgentRecord(
                            agentId: hello.deviceId,
                            displayName: cleanName,
                            kind: .device,
                            access: .app,
                            baseUrl: fullHost,
                            authRef: "device:\(hello.deviceId):public_key",
                            lastTestSummary: "已连接（在线）",
                            memberConnectionState: "CONNECTED",
                            deviceId: hello.deviceId,
                            deviceFolder: selectedFolder
                        )
                        try? LocalData.shared.agents.saveAgent(rec)

                        // 核心硬规则：配对成功不得自动建 Agent 记录、不得触发任何扫描；发现与配对完全独立！
                        onSaved(rec)
                        dismiss()
                    case .codeExpired(let reason):
                        self.errorMessage = "配对码已过期：\(reason)"
                    case .codeConsumed(let reason):
                        self.errorMessage = "配对码已作废：\(reason)"
                    case .codeWrong(let reason):
                        self.errorMessage = "配对码错误：\(reason)"
                    case .clientLocked(let reason):
                        self.errorMessage = "尝试次数超限：\(reason)"
                    case .revoked(let reason):
                        self.errorMessage = "授权已被撤销：\(reason)"
                    case .rejected(let code, let reason):
                        self.errorMessage = "远端拒绝（\(code)）：\(reason)"
                    case .failed(let reason):
                        self.errorMessage = "连接失败：\(reason)"
                    }
                }
            } catch {
                await MainActor.run {
                    self.isPairing = false
                    self.errorMessage = error.localizedDescription
                }
            }
        }
    }

    private var isLocallyAuthorizedPeer: Bool {
        guard let hello = probeHello else { return false }
        return HandIdentityManager.getPeerPublicKey(deviceId: hello.deviceId) != nil
    }

    private func executeRemoteRefresh() {
        guard let hello = probeHello else { return }
        let cleanHost = host.trimmingCharacters(in: .whitespacesAndNewlines)
        let port = Int(portStr.trimmingCharacters(in: .whitespacesAndNewlines)) ?? 18743

        isRefreshingCode = true
        errorMessage = nil
        refreshNotice = nil

        Task {
            do {
                let (newCode, ttl) = try await HandClient.shared.remoteRotatePairCode(host: cleanHost, port: port, targetDeviceId: hello.deviceId)
                await MainActor.run {
                    self.isRefreshingCode = false
                    self.pairCode = newCode
                    self.refreshNotice = "已由已认证设备授权刷新新配对码：\(newCode)（\(ttl) 秒有效）"
                }
            } catch {
                await MainActor.run {
                    self.isRefreshingCode = false
                    self.errorMessage = "远程刷新失败：\(error.localizedDescription)"
                }
            }
        }
    }
}

// MARK: - 一键安装指引 Sheet

private struct InstallGuideSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var selectedOs: DeviceTargetOs = .mac
    @State private var copiedNotice: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("选择目标设备系统") {
                    Picker("操作系统", selection: $selectedOs) {
                        ForEach(DeviceTargetOs.allCases) { os in
                            Label(os.rawValue, systemImage: os.iconName).tag(os)
                        }
                    }
                    .pickerStyle(.segmented)

                    HStack(spacing: 8) {
                        Image(systemName: selectedOs.iconName)
                            .font(.title3)
                            .foregroundStyle(.blue)
                        Text(selectedOs.subtitle)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                }

                Section("一键安装命令") {
                    let cmd = DeviceInstallGuide.installCommand(for: selectedOs)
                    VStack(alignment: .leading, spacing: 8) {
                        Text(cmd)
                            .font(.system(size: 12, design: .monospaced))
                            .padding(8)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 6))
                            .textSelection(.enabled)

                        HStack {
                            Button {
                                UIPasteboard.general.string = cmd
                                copiedNotice = "已复制安装命令"
                            } label: {
                                Label("复制命令", systemImage: "doc.on.doc")
                                    .font(.subheadline)
                            }
                            .buttonStyle(.borderedProminent)

                            Spacer()

                            if let copiedNotice {
                                Text(copiedNotice)
                                    .font(.caption)
                                    .foregroundStyle(.green)
                            }
                        }
                    }
                }

                Section("操作说明") {
                    ForEach(Array(DeviceInstallGuide.stepInstructions(for: selectedOs).enumerated()), id: \.offset) { idx, step in
                        HStack(alignment: .top, spacing: 8) {
                            Text("\(idx + 1).")
                                .font(.subheadline.weight(.bold))
                                .foregroundStyle(.blue)
                            Text(step)
                                .font(.subheadline)
                        }
                    }
                }

                if selectedOs == .mac || selectedOs == .linux || selectedOs == .vps {
                    Section("卸载命令") {
                        Text(DeviceInstallGuide.uninstallCommand(for: selectedOs))
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundStyle(.secondary)
                            .textSelection(.enabled)
                    }
                }
            }
            .navigationTitle("在新设备上安装 Hand")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
    }
}

// MARK: - 设备详情 Sheet

private struct DeviceDetailSheet: View {
    @State var device: AgentRecord
    var onUpdated: () -> Void
    var onNavigateRoute: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var isProbing = false
    @State private var probeResult: String?
    @State private var remoteHello: RemoteHello?
    @State private var isAuthorizingFolder = false
    @State private var authResult: String?
    @State private var directoryFiles: [RemoteFileEntry] = []
    @State private var isListingFiles = false
    @State private var showUnpairConfirm = false
    @State private var showDeleteConfirm = false
    @State private var fileOpResult: String?
    @State private var isDeleted = false

    var body: some View {
        NavigationStack {
            Form {
                Section("设备标识与连接") {
                    LabeledContent("设备名称", value: device.displayName)
                    LabeledContent("设备 ID", value: device.deviceId ?? device.agentId)
                    LabeledContent("连接状态") {
                        statusBadge(for: device.memberConnectionState)
                    }

                    HStack {
                        Text("地址")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .frame(width: 44, alignment: .leading)
                        TextField("如 192.168.1.4:18743", text: Binding(
                            get: { device.baseUrl ?? "" },
                            set: { val in
                                device.baseUrl = val.trimmingCharacters(in: .whitespacesAndNewlines)
                                try? LocalData.shared.agents.saveAgent(device)
                                onUpdated()
                            }
                        ))
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.system(.body, design: .monospaced))
                    }

                    Button(isProbing ? "正在连接探测…" : "测试连接并更新状态") {
                        probeRealConnection()
                    }
                    .disabled(isProbing || (device.baseUrl?.isEmpty ?? true))

                    if let probeResult {
                        Text(probeResult)
                            .font(.caption)
                            .foregroundStyle(probeResult.contains("成功") ? .green : .red)
                    }
                }

                if let hello = remoteHello {
                    Section {
                        ForEach(hello.agents) { ag in
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(ag.displayName).font(.subheadline.weight(.medium))
                                    Text("路径：\(ag.folder)").font(.caption2).foregroundStyle(.secondary)
                                }
                                Spacer()
                                Text(ag.available ? "可用" : "未安装")
                                    .font(.caption2)
                                    .foregroundStyle(ag.available ? .green : .secondary)
                            }
                        }
                        Button("进入 Agent 管理配置 / 调度 Agent") {
                            dismiss()
                            onNavigateRoute("agents")
                        }
                        .font(.caption)
                    } header: {
                        Text("承载 Agent（只读关联信息 · \(hello.agents.count) 个）")
                    } footer: {
                        Text("设备仅作为执行宿主，点此卡片只管理设备连接本身；要修改、配置或增删 Agent，请前往 Agent 管理。")
                    }
                }

                Section("工作区目录授权") {
                    TextField("工作目录路径", text: Binding(
                        get: { device.deviceFolder ?? "" },
                        set: { val in
                            device.deviceFolder = val
                            try? LocalData.shared.agents.saveAgent(device)
                            onUpdated()
                        }
                    ))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()

                    Button("向目标机授权此目录 (authorizeProjectFolder)") {
                        authorizeDirectory()
                    }
                    .disabled(isAuthorizingFolder || (device.deviceFolder?.isEmpty ?? true))

                    if let authResult {
                        Text(authResult)
                            .font(.caption)
                            .foregroundStyle(authResult.contains("成功") ? .green : .red)
                    }
                }

                Section("远程文件管理 (Hand 协议)") {
                    Button(isListingFiles ? "正在读取目录…" : "列出授权工作区文件 (listDirectory)") {
                        loadDirectoryFiles()
                    }
                    .disabled(isListingFiles || (device.deviceFolder?.isEmpty ?? true))

                    if !directoryFiles.isEmpty {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("目录文件列表（\(directoryFiles.count) 项）：")
                                .font(.caption.weight(.semibold))
                            ForEach(directoryFiles.prefix(10)) { item in
                                Label(item.name, systemImage: item.isDir ? "folder.fill" : "doc.text")
                                    .font(.caption)
                                    .foregroundStyle(item.isDir ? .blue : .primary)
                            }
                        }
                    }

                    if let fileOpResult {
                        Text(fileOpResult)
                            .font(.caption)
                            .foregroundStyle(.blue)
                    }
                }

                Section("管理操作") {
                    Button("查看关联任务") { onNavigateRoute("tasks") }
                    if isLocallyAuthorized {
                        Button("取消配对并撤销凭据", role: .destructive) {
                            showUnpairConfirm = true
                        }
                    }
                    Button("删除设备", role: .destructive) {
                        showDeleteConfirm = true
                    }
                }
            }
            .navigationTitle("设备详情")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
            .alert("确认取消配对", isPresented: $showUnpairConfirm) {
                Button("取消", role: .cancel) { }
                Button("取消配对并撤销凭据", role: .destructive) {
                    unpairAndRevokeDevice()
                }
            } message: {
                Text("确定要取消与「\(device.displayName)」的配对吗？本地与远端配对凭据将被安全撤销。")
            }
            .alert("确认删除设备", isPresented: $showDeleteConfirm) {
                Button("取消", role: .cancel) { }
                Button("删除设备", role: .destructive) {
                    deleteDeviceRecord(device: device)
                    dismiss()
                }
            } message: {
                Text("确定删除设备「\(device.displayName)」吗？只删这条设备记录：这台设备上的 Agent 记录、项目和会话都不删，之后可以重新添加这台设备。")
            }
            .onAppear {
                probeRealConnection()
            }
        }
    }

    private var isLocallyAuthorized: Bool {
        let devId = device.deviceId ?? device.agentId
        return HandIdentityManager.getPeerPublicKey(deviceId: devId) != nil || device.authRef != nil
    }

    private func unpairAndRevokeDevice() {
        let devId = device.deviceId ?? device.agentId
        if let raw = device.baseUrl, !raw.isEmpty {
            let (host, port) = parseHostAndPort(raw)
            Task {
                _ = try? await HandClient.shared.unpairDevice(host: host, port: port, targetDeviceId: devId)
            }
        }
        HandIdentityManager.deleteDeviceCredentials(deviceId: devId)
        device.memberConnectionState = "NOT_CONNECTED"
        device.authRef = nil
        device.lastTestSummary = "已取消配对，本地与远端凭据均已安全撤销"
        try? LocalData.shared.agents.saveAgent(device)
        if let all = try? LocalData.shared.agents.listAgents() {
            for var child in all where child.kind == .device && child.deviceId == devId && child.agentId != devId {
                child.memberConnectionState = "NOT_CONNECTED"
                try? LocalData.shared.agents.saveAgent(child)
            }
        }
        onUpdated()
        probeResult = "已成功取消配对，Keychain 凭据已安全撤销。"
    }

    private func deleteDeviceRecord(device: AgentRecord) {
        isDeleted = true
        let devId = device.deviceId ?? device.agentId
        if let raw = device.baseUrl, !raw.isEmpty {
            let (host, port) = parseHostAndPort(raw)
            Task {
                _ = try? await HandClient.shared.unpairDevice(host: host, port: port, targetDeviceId: devId)
            }
        }
        HandIdentityManager.deleteDeviceCredentials(deviceId: devId)
        if device.agentId != devId {
            HandIdentityManager.deleteDeviceCredentials(deviceId: device.agentId)
        }
        try? LocalData.shared.agents.deleteDeviceCascade(deviceId: devId)
        if device.agentId != devId {
            try? LocalData.shared.agents.deleteAgent(agentId: device.agentId)
        }
        // 核心规则：删设备只删设备、删 Agent 只删 Agent，互不级联，不影响任务、项目或会话
        onUpdated()
    }

    private func probeRealConnection() {
        guard !isDeleted else { return }
        guard let raw = device.baseUrl, !raw.isEmpty else { return }
        let (host, port) = parseHostAndPort(raw)

        isProbing = true
        probeResult = nil

        Task {
            do {
                let hello = try await HandClient.shared.probeHello(host: host, port: port)
                await MainActor.run {
                    guard !self.isDeleted else { return }
                    guard (try? LocalData.shared.agents.getAgent(agentId: self.device.agentId)) != nil else { return }
                    self.isProbing = false
                    self.remoteHello = hello
                    self.device.memberConnectionState = hello.clientStatus == "APPROVED" ? "CONNECTED" : "PENDING"
                    self.device.lastTestSummary = "已连接（在线）"
                    try? LocalData.shared.agents.saveAgent(self.device)
                    // 同步属于该设备的所有子 Agent 地址与连接状态
                    if let all = try? LocalData.shared.agents.listAgents() {
                        let devId = self.device.deviceId ?? self.device.agentId
                        for var child in all where child.kind == .device && child.deviceId == devId && child.agentId != devId {
                            child.baseUrl = self.device.baseUrl
                            child.memberConnectionState = self.device.memberConnectionState
                            try? LocalData.shared.agents.saveAgent(child)
                        }
                    }
                    self.probeResult = "连接成功！\(hello.displayName) 在线。"
                    onUpdated()
                }
            } catch {
                await MainActor.run {
                    guard !self.isDeleted else { return }
                    guard (try? LocalData.shared.agents.getAgent(agentId: self.device.agentId)) != nil else { return }
                    self.isProbing = false
                    self.device.memberConnectionState = "NOT_CONNECTED"
                    self.device.lastTestSummary = "连接失败：\(error.localizedDescription)"
                    try? LocalData.shared.agents.saveAgent(self.device)
                    self.probeResult = "连接失败：\(error.localizedDescription)"
                    onUpdated()
                }
            }
        }
    }

    private func authorizeDirectory() {
        guard let raw = device.baseUrl, !raw.isEmpty, let targetFolder = device.deviceFolder, !targetFolder.isEmpty else { return }
        let (host, port) = parseHostAndPort(raw)
        let devId = device.deviceId ?? device.agentId

        isAuthorizingFolder = true
        authResult = nil

        Task {
            do {
                _ = try await HandClient.shared.authorizeProjectFolder(host: host, port: port, targetDeviceId: devId, folder: targetFolder)
                await MainActor.run {
                    self.isAuthorizingFolder = false
                    self.authResult = "授权目录成功！远端工作区已激活：\(targetFolder)"
                    onUpdated()
                }
            } catch {
                await MainActor.run {
                    self.isAuthorizingFolder = false
                    self.authResult = "授权目录失败：\(error.localizedDescription)"
                }
            }
        }
    }

    private func loadDirectoryFiles() {
        guard let raw = device.baseUrl, !raw.isEmpty, let targetFolder = device.deviceFolder, !targetFolder.isEmpty else { return }
        let (host, port) = parseHostAndPort(raw)
        let devId = device.deviceId ?? device.agentId

        isListingFiles = true
        fileOpResult = nil

        Task {
            do {
                let items = try await HandClient.shared.listDirectory(host: host, port: port, targetDeviceId: devId, folder: targetFolder)
                await MainActor.run {
                    self.isListingFiles = false
                    self.directoryFiles = items
                    self.fileOpResult = "成功读取 \(items.count) 个文件/子目录。"
                }
            } catch {
                await MainActor.run {
                    self.isListingFiles = false
                    self.fileOpResult = "读取目录失败：\(error.localizedDescription)"
                }
            }
        }
    }

    private func parseHostAndPort(_ raw: String) -> (String, Int) {
        let clean = raw.replacingOccurrences(of: "http://", with: "").replacingOccurrences(of: "https://", with: "")
        let parts = clean.split(separator: ":")
        if parts.count >= 2, let p = Int(parts[1]) {
            return (String(parts[0]), p)
        }
        return (clean, 18743)
    }

    private func statusBadge(for state: String) -> some View {
        let (text, color): (String, Color) = {
            switch state {
            case "CONNECTED": return ("已连接", .green)
            case "PENDING": return ("待配对", .orange)
            case "DENIED": return ("权限不足", .red)
            default: return ("未连接", .secondary)
            }
        }()
        return Text(text)
            .font(.caption2.weight(.bold))
            .foregroundStyle(color)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(color.opacity(0.12), in: Capsule())
    }
}
