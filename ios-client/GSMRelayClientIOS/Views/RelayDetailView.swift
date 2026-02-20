import SwiftUI
import UniformTypeIdentifiers

struct RelayDetailView: View {
    @EnvironmentObject private var vm: AppViewModel
    let relay: Relay

    @State private var selectedTab = 0

    var body: some View {
        VStack(spacing: 0) {
            relayHeader
            Picker("Tab", selection: $selectedTab) {
                Text("Users").tag(0)
                Text("Commands").tag(1)
                Text("Queue").tag(2)
                Text("History").tag(3)
                Text("Events").tag(4)
                Text("Notifications").tag(5)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal)

            switch selectedTab {
            case 0:
                RelayUsersTab(relay: relay)
            case 1:
                RelayCommandsTab(relay: relay)
            case 2:
                RelayQueueTab(relay: relay)
            case 3:
                RelayHistoryTab(relay: relay)
            case 4:
                RelayEventsTab(relay: relay)
            default:
                RelayNotificationsTab(relay: relay)
            }
        }
        .task {
            await vm.syncCommands()
        }
    }

    private var relayHeader: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(relay.displayName).font(.title3.bold())
            Text(relay.phoneNumber).font(.subheadline).foregroundStyle(.secondary)
            HStack(spacing: 8) {
                let count = relay.users?.filter { !($0.phone ?? "").isEmpty }.count ?? 0
                Text("\(count)/200 users")
                    .font(.caption)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(.thinMaterial, in: Capsule())
                if let location = relay.location, !location.isEmpty {
                    Text(location)
                        .font(.caption)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(.thinMaterial, in: Capsule())
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(Color.accentColor.opacity(0.12))
    }
}

private struct RelayUsersTab: View {
    @EnvironmentObject private var vm: AppViewModel
    let relay: Relay

    @State private var showAddUser = false
    @State private var query = ""
    @State private var isImportingCsv = false
    @State private var isExportingCsv = false
    @State private var exportCsvText = ""

    private var users: [RelayUser] {
        (relay.users ?? []).filter { user in
            if query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return true }
            let q = query.lowercased()
            let id = String(format: "%03d", user.id)
            return (user.phone ?? "").lowercased().contains(q)
                || (user.group ?? "").lowercased().contains(q)
                || id.contains(q)
        }
    }

    var body: some View {
        List {
            TextField("Search phone/group/ID", text: $query)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()

            HStack {
                Button("Add") { showAddUser = true }
                Button("Import CSV") { isImportingCsv = true }
                Button("Export CSV") {
                    exportCsvText = vm.buildUsersCsv(for: relay)
                    isExportingCsv = true
                }
                Spacer()
            }

            ForEach(users, id: \.id) { user in
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Slot \(String(format: "%03d", user.id))").font(.headline)
                        Text((user.phone ?? "").isEmpty ? "Empty" : (user.phone ?? "")).font(.subheadline)
                        if let group = user.group, !group.isEmpty, !(user.phone ?? "").isEmpty {
                            Text("Group: \(group)").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    Spacer()
                    if !(user.phone ?? "").isEmpty {
                        Button(role: .destructive) {
                            Task { await vm.deleteUser(relayId: relay.id, userId: user.id) }
                        } label: {
                            Image(systemName: "trash")
                        }
                        .buttonStyle(.borderless)
                    }
                }
            }
        }
        .sheet(isPresented: $showAddUser) {
            AddUserSheet(relay: relay) { userId, phone, name, group in
                Task {
                    await vm.addUser(relayId: relay.id, userId: userId, phone: phone, name: name, group: group)
                }
            }
        }
        .fileImporter(
            isPresented: $isImportingCsv,
            allowedContentTypes: [.plainText, .commaSeparatedText],
            allowsMultipleSelection: false
        ) { result in
            if case let .success(urls) = result, let url = urls.first {
                Task {
                    do {
                        let text = try String(contentsOf: url, encoding: .utf8)
                        await vm.importUsersCsv(for: relay, csvText: text)
                    } catch {
                        // Ignore read errors; vm status bar remains unchanged.
                    }
                }
            }
        }
        .fileExporter(
            isPresented: $isExportingCsv,
            document: TextFileDocument(text: exportCsvText),
            contentType: .commaSeparatedText,
            defaultFilename: "\(relay.displayName)_users"
        ) { _ in }
    }
}

private struct AddUserSheet: View {
    @Environment(\.dismiss) private var dismiss
    let relay: Relay
    let onAdd: (Int, String, String, String) -> Void

    @State private var userId = ""
    @State private var phone = ""
    @State private var name = ""
    @State private var group = "general"

    var body: some View {
        NavigationStack {
            Form {
                TextField("User ID (1-200)", text: $userId)
                    .keyboardType(.numberPad)
                TextField("Phone", text: $phone)
                    .keyboardType(.phonePad)
                TextField("Name (optional)", text: $name)
                TextField("Group", text: $group)
            }
            .navigationTitle("Add user")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Send") {
                        let id = min(max(Int(userId) ?? 1, 1), 200)
                        onAdd(id, phone, name, group)
                        dismiss()
                    }
                    .disabled(phone.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}

private struct RelayCommandsTab: View {
    @EnvironmentObject private var vm: AppViewModel
    let relay: Relay

    @State private var showQuery = false
    @State private var showPassword = false
    @State private var showTimer = false

    var body: some View {
        List {
            Button("Query users") { showQuery = true }
            Button("Change password") { showPassword = true }
            Button("Set timer") { showTimer = true }
            Button("Allow all") {
                Task { await vm.allowAll(relay) }
            }
            Button("Allow authorized only") {
                Task { await vm.allowAuthorizedOnly(relay) }
            }
        }
        .sheet(isPresented: $showQuery) {
            QueryUsersSheet { start, end in
                Task { await vm.queryUsers(relay, start: start, end: end) }
            }
        }
        .sheet(isPresented: $showPassword) {
            PasswordSheet { newPass in
                Task { await vm.changeRelayPassword(relay, newPassword: newPass) }
            }
        }
        .sheet(isPresented: $showTimer) {
            TimerSheet { seconds in
                Task { await vm.setRelayTimer(relay, seconds: seconds) }
            }
        }
    }
}

private struct QueryUsersSheet: View {
    @Environment(\.dismiss) private var dismiss
    let onSend: (Int, Int) -> Void
    @State private var start = "1"
    @State private var end = "200"

    var body: some View {
        NavigationStack {
            Form {
                TextField("Start", text: $start).keyboardType(.numberPad)
                TextField("End", text: $end).keyboardType(.numberPad)
            }
            .navigationTitle("Query users")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Send") {
                        onSend(Int(start) ?? 1, Int(end) ?? 200)
                        dismiss()
                    }
                }
            }
        }
    }
}

private struct PasswordSheet: View {
    @Environment(\.dismiss) private var dismiss
    let onSend: (String) -> Void
    @State private var value = ""

    var body: some View {
        NavigationStack {
            Form { TextField("New password", text: $value) }
                .navigationTitle("Change password")
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) { Button("Cancel") { dismiss() } }
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("Send") {
                            onSend(value)
                            dismiss()
                        }
                        .disabled(value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                }
        }
    }
}

private struct TimerSheet: View {
    @Environment(\.dismiss) private var dismiss
    let onSend: (Int) -> Void
    @State private var value = "5"

    var body: some View {
        NavigationStack {
            Form { TextField("Seconds", text: $value).keyboardType(.numberPad) }
                .navigationTitle("Set timer")
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) { Button("Cancel") { dismiss() } }
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("Send") {
                            onSend(Int(value) ?? 5)
                            dismiss()
                        }
                    }
                }
        }
    }
}

private struct RelayQueueTab: View {
    @EnvironmentObject private var vm: AppViewModel
    let relay: Relay

    var body: some View {
        List(vm.queueForRelay(relay), id: \.id) { cmd in
            VStack(alignment: .leading, spacing: 4) {
                Text(cmd.description.isEmpty ? "Command" : cmd.description).font(.headline)
                Text(cmd.command).font(.footnote.monospaced())
                Text("Status: \(cmd.status)").font(.caption).foregroundStyle(.secondary)
                if !cmd.responseText.isEmpty {
                    Text("Response: \(cmd.responseText)").font(.caption).foregroundStyle(.secondary)
                }
            }
        }
    }
}

private struct RelayHistoryTab: View {
    @EnvironmentObject private var vm: AppViewModel
    let relay: Relay

    var body: some View {
        List(vm.historyForRelay(relay), id: \.id) { item in
            VStack(alignment: .leading, spacing: 4) {
                Text(item.description).font(.headline)
                Text(item.command).font(.footnote.monospaced())
                Text("Status: \(item.status)").font(.caption).foregroundStyle(.secondary)
                Text(formatMillis(item.timestamp)).font(.caption).foregroundStyle(.secondary)
            }
        }
    }
}

private struct RelayEventsTab: View {
    @EnvironmentObject private var vm: AppViewModel
    let relay: Relay

    @State private var startDate = Calendar.current.date(byAdding: .day, value: -1, to: Date()) ?? Date()
    @State private var endDate = Date()
    @State private var isExportingCsv = false
    @State private var exportCsvText = ""

    var body: some View {
        List {
            DatePicker("From", selection: $startDate)
            DatePicker("To", selection: $endDate)
            HStack {
                Button("Scrape") {
                    let start = Int64(startDate.timeIntervalSince1970 * 1000)
                    let end = Int64(endDate.timeIntervalSince1970 * 1000)
                    Task {
                        await vm.requestScrapeEvents(relay, start: start, end: end)
                    }
                }
                Button("Export CSV") {
                    let start = Int64(startDate.timeIntervalSince1970 * 1000)
                    let end = Int64(endDate.timeIntervalSince1970 * 1000)
                    exportCsvText = vm.buildEventsCsv(events: vm.eventsForRelay(relay, start: start, end: end))
                    isExportingCsv = true
                }
            }

            let start = Int64(startDate.timeIntervalSince1970 * 1000)
            let end = Int64(endDate.timeIntervalSince1970 * 1000)
            ForEach(vm.eventsForRelay(relay, start: start, end: end), id: \.id) { ev in
                VStack(alignment: .leading, spacing: 4) {
                    Text(ev.relayName.isEmpty ? relay.displayName : ev.relayName).font(.headline)
                    Text("Operator: \(ev.operatorPhone)").font(.caption).foregroundStyle(.secondary)
                    Text(formatMillis(ev.timestamp)).font(.caption).foregroundStyle(.secondary)
                    Text(ev.message).font(.footnote)
                }
            }
        }
        .fileExporter(
            isPresented: $isExportingCsv,
            document: TextFileDocument(text: exportCsvText),
            contentType: .commaSeparatedText,
            defaultFilename: "\(relay.displayName)_events"
        ) { _ in }
    }
}

private struct RelayNotificationsTab: View {
    @EnvironmentObject private var vm: AppViewModel
    let relay: Relay

    var body: some View {
        List(vm.notificationsForRelay(relay), id: \.id) { note in
            VStack(alignment: .leading, spacing: 4) {
                Text(note.message).font(.headline)
                Text("Type: \(note.type)").font(.caption).foregroundStyle(.secondary)
                Text(formatMillis(note.timestamp)).font(.caption).foregroundStyle(.secondary)
            }
        }
    }
}

private func formatMillis(_ value: Int64) -> String {
    guard value > 0 else { return "-" }
    let date = Date(timeIntervalSince1970: TimeInterval(value) / 1000.0)
    let formatter = DateFormatter()
    formatter.dateFormat = "yyyy-MM-dd HH:mm"
    return formatter.string(from: date)
}
