// WhereverCode Agent — iOS phase-2 UI tests (simulator)
//
// Drives the REAL app binary in the simulator:
// 1. Navigation regression: the phase-1 bug showed the Settings page
//    while the tab bar still selected 对话 (a Settings screen had
//    been pushed onto the chat tab's stack). The "去设置" action must
//    now SELECT the Settings tab, and every tab must show its own
//    page with the matching selection.
// 2. Real error surfacing: the app is configured (through its own
//    Settings UI) with an unresolvable https address and a dummy
//    token typed by the test — not a real credential. Every page
//    must then show the REAL network error and a retry path, and
//    Settings must show 连接失败 with the real error text.
// Screenshots are attached at each stage and exported by CI as the
// "ios-screenshots-simulator" artifact.

import XCTest

final class WhereverCodeAgentUITests: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testTabNavigationAndRealErrorStates() {
        let app = XCUIApplication()
        app.launch()

        let tabBar = app.tabBars.firstMatch
        XCTAssertTrue(tabBar.waitForExistence(timeout: 15))
        let chatTab = tabBar.buttons["对话"]
        let tasksTab = tabBar.buttons["任务"]
        let devicesTab = tabBar.buttons["设备"]
        let settingsTab = tabBar.buttons["设置"]
        XCTAssertTrue(chatTab.exists && tasksTab.exists && devicesTab.exists && settingsTab.exists)
        XCTAssertTrue(chatTab.isSelected)
        snap(app, "ui-01-chat-home")

        // --- Navigation regression: 去设置 must select the Settings tab.
        let goSettings = app.buttons["去设置"]
        if goSettings.waitForExistence(timeout: 10) {
            goSettings.tap()
        } else {
            settingsTab.tap()
        }
        XCTAssertTrue(settingsTab.isSelected, "Settings TAB must be selected after 去设置")
        let urlField = app.textFields.matching(
            NSPredicate(format: "placeholderValue BEGINSWITH %@", "平台地址")
        ).firstMatch
        XCTAssertTrue(urlField.waitForExistence(timeout: 10))
        snap(app, "ui-02-settings")

        // --- Configure an unresolvable https address + dummy token.
        // The channel id is only typed when the field is empty (a
        // default value may already be there; typing would append),
        // so the flow does not depend on inherited defaults.
        urlField.tap()
        urlField.typeText("https://uitest.invalid")
        let channelField = app.textFields.matching(
            NSPredicate(format: "placeholderValue BEGINSWITH %@", "频道")
        ).firstMatch
        XCTAssertTrue(channelField.waitForExistence(timeout: 10))
        if (channelField.value as? String) == "频道 ID" {
            channelField.tap()
            channelField.typeText("uitest-channel")
        }
        app.buttons["保存地址与频道"].tap()

        let tokenField = app.secureTextFields.matching(
            NSPredicate(format: "placeholderValue BEGINSWITH %@", "粘贴 Agent Token")
        ).firstMatch
        XCTAssertTrue(tokenField.waitForExistence(timeout: 10))
        tokenField.tap()
        tokenField.typeText("uitest-dummy-token")
        app.buttons["保存 Token"].tap()

        // Settings must report the connection failure with real text.
        let failedLabel = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS %@", "连接失败")
        ).firstMatch
        XCTAssertTrue(failedLabel.waitForExistence(timeout: 60),
                      "Settings must show 连接失败 with the real error after save")
        snap(app, "ui-03-settings-connection-failed")

        // The keyboard is still up from the token field and covers
        // the tab bar; dismiss it before tapping tabs, or the taps
        // land on the keyboard and the selection never changes.
        dismissKeyboard(app)

        // --- Channel list: real load error + retry, not a blank page.
        chatTab.tap()
        XCTAssertTrue(chatTab.isSelected)
        let retryInChat = app.buttons["重试"]
        XCTAssertTrue(retryInChat.waitForExistence(timeout: 60),
                      "Channel list must surface the real load error with a retry button")
        snap(app, "ui-04-channel-list-error")

        // --- Task list: same real error surfacing.
        tasksTab.tap()
        XCTAssertTrue(tasksTab.isSelected)
        XCTAssertTrue(app.buttons["重试"].waitForExistence(timeout: 60),
                      "Task list must surface the real load error with a retry button")
        snap(app, "ui-05-task-list-error")

        // --- Devices: honest blocked page.
        devicesTab.tap()
        XCTAssertTrue(devicesTab.isSelected)
        let blockedText = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS %@", "服务端无此接口")
        ).firstMatch
        XCTAssertTrue(blockedText.waitForExistence(timeout: 10))
        snap(app, "ui-06-devices-blocked")

        // --- Selection and content stay in sync on the way back.
        settingsTab.tap()
        XCTAssertTrue(settingsTab.isSelected)
        XCTAssertTrue(urlField.exists)
        chatTab.tap()
        XCTAssertTrue(chatTab.isSelected)
        XCTAssertTrue(app.buttons["重试"].exists)
    }

    /// Get rid of the software keyboard before tapping the tab bar:
    /// while it is up it COVERS the tab bar, so tab taps land on the
    /// keyboard (a plain swipe only scrolls the form — verified from
    /// a failing run's screen recording). First try the interactive
    /// drag-down dismissal; if the keyboard is still up, relaunch —
    /// settings and token persist, and the app reopens on the chat
    /// tab, which is where the test goes next anyway.
    private func dismissKeyboard(_ app: XCUIApplication) {
        guard app.keyboards.firstMatch.exists else { return }
        let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.40))
        let end = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.80))
        start.press(forDuration: 0.05, thenDragTo: end)
        let deadline = Date().addingTimeInterval(3)
        while app.keyboards.firstMatch.exists && Date() < deadline {
            Thread.sleep(forTimeInterval: 0.2)
        }
        if app.keyboards.firstMatch.exists {
            app.terminate()
            app.launch()
        }
    }

    private func snap(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
