// WhereverCode Agent — iOS
//
// Settings section pages for the unified settings home
// (unification 212, SPEC §1/§3). Each view mirrors the content of
// the Android settings block of the same name (Android's old long
// form cut into blocks by `SettingsSections`):
//
// - 仓库授权 mirrors Android's REPOS block: GitHub / GitLab /
//   Gitee authorization rows with the same texts, the same
//   已配/未配 status line and 保存授权 / 清除授权 buttons. Tokens
//   are stored only in the Keychain (Android: Keystore), never
//   shown again, exactly like the platform credential.
// - 连接管理 mirrors Android's CONNECTIONS section (REPOS +
//   TOKEN blocks): the repo authorization rows plus a platform
//   connection status summary (SPEC §3).
// - 跨端同步 / 工作区 / 自主度 mirror Android's HAND / WORKSPACE /
//   AUTONOMY blocks' texts. Where iOS has no backing store for a
//   block's controls yet, the page says so plainly instead of
//   rendering fake toggles (honest-state rule).
// - 开发者选项 mirrors Android's DEV block's 本机信息 card with
//   values read live from the device and the bundle.
// - 关于 reuses the former SettingsView 关于 section content
//   verbatim.

import SwiftUI
import UIKit

/// "1.3 (4)" — read from the bundle, never hardcoded, so the row
/// can never drift from the shipped build. (Same helper the old
/// SettingsView 关于 section used.)
enum SettingsVersionInfo {
    static var versionString: String {
        let info = Bundle.main.infoDictionary
        let short = info?["CFBundleShortVersionString"] as? String ?? "?"
        let build = info?["CFBundleVersion"] as? String ?? "?"
        return "\(short) (\(build))"
    }
}

// MARK: - 仓库授权

/// One code-host authorization block, shaped exactly like
/// Android's REPOS rows: description, 「<标题>：已配/未配」 status,
/// a password field and 保存授权 / 清除授权. The token goes to
/// the Keychain under its own account and is never displayed.
private struct RepoAuthorizationSection: View {
    let title: String
    let description: String
    let hint: String
    let store: KeychainStore

    @State private var tokenText = ""
    @State private var isSaved = false
    @State private var errorMessage: String?

    var body: some View {
        Section {
            Text(description)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text("\(title)：\(isSaved ? "已配" : "未配")")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            SecureField(hint, text: $tokenText)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            HStack {
                Button("保存授权") {
                    save()
                }
                .disabled(tokenText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                Spacer()
                Button("清除授权", role: .destructive) {
                    clear()
                }
                .disabled(!isSaved)
            }
            if let errorMessage {
                Text(errorMessage)
                    .font(.caption)
                    .foregroundStyle(.red)
            }
        } header: { Text(title).textCase(nil) }
        .onAppear { isSaved = store.hasToken }
    }

    private func save() {
        let token = tokenText.trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            try store.saveToken(token)
            tokenText = ""
            isSaved = true
            errorMessage = nil
        } catch {
            errorMessage = (error as? KeychainError)?.message ?? "保存授权失败。"
        }
    }

    private func clear() {
        do {
            try store.deleteToken()
            isSaved = false
            errorMessage = nil
        } catch {
            errorMessage = (error as? KeychainError)?.message ?? "清除授权失败。"
        }
    }
}

/// The three REPOS blocks, shared by the 仓库授权 page and the
/// 连接管理 page (Android's CONNECTIONS section contains REPOS).
private struct RepoAuthorizationSections: View {
    var body: some View {
        RepoAuthorizationSection(
            title: "GitHub 授权",
            description: "GitHub 家项目的读/写、推分支、开 PR 都用它。授权只存本机 Keychain。GitHub 网页：头像 → Settings → Developer settings → Personal access tokens，新建一个能读该仓库的 token（repo 权限），粘贴进来。",
            hint: "GitHub 授权（token），如 ghp_…",
            store: KeychainStore(service: "wherevercode.agent", account: "github-token")
        )
        RepoAuthorizationSection(
            title: "GitLab 授权",
            description: "GitLab 家项目的读/写、推分支、开合并请求都用它。授权只存本机 Keychain。GitLab 网页：头像 → Preferences → Access Tokens，新建一个 token（读仓库用 read_api，写/开合并请求用 api），粘贴进来。",
            hint: "GitLab 授权（token）",
            store: KeychainStore(service: "wherevercode.agent", account: "gitlab-token")
        )
        RepoAuthorizationSection(
            title: "Gitee 授权",
            description: "Gitee 家项目的读/写、推分支、开 PR 都用它。授权只存本机 Keychain。Gitee 网页：头像 → 个人设置 → 安全设置 → 私人令牌，新建一个能读写仓库的 token（projects 权限），粘贴进来。",
            hint: "Gitee 授权（token）",
            store: KeychainStore(service: "wherevercode.agent", account: "gitee-token")
        )
    }
}

struct RepoAuthorizationView: View {
    var body: some View {
        Form {
            RepoAuthorizationSections()
        }
        .navigationTitle("仓库授权")
    }
}

// MARK: - 连接管理

/// Android's CONNECTIONS section = REPOS + TOKEN blocks; here:
/// a platform connection status summary (SPEC §3) followed by
/// the same repo authorization blocks as the 仓库授权 page.
struct ConnectionManagementView: View {
    @ObservedObject private var appState: AppState

    init(appState: AppState) {
        self.appState = appState
    }

    var body: some View {
        Form {
            Section {
                LabeledContent("平台地址", value: platformAddressText)
                LabeledContent("地址状态", value: platformAddressStateText)
                LabeledContent("平台状态", value: appState.hasToken ? "平台已配置（凭证已存本机）" : "平台未配置（本地优先）")
                LabeledContent("连接状态", value: appState.connectionStatus.displayText)
                LabeledContent("凭证状态", value: appState.hasToken ? "已保存" : "未保存")
                NavigationLink {
                    SettingsView(appState: appState)
                } label: {
                    Text("平台连接与令牌签发")
                }
            } header: { Text("平台连接状态").textCase(nil) }
            RepoAuthorizationSections()
        }
        .navigationTitle("连接管理")
    }

    private var platformAddressText: String {
        let address = appState.settings.platformBaseURLString
        return address.isEmpty ? "未配置" : address
    }

    private var platformAddressStateText: String {
        appState.settings.platformBaseURLString.isEmpty ? "未配置" : "已配置"
    }
}

// MARK: - 跨端同步

/// Android's HAND block: Hand auto-sync after app updates. iOS
/// has no Hand auto-sync setting store yet, so the page carries
/// Android's explanation text and says plainly that the switch
/// itself is not wired on iOS — no fake toggle.
struct CrossDeviceSyncView: View {
    var body: some View {
        Form {
            Section {
                LabeledContent("自动同步 Hand", value: "未接入")
                Text("App 更新后，已连接设备的 Hand 还是旧版，App 发新东西旧 Hand 会拒收。设备卡上有 Hand 版本与“更新 Hand”按钮；更新前先备份旧版，失败自动回滚。旧版 Hand（版本未知）不支持自更新，首次部署请走一键安装。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text("iOS 尚未接入 Hand 自动同步开关与「检查全部设备更新」：本页先与安卓同文案说明 Hand 更新机制，设置项接入后在这里操作。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } header: { Text("Hand 同步").textCase(nil) }
        }
        .navigationTitle("跨端同步")
    }
}

// MARK: - 工作区

/// Android's WORKSPACE block is a single status card for the
/// local workspace; on iOS projects live in the app sandbox and
/// each project's location is recorded on the project itself.
struct WorkspaceSettingsView: View {
    var body: some View {
        Form {
            Section {
                Text("本机工作区已配置")
                Text("iOS 的项目保存在本机沙盒内：新建项目时从本机文件夹选定位置（默认在 Documents 下以项目名建夹），每个项目的位置记录在项目详情的「工作区位置」里。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } header: { Text("工作区").textCase(nil) }
        }
        .navigationTitle("工作区")
    }
}

// MARK: - 自主度

/// Android's AUTONOMY block: mode （监督 / 全权） plus initiative,
/// execution-permission and UI-freedom pickers backed by Android
/// stores. iOS has no autonomy settings store yet, so the page
/// carries Android's mode explanation verbatim and says plainly
/// that the pickers are not wired on iOS — no fake rows.
struct AutonomySettingsView: View {
    var body: some View {
        Form {
            Section {
                Text("监督模式：plan 批准、派发确认、审查 verdict 每一道都等你点；全权模式：大脑总管自动批、自动派、自动采纳，返工上限 2 轮不变，超限升级给你。自动决定记在任务详情页“自动决定”里，可查可打回。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text("iOS 尚未接入自主度设置（模式 / 主动性 / 执行权限 / 界面自由度）：本页先与安卓同文案说明两种模式，设置项接入后在这里切换。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } header: { Text("自主度（大脑总管）").textCase(nil) }
        }
        .navigationTitle("自主度")
    }
}

// MARK: - 开发者选项

/// Android's DEV block opens with a 本机信息 card; the values
/// here are read live from the device and the bundle.
struct DeveloperOptionsView: View {
    var body: some View {
        Form {
            Section {
                LabeledContent("设备型号", value: UIDevice.current.model)
                LabeledContent("系统版本", value: "\(UIDevice.current.systemName) \(UIDevice.current.systemVersion)")
                LabeledContent("App 版本", value: SettingsVersionInfo.versionString)
                LabeledContent("Bundle ID", value: "wherevercode.agent")
            } header: { Text("本机信息").textCase(nil) }
        }
        .navigationTitle("开发者选项")
    }
}

// MARK: - 关于

/// The former SettingsView 关于 section, verbatim, as its own
/// page (Android's ABOUT block is likewise its own section).
struct AboutSettingsView: View {
    var body: some View {
        Form {
            Section {
                LabeledContent("版本", value: SettingsVersionInfo.versionString)
                LabeledContent("Bundle ID", value: "wherevercode.agent")
                LabeledContent("最低系统", value: "iOS 17.0")
                Text("可用：团队频道（读 / 发 / 实时推送）、我的任务（领取 / 汇报 / 心跳 / 交活 / 放手）、设置与连接状态、大脑管理（内置大脑种子、通用模型 API、Key 存 Keychain、模型列表实时拉取、设当前大脑）、平台令牌生成（issue_agent_token，与安卓同接口；需服务端已部署该工具且本机凭证为 owner，未部署时点生成会如实报错）。阻塞中：审批、暂停 / 恢复 / 停止、改目标与设备管理——服务端无此接口，入口处有明确标注。大脑对话可用（从侧边栏「对话」进入，与当前大脑聊）；单兵对话尚未接入，入口处有明确标注。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } header: { Text("关于").textCase(nil) }
        }
        .navigationTitle("关于")
    }
}
