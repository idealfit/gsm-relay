import SwiftUI

struct RootView: View {
    @EnvironmentObject private var vm: AppViewModel

    var body: some View {
        TabView {
            NavigationStack {
                RelaysHomeView()
            }
            .tabItem {
                Label("Relays", systemImage: "dot.radiowaves.left.and.right")
            }

            NavigationStack {
                SettingsView()
            }
            .tabItem {
                Label("Settings", systemImage: "gearshape")
            }
        }
        .overlay(alignment: .bottom) {
            Text(vm.statusMessage)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .padding(.vertical, 6)
                .padding(.horizontal, 12)
                .background(.ultraThinMaterial, in: Capsule())
                .padding(.bottom, 8)
        }
    }
}
