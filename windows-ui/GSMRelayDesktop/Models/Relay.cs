using System.Collections.Generic;

namespace GSMRelayDesktop.Models;

public class Relay
{
    public long Id { get; set; }
    public string Name { get; set; } = "";
    public string PhoneNumber { get; set; } = "";
    public string Password { get; set; } = "";
    public string Location { get; set; } = "";
    public List<RelayUser> Users { get; set; } = new();
    public long? LastSync { get; set; }
    public bool CloudBackup { get; set; }

    public string DisplayName => string.IsNullOrWhiteSpace(Name) ? PhoneNumber : Name;
}
