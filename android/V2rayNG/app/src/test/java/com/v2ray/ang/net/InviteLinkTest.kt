package com.v2ray.ang.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InviteLinkTest {

    @Test
    fun encodedUrlWithoutName() {
        val i = InviteLink.parse("flowveil://add?url=https%3A%2F%2Fp.example%2Fsub%2Fabc")!!
        assertEquals("https://p.example/sub/abc", i.link)
        assertNull(i.name)
    }

    @Test
    fun nameBecomesFragment() {
        val i = InviteLink.parse("flowveil://add?url=https%3A%2F%2Fp.example%2Fsub%2Fabc&name=%D0%9C%D0%BE%D0%B9%20%D0%BF%D1%80%D0%BE%D0%B2%D0%B0%D0%B9%D0%B4%D0%B5%D1%80")!!
        assertEquals("Мой провайдер", i.name)
        assertEquals("https://p.example/sub/abc#%D0%9C%D0%BE%D0%B9%20%D0%BF%D1%80%D0%BE%D0%B2%D0%B0%D0%B9%D0%B4%D0%B5%D1%80", i.link)
    }

    @Test
    fun nameBeforeUrl() {
        val i = InviteLink.parse("flowveil://add?name=Net&url=https%3A%2F%2Fp.example%2Fs")!!
        assertEquals("Net", i.name)
        assertEquals("https://p.example/s#Net", i.link)
    }

    @Test
    fun pathForm() {
        val i = InviteLink.parse("flowveil://add/https://p.example/sub/abc")!!
        assertEquals("https://p.example/sub/abc", i.link)
    }

    @Test
    fun fragmentOfTheInviteIsTheName() {
        val i = InviteLink.parse("flowveil://add?url=https%3A%2F%2Fp.example%2Fs#My%20Net")!!
        assertEquals("My Net", i.name)
    }

    @Test
    fun installSubAndV2rayng() {
        assertEquals("https://p.example/s", InviteLink.parse("flowveil://install-sub?url=https%3A%2F%2Fp.example%2Fs")!!.link)
        assertEquals("https://p.example/s", InviteLink.parse("v2rayng://install-sub?url=https%3A%2F%2Fp.example%2Fs")!!.link)
    }

    @Test
    fun nothingToImport() {
        assertNull(InviteLink.parse("flowveil://add"))
        assertNull(InviteLink.parse("flowveil://add?name=Only"))
        assertNull(InviteLink.parse("hello"))
    }

    @Test
    fun nameIsCleaned() {
        assertEquals("A B", InviteLink.cleanName("  A\u0000\n   B  "))
        assertNull(InviteLink.cleanName("   "))
        assertEquals(InviteLink.MAX_NAME, InviteLink.cleanName("x".repeat(200))!!.length)
    }

    @Test
    fun plusStaysPlus() {
        assertEquals("https://p.example/s?t=a+b", InviteLink.parse("flowveil://add?url=https%3A%2F%2Fp.example%2Fs%3Ft%3Da%2Bb")!!.link)
    }

    @Test
    fun serverLinkKeepsItsFragment() {
        val i = InviteLink.parse("v2rayng://install-config?url=vless%3A%2F%2Fid%40h%3A443%3Ftype%3Dtcp%23My%20Server")!!
        assertEquals("vless://id@h:443?type=tcp#My Server", i.link)
        assertNull(i.name)
    }
}
