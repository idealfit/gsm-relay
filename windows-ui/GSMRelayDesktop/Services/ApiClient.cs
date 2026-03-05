using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using GSMRelayDesktop.Models;

namespace GSMRelayDesktop.Services;

public class ApiClient
{
    private readonly HttpClient _httpClient = new();
    private readonly JsonSerializerOptions _jsonOptions = new()
    {
        PropertyNameCaseInsensitive = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase
    };

    public async Task<ServerSnapshot?> GetSnapshotAsync(ServerConfig config)
    {
        var request = new HttpRequestMessage(HttpMethod.Get, BuildUrl(config.BaseUrl, "/api/snapshot"));
        ApplyAuth(request, config);
        using var response = await _httpClient.SendAsync(request);
        if (!response.IsSuccessStatusCode) return null;
        var body = await response.Content.ReadAsStringAsync();
        return JsonSerializer.Deserialize<ServerSnapshot>(body, _jsonOptions);
    }

    public async Task<bool> UploadSnapshotAsync(ServerConfig config, ServerSnapshot snapshot)
    {
        var request = new HttpRequestMessage(HttpMethod.Post, BuildUrl(config.BaseUrl, "/api/snapshot"));
        ApplyAuth(request, config);
        var payload = JsonSerializer.Serialize(snapshot, _jsonOptions);
        request.Content = new StringContent(payload, Encoding.UTF8, "application/json");
        using var response = await _httpClient.SendAsync(request);
        return response.IsSuccessStatusCode;
    }

    public async Task<List<CommandQueueItem>> GetCommandsAsync(ServerConfig config, string status, int limit)
    {
        var gatewayId = (config.GatewayId ?? string.Empty).Trim();
        var query = new List<string>
        {
            $"status={Uri.EscapeDataString(status ?? string.Empty)}",
            $"limit={limit}"
        };
        if (!string.IsNullOrWhiteSpace(gatewayId))
        {
            query.Add($"gatewayId={Uri.EscapeDataString(gatewayId)}");
        }
        var url = BuildUrl(config.BaseUrl, $"/api/commands?{string.Join("&", query)}");
        var request = new HttpRequestMessage(HttpMethod.Get, url);
        ApplyAuth(request, config);
        using var response = await _httpClient.SendAsync(request);
        if (!response.IsSuccessStatusCode) return new List<CommandQueueItem>();
        var body = await response.Content.ReadAsStringAsync();
        var wrapper = JsonSerializer.Deserialize<CommandsResponse>(body, _jsonOptions);
        return wrapper?.Commands ?? new List<CommandQueueItem>();
    }

    public async Task<CommandCreateResult> CreateCommandAsync(ServerConfig config, string relayPhone, string command, string description, string source)
    {
        var gatewayId = (config.GatewayId ?? string.Empty).Trim();
        var request = new HttpRequestMessage(HttpMethod.Post, BuildUrl(config.BaseUrl, "/api/commands"));
        ApplyAuth(request, config);
        var payload = JsonSerializer.Serialize(new
        {
            relayPhone,
            gatewayId,
            command,
            description,
            source
        }, _jsonOptions);
        request.Content = new StringContent(payload, Encoding.UTF8, "application/json");
        using var response = await _httpClient.SendAsync(request);
        if (!response.IsSuccessStatusCode)
        {
            return new CommandCreateResult
            {
                Ok = false,
                StatusCode = (int)response.StatusCode
            };
        }
        var body = await response.Content.ReadAsStringAsync();
        var data = JsonSerializer.Deserialize<CommandCreateResponse>(body, _jsonOptions);
        return new CommandCreateResult
        {
            Ok = true,
            Id = data?.Id ?? "",
            StatusCode = (int)response.StatusCode
        };
    }

    public async Task<bool> UpdateCommandStatusAsync(ServerConfig config, string id, string status, string responseText)
    {
        var request = new HttpRequestMessage(HttpMethod.Post, BuildUrl(config.BaseUrl, $"/api/commands/{id}/status"));
        ApplyAuth(request, config);
        var payload = JsonSerializer.Serialize(new
        {
            status,
            responseText
        }, _jsonOptions);
        request.Content = new StringContent(payload, Encoding.UTF8, "application/json");
        using var response = await _httpClient.SendAsync(request);
        return response.IsSuccessStatusCode;
    }

    public async Task<RelayDeleteResult> DeleteRelayDataAsync(ServerConfig config, string relayPhone)
    {
        var phone = relayPhone?.Trim() ?? "";
        if (string.IsNullOrWhiteSpace(phone))
        {
            return new RelayDeleteResult { Ok = false, StatusCode = 400 };
        }

        var request = new HttpRequestMessage(HttpMethod.Delete, BuildUrl(config.BaseUrl, $"/api/relays/{Uri.EscapeDataString(phone)}"));
        ApplyAuth(request, config);
        using var response = await _httpClient.SendAsync(request);
        return new RelayDeleteResult
        {
            Ok = response.IsSuccessStatusCode,
            StatusCode = (int)response.StatusCode
        };
    }

    public async Task<RelayDeleteResult> ClearRelayDatabaseAsync(ServerConfig config, string relayPhone)
    {
        var phone = relayPhone?.Trim() ?? "";
        if (string.IsNullOrWhiteSpace(phone))
        {
            return new RelayDeleteResult { Ok = false, StatusCode = 400 };
        }

        var request = new HttpRequestMessage(HttpMethod.Post, BuildUrl(config.BaseUrl, $"/api/relays/{Uri.EscapeDataString(phone)}/clear-db"));
        ApplyAuth(request, config);
        request.Content = new StringContent("{}", Encoding.UTF8, "application/json");
        using var response = await _httpClient.SendAsync(request);
        return new RelayDeleteResult
        {
            Ok = response.IsSuccessStatusCode,
            StatusCode = (int)response.StatusCode
        };
    }

    private static string BuildUrl(string baseUrl, string path)
    {
        var clean = baseUrl.EndsWith("/") ? baseUrl[..^1] : baseUrl;
        return clean + path;
    }

    private static void ApplyAuth(HttpRequestMessage request, ServerConfig config)
    {
        var creds = $"{config.Username}:{config.Password}";
        var bytes = Encoding.UTF8.GetBytes(creds);
        request.Headers.Authorization = new AuthenticationHeaderValue("Basic", Convert.ToBase64String(bytes));
    }

    private class CommandsResponse
    {
        public List<CommandQueueItem> Commands { get; set; } = new();
    }

    private class CommandCreateResponse
    {
        public string Id { get; set; } = "";
    }

    public class CommandCreateResult
    {
        public bool Ok { get; set; }
        public string Id { get; set; } = "";
        public int StatusCode { get; set; }
    }

    public class RelayDeleteResult
    {
        public bool Ok { get; set; }
        public int StatusCode { get; set; }
    }
}
