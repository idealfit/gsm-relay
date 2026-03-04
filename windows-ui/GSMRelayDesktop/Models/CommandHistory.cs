namespace GSMRelayDesktop.Models;

public class CommandHistory
{
    public long Id { get; set; }
    public string RelayName { get; set; } = "";
    public string RelayPhone { get; set; } = "";
    public string Command { get; set; } = "";
    public string Description { get; set; } = "";
    public long Timestamp { get; set; }
    public string Status { get; set; } = "";
}
