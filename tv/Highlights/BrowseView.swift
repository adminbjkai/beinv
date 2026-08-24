import SwiftUI

@MainActor
final class BrowseModel: ObservableObject {
    private let prefs = UserDefaults.standard
    /// Restore the saved week only on the first season load; user-driven season changes reset to the default week.
    private var restoreSavedWeek = true

    @Published var league: League { didSet { prefs.set(league.id, forKey: "league"); restoreSavedWeek = false; Task { await loadSeasons() } } }
    @Published var seasons: [Season] = []
    @Published var seasonId: Int? { didSet { prefs.set(seasonId, forKey: "season"); seasonMatches = []; team = nil; selectDefaultWeek() } }
    @Published var round: Int? { didSet { prefs.set(round, forKey: "week"); Task { await loadMatches() } } }
    @Published var mode: Mode { didSet { prefs.set(mode.rawValue, forKey: "mode"); if mode == .team { Task { await loadSeason() } } } }
    @Published var matches: [Match] = []
    @Published var seasonMatches: [Match] = []
    @Published var seasonProgress: (Int, Int)?
    /// Remembered per §1 so By team reopens on the same team (restored once the season's team list is loaded).
    @Published var team: String? { didSet { if let t = team { prefs.set(t, forKey: "team") } } }
    /// By team sub-switch: Matches (false) | Goals (true).
    @Published var teamGoals = false
    /// By team → Goals: "Only <Team> goals" (default ON).
    @Published var onlyTeamGoals = true
    @Published var hd: Bool { didSet { prefs.set(hd, forKey: "hd") } }
    @Published var allWeeks: Bool { didSet { prefs.set(allWeeks, forKey: "allWeeks"); if allWeeks { Task { await loadSeason() } } } }
    var showingGoals: Bool { mode == .goals || (mode == .team && team != nil && teamGoals) }
    @Published var loading = false
    @Published var error: String?

    init() {
        let d = UserDefaults.standard
        // `-reset` (used by the UI test) starts from defaults instead of the remembered selection
        if CommandLine.arguments.contains("-reset") {
            for k in ["league", "mode", "season", "week", "team"] { d.removeObject(forKey: k) }
        }
        league = League.all.first { $0.id == d.string(forKey: "league") } ?? League.all[0]
        mode = Mode(rawValue: d.string(forKey: "mode") ?? "") ?? .highlights
        hd = d.object(forKey: "hd") as? Bool ?? true
        allWeeks = d.object(forKey: "allWeeks") as? Bool ?? true
    }

    var season: Season? { seasons.first { $0.id == seasonId } }
    var weeks: [Week] { season?.weeks ?? [] }
    var teams: [TeamRef] {
        var seen: [String: TeamRef] = [:]
        for m in seasonMatches { for t in [m.homeTeam, m.awayTeam] { if let n = t?.name, seen[n] == nil { seen[n] = TeamRef(name: n, logo: t?.logoURL) } } }
        return seen.values.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }
    var teamRef: TeamRef? { teams.first { $0.name == team } }
    var teamMatches: [Match] { team.map { t in seasonMatches.filter { $0.involves(t) } } ?? [] }
    /// Matches shown in the current mode (week, or the team's season).
    var visible: [Match] {
        if mode == .team { return teamMatches }
        if allWeeks { return seasonMatches.sorted { ($0.date ?? .distantPast) < ($1.date ?? .distantPast) } }
        return matches
    }
    /// Goal rows per visible match, honouring the "Only <Team> goals" filter.
    func goalRows(of m: Match) -> [GoalRow] {
        let rows = m.goalRows.filter { $0.event.clipURL != nil }
        if mode == .team, onlyTeamGoals, let t = team { return rows.filter { $0.teamName == t } }
        return rows
    }
    var goalGroups: [(Match, [GoalRow])] { visible.map { ($0, goalRows(of: $0)) }.filter { !$1.isEmpty } }
    /// Play-all playlist: exactly the visible goal rows in §2b order (week → kick-off → minute).
    var goalClips: [Clip] { orderedGoalPlaylist(goalGroups.flatMap { $1 }, weekName: weekName) }
    func weekName(_ r: Int?) -> String { weeks.first { $0.round == r }?.weekName ?? r.map { "Week \($0)" } ?? "" }

    func loadSeasons() async {
        loading = true; error = nil; matches = []
        do {
            seasons = try await API.shared.seasons(for: league).filter { $0.id != nil }
            let saved = prefs.object(forKey: "season") as? Int
            // Seasons come back newest-first, so the fallback is `.first` — matching web and Android.
            seasonId = (seasons.first { $0.id == saved } ?? seasons.first { $0.isCurrent == true } ?? seasons.first)?.id
        } catch {
            self.error = error.localizedDescription; loading = false
        }
    }

    func selectSeason(_ id: Int?) {
        guard id != seasonId else { return }
        restoreSavedWeek = false
        seasonId = id
    }

    private func selectDefaultWeek() {
        let w = weeks
        let saved = restoreSavedWeek ? prefs.object(forKey: "week") as? Int : nil
        restoreSavedWeek = false
        round = (w.first { $0.round == saved } ?? w.first { $0.currentWeekForFixture == true } ?? w.last)?.round
        if mode == .team || allWeeks { Task { await loadSeason() } }
    }

    func loadMatches() async {
        guard let sid = seasonId, let r = round else { matches = []; loading = false; return }
        loading = true; error = nil
        do {
            let m = try await API.shared.matches(league: league, seasonId: sid, round: r)
            if sid == seasonId && r == round { matches = m }
        } catch {
            self.error = error.localizedDescription
        }
        loading = false
    }

    func loadSeason() async {
        guard let sid = seasonId, seasonMatches.isEmpty, seasonProgress == nil else { return }
        // also used by Highlights/Goals "All weeks"
        let rounds = weeks.compactMap(\.round)
        // The season's weeks may not have arrived yet. Fetching with no rounds would cache an empty
        // season for the rest of the run, leaving "By team" permanently empty.
        guard !rounds.isEmpty else { return }
        seasonProgress = (0, rounds.count); error = nil
        do {
            let all = try await API.shared.seasonMatches(league: league, seasonId: sid, rounds: rounds) { done, total in
                Task { @MainActor [weak self] in self?.seasonProgress = (done, total) }
            }
            if sid == seasonId {
                seasonMatches = all
                if team == nil, let saved = prefs.string(forKey: "team"), teams.contains(where: { $0.name == saved }) { team = saved }
            }
        } catch {
            self.error = error.localizedDescription
        }
        seasonProgress = nil
    }

    func step(_ delta: Int) {
        let rounds = weeks.compactMap(\.round)
        guard let r = round, let i = rounds.firstIndex(of: r) else { return }
        let j = i + delta
        if rounds.indices.contains(j) { round = rounds[j] }
    }

    func retry() {
        Task {
            if seasons.isEmpty { await loadSeasons() }
            else if mode == .team { await loadSeason() }
            else { await loadMatches() }
        }
    }
}

struct Playback: Identifiable {
    let clips: [Clip]
    var start = 0
    var id: String { (clips.first?.id ?? "") + "\(start)" }
}

/// Which full-screen picker is open.
enum PickerKind: String, Identifiable {
    case season, week, team
    var id: String { rawValue }
}

struct BrowseView: View {
    @StateObject private var model = BrowseModel()
    @State private var playback: Playback?
    @State private var clips: Match?
    @State private var picker: PickerKind?

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 40), count: 3)

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 14) {
                leagueRow
                seasonRow
                modeRow
                modeSpecificRow
                HStack(alignment: .top, spacing: 28) {
                    if model.mode != .team { weekRail }
                    content
                }
            }
            .padding(.horizontal, 60)
            .padding(.top, 30)
        }
        .task { await model.loadSeasons() }
        .fullScreenCover(item: $playback) { PlayerView(clips: $0.clips, startIndex: $0.start).ignoresSafeArea() }
        .fullScreenCover(item: $clips) { ClipsView(match: $0) }
        .fullScreenCover(item: $picker) { kind in pickerSheet(kind) }
    }

    // MARK: rows (§0 order)

    private var leagueRow: some View {
        HStack(spacing: 24) {
            ForEach(League.all) { l in
                Button(l.name) { if model.league != l { model.league = l } }
                    .foregroundStyle(model.league == l ? Theme.accent : .primary)
                    .accessibilityIdentifier("league.\(l.id)")
            }
            Spacer()
        }
        .buttonStyle(.bordered).font(.callout).focusSection()
    }

    private var seasonRow: some View {
        HStack(spacing: 20) {
            rowLabel("Season")
            Button(model.season?.name ?? "Season") { picker = .season }
                .disabled(model.seasons.isEmpty)
                .accessibilityIdentifier("season.button")
            Spacer()
        }
        .buttonStyle(.bordered).font(.callout).focusSection()
    }

    private var weekRail: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Week").font(.caption).foregroundStyle(Theme.secondaryText)
            Button("All weeks") { model.allWeeks = true }
                .foregroundStyle(model.allWeeks ? Theme.accent : .primary)
                .accessibilityIdentifier("week.all")
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(model.weeks) { w in
                        Button(w.weekName ?? "Week \(w.round ?? 0)") {
                            model.allWeeks = false
                            model.round = w.round
                        }
                        .foregroundStyle(!model.allWeeks && model.round == w.round ? Theme.accent : .primary)
                    }
                }
            }
        }
        .frame(width: 220)
        .buttonStyle(.bordered).font(.callout).focusSection()
    }

    private var modeRow: some View {
        HStack(spacing: 16) {
            ForEach(Mode.allCases) { m in
                Button(m.label) { model.mode = m }
                    .foregroundStyle(model.mode == m ? Theme.accent : .primary)
                    .accessibilityIdentifier("mode.\(m.rawValue)")
            }
            if model.league.usesHdToggle {
                Button(model.hd ? "HD on" : "HD off") { model.hd.toggle() }
                    .foregroundStyle(model.hd ? Theme.accent : .primary)
                    .accessibilityIdentifier("hd.toggle")
            }
            Spacer()
        }
        .buttonStyle(.bordered).font(.callout).focusSection()
    }

    @ViewBuilder private var modeSpecificRow: some View {
        HStack(spacing: 16) {
            if model.mode == .team {
                rowLabel("Team")
                Button { picker = .team } label: {
                    HStack(spacing: 10) {
                        if let t = model.teamRef {
                            AsyncImage(url: t.logo) { $0.resizable().aspectRatio(contentMode: .fit) } placeholder: { Color.clear }
                                .frame(width: 32, height: 32)
                            Text(t.name)
                        } else { Text("Choose team") }
                    }
                }
                .disabled(model.teams.isEmpty)
                .accessibilityIdentifier("team.button")
                Button("Matches") { model.teamGoals = false }
                    .foregroundStyle(model.teamGoals ? .primary : Theme.accent)
                    .accessibilityIdentifier("sub.matches")
                Button("Goals") { model.teamGoals = true }
                    .foregroundStyle(model.teamGoals ? Theme.accent : .primary)
                    .accessibilityIdentifier("sub.goals")
                if model.teamGoals, let t = model.team {
                    Button { model.onlyTeamGoals.toggle() } label: {
                        Label("Only \(t) goals", systemImage: model.onlyTeamGoals ? "checkmark.square.fill" : "square")
                    }
                    .foregroundStyle(model.onlyTeamGoals ? Theme.accent : .primary)
                    .accessibilityIdentifier("only.toggle")
                }
            }
            Spacer()
            if model.showingGoals {
                let goals = model.goalClips
                let from = goals.first?.week.map { " · from \($0)" } ?? ""
                Button { playback = Playback(clips: goals) } label: { Label("Play all · \(goals.count) \(goals.count == 1 ? "goal" : "goals")\(from)", systemImage: "play.fill") }
                    .disabled(goals.isEmpty)
                    .accessibilityIdentifier("goals.playall")
            }
        }
        .buttonStyle(.bordered).font(.callout)
    }

    private func rowLabel(_ s: String) -> some View {
        Text(s).font(.callout).foregroundStyle(Theme.secondaryText).frame(width: 110, alignment: .leading)
    }

    // MARK: pickers

    @ViewBuilder private func pickerSheet(_ kind: PickerKind) -> some View {
        switch kind {
        case .season:
            PickerSheet(title: "Season", items: model.seasons, selected: model.seasonId, onSelect: { model.selectSeason($0.id) }) { s in
                Text(s.name ?? "-")
            }
        case .week:
            PickerSheet(title: "Week", items: model.weeks, selected: model.round, onSelect: { model.round = $0.round }) { w in
                Text(w.weekName ?? "\(w.round ?? 0)")
            }
        case .team:
            PickerSheet(title: "Team", items: model.teams, selected: model.team, columns: 4, onSelect: { model.team = $0.name }) { t in
                HStack(spacing: 12) {
                    AsyncImage(url: t.logo) { $0.resizable().aspectRatio(contentMode: .fit) } placeholder: { Color.clear }
                        .frame(width: 44, height: 44)
                    Text(t.name).lineLimit(1)
                }
            }
        }
    }

    // MARK: content

    @ViewBuilder private var content: some View {
        if let err = model.error {
            VStack(spacing: 20) {
                Text(err).foregroundStyle(Theme.secondaryText)
                Button("Retry") { model.retry() }.buttonStyle(.bordered)
            }.frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let (done, total) = model.seasonProgress, model.mode == .team || model.allWeeks {
            VStack(spacing: 20) {
                ProgressView(value: Double(done), total: Double(max(total, 1))).tint(Theme.accent).frame(width: 500)
                Text("Loading season… \(done)/\(total)").foregroundStyle(Theme.secondaryText)
            }.frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if model.loading && model.mode != .team {
            skeleton
        } else if model.mode == .team && model.team == nil {
            empty(model.teams.isEmpty ? "No matches in this season yet." : "Choose a team above.")
        } else if model.showingGoals {
            goalsGrid
        } else if model.visible.isEmpty {
            empty(model.mode == .team ? "No highlights for this team yet." : (model.allWeeks ? "No highlights published for this season yet." : "No highlights published for this week yet."))
        } else {
            matchGrid
        }
    }

    private func empty(_ text: String) -> some View {
        Text(text).foregroundStyle(Theme.secondaryText).frame(maxWidth: .infinity, maxHeight: .infinity)
            .accessibilityIdentifier("empty")
    }

    private var skeleton: some View {
        LazyVGrid(columns: columns, spacing: 50) {
            ForEach(0..<6, id: \.self) { _ in
                RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous)
                    .fill(Theme.card).aspectRatio(16 / 11, contentMode: .fit)
            }
        }
        .padding(.vertical, 40)
        .frame(maxHeight: .infinity, alignment: .top)
        .redacted(reason: .placeholder)
    }

    private var matchGrid: some View {
        ScrollView {
            LazyVGrid(columns: columns, spacing: 50) {
                ForEach(model.visible) { m in
                    Button {
                        let r = m.round ?? model.round ?? 0
                        let sid = model.seasonId ?? 0
                        playback = Playback(clips: m.playable(league: model.league, seasonId: sid, round: r, hd: model.hd).playlist)
                    } label: {
                        MatchCard(match: m, subtitle: (model.mode == .team || model.allWeeks) ? model.weekName(m.round) : nil)
                    }
                    .buttonStyle(.card)
                    .onLongPressGesture { clips = m }
                }
            }
            .padding(.vertical, 40)
        }
    }

    private var goalsGrid: some View {
        let groups = model.goalGroups
        let all = model.goalClips
        return ScrollView {
            if groups.isEmpty { empty("No goal clips for this selection.").padding(.top, 80) }
            LazyVStack(alignment: .leading, spacing: 30) {
                ForEach(groups, id: \.0.id) { m, rows in
                    MatchHeader(match: m, week: model.mode == .team ? model.weekName(m.round) : nil)
                    LazyVGrid(columns: columns, spacing: 40) {
                        ForEach(rows) { r in
                            Button { playback = Playback(clips: all, start: all.firstIndex { $0.id == r.clip.id } ?? 0) } label: {
                                GoalCard(row: r)
                            }
                            .buttonStyle(.card)
                            .accessibilityLabel("\(r.minute) \(r.scorer) · \(r.teamName) · \(r.score)")
                            .accessibilityIdentifier("goal.\(r.id)")
                        }
                    }
                }
            }
            .padding(.vertical, 40)
        }
    }
}

/// Full-screen option list used for Season / Week / Team. Selecting an item dismisses.
struct PickerSheet<Item: Identifiable & Hashable, Label: View>: View {
    let title: String
    let items: [Item]
    var selected: Item.ID?
    var columns = 1
    let onSelect: (Item) -> Void
    @ViewBuilder let label: (Item) -> Label
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 30) {
                Text(title).font(.title2).bold()
                ScrollView {
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 24), count: columns), spacing: 16) {
                        ForEach(Array(items.enumerated()), id: \.element.id) { i, item in
                            Button { onSelect(item); dismiss() } label: {
                                HStack {
                                    label(item)
                                    Spacer()
                                    if item.id == selected { Image(systemName: "checkmark").foregroundStyle(Theme.accent) }
                                }
                                .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.bordered)
                            .accessibilityIdentifier("picker.row.\(i)")
                        }
                    }
                }
            }
            .padding(60)
        }
    }
}

struct MatchHeader: View {
    let match: Match
    var week: String?
    var body: some View {
        HStack(spacing: 16) {
            logo(match.homeTeam)
            Text("\(match.homeTeam?.name ?? "?")  \(Match.scoreText(match.homeTeam))–\(Match.scoreText(match.awayTeam))  \(match.awayTeam?.name ?? "?")")
                .font(.headline).monospacedDigit()
            logo(match.awayTeam)
            if let w = week { Text("· \(w)").font(.headline).foregroundStyle(Theme.accent) }
        }
        .foregroundStyle(Theme.secondaryText)
    }
    private func logo(_ t: Team?) -> some View {
        AsyncImage(url: t?.logoURL) { $0.resizable().aspectRatio(contentMode: .fit) } placeholder: { Color.clear }
            .frame(width: 36, height: 36)
    }
}

/// Goal card: minute, scorer, scoring team (logo + name) and the running score with the scoring side highlighted.
struct GoalCard: View {
    let row: GoalRow
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            AsyncImage(url: row.event.thumbnailURL ?? row.match.thumbnailURL) { $0.resizable().aspectRatio(contentMode: .fill) } placeholder: { Rectangle().fill(Theme.card) }
                .aspectRatio(16 / 9, contentMode: .fit).clipped()
                .overlay(alignment: .topLeading) {
                    Text(row.minute).font(.caption).bold().padding(.horizontal, 12).padding(.vertical, 6)
                        .background(Theme.accent, in: Capsule()).foregroundStyle(.black).padding(12)
                }
            VStack(alignment: .leading, spacing: 8) {
                Text(row.scorer).font(.callout).bold().lineLimit(2).minimumScaleFactor(0.85).frame(height: 56, alignment: .topLeading)
                HStack(spacing: 10) {
                    AsyncImage(url: row.team?.logoURL) { $0.resizable().aspectRatio(contentMode: .fit) } placeholder: { Color.clear }
                        .frame(width: 28, height: 28)
                    Text(row.teamName).font(.caption).foregroundStyle(Theme.secondaryText).lineLimit(1)
                    Spacer()
                    score
                }
            }
            .padding(16).background(Theme.card)
        }
        .clipShape(RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous))
    }
    private var score: some View {
        (Text("\(row.home)").foregroundColor(row.side == .home ? Theme.accent : .primary)
         + Text("–").foregroundColor(Theme.secondaryText)
         + Text("\(row.away)").foregroundColor(row.side == .away ? Theme.accent : .primary))
            .font(.title3).bold().monospacedDigit()
    }
}

struct MatchCard: View {
    let match: Match
    var subtitle: String? = nil

    private static let df: DateFormatter = {
        let f = DateFormatter(); f.dateStyle = .short; f.timeStyle = .short; return f
    }()

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            AsyncImage(url: match.thumbnailURL) { img in
                img.resizable().aspectRatio(contentMode: .fill)
            } placeholder: {
                Rectangle().fill(Theme.card)
            }
            .aspectRatio(16 / 9, contentMode: .fit)
            .clipped()
            .overlay(alignment: .topTrailing) {
                let g = match.goals.count
                if g > 0 {
                    Label("\(g)", systemImage: "soccerball").font(.caption).bold()
                        .padding(.horizontal, 12).padding(.vertical, 6)
                        .background(Theme.accent, in: Capsule()).foregroundStyle(.black).padding(12)
                }
            }
            HStack(spacing: 14) {
                logo(match.homeTeam)
                Text("\(Match.scoreText(match.homeTeam)) - \(Match.scoreText(match.awayTeam))")
                    .font(.title3).bold().monospacedDigit()
                logo(match.awayTeam)
                Spacer()
                VStack(alignment: .trailing, spacing: 2) {
                    if let s = subtitle { Text(s).font(.caption).bold().foregroundStyle(Theme.accent) }
                    if let d = match.date { Text(Self.df.string(from: d)).font(.caption).foregroundStyle(Theme.secondaryText) }
                }
            }
            .padding(16)
            .background(Theme.card)
        }
        .clipShape(RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous))
    }

    private func logo(_ t: Team?) -> some View {
        AsyncImage(url: t?.logoURL) { $0.resizable().aspectRatio(contentMode: .fit) } placeholder: { Color.clear }
            .frame(width: 40, height: 40)
    }
}
