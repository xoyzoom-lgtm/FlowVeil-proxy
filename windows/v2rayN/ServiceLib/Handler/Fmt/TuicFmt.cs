namespace ServiceLib.Handler.Fmt;

public class TuicFmt : BaseFmt
{
    public static ProfileItem? Resolve(string str, out string msg)
    {
        msg = ResUI.ConfigurationFormatIncorrect;

        ProfileItem item = new()
        {
            ConfigType = EConfigType.TUIC
        };

        var url = Utils.TryUri(str);
        if (url == null)
        {
            return null;
        }

        item.Address = url.IdnHost;
        // The scheme has no default port: an omitted one arrives as -1, TUIC servers listen on 443 by default.
        item.Port = url.Port == -1 ? 443 : url.Port;
        item.Remarks = url.GetComponents(UriComponents.Fragment, UriFormat.Unescaped);
        var rawUserInfo = Utils.UrlDecode(url.UserInfo);
        var userInfoParts = rawUserInfo.Split(new[] { ':' }, 2);
        if (userInfoParts.Length == 2)
        {
            item.Username = userInfoParts.First();
            item.Password = userInfoParts.Last();
        }

        var query = Utils.ParseQueryString(url.Query);
        ResolveUriQuery(query, ref item);
        if (GetQueryValue(query, "allow_insecure") == "1")
        {
            item.AllowInsecure = Global.StringTrue;
        }
        var congestion = GetQueryValue(query, "congestion_control");
        if (congestion.IsNullOrEmpty())
        {
            congestion = GetQueryValue(query, "congestion-control");
        }
        var udpRelayMode = GetQueryValue(query, "udp_relay_mode");
        if (udpRelayMode.IsNullOrEmpty())
        {
            udpRelayMode = GetQueryValue(query, "udp-relay-mode");
        }
        item.SetProtocolExtra(item.GetProtocolExtra() with
        {
            CongestionControl = congestion,
            UdpRelayMode = udpRelayMode
        });

        return item;
    }

    public static string? ToUri(ProfileItem? item)
    {
        if (item == null)
        {
            return null;
        }

        var remark = string.Empty;
        if (item.Remarks.IsNotEmpty())
        {
            remark = "#" + Utils.UrlEncode(item.Remarks);
        }

        var dicQuery = new Dictionary<string, string>();
        ToUriQueryLite(item, ref dicQuery);
        if (item.GetAllowInsecure())
        {
            dicQuery.Add("allow_insecure", "1");
        }
        if (!item.GetProtocolExtra().CongestionControl.IsNullOrEmpty())
        {
            dicQuery.Add("congestion_control", item.GetProtocolExtra().CongestionControl);
        }
        if (!item.GetProtocolExtra().UdpRelayMode.IsNullOrEmpty())
        {
            dicQuery.Add("udp_relay_mode", item.GetProtocolExtra().UdpRelayMode);
        }

        return ToUri(EConfigType.TUIC, item.Address, item.Port, $"{item.Username ?? ""}:{item.Password}", dicQuery, remark);
    }
}
