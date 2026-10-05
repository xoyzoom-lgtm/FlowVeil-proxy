package com.v2ray.ang.net

import com.v2ray.ang.net.ImportPreview.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportPreviewTest {
    private fun of(t: String) = ImportPreview.of(t)

    @Test
    fun aSubscriptionShowsItsHost() {
        val p = of("https://Sub.Provider.example/api/sub/abc?x=1")
        assertEquals(Kind.SUBSCRIPTION, p.kind)
        assertEquals("sub.provider.example", p.host)
        assertFalse(p.insecure)
        assertTrue(of("http://1.2.3.4:2096/sub/x").insecure)
    }

    @Test
    fun idnHostsAreShownInBothForms() {
        val p = of("https://xn--e1afmkfd.xn--p1ai/sub")
        assertEquals("пример.рф", p.host)
        assertEquals("xn--e1afmkfd.xn--p1ai", p.hostAscii)
        assertFalse(p.lookAlike)
    }

    @Test
    fun aLookAlikeHostIsFlagged() {
        // Latin "pple" with a Cyrillic "а"
        assertTrue(of("https://аpple.com/sub").lookAlike)
        assertTrue(ImportPreview.mixedScripts("pаypal.com"))
        assertFalse(ImportPreview.mixedScripts("paypal.com"))
    }

    @Test
    fun serverLinksAreCounted() {
        val p = of("vless://id@de.example:443?type=tcp#DE\nvmess://eyJ2IjoyfQ==\ntrojan://pw@[2001:db8::1]:443#v6")
        assertEquals(Kind.SERVERS, p.kind)
        assertEquals(3, p.servers)
        assertEquals("de.example", p.host)
    }

    @Test
    fun dangerousOrBrokenInputIsRejected() {
        listOf(
            "javascript:alert(1)", "file:///sdcard/x", "intent://scan#Intent;end", "content://x/y",
            "https://a.example/x%0d%0aSet-Cookie", "https://a.example/ x", "https://a.example/\u0000",
            "flowveil://add?url=flowveil%3A%2F%2Fadd", "https://a.example/flowveil://add",
            "https://" + "a".repeat(2100) + ".example", "", "   ", "hello world",
        ).forEach { assertEquals(it.take(40), Kind.REJECTED, of(it).kind) }
    }

    @Test
    fun aDoubleEncodedLinkIsJustAnOddPathNotASecondLink() {
        val p = of("https://a.example/sub%252Fx")
        assertEquals(Kind.SUBSCRIPTION, p.kind)
        assertEquals("a.example", p.host)
    }
}
