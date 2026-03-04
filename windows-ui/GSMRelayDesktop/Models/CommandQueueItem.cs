namespace GSMRelayDesktop.Models;

public class CommandQueueItem
{
    public string Id { get; set; } = "";
    public string RelayPhone { get; set; } = "";
    public string RelayKey { get; set; } = "";
    public string GatewayId { get; set; } = "";
    public string Command { get; set; } = "";
    public string Description { get; set; } = "";
    public string Status { get; set; } = "";
    public string Source { get; set; } = "";
    public long CreatedAt { get; set; }
    public long UpdatedAt { get; set; }
    public string ResponseText { get; set; } = "";
}
