import XCTest

final class HighlightsUITests: XCTestCase {
    let remote = XCUIRemote.shared

    /// Presses `dir` (up to `max` times) until `done()` is true.
    @discardableResult
    private func press(_ dir: XCUIRemote.Button, max: Int = 8, until done: () -> Bool) -> Bool {
        for _ in 0..<max {
            if done() { return true }
            remote.press(dir)
            Thread.sleep(forTimeInterval: 0.4)
        }
        return done()
    }

    func testSeasonPickerAndGoalsMode() {
        let app = XCUIApplication()
        app.launchArguments = ["-reset"]   // start from default selection regardless of saved state
        app.launch()

        let season = app.buttons["season.button"]
        XCTAssertTrue(season.waitForExistence(timeout: 20), "season button missing")
        // Wait until seasons are loaded (button shows a season name, not the placeholder)
        XCTAssertTrue(NSPredicate(format: "label != 'Season' AND label != ''").wait(for: season, timeout: 30))
        let before = season.label

        // Focus the Season control: first row is leagues, second row is Season.
        XCTAssertTrue(press(.down) { season.hasFocus } || press(.right) { season.hasFocus }, "could not focus Season")
        remote.press(.select)

        let second = app.buttons["picker.row.1"]
        XCTAssertTrue(second.waitForExistence(timeout: 10), "season picker did not open")
        let target = second.label
        XCTAssertTrue(press(.down) { second.hasFocus }, "could not focus second season")
        remote.press(.select)

        XCTAssertTrue(NSPredicate(format: "label == %@", target).wait(for: season, timeout: 10),
                      "season label did not change to \(target) (was \(before), now \(season.label))")

        // Mode → Goals
        let goals = app.buttons["mode.goals"]
        XCTAssertTrue(goals.waitForExistence(timeout: 5))
        let modeRowFocused = { Mode.allCases.contains { app.buttons["mode.\($0)"].hasFocus } }
        XCTAssertTrue(press(.down) { modeRowFocused() }, "could not reach mode row")
        XCTAssertTrue(press(.right) { goals.hasFocus } || press(.left) { goals.hasFocus }, "could not focus Goals")
        remote.press(.select)

        let scored = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS '–'")).firstMatch
        let empty = app.staticTexts["empty"]
        let ok = scored.waitForExistence(timeout: 30) || empty.waitForExistence(timeout: 5)
        XCTAssertTrue(ok, "neither a running score nor the empty state appeared")
    }

    /// §2b: Goals → Play all opens the player (`player.view`); Menu returns to the browse screen.
    func testPlayAllOpensPlayerAndMenuReturns() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-reset"]
        app.launch()

        let season = app.buttons["season.button"]
        XCTAssertTrue(season.waitForExistence(timeout: 20), "season button missing")
        XCTAssertTrue(NSPredicate(format: "label != 'Season' AND label != ''").wait(for: season, timeout: 30))

        let goals = app.buttons["mode.goals"]
        XCTAssertTrue(goals.waitForExistence(timeout: 5))
        let modeRowFocused = { Mode.allCases.contains { app.buttons["mode.\($0)"].hasFocus } }
        XCTAssertTrue(press(.down) { modeRowFocused() }, "could not reach mode row")
        XCTAssertTrue(press(.right) { goals.hasFocus } || press(.left) { goals.hasFocus }, "could not focus Goals")
        remote.press(.select)

        let playAll = app.buttons["goals.playall"]
        XCTAssertTrue(playAll.waitForExistence(timeout: 10), "Play all missing")
        guard NSPredicate(format: "enabled == true").wait(for: playAll, timeout: 30) else {
            throw XCTSkip("no goal clips in the default week — nothing to play")
        }
        // Play all is right-aligned in the row under the mode bar: go down into the grid, right to the last column, then up.
        let cardFocused = { app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'goal.'")).allElementsBoundByIndex.contains { $0.hasFocus } }
        XCTAssertTrue(press(.down) { cardFocused() || playAll.hasFocus }, "could not reach the goals grid")
        if !playAll.hasFocus { press(.right, max: 2) { false }; press(.up) { playAll.hasFocus } }
        XCTAssertTrue(playAll.hasFocus || press(.right) { playAll.hasFocus }, "could not focus Play all")
        remote.press(.select)

        let player = app.descendants(matching: .any)["player.view"]
        XCTAssertTrue(player.waitForExistence(timeout: 15), "player did not open")
        Thread.sleep(forTimeInterval: 3)   // let the first clip start rendering (transport bar shows the week-labelled title)
        let shot = XCUIScreen.main.screenshot()
        let att = XCTAttachment(screenshot: shot); att.lifetime = .keepAlways; add(att)
        let out = URL(fileURLWithPath: "/Users/m17/2026/beinv/tv/build/v23-player.png")
        try? FileManager.default.createDirectory(at: out.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? shot.pngRepresentation.write(to: out)

        // Best effort (not required): reach the info panel's "Clips" tab and capture it.
        let list = app.descendants(matching: .any)["player.clips"]
        if press(.down, max: 3, until: { list.exists }) {
            Thread.sleep(forTimeInterval: 1.5)
            try? XCUIScreen.main.screenshot().pngRepresentation.write(to: out.deletingLastPathComponent().appendingPathComponent("v24-clips.png"))
            // Jump: move to the second row and select; the player view's label mirrors the current index.
            let before = player.label
            remote.press(.down); Thread.sleep(forTimeInterval: 0.6)
            remote.press(.select); Thread.sleep(forTimeInterval: 1.5)
            XCTAssertEqual(before, "clip 0"); XCTAssertEqual(player.label, "clip 1", "selecting row 2 in the Clips tab did not jump")
            try? XCUIScreen.main.screenshot().pngRepresentation.write(to: out.deletingLastPathComponent().appendingPathComponent("v24-jump.png"))
        }

        // Apple-standard Menu: first press closes the panel / hides the chrome, the next one exits the player.
        XCTAssertTrue(press(.menu, max: 4) { !player.exists }, "player still open after Menu")
        XCTAssertTrue(goals.waitForExistence(timeout: 10), "browse screen not back")
    }

    private enum Mode: String, CaseIterable { case highlights, goals, team }
}

private extension NSPredicate {
    func wait(for el: XCUIElement, timeout: TimeInterval) -> Bool {
        XCTNSPredicateExpectation(predicate: self, object: el).wait(timeout: timeout)
    }
}
private extension XCTNSPredicateExpectation {
    func wait(timeout: TimeInterval) -> Bool { XCTWaiter().wait(for: [self], timeout: timeout) == .completed }
}
