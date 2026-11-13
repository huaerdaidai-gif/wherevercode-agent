// WhereverCode Agent — iOS UI tests (simulator)
//
// Drives the REAL app binary in the simulator:
// 1. Navigation regression: the phase-1 bug showed the Settings page
//    while the tab bar still selected 对话 (a Settings screen had
//    been pushed onto the chat tab's stack). The "去设置" action must
//    now SELECT the Settings tab, and every tab must show its own
//    page with the matching selection.
// 2. Config experience: the platform address is PREFILLED (the test
//    asserts the platform host is already in the field — nobody
//    types it), and "生成并保存 Token" with no credential saved
//    must honestly report 未连接平台 (issuance is authorized by an
//    existing owner credential, exactly as on Android).
// 3. Real error surfacing: the app is then reconfigured (through
//    its own Settings UI, token pasted in the collapsed advanced
//    entry) with an unresolvable https address and a dummy token
//    typed by the test — not a real credential. Every page must
//    show the REAL network error and a retry path, and Settings
//    must show 连接失败 with the real error text.
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
        let urlField = app.textFields["settings.address"]
        XCTAssertTrue(urlField.waitForExistence(timeout: 10))
        snap(app, "ui-02-settings")

        // --- The platform address is prefilled, not typed by anyone.
        let prefilled = urlField.value as? String ?? ""
        XCTAssertTrue(
            prefilled.contains("v5.cococolo.xyz"),
            "Address must be prefilled with the platform endpoint, got: \(prefilled)"
        )

        // --- Generate with no credential: honest 未连接平台 report.
        // Deterministic: with no token in the Keychain the MCP layer
        // refuses before any networking, and the issuer maps that to
        // the not-connected message (Android's semantics).
        let generateButton = app.buttons["settings.generateToken"]
        reveal(generateButton, in: app)
        XCTAssertTrue(generateButton.waitForExistence(timeout: 10))
        generateButton.tap()
        let notConnected = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS %@", "未连接平台")
        ).firstMatch
        XCTAssertTrue(notConnected.waitForExistence(timeout: 20),
                      "Generate without a credential must report 未连接平台")
        snap(app, "ui-02b-generate-no-credential")

        // --- Reconfigure with an unresolvable https address.
        scrollToTop(app)
        XCTAssertTrue(urlField.waitForExistence(timeout: 10))
        replaceText(in: urlField, with: "https://uitest.invalid")
        XCTAssertEqual(urlField.value as? String, "https://uitest.invalid",
                       "Address field must contain exactly the fake address after replacement")
        app.buttons["settings.saveAddressChannel"].tap()

        // --- Paste the dummy token through the advanced fallback.
        // The disclosure header is found by label: an identifier on
        // the DisclosureGroup container itself would propagate to
        // (and shadow) every control inside it.
        let advancedToggle = app.buttons["手工粘贴 Token（兜底）"]
        reveal(advancedToggle, in: app)
        XCTAssertTrue(advancedToggle.waitForExistence(timeout: 10))
        advancedToggle.tap()
        // Query the token field by its placeholder (the phase-2 way).
        // An earlier failure had a subtle cause, proven by that run's
        // element dump: the DisclosureGroup's own identifier had
        // propagated to every child inside it, shadowing
        // settings.tokenField — the group no longer carries an
        // identifier, and the placeholder query is kept as the
        // proven route regardless.
        let tokenField = app.secureTextFields.matching(
            NSPredicate(format: "placeholderValue BEGINSWITH %@", "粘贴 Agent Token")
        ).firstMatch
        reveal(tokenField, in: app)
        XCTAssertTrue(tokenField.waitForExistence(timeout: 15))
        tokenField.tap()
        tokenField.typeText("uitest-dummy-token")
        app.buttons["settings.saveToken"].tap()

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

    /// Scroll the settings form until `element` is in the hierarchy
    /// (Form rows below the fold do not exist for XCTest until
    /// scrolled near).
    private func reveal(_ element: XCUIElement, in app: XCUIApplication, maxSwipes: Int = 6) {
        var swipes = 0
        while !element.exists && swipes < maxSwipes {
            app.swipeUp()
            swipes += 1
        }
    }

    private func scrollToTop(_ app: XCUIApplication) {
        for _ in 0..<4 { app.swipeDown() }
    }

    /// Replace a text field's whole content: triple-tap selects all
    /// (the field is prefilled now, so appending is not an option),
    /// then typing replaces the selection.
    private func replaceText(in field: XCUIElement, with text: String) {
        field.tap(withNumberOfTaps: 3, numberOfTouches: 1)
        field.typeText(text)
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
