namespace GSMRelayDesktop.Models;

public class LogEntry
{
    public long Timestamp { get; set; }
    public string Message { get; set; } = "";
    public string TimeLocal =>
        DateTimeOffset.FromUnixTimeMilliseconds(Timestamp).ToLocalTime().ToString("yyyy-MM-dd HH:mm:ss");
}
