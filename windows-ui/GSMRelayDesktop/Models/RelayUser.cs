namespace GSMRelayDesktop.Models;

public class RelayUser
{
    public int Id { get; set; }
    public string Phone { get; set; } = "";
    public string Name { get; set; } = "";
    public string Group { get; set; } = "general";
    public long? AddedDate { get; set; }
    public bool Known { get; set; }
}
