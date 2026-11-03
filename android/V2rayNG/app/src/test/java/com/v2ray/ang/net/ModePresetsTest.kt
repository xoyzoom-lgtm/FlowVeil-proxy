package com.v2ray.ang.net

import com.v2ray.ang.net.ModePresets.DIRECT
import com.v2ray.ang.net.ModePresets.Mode
import com.v2ray.ang.net.ModePresets.PROXY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModePresetsTest {
    @Test
    fun gamesGoDirectAndDiscordGoesThroughTheServer() {
        val rules = ModePresets.rules(Mode.GAMES)
        val games = rules.first { it.id == "fv-mode-games" }
        assertEquals(DIRECT, games.outbound)
        assertTrue("domain:steampowered.com" in games.domain.orEmpty())
        assertTrue("domain:faceit.com" in games.domain.orEmpty())
        assertEquals(listOf("bittorrent"), rules.first { it.id == "fv-mode-torrent" }.protocol)
        val discord = rules.first { it.id == "fv-mode-discord" }
        assertEquals(PROXY, discord.outbound)
        assertTrue("domain:discord.media" in discord.domain.orEmpty())
    }

    @Test
    fun socialSendsOnlyVideoAndNetworksThroughTheServer() {
        val rule = ModePresets.rules(Mode.SOCIAL).single()
        assertEquals(PROXY, rule.outbound)
        assertTrue("domain:youtube.com" in rule.domain.orEmpty())
        assertTrue("domain:tiktok.com" in rule.domain.orEmpty())
        val banks = listOf("sberbank", "tbank", "tinkoff", "gosuslugi", "ozon", "wildberries", "yandex")
        assertTrue(rule.domain.orEmpty().none { d -> banks.any { d.contains(it) } })
    }

    @Test
    fun fullModeAndNoModeNeedNoExtraRules() {
        assertTrue(ModePresets.rules(Mode.FULL).isEmpty())
        assertTrue(ModePresets.rules(null).isEmpty())
    }

    @Test
    fun everyRuleIsKnownAndOnlyUsesPlainDomainNames() {
        Mode.entries.flatMap { ModePresets.rules(it) }.forEach { r ->
            assertTrue(r.id in ModePresets.ruleIds)
            r.domain.orEmpty().forEach { assertTrue(it, it.startsWith("domain:")) }
        }
    }

    @Test
    fun keysRoundTrip() {
        Mode.entries.forEach { assertEquals(it, ModePresets.byKey(it.key)) }
        assertNull(ModePresets.byKey(""))
        assertNull(ModePresets.byKey(null))
    }
}
