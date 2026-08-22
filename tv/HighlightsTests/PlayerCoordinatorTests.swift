import XCTest
import AVFoundation
@testable import Highlights

/// §2b jump: the queue is rebuilt from the chosen index and playback targets that item.
@MainActor
final class PlayerCoordinatorTests: XCTestCase {
    private func clips(_ n: Int) -> [Clip] {
        (0..<n).map { Clip(id: "c\($0)", title: "t\($0)", subtitle: "", url: URL(string: "https://example.com/\($0).mp4")!) }
    }
    private func url(_ item: AVPlayerItem?) -> URL? { (item?.asset as? AVURLAsset)?.url }

    func testJumpRebuildsQueueAtIndex() {
        let list = clips(4)
        let c = PlayerView.Coordinator(clips: list, dismiss: {})
        c.jump(to: 0)
        XCTAssertEqual(c.player.items().count, 4)
        XCTAssertEqual(url(c.player.currentItem), list[0].url)

        c.jump(to: 2)
        XCTAssertEqual(c.index, 2)
        XCTAssertEqual(c.player.items().count, 2, "queue holds item 2 and 3 only")
        XCTAssertEqual(url(c.player.currentItem), list[2].url)
        XCTAssertEqual(c.title(2), list[2].canonicalTitle)
        XCTAssertEqual(c.subtitle(2), "3 of 4 · Up next: t3")
        XCTAssertEqual(c.subtitle(3), "4 of 4 · Last clip")
    }

    func testNextPreviousBounds() {
        let c = PlayerView.Coordinator(clips: clips(2), dismiss: {})
        c.jump(to: 0); c.previous(); XCTAssertEqual(c.index, 0)
        c.next(); XCTAssertEqual(c.index, 1)
        c.next(); XCTAssertEqual(c.index, 1)
        XCTAssertEqual(c.player.items().count, 1)
    }

    func testOrderedGoalPlaylist() {
        func match(id: Int, round: Int, date: String, goals: [(Int, String)]) -> Match {
            var m = Match(matchId: id, matchDate: date, homeTeam: Team(name: "H\(id)", matchScore: 1), awayTeam: Team(name: "A\(id)", matchScore: 0),
                          matchEvents: goals.map { MatchEvent(id: $0.0 + id * 100, minute: $0.0, description: $0.1, type: 0, eventTeamSide: "Home", sourceVideoUrl: "https://x/\(id)-\($0.0).mp4") })
            m.round = round
            return m
        }
        let rows = [match(id: 2, round: 3, date: "2026-02-01T18:00:00Z", goals: [(70, "b"), (10, "a")]),
                    match(id: 1, round: 1, date: "2026-01-01T18:00:00Z", goals: [(55, "c")]),
                    match(id: 3, round: 3, date: "2026-02-01T15:00:00Z", goals: [(5, "d")])].flatMap(\.goalRows)
        let out = orderedGoalPlaylist(rows) { "\($0 ?? 0). Hafta" }
        XCTAssertEqual(out.map(\.scorer), ["c", "d", "a", "b"])
        XCTAssertEqual(out[0].canonicalTitle, "1. Hafta · H1 1–0 A1 · 55' c")
    }
}
