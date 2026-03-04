using System;
using System.IO;
using System.Text.Json;
using GSMRelayDesktop.Models;

namespace GSMRelayDesktop.Helpers;

public static class ConfigStore
{
    private static string GetConfigPath()
    {
        var root = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
        return Path.Combine(root, "GSMRelayDesktop", "server-config.json");
    }

    public static ServerConfig? LoadServerConfig()
    {
        try
        {
            var path = GetConfigPath();
            if (!File.Exists(path)) return null;
            var json = File.ReadAllText(path);
            return JsonSerializer.Deserialize<ServerConfig>(json);
        }
        catch
        {
            return null;
        }
    }

    public static void SaveServerConfig(ServerConfig config)
    {
        try
        {
            var path = GetConfigPath();
            var dir = Path.GetDirectoryName(path);
            if (!string.IsNullOrWhiteSpace(dir))
            {
                Directory.CreateDirectory(dir);
            }
            var json = JsonSerializer.Serialize(config, new JsonSerializerOptions
            {
                WriteIndented = true
            });
            File.WriteAllText(path, json);
        }
        catch
        {
            // ignore persistence errors
        }
    }
}
