import SwiftUI

struct CommandsView: View {
    @EnvironmentObject private var vm: AppViewModel

    var body: some View {
        List(vm.commands, id: \.id) { item in
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text(item.status.uppercased())
                        .font(.caption.bold())
                        .foregroundStyle(statusColor(item.status))
                    Spacer()
                    Text(formatMillis(item.createdAt))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Text(item.relayPhone)
                    .font(.subheadline.bold())
                Text(item.command)
                    .font(.footnote.monospaced())
                if !item.description.isEmpty {
                    Text(item.description)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.vertical, 4)
        }
        .navigationTitle("Command queue")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    Task { await vm.refreshAll() }
                } label: {
                    if vm.isLoading {
                        ProgressView()
                    } else {
                        Image(systemName: "arrow.clockwise")
                    }
                }
            }
        }
        .refreshable {
            await vm.refreshAll()
        }
    }

    private func statusColor(_ status: String) -> Color {
        switch status.lowercased() {
        case "done":
            return .green
        case "failed":
            return .red
        case "sent_waiting", "pending":
            return .orange
        default:
            return .blue
        }
    }

    private func formatMillis(_ value: Int64) -> String {
        guard value > 0 else { return "-" }
        let date = Date(timeIntervalSince1970: TimeInterval(value) / 1000.0)
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        return formatter.string(from: date)
    }
}
