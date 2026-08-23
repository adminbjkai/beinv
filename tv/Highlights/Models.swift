import Foundation

struct League: Identifiable, Hashable {
    let id: String
    let name: String
    let orgId: Int
    static let all: [League] = [
        League(id: "super-lig", name: "Trendyol Süper Lig", orgId: 18),
        League(id: "ingiltere-premier-ligi", name: "İngiltere Premier Lig", orgId: 17),
    ]
}

enum Mode: String, CaseIterable, Identifiable {
    case highlights, goals, team
    var id: String { rawValue }
    var label: String {
        switch self {
        case .highlights: return "Highlights"
        case .goals: return "Goals"
        case .team: return "By team"
        }
    }
}

struct Week: Codable, Hashable, Identifiable {
    var round: Int?
    var weekName: String?
    var currentWeekForFixture: Bool?
    var id: Int { round ?? 0 }
}

struct Season: Codable, Hashable, Identifiable {
    var id: Int?
    var name: String?
    var isCurrent: Bool?
    var beinSportsFixtureWeekList: [Week]?
    var weeks: [Week] { beinSportsFixtureWeekList ?? [] }
}

struct OrganizationResponse: Codable {
    struct Data: Codable { var seasons: [Season]? }
    var Data: Data?
}

struct Team: Codable, Hashable {
    var name: String?
    var logo: String?
    var matchScore: Int?
    var logoURL: URL? { logo.flatMap(URL.init(string:)) }
}

/// Deduplicated team for the "By team" picker.
struct TeamRef: Hashable, Identifiable {
    let name: String
    let logo: URL?
    var id: String { name }
}

enum Side { case home, away }

/// A goal with the running score after it (spec §2 Goals).
struct GoalRow: Identifiable, Hashable {
    let event: MatchEvent
    let match: Match
    let side: Side?
    let home: Int
    let away: Int
    var id: String { "g\(event.id ?? 0)-\(match.id)" }
    var team: Team? { side == .home ? match.homeTeam : side == .away ? match.awayTeam : nil }
    var teamName: String { team?.name ?? "—" }
    var minute: String { event.minute.map { "\($0)'" } ?? "" }
    var scorer: String { event.description ?? "Goal" }
    var score: String { "\(home)–\(away)" }
    var clip: Clip { Clip(event: event, match: match) }
    /// Playlist item carrying the week label, running score and scoring-team logo (§2b clip selector).
    func clip(week: String) -> Clip {
        var c = Clip(event: event, match: match)
        c.week = week; c.score = score; c.side = side; c.teamLogo = team?.logoURL; c.minute = minute; c.scorer = scorer
        // scoreline AT this goal (running score), not the final result — parity with web/Android
        c.matchScore = "\(match.homeTeam?.name ?? "?") \(home)–\(away) \(match.awayTeam?.name ?? "?")"
        return c
    }
}

/// §2b playlist order — the single ordering used by Play all and by selecting a goal card:
/// week ascending → match kick-off ascending → goal minute ascending (stable on row id).
func orderedGoalPlaylist(_ rows: [GoalRow], weekName: (Int?) -> String) -> [Clip] {
    rows.sorted { a, b in
        let ka = (a.match.round ?? 0, a.match.date ?? .distantPast, a.event.minute ?? 0, a.id)
        let kb = (b.match.round ?? 0, b.match.date ?? .distantPast, b.event.minute ?? 0, b.id)
        return ka < kb
    }.map { $0.clip(week: weekName($0.match.round)) }
}

struct MatchEvent: Codable, Hashable, Identifiable {
    var id: Int?
    var minute: Int?
    var description: String?
    var type: Int?
    var eventTeamSide: String?
    var thumbnail: String?
    var sourceVideoUrl: String?
    var videoUrl: String?
    var isGoal: Bool { type == 0 }
    /// Scoring side; nil for own goals / unknown.
    var side: Side? {
        switch eventTeamSide?.lowercased() { case "home": return .home; case "away": return .away; default: return nil }
    }
    var clipURL: URL? {
        let s = [sourceVideoUrl, videoUrl].compactMap { $0 }.first { !$0.isEmpty }
        return s.flatMap(URL.init(string:))
    }
    var thumbnailURL: URL? { thumbnail.flatMap(URL.init(string:)) }
    var label: String { "\(minute.map { "\($0)'" } ?? "")  \(description ?? "Clip")".trimmingCharacters(in: .whitespaces) }
}

struct Match: Codable, Hashable, Identifiable {
    var matchId: Int?
    var matchDate: String?
    var highLightTitle: String?
    var highlightThumbnail: String?
    var highlightVideoUrl: String?
    var homeTeam: Team?
    var awayTeam: Team?
    var matchEvents: [MatchEvent]?
    /// Week round, filled in by the API layer (not part of the upstream payload).
    var round: Int? = nil

    private enum CodingKeys: String, CodingKey {
        case matchId, matchDate, highLightTitle, highlightThumbnail, highlightVideoUrl, homeTeam, awayTeam, matchEvents
    }

    var id: Int { matchId ?? (highlightVideoUrl ?? "").hashValue }
    var videoURL: URL? { highlightVideoUrl.flatMap { $0.isEmpty ? nil : URL(string: $0) } }
    var thumbnailURL: URL? { highlightThumbnail.flatMap(URL.init(string:)) }
    var events: [MatchEvent] { (matchEvents ?? []).filter { $0.clipURL != nil } }
    var goals: [MatchEvent] { events.filter(\.isGoal) }
    /// Goals in minute order with the running score after each one.
    var goalRows: [GoalRow] {
        var h = 0, a = 0
        // every goal counts toward the running score, even without a clip
        return (matchEvents ?? []).filter(\.isGoal).sorted { ($0.minute ?? 0) < ($1.minute ?? 0) }.map { g in
            if g.side == .home { h += 1 } else if g.side == .away { a += 1 }
            return GoalRow(event: g, match: self, side: g.side, home: h, away: a)
        }
    }
    /// Shared: `date` is read inside sort comparators over a whole season, and building an
    /// `ISO8601DateFormatter` per access dominated that work. Parsing is thread-safe.
    private static let iso = ISO8601DateFormatter()
    var date: Date? { matchDate.flatMap { Match.iso.date(from: $0) } }
    /// A score upstream did not report renders as an en dash on every client (never as 0).
    static func scoreText(_ t: Team?) -> String { t?.matchScore.map(String.init) ?? "–" }
    /// `Beşiktaş 2–1 Trabzonspor` (en dash) — used in the canonical playlist title.
    var score: String {
        "\(homeTeam?.name ?? "?") \(Match.scoreText(homeTeam))–\(Match.scoreText(awayTeam)) \(awayTeam?.name ?? "?")"
    }
    var scoreline: String {
        "\(homeTeam?.name ?? "?") \(Match.scoreText(homeTeam))-\(Match.scoreText(awayTeam)) \(awayTeam?.name ?? "?")"
    }
    var title: String {
        if let t = highLightTitle, !t.isEmpty { return t }
        return "\(homeTeam?.name ?? "?") - \(awayTeam?.name ?? "?")"
    }
    func involves(_ team: String) -> Bool { homeTeam?.name == team || awayTeam?.name == team }

    /// Full highlight followed by every clip — the player's playlist.
    var playlist: [Clip] {
        var list: [Clip] = []
        if let u = videoURL { list.append(Clip(id: "m\(id)", title: "Full highlight", subtitle: title, url: u, isFull: true)) }
        list += events.map { Clip(event: $0, match: self) }
        return list
    }
}

struct Clip: Identifiable, Hashable {
    let id: String
    let title: String
    let subtitle: String
    let url: URL
    var isFull = false
    var isGoal = false
    var thumbnail: URL? = nil
    var matchId: Int? = nil
    // §2b selector fields (goal playlists only)
    var week: String? = nil
    var score: String? = nil
    var side: Side? = nil
    var teamLogo: URL? = nil
    var minute: String = ""
    var scorer: String = ""
    /// Canonical §2b title, identical on all clients: `3. Hafta · Beşiktaş 2–1 Trabzonspor · 55' Jota Silva`.
    /// Falls back to the plain clip title for match playlists (no week).
    var canonicalTitle: String { week.map { "\($0) · \(matchScore) · \(minute) \(scorer)" } ?? title }
    var matchScore: String = ""

    init(id: String, title: String, subtitle: String, url: URL, isFull: Bool = false) {
        self.id = id; self.title = title; self.subtitle = subtitle; self.url = url; self.isFull = isFull
    }
    init(event e: MatchEvent, match m: Match) {
        id = "e\(e.id ?? 0)-\(m.id)"; title = e.label; subtitle = m.scoreline; url = e.clipURL!
        isGoal = e.isGoal; thumbnail = e.thumbnailURL; matchId = m.id
    }
}

struct EventsResponse: Codable {
    struct Data: Codable { var events: [Match]? }
    var Data: Data?
}
