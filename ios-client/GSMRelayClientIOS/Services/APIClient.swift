import Foundation

enum APIError: Error {
    case invalidURL
    case invalidResponse
    case httpStatus(Int)
    case decodeFailed
}

final class APIClient {
    private let decoder = JSONDecoder()
    private let encoder = JSONEncoder()

    func downloadSnapshot(config: ServerConfig) async throws -> ServerSnapshot {
        let data = try await request(
            config: config,
            path: "/api/snapshot",
            method: "GET"
        )
        guard let snapshot = try? decoder.decode(ServerSnapshot.self, from: data) else {
            throw APIError.decodeFailed
        }
        return snapshot
    }

    func fetchCommands(config: ServerConfig, status: String = "", limit: Int = 200) async throws -> [CommandQueueItem] {
        let safeLimit = min(max(limit, 1), 200)
        var queryItems = [
            URLQueryItem(name: "status", value: status),
            URLQueryItem(name: "limit", value: String(safeLimit))
        ]
        if !config.gatewayId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            queryItems.append(URLQueryItem(name: "gatewayId", value: config.gatewayId))
        }

        let data = try await request(
            config: config,
            path: "/api/commands",
            method: "GET",
            queryItems: queryItems
        )
        guard let response = try? decoder.decode(CommandListResponse.self, from: data) else {
            throw APIError.decodeFailed
        }
        return response.commands
    }

    func uploadSnapshot(config: ServerConfig, snapshot: ServerSnapshot) async -> Bool {
        do {
            let data = try encoder.encode(snapshot)
            _ = try await request(
                config: config,
                path: "/api/snapshot",
                method: "POST",
                body: data
            )
            return true
        } catch {
            return false
        }
    }

    func createCommand(
        config: ServerConfig,
        relayPhone: String,
        command: String,
        description: String,
        source: String = "ios"
    ) async -> CommandCreateResult {
        let payload: [String: String] = [
            "relayPhone": relayPhone,
            "command": command,
            "description": description,
            "source": source,
            "gatewayId": config.gatewayId.trimmingCharacters(in: .whitespacesAndNewlines)
        ]

        do {
            _ = try await request(
                config: config,
                path: "/api/commands",
                method: "POST",
                body: try encoder.encode(payload)
            )
            return CommandCreateResult(ok: true, statusCode: 200)
        } catch APIError.httpStatus(let code) {
            return CommandCreateResult(ok: false, statusCode: code)
        } catch {
            return CommandCreateResult(ok: false, statusCode: 0)
        }
    }

    private func request(
        config: ServerConfig,
        path: String,
        method: String,
        queryItems: [URLQueryItem] = [],
        body: Data? = nil
    ) async throws -> Data {
        guard var components = URLComponents(string: config.normalizedBaseURL + path) else {
            throw APIError.invalidURL
        }
        if !queryItems.isEmpty {
            components.queryItems = queryItems
        }
        guard let url = components.url else {
            throw APIError.invalidURL
        }

        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = 8
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(basicAuthHeader(for: config), forHTTPHeaderField: "Authorization")
        if let body {
            request.httpBody = body
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw APIError.invalidResponse
        }
        guard (200...299).contains(http.statusCode) else {
            throw APIError.httpStatus(http.statusCode)
        }
        return data
    }

    private func basicAuthHeader(for config: ServerConfig) -> String {
        let raw = "\(config.username):\(config.password)"
        let encoded = Data(raw.utf8).base64EncodedString()
        return "Basic \(encoded)"
    }
}
