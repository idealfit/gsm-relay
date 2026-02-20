import Foundation

struct RelayUser: Codable, Identifiable, Hashable {
    let id: Int
    let phone: String?
    let name: String?
    let group: String?
    let addedDate: Int64?
    let known: Bool?
}

struct Relay: Codable, Identifiable, Hashable {
    let id: Int64
    let name: String
    let phoneNumber: String
    let password: String
    let location: String?
    let users: [RelayUser]?
    let lastSync: Int64?
    let cloudBackup: Bool?

    var displayName: String {
        name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? phoneNumber : name
    }
}

struct CommandHistory: Codable, Identifiable, Hashable {
    let id: Int64
    let relayName: String
    let relayPhone: String
    let command: String
    let description: String
    let timestamp: Int64
    let status: String
}

struct AppNotification: Codable, Identifiable, Hashable {
    let id: Int64
    let message: String
    let type: String
    let timestamp: Int64
    let read: Bool
    let relayPhone: String
    let relayName: String
}

struct RelayEvent: Codable, Identifiable, Hashable {
    let id: Int64
    let relayName: String
    let relayPhone: String
    let operatorPhone: String
    let message: String
    let timestamp: Int64
}

struct ServerSnapshot: Codable {
    let relays: [Relay]
    let history: [CommandHistory]
    let events: [RelayEvent]
    let locations: [String]?
}

struct CommandQueueItem: Codable, Identifiable, Hashable {
    let id: String
    let relayPhone: String
    let relayKey: String
    let gatewayId: String
    let command: String
    let description: String
    let status: String
    let source: String
    let createdAt: Int64
    let updatedAt: Int64
    let responseText: String
}

struct CommandListResponse: Codable {
    let commands: [CommandQueueItem]
}

struct CommandCreateResult {
    let ok: Bool
    let statusCode: Int
}
