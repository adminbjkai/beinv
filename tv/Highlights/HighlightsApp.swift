import SwiftUI

@main
struct HighlightsApp: App {
    var body: some Scene {
        WindowGroup {
            BrowseView()
                .preferredColorScheme(.dark)
        }
    }
}
