namespace ServiceLib.Handler;

/// <summary>
/// "Сайты страны напрямую": the country's addresses and domains go directly, the rest through the server.
/// Same data as shared/country-profiles.json and Android net/CountryProfiles.kt (tests compare them).
/// </summary>
public static class CountryProfiles
{
    public sealed record Country(string Id, string NameRu, string NameEn, string Flag, string GeoIp, string[] GeoSites, string[] DomainSuffixes);

    public const string None = "none";
    private const string File_ = "country_direct";

    public static readonly IReadOnlyList<Country> All =
    [
        new("ru", "Россия", "Russia", "🇷🇺", "ru", ["category-ru", "category-gov-ru", "category-bank-ru", "tld-ru"], ["ru", "su", "xn--p1ai"]),
        new("by", "Беларусь", "Belarus", "🇧🇾", "by", [], ["by", "xn--90ais"]),
        new("kz", "Казахстан", "Kazakhstan", "🇰🇿", "kz", [], ["kz", "xn--80ao21a"]),
        new("uz", "Узбекистан", "Uzbekistan", "🇺🇿", "uz", [], ["uz"]),
        new("ua", "Украина", "Ukraine", "🇺🇦", "ua", [], ["ua", "xn--j1amh"]),
        new("tr", "Турция", "Turkey", "🇹🇷", "tr", [], ["tr"]),
        new("ae", "ОАЭ", "UAE", "🇦🇪", "ae", [], ["ae"]),
        new("sa", "Саудовская Аравия", "Saudi Arabia", "🇸🇦", "sa", [], ["sa"]),
        new("ir", "Иран", "Iran", "🇮🇷", "ir", ["category-ir"], ["ir"]),
        new("cn", "Китай (материковый)", "China (mainland)", "🇨🇳", "cn", ["cn"], ["cn"]),
        new("cu", "Куба", "Cuba", "🇨🇺", "cu", [], ["cu"]),
    ];

    public static Country? ById(string? id) => All.FirstOrDefault(c => c.Id == id);

    public static List<string> Domains(Country c) => [.. c.GeoSites.Select(g => $"geosite:{g}"), .. c.DomainSuffixes.Select(d => $"domain:{d}")];

    public static List<string> Ips(Country c) => [$"geoip:{c.GeoIp}"];

    /// <summary>The chosen country, kept in a small file in the config folder; none by default.</summary>
    public static Country? Current
    {
        get
        {
            try
            {
                var path = Utils.GetConfigPath(File_);
                return File.Exists(path) ? ById(File.ReadAllText(path).Trim()) : null;
            }
            catch
            {
                return null;
            }
        }
    }

    public static void SetCurrent(string? id)
    {
        try
        {
            var path = Utils.GetConfigPath(File_);
            if (ById(id) == null)
            {
                File.Delete(path);
            }
            else
            {
                File.WriteAllText(path, id);
            }
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(CountryProfiles), ex);
        }
    }
}
