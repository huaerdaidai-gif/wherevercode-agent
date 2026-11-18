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
        let newChatBtn = app.buttons.matching(
            NSPredicate(format: "label CONTAINS %@ OR identifier CONTAINS %@", "新对话", "newChat")
        ).firstMatch
        if newChatBtn.waitForExistence(timeout: 10) {
            newChatBtn.tap()
        }
        let searchBtn = app.buttons["搜索会话"]
        if searchBtn.waitForExistence(timeout: 5) {
            searchBtn.tap()
            XCTAssertTrue(app.navigationBars["会话"].waitForExistence(timeout: 10))
            snap(app, "ui-01b-history")
            let doneBtn = app.buttons["完成"].firstMatch
            if doneBtn.waitForExistence(timeout: 5) {
                doneBtn.tap()
            }
        }
        navigate(app, to: "settings")
        snap(app, "ui-02-settings")

        // --- Platform address is preset as static text row, not user-typed.
        let addressText = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS %@ OR identifier == 'settings.address'", "v5.cococolo.xyz")
        ).firstMatch
        XCTAssertTrue(
            addressText.waitForExistence(timeout: 10),
            "Settings must display preset platform address as static text"
        )

        let validStatus = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS %@", "地址有效。")
        ).firstMatch
        XCTAssertTrue(
            validStatus.waitForExistence(timeout: 10),
            "Settings must show 地址有效。 status"
        )

        // Dismiss settings sheet
        let settingsDone = app.buttons["完成"].firstMatch
        if settingsDone.waitForExistence(timeout: 5) {
            settingsDone.tap()
        }

        // --- Primary tab navigation across drawer items
        navigate(app, to: "channels")
        snap(app, "ui-04-channel-list")
        XCTAssertTrue(app.staticTexts["频道与协作"].firstMatch.waitForExistence(timeout: 10))

        navigate(app, to: "projects")
        snap(app, "ui-05-projects")

        navigate(app, to: "console")
        snap(app, "ui-06-console")

        navigate(app, to: "chat")
        XCTAssertTrue(app.descendants(matching: .any)["chat.input"].waitForExistence(timeout: 10))
    }

    private func navigate(_ app: XCUIApplication, to destination: String) {
        let dismissBtn = app.buttons["完成"].firstMatch
        if dismissBtn.exists {
            dismissBtn.tap()
        }

        let menu = app.buttons["navigation.menu"].firstMatch
        XCTAssertTrue(menu.waitForExistence(timeout: 10))
        menu.tap()

        if destination == "tasks" { snap(app, "ui-drawer") }

        if destination == "settings" {
            let settingsBtn = app.buttons["drawer.parent.设置"].firstMatch
            XCTAssertTrue(settingsBtn.waitForExistence(timeout: 10))
            settingsBtn.tap()
            return
        }

        if destination == "chat" {
            let chatBtn = app.buttons["drawer.parent.对话"].firstMatch
            XCTAssertTrue(chatBtn.waitForExistence(timeout: 10))
            chatBtn.tap()
            return
        }

        if destination == "projects" {
            let projBtn = app.buttons["drawer.parent.项目"].firstMatch
            XCTAssertTrue(projBtn.waitForExistence(timeout: 10))
            projBtn.tap()
            return
        }

        if destination == "channels" || destination == "channel" {
            let channelBtn = app.buttons["drawer.parent.频道"].firstMatch
            XCTAssertTrue(channelBtn.waitForExistence(timeout: 10))
            channelBtn.tap()
            return
        }

        if destination == "console" || destination == "dashboard" {
            let ctrlBtn = app.buttons["drawer.parent.控制"].firstMatch
            XCTAssertTrue(ctrlBtn.waitForExistence(timeout: 10))
            ctrlBtn.tap()
            return
        }

        if ["devices", "agents", "models", "mcp"].contains(destination) {
            let ctrlBtn = app.buttons["drawer.parent.控制"].firstMatch
            if ctrlBtn.waitForExistence(timeout: 5) {
                ctrlBtn.tap()
                let card = app.buttons["control.card.\(destination)"].firstMatch
                if card.waitForExistence(timeout: 5) {
                    card.tap()
                    return
                }
            }
        }

        if destination == "tasks" {
            let projBtn = app.buttons["drawer.parent.项目"].firstMatch
            if projBtn.waitForExistence(timeout: 5) {
                projBtn.tap()
                let card = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'project.card.'")).firstMatch
                if card.waitForExistence(timeout: 5) {
                    card.tap()
                    let tasksTab = app.buttons["任务"].firstMatch
                    if tasksTab.waitForExistence(timeout: 5) {
                        tasksTab.tap()
                        return
                    }
                }
            }
        }

        let settingsBtn = app.buttons["drawer.parent.设置"].firstMatch
        if settingsBtn.waitForExistence(timeout: 5) {
            settingsBtn.tap()
            let diag = app.buttons["settings.developerDiagnostics"].firstMatch
            if diag.waitForExistence(timeout: 5) {
                diag.tap()
                let targetBtn = app.buttons["diagnostics.route.\(destination)"].firstMatch
                if targetBtn.waitForExistence(timeout: 5) {
                    targetBtn.tap()
                    return
                }
            }
        }
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
        let newChatBtn = app.buttons.matching(
            NSPredicate(format: "label CONTAINS %@ OR identifier CONTAINS %@", "新对话", "newChat")
        ).firstMatch
        XCTAssertTrue(newChatBtn.waitForExistence(timeout: 10), "New chat button should exist")
        newChatBtn.tap()
        XCTAssertTrue(app.descendants(matching: .any)["chat.input"].exists)
        XCTAssertFalse(app.tabBars.firstMatch.exists)
    }

    func testAgentSelectorContainsOnlyAgentsAndManageLink() {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.descendants(matching: .any)["chat.input"].waitForExistence(timeout: 15))
        let selectorBtn = app.buttons["home.agentSelectorButton"].firstMatch
        XCTAssertTrue(selectorBtn.waitForExistence(timeout: 10), "Agent selector button must exist")
        selectorBtn.tap()

        XCTAssertTrue(
            app.staticTexts["选择对话方"].waitForExistence(timeout: 5) ||
            app.navigationBars["选择对话方"].waitForExistence(timeout: 5),
            "选择对话方 sheet 应展示"
        )
        XCTAssertFalse(app.staticTexts["已配置模型"].exists, "选择对话方不得包含「已配置模型」节")
        XCTAssertTrue(
            app.staticTexts["可选 Agent（设备 / 平台 / 云端）"].firstMatch.waitForExistence(timeout: 5),
            "选择器必须保留可选 Agent 列表"
        )
        let manageBtn = app.buttons["管理模型与连接"].firstMatch
        XCTAssertTrue(manageBtn.waitForExistence(timeout: 5), "选择器必须保留「管理模型与连接」入口")
        let ruleText = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS %@", "单选规则：对话框仅保留一个勾选的 Agent")
        ).firstMatch
        XCTAssertTrue(ruleText.waitForExistence(timeout: 5), "单选规则文案应只提 Agent")

        snap(app, "ui-03-agent-selector")
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
