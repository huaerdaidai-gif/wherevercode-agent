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
//    its own Settings UI — the platform credential field inside
//    the 平台连接 section, where Android's platform-agent form
//    keeps the same field) with an unreachable https address and
//    a dummy token typed by the test — not a real credential.
//    Every page must show the REAL network error and a retry
//    path, and Settings must show 连接失败 with the real error
//    text. The unreachable address is the loopback discard port
//    (127.0.0.1:9): the connection is refused instantly, so the
//    error is a real network error that does not depend on how
//    slowly the runner's DNS resolver gives up on an
//    unresolvable name (an earlier .invalid run lost the race
//    against the client's own 60 s request timeout).
// 4. The platform-not-connected home is a page, not a gate: on
//    first launch the chat tab shows the conversation home with
//    its 平台连接 card and the channel setup hint, and every tab
//    still shows its own page.
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

        XCTAssertFalse(app.tabBars.firstMatch.exists)
        XCTAssertTrue(app.descendants(matching: .any)["chat.input"].waitForExistence(timeout: 15))
        snap(app, "ui-01-chat-home")
        app.buttons["新对话"].tap()
        app.buttons["搜索会话"].tap()
        XCTAssertTrue(app.navigationBars["会话"].waitForExistence(timeout: 10))
        snap(app, "ui-01b-history")
        app.buttons["完成"].tap()
        navigate(app, to: "settings")
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
        replaceText(in: urlField, with: "https://127.0.0.1:9")
        XCTAssertEqual(urlField.value as? String, "https://127.0.0.1:9",
                       "Address field must contain exactly the fake address after replacement")
        app.buttons["settings.saveAddressChannel"].tap()

        // --- Paste the dummy token into the credential field of
        // the 平台连接 section (the platform-agent form's field —
        // in the form itself since the Android-parity restructure,
        // not behind a collapsed "advanced" disclosure). The field
        // is queried by its own accessibility identifier: the
        // phase-2 placeholder query existed only because the old
        // DisclosureGroup's identifier propagated to (and shadowed)
        // its children; with the group gone the identifier is the
        // stable route — the placeholder predicate matched nothing
        // for the always-visible field in run 43438600602 (the
        // field renders fine; see the page-05 render).
        scrollToTop(app)
        let tokenField = app.secureTextFields["settings.tokenField"]
        reveal(tokenField, in: app)
        XCTAssertTrue(tokenField.waitForExistence(timeout: 15))
        tokenField.tap()
        tokenField.typeText("uitest-dummy-token")
        let keyboardSave = app.buttons["settings.saveTokenKeyboard"]
        if keyboardSave.waitForExistence(timeout: 3) {
            keyboardSave.tap()
        } else {
            let saveToken = app.buttons["settings.saveToken"]
            reveal(saveToken, in: app)
            saveToken.tap()
        }

        // Settings must report the connection failure with real text.
        let failedLabel = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS %@", "连接失败")
        ).firstMatch
        // The 连接状态 section sits below the fold and a SwiftUI
        // List only materializes rows near the viewport (the same
        // behaviour that hid the credential field), so scroll the
        // section into view while waiting — an existence wait
        // alone never sees it from the top of the page. The
        // keyboard is deliberately NOT dismissed before this
        // search: the press-and-drag dismissal gesture gets the
        // app process terminated by the test framework in the
        // simulator (SIGTERM, no crash report; runs
        // 43446321473–43456659182), after which the relaunched app
        // lands on the chat tab and every Settings assertion
        // searches the wrong page. With plain swipes only, the
        // save → failure-label path is stable. Dismissal stays
        // after the assertion, where it always was.
        var revealAttempts = 0
        while !failedLabel.exists && revealAttempts < 30 {
            app.swipeUp()
            revealAttempts += 1
            if failedLabel.waitForExistence(timeout: 1) { break }
        }
        XCTAssertTrue(failedLabel.exists,
                      "Settings must show 连接失败 with the real error after save")
        snap(app, "ui-03-settings-connection-failed")

        // The keyboard is still up from the token field and covers
        // the tab bar; dismiss it before tapping tabs, or the taps
        // land on the keyboard and the selection never changes.
        dismissKeyboard(app)

        // --- Channel list: real load error + retry, not a blank page.
        navigate(app, to: "channels")
        let retryInChat = app.buttons["重试"]
        XCTAssertTrue(retryInChat.waitForExistence(timeout: 60),
                      "Channel list must surface the real load error with a retry button")
        snap(app, "ui-04-channel-list-error")

        // --- Task list: same real error surfacing.
        navigate(app, to: "tasks")
        snap(app, "ui-05a-local-tasks")
        app.buttons["平台任务"].tap()
        XCTAssertTrue(app.buttons["重试"].waitForExistence(timeout: 60),
                      "Task list must surface the real load error with a retry button")
        snap(app, "ui-05-task-list-error")

        // --- Devices: honest blocked page.
        navigate(app, to: "devices")
        let blockedText = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS %@", "服务端无此接口")
        ).firstMatch
        XCTAssertTrue(blockedText.waitForExistence(timeout: 10))
        snap(app, "ui-06-devices-blocked")

        // --- Selection and content stay in sync on the way back.
        navigate(app, to: "settings")
        XCTAssertTrue(urlField.exists)
        navigate(app, to: "channels")
        XCTAssertTrue(app.buttons["重试"].exists)
    }

    private func navigate(_ app: XCUIApplication, to destination: String) {
        let menu = app.buttons["navigation.menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 10))
        menu.tap()
        let row = app.buttons["drawer_\(destination)"]
        XCTAssertTrue(row.waitForExistence(timeout: 10))
        if destination == "tasks" { snap(app, "ui-drawer") }
        row.tap()
    }

    func testLocalWorkspacesRemainAvailableWithoutPlatform() {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.descendants(matching: .any)["chat.input"].waitForExistence(timeout: 15))
        for destination in ["projects", "agents", "console", "tasks"] {
            navigate(app, to: destination)
            snap(app, "ui-local-\(destination)")
        }
        navigate(app, to: "chat")
        app.buttons["新对话"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["chat.input"].exists)
        XCTAssertFalse(app.tabBars.firstMatch.exists)
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
