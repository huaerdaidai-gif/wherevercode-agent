// DeviceCodeLoginView.swift
// 会员大脑设备码登录页（RFC 8628）：App 向厂商要设备码，用户在
// 浏览器里输码授权，App 按 interval 轮询换 token。流程与文案
// 口径与安卓 MainActivity 的设备码弹框一致（BrainLoginRows）：
// 登录中不要退出设置页，可取消；全程不用真实账号做自动化测试
// ——真机验证由 maintainer 用自己的账号走通一次再算交付。

import SwiftUI

public struct DeviceCodeLoginView: View {
    @ObservedObject var model: BrainSettingsModel
    let config: DeviceCodeConfig
    let manager: DeviceCodeManager
    @Environment(\.dismiss) private var dismiss

    private final class CancelBox {
        var cancelled = false
    }

    private enum Phase {
        case requesting
        case waiting(DeviceCodeLoginAttempt)
        case failed(String)
        case done
    }

    @State private var phase: Phase = .requesting
    @State private var cancelBox = CancelBox()

    public init(
        model: BrainSettingsModel,
        config: DeviceCodeConfig,
        manager: DeviceCodeManager
    ) {
        self.model = model
        self.config = config
        self.manager = manager
    }

    public var body: some View {
        NavigationStack {
            Form {
                switch phase {
                case .requesting:
                    Section {
                        HStack(spacing: 8) {
                            ProgressView()
                            Text("正在向\(config.displayName)要设备码…")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                    }
                case .waiting(let attempt):
                    Section {
                        Text(Self.loginDialogText(
                            displayName: config.displayName,
                            verificationUriComplete: attempt.response.verificationUriComplete,
                            userCode: attempt.response.userCode,
                            complianceNote: config.complianceNote,
                            experimentalNote: config.experimentalNote))
                            .font(.subheadline)
                            .textSelection(.enabled)
                        if let url = URL(string: attempt.response.verificationUriComplete) {
                            Link("打开浏览器", destination: url)
                        }
                        HStack(spacing: 8) {
                            ProgressView()
                            Text(Self.signingInNote)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                case .failed(let message):
                    Section {
                        Text("\(config.displayName)登录未完成（\(message)）。可以重试，或继续用设备 Agent。")
                            .font(.subheadline)
                            .foregroundStyle(.red)
                            .textSelection(.enabled)
                    }
                case .done:
                    Section {
                        Text("已登录\(config.displayName)。回到编辑页填好模型名，即可设为当前模型。")
                            .font(.subheadline)
                            .foregroundStyle(.green)
                    }
                }
            }
            .navigationTitle("会员登录（设备码）")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消登录") {
                        cancelBox.cancelled = true
                        dismiss()
                    }
                }
            }
            .task {
                await runLogin()
            }
        }
    }

    private func runLogin() async {
        let attempt: DeviceCodeLoginAttempt
        do {
            attempt = try await manager.beginLogin()
        } catch let error as BrainErrorInfo {
            phase = .failed(error.message)
            return
        } catch {
            phase = .failed("要设备码失败。")
            return
        }
        if cancelBox.cancelled { return }
        phase = .waiting(attempt)
        do {
            _ = try await manager.awaitAuthorization(
                attempt: attempt,
                isCancelled: { [cancelBox] in cancelBox.cancelled })
            model.reload()
            model.onChange()
            phase = .done
        } catch let error as BrainErrorInfo {
            if error.code == "device_code_cancelled" {
                dismiss()
            } else {
                phase = .failed(error.message)
            }
        } catch {
            phase = .failed("登录未完成。")
        }
    }

    /// 设备码登录进行中文案（安卓 BrainLoginRows 同口径）。
    public static let signingInNote =
        "正在等你在浏览器里完成授权：登录中不要退出设置页；点“取消登录”可随时停止。"

    /// 设备码弹框正文（安卓 BrainLoginRows.deviceCodeLoginDialogText 同口径）。
    public static func loginDialogText(
        displayName: String,
        verificationUriComplete: String,
        userCode: String,
        complianceNote: String,
        experimentalNote: String
    ) -> String {
        var text = "在浏览器打开下面的地址，登录你的 \(displayName) 账号并输入验证码，完成授权：\n\n"
        text += "验证码：\(userCode)\n"
        text += "地址：\(verificationUriComplete)\n"
        if !experimentalNote.isEmpty { text += "\n\(experimentalNote)" }
        if !complianceNote.isEmpty { text += "\n\(complianceNote)" }
        text += "\n授权完成后 App 会自动继续，不用再点什么；点“取消登录”可随时停止。"
        return text
    }
}
