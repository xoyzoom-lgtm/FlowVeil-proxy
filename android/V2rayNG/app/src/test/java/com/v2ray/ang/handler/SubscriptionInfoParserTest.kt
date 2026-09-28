package com.v2ray.ang.handler

import com.v2ray.ang.dto.entities.SubscriptionItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionInfoParserTest {

    @Test
    fun parsesFullUserInfo() {
        val info = SubscriptionInfoParser.parseUserInfo("upload=100; download=900; total=5000; expire=1700000000")
        assertEquals(SubscriptionInfoParser.UserInfo(1000L, 5000L, 1_700_000_000_000L), info)
    }

    @Test
    fun zeroTotalAndExpireMeanUnlimited() {
        val info = SubscriptionInfoParser.parseUserInfo("upload=0;download=42;total=0;expire=0")
        assertEquals(SubscriptionInfoParser.UserInfo(42L, null, null), info)
    }

    @Test
    fun ignoresMalformedFieldsAndAcceptsDecimals() {
        val info = SubscriptionInfoParser.parseUserInfo("upload=abc; download=1.5e3; junk; total=")
        assertEquals(SubscriptionInfoParser.UserInfo(1500L, null, null), info)
    }

    @Test
    fun emptyUserInfoReturnsNull() {
        assertNull(SubscriptionInfoParser.parseUserInfo(""))
        assertNull(SubscriptionInfoParser.parseUserInfo("no fields here"))
    }

    @Test
    fun decodesBase64PrefixedValues() {
        // "Мой VPN" in UTF-8, base64 encoded
        assertEquals("Мой VPN", SubscriptionInfoParser.decodeMaybeBase64("base64:0JzQvtC5IFZQTg=="))
        assertEquals("Plain title", SubscriptionInfoParser.decodeMaybeBase64("  Plain title "))
        assertEquals("", SubscriptionInfoParser.decodeMaybeBase64("base64:@@@"))
    }

    @Test
    fun applyFillsItemAndRejectsUnsafeSupportUrl() {
        val item = SubscriptionItem()
        SubscriptionInfoParser.apply(
            item,
            mapOf(
                "subscription-userinfo" to "upload=1; download=2; total=10; expire=5",
                "profile-title" to "My Provider",
                "announce" to "Hello",
                "support-url" to "javascript:alert(1)",
            )
        )
        assertEquals(3L, item.trafficUsed)
        assertEquals(10L, item.trafficTotal)
        assertEquals(5000L, item.expireAt)
        assertEquals("My Provider", item.profileTitle)
        assertEquals("Hello", item.announce)
        assertNull(item.supportUrl)
    }

    @Test
    fun applyWithoutHeadersKeepsExistingValues() {
        val item = SubscriptionItem(profileTitle = "Old", trafficTotal = 7L)
        SubscriptionInfoParser.apply(item, emptyMap())
        assertEquals("Old", item.profileTitle)
        assertEquals(7L, item.trafficTotal)
    }
}
