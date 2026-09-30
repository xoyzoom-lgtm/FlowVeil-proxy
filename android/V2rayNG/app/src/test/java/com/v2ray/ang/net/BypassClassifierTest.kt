package com.v2ray.ang.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BypassClassifierTest {
    private val masks = listOf("yandex.ru", "sberbank.ru", "vk.com")

    private fun rate(f: ServerFacts, history: HistoryFacts? = null, hint: ListHint = ListHint(false), extra: List<String> = emptyList()) =
        BypassClassifier.rate(f, masks, extra, hint, history)

    @Test
    fun normalizeStripsEmojiPunctuationAndYo() {
        assertEquals("белые списки 3", BypassClassifier.normalize("🇷🇺  Белые-списки ▪ 3 🔥"))
        assertEquals("елка", BypassClassifier.normalize("Ёлка"))
        assertEquals("wi fi", BypassClassifier.normalize("Wi-Fi"))
    }

    @Test
    fun providerLabelMakesAServerStrong() {
        val r = rate(ServerFacts("Белые списки 1", "vless", "tcp", "tls"))
        assertEquals(BypassLevel.STRONG, r.level)
        assertTrue(r.reasons.any { it.code == "name_label" })
        assertEquals(BypassLevel.STRONG, rate(ServerFacts("🇷🇺 WL | Москва", "vless", "tcp", "tls")).level)
        assertEquals(BypassLevel.STRONG, rate(ServerFacts("Obhod #2", "vless", "tcp", "tls")).level)
    }

    @Test
    fun realityVisionWithAllowedSniIsStrong() {
        val r = rate(ServerFacts("Германия", "vless", "tcp", "reality", sni = "www.yandex.ru", flow = "xtls-rprx-vision"))
        assertEquals(BypassLevel.STRONG, r.level)
        assertTrue(r.reasons.any { it.code == "mask_sni" })
        assertTrue(r.reasons.any { it.code == "reality_vision" })
    }

    @Test
    fun realityVisionAloneIsLikely() {
        val r = rate(ServerFacts("Германия", "vless", "tcp", "reality", sni = "www.microsoft.com", flow = "xtls-rprx-vision"))
        assertEquals(BypassLevel.LIKELY, r.level)
    }

    @Test
    fun gamingUdpServerIsNotAnAutoCandidate() {
        val r = rate(ServerFacts("🇳🇱 Нидерланды | Игровой 🔥", "hysteria2", "", "tls"))
        assertEquals(BypassLevel.UNLIKELY, r.level)
        assertTrue(r.reasons.any { it.code == "name_gaming" })
    }

    @Test
    fun unknownTlsServerWithoutMarkersIsWeak() {
        assertEquals(BypassLevel.WEAK, rate(ServerFacts("Франция 12", "trojan", "tcp", "tls")).level)
    }

    @Test
    fun plainProxyWithoutTlsIsUnlikely() {
        assertEquals(BypassLevel.UNLIKELY, rate(ServerFacts("Финляндия", "vless", "tcp", "none")).level)
    }

    @Test
    fun wifiOnlyLabelPushesAServerDown() {
        val r = rate(ServerFacts("Только Wi-Fi | Швеция", "vless", "tcp", "reality", flow = "xtls-rprx-vision"))
        assertTrue(r.score < BypassData.LIKELY_AT)
        assertTrue(r.reasons.any { it.code == "name_only_wifi" })
    }

    @Test
    fun mobileMarkerAndOperatorNamesCount() {
        assertTrue(rate(ServerFacts("МТС мобильный 1", "vless", "tcp", "tls")).level <= BypassLevel.LIKELY)
        assertEquals(BypassLevel.LIKELY, rate(ServerFacts("LTE Москва", "vless", "tcp", "tls")).level)
    }

    @Test
    fun shortMarkersNeedAWholeWord() {
        // "wl" must not fire inside "Newland", "lte" not inside "Malte".
        assertFalse(rate(ServerFacts("Newland", "vless", "tcp", "tls")).reasons.any { it.code.startsWith("name_") })
        assertFalse(rate(ServerFacts("Malte", "vless", "tcp", "tls")).reasons.any { it.code.startsWith("name_") })
    }

    @Test
    fun cdnTransportWithAllowedHostGetsAMaskBonus() {
        val r = rate(ServerFacts("Server", "vless", "ws", "tls", sni = "cdn.example.org", host = "static.sberbank.ru"))
        assertTrue(r.reasons.any { it.code == "mask_host" })
        assertTrue(r.reasons.any { it.code == "cdn_transport" })
    }

    @Test
    fun domainMatchIsSuffixOnly() {
        assertTrue(BypassClassifier.matchesDomain("api.vk.com", masks))
        assertTrue(BypassClassifier.matchesDomain("VK.COM.", masks))
        assertFalse(BypassClassifier.matchesDomain("notvk.com", masks))
        assertFalse(BypassClassifier.matchesDomain("", masks))
    }

    @Test
    fun historyDecidesMore() {
        val f = ServerFacts("Германия", "vless", "tcp", "tls")
        assertEquals(BypassLevel.STRONG, rate(f, HistoryFacts(60_000L, badNow = false)).level)
        assertTrue(rate(f, HistoryFacts(null, badNow = true)).score < 0)
        // a failure wins over an old success
        assertTrue(rate(f, HistoryFacts(3_600_000L, badNow = true)).reasons.any { it.code == "history_bad" })
    }

    @Test
    fun announcementCanPutBypassServersAtTheTail() {
        val hint = BypassClassifier.announceHint("Сервера \"Белые списки\" - в конце списка стран")
        assertTrue(hint.bypassAtTail)
        assertFalse(BypassClassifier.announceHint("Поддержка: @support").bypassAtTail)
        val f = ServerFacts("Server 50", "vless", "tcp", "tls", position = 45, listSize = 53)
        assertTrue(rate(f, hint = hint).reasons.any { it.code == "list_tail" })
        assertFalse(rate(f.copy(position = 3)).reasons.any { it.code == "list_tail" })
    }

    @Test
    fun userLabelsWork() {
        val f = ServerFacts("Мост Тверь", "vless", "tcp", "tls")
        assertTrue(rate(f, extra = listOf("тверь")).level == BypassLevel.STRONG)
    }

    @Test
    fun customJsonProfilesAreNeutralToSlightlyPositive() {
        val r = rate(ServerFacts("Some config", "custom"))
        assertEquals(BypassLevel.WEAK, r.level)
    }
}
