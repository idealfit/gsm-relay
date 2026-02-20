import Foundation

final class ServerConfigStore {
    private let key = "gsmrelay.server.config.v1"
    private let defaults = UserDefaults.standard

    func load() -> ServerConfig {
        guard let data = defaults.data(forKey: key) else {
            return ServerConfig.defaults
        }
        do {
            let decoded = try JSONDecoder().decode(ServerConfig.self, from: data)
            return mergeWithDefaults(decoded)
        } catch {
            return ServerConfig.defaults
        }
    }

    func save(_ config: ServerConfig) {
        do {
            let data = try JSONEncoder().encode(config)
            defaults.set(data, forKey: key)
        } catch {
            // No throw, keep UI flow simple.
        }
    }

    private func mergeWithDefaults(_ config: ServerConfig) -> ServerConfig {
        ServerConfig(
            baseURL: config.baseURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? ServerConfig.defaults.baseURL : config.baseURL,
            username: config.username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? ServerConfig.defaults.username : config.username,
            password: config.password.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? ServerConfig.defaults.password : config.password,
            gatewayId: config.gatewayId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? ServerConfig.defaults.gatewayId : config.gatewayId,
            masterPhone: config.masterPhone.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? ServerConfig.defaults.masterPhone : config.masterPhone
        )
    }
}
