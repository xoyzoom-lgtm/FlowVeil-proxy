package com.v2ray.ang.net

import com.v2ray.ang.net.RedirectTarget.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class RedirectTargetTest {
    private val base = "https://panel.example/sub/abc/"

    @Test
    fun ordinaryAndRelative() {
        assertEquals(Kind.Follow("https://other.example/x"), RedirectTarget.classify(base, "https://other.example/x"))
        assertEquals(Kind.Follow("https://panel.example/sub/abc/json"), RedirectTarget.classify(base, "json"))
        assertEquals(Kind.Follow("https://panel.example/z"), RedirectTarget.classify(base, "/z"))
    }

    @Test
    fun wrappedAddressIsFollowed() {
        assertEquals(Kind.Follow("https://a.example/s"), RedirectTarget.classify(base, "v2rayng://install-sub?url=https%3A%2F%2Fa.example%2Fs"))
        assertEquals(Kind.Follow("https://a.example/s"), RedirectTarget.classify(base, "happ://add/https://a.example/s"))
    }

    @Test
    fun encryptedAndOtherAppLinks() {
        assertEquals(Kind.HappEncrypted, RedirectTarget.classify(base, "happ://crypt3/AAAA"))
        assertEquals(Kind.AppLink("clash"), RedirectTarget.classify(base, "clash://install-config?url=abc"))
        assertEquals(Kind.AppLink("sing-box"), RedirectTarget.classify(base, "sing-box://import-remote-profile?url=abc"))
    }

    @Test
    fun invalid() {
        assertEquals(Kind.Invalid, RedirectTarget.classify(base, ""))
        assertEquals(Kind.Invalid, RedirectTarget.classify(base, "https://"))
        assertEquals(Kind.Invalid, RedirectTarget.classify(base, "https://a.example/\n x"))
    }
}
