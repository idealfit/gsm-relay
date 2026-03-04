using System.Collections.Generic;

namespace GSMRelayDesktop.Models;

public class ServerSnapshot
{
    public List<Relay> Relays { get; set; } = new();
    public List<CommandHistory> History { get; set; } = new();
    public List<RelayEvent> Events { get; set; } = new();
    public List<string> Locations { get; set; } = new();
}
