package com.v2ray.ang.net

import com.v2ray.ang.net.LanShare.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanShareTest {
    @Test
    fun privateRanges() {
        assertTrue(LanShare.isPrivateV4("192.168.1.5"))
        assertTrue(LanShare.isPrivateV4("10.0.0.7"))
        assertTrue(LanShare.isPrivateV4("172.16.0.1"))
        assertTrue(LanShare.isPrivateV4("172.31.255.1"))
        assertFalse(LanShare.isPrivateV4("172.32.0.1"))
        assertFalse(LanShare.isPrivateV4("8.8.8.8"))
        assertFalse(LanShare.isPrivateV4("100.64.1.1"))
        assertFalse(LanShare.isPrivateV4("192.168.1"))
        assertFalse(LanShare.isPrivateV4("192.168.1.300"))
        assertFalse(LanShare.isPrivateV4("fe80::1"))
    }

    @Test
    fun wifiBeatsCableBeatsHotspotAndMobileNeverCounts() {
        val all = listOf(
            Candidate("rmnet_data0", "10.20.30.40"),
            Candidate("tun0", "10.0.0.2"),
            Candidate("ap0", "192.168.43.1"),
            Candidate("eth0", "192.168.0.9"),
            Candidate("wlan0", "192.168.1.5"),
        )
        assertEquals("192.168.1.5", LanShare.pickAddress(all))
        assertEquals("192.168.0.9", LanShare.pickAddress(all.filter { it.iface != "wlan0" }))
        assertEquals("192.168.43.1", LanShare.pickAddress(all.filter { it.iface == "ap0" }))
        assertNull(LanShare.pickAddress(all.filter { it.iface == "rmnet_data0" || it.iface == "tun0" }))
        assertNull(LanShare.pickAddress(listOf(Candidate("wlan0", "8.8.8.8"))))
        assertNull(LanShare.pickAddress(emptyList()))
    }

    @Test
    fun passwordHasFixedLengthAndNoLookAlikes() {
        var n = 0
        val p = LanShare.newPassword { bound -> (n++ * 7) % bound }
        assertEquals(12, p.length)
        assertTrue(p.none { it in "0O1lI" })
    }

    @Test
    fun uriCarriesLoginPasswordAddressAndPort() {
        assertEquals("socks5://flowveil:abc@192.168.1.5:10808", LanShare.proxyUri(LanShare.USER, "abc", "192.168.1.5", 10808))
    }
}
