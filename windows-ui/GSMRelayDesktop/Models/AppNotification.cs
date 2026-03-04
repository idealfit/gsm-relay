namespace GSMRelayDesktop.Models;

public class AppNotification
{
    public long Id { get; set; }
    public string Message { get; set; } = "";
    public string Type { get; set; } = "info";
    public long Timestamp { get; set; }
    public bool Read { get; set; }
}
