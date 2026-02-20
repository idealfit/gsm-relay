import SwiftUI

struct LocationDetailView: View {
    @EnvironmentObject private var vm: AppViewModel

    let locationName: String

    @State private var selectedTab = 0
    @State private var showAddRelay = false
    @State private var editingRelay: Relay?
    @State private var deleteRelay: Relay?
    @State private var showAddUserToSelection = false
    @State private var selectedRelayIds: Set<Int64> = []

    var relays: [Relay] { vm.relaysInSelectedLocation() }

    var body: some View {
        VStack(spacing: 0) {
            Picker("Tab", selection: $selectedTab) {
                Text("Relays").tag(0)
                Text("Queue").tag(1)
                Text("Events").tag(2)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal)
            .padding(.top, 8)

            switch selectedTab {
            case 0:
                relaysTab
            case 1:
                queueTab
            default:
                eventsTab
            }
        }
        .onAppear {
            selectedRelayIds = Set(relays.map(\.id))
        }
        .sheet(isPresented: $showAddRelay) {
            RelayEditSheet(
                title: "New relay",
                initialName: "",
                initialPhone: "",
                initialPassword: "2005",
                initialLocation: locationName == "Fara locatie" ? "" : locationName
            ) { name, phone, password, location in
                vm.addRelay(name: name, phone: phone, password: password, location: location)
            }
        }
        .sheet(item: $editingRelay) { relay in
            RelayEditSheet(
                title: "Edit relay",
                initialName: relay.name,
                initialPhone: relay.phoneNumber,
                initialPassword: relay.password,
                initialLocation: relay.location ?? ""
            ) { name, phone, password, location in
                vm.updateRelay(relay.id, name: name, phone: phone, password: password, location: location)
            }
        }
        .sheet(isPresented: $showAddUserToSelection) {
            AddUserSelectionSheet(relayCount: selectedRelayIds.count) { phone, name, group in
                Task {
                    await vm.addUserToRelays(
                        relayIds: Array(selectedRelayIds),
                        phone: phone,
                        name: name,
                        group: group
                    )
                }
            }
        }
        .alert("Delete relay?", isPresented: Binding(
            get: { deleteRelay != nil },
            set: { if !$0 { deleteRelay = nil } }
        )) {
            Button("Cancel", role: .cancel) { deleteRelay = nil }
            Button("Delete", role: .destructive) {
                if let relay = deleteRelay {
                    vm.deleteRelay(relay.id)
                }
                deleteRelay = nil
            }
        } message: {
            Text("Relay and associated data will be removed.")
        }
    }

    private var relaysTab: some View {
        List {
            HStack(spacing: 8) {
                Text("Selected: \(selectedRelayIds.count)/\(relays.count)")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Spacer()
                Button("Select all") {
                    selectedRelayIds = Set(relays.map(\.id))
                }
                Button("Add user") {
                    showAddUserToSelection = true
                }
                .disabled(selectedRelayIds.isEmpty)
            }

            ForEach(relays) { relay in
                HStack(spacing: 12) {
                    Toggle("", isOn: Binding(
                        get: { selectedRelayIds.contains(relay.id) },
                        set: { isOn in
                            if isOn {
                                selectedRelayIds.insert(relay.id)
                            } else {
                                selectedRelayIds.remove(relay.id)
                            }
                        }
                    ))
                    .labelsHidden()
                    .frame(width: 32)

                    VStack(alignment: .leading, spacing: 4) {
                        Text(relay.displayName).font(.headline)
                        Text(relay.phoneNumber).font(.subheadline).foregroundStyle(.secondary)
                        let userCount = relay.users?.filter { !($0.phone ?? "").isEmpty }.count ?? 0
                        Text("\(userCount)/200 users").font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button {
                        editingRelay = relay
                    } label: { Image(systemName: "pencil") }
                    .buttonStyle(.borderless)
                    Button(role: .destructive) {
                        deleteRelay = relay
                    } label: { Image(systemName: "trash") }
                    .buttonStyle(.borderless)
                }
                .contentShape(Rectangle())
                .onTapGesture {
                    vm.selectRelay(relay)
                }
            }
        }
        .overlay(alignment: .bottomTrailing) {
            Button {
                showAddRelay = true
            } label: {
                Image(systemName: "plus")
                    .font(.title2)
                    .foregroundStyle(.white)
                    .padding(14)
                    .background(Color.accentColor, in: Circle())
                    .shadow(radius: 4)
            }
            .padding(20)
        }
    }

    private var queueTab: some View {
        List(vm.queueForSelectedLocation(), id: \.id) { cmd in
            VStack(alignment: .leading, spacing: 4) {
                Text(cmd.description.isEmpty ? "Command" : cmd.description).font(.headline)
                Text(cmd.command).font(.footnote.monospaced())
                Text("Status: \(cmd.status)").font(.caption).foregroundStyle(.secondary)
                if !cmd.responseText.isEmpty {
                    Text("Response: \(cmd.responseText)").font(.caption).foregroundStyle(.secondary)
                }
            }
            .padding(.vertical, 2)
        }
    }

    private var eventsTab: some View {
        List(vm.eventsForSelectedLocation(), id: \.id) { ev in
            VStack(alignment: .leading, spacing: 4) {
                Text(ev.relayName.isEmpty ? ev.relayPhone : ev.relayName).font(.headline)
                Text("Operator: \(ev.operatorPhone)").font(.caption).foregroundStyle(.secondary)
                Text(formatMillis(ev.timestamp)).font(.caption).foregroundStyle(.secondary)
                Text(ev.message).font(.footnote)
            }
            .padding(.vertical, 2)
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

private struct AddUserSelectionSheet: View {
    @Environment(\.dismiss) private var dismiss

    let relayCount: Int
    let onAdd: (String, String, String) -> Void

    @State private var phone = ""
    @State private var name = ""
    @State private var group = "general"

    var body: some View {
        NavigationStack {
            Form {
                Section("Selected relays: \(relayCount)") {
                    TextField("Phone", text: $phone)
                        .keyboardType(.phonePad)
                    TextField("Name (optional)", text: $name)
                    TextField("Group", text: $group)
                }
            }
            .navigationTitle("Add user")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Send") {
                        onAdd(phone, name, group)
                        dismiss()
                    }
                    .disabled(phone.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}

private struct RelayEditSheet: View {
    @Environment(\.dismiss) private var dismiss

    let title: String
    let onSave: (String, String, String, String) -> Void

    @State private var name: String
    @State private var phone: String
    @State private var password: String
    @State private var location: String

    init(
        title: String,
        initialName: String,
        initialPhone: String,
        initialPassword: String,
        initialLocation: String,
        onSave: @escaping (String, String, String, String) -> Void
    ) {
        self.title = title
        self.onSave = onSave
        _name = State(initialValue: initialName)
        _phone = State(initialValue: initialPhone)
        _password = State(initialValue: initialPassword)
        _location = State(initialValue: initialLocation)
    }

    var body: some View {
        NavigationStack {
            Form {
                TextField("Name", text: $name)
                TextField("Phone", text: $phone)
                    .keyboardType(.phonePad)
                TextField("Password", text: $password)
                TextField("Location", text: $location)
            }
            .navigationTitle(title)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Save") {
                        onSave(name, phone, password, location)
                        dismiss()
                    }
                    .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || phone.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}
