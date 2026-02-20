import SwiftUI

struct RelaysHomeView: View {
    @EnvironmentObject private var vm: AppViewModel

    @State private var showAddLocation = false
    @State private var locationToRename: String?
    @State private var showRenameLocation = false
    @State private var locationToDelete: String?

    var body: some View {
        Group {
            if let relay = vm.selectedRelay {
                RelayDetailView(relay: relay)
            } else if let location = vm.selectedLocation {
                LocationDetailView(locationName: location)
            } else {
                locationList
            }
        }
        .navigationTitle(vm.selectedRelay?.displayName ?? vm.selectedLocation ?? "Locations")
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                if vm.selectedRelay != nil {
                    Button {
                        vm.selectRelay(nil)
                    } label: {
                        Label("Back", systemImage: "chevron.left")
                    }
                } else if vm.selectedLocation != nil {
                    Button {
                        vm.selectLocation(nil)
                    } label: {
                        Label("Back", systemImage: "chevron.left")
                    }
                }
            }
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
        .sheet(isPresented: $showAddLocation) {
            LocationNameSheet(
                title: "Add location",
                initialValue: "",
                confirmLabel: "Add"
            ) { value in
                vm.addLocation(value)
            }
        }
        .sheet(isPresented: $showRenameLocation) {
            LocationNameSheet(
                title: "Rename location",
                initialValue: locationToRename ?? "",
                confirmLabel: "Save"
            ) { value in
                if let source = locationToRename {
                    vm.renameLocation(from: source, to: value)
                }
            }
        }
        .alert("Delete location?", isPresented: Binding(
            get: { locationToDelete != nil },
            set: { if !$0 { locationToDelete = nil } }
        )) {
            Button("Cancel", role: .cancel) {
                locationToDelete = nil
            }
            Button("Delete", role: .destructive) {
                if let locationToDelete {
                    vm.deleteLocation(locationToDelete)
                }
                locationToDelete = nil
            }
        } message: {
            Text("Location and associated relays data will be removed.")
        }
    }

    private var locationList: some View {
        List {
            ForEach(vm.locations, id: \.self) { location in
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(location)
                            .font(.headline)
                        Text("\(vm.relays.filter { normalizeLocation($0.location) == location }.count) relays")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button {
                        locationToRename = location
                        showRenameLocation = true
                    } label: {
                        Image(systemName: "pencil")
                    }
                    .buttonStyle(.borderless)
                    Button(role: .destructive) {
                        locationToDelete = location
                    } label: {
                        Image(systemName: "trash")
                    }
                    .buttonStyle(.borderless)
                }
                .contentShape(Rectangle())
                .onTapGesture {
                    vm.selectLocation(location)
                }
            }
        }
        .overlay(alignment: .bottomTrailing) {
            Button {
                showAddLocation = true
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

    private func normalizeLocation(_ location: String?) -> String {
        let value = (location ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? "Fara locatie" : value
    }
}

private struct LocationNameSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var value: String

    let title: String
    let confirmLabel: String
    let onConfirm: (String) -> Void

    init(title: String, initialValue: String, confirmLabel: String, onConfirm: @escaping (String) -> Void) {
        self.title = title
        self.confirmLabel = confirmLabel
        self.onConfirm = onConfirm
        _value = State(initialValue: initialValue)
    }

    var body: some View {
        NavigationStack {
            Form {
                TextField("Location name", text: $value)
            }
            .navigationTitle(title)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(confirmLabel) {
                        onConfirm(value)
                        dismiss()
                    }
                }
            }
        }
    }
}
