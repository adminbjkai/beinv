import SwiftUI
import AVKit
import Combine

/// Full-screen AVPlayerViewController driving an AVQueuePlayer over `clips`.
/// Native transport bar + "Previous clip / Next clip" items; Siri Remote page-skip (right/left) jumps between clips
/// (`skippingBehavior = .skipItem`). The clip list lives in the swipe-down info panel ("Clips" tab).
/// Every item carries the canonical title + "x of N · Up next: …" subtitle. Dismisses after the last clip (no loop).
struct PlayerView: UIViewControllerRepresentable {
    let clips: [Clip]
    var startIndex: Int = 0
    @Environment(\.dismiss) private var dismiss

    func makeCoordinator() -> Coordinator { Coordinator(clips: clips, dismiss: { dismiss() }) }

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let vc = AVPlayerViewController()
        let c = context.coordinator
        c.playerVC = vc
        vc.player = c.player
        vc.delegate = c
        vc.skippingBehavior = .skipItem                       // right/left page-skip → next/previous clip
        vc.transportBarCustomMenuItems = c.menuItems()
        vc.view.accessibilityIdentifier = "player.view"
        vc.view.isAccessibilityElement = true
        // Apple-standard Menu: AVKit hides its chrome / closes the info panel on the first press (the press is not
        // cancelled); when the chrome is already hidden the same press exits the player.
        let menu = UITapGestureRecognizer(target: c, action: #selector(Coordinator.menuPressed))
        menu.allowedPressTypes = [NSNumber(value: UIPress.PressType.menu.rawValue)]
        menu.cancelsTouchesInView = false
        vc.view.addGestureRecognizer(menu)
        if clips.count > 1 {
            vc.customInfoViewControllers = [ClipListController(coordinator: c)]
        }
        c.jump(to: startIndex)
        return vc
    }

    func updateUIViewController(_ vc: AVPlayerViewController, context: Context) {}

    static func dismantleUIViewController(_ vc: AVPlayerViewController, coordinator: Coordinator) {
        coordinator.stop()
    }

    @MainActor
    final class Coordinator: NSObject, ObservableObject, AVPlayerViewControllerDelegate {
        let clips: [Clip]
        let player = AVQueuePlayer()
        let dismiss: () -> Void
        weak var playerVC: AVPlayerViewController?
        /// Current index, mirrored into the player view's accessibility label (`clip <i>`) so UI tests can observe jumps.
        @Published private(set) var index = 0 { didSet { playerVC?.view.accessibilityLabel = "clip \(index)" } }
        private var fullResume: CMTime = .zero
        private var observer: NSObjectProtocol?
        /// Items currently in the queue, in order — maps an end-of-item notification back to an index.
        private var queued: [AVPlayerItem] = []
        private var queueBase = 0
        private var chromeVisible = false

        init(clips: [Clip], dismiss: @escaping () -> Void) {
            self.clips = clips; self.dismiss = dismiss
            super.init()
            player.actionAtItemEnd = .advance                    // autoplay next (AVQueuePlayer default, made explicit)
            // `queue: .main` guarantees main-thread delivery, but the closure is `@Sendable`, so the
            // main-actor state below has to be reached through an explicit assumption.
            observer = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: nil, queue: .main) { [weak self] n in
                MainActor.assumeIsolated {
                    guard let self, let item = n.object as? AVPlayerItem, let pos = self.queued.firstIndex(of: item) else { return }
                    if item == self.queued.last { self.dismiss() }       // last item of the playlist → back to the list
                    else { self.index = self.queueBase + pos + 1 }      // AVQueuePlayer advances itself; track the index
                }
            }
        }

        func stop() {
            if let o = observer { NotificationCenter.default.removeObserver(o) }
            player.pause(); player.removeAllItems()
        }

        // MARK: AVPlayerViewControllerDelegate
        nonisolated func playerViewController(_ playerViewController: AVPlayerViewController,
                                              willTransitionToVisibilityOfTransportBar visible: Bool,
                                              with coordinator: AVPlayerViewControllerAnimationCoordinator) {
            Task { @MainActor in self.chromeVisible = visible }
        }

        @objc func menuPressed() { if !chromeVisible { dismiss() } }

        // MARK: titles (§2b)
        func title(_ i: Int) -> String { clips[i].canonicalTitle }
        /// `x of N · Up next: <next canonical title>` or `x of N · Last clip`.
        func subtitle(_ i: Int) -> String {
            let pos = "\(i + 1) of \(clips.count)"
            return i + 1 < clips.count ? "\(pos) · Up next: \(title(i + 1))" : "\(pos) · Last clip"
        }

        private func item(at i: Int) -> AVPlayerItem {
            let item = AVPlayerItem(url: clips[i].url)
            item.externalMetadata = [meta(.commonIdentifierTitle, title(i)), meta(.iTunesMetadataTrackSubTitle, subtitle(i))]
            return item
        }

        private func meta(_ id: AVMetadataIdentifier, _ value: String) -> AVMetadataItem {
            let m = AVMutableMetadataItem()
            m.identifier = id; m.value = value as NSString; m.extendedLanguageTag = "und"
            return m
        }

        /// Jumps to clip `i`: rebuilds the queue from `i` onward and starts playing.
        func jump(to i: Int) {
            guard clips.indices.contains(i) else { return }
            if let cur = player.currentItem, clips[index].isFull { fullResume = cur.currentTime() }
            index = i
            player.removeAllItems()
            queueBase = i
            queued = clips.indices[i...].map(item(at:))
            for it in queued { player.insert(it, after: nil) }
            if clips[i].isFull, fullResume.seconds > 1 { player.seek(to: fullResume) }   // best-effort resume
            player.play()
        }

        func next() { if index + 1 < clips.count { jump(to: index + 1) } }
        func previous() { if index > 0 { jump(to: index - 1) } }

        func menuItems() -> [UIMenuElement] {
            let prev = UIAction(title: "Previous clip", image: UIImage(systemName: "backward.end")) { [weak self] _ in self?.previous() }
            let next = UIAction(title: "Next clip", image: UIImage(systemName: "forward.end")) { [weak self] _ in self?.next() }
            return [prev, next]
        }
    }
}

/// Info-panel "Clips" tab (§2b) — native UIKit so focus/select are reliable inside AVKit's panel on a real Apple TV.
/// Ordered playlist grouped by week; rows = minute · team logo · scorer · scoreline · running score.
/// Current row emerald and focused by default; select → `Coordinator.jump(to:)`.
final class ClipListController: UITableViewController {
    private let coordinator: PlayerView.Coordinator
    private let sections: [(week: String, items: [(index: Int, clip: Clip)])]
    private var cancellable: AnyCancellable?

    init(coordinator: PlayerView.Coordinator) {
        self.coordinator = coordinator
        var out: [(String, [(Int, Clip)])] = []
        for (i, c) in coordinator.clips.enumerated() {
            let w = c.week ?? ""
            if out.last?.0 == w { out[out.count - 1].1.append((i, c)) } else { out.append((w, [(i, c)])) }
        }
        sections = out.map { (week: $0.0, items: $0.1.map { (index: $0.0, clip: $0.1) }) }
        super.init(style: .plain)
        title = "Clips"
        preferredContentSize = CGSize(width: 1920, height: 520)
    }
    @available(*, unavailable) required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        tableView.backgroundColor = .clear
        tableView.remembersLastFocusedIndexPath = true
        tableView.accessibilityIdentifier = "player.clips"
        tableView.register(ClipCell.self, forCellReuseIdentifier: "clip")
        tableView.rowHeight = 72
        tableView.sectionHeaderHeight = 44
        tableView.contentInset = UIEdgeInsets(top: 8, left: 0, bottom: 8, right: 0)
        cancellable = coordinator.$index.receive(on: RunLoop.main).sink { [weak self] _ in self?.tableView.reloadData() }
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        tableView.reloadData()
        if let ip = indexPath(of: coordinator.index) { tableView.scrollToRow(at: ip, at: .middle, animated: false) }
    }

    private func indexPath(of index: Int) -> IndexPath? {
        for (s, sec) in sections.enumerated() { if let r = sec.items.firstIndex(where: { $0.index == index }) { return IndexPath(row: r, section: s) } }
        return nil
    }

    override var preferredFocusEnvironments: [UIFocusEnvironment] {
        if let ip = indexPath(of: coordinator.index), let cell = tableView.cellForRow(at: ip) { return [cell] }
        return [tableView]
    }

    override func numberOfSections(in tableView: UITableView) -> Int { sections.count }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { sections[section].items.count }
    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? { sections[section].week.isEmpty ? nil : sections[section].week }

    override func tableView(_ tableView: UITableView, viewForHeaderInSection section: Int) -> UIView? {
        guard !sections[section].week.isEmpty else { return nil }
        let l = UILabel()
        l.text = sections[section].week
        l.font = .boldSystemFont(ofSize: 26)
        l.textColor = UIColor(Theme.accent)
        let wrap = UIView()
        wrap.addSubview(l); l.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([l.leadingAnchor.constraint(equalTo: wrap.leadingAnchor, constant: 80), l.bottomAnchor.constraint(equalTo: wrap.bottomAnchor, constant: -6)])
        return wrap
    }

    override func tableView(_ tableView: UITableView, cellForRowAt ip: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "clip", for: ip) as! ClipCell
        let item = sections[ip.section].items[ip.row]
        cell.configure(item.clip, current: item.index == coordinator.index)
        cell.accessibilityIdentifier = "clip.\(item.index)"
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt ip: IndexPath) {
        coordinator.jump(to: sections[ip.section].items[ip.row].index)   // rebuilds the queue + resumes playback
        tableView.reloadData()
    }
}

/// Row: minute · logo · scorer · scoreline · running score. Emerald when current; white ring when focused.
final class ClipCell: UITableViewCell {
    private let card = UIView()
    private let minute = UILabel(), scorer = UILabel(), scoreline = UILabel(), score = UILabel()
    private let logo = UIImageView()
    private var logoURL: URL?
    private var current = false

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        backgroundColor = .clear
        focusStyle = .custom
        card.layer.cornerRadius = 14; card.layer.borderWidth = 0
        contentView.addSubview(card); card.translatesAutoresizingMaskIntoConstraints = false
        minute.font = .boldSystemFont(ofSize: 28); minute.textAlignment = .right
        scorer.font = .boldSystemFont(ofSize: 28)
        scoreline.font = .systemFont(ofSize: 22)
        score.font = .monospacedDigitSystemFont(ofSize: 28, weight: .bold)
        logo.contentMode = .scaleAspectFit
        let stack = UIStackView(arrangedSubviews: [minute, logo, scorer, scoreline, UIView(), score])
        stack.spacing = 18; stack.alignment = .center
        scorer.setContentHuggingPriority(.required, for: .horizontal)
        scoreline.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        card.addSubview(stack); stack.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            card.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 60),
            card.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -60),
            card.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 4),
            card.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -4),
            stack.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 20),
            stack.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -24),
            stack.centerYAnchor.constraint(equalTo: card.centerYAnchor),
            minute.widthAnchor.constraint(equalToConstant: 70),
            logo.widthAnchor.constraint(equalToConstant: 36), logo.heightAnchor.constraint(equalToConstant: 36),
        ])
    }
    @available(*, unavailable) required init?(coder: NSCoder) { fatalError() }

    func configure(_ c: Clip, current: Bool) {
        self.current = current
        minute.text = c.minute.isEmpty ? (c.isFull ? "▶" : "·") : c.minute
        scorer.text = c.scorer.isEmpty ? c.title : c.scorer
        scoreline.text = c.matchScore.isEmpty ? c.subtitle : c.matchScore
        score.text = c.score ?? ""
        logoURL = c.teamLogo ?? c.thumbnail
        logo.image = nil
        if let u = logoURL { ImageCache.shared.load(u) { [weak self] img in if self?.logoURL == u { self?.logo.image = img } } }
        applyStyle()
    }

    private func applyStyle() {
        let fg: UIColor = current ? .black : .white
        card.backgroundColor = current ? UIColor(Theme.accent) : UIColor(Theme.background).withAlphaComponent(0.78)
        minute.textColor = current ? .black : UIColor(Theme.accent)
        scorer.textColor = fg; score.textColor = fg
        scoreline.textColor = current ? UIColor.black.withAlphaComponent(0.7) : UIColor(Theme.secondaryText)
        card.layer.borderColor = UIColor.white.cgColor
        card.layer.borderWidth = isFocused ? 4 : 0
        transform = isFocused ? CGAffineTransform(scaleX: 1.02, y: 1.02) : .identity
    }

    override func didUpdateFocus(in context: UIFocusUpdateContext, with coordinator: UIFocusAnimationCoordinator) {
        coordinator.addCoordinatedAnimations({ self.applyStyle() })
    }
}

/// Tiny in-memory image loader for team logos in the Clips tab.
final class ImageCache {
    static let shared = ImageCache()
    private let cache = NSCache<NSURL, UIImage>()
    func load(_ url: URL, _ done: @escaping (UIImage?) -> Void) {
        if let img = cache.object(forKey: url as NSURL) { done(img); return }
        URLSession.shared.dataTask(with: url) { data, _, _ in
            let img = data.flatMap(UIImage.init(data:))
            if let img { self.cache.setObject(img, forKey: url as NSURL) }
            DispatchQueue.main.async { done(img) }
        }.resume()
    }
}

/// The clip a `ClipsView` row opened, as a presentation item.
/// (A retroactive `Int: Identifiable` conformance would warn, and is an error in Swift 6.)
private struct StartAt: Identifiable { let id: Int }

/// Clips list (press-and-hold on a card). Selecting a clip opens the playlist at that clip.
struct ClipsView: View {
    let match: Match
    @State private var start: StartAt?

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 24) {
                Text(match.title).font(.title2).bold()
                ScrollView {
                    LazyVStack(spacing: 12) {
                        ForEach(Array(match.playlist.enumerated()), id: \.element.id) { i, c in
                            Button { start = StartAt(id: i) } label: {
                                HStack(spacing: 16) {
                                    Circle().fill(c.isGoal ? Theme.accent : Color.clear)
                                        .overlay(Circle().stroke(Theme.secondaryText, lineWidth: c.isGoal ? 0 : 2))
                                        .frame(width: 16, height: 16)
                                    Text(c.title)
                                    Spacer()
                                }
                                .padding(.horizontal, 24)
                            }
                        }
                        if match.events.isEmpty {
                            Text("No individual clips for this match.").foregroundStyle(Theme.secondaryText)
                        }
                    }
                }
            }
            .padding(60)
        }
        .fullScreenCover(item: $start) { PlayerView(clips: match.playlist, startIndex: $0.id).ignoresSafeArea() }
    }
}
