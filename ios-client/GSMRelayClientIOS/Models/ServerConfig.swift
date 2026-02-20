import Foundation

struct ServerConfig: Codable, Equatable {
    var baseURL: String
    var username: String
    var password: String
    var gatewayId: String
    var masterPhone: String

    static let defaults = ServerConfig(
        baseURL: "http://86.120.150.58:5174",
        username: "admin",
        password: "admin1316",
        gatewayId: "pQF6bci9",
        masterPhone: "0724264464"
    )

    var isValid: Bool {
        !baseURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
        !username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
        !password.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var normalizedBaseURL: String {
        let trimmed = baseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.hasSuffix("/") {
            return String(trimmed.dropLast())
        }
        return trimmed
    }
}
