package com.v2ray.ang.net

/**
 * Three one-tap modes for the home screen. Each is a base rule profile plus a few rules at the top of the list:
 *  - GAMES: Russian sites direct, the rest through the server; Steam, CS2, Faceit, Dota 2 and torrents go direct (lowest ping),
 *    Discord (calls included) goes through the server;
 *  - SOCIAL: everything direct except video and social networks (banks, state services, shops and Yandex are never touched);
 *  - FULL: everything through the server.
 * Only plain "domain:" names are used: a name that is missing from the bundled geosite file would stop the core from starting.
 */
object ModePresets {
    enum class Mode(val key: String) { FULL("full"), GAMES("games"), SOCIAL("social") }

    fun byKey(key: String?): Mode? = Mode.entries.firstOrNull { it.key == key }

    val GAMES_DIRECT_DOMAINS = listOf(
        "domain:steampowered.com", "domain:steamcommunity.com", "domain:steamcontent.com", "domain:steamstatic.com",
        "domain:steamgames.com", "domain:steamusercontent.com", "domain:steamserver.net", "domain:valvesoftware.com", "domain:valve.net",
        "domain:faceit.com", "domain:faceit-cdn.net", "domain:dota2.com", "domain:counter-strike.net",
    )

    val DISCORD_DOMAINS = listOf(
        "domain:discord.com", "domain:discord.gg", "domain:discordapp.com", "domain:discordapp.net",
        "domain:discord.media", "domain:discordcdn.com", "domain:discord.gift", "domain:discordstatus.com",
    )

    val SOCIAL_DOMAINS = listOf(
        "domain:youtube.com", "domain:youtu.be", "domain:googlevideo.com", "domain:ytimg.com", "domain:ggpht.com",
        "domain:youtube-nocookie.com", "domain:youtubekids.com",
        "domain:instagram.com", "domain:cdninstagram.com", "domain:facebook.com", "domain:fbcdn.net",
        "domain:tiktok.com", "domain:tiktokcdn.com", "domain:tiktokv.com", "domain:byteoversea.com", "domain:ibytedtos.com",
        "domain:twitter.com", "domain:x.com", "domain:twimg.com",
        "domain:t.me", "domain:telegram.org", "domain:telegram.me",
    ) + DISCORD_DOMAINS

    /** One rule of a mode, before it becomes a ruleset item: [outbound] is "proxy" or "direct". */
    data class Rule(val id: String, val remarks: String, val outbound: String, val domain: List<String>? = null, val protocol: List<String>? = null)

    const val PROXY = "proxy"
    const val DIRECT = "direct"

    /** The rules a mode puts at the top, in order. FULL needs none. */
    fun rules(mode: Mode?): List<Rule> = when (mode) {
        Mode.GAMES -> listOf(
            Rule("fv-mode-games", "FlowVeil: игры напрямую", DIRECT, domain = GAMES_DIRECT_DOMAINS),
            Rule("fv-mode-torrent", "FlowVeil: торренты напрямую", DIRECT, protocol = listOf("bittorrent")),
            Rule("fv-mode-discord", "FlowVeil: Discord через сервер", PROXY, domain = DISCORD_DOMAINS),
        )
        Mode.SOCIAL -> listOf(Rule("fv-mode-social", "FlowVeil: видео и соцсети через сервер", PROXY, domain = SOCIAL_DOMAINS))
        Mode.FULL, null -> emptyList()
    }

    val ruleIds: Set<String> = setOf("fv-mode-games", "fv-mode-torrent", "fv-mode-discord", "fv-mode-social")
}
