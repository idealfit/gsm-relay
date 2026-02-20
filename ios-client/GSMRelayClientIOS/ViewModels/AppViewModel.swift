import Foundation

@MainActor
final class AppViewModel: ObservableObject {
    @Published var config: ServerConfig
    @Published var relays: [Relay] = []
    @Published var history: [CommandHistory] = []
    @Published var events: [RelayEvent] = []
    @Published var commands: [CommandQueueItem] = []
    @Published var notifications: [AppNotification] = []
    @Published var locations: [String] = []
    @Published var selectedLocation: String?
    @Published var selectedRelay: Relay?
    @Published var isLoading = false
    @Published var statusMessage = "Ready"

    private let store = ServerConfigStore()
    private let api = APIClient()

    private var explicitLocations: [String] = []
    private var autoSyncTask: Task<Void, Never>?

    init() {
        self.config = store.load()
        if config.isValid {
            startAutoSync()
        }
        Task {
            await refreshAll()
        }
    }

    deinit {
        autoSyncTask?.cancel()
    }

    // MARK: - Config

    func saveConfig(_ updated: ServerConfig) {
        config = ServerConfig(
            baseURL: updated.normalizedBaseURL,
            username: updated.username.trimmingCharacters(in: .whitespacesAndNewlines),
            password: updated.password,
            gatewayId: updated.gatewayId.trimmingCharacters(in: .whitespacesAndNewlines),
            masterPhone: updated.masterPhone.trimmingCharacters(in: .whitespacesAndNewlines)
        )
        store.save(config)
        statusMessage = "Server settings saved"
        if config.isValid {
            startAutoSync()
            Task { await refreshAll() }
        } else {
            autoSyncTask?.cancel()
            autoSyncTask = nil
        }
    }

    func resetConfigToDefaults() {
        config = ServerConfig.defaults
        store.save(config)
        statusMessage = "Defaults restored"
        startAutoSync()
        Task { await refreshAll() }
    }

    // MARK: - Selection

    func selectLocation(_ location: String?) {
        selectedLocation = location
        if let selectedRelay, let location {
            let currentLocation = normalizeLocation(selectedRelay.location)
            if currentLocation != location {
                self.selectedRelay = nil
            }
        }
    }

    func selectRelay(_ relay: Relay?) {
        selectedRelay = relay
        if let relay {
            selectedLocation = normalizeLocation(relay.location)
        }
    }

    // MARK: - Sync

    func refreshAll() async {
        guard config.isValid else {
            statusMessage = "Incomplete settings"
            return
        }
        isLoading = true
        defer { isLoading = false }

        do {
            let snapshot = try await api.downloadSnapshot(config: config)
            relays = mergeRelays(local: relays, remote: snapshot.relays)
                .sorted { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }
            history = snapshot.history.sorted { $0.timestamp > $1.timestamp }
            events = snapshot.events.sorted { $0.timestamp > $1.timestamp }
            if let remoteLocations = snapshot.locations {
                explicitLocations = remoteLocations.compactMap(normalizeLocationName)
            }
            refreshLocationsState()
            restoreSelections()

            let items = try await api.fetchCommands(config: config, status: "", limit: 200)
            commands = items.sorted { $0.createdAt > $1.createdAt }

            statusMessage = "Synced: \(relays.count) relays, \(commands.count) commands"
        } catch {
            addNotification("Sync failed", type: "error")
            statusMessage = "Sync failed"
        }
    }

    func syncCommands() async {
        guard config.isValid else { return }
        do {
            let items = try await api.fetchCommands(config: config, status: "", limit: 200)
            commands = items.sorted { $0.createdAt > $1.createdAt }
        } catch {
            addNotification("Command queue load failed", type: "error")
        }
    }

    private func uploadSnapshotNow() async {
        guard config.isValid else { return }
        let snapshot = ServerSnapshot(
            relays: relays,
            history: history,
            events: events,
            locations: locations
        )
        let ok = await api.uploadSnapshot(config: config, snapshot: snapshot)
        if !ok {
            addNotification("Server upload failed", type: "error")
        }
    }

    private func startAutoSync() {
        autoSyncTask?.cancel()
        autoSyncTask = Task { [weak self] in
            while let self {
                try? await Task.sleep(nanoseconds: 15_000_000_000)
                if Task.isCancelled { break }
                await self.refreshAll()
            }
        }
    }

    // MARK: - Locations

    func addLocation(_ name: String) {
        guard let normalized = normalizeLocationName(name) else {
            addNotification("Location name required", type: "error")
            return
        }
        if locations.contains(where: { $0.caseInsensitiveCompare(normalized) == .orderedSame }) {
            addNotification("Location already exists", type: "error")
            return
        }
        explicitLocations = (explicitLocations + [normalized]).uniquedCaseInsensitive().sortedCaseInsensitive()
        refreshLocationsState()
        statusMessage = "Location added"
        Task { await uploadSnapshotNow() }
    }

    func renameLocation(from oldName: String, to newName: String) {
        let source = normalizeLocation(oldName)
        guard let target = normalizeLocationName(newName) else {
            addNotification("New location name required", type: "error")
            return
        }
        if source.caseInsensitiveCompare(target) == .orderedSame {
            return
        }
        relays = relays.map { relay in
            if normalizeLocation(relay.location).caseInsensitiveCompare(source) == .orderedSame {
                return relay.with(location: target, lastSync: nowMs())
            }
            return relay
        }
        explicitLocations = explicitLocations
            .filter { $0.caseInsensitiveCompare(source) != .orderedSame }
        explicitLocations.append(target)
        explicitLocations = explicitLocations.uniquedCaseInsensitive().sortedCaseInsensitive()
        refreshLocationsState()
        restoreSelections()
        Task { await uploadSnapshotNow() }
    }

    func deleteLocation(_ name: String) {
        let source = normalizeLocation(name)
        let toDelete = relays.filter { normalizeLocation($0.location).caseInsensitiveCompare(source) == .orderedSame }
        deleteRelaysAndAssociatedData(toDelete)
        explicitLocations = explicitLocations
            .filter { $0.caseInsensitiveCompare(source) != .orderedSame }
            .uniquedCaseInsensitive()
            .sortedCaseInsensitive()
        refreshLocationsState()
        if selectedLocation?.caseInsensitiveCompare(source) == .orderedSame {
            selectedLocation = nil
            selectedRelay = nil
        }
        Task { await uploadSnapshotNow() }
    }

    // MARK: - Relays

    func addRelay(name: String, phone: String, password: String, location: String) {
        let relay = Relay(
            id: nowMs(),
            name: name.trimmingCharacters(in: .whitespacesAndNewlines),
            phoneNumber: phone.trimmingCharacters(in: .whitespacesAndNewlines),
            password: password.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "2005" : password.trimmingCharacters(in: .whitespacesAndNewlines),
            location: location.trimmingCharacters(in: .whitespacesAndNewlines),
            users: (1...200).map { idx in RelayUser(id: idx, phone: "", name: "", group: "general", addedDate: nil, known: false) },
            lastSync: nowMs(),
            cloudBackup: false
        )
        relays.append(relay)
        relays.sort { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }
        refreshLocationsState()
        selectRelay(relay)
        Task { await uploadSnapshotNow() }
    }

    func updateRelay(_ relayId: Int64, name: String, phone: String, password: String, location: String) {
        guard let idx = relays.firstIndex(where: { $0.id == relayId }) else { return }
        let updated = relays[idx].with(
            name: name.trimmingCharacters(in: .whitespacesAndNewlines),
            phoneNumber: phone.trimmingCharacters(in: .whitespacesAndNewlines),
            password: password.trimmingCharacters(in: .whitespacesAndNewlines),
            location: location.trimmingCharacters(in: .whitespacesAndNewlines),
            lastSync: nowMs()
        )
        relays[idx] = updated
        relays.sort { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }
        if selectedRelay?.id == relayId {
            selectedRelay = updated
        }
        refreshLocationsState()
        Task { await uploadSnapshotNow() }
    }

    func deleteRelay(_ relayId: Int64) {
        guard let relay = relays.first(where: { $0.id == relayId }) else { return }
        deleteRelaysAndAssociatedData([relay])
        Task { await uploadSnapshotNow() }
    }

    // MARK: - Users

    func addUser(relayId: Int64, userId: Int, phone: String, name: String, group: String) async {
        guard let relay = relays.first(where: { $0.id == relayId }) else { return }
        let command = "\(relay.password)A\(String(format: "%03d", userId))#\(phone)#"
        let description = "Add user \(name.isEmpty ? phone : name) (\(group))"
        let result = await api.createCommand(
            config: config,
            relayPhone: relay.phoneNumber,
            command: command,
            description: description,
            source: "ios"
        )
        guard result.ok else {
            addNotification("User add command failed", type: "error", relay: relay)
            return
        }
        applyUserPatch(relayId: relayId, userId: userId, phone: phone, name: name, group: group, known: true)
        addHistory(relay: relay, command: command, description: description, status: "queued")
        await syncCommands()
        await uploadSnapshotNow()
    }

    func deleteUser(relayId: Int64, userId: Int) async {
        guard let relay = relays.first(where: { $0.id == relayId }) else { return }
        let command = "\(relay.password)A\(String(format: "%03d", userId))##"
        let description = "Delete user slot \(userId)"
        let result = await api.createCommand(
            config: config,
            relayPhone: relay.phoneNumber,
            command: command,
            description: description,
            source: "ios"
        )
        guard result.ok else {
            addNotification("User delete command failed", type: "error", relay: relay)
            return
        }
        applyUserPatch(relayId: relayId, userId: userId, phone: "", name: "", group: "general", known: true)
        addHistory(relay: relay, command: command, description: description, status: "queued")
        await syncCommands()
        await uploadSnapshotNow()
    }

    func addUserToRelays(relayIds: [Int64], phone: String, name: String, group: String) async {
        for relayId in relayIds {
            guard let relay = relays.first(where: { $0.id == relayId }) else { continue }
            let slot = relay.users?.sorted(by: { $0.id < $1.id }).first(where: { ($0.known ?? false) && (($0.phone ?? "").isEmpty) })
            guard let userSlot = slot else { continue }
            await addUser(relayId: relayId, userId: userSlot.id, phone: phone, name: name, group: group)
        }
    }

    // MARK: - Relay commands

    func queryUsers(_ relay: Relay, start: Int, end: Int) async {
        let safeStart = min(max(start, 1), 200)
        let safeEnd = min(max(end, 1), 200)
        await sendSimpleCommand(
            relay: relay,
            command: "\(relay.password)AL\(String(format: "%03d", safeStart))#\(String(format: "%03d", safeEnd))#",
            description: "Query users \(safeStart)-\(safeEnd)"
        )
    }

    func changeRelayPassword(_ relay: Relay, newPassword: String) async {
        await sendSimpleCommand(
            relay: relay,
            command: "\(relay.password)P\(newPassword)",
            description: "Change password"
        )
    }

    func setRelayTimer(_ relay: Relay, seconds: Int) async {
        let safe = min(max(seconds, 0), 999)
        await sendSimpleCommand(
            relay: relay,
            command: "\(relay.password)GOT\(safe)#",
            description: "Set timer \(safe)s"
        )
    }

    func allowAll(_ relay: Relay) async {
        await sendSimpleCommand(relay: relay, command: "\(relay.password)ALL#", description: "Allow all")
    }

    func allowAuthorizedOnly(_ relay: Relay) async {
        await sendSimpleCommand(relay: relay, command: "\(relay.password)AUT#", description: "Allow authorized")
    }

    func requestScrapeEvents(_ relay: Relay, start: Int64, end: Int64) async {
        await sendSimpleCommand(
            relay: relay,
            command: "SCRAPE_EVENTS|\(start)|\(end)",
            description: "Scrape events"
        )
    }

    private func sendSimpleCommand(relay: Relay, command: String, description: String) async {
        guard config.isValid else { return }
        let result = await api.createCommand(
            config: config,
            relayPhone: relay.phoneNumber,
            command: command,
            description: description,
            source: "ios"
        )
        if result.ok {
            addHistory(relay: relay, command: command, description: description, status: "queued")
            addNotification("Command queued", type: "success", relay: relay)
            await syncCommands()
        } else if result.statusCode == 404 {
            addNotification("Server missing /api/commands", type: "error", relay: relay)
        } else {
            addNotification("Command rejected (HTTP \(result.statusCode))", type: "error", relay: relay)
        }
    }

    // MARK: - Helpers

    func relaysInSelectedLocation() -> [Relay] {
        guard let selectedLocation else { return [] }
        return relays
            .filter { normalizeLocation($0.location).caseInsensitiveCompare(selectedLocation) == .orderedSame }
            .sorted { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }
    }

    func queueForSelectedLocation() -> [CommandQueueItem] {
        let relays = relaysInSelectedLocation()
        let keys = Set(relays.map { relayPhoneKey($0.phoneNumber) })
        return commands.filter { keys.contains(relayPhoneKey($0.relayPhone)) }
            .sorted { $0.createdAt > $1.createdAt }
    }

    func eventsForSelectedLocation() -> [RelayEvent] {
        let relays = relaysInSelectedLocation()
        let keys = Set(relays.map { relayPhoneKey($0.phoneNumber) })
        return events.filter { keys.contains(relayPhoneKey($0.relayPhone)) }
            .sorted { $0.timestamp > $1.timestamp }
    }

    func queueForRelay(_ relay: Relay) -> [CommandQueueItem] {
        let key = relayPhoneKey(relay.phoneNumber)
        return commands.filter { relayPhoneKey($0.relayPhone) == key }.sorted { $0.createdAt > $1.createdAt }
    }

    func historyForRelay(_ relay: Relay) -> [CommandHistory] {
        let key = relayPhoneKey(relay.phoneNumber)
        return history.filter { relayPhoneKey($0.relayPhone) == key }.sorted { $0.timestamp > $1.timestamp }
    }

    func eventsForRelay(_ relay: Relay) -> [RelayEvent] {
        let key = relayPhoneKey(relay.phoneNumber)
        return events.filter { relayPhoneKey($0.relayPhone) == key }.sorted { $0.timestamp > $1.timestamp }
    }

    func notificationsForRelay(_ relay: Relay) -> [AppNotification] {
        let key = relayPhoneKey(relay.phoneNumber)
        return notifications.filter { relayPhoneKey($0.relayPhone) == key }.sorted { $0.timestamp > $1.timestamp }
    }

    private func addHistory(relay: Relay, command: String, description: String, status: String) {
        let item = CommandHistory(
            id: nowMs(),
            relayName: relay.name,
            relayPhone: relay.phoneNumber,
            command: command,
            description: description,
            timestamp: nowMs(),
            status: status
        )
        history.insert(item, at: 0)
        if history.count > 200 {
            history = Array(history.prefix(200))
        }
    }

    private func addNotification(_ message: String, type: String, relay: Relay? = nil) {
        let note = AppNotification(
            id: nowMs(),
            message: message,
            type: type,
            timestamp: nowMs(),
            read: false,
            relayPhone: relay?.phoneNumber ?? "",
            relayName: relay?.name ?? ""
        )
        notifications.insert(note, at: 0)
        if notifications.count > 100 {
            notifications = Array(notifications.prefix(100))
        }
        statusMessage = message
    }

    private func applyUserPatch(relayId: Int64, userId: Int, phone: String, name: String, group: String, known: Bool) {
        guard let idx = relays.firstIndex(where: { $0.id == relayId }) else { return }
        let relay = relays[idx]
        let users = relay.users ?? []
        let patched = users.map { user in
            if user.id == userId {
                return RelayUser(
                    id: userId,
                    phone: phone,
                    name: name,
                    group: group.isEmpty ? "general" : group,
                    addedDate: phone.isEmpty ? nil : nowMs(),
                    known: known
                )
            }
            return user
        }
        let updated = relay.with(users: patched, lastSync: nowMs())
        relays[idx] = updated
        if selectedRelay?.id == relayId {
            selectedRelay = updated
        }
    }

    private func refreshLocationsState() {
        let relayLocations = relays.compactMap { normalizeLocationName($0.location) }
        locations = (relayLocations + explicitLocations).uniquedCaseInsensitive().sortedCaseInsensitive()
    }

    private func restoreSelections() {
        if let selectedRelay {
            let previousKey = relayPhoneKey(selectedRelay.phoneNumber)
            self.selectedRelay = relays.first(where: { relayPhoneKey($0.phoneNumber) == previousKey })
        }
        if let selectedLocation {
            if !locations.contains(where: { $0.caseInsensitiveCompare(selectedLocation) == .orderedSame }) {
                self.selectedLocation = nil
            }
        }
    }

    private func mergeRelays(local: [Relay], remote: [Relay]) -> [Relay] {
        if local.isEmpty { return normalizeUsers(remote) }
        if remote.isEmpty { return normalizeUsers(local) }

        var result: [Relay] = []
        for remoteRelay in remote {
            if let localRelay = local.first(where: { sameRelayNumber($0.phoneNumber, remoteRelay.phoneNumber) }) {
                let preferred: Relay
                if (localRelay.lastSync ?? 0) > (remoteRelay.lastSync ?? 0) {
                    preferred = localRelay
                } else {
                    preferred = remoteRelay.with(id: localRelay.id)
                }
                result.append(preferred)
            } else {
                result.append(remoteRelay)
            }
        }
        return normalizeUsers(result)
    }

    private func normalizeUsers(_ relays: [Relay]) -> [Relay] {
        relays.map { relay in
            var byId: [Int: RelayUser] = [:]
            (relay.users ?? []).forEach { byId[$0.id] = $0 }
            let full = (1...200).map { id in
                byId[id] ?? RelayUser(id: id, phone: "", name: "", group: "general", addedDate: nil, known: false)
            }
            return relay.with(users: full)
        }
    }

    private func deleteRelaysAndAssociatedData(_ relaysToDelete: [Relay]) {
        guard !relaysToDelete.isEmpty else { return }
        let keys = Set(relaysToDelete.map { relayPhoneKey($0.phoneNumber) })
        relays.removeAll { relay in keys.contains(relayPhoneKey(relay.phoneNumber)) }
        history.removeAll { keys.contains(relayPhoneKey($0.relayPhone)) }
        events.removeAll { keys.contains(relayPhoneKey($0.relayPhone)) }
        notifications.removeAll { keys.contains(relayPhoneKey($0.relayPhone)) }
        if let selectedRelay, keys.contains(relayPhoneKey(selectedRelay.phoneNumber)) {
            self.selectedRelay = nil
        }
        refreshLocationsState()
    }

    private func sameRelayNumber(_ a: String, _ b: String) -> Bool {
        relayPhoneKey(a) == relayPhoneKey(b) && !relayPhoneKey(a).isEmpty
    }

    private func relayPhoneKey(_ phone: String) -> String {
        let digits = phone.filter(\.isNumber)
        return String(digits.suffix(8))
    }

    private func nowMs() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }

    private func normalizeLocationName(_ value: String?) -> String? {
        let trimmed = (value ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return nil }
        if trimmed.caseInsensitiveCompare("Fara locatie") == .orderedSame { return nil }
        return trimmed
    }

    private func normalizeLocation(_ value: String?) -> String {
        let trimmed = (value ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "Fara locatie" : trimmed
    }
}

private extension Array where Element == String {
    func uniquedCaseInsensitive() -> [String] {
        var seen: Set<String> = []
        return self.filter {
            let key = $0.lowercased()
            if seen.contains(key) { return false }
            seen.insert(key)
            return true
        }
    }

    func sortedCaseInsensitive() -> [String] {
        self.sorted { $0.localizedCaseInsensitiveCompare($1) == .orderedAscending }
    }
}

private extension Relay {
    func with(
        id: Int64? = nil,
        name: String? = nil,
        phoneNumber: String? = nil,
        password: String? = nil,
        location: String? = nil,
        users: [RelayUser]? = nil,
        lastSync: Int64? = nil,
        cloudBackup: Bool? = nil
    ) -> Relay {
        Relay(
            id: id ?? self.id,
            name: name ?? self.name,
            phoneNumber: phoneNumber ?? self.phoneNumber,
            password: password ?? self.password,
            location: location ?? self.location,
            users: users ?? self.users,
            lastSync: lastSync ?? self.lastSync,
            cloudBackup: cloudBackup ?? self.cloudBackup
        )
    }
}
