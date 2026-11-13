// WhereverCode Agent — iOS phase-2 screenshot evidence
//
// Renders the five main pages in the SIMULATOR test host and attaches
// PNG snapshots to the test result bundle (CI exports them as the
// "ios-screenshots-simulator" artifact). The pages are the app's
// real SwiftUI views; the channel/task data shown is sample fixture
// data constructed HERE, inside the test target only — the shipped
// app contains no fixture data and these renders are labelled as
// simulator evidence, never as real-device or live-platform proof.

import SwiftUI
import UIKit
import XCTest
@testable import WhereverCodeAgent

@MainActor
final class ScreenshotRenderTests: XCTestCase {

    // MARK: - Sample fixtures (test-only, visibly sample content)

    private let sampleChannels = [
        ChannelInfo(
            id: "demo-1",
            name: "示例频道",
            participants: ["示例 Agent", "示例成员"],
            lastMessageText: "这是一条用于界面截图的示例消息。"
        ),
    ]

    private let sampleMessages = [
        ChannelMessage(id: "s1", agentId: "agent-a", agentName: "示例 Agent",
                       text: "这是一条用于界面截图的示例消息。", createdAtSeconds: 1_700_000_000),
        ChannelMessage(id: "s2", agentId: "me", agentName: "我",
                       text: "收到，这条也是截图示例。", createdAtSeconds: 1_700_000_060),
    ]

    private let sampleTasks = [
        AgentTask(id: "task-demo-1", title: "示例任务：整理截图证据",
                  detail: "这是任务详情的截图示例内容。", status: "claimed", version: 3),
        AgentTask(id: "task-demo-2", title: "示例任务：复核频道消息",
                  detail: "", status: "queued", version: 1),
    ]

    // MARK: - Pages

    func testScreenshotChannelList() async {
        let image = await snapshot(
            NavigationStack {
                ChannelListContentView(channels: sampleChannels, loadError: nil, onRetry: {}, onRefresh: {})
                    .navigationTitle("对话")
            }
        )
        attach(image, name: "page-01-channel-list")
    }

    func testScreenshotChannelChat() async {
        let image = await snapshot(
            ChannelContentView(
                messages: sampleMessages,
                connectionState: .live,
                connectionError: nil,
                isLoadingHistory: false,
                composerText: .constant(""),
                isSending: false,
                onSend: {},
                onRefresh: {}
            )
        )
        attach(image, name: "page-02-channel-chat")
    }

    func testScreenshotTaskList() async {
        let image = await snapshot(
            NavigationStack {
                TaskListContentView(tasks: sampleTasks, loadError: nil, onRetry: {}, onRefresh: {})
                    .navigationTitle("任务")
            }
        )
        attach(image, name: "page-03-task-list")
    }

    func testScreenshotTaskDetail() async {
        let image = await snapshot(
            NavigationStack {
                TaskDetailView(task: sampleTasks[0])
            }
            .environmentObject(AppState())
        )
        attach(image, name: "page-04-task-detail")
    }

    func testScreenshotSettings() async {
        let image = await snapshot(
            SettingsView().environmentObject(AppState())
        )
        attach(image, name: "page-05-settings")
    }

    func testScreenshotChannelPicker() async {
        // The Settings channel picker: channels are chosen by
        // tapping; the checkmark marks the current selection. Two
        // sample rows, the first one selected.
        let pickerChannels = sampleChannels + [
            ChannelInfo(
                id: "demo-2",
                name: "示例频道二",
                participants: ["示例 Agent"],
                lastMessageText: nil
            ),
        ]
        let image = await snapshot(
            NavigationStack {
                List {
                    Section("团队频道") {
                        ChannelPickerContentView(
                            channels: pickerChannels,
                            selectedChannelID: "demo-1",
                            onSelect: { _ in }
                        )
                    }
                }
                .navigationTitle("设置 · 选频道")
            }
        )
        attach(image, name: "page-07-channel-picker")
    }

    func testScreenshotDevices() async {
        let image = await snapshot(DevicesView())
        attach(image, name: "page-06-devices")
    }

    // MARK: - Snapshot helper

    /// Host the view in a real window on the simulator and draw it,
    /// so List/Form content renders exactly as on screen. The window
    /// MUST join the app's foreground scene — a scene-less window
    /// draws pure black (that is how the first CI run's renders came
    /// out blank), so `assertNotBlank` guards the evidence as well.
    private func snapshot<V: View>(_ view: V, size: CGSize = CGSize(width: 393, height: 852)) async -> UIImage {
        let host = UIHostingController(rootView: view)
        let window = UIWindow(frame: CGRect(origin: .zero, size: size))
        if let scene = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene })
            .first(where: { $0.activationState == .foregroundActive || $0.activationState == .foregroundInactive }) {
            window.windowScene = scene
        }
        window.rootViewController = host
        window.makeKeyAndVisible()
        host.view.frame = window.bounds
        // Let onAppear/.task settle and the first layout pass finish.
        try? await Task.sleep(nanoseconds: 500_000_000)
        host.view.setNeedsLayout()
        host.view.layoutIfNeeded()
        let renderer = UIGraphicsImageRenderer(bounds: window.bounds)
        let image = renderer.image { _ in
            window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
        }
        window.isHidden = true
        assertNotBlank(image)
        return image
    }

    /// A rendered page is never a single flat colour. Downscale to a
    /// tiny bitmap and require several distinct pixel values, so a
    /// broken (blank/black) render fails the test loudly instead of
    /// silently producing useless screenshot evidence.
    private func assertNotBlank(_ image: UIImage) {
        let side = 16
        var pixels = [UInt8](repeating: 0, count: side * side)
        let context = CGContext(
            data: &pixels, width: side, height: side,
            bitsPerComponent: 8, bytesPerRow: side,
            space: CGColorSpaceCreateDeviceGray(),
            bitmapInfo: CGImageAlphaInfo.none.rawValue
        )
        guard let context, let cgImage = image.cgImage else {
            XCTFail("screenshot could not be sampled")
            return
        }
        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: side, height: side))
        XCTAssertGreaterThan(Set(pixels).count, 4, "screenshot looks blank — the render produced a flat image")
    }

    private func attach(_ image: UIImage, name: String) {
        let attachment = XCTAttachment(image: image)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
