import Foundation

actor API {
    static let shared = API()

    private let userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/126 Safari/537.36"
    private var seasonCache: [String: [Season]] = [:]
    private var matchCache: [String: [Match]] = [:]
    private var seasonMatchCache: [String: [Match]] = [:]

    func seasons(for league: League) async throws -> [Season] {
        if let cached = seasonCache[league.id] { return cached }
        let url = URL(string: "https://apigateway.beinsports.com.tr/api/organizations/v3/rewriteid/\(league.id)")!
        let data = try await get(url)
        let seasons = (try? JSONDecoder().decode(OrganizationResponse.self, from: data))?.Data?.seasons ?? []
        seasonCache[league.id] = seasons
        return seasons
    }

    func matches(league: League, seasonId: Int, round: Int) async throws -> [Match] {
        let key = "\(league.orgId)-\(seasonId)-\(round)"
        if let cached = matchCache[key] { return cached }
        let matches: [Match]
        if league.isLaLiga {
            let url = URL(string: "\(Beinv.host)/api/leagues/\(league.id)/seasons/\(seasonId)/weeks/\(round)")!
            let data = try await get(url)
            let list = (try? JSONDecoder().decode([BeinvMatch].self, from: data)) ?? []
            matches = list.filter { $0.has_highlight == true }
                .map { $0.asMatch(league: league, seasonId: seasonId) }
                .sorted { ($0.date ?? .distantPast) < ($1.date ?? .distantPast) }
        } else {
            let url = URL(string: "https://beinsports.com.tr/api/highlights/events?sp=1&o=\(league.orgId)&s=\(seasonId)&r=\(round)&st=0")!
            let data = try await get(url)
            let events = (try? JSONDecoder().decode(EventsResponse.self, from: data))?.Data?.events ?? []
            matches = events
                .filter { $0.videoURL != nil }
                .sorted { ($0.date ?? .distantPast) < ($1.date ?? .distantPast) }
                .map { var m = $0; m.round = round; return m }
        }
        matchCache[key] = matches
        return matches
    }

    /// Every match of a season (all weeks fetched ~6 at a time), newest first. Cached per season.
    func seasonMatches(league: League, seasonId: Int, rounds: [Int],
                       progress: @Sendable @escaping (Int, Int) -> Void) async throws -> [Match] {
        let key = "\(league.orgId)-\(seasonId)"
        if let cached = seasonMatchCache[key] { return cached }
        var all: [Match] = []
        var done = 0
        try await withThrowingTaskGroup(of: [Match].self) { group in
            var it = rounds.makeIterator()
            for _ in 0..<6 { if let r = it.next() { group.addTask { try await self.matches(league: league, seasonId: seasonId, round: r) } } }
            while let part = try await group.next() {
                all += part
                done += 1; progress(done, rounds.count)
                if let r = it.next() { group.addTask { try await self.matches(league: league, seasonId: seasonId, round: r) } }
            }
        }
        all.sort { ($0.date ?? .distantPast) > ($1.date ?? .distantPast) }
        seasonMatchCache[key] = all
        return all
    }

    /// Ask the remux host to load this week/season so HD files start warming.
    func warm(league: League, seasonId: Int, round: Int?) async {
        let path = if let r = round {
            "\(Beinv.host)/api/leagues/\(league.id)/seasons/\(seasonId)/weeks/\(r)"
        } else {
            "\(Beinv.host)/api/leagues/\(league.id)/seasons/\(seasonId)/matches"
        }
        guard let url = URL(string: path) else { return }
        _ = try? await get(url)
    }

    private func get(_ url: URL) async throws -> Data {
        var req = URLRequest(url: url)
        req.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        let (data, resp) = try await URLSession.shared.data(for: req)
        if let http = resp as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw URLError(.badServerResponse)
        }
        return data
    }
}
