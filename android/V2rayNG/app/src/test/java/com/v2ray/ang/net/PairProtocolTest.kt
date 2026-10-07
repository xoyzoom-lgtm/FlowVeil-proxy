package com.v2ray.ang.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairProtocolTest {
    private val key = ByteArray(32) { it.toByte() }
    private val nonce = ByteArray(12) { (0xA0 + it).toByte() }
    private val plain = "{\"type\":\"subscription\",\"items\":[\"https://p.example/s\"]}"

    /** Same vector as PairTests.Crypto_SharedVector on Windows (also produced independently by plain JCE). */
    @Test
    fun sharedVector() {
        val wire = PairProtocol.encrypt(key, "sid-test", plain, nonce)
        assertEquals("oKGio6SlpqeoqaqrnToIVDWuIIVAFvKxdBmytwDYMH_8lW5O9XpD6wyJT1rwHjOL31FpEnDsKq1xG-6JK35pO0CNZ9Ao1PBK-WF-h3Qw15V5oUo", wire)
        assertEquals(plain, PairProtocol.decrypt(key, "sid-test", wire))
        assertNull(PairProtocol.decrypt(key, "other", wire))
    }

    @Test
    fun qrRoundTrip() {
        val url = "http://192.168.1.10:5123/p/SID123#t=TOK&k=${PairProtocol.b64Encode(key)}&v=1"
        val qr = PairProtocol.parseQr(url)
        assertNotNull(qr)
        assertEquals("192.168.1.10", qr!!.host)
        assertEquals(5123, qr.port)
        assertEquals("SID123", qr.sid)
        assertEquals("TOK", qr.token)
        assertTrue(qr.key.contentEquals(key))
    }

    @Test
    fun wrappedQrIsUnderstood() {
        val plain = "http://192.168.1.10:5123/p/SID123#t=TOK&k=${PairProtocol.b64Encode(key)}&v=1"
        val wrapped = "flowveil://pair?q=" + java.net.URLEncoder.encode(plain, "UTF-8")
        val qr = PairProtocol.parseQr(wrapped)
        assertNotNull(qr)
        assertEquals("SID123", qr!!.sid)
        assertEquals("TOK", qr.token)
        assertTrue(PairProtocol.looksLikePair(wrapped))
    }

    @Test
    fun looksLikePairCatchesBrokenCodes() {
        assertTrue(PairProtocol.looksLikePair("http://192.168.1.10:5123/p/abc#t=1&k=AAAA&v=1"))
        assertFalse(PairProtocol.looksLikePair("http://192.168.1.10/p/my-subscription"))
        assertTrue(PairProtocol.looksLikePair("flowveil://pair?x=1"))
        assertFalse(PairProtocol.looksLikePair("http://example.com/p/abc"))
        assertFalse(PairProtocol.looksLikePair("https://sub.example/s/abc"))
        assertFalse(PairProtocol.looksLikePair("vless://abc"))
        assertFalse(PairProtocol.looksLikePair(null))
    }

    @Test
    fun qrRejectsForeign() {
        assertNull(PairProtocol.parseQr("https://example.com/p/a#t=1&k=AAAA&v=1"))
        assertNull(PairProtocol.parseQr("flowveil://pair"))
        assertNull(PairProtocol.parseQr("flowveil://pair?q="))
        assertNull(PairProtocol.parseQr("http://192.168.1.10:5123/p/a"))
        assertNull(PairProtocol.parseQr("http://192.168.1.10:5123/x/a#t=1&k=${PairProtocol.b64Encode(key)}&v=1"))
        assertNull(PairProtocol.parseQr("http://192.168.1.10:5123/p/a#t=1&k=AAAA&v=1"))
        assertNull(PairProtocol.parseQr("vless://abc"))
    }

    @Test
    fun privateHosts() {
        assertTrue(PairProtocol.isPrivateHost("192.168.0.5"))
        assertTrue(PairProtocol.isPrivateHost("10.1.2.3"))
        assertTrue(PairProtocol.isPrivateHost("172.20.0.1"))
        assertFalse(PairProtocol.isPrivateHost("8.8.8.8"))
        assertFalse(PairProtocol.isPrivateHost("172.32.0.1"))
        assertFalse(PairProtocol.isPrivateHost("example.com"))
    }

    @Test
    fun linkWhitelist() {
        assertTrue(PairProtocol.isAllowedLink("https://p.example/sub/x"))
        assertTrue(PairProtocol.isAllowedLink("vless://id@host:443#n"))
        assertTrue(PairProtocol.isAllowedLink("flowveil://add?url=x"))
        assertFalse(PairProtocol.isAllowedLink("flowveil://other"))
        assertFalse(PairProtocol.isAllowedLink("javascript:alert(1)"))
        assertFalse(PairProtocol.isAllowedLink("https://a.example/x y"))
    }

    @Test
    fun payloadJsonEscapes() {
        assertEquals("{\"type\":\"subscription\",\"items\":[\"https://p.example/a\"],\"name\":\"a\\\"b\"}", PairProtocol.payloadJson(listOf("https://p.example/a"), "a\"b"))
    }

    @Test
    fun base64RoundTrip() {
        for (n in 0..40) {
            val data = ByteArray(n) { (it * 7).toByte() }
            assertTrue(PairProtocol.b64Decode(PairProtocol.b64Encode(data))!!.contentEquals(data))
        }
    }
}
