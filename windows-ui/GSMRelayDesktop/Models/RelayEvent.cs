using System;
using System.Text.Json.Serialization;

namespace GSMRelayDesktop.Models;

public class RelayEvent
{
    public long Id { get; set; }
    public string RelayName { get; set; } = "";
    public string RelayPhone { get; set; } = "";
    public string OperatorPhone { get; set; } = "";
    public string Message { get; set; } = "";
    public long Timestamp { get; set; }

    [JsonIgnore]
    public string TimeLocal => DateTimeOffset.FromUnixTimeMilliseconds(Timestamp)
        .ToLocalTime()
        .ToString("yyyy-MM-dd HH:mm");
}
